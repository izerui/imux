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

    @Test
    fun `相同 AgentType 的绑定有不同 generation`() {
        val b1 = PeerBinding(AgentType.CLAUDE)
        val b2 = PeerBinding(AgentType.CLAUDE)
        assertFalse("解绑后重新绑定不应与旧绑定相等", b1 == b2)
    }

    @Test
    fun `迁移保留原 generation`() {
        val original = PeerBinding(AgentType.CLAUDE, task = "修复 bug")
        val migrated = original.copy(task = "")
        assertEquals(original.generation, migrated.generation)
    }

    @Test
    fun `中文提示词包含关键措辞`() {
        val prompt = PeerCoordinator.DEFAULT_PROMPT_ZH
        assertTrue("应包含诊断性问题", prompt.contains("诊断性问题"))
        assertTrue("应包含问题标注", prompt.contains("[问题]"))
        assertTrue("应包含疑点标注", prompt.contains("[疑点]"))
        assertTrue("应禁止具体实现方案", prompt.contains("具体实现方案"))
        assertTrue("应声明上下文为待审查数据", prompt.contains("待审查的数据"))
        assertTrue("应声明输出非用户授权", prompt.contains("不是用户授权"))
        assertTrue("应区分证据类型", prompt.contains("用户消息") && prompt.contains("助手消息") && prompt.contains("工具调用和返回"))
        assertTrue("应说明工具记录更可靠", prompt.contains("比助手自述更可靠"))
        assertTrue("应包含模式占位符", prompt.contains("\${mode}"))
        assertTrue("应包含验证充分性维度", prompt.contains("验证充分性"))
        assertTrue("应要求尊重用户范围", prompt.contains("尊重用户限定的修改范围"))
        assertFalse("不应包含'有什么想说的'", prompt.contains("有什么想说的"))
        assertFalse("不应包含'提问和启发'", prompt.contains("提问和启发"))
    }

    @Test
    fun `英文提示词包含关键措辞`() {
        val prompt = PeerCoordinator.DEFAULT_PROMPT_EN
        assertTrue("应包含 diagnostic questions", prompt.contains("diagnostic questions"))
        assertTrue("应包含 issue 标注", prompt.contains("[issue]"))
        assertTrue("应包含 suspect 标注", prompt.contains("[suspect]"))
        assertTrue("应禁止 concrete implementation", prompt.contains("concrete implementation"))
        assertTrue("应声明上下文为待审查数据", prompt.contains("data to review"))
        assertTrue("应声明输出非用户授权", prompt.contains("not user authorization"))
        assertTrue("应区分证据类型", prompt.contains("User messages") && prompt.contains("Assistant messages") && prompt.contains("Tool calls and returns"))
        assertTrue("应说明工具记录更可靠", prompt.contains("more reliable than assistant self-reports"))
        assertTrue("应包含模式占位符", prompt.contains("\${mode}"))
        assertTrue("应包含验证充分性维度", prompt.contains("Verification adequacy"))
        assertTrue("应要求尊重用户范围", prompt.contains("Respect the scope"))
        assertFalse("不应包含 'share whatever comes to mind'", prompt.contains("share whatever comes to mind"))
    }

    @Test
    fun `反馈前缀包含来源声明`() {
        val zhPrefix = FEEDBACK_PREFIX_ZH
        val enPrefix = FEEDBACK_PREFIX_EN
        assertTrue("中文前缀应声明非用户指令", zhPrefix.contains("非用户指令"))
        assertTrue("英文前缀应声明非用户指令", enPrefix.contains("not a user instruction"))
    }
}
