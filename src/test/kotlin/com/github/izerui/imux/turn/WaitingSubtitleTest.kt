package com.github.izerui.imux.turn

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Test

class WaitingSubtitleTest {

    @Test
    fun `权限确认单列文案`() {
        assertEquals("Claude Code · 等待权限确认", waitingSubtitle(AgentType.CLAUDE, "permission prompt"))
    }

    @Test
    fun `选择框单列文案`() {
        assertEquals("Claude Code · 等待你的选择", waitingSubtitle(AgentType.CLAUDE, "dialog open"))
    }

    @Test
    fun `需要输入单列文案`() {
        assertEquals("Claude Code · 等待输入", waitingSubtitle(AgentType.CLAUDE, "input needed"))
    }

    /**
     * 子代理与沙箱场景日常几乎不出现，单列文案不会被读到，统一走兜底。
     * CLI 将来新增取值时同样落到这里，不会显示成空白。
     */
    @Test
    fun `子代理与沙箱请求走兜底文案`() {
        assertEquals("Claude Code · 等待你的确认", waitingSubtitle(AgentType.CLAUDE, "worker request"))
        assertEquals("Claude Code · 等待你的确认", waitingSubtitle(AgentType.CLAUDE, "sandbox request"))
    }

    @Test
    fun `未知取值走兜底文案`() {
        assertEquals("Claude Code · 等待你的确认", waitingSubtitle(AgentType.CLAUDE, "某个将来才有的原因"))
    }

    @Test
    fun `缺少等待原因时走兜底文案`() {
        assertEquals("Claude Code · 等待你的确认", waitingSubtitle(AgentType.CLAUDE, null))
    }

    /** 与 completionSubtitle 一致：拿不到的部分整段略去，不留多余的间隔号。 */
    @Test
    fun `拿不到 agent 时只留原因`() {
        assertEquals("等待权限确认", waitingSubtitle(null, "permission prompt"))
    }
}
