package com.github.izerui.imux.bridge.core

import java.time.LocalTime

/** 入站附件的命名规则。纯函数，不碰文件系统。 */
object AttachmentNames {
    const val MAX_NAME_LENGTH = 120

    private const val ILLEGAL = "<>:\"|?*"

    /** 去掉路径部分、控制字符与 Windows 非法字符，去掉首尾空白和点；空名返回 null。 */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.filterNot { it.isISOControl() || it in ILLEGAL }.trim().trim('.').trim()
        if (cleaned.isEmpty()) return null
        return truncate(cleaned)
    }

    private fun truncate(name: String): String {
        if (name.length <= MAX_NAME_LENGTH) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0) name.substring(dot) else ""
        if (extension.length in 2..11) return name.substring(0, MAX_NAME_LENGTH - extension.length) + extension
        return name.substring(0, MAX_NAME_LENGTH)
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
            head.asciiAt(4, "ftyp") -> "mp4"
            else -> null
        }

    /** 平台没给文件名时的默认名，例如 `image-153012.png`。 */
    fun fallback(
        kind: AttachmentKind,
        time: LocalTime,
        extension: String?,
    ): String = "%s-%02d%02d%02d.%s".format(kind.name.lowercase(), time.hour, time.minute, time.second, extension ?: "bin")

    /** 有同名时依次尝试 `a-1.png`、`a-2.png`。 */
    fun unique(
        name: String,
        taken: (String) -> Boolean,
    ): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        val stem = dot?.let { name.substring(0, it) } ?: name
        val extension = dot?.let { name.substring(it) }.orEmpty()
        var index = 1
        while (true) {
            val candidate = "$stem-$index$extension"
            if (!taken(candidate)) return candidate
            index++
        }
    }

    private fun ByteArray.startsWith(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { (this[it].toInt() and 0xFF) == values[it] }

    private fun ByteArray.startsWithAscii(text: String): Boolean = asciiAt(0, text)

    private fun ByteArray.asciiAt(
        offset: Int,
        text: String,
    ): Boolean = size >= offset + text.length && text.indices.all { this[offset + it] == text[it].code.toByte() }
}
