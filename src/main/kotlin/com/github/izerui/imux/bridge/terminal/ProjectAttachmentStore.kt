package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.AttachmentNames
import com.github.izerui.imux.bridge.core.AttachmentStore
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import com.github.izerui.imux.bridge.core.InboundAttachment
import com.github.izerui.imux.bridge.core.PlaceResult
import com.github.izerui.imux.bridge.core.SessionTarget
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import kotlin.coroutines.cancellation.CancellationException

/**
 * 把附件存到绑定会话所在项目的 `.imux/attachments/<日期>/`：Agent 读取项目内文件不会触发权限确认。
 * 顺带把 `.imux/` 写进本地 `.git/info/exclude`，并清理过期的日期目录。
 */
class ProjectAttachmentStore(
    private val clock: () -> LocalDateTime = LocalDateTime::now,
    private val keepDays: Long = 7,
) : AttachmentStore {
    override suspend fun place(
        target: SessionTarget,
        attachment: InboundAttachment,
    ): PlaceResult =
        when (val fetched = attachment.fetch(AttachmentStore.MAX_BYTES)) {
            FetchResult.TooLarge -> PlaceResult.TooLarge
            is FetchResult.Failed -> PlaceResult.Failed(fetched.reason)
            is FetchResult.Ok ->
                try {
                    withContext(Dispatchers.IO) {
                        try {
                            save(Path.of(target.projectPath), attachment.kind, fetched.file)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LOG.warn("保存附件失败：${e.javaClass.simpleName}")
                            PlaceResult.Failed("save")
                        }
                    }
                } finally {
                    // 保存成功时临时文件已被移走；其余任何退出（失败、取消）都删掉解密后的临时文件
                    deleteQuietly(fetched.file.path)
                }
        }

    private fun save(
        root: Path,
        kind: AttachmentKind,
        file: FetchedFile,
    ): PlaceResult.Saved {
        val now = clock()
        val base = root.resolve(".imux").resolve("attachments")
        val dir = base.resolve(now.toLocalDate().toString())
        Files.createDirectories(dir)
        val sniffed = AttachmentNames.extensionOf(readHead(file.path))
        val name =
            AttachmentNames.sanitize(file.suggestedName)?.let { if ('.' !in it && sniffed != null) "$it.$sniffed" else it }
                ?: AttachmentNames.fallback(kind, now.toLocalTime(), sniffed)
        val dest = dir.resolve(AttachmentNames.unique(name) { Files.exists(dir.resolve(it)) })
        Files.move(file.path, dest)
        ensureGitExclude(root)
        cleanup(base, now.toLocalDate())
        return PlaceResult.Saved(root.relativize(dest).joinToString("/"))
    }

    private fun readHead(path: Path): ByteArray =
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(HEAD_BYTES)
            val read = input.readNBytes(buffer, 0, HEAD_BYTES)
            buffer.copyOf(read)
        }

    /** 尽力而为：附件已经保存，写 exclude 失败只记日志，不影响本次结果。 */
    private fun ensureGitExclude(root: Path) {
        try {
            val git = root.resolve(".git")
            if (!Files.isDirectory(git)) return
            val exclude = git.resolve("info").resolve("exclude")
            val lines = if (Files.exists(exclude)) Files.readAllLines(exclude) else emptyList()
            if (lines.any { it.trim() == EXCLUDE_LINE }) return
            Files.createDirectories(exclude.parent)
            val prefix = if (lines.isNotEmpty() && !Files.readString(exclude).endsWith("\n")) "\n" else ""
            Files.writeString(exclude, "$prefix$EXCLUDE_LINE\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: IOException) {
            LOG.warn("写入 git exclude 失败：${e.javaClass.simpleName}")
        }
    }

    /** 尽力清理：失败只记日志，不影响本次保存。 */
    private fun cleanup(
        base: Path,
        today: LocalDate,
    ) {
        val cutoff = today.minusDays(keepDays)
        try {
            Files.list(base).use { entries ->
                entries.filter(Files::isDirectory).forEach { dir ->
                    val date =
                        try {
                            LocalDate.parse(dir.fileName.toString())
                        } catch (_: DateTimeParseException) {
                            null
                        }
                    if (date != null && date.isBefore(cutoff)) deleteRecursively(dir)
                }
            }
        } catch (e: IOException) {
            LOG.warn("清理过期附件失败：${e.javaClass.simpleName}")
        }
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            LOG.warn("删除附件临时文件失败：${e.javaClass.simpleName}")
        }
    }

    private fun deleteRecursively(dir: Path) {
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private companion object {
        const val HEAD_BYTES = 16
        const val EXCLUDE_LINE = ".imux/"
        val LOG = logger<ProjectAttachmentStore>()
    }
}
