package com.github.izerui.imux.session

/**
 * 从一行 JSON 文本中按键取出字符串值。
 *
 * 为什么不用正则：形如 `"key"\s*:\s*"((?:[^"\\]|\\.)*)"` 的模式会按**被匹配值的长度**
 * 递归，值达到万级字符即 StackOverflowError。这不是理论风险——真实 codex 会话里
 * 一条用户消息的 text 值长 11860 字符，实测必炸；而同一文件 22KB 的 session_meta 行
 * 反而没事，因为 cwd 的值很短。决定深度的是值，不是行。
 *
 * 本实现是线性扫描，无递归，对任意长度的值都安全。
 * 只做「按键取字符串」这一件事，不是通用 JSON 解析器——会话文件动辄数 MB，
 * 为取一个标题去反序列化整行不划算。
 */
internal object JsonLineScanner {

    /**
     * 返回 `"key": "value"` 中已反转义的 value；找不到该键、或值不是字符串、
     * 或字符串未闭合时返回 null。
     */
    fun stringValue(line: String, key: String): String? {
        val marker = "\"$key\""
        var searchFrom = 0
        while (true) {
            val keyAt = line.indexOf(marker, searchFrom)
            if (keyAt < 0) return null

            val valueQuote = locateValueQuote(line, keyAt + marker.length)
            if (valueQuote >= 0) return readString(line, valueQuote)

            // 该处不是「键: 字符串」结构（例如出现在别的值里），继续往后找
            searchFrom = keyAt + marker.length
        }
    }

    /** 从键之后定位值的起始双引号；不是字符串值则返回 -1。 */
    private fun locateValueQuote(line: String, afterKey: Int): Int {
        var i = skipWhitespace(line, afterKey)
        if (i >= line.length || line[i] != ':') return -1
        i = skipWhitespace(line, i + 1)
        return if (i < line.length && line[i] == '"') i else -1
    }

    private fun skipWhitespace(line: String, from: Int): Int {
        var i = from
        while (i < line.length && line[i].isWhitespace()) i++
        return i
    }

    /** 从起始双引号处读一个 JSON 字符串并反转义。未闭合返回 null。 */
    private fun readString(line: String, quoteIndex: Int): String? {
        val sb = StringBuilder()
        var i = quoteIndex + 1
        while (i < line.length) {
            when (val c = line[i]) {
                '"' -> return sb.toString()

                '\\' -> {
                    if (i + 1 >= line.length) return null
                    when (val escaped = line[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'u' -> {
                            if (i + 5 >= line.length) return null
                            val code = line.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                            sb.append(code.toChar())
                            i += 4
                        }
                        // 包括 " \ / 三种，原样取字面量
                        else -> sb.append(escaped)
                    }
                    i++
                }

                else -> sb.append(c)
            }
            i++
        }
        return null
    }
}

/**
 * 读取文件的创建时间。
 *
 * macOS 上 creationTime 可靠（实测与会话首条记录的时间戳吻合）。
 * 某些文件系统不记录创建时间、返回纪元 0，此时退回最后修改时间。
 */
internal fun creationTimeOf(file: java.nio.file.Path): java.time.Instant {
    val attrs = java.nio.file.Files.readAttributes(
        file,
        java.nio.file.attribute.BasicFileAttributes::class.java,
    )
    val created = attrs.creationTime().toInstant()
    return if (created.epochSecond <= 0) attrs.lastModifiedTime().toInstant() else created
}
