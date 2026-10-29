package com.github.izerui.imux.bridge.channel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class BinaryFrameAssemblerTest {
    @Test
    fun `分片拼接到 last 才输出`() {
        val assembler = BinaryFrameAssembler()
        assertNull(assembler.accept(ByteBuffer.wrap(byteArrayOf(1, 2)), false))
        assertArrayEquals(byteArrayOf(1, 2, 3), assembler.accept(ByteBuffer.wrap(byteArrayOf(3)), true))
        assertArrayEquals(byteArrayOf(9), assembler.accept(ByteBuffer.wrap(byteArrayOf(9)), true))
    }

    @Test
    fun `超长消息整条丢弃，下一条正常`() {
        val assembler = BinaryFrameAssembler(maxBytes = 4)
        assertNull(assembler.accept(ByteBuffer.wrap(ByteArray(5)), false))
        assertNull(assembler.accept(ByteBuffer.wrap(ByteArray(1)), true))
        assertArrayEquals(byteArrayOf(7), assembler.accept(ByteBuffer.wrap(byteArrayOf(7)), true))
    }
}
