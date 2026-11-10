package com.github.izerui.imux.bridge

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLabelTest {
    @Test
    fun `署名依次是工程、智能体、会话标题，只在最前面带 💬`() {
        assertEquals("💬 imux · Claude Code · 代理商改造", sessionLabel("imux", "Claude Code", "代理商改造"))
    }

    @Test
    fun `各智能体有自己的图标`() {
        assertEquals(listOf("✴️", "⚛️", "🚀"), listOf(AgentType.CLAUDE, AgentType.CODEX, AgentType.PI).map(::agentIcon))
    }

    @Test
    fun `会话标题过长时只截标题，工程名与智能体名保持完整`() {
        val label = sessionLabel("imux", "Codex", "长".repeat(60))
        assertEquals("💬 imux · Codex · " + "长".repeat(40) + "…", label)
    }

    @Test
    fun `工程名过长时截工程名，智能体名与会话标题仍在`() {
        val label = sessionLabel("工".repeat(80), "Claude Code", "代理商改造")
        assertEquals("💬 " + "工".repeat(29) + "… · Claude Code · 代理商改造", label)
    }

    @Test
    fun `三段都超长时各自截短，带图标的整行不超过路由的署名上限`() {
        val label = sessionLabel("工".repeat(200), "智".repeat(200), "会".repeat(200))
        assertEquals("💬 " + "工".repeat(29) + "… · " + "智".repeat(19) + "… · " + "会".repeat(40) + "…", label)
        assertTrue(label.length <= 100)
    }

    @Test
    fun `空白的段不出现，也不留多余的分隔点，全空时不加图标`() {
        assertEquals("💬 imux · Pi", sessionLabel("imux", "Pi", " "))
        assertEquals("💬 Claude Code · 标题", sessionLabel(" ", "Claude Code", "标题"))
        assertEquals("", sessionLabel(" ", " ", " "))
    }
}
