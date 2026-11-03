package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerReviewLifecycleTest {
    @Test
    fun `预约清除失败但保留通过结果`() {
        val failed = PeerReviewLifecycle()
        failed.recordOutcome(outcome(PeerOutcomeKind.FAILED))
        failed.schedule(AgentType.PI, "新要求")
        assertNull("旧失败不再遮住新预约", failed.outcome)
        assertEquals(ScheduledPeerReview(AgentType.PI, "新要求"), failed.scheduled)

        val passed = PeerReviewLifecycle()
        val result = outcome(PeerOutcomeKind.PASSED)
        passed.recordOutcome(result)
        passed.schedule(AgentType.CODEX, null)
        assertEquals("预约还没开始审查，保留上次通过记录", result, passed.outcome)
    }

    @Test
    fun `审查解绑保留反馈保护而轮次完成清理保护`() {
        val state = PeerReviewLifecycle()
        state.startOneShot(1)
        state.feedbackDelivered(1, "反馈", true, null)
        state.detachReview()
        assertFalse(state.isOneShot)
        assertTrue("审查结束不代表反馈处理结束", state.isHandlingFeedback)
        state.turnCompleted()
        assertFalse(state.isHandlingFeedback)
    }

    @Test
    fun `旧审查代次不能为新的单次审查设置反馈保护`() {
        val state = PeerReviewLifecycle()
        state.startOneShot(2)
        state.feedbackDelivered(1, "过时反馈", true, null)
        assertFalse(state.isHandlingFeedback)
        assertTrue("新审查仍然有效", state.isOneShot(2))
    }

    @Test
    fun `提交记录读取期间轮次结束不恢复反馈保护`() {
        val state = stagedState()
        val pending = state.feedback as PeerReviewLifecycle.Feedback.Staged
        state.turnCompleted()
        assertFalse(state.observeSubmission(pending, PeerUserInput("new", "反馈")))
        assertNull(state.feedback)
    }

    @Test
    fun `旧提交记录读取结果不能覆盖新审查的暂存反馈`() {
        val state = stagedState()
        val pending = state.feedback as PeerReviewLifecycle.Feedback.Staged
        state.bind()
        state.startOneShot(2)
        state.feedbackDelivered(2, "新反馈", false, null)
        assertFalse(state.observeSubmission(pending, PeerUserInput("new", "反馈")))
        assertEquals("新反馈", (state.feedback as PeerReviewLifecycle.Feedback.Staged).text)
    }

    @Test
    fun `暂存后没有新的用户记录时不认定已提交`() {
        val previous = PeerUserInput("old", "反馈")
        val state = PeerReviewLifecycle()
        state.startOneShot(1)
        state.feedbackDelivered(1, "反馈", false, previous)
        val pending = state.feedback as PeerReviewLifecycle.Feedback.Staged
        state.observeSubmission(pending, previous)
        assertFalse(state.isHandlingFeedback)
        state.observeSubmission(pending, null)
        assertFalse("文件未就绪也不能推断用户提交", state.isHandlingFeedback)
        state.observeSubmission(pending, PeerUserInput("new", "反馈"))
        assertTrue("新记录的相同内容才是提交", state.isHandlingFeedback)
    }

    private fun stagedState() = PeerReviewLifecycle().apply {
        startOneShot(1)
        feedbackDelivered(1, "反馈", false, PeerUserInput("old", "原任务"))
    }

    private fun outcome(kind: PeerOutcomeKind) = PeerOutcome(kind, AgentType.CODEX, 1, 1L)
}
