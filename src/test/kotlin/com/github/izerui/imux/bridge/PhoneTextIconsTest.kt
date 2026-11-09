package com.github.izerui.imux.bridge

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertTrue
import org.junit.Test

/** 署名之外的手机提示里，工程、智能体、会话标题也带与署名相同的图标。 */
class PhoneTextIconsTest {
    @Test
    fun `已连接提示里工程、智能体、会话标题都带图标`() {
        val text = BundleBridgeTexts.connected("maas-api", agentName(AgentType.CLAUDE), "UAT 临时 API key")
        assertTrue(text, text.contains("📂 maas-api"))
        assertTrue(text, text.contains("✴️ Claude Code"))
        assertTrue(text, text.contains("💬 UAT 临时 API key"))
    }

    @Test
    fun `已断开提示里会话标题带图标`() {
        val text = BundleBridgeTexts.released("UAT 临时 API key")
        assertTrue(text, text.contains("💬 UAT 临时 API key"))
    }

    @Test
    fun `带图标的智能体名`() {
        assertTrue(agentName(AgentType.CODEX) == "⚛️ Codex")
        assertTrue(agentName(AgentType.PI) == "🚀 Pi")
    }
}
