package com.github.izerui.imux.bridge.core

import com.github.izerui.imux.bridge.BundleBridgeTexts
import com.github.izerui.imux.model.AgentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexReviewDraftGuidanceTest {
    @Test fun `电脑已有草稿时手机收到草稿提示，发送正文后再次提示，回零退出`() = runBlocking {
        val menu = requireNotNull(CodexReviewMenu.parse("""
            Custom review instructions
            › Desktop draft
            enter submit · esc back
        """.trimIndent()))
        val keys = mutableListOf<String>()
        val port = object : TerminalSessionPort {
            override suspend fun readReview(target: SessionTarget) = ReviewScreen.Open(menu)
            override suspend fun submit(target: SessionTarget, text: String): SubmitResult = error("Unexpected submit")
            override suspend fun answerReviewMenu(binding: Binding, expected: CodexReviewMenu, reply: String): Boolean {
                keys += requireNotNull(expected.keysFor(reply))
                return true
            }
        }
        val texts = object : BridgeTexts by BundleBridgeTexts {
            override val reviewDraftHint = "Finish on the computer or reply 0 to go back."
            override val reviewTextHint = "Send instructions."
        }
        val messages = mutableListOf<String>()
        val dialogs = CodexReviewDialogs(port, texts) { _, text, current -> current() && messages.add(text) }
        val binding = Binding(RobotId("phone"), SessionTarget("/project", "tab", AgentType.CODEX, "session"), 1)
        dialogs.observe(binding, menu)
        val prompt = messages.single()
        assertEquals(texts.reviewDraftHint, prompt.lineSequence().last())
        assertEquals(ReviewReply.REJECTED, dialogs.answer(binding, "Replace the draft", textOnly = true))
        assertEquals(listOf(prompt, prompt), messages)
        assertEquals(emptyList<String>(), keys)
        assertEquals(ReviewReply.ACCEPTED, dialogs.answer(binding, "0", textOnly = true))
        assertEquals(listOf("\u001b"), keys)
        dialogs.observe(binding, null)
        assertEquals(listOf(prompt, prompt), messages)
    }
}
