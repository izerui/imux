package com.github.izerui.imux.bridge.core

/**
 * 把正文切成不超过渠道上限的若干段，绝不截断。
 *
 * 优先在换行处切；单行超限时按码点切，不会把一个汉字劈成两半。
 * 切点落在 Markdown 代码块里时，本段末尾补上闭合围栏、下一段用原围栏头重开，
 * 否则手机端从切点往后会整段按代码渲染。
 */
object TextSegmenter {
    const val MIN_SEGMENT_BYTES = 64
    private const val FENCE = "```"
    private const val CLOSING = "\n" + FENCE

    fun split(
        text: String,
        maxBytes: Int,
    ): List<String> {
        require(maxBytes >= MIN_SEGMENT_BYTES) { "maxBytes 过小：$maxBytes" }
        if (bytes(text) <= maxBytes) return listOf(text)

        // 任何一段都可能需要补闭合围栏，统一预留
        val budget = maxBytes - bytes(CLOSING)
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var currentBytes = 0
        var carriedPrefixLength = 0
        var openFence: String? = null

        fun flush() {
            if (openFence != null) current.append(CLOSING)
            out += current.toString()
            current.setLength(0)
            currentBytes = 0
            carriedPrefixLength = 0
            openFence?.let { header ->
                current.append(header)
                currentBytes = bytes(header)
                carriedPrefixLength = header.length
            }
        }

        for (line in text.split('\n')) {
            val headerBytes = openFence?.let { bytes(it) + 1 } ?: 0
            for (piece in splitLongLine(line, budget - headerBytes)) {
                val needed = (if (current.isEmpty()) 0 else 1) + bytes(piece)
                if (currentBytes + needed > budget && current.length > carriedPrefixLength) flush()
                if (current.isNotEmpty()) {
                    current.append('\n')
                    currentBytes += 1
                }
                current.append(piece)
                currentBytes += bytes(piece)
            }
            if (line.trimStart().startsWith(FENCE)) {
                openFence =
                    if (openFence == null) {
                        // 围栏头过长时只用裸围栏重开，保证后续正文仍有足够空间
                        line.trimStart().takeIf { bytes(it) <= budget / 4 } ?: FENCE
                    } else {
                        null
                    }
            }
        }
        if (current.length > carriedPrefixLength) out += current.toString()
        return out
    }

    private fun splitLongLine(
        line: String,
        limit: Int,
    ): List<String> {
        if (bytes(line) <= limit) return listOf(line)
        val pieces = mutableListOf<String>()
        val piece = StringBuilder()
        var pieceBytes = 0
        var index = 0
        while (index < line.length) {
            val codePoint = line.codePointAt(index)
            val chars = Character.charCount(codePoint)
            val size = bytes(line.substring(index, index + chars))
            if (pieceBytes + size > limit && piece.isNotEmpty()) {
                pieces += piece.toString()
                piece.setLength(0)
                pieceBytes = 0
            }
            piece.appendCodePoint(codePoint)
            pieceBytes += size
            index += chars
        }
        if (piece.isNotEmpty()) pieces += piece.toString()
        return pieces
    }

    private fun bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size
}
