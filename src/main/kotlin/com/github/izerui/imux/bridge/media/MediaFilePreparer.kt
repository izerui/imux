package com.github.izerui.imux.bridge.media

import com.github.izerui.imux.bridge.core.MediaCapability
import com.github.izerui.imux.bridge.core.MediaKind
import com.github.izerui.imux.bridge.core.MediaRejected
import com.github.izerui.imux.bridge.core.PreparedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.coroutineContext

class MediaFilePreparer(private val tempDir: Path? = null) {
    suspend fun prepare(
        rawPath: String,
        name: String?,
        maxBytes: Long,
        durationMillis: Long? = null,
    ): PreparedMedia = withContext(Dispatchers.IO) {
        val source = try {
            Path.of(rawPath).also { if (!it.isAbsolute) throw MediaRejected("absolute_path_required", canFallback = false) }.toRealPath()
        } catch (e: MediaRejected) {
            throw e
        } catch (_: Exception) {
            throw MediaRejected("file_not_found", canFallback = false)
        }
        val before = Files.readAttributes(source, BasicFileAttributes::class.java)
        if (!before.isRegularFile || !Files.isReadable(source)) throw MediaRejected("file_not_readable", canFallback = false)
        if (before.size() > maxBytes) throw MediaRejected("too_large", canFallback = false)
        val target = if (tempDir == null) Files.createTempFile("imux-media-", ".part")
        else Files.createTempFile(tempDir, "imux-media-", ".part")
        var retained = false
        try {
            val context = coroutineContext
            runInterruptible {
                Files.newInputStream(source).use { input ->
                    Files.newOutputStream(target).use { output ->
                        val bytes = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            context.ensureActive()
                            val count = input.read(bytes)
                            if (count < 0) break
                            total += count
                            if (total > maxBytes) throw MediaRejected("too_large", canFallback = false)
                            output.write(bytes, 0, count)
                        }
                    }
                }
            }
            val after = Files.readAttributes(source, BasicFileAttributes::class.java)
            if (before.fileKey() != after.fileKey() || before.size() != after.size() ||
                before.lastModifiedTime() != after.lastModifiedTime() || Files.size(target) != before.size()
            ) throw MediaRejected("file_changed", canFallback = false)
            val fileName = (name ?: source.fileName.toString()).substringAfterLast('/').substringAfterLast('\\')
                .filter { !it.isISOControl() }.take(180).ifBlank { "attachment" }
            val format = detectFormat(target, source.fileName.toString())
            retained = true
            PreparedMedia(target, fileName, before.size(), format, durationMillis)
        } finally {
            if (!retained) Files.deleteIfExists(target)
        }
    }

    private fun detectFormat(file: Path, originalName: String): String {
        val bytes = Files.newInputStream(file).use { it.readNBytes(16) }
        val text = String(bytes, Charsets.ISO_8859_1)
        return when {
            bytes.size >= 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte) -> "png"
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
            text.startsWith("GIF87a") || text.startsWith("GIF89a") -> "gif"
            text.startsWith("BM") -> "bmp"
            text.startsWith("RIFF") && text.drop(8).startsWith("WEBP") -> "webp"
            text.drop(4).startsWith("ftyp") && originalName.substringAfterLast('.').equals("mp4", true) -> "mp4"
            else -> originalName.substringAfterLast('.', "").lowercase().let { ext ->
                // 声明为图片/MP4 却没有对应文件头，不允许原生媒体发送；文件附件仍可保留原文件。
                if (ext in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "mp4")) "unknown" else ext
            }
        }
    }
}

fun checkMediaCapability(file: PreparedMedia, kind: MediaKind, capability: MediaCapability?) {
    if (capability?.supported != true) throw MediaRejected("unsupported")
    if (capability.maxBytes != null && file.size > capability.maxBytes) throw MediaRejected("too_large")
    if (capability.formats.isNotEmpty() && file.format !in capability.formats) throw MediaRejected("unsupported_format")
    if (kind == MediaKind.VIDEO) {
        if ("duration_ms" in capability.requiredFields && file.durationMillis == null) throw MediaRejected("missing_duration")
        if ("thumbnail_path" in capability.requiredFields && file.thumbnail == null) throw MediaRejected("missing_thumbnail")
    }
}

fun PreparedMedia.deleteSnapshot() {
    thumbnail?.deleteSnapshot()
    runCatching { Files.deleteIfExists(path) }
}
