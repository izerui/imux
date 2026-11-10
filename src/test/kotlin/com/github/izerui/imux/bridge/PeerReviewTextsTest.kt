package com.github.izerui.imux.bridge

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.peer.PeerOutcome
import com.github.izerui.imux.peer.PeerOutcomeKind
import com.github.izerui.imux.peer.PeerReviewEvent
import com.github.izerui.imux.peer.PeerStopReason
import com.github.izerui.imux.settings.PluginLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 开着结对时手机上单独说审查这件事：开始一条，不再引出新输入的结局一条，其余不推。 */
class PeerReviewTextsTest {
    private fun outcome(kind: PeerOutcomeKind, detail: String? = null) = PeerOutcome(kind, AgentType.CODEX, 1, 0L, detail)

    private val started = PeerReviewEvent.Started("s1", AgentType.CODEX, 2, oneShot = false)
    private val startedOnce = PeerReviewEvent.Started("s1", AgentType.CODEX, 1, oneShot = true)
    private val injected = PeerReviewEvent.Finished("s1", outcome(PeerOutcomeKind.FEEDBACK), "第 12 行空指针", autoInjected = true)
    private val turnStarted = PeerReviewEvent.Stopped("s1", AgentType.CODEX, PeerStopReason.TURN_STARTED)

    /** 之后不会再引出新输入的结局。 */
    private val idle =
        listOf(
            PeerReviewEvent.Finished("s1", outcome(PeerOutcomeKind.PASSED)),
            PeerReviewEvent.Finished("s1", outcome(PeerOutcomeKind.FAILED, "调用超时")),
        ) + (PeerStopReason.entries - PeerStopReason.TURN_STARTED).map { PeerReviewEvent.Stopped("s1", AgentType.CODEX, it, maxRounds = 3) }

    private val staged = PeerReviewEvent.Finished("s1", outcome(PeerOutcomeKind.FEEDBACK), "第 12 行空指针", autoInjected = false)

    private fun zh(event: PeerReviewEvent) =
        peerReviewText(event) { key, args -> ImuxBundle.message(PluginLanguage.SIMPLIFIED_CHINESE, key, *args.toTypedArray()) }

    @Test
    fun `审查开始时告诉手机正在审查`() {
        for (event in listOf(started, startedOnce)) assertTrue(zh(event)!!.contains("正在审查"))
    }

    @Test
    fun `每条都按结对编程来说，不说会话忙闲`() {
        for (event in idle + started) {
            val text = zh(event)!!
            assertTrue(text, text.contains("结对编程"))
            assertTrue(text, !text.contains("空闲") && !text.contains("忙"))
        }
    }

    @Test
    fun `意见自动注入不推，手机上随后看得到那条输入`() {
        assertEquals(null, zh(injected))
    }

    @Test
    fun `审查中又发了消息、审查被取消时立即说明，其余不用立即发`() {
        assertTrue(zh(turnStarted)!!.contains("已取消"))
        assertTrue(turnStarted.immediate())
        for (event in idle + started + startedOnce + staged) assertTrue(!event.immediate())
    }

    @Test
    fun `意见只填进输入框时说一声，手机上不会有那条输入`() {
        val text = zh(staged)!!
        assertTrue(text.contains("IDE") && !text.contains("第 12 行空指针"))
    }

    @Test
    fun `失败原因与上限轮数写进消息`() {
        assertTrue(zh(idle[1])!!.contains("调用超时"))
        assertTrue(zh(idle.last { it is PeerReviewEvent.Stopped && it.reason == PeerStopReason.MAX_ROUNDS })!!.contains("3"))
    }

    @Test
    fun `每种语言都能排出完整的文字`() {
        for (language in PluginLanguage.entries) for (event in idle + started + startedOnce + staged) {
            val text = peerReviewText(event) { key, args -> ImuxBundle.message(language, key, *args.toTypedArray()) }
            assertTrue("$language $event", !text.isNullOrBlank() && !text.contains("{") && !text.contains("''"))
        }
    }

    @Test
    fun `持续结对的还在忙和到上限排在本轮回复之后，单次审查与其它结局不用`() {
        assertTrue(started.followsTurn())
        assertTrue(!startedOnce.followsTurn())
        for (event in idle) assertEquals(event is PeerReviewEvent.Stopped && event.reason == PeerStopReason.MAX_ROUNDS, event.followsTurn())
    }
}
