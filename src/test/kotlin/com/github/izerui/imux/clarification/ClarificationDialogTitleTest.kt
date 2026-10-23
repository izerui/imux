package com.github.izerui.imux.clarification

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 窗口标题与 Agent 默认选择的纯函数测试。 */
class ClarificationDialogTitleTest {
    /** 功能名称（随语言变化：先聊再做 / Talk First / …）。 */
    private val featureName = ImuxBundle.message("clarify.action.text")

    @Test
    fun `开始前标题只有功能名`() {
        val title = dialogTitle(null, null, AgentType.CLAUDE)
        assertEquals(featureName, title)
    }

    @Test
    fun `开始后标题带目标`() {
        val title = dialogTitle("给终端增加断线重连", AgentType.CLAUDE, AgentType.CLAUDE)
        assertEquals("$featureName · 给终端增加断线重连", title)
    }

    @Test
    fun `选的 Agent 与当前会话不同时追加 Agent 名`() {
        val title = dialogTitle("重构登录模块", AgentType.CODEX, AgentType.CLAUDE)
        assertEquals("$featureName · 重构登录模块 · Codex", title)
    }

    @Test
    fun `选的 Agent 与当前会话相同时不追加`() {
        val title = dialogTitle("目标", AgentType.CLAUDE, AgentType.CLAUDE)
        assertFalse("同类型不追加 Agent 名", title.contains("Claude"))
    }

    @Test
    fun `长目标截断到约 30 字`() {
        val long = "这是一段非常非常非常非常非常非常非常非常非常非常长的目标描述文字"
        val title = dialogTitle(long, AgentType.CLAUDE, AgentType.CLAUDE)
        assertTrue("包含截断标记", title.endsWith("…"))
        assertTrue("截断后长度合理", title.length < featureName.length + 3 + 30 + 5)
    }

    @Test
    fun `默认选择：当前会话的类型已启用则选它`() {
        val selected = defaultAgentSelection(listOf(AgentType.CLAUDE, AgentType.CODEX), AgentType.CODEX)
        assertEquals(AgentType.CODEX, selected)
    }

    @Test
    fun `默认选择：当前会话的类型未启用则选第一个`() {
        val selected = defaultAgentSelection(listOf(AgentType.CODEX, AgentType.PI), AgentType.CLAUDE)
        assertEquals(AgentType.CODEX, selected)
    }
}
