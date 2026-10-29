package com.github.izerui.imux.bridge.core

import java.time.LocalTime

/** 入站附件的命名规则。纯函数，不碰文件系统。 */
object AttachmentNames {
    const val MAX_NAME_LENGTH = 120

    /** 常见文件系统单个文件名上限 255 字节，留出随机后缀 `-xxxx` 的余量。 */
    const val MAX_NAME_BYTES = 200

    private const val ILLEGAL = "<>:\"|?*"

    /**
     * 去掉路径部分、控制字符、Unicode 格式字符（如 U+202E，可用来伪装扩展名）与 Windows 非法字符，
     * 去掉首尾空白和点；空名返回 null。
     */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned =
            base.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() || it in ILLEGAL }
                .trim().trim('.').trim()
        if (cleaned.isEmpty()) return null
        return truncate(cleaned)
    }

    /** 同时限制字符数与 UTF-8 字节数；尽量保留扩展名，按码点截断，不拆开代理对。 */
    private fun truncate(name: String): String {
        if (name.length <= MAX_NAME_LENGTH && utf8Size(name) <= MAX_NAME_BYTES) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0) name.substring(dot) else ""
        val suffix = if (extension.length in 2..11) extension else ""
        val stem = if (suffix.isEmpty()) name else name.substring(0, dot)
        return takeWithin(stem, MAX_NAME_LENGTH - suffix.length, MAX_NAME_BYTES - utf8Size(suffix)) + suffix
    }

    private fun takeWithin(
        text: String,
        maxChars: Int,
        maxBytes: Int,
    ): String {
        var end = 0
        var bytes = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val chars = Character.charCount(codePoint)
            val size = utf8Size(codePoint)
            if (end + chars > maxChars || bytes + size > maxBytes) break
            end += chars
            bytes += size
        }
        return text.substring(0, end)
    }

    private fun utf8Size(text: String): Int = text.codePoints().map(::utf8Size).sum()

    private fun utf8Size(codePoint: Int): Int =
        when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }

    /** 按文件头识别扩展名；识别不了返回 null。[head] 至少给 12 字节才能识别全部类型。 */
    fun extensionOf(head: ByteArray): String? =
        when {
            head.startsWith(0x89, 0x50, 0x4E, 0x47) -> "png"
            head.startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
            head.startsWithAscii("GIF8") -> "gif"
            head.startsWithAscii("RIFF") && head.asciiAt(8, "WEBP") -> "webp"
            head.startsWithAscii("BM") -> "bmp"
            head.startsWithAscii("%PDF") -> "pdf"
            head.startsWith(0x50, 0x4B, 0x03, 0x04) -> "zip"
            head.startsWithAscii("OggS") -> "ogg"
            head.asciiAt(4, "ftyp") -> "mp4"
            else -> null
        }

    /** 平台没给文件名时的默认名，例如 `image-153012.png`。 */
    fun fallback(
        kind: AttachmentKind,
        time: LocalTime,
        extension: String?,
    ): String = "%s-%02d%02d%02d.%s".format(kind.name.lowercase(), time.hour, time.minute, time.second, extension ?: "bin")

    /** 在扩展名前加后缀，例如 `报告.pdf` 加 `k3f9` 得 `报告-k3f9.pdf`；没有扩展名或以点开头的整体作为主名。 */
    fun withSuffix(
        name: String,
        suffix: String,
    ): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        val stem = dot?.let { name.substring(0, it) } ?: name
        val extension = dot?.let { name.substring(it) }.orEmpty()
        return "$stem-$suffix$extension"
    }

    /** 4 位小写字母与数字：附件名可读，又让同名附件几乎不会撞名。 */
    fun randomSuffix(random: kotlin.random.Random = kotlin.random.Random.Default): String =
        String(CharArray(SUFFIX_LENGTH) { SUFFIX_CHARS[random.nextInt(SUFFIX_CHARS.length)] })

    private fun ByteArray.startsWith(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { (this[it].toInt() and 0xFF) == values[it] }

    private fun ByteArray.startsWithAscii(text: String): Boolean = asciiAt(0, text)

    private fun ByteArray.asciiAt(
        offset: Int,
        text: String,
    ): Boolean = size >= offset + text.length && text.indices.all { this[offset + it] == text[it].code.toByte() }

    private const val SUFFIX_LENGTH = 4
    private const val SUFFIX_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
}
