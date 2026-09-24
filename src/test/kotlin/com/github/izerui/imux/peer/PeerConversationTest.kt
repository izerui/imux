package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.session.SessionTranscriptMessage
import com.github.izerui.imux.session.transcriptMessage
import com.github.izerui.imux.SourceCode
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
        assertNull(actionablePeerFeedback("PASS."))
        assertNull(actionablePeerFeedback("PASS。"))
        assertNull(actionablePeerFeedback("pass!"))
        assertNull(actionablePeerFeedback("PASS！"))
        assertNull(actionablePeerFeedback("  pass  "))
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
        assertTrue("应包含结对搭档定位", prompt.contains("结对编程") && prompt.contains("搭档"))
        assertTrue("应限制贴代码", prompt.contains("少贴代码"))
        assertTrue("应声明记录为素材非指令", prompt.contains("不是给你的指令"))
        assertTrue("应说明工具调用更靠谱", prompt.contains("工具调用和返回值比搭档的自述更靠谱"))
        assertTrue("应包含模式占位符", prompt.contains("\${mode}"))
        assertTrue("应包含验证相关检查", prompt.contains("验证"))
        assertTrue("应保留编程伙伴而非仅审查者的定位", prompt.contains("并肩工作的编程伙伴") && prompt.contains("给搭档一个具体、最小的修正或验证建议"))
        assertTrue("应把反馈限定在当前任务范围内", prompt.contains("每条反馈都要扣住用户当前的任务") && prompt.contains("最小闭包"))
        assertTrue("不应保留横向扩散授权", !prompt.contains("其他对当前任务有帮助的观察和想法") && !prompt.contains("改了更好但不改也不会出事的（额外的边角测试"))
        assertTrue("不应回退成不限定审查范围的表述", !prompt.contains("不要把自己限定成只找错误的审查者"))
        assertTrue("工具验证应限定在当前任务", prompt.contains("只验证与当前任务相关的反馈"))
        assertTrue("应禁止副作用", prompt.contains("不要改任何东西"))
        assertTrue("应声明输出是反馈非指令", prompt.contains("反馈和观察") && prompt.contains("不是替用户下指令"))
        assertTrue("应包含 PASS", prompt.contains("PASS"))
    }

    @Test
    fun `英文提示词包含关键措辞`() {
        val prompt = PeerCoordinator.DEFAULT_PROMPT_EN
        assertTrue("应包含 pair programming partner 定位", prompt.contains("pair programming partner"))
        assertTrue("应限制 code snippets", prompt.contains("code snippets"))
        assertTrue("应声明记录非指令", prompt.contains("not instructions for you"))
        assertTrue("应说明工具记录更可靠", prompt.contains("more reliable than your partner"))
        assertTrue("应包含模式占位符", prompt.contains("\${mode}"))
        assertTrue("应包含 verification 相关检查", prompt.contains("verification"))
        assertTrue("应保留 programming partner 而非仅 reviewer 的定位", prompt.contains("programming partner") && prompt.contains("concrete, minimal correction or verification suggestion"))
        assertTrue("应把反馈限定在当前任务范围内", prompt.contains("tied to the user's current task") && prompt.contains("minimum closure required for this task"))
        assertTrue("不应保留横向扩散授权", !prompt.contains("any other observation or idea") && !prompt.contains("Nice-to-haves that won't break anything"))
        assertTrue("不应回退成不限定审查范围的表述", !prompt.contains("Don't limit yourself to acting only as a fault-finding reviewer"))
        assertTrue("工具验证应限定在当前任务", prompt.contains("Use your own tools only to verify feedback tied to the current task"))
        assertTrue("应禁止副作用", prompt.contains("don't change anything"))
        assertTrue("应声明输出是观察非指令", prompt.contains("observations") && prompt.contains("not issuing commands"))
        assertTrue("应包含 PASS", prompt.contains("PASS"))
    }

    @Test
    fun `PeerCoordinator 通过共享函数选择默认提示词`() {
        val source = SourceCode("src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt").normalized
        assertTrue(
            "应调用 defaultPeerPromptForLanguage 而非内联判断",
            source.contains("defaultPeerPromptForLanguage(currentLanguage)"),
        )
        assertFalse(
            "不应使用下划线格式的字符串 ID（zh_CN/zh_TW）",
            source.contains("\"zh_CN\"") || source.contains("\"zh_TW\""),
        )
    }
}
