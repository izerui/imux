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
        // Pi 0.84.3 的 tool_execution_end 不携带 args，只有 toolName
        parser.accept(
            """{"type":"tool_execution_end","toolCallId":"1","toolName":"grep","result":{},"isError":false}""",
        )
        parser.accept(
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"是否覆盖了取消路径？"}]}}""",
        )

        assertEquals(PeerProgressKind.TOOL_STARTED, events[0].kind)
        assertTrue(events[0].subject.contains("PeerStatus"))
        assertEquals(PeerProgressKind.TOOL_FINISHED, events[1].kind)
        assertEquals("grep", events[1].subject)
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

    /**
     * Pi 0.84.3 格式事件经过 `PeerJsonEventParser` → `PeerRun.recordProgress`。
     *
     * 按 Pi 0.84.3 的实际字段结构手写事件，钉住三条行为：
     * 1. `tool_execution_start` 携带 args → subject 含参数摘要
     * 2. `tool_execution_end` 不携带 args → `completedActions` 仍递增，subject 只有工具名
     * 3. 最终 `message_end(assistant)` → `finalText` 非空
     *
     * 不覆盖 stdout 分行、EDT 通知或横幅渲染；这些需要 runIde 沙盒验证。
     */
    @Test
    fun `Pi 0·84·3 格式事件经过 PeerRun 后 completedActions 和状态序列正确`() {
        val run = PeerRun()
        run.startProgress(1)
        val events = mutableListOf<PeerProgressEvent>()
        val parser = PeerJsonEventParser(AgentType.PI) { event ->
            events.add(event)
            run.recordProgress(event)
        }

        // Pi 0.84.3 真实事件：tool_execution_start 有 args，tool_execution_end 没有
        val lines = listOf(
            """{"type":"agent_start"}""",
            """{"type":"turn_start"}""",
            """{"type":"tool_execution_start","toolCallId":"call_1","toolName":"read","args":{"path":"src/main/kotlin/Foo.kt","offset":1,"limit":400}}""",
            """{"type":"tool_execution_end","toolCallId":"call_1","toolName":"read","result":{},"isError":false}""",
            """{"type":"turn_start"}""",
            """{"type":"tool_execution_start","toolCallId":"call_2","toolName":"bash","args":{"command":"rg -n TODO src","timeout":20}}""",
            """{"type":"tool_execution_end","toolCallId":"call_2","toolName":"bash","result":{},"isError":false}""",
            """{"type":"turn_start"}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"这个边界条件没有被覆盖。"}]}}""",
            """{"type":"agent_end"}""",
        )

        lines.forEach { parser.accept(it) }

        val snapshot = run.progressSnapshot()
        assertEquals("两次工具调用完成后 completedActions 应为 2", 2, snapshot.completedActions)

        // tool_execution_start 的 subject 应包含参数摘要
        val toolStarted = events.filter { it.kind == PeerProgressKind.TOOL_STARTED }
        assertEquals(2, toolStarted.size)
        assertTrue("read 启动应含文件路径", toolStarted[0].subject.contains("src/main/kotlin/Foo.kt"))
        assertTrue("bash 启动应含命令", toolStarted[1].subject.contains("rg -n TODO"))

        // tool_execution_end 没有 args，subject 只是工具名
        val toolFinished = events.filter { it.kind == PeerProgressKind.TOOL_FINISHED }
        assertEquals(2, toolFinished.size)
        assertEquals("read", toolFinished[0].subject)
        assertEquals("bash", toolFinished[1].subject)

        // 最终反馈
        assertEquals("这个边界条件没有被覆盖。", parser.finalText())
    }
}
