package com.github.izerui.imux.bridge.channel.feishu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FeishuFrameCodecTest {
    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val pingHex = "08001000180720002a0c0a0474797065120470696e67"
    private val dataHex =
        "08ac021005180720012a0d0a047479706512056576656e742a100a0a6d6573736167655f696412026d312a080a0373756d1201312a080a03" +
                "7365711201302a0d0a0874726163655f6964120174420b7b2261223a22e4b8ad227d4a014c"

    private val ping =
        FeishuFrame(service = 7, method = FeishuFrame.METHOD_CONTROL, headers = listOf(FeishuHeader("type", "ping")))

    private val data =
        FeishuFrame(
            seqId = 300,
            logId = 5,
            service = 7,
            method = FeishuFrame.METHOD_DATA,
            headers =
                listOf(
                    FeishuHeader("type", "event"),
                    FeishuHeader("message_id", "m1"),
                    FeishuHeader("sum", "1"),
                    FeishuHeader("seq", "0"),
                    FeishuHeader("trace_id", "t"),
                ),
            payload = """{"a":"中"}""".toByteArray(Charsets.UTF_8),
            logIdNew = "L",
        )

    @Test
    fun `编码与官方 SDK 逐字节一致，值为 0 的必填字段也写出`() {
        assertArrayEquals(hex(pingHex), FeishuFrameCodec.encode(ping))
        assertArrayEquals(hex(dataHex), FeishuFrameCodec.encode(data))
    }

    @Test
    fun `解码官方 SDK 的字节`() {
        assertEquals(ping, FeishuFrameCodec.decode(hex(pingHex)))
        assertEquals(data, FeishuFrameCodec.decode(hex(dataHex)))
        assertEquals("m1", FeishuFrameCodec.decode(hex(dataHex)).header("message_id"))
    }

    @Test
    fun `未知字段跳过`() {
        val withUnknown = hex(pingHex) + byteArrayOf(0x78, 0x01) + byteArrayOf(0x82.toByte(), 0x01, 0x01, 0x41)
        assertEquals(ping, FeishuFrameCodec.decode(withUnknown))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `截断的输入报错`() {
        val bytes = hex(dataHex)
        FeishuFrameCodec.decode(bytes.copyOf(bytes.size - 2))
    }

    @Test
    fun `追加 header 不改原帧`() {
        val acked = data.withHeader("biz_rt", "0")
        assertEquals("0", acked.header("biz_rt"))
        assertEquals(null, data.header("biz_rt"))
    }
}
