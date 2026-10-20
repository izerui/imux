package com.github.izerui.imux.agent

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentJsonEventParserTest {
    @Test
    fun `Claude 事件报告进度并记录会话 ID 与最终回答`() {
        val events = mutableListOf<AgentProgressEvent>()
        val parser = AgentJsonEventParser(AgentType.CLAUDE, events::add)

        parser.accept("""{"type":"system","subtype":"init","session_id":"abc","model":"m"}""")
        parser.accept("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Grep","input":{"pattern":"role\":\"user"}}]}}""")
        parser.accept("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}""")
        parser.accept("""{"type":"result","subtype":"success","is_error":false,"result":"[MAIN] 完成"}""")

        assertEquals("abc", parser.claudeSessionId)
        assertEquals(
            listOf(AgentProgressKind.STARTING, AgentProgressKind.TOOL_STARTED, AgentProgressKind.TOOL_FINISHED, AgentProgressKind.COMPLETED),
            events.map(AgentProgressEvent::kind),
        )
        assertTrue(events[1].subject.startsWith("Grep: "))
        assertEquals("Grep: role\":\"user", events[2].subject)
        assertEquals("[MAIN] 完成", parser.finalText())
        assertNull(parser.failureDetail())
    }

    @Test
    fun `Claude 只在 result 里带会话 ID 时也能记下`() {
        val parser = AgentJsonEventParser(AgentType.CLAUDE, {})
        parser.accept("""{"type":"result","is_error":false,"session_id":"late","result":"ok"}""")
        assertEquals("late", parser.claudeSessionId)
    }

    @Test
    fun `Claude is_error 保留失败详情`() {
        val parser = AgentJsonEventParser(AgentType.CLAUDE, {})
        parser.accept("""{"type":"result","is_error":true,"errors":["context window exceeded"]}""")
        assertEquals("context window exceeded", parser.failureDetail())
    }

    @Test
    fun `Codex 事件报告命令进度与最终回答`() {
        val events = mutableListOf<AgentProgressEvent>()
        val parser = AgentJsonEventParser(AgentType.CODEX, events::add)

        parser.accept("""{"type":"thread.started"}""")
        parser.accept("""{"type":"turn.started"}""")
        parser.accept("""{"type":"item.started","item":{"type":"command_execution","command":"rg -n user session.jsonl"}}""")
        parser.accept("""{"type":"item.completed","item":{"type":"command_execution","command":"rg -n user session.jsonl"}}""")
        parser.accept("""{"type":"item.completed","item":{"type":"agent_message","text":"[MAIN] 完成"}}""")
        parser.accept("""{"type":"turn.completed"}""")

        assertEquals(
            listOf(
                AgentProgressKind.STARTING, AgentProgressKind.THINKING, AgentProgressKind.TOOL_STARTED,
                AgentProgressKind.TOOL_FINISHED, AgentProgressKind.RESPONDING, AgentProgressKind.COMPLETED,
            ),
            events.map(AgentProgressEvent::kind),
        )
        assertEquals("shell: rg -n user session.jsonl", events[2].subject)
        assertEquals("[MAIN] 完成", parser.finalText())
    }

    @Test
    fun `Codex turn failed 保留失败详情`() {
        val parser = AgentJsonEventParser(AgentType.CODEX, {})
        parser.accept("""{"type":"turn.failed","error":{"message":"token limit exceeded"}}""")
        assertEquals("token limit exceeded", parser.failureDetail())
    }

    @Test
    fun `Pi 事件报告工具进度并把多段文本拼成完整回答`() {
        val events = mutableListOf<AgentProgressEvent>()
        val parser = AgentJsonEventParser(AgentType.PI, events::add)

        parser.accept("""{"type":"agent_start"}""")
        parser.accept("""{"type":"tool_execution_start","toolName":"read","args":{"path":"/tmp/s.jsonl"}}""")
        parser.accept("""{"type":"tool_execution_end","toolName":"read"}""")
        parser.accept("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"[MAIN] 一\n"},{"type":"text","text":"[MAIN] 二"}]}}""")
        parser.accept("""{"type":"agent_end"}""")

        assertEquals("read: /tmp/s.jsonl", events[1].subject)
        assertEquals("[MAIN] 一\n[MAIN] 二", parser.finalText())
        assertEquals(AgentProgressKind.COMPLETED, events.last().kind)
    }

    @Test
    fun `回答超过上限时标记溢出`() {
        val parser = AgentJsonEventParser(AgentType.CLAUDE, {}, maxOutputChars = 10)
        parser.accept("""{"type":"result","is_error":false,"result":"${"x".repeat(11)}"}""")
        assertTrue(parser.outputOverflowed)
    }

    @Test
    fun `Pi 分段文本合计超过上限时标记溢出`() {
        val parser = AgentJsonEventParser(AgentType.PI, {}, maxOutputChars = 10)
        parser.accept("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"123456"},{"type":"text","text":"789012"}]}}""")
        assertTrue("拼接后才超限，也应标记", parser.outputOverflowed)
    }

    @Test
    fun `后来的回答未超限时清除溢出标记`() {
        val parser = AgentJsonEventParser(AgentType.CODEX, {}, maxOutputChars = 10)
        parser.accept("""{"type":"item.completed","item":{"type":"agent_message","text":"${"x".repeat(20)}"}}""")
        parser.accept("""{"type":"item.completed","item":{"type":"agent_message","text":"short"}}""")
        assertFalse(parser.outputOverflowed)
        assertEquals("short", parser.finalText())
    }

    @Test
    fun `非 JSON 行不被接受`() {
        val parser = AgentJsonEventParser(AgentType.CLAUDE, {})
        assertFalse(parser.accept("plain text"))
    }

    private fun piEnd(stopReason: String?, text: String = "[MAIN] 节点", errorMessage: String? = null): String {
        val stop = stopReason?.let { ""","stopReason":"$it"""" }.orEmpty()
        val error = errorMessage?.let { ""","errorMessage":"$it"""" }.orEmpty()
        return """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]$stop$error}}"""
    }

    @Test
    fun `Pi 最后一条消息的停止原因决定成败`() {
        val failing = mapOf("length" to null, "error" to "boom", "aborted" to null, "toolUse" to null, "pending" to null, "deferred" to null)
        for ((reason, error) in failing) {
            val events = mutableListOf<AgentProgressEvent>()
            val parser = AgentJsonEventParser(AgentType.PI, events::add)
            parser.accept(piEnd(reason, errorMessage = error))
            parser.accept("""{"type":"agent_end"}""")

            val detail = parser.failureDetail()
            assertTrue("$reason 应判为失败：$detail", detail != null && detail.contains("stopReason=$reason"))
            error?.let { assertTrue(detail!!.contains(it)) }
            assertEquals("$reason 结束时应报告失败而不是完成", AgentProgressKind.FAILED, events.last().kind)
        }
    }

    @Test
    fun `Pi 正常结束或旧版本没有停止原因时判为成功`() {
        for (reason in listOf("stop", null)) {
            val events = mutableListOf<AgentProgressEvent>()
            val parser = AgentJsonEventParser(AgentType.PI, events::add)
            parser.accept(piEnd(reason))
            parser.accept("""{"type":"agent_end"}""")
            assertNull(parser.failureDetail())
            assertEquals(AgentProgressKind.COMPLETED, events.last().kind)
        }
    }

    @Test
    fun `Pi 出错后重试成功以最后一条为准`() {
        val parser = AgentJsonEventParser(AgentType.PI, {})
        parser.accept(piEnd("error", "[MAIN] 半截", "overloaded"))
        parser.accept(piEnd("stop", "[MAIN] 完整"))
        assertNull(parser.failureDetail())
        assertEquals("[MAIN] 完整", parser.finalText())
    }

    @Test
    fun `Pi 正常结束后又出错截断以最后一条为准`() {
        val parser = AgentJsonEventParser(AgentType.PI, {})
        parser.accept(piEnd("stop", "[MAIN] 第一轮"))
        parser.accept(piEnd("length", "[MAIN] 第二轮做到一半"))
        assertTrue(parser.failureDetail()!!.contains("stopReason=length"))
    }

    @Test
    fun `Pi 用户消息的停止原因不影响判断`() {
        val parser = AgentJsonEventParser(AgentType.PI, {})
        parser.accept("""{"type":"message_end","message":{"role":"user","content":[],"stopReason":"error"}}""")
        parser.accept(piEnd("stop"))
        assertNull(parser.failureDetail())
    }

    @Test
    fun `Claude 回答被截断或拒绝时即使 is_error 为 false 也判为失败`() {
        for (reason in listOf("max_tokens", "refusal")) {
            val events = mutableListOf<AgentProgressEvent>()
            val parser = AgentJsonEventParser(AgentType.CLAUDE, events::add)
            parser.accept("""{"type":"result","subtype":"success","is_error":false,"stop_reason":"$reason","result":"[MAIN] 前半段"}""")
            assertTrue("$reason：${parser.failureDetail()}", parser.failureDetail()!!.contains("stop_reason=$reason"))
            assertEquals(AgentProgressKind.FAILED, events.last().kind)
        }
    }

    @Test
    fun `Claude 正常结束或没有 stop_reason 时判为成功`() {
        for (reason in listOf("end_turn", "stop_sequence", null)) {
            val parser = AgentJsonEventParser(AgentType.CLAUDE, {})
            val stop = reason?.let { ""","stop_reason":"$it"""" }.orEmpty()
            parser.accept("""{"type":"result","subtype":"success","is_error":false$stop,"result":"[MAIN] 完整"}""")
            assertNull("$reason", parser.failureDetail())
        }
    }

    @Test
    fun `Pi 最后一条正文为空时清掉之前的回答`() {
        val parser = AgentJsonEventParser(AgentType.PI, {})
        parser.accept(piEnd("length", "[MAIN] 旧节点"))
        parser.accept("""{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"stop"}}""")
        assertNull("停止原因已是正常结束", parser.failureDetail())
        assertNull("正文必须跟最后一条一起更新，不能沿用旧回答", parser.finalText())
    }

    @Test
    fun `Pi 超限回答之后的空正文消息同时清掉溢出标记`() {
        val parser = AgentJsonEventParser(AgentType.PI, {}, maxOutputChars = 10)
        parser.accept(piEnd("length", "x".repeat(20)))
        parser.accept("""{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"stop"}}""")
        assertFalse(parser.outputOverflowed)
        assertNull(parser.finalText())
    }

    @Test
    fun `Codex 没有 turn completed 时判为未完成`() {
        val parser = AgentJsonEventParser(AgentType.CODEX, {})
        parser.accept("""{"type":"turn.started"}""")
        parser.accept("""{"type":"item.completed","item":{"type":"agent_message","text":"[MAIN] 节点"}}""")
        assertTrue(parser.failureDetail()!!.contains("turn.completed"))

        parser.accept("""{"type":"turn.completed"}""")
        assertNull(parser.failureDetail())
        assertEquals("[MAIN] 节点", parser.finalText())
    }

    @Test
    fun `Codex 新一轮开始后需要重新收到 turn completed`() {
        val parser = AgentJsonEventParser(AgentType.CODEX, {})
        parser.accept("""{"type":"turn.started"}""")
        parser.accept("""{"type":"turn.completed"}""")
        parser.accept("""{"type":"turn.started"}""")
        parser.accept("""{"type":"item.completed","item":{"type":"agent_message","text":"[MAIN] 第二轮"}}""")
        assertTrue(parser.failureDetail()!!.contains("turn.completed"))
    }

    @Test
    fun `Codex 回答之后又调用工具且没有新回答时不沿用旧回答`() {
        val parser = AgentJsonEventParser(AgentType.CODEX, {})
        parser.accept("""{"type":"turn.started"}""")
        parser.accept("""{"type":"item.completed","item":{"type":"agent_message","text":"[MAIN] 中途"}}""")
        parser.accept("""{"type":"item.started","item":{"type":"command_execution","command":"cat a"}}""")
        parser.accept("""{"type":"item.completed","item":{"type":"command_execution","command":"cat a"}}""")
        parser.accept("""{"type":"turn.completed"}""")
        assertNull("最后一步是工具调用，没有最终回答", parser.finalText())
    }

    @Test
    fun `Codex 新一轮开始时清掉上一轮的回答和溢出标记`() {
        val parser = AgentJsonEventParser(AgentType.CODEX, {}, maxOutputChars = 10)
        parser.accept("""{"type":"turn.started"}""")
        parser.accept("""{"type":"item.completed","item":{"type":"agent_message","text":"${"x".repeat(20)}"}}""")
        parser.accept("""{"type":"turn.completed"}""")
        assertTrue(parser.outputOverflowed)

        parser.accept("""{"type":"turn.started"}""")
        parser.accept("""{"type":"turn.completed"}""")
        assertNull("第二轮没有回答", parser.finalText())
        assertFalse("溢出标记属于上一轮", parser.outputOverflowed)
        assertNull(parser.failureDetail())
    }

    @Test
    fun `Pi 失败的 agent_end 之后重试成功时清除失败`() {
        val events = mutableListOf<AgentProgressEvent>()
        val parser = AgentJsonEventParser(AgentType.PI, events::add)
        parser.accept(piEnd("error", "[MAIN] 半截", "overloaded"))
        parser.accept("""{"type":"agent_end"}""")
        assertTrue("第一次尝试结束时是失败", parser.failureDetail()!!.contains("overloaded"))
        assertEquals(AgentProgressKind.FAILED, events.last().kind)

        parser.accept(piEnd("stop", "[MAIN] 完整"))
        parser.accept("""{"type":"agent_end"}""")
        assertNull("重试成功后不再是失败", parser.failureDetail())
        assertEquals(AgentProgressKind.COMPLETED, events.last().kind)
        assertEquals("[MAIN] 完整", parser.finalText())
    }

    @Test
    fun `Codex 报错后收到 turn completed 时清除失败`() {
        val parser = AgentJsonEventParser(AgentType.CODEX, {})
        parser.accept("""{"type":"turn.started"}""")
        parser.accept("""{"type":"error","message":"Reconnecting... 1/5"}""")
        assertTrue(parser.failureDetail()!!.contains("Reconnecting"))
        parser.accept("""{"type":"item.completed","item":{"type":"agent_message","text":"[MAIN] 完整"}}""")
        parser.accept("""{"type":"turn.completed"}""")
        assertNull(parser.failureDetail())
    }

    @Test
    fun `Codex 完成之后再报错仍判为失败`() {
        val parser = AgentJsonEventParser(AgentType.CODEX, {})
        parser.accept("""{"type":"turn.started"}""")
        parser.accept("""{"type":"turn.completed"}""")
        parser.accept("""{"type":"error","message":"fatal after completion"}""")
        assertEquals("fatal after completion", parser.failureDetail())
    }

    @Test
    fun `Claude 失败的 result 之后又有成功的 result 时清除失败`() {
        val parser = AgentJsonEventParser(AgentType.CLAUDE, {})
        parser.accept("""{"type":"result","is_error":true,"errors":["overloaded"]}""")
        assertEquals("overloaded", parser.failureDetail())
        parser.accept("""{"type":"result","is_error":false,"stop_reason":"end_turn","result":"[MAIN] 完整"}""")
        assertNull(parser.failureDetail())
    }
}
