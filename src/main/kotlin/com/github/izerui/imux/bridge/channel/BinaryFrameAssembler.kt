package com.github.izerui.imux.bridge.channel

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * java.net.http.WebSocket 的 onBinary 可能把一条消息分成多段，拼到 last=true 才是完整的帧。
 * 超过 [maxBytes] 的消息整条丢弃，避免异常大帧占满内存（与 [TextFrameAssembler] 同一规则）。
 */
class BinaryFrameAssembler(
    private val maxBytes: Int = 4 * 1024 * 1024,
) {
    private val buffer = ByteArrayOutputStream()
    private var overflowed = false

    fun accept(
        part: ByteBuffer,
        last: Boolean,
    ): ByteArray? {
        if (!overflowed) {
            if (buffer.size() + part.remaining() > maxBytes) {
                overflowed = true
                buffer.reset()
            } else {
                val bytes = ByteArray(part.remaining())
                part.get(bytes)
                buffer.write(bytes)
            }
        }
        if (!last) return null
        val complete = if (overflowed) null else buffer.toByteArray()
        buffer.reset()
        overflowed = false
        return complete
    }
}
