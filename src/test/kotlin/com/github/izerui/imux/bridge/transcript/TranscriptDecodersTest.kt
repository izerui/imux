package com.github.izerui.imux.bridge.transcript

import com.github.izerui.imux.bridge.core.SessionEvent
import com.github.izerui.imux.bridge.core.SessionEventKind.ASSISTANT_TEXT
import com.github.izerui.imux.bridge.core.SessionEventKind.TOOL_FINISHED
import com.github.izerui.imux.bridge.core.SessionEventKind.TOOL_STARTED
import com.github.izerui.imux.bridge.core.SessionEventKind.USER_TEXT
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptDecodersTest {
    @Test
    fun `Claude 区分用户文本、助手文本、工具调用与结果且保留换行`() {
        val user = """{"type":"user","uuid":"u1","message":{"role":"user","content":"第一行\n第二行"}}"""
        val assistant =
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[""" +
                """{"type":"thinking","thinking":"内部推理"},""" +
                """{"type":"text","text":"```kt\nval a = 1\n```"},""" +
                """{"type":"tool_use","id":"t1","name":"Read","input":{}}]}}"""
        val result =
            """{"type":"user","uuid":"u2","message":{"role":"user","content":[""" +
                """{"type":"tool_result","tool_use_id":"t1","content":"文件内容"}]}}"""

        assertEquals(listOf(SessionEvent(USER_TEXT, "第一行\n第二行")), ClaudeTranscriptDecoder.decode(user))
        assertEquals(
            listOf(SessionEvent(ASSISTANT_TEXT, "```kt\nval a = 1\n```"), SessionEvent(TOOL_STARTED, "Read", "t1")),
            ClaudeTranscriptDecoder.decode(assistant),
        )
        assertEquals(listOf(SessionEvent(TOOL_FINISHED, "", "t1")), ClaudeTranscriptDecoder.decode(result))
    }

    @Test
    fun `Claude 跳过元数据、压缩摘要、子代理、命令包裹与中断标记`() {
        listOf(
            """{"type":"user","isMeta":true,"message":{"role":"user","content":"meta"}}""",
            """{"type":"user","isCompactSummary":true,"message":{"role":"user","content":"摘要"}}""",
            """{"type":"assistant","isSidechain":true,"message":{"role":"assistant","content":[{"type":"text","text":"子代理"}]}}""",
            """{"type":"user","message":{"role":"user","content":"<command-name>/clear</command-name>"}}""",
            """{"type":"user","message":{"role":"user","content":"[Request interrupted by user]"}}""",
            """{"type":"summary","summary":"x"}""",
            "not json",
        ).forEach { assertTrue(it, ClaudeTranscriptDecoder.decode(it).isEmpty()) }
    }

    @Test
    fun `Codex 只读 response_item 并过滤环境与 AGENTS 注入`() {
        val user =
            """{"type":"response_item","payload":{"type":"message","role":"user","content":[""" +
                """{"type":"input_text","text":"<environment_context>x</environment_context>"},""" +
                """{"type":"input_text","text":"修一下\n测试"}]}}"""
        val agents =
            """{"type":"response_item","payload":{"type":"message","role":"user","content":[""" +
                """{"type":"input_text","text":"# AGENTS.md instructions for /p"}]}}"""
        val assistant =
            """{"type":"response_item","payload":{"type":"message","role":"assistant","content":[""" +
                """{"type":"output_text","text":"好的"}]}}"""
        val call = """{"type":"response_item","payload":{"type":"function_call","name":"shell","call_id":"c1","arguments":"{}"}}"""
        val output = """{"type":"response_item","payload":{"type":"function_call_output","call_id":"c1","output":"ok"}}"""
        val mirror = """{"type":"event_msg","payload":{"type":"agent_message","message":"好的"}}"""

        assertEquals(listOf(SessionEvent(USER_TEXT, "修一下\n测试")), CodexTranscriptDecoder.decode(user))
        assertTrue(CodexTranscriptDecoder.decode(agents).isEmpty())
        assertEquals(listOf(SessionEvent(ASSISTANT_TEXT, "好的")), CodexTranscriptDecoder.decode(assistant))
        assertEquals(listOf(SessionEvent(TOOL_STARTED, "shell", "c1")), CodexTranscriptDecoder.decode(call))
        assertEquals(listOf(SessionEvent(TOOL_FINISHED, "", "c1")), CodexTranscriptDecoder.decode(output))
        assertTrue("event_msg 是镜像，不能重复转发", CodexTranscriptDecoder.decode(mirror).isEmpty())
    }

    @Test
    fun `Pi 区分 text、toolCall、toolResult 并跳过 system 与会话头`() {
        val user = """{"type":"message","id":"1","parentId":null,"message":{"role":"user","content":"你好"}}"""
        val assistant =
            """{"type":"message","id":"2","parentId":"1","message":{"role":"assistant","content":[""" +
                """{"type":"text","text":"看看"},{"type":"toolCall","id":"k1","name":"bash","arguments":{}}]}}"""
        val result =
            """{"type":"message","id":"3","parentId":"2","message":{"role":"toolResult","toolCallId":"k1","content":[]}}"""
        val system = """{"type":"message","id":"0","parentId":null,"message":{"role":"system","content":""}}"""
        val header = """{"type":"session","version":3,"id":"s","cwd":"/p"}"""

        assertEquals(listOf(SessionEvent(USER_TEXT, "你好")), PiTranscriptDecoder.decode(user))
        assertEquals(
            listOf(SessionEvent(ASSISTANT_TEXT, "看看"), SessionEvent(TOOL_STARTED, "bash", "k1")),
            PiTranscriptDecoder.decode(assistant),
        )
        assertEquals(listOf(SessionEvent(TOOL_FINISHED, "", "k1")), PiTranscriptDecoder.decode(result))
        assertTrue(PiTranscriptDecoder.decode(system).isEmpty())
        assertTrue(PiTranscriptDecoder.decode(header).isEmpty())
    }

    @Test
    fun `按 Agent 选择解码器`() {
        assertSame(ClaudeTranscriptDecoder, TranscriptDecoders.forAgent(AgentType.CLAUDE))
        assertSame(CodexTranscriptDecoder, TranscriptDecoders.forAgent(AgentType.CODEX))
        assertSame(PiTranscriptDecoder, TranscriptDecoders.forAgent(AgentType.PI))
    }
}
