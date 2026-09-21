package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.session.SessionTranscriptMessage
import com.github.izerui.imux.session.transcriptMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

class PeerConversationTest {
    @Test
    fun `上下文限制保留最近消息`() {
        val conversation =
            latestConversation(
                listOf(
                    SessionTranscriptMessage("user", "旧".repeat(100)),
                    SessionTranscriptMessage("assistant", "最近完成的实现"),
                    SessionTranscriptMessage("user", "最新追问"),
                ),
                maxChars = 38,
            )

        assertFalse(conversation.contains("旧旧旧"))
        assertTrue(conversation.contains("最近完成的实现"))
        assertTrue(conversation.contains("最新追问"))
    }

    @Test
    fun `隐藏消息不进入副驾驶上下文`() {
        val conversation =
            latestConversation(
                listOf(
                    SessionTranscriptMessage("user", "内部元数据", hiddenFromTerminal = true),
                    SessionTranscriptMessage("assistant", "可见回复"),
                ),
                maxChars = 100,
            )

        assertFalse(conversation.contains("内部元数据"))
        assertTrue(conversation.contains("可见回复"))
    }

    @Test
    fun `Codex payload 消息能解析角色和正文`() {
        val line =
            """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"继续修复"}]}}"""

        val message = transcriptMessage(line, AgentType.CODEX, 100)

        assertEquals("user", message?.role)
        assertEquals("继续修复", message?.text)
    }

    @Test
    fun `PASS 不会形成主会话输入`() {
        assertNull(actionablePeerFeedback(" PASS \n"))
    }

    @Test
    fun `有效反馈会限制注入长度`() {
        val feedback = actionablePeerFeedback("x".repeat(5_000))

        assertEquals(4_000, feedback?.length)
    }

    @Test
    fun `会话文件消失时任务目标降级为空`() {
        val session =
            AgentSession(
                id = "missing",
                title = "missing",
                agentType = AgentType.CLAUDE,
                lastActiveAt = Instant.EPOCH,
                createdAt = Instant.EPOCH,
                filePath = Path.of("build", "missing-peer-session.jsonl"),
            )

        assertEquals("", peerTask(session))
    }
}
