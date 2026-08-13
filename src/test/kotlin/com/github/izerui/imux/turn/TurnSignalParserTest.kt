package com.github.izerui.imux.turn

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnSignalParserTest {

    private fun claude(previous: TurnState, vararg lines: String) =
        TurnSignalParser.parse(AgentType.CLAUDE, previous, lines.toList())

    private fun codex(previous: TurnState, vararg lines: String) =
        TurnSignalParser.parse(AgentType.CODEX, previous, lines.toList())

    private fun assistant(stopReason: String?) =
        """{"type":"assistant","message":{"role":"assistant","stop_reason":${
            if (stopReason == null) "null" else "\"$stopReason\""
        },"content":[{"type":"text","text":"回复"}]}}"""

    private val userLine = """{"type":"user","message":{"content":[{"type":"tool_result"}]}}"""

    private val started = """{"type":"event_msg","payload":{"type":"task_started"}}"""
    private val done = """{"type":"event_msg","payload":{"type":"task_complete"}}"""
    private val aborted = """{"type":"event_msg","payload":{"type":"turn_aborted"}}"""

    private fun pi(previous: TurnState, vararg lines: String) =
        TurnSignalParser.parse(AgentType.PI, previous, lines.toList())

    private fun piAssistant(stopReason: String) =
        """{"type":"message","id":"a1","parentId":"u1","timestamp":"2026-08-13T08:03:21.444Z","message":{"role":"assistant","content":[{"type":"text","text":"回复"}],"stopReason":"$stopReason"}}"""

    private val piUser =
        """{"type":"message","id":"u1","parentId":null,"timestamp":"2026-08-13T08:03:09.000Z","message":{"role":"user","content":[{"type":"text","text":"帮我改一下"}]}}"""

    // ---- Claude ----

    @Test
    fun `claude 调用工具视为进行中`() {
        assertEquals(TurnState.WORKING, claude(TurnState.IDLE, assistant("tool_use")).state)
    }

    @Test
    fun `claude end_turn 视为完成`() {
        val r = claude(TurnState.WORKING, assistant("end_turn"))
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.COMPLETED, r.event)
    }

    /** 关键：不能只认 end_turn。实测 71 个会话中有 7 个以 stop_sequence 收尾。 */
    @Test
    fun `claude stop_sequence 同样视为完成`() {
        val r = claude(TurnState.WORKING, assistant("stop_sequence"))
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.COMPLETED, r.event)
    }

    @Test
    fun `claude stop_reason 为 null 时视为完成`() {
        val r = claude(TurnState.WORKING, assistant(null))
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.COMPLETED, r.event)
    }

    @Test
    fun `claude 工具结果回填视为进行中`() {
        assertEquals(TurnState.WORKING, claude(TurnState.IDLE, userLine).state)
    }

    @Test
    fun `claude 完整一轮只产出一次完成事件`() {
        val r = claude(
            TurnState.IDLE,
            userLine,
            assistant("tool_use"),
            userLine,
            assistant("end_turn"),
        )
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.COMPLETED, r.event)
    }

    @Test
    fun `claude 已处于空闲时再来完成信号不产出事件`() {
        val r = claude(TurnState.IDLE, assistant("end_turn"))
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.NONE, r.event)
    }

    // ---- Codex ----

    @Test
    fun `codex task_started 视为进行中`() {
        assertEquals(TurnState.WORKING, codex(TurnState.IDLE, started).state)
    }

    @Test
    fun `codex task_complete 产出完成事件`() {
        val r = codex(TurnState.IDLE, started, done)
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.COMPLETED, r.event)
    }

    /** 用户自己按 Esc 中断的，回到空闲但不该叫他回来看。 */
    @Test
    fun `codex turn_aborted 回到空闲但不提醒`() {
        val r = codex(TurnState.IDLE, started, aborted)
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.ABORTED, r.event)
    }

    // ---- pi ----
    //
    // pi 没有 codex 那样的 task_started / task_complete 事件，判据是 assistant 消息的
    // stopReason，取值为 stop | length | toolUse | error | aborted（见 pi 文档 session-format.md）。

    @Test
    fun `pi 用户消息开启新一轮`() {
        assertEquals(TurnState.WORKING, pi(TurnState.IDLE, piUser).state)
    }

    @Test
    fun `pi 调用工具视为进行中`() {
        assertEquals(TurnState.WORKING, pi(TurnState.IDLE, piAssistant("toolUse")).state)
    }

    @Test
    fun `pi stop 视为完成`() {
        val r = pi(TurnState.WORKING, piAssistant("stop"))
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.COMPLETED, r.event)
    }

    /** 撞上下文上限而截断，同样是「这一轮结束了，去看看」，该提醒。 */
    @Test
    fun `pi length 视为完成`() {
        val r = pi(TurnState.WORKING, piAssistant("length"))
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.COMPLETED, r.event)
    }

    /** 报错也是停下来了，不提醒的话用户会一直等一个永远不来的通知。 */
    @Test
    fun `pi error 视为完成`() {
        val r = pi(TurnState.WORKING, piAssistant("error"))
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.COMPLETED, r.event)
    }

    @Test
    fun `pi aborted 回到空闲但不提醒`() {
        val r = pi(TurnState.WORKING, piAssistant("aborted"))
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.ABORTED, r.event)
    }

    /**
     * 一轮完整对话：提问 → 调工具 → 收工具结果 → 收尾。
     * 中间的 toolResult 消息不能被当成用户提问，否则每次工具往返都要重开一轮。
     */
    @Test
    fun `pi 工具往返不打断本轮`() {
        val toolResult =
            """{"type":"message","id":"t1","parentId":"a1","timestamp":"2026-08-13T08:03:30.000Z","message":{"role":"toolResult","content":[{"type":"text","text":"ok"}]}}"""

        val r = pi(TurnState.IDLE, piUser, piAssistant("toolUse"), toolResult, piAssistant("stop"))

        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.COMPLETED, r.event)
        assertEquals("应当只有 IDLE→WORKING 与 WORKING→IDLE 两次跃迁", 2, r.transitions.size)
    }

    // ---- 通用 ----

    @Test
    fun `无关行不改变状态`() {
        val noise = """{"type":"event_msg","payload":{"type":"token_count","total":123}}"""
        val r = codex(TurnState.WORKING, noise)
        assertEquals(TurnState.WORKING, r.state)
        assertEquals(TurnEvent.NONE, r.event)
    }

    @Test
    fun `损坏行被跳过且不改变状态`() {
        val r = claude(TurnState.WORKING, "这不是 json", "{未闭合")
        assertEquals(TurnState.WORKING, r.state)
        assertEquals(TurnEvent.NONE, r.event)
    }

    @Test
    fun `空输入不改变状态`() {
        val r = claude(TurnState.WORKING)
        assertEquals(TurnState.WORKING, r.state)
        assertEquals(TurnEvent.NONE, r.event)
    }
}
