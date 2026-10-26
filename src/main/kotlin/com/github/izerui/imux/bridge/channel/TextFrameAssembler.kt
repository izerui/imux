package com.github.izerui.imux.bridge.channel

/**
 * java.net.http.WebSocket 的 onText 可能把一条消息分成多段，拼到 last=true 才是完整 JSON。
 * 超过 [maxChars] 的消息整条丢弃，避免异常大帧占满内存。
 */
class TextFrameAssembler(
    private val maxChars: Int = 4 * 1024 * 1024,
) {
    private val buffer = StringBuilder()
    private var overflowed = false

    fun accept(
        part: CharSequence,
        last: Boolean,
    ): String? {
        if (!overflowed) {
            if (buffer.length + part.length > maxChars) {
                overflowed = true
                buffer.setLength(0)
            } else {
                buffer.append(part)
            }
        }
        if (!last) return null
        val complete = if (overflowed) null else buffer.toString()
        buffer.setLength(0)
        overflowed = false
        return complete
    }
}
