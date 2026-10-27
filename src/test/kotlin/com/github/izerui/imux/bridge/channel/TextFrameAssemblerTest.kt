package com.github.izerui.imux.bridge.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextFrameAssemblerTest {
    @Test
    fun `分片拼接到 last 才输出`() {
        val assembler = TextFrameAssembler()
        assertNull(assembler.accept("{\"a\":", false))
        assertEquals("{\"a\":1}", assembler.accept("1}", true))
        assertEquals("x", assembler.accept("x", true))
    }

    @Test
    fun `超长消息整条丢弃，下一条正常`() {
        val assembler = TextFrameAssembler(maxChars = 4)
        assertNull(assembler.accept("12345", false))
        assertNull(assembler.accept("6", true))
        assertEquals("ok", assembler.accept("ok", true))
    }
}
