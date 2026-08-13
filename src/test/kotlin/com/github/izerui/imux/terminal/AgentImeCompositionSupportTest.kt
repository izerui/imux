package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.AttributedString

class AgentImeCompositionSupportTest {

    /**
     * 谁需要这层覆盖：**流式重绘会不断移动终端光标的 TUI**。平台默认把未提交文字做成
     * inline inlay，光标一动就重建，inlay 参与 soft-wrap 计算，于是输入区在打字期间
     * 反复抽行。codex 与 pi 都是这个形态。
     *
     * claude 不需要：它走 CLAUDE_CODE_NATIVE_CURSOR，维护真实终端光标，
     * 平台原生的输入法路径就能正确定位（见 launchEnvironment）。
     */
    @Test
    fun `流式重绘的 agent 才需要输入法覆盖层`() {
        assertTrue(AgentType.CODEX.needsImeCompositionOverlay)
        assertTrue(AgentType.PI.needsImeCompositionOverlay)
        assertFalse(AgentType.CLAUDE.needsImeCompositionOverlay)
    }

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
