package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerProgressTest {
    @Test
    fun `Codex 事件分别报告命令开始完成与最终反馈`() {
        val events = mutableListOf<PeerProgressEvent>()
        val parser = PeerJsonEventParser(AgentType.CODEX, events::add)

        parser.accept("""{"type":"turn.started"}""")
        parser.accept(
            """{"type":"item.started","item":{"type":"command_execution","command":"rg -n TODO src"}}""",
        )
        parser.accept(
            """{"type":"item.completed","item":{"type":"command_execution","command":"rg -n TODO src"}}""",
        )
        parser.accept(
            """{"type":"item.completed","item":{"type":"agent_message","text":"检查这个边界条件了吗？"}}""",
        )

        assertEquals(PeerProgressKind.THINKING, events[0].kind)
        assertEquals(PeerProgressKind.TOOL_STARTED, events[1].kind)
        assertTrue(events[1].subject.contains("rg -n TODO src"))
        assertEquals(PeerProgressKind.TOOL_FINISHED, events[2].kind)
        assertEquals("检查这个边界条件了吗？", parser.finalText())
    }

    @Test
    fun `Claude 事件分别报告工具开始完成与最终反馈`() {
        val events = mutableListOf<PeerProgressEvent>()
        val parser = PeerJsonEventParser(AgentType.CLAUDE, events::add)

        parser.accept(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"tool-1","name":"Read","input":{"file_path":"src/App.kt"}}]}}""",
        )
        parser.accept(
            """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"tool-1","content":"ok"}]}}""",
        )
        parser.accept(
            """{"type":"result","subtype":"success","is_error":false,"result":"PASS"}""",
        )

        assertEquals(PeerProgressKind.TOOL_STARTED, events[0].kind)
        assertTrue(events[0].subject.contains("src/App.kt"))
        assertEquals(PeerProgressKind.TOOL_FINISHED, events[1].kind)
        assertEquals("PASS", parser.finalText())
        assertEquals(PeerProgressKind.COMPLETED, events[2].kind)
    }

    @Test
    fun `Pi 事件分别报告工具开始完成与最终反馈`() {
        val events = mutableListOf<PeerProgressEvent>()
        val parser = PeerJsonEventParser(AgentType.PI, events::add)

        parser.accept(
            """{"type":"tool_execution_start","toolCallId":"1","toolName":"grep","args":{"pattern":"PeerStatus"}}""",
        )
        parser.accept(
            """{"type":"tool_execution_end","toolCallId":"1","toolName":"grep","args":{"pattern":"PeerStatus"},"result":{},"isError":false}""",
        )
        parser.accept(
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"是否覆盖了取消路径？"}]}}""",
        )

        assertEquals(PeerProgressKind.TOOL_STARTED, events[0].kind)
        assertTrue(events[0].subject.contains("PeerStatus"))
        assertEquals(PeerProgressKind.TOOL_FINISHED, events[1].kind)
        assertEquals("是否覆盖了取消路径？", parser.finalText())
        assertEquals(PeerProgressKind.RESPONDING, events[2].kind)
    }

    @Test
    fun `Codex 最终反馈写入时受长度上限约束`() {
        val parser = PeerJsonEventParser(AgentType.CODEX) {}
        val text = "x".repeat(MAX_PEER_OUTPUT_CHARS + 100)

        parser.accept("""{"type":"item.completed","item":{"type":"agent_message","text":"$text"}}""")

        assertEquals(MAX_PEER_OUTPUT_CHARS, parser.finalText()!!.length)
    }

    @Test
    fun `Claude 最终反馈写入时受长度上限约束`() {
        val parser = PeerJsonEventParser(AgentType.CLAUDE) {}
        val text = "x".repeat(MAX_PEER_OUTPUT_CHARS + 100)

        parser.accept("""{"type":"result","subtype":"success","is_error":false,"result":"$text"}""")

        assertEquals(MAX_PEER_OUTPUT_CHARS, parser.finalText()!!.length)
    }

    @Test
    fun `Pi 最终反馈写入时受长度上限约束`() {
        val parser = PeerJsonEventParser(AgentType.PI) {}
        val first = "x".repeat(MAX_PEER_OUTPUT_CHARS - 10)
        val second = "y".repeat(100)

        parser.accept(
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"$first"},{"type":"text","text":"$second"}]}}""",
        )

        assertEquals(MAX_PEER_OUTPUT_CHARS, parser.finalText()!!.length)
        assertTrue(parser.finalText()!!.endsWith("y".repeat(10)))
    }
}
