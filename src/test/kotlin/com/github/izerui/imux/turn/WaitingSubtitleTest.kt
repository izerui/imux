package com.github.izerui.imux.turn

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.settings.PluginLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class WaitingSubtitleTest {
    @Test
    fun `formats permission prompt`() {
        assertEquals("Claude · Waiting for permission", waitingSubtitle(AgentType.CLAUDE, "permission prompt"))
    }

    @Test
    fun `formats simplified Chinese independently`() {
        assertEquals(
            "Claude · 等待权限确认",
            waitingSubtitle(
                AgentType.CLAUDE,
                "permission prompt",
                PluginLanguage.SIMPLIFIED_CHINESE,
            ),
        )
    }

    @Test
    fun `formats selection dialog`() {
        assertEquals("Claude · Waiting for your selection", waitingSubtitle(AgentType.CLAUDE, "dialog open"))
    }

    @Test
    fun `formats input request`() {
        assertEquals("Claude · Waiting for input", waitingSubtitle(AgentType.CLAUDE, "input needed"))
    }

    @Test
    fun `uses fallback for uncommon or unknown reasons`() {
        assertEquals("Claude · Waiting for your confirmation", waitingSubtitle(AgentType.CLAUDE, "worker request"))
        assertEquals("Claude · Waiting for your confirmation", waitingSubtitle(AgentType.CLAUDE, "sandbox request"))
        assertEquals("Claude · Waiting for your confirmation", waitingSubtitle(AgentType.CLAUDE, "future reason"))
        assertEquals("Claude · Waiting for your confirmation", waitingSubtitle(AgentType.CLAUDE, null))
    }

    @Test
    fun `omits unknown agent without an extra separator`() {
        assertEquals("Waiting for permission", waitingSubtitle(null, "permission prompt"))
    }

    @Test
    fun `手机端等待提示与 IDE 提醒共用原因译名，没有原因时兜底为等待确认`() {
        assertEquals("等待输入", waitingReason("input needed", PluginLanguage.SIMPLIFIED_CHINESE))
        assertEquals("等待确认", waitingReason(null, PluginLanguage.SIMPLIFIED_CHINESE))
        val texts = com.github.izerui.imux.SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BundleBridgeTexts.kt")
        val waiting = texts.normalized.substringAfter("override fun waiting(reason: String?)").substringBefore("override fun")
        assertEquals(true, waiting.contains("waitingReason(reason)"))
    }
}
