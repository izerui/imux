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
        state.feedbackDelivered(1)
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
        state.feedbackDelivered(1)
        assertFalse(state.isHandlingFeedback)
        assertTrue("新审查仍然有效", state.isOneShot(2))
    }

    private fun outcome(kind: PeerOutcomeKind) = PeerOutcome(kind, AgentType.CODEX, 1, 1L)
}
