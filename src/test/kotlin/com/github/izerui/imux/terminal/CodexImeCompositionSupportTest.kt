package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.AttributedString

class CodexImeCompositionSupportTest {

    @Test
    fun `拆分已提交文字和仍在组合的文字`() {
        val text = AttributedString("你hao").iterator

        assertEquals(
            InputMethodText(committed = "你", composed = "hao"),
            splitInputMethodText(text, committedCharacterCount = 1),
        )
    }

    @Test
    fun `全部提交后组合文字为空`() {
        val text = AttributedString("中文").iterator

        assertEquals(
            InputMethodText(committed = "中文", composed = ""),
            splitInputMethodText(text, committedCharacterCount = 2),
        )
    }

    @Test
    fun `空事件会清除组合文字`() {
        assertEquals(
            InputMethodText(committed = "", composed = ""),
            splitInputMethodText(null, committedCharacterCount = 0),
        )
    }

    @Test
    fun `与平台一致过滤控制字符`() {
        val text = AttributedString("a\nb").iterator

        assertEquals(
            InputMethodText(committed = "a", composed = "b"),
            splitInputMethodText(text, committedCharacterCount = 1),
        )
    }
}
