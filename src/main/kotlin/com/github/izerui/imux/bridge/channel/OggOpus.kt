package com.github.izerui.imux.bridge.channel

import io.github.jaredmdobson.concentus.OpusDecoder
import java.io.ByteArrayOutputStream

/**
 * 把 Ogg 封装的 Opus 语音解码成 16 kHz 单声道 16 位 PCM，供只收 pcm 的语音识别接口使用。
 * 只处理单个逻辑流（语音消息就是这样）；立体声由解码器直接混成单声道。
 * 无法解析、解码失败或超过上限时抛 [IllegalArgumentException]。
 */
object OggOpus {
    const val SAMPLE_RATE = 16_000

    /** Opus 一帧最长 120 毫秒。 */
    private const val MAX_FRAME_SAMPLES = SAMPLE_RATE * 120 / 1_000

    private val CAPTURE = "OggS".toByteArray(Charsets.US_ASCII)

    fun decode(
        bytes: ByteArray,
        maxSamples: Int,
    ): ShortArray {
        val packets = packets(bytes)
        require(packets.size >= 2) { "不是 Ogg Opus" }
        val head = packets[0]
        require(head.size >= 19 && String(head, 0, 8, Charsets.US_ASCII) == "OpusHead") { "缺少 OpusHead" }
        // pre-skip 按 48 kHz 计，是编码器在开头垫的采样，要丢掉
        var skip = ((head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)) * SAMPLE_RATE / 48_000
        val decoder =
            try {
                OpusDecoder(SAMPLE_RATE, 1)
            } catch (e: Exception) {
                throw IllegalArgumentException("无法创建解码器", e)
            }
        val frame = ShortArray(MAX_FRAME_SAMPLES)
        var output = ShortArray(SAMPLE_RATE)
        var size = 0
        // packets[1] 是 OpusTags，跳过
        for (index in 2 until packets.size) {
            val packet = packets[index]
            if (packet.isEmpty()) continue
            val count =
                try {
                    decoder.decode(packet, 0, packet.size, frame, 0, MAX_FRAME_SAMPLES, false)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Opus 解码失败", e)
                }
            val start = minOf(skip, count)
            skip -= start
            val kept = count - start
            require(size + kept <= maxSamples) { "语音过长" }
            if (size + kept > output.size) output = output.copyOf(maxOf(output.size * 2, size + kept))
            frame.copyInto(output, size, start, count)
            size += kept
        }
        return output.copyOf(size)
    }

    /** 按页拆出数据包：段长为 255 表示这个数据包在下一段继续。 */
    private fun packets(bytes: ByteArray): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val current = ByteArrayOutputStream()
        var position = 0
        while (position < bytes.size) {
            require(
                position + 27 <= bytes.size && bytes.copyOfRange(position, position + 4).contentEquals(CAPTURE)
            ) { "不是 Ogg 页" }
            val segments = bytes[position + 26].toInt() and 0xFF
            var data = position + 27 + segments
            require(data <= bytes.size) { "Ogg 页被截断" }
            for (segment in 0 until segments) {
                val length = bytes[position + 27 + segment].toInt() and 0xFF
                require(data + length <= bytes.size) { "Ogg 页被截断" }
                current.write(bytes, data, length)
                data += length
                if (length < 255) {
                    packets += current.toByteArray()
                    current.reset()
                }
            }
            position = data
        }
        require(current.size() == 0) { "最后一个数据包不完整" }
        return packets
    }
}
