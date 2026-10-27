package com.github.izerui.imux.clarification

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.agent.AgentJsonEventParser
import com.github.izerui.imux.agent.AgentProgressEvent
import com.github.izerui.imux.agent.AgentProgressKind
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.overview.overviewProgressText
import com.github.izerui.imux.settings.PluginLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationProgressTest {
    private val zh = ViewMessages { key, params -> ImuxBundle.message(PluginLanguage.SIMPLIFIED_CHINESE, key, *params) }

    @Test
    fun `三个智能体输出文字后显示正在回复`() {
        val outputs = listOf(
            AgentType.CLAUDE to """{"type":"assistant","message":{"content":[{"type":"text","text":"回答"}]}}""",
            AgentType.CODEX to """{"type":"item.completed","item":{"type":"agent_message","text":"回答"}}""",
            AgentType.PI to """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"回答"}],"stopReason":"stop"}}""",
        )
        for ((type, output) in outputs) {
            val shown = mutableListOf<String>()
            val parser = AgentJsonEventParser(type, { event -> clarificationProgressText(event, zh)?.let(shown::add) })
            parser.accept(output)
            assertEquals("$type 必须实际产生回复进度", listOf("正在回复…"), shown)
        }
    }

    @Test
    fun `请求重试显示已收到的次数而未提供次数时不留占位符`() {
        val shown = mutableListOf<String>()
        val parser = AgentJsonEventParser(AgentType.CLAUDE, { event -> clarificationProgressText(event, zh)?.let(shown::add) })
        parser.accept("""{"type":"system","subtype":"api_retry","attempt":2,"max_retries":5}""")
        assertEquals(listOf("正在重试请求… 2/5"), shown)
        assertEquals("正在重试请求…", clarificationProgressText(AgentProgressEvent(AgentProgressKind.RETRYING), zh))
    }

    @Test
    fun `启动思考工具进度按事件区分`() {
        assertEquals("正在连接 Agent…", clarificationProgressText(AgentProgressEvent(AgentProgressKind.STARTING), zh))
        assertEquals("正在思考…", clarificationProgressText(AgentProgressEvent(AgentProgressKind.THINKING), zh))
        val read = clarificationProgressText(AgentProgressEvent(AgentProgressKind.TOOL_STARTED, "Read: /project/README.md"), zh)!!
        assertTrue("工具动作保留文件名", read.contains("README.md"))
        assertFalse("工具动作不泄漏完整路径", read.contains("/project/"))
    }

    @Test
    fun `结束类事件不覆盖等待状态与流程结果`() {
        assertNull(clarificationProgressText(AgentProgressEvent(AgentProgressKind.TOOL_FINISHED), zh))
        assertNull(clarificationProgressText(AgentProgressEvent(AgentProgressKind.COMPLETED), zh))
        assertNull(clarificationProgressText(AgentProgressEvent(AgentProgressKind.FAILED), zh))
    }

    @Test
    fun `会话轨迹保留时间线文案而讨论使用独立回复文案`() {
        val event = AgentProgressEvent(AgentProgressKind.RESPONDING)
        assertEquals(ImuxBundle.message("action.overview.progress.responding"), overviewProgressText(event))
        assertEquals("正在回复…", clarificationProgressText(event, zh))
    }
}
