package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentType

internal enum class PeerScheduleResult { SCHEDULED, ALREADY_PAIRED, FEEDBACK_TURN, CLOSED }

internal data class ScheduledPeerReview(val target: AgentType, val instruction: String?)

/**
 * 一个会话的单次审查生命周期；写操作由协调器在 EDT 串行执行。
 *
 * 审查与反馈是两个阶段：审查收尾只解除预约/单次绑定，反馈保护到主会话轮次结束才解除。
 * 反馈总是直接发给主会话，送出即进入反馈处理阶段。
 * 主动绑定会开始新的审查意图；解绑、关闭或换会话则丢弃整个对象。
 */
internal class PeerReviewLifecycle {
    private sealed interface Review {
        data object Idle : Review
        data class Scheduled(val request: ScheduledPeerReview) : Review
        data class OneShot(val generation: Long) : Review
    }

    @Volatile private var review: Review = Review.Idle
    @Volatile var isHandlingFeedback: Boolean = false
        private set
    @Volatile var outcome: PeerOutcome? = null
        private set

    val scheduled: ScheduledPeerReview? get() = (review as? Review.Scheduled)?.request
    val isOneShot: Boolean get() = review is Review.OneShot

    fun isOneShot(generation: Long): Boolean = (review as? Review.OneShot)?.generation == generation

    fun schedule(target: AgentType, instruction: String?) {
        check(!isOneShot && !isHandlingFeedback)
        review = Review.Scheduled(ScheduledPeerReview(target, instruction))
        isHandlingFeedback = false
        clearFailure()
    }

    fun takeScheduled(): ScheduledPeerReview? = scheduled?.also { review = Review.Idle }

    /** 不清反馈状态，以免取消操作绕过反馈轮保护。 */
    fun cancelScheduled(): Boolean = takeScheduled() != null

    fun bind() {
        review = Review.Idle
        isHandlingFeedback = false
        clearFailure()
    }

    fun startOneShot(generation: Long) {
        review = Review.OneShot(generation)
    }

    fun keepPairing(): Boolean {
        if (!isOneShot) return false
        review = Review.Idle
        isHandlingFeedback = false
        return true
    }

    /** 自动收尾、新轮抢占或取消审查不能撤销已经送出的反馈。 */
    fun detachReview() {
        review = Review.Idle
    }

    fun feedbackDelivered(generation: Long) {
        if (isOneShot(generation)) isHandlingFeedback = true
    }

    fun turnCompleted() {
        isHandlingFeedback = false
    }

    fun reviewStarted() {
        outcome = null
    }

    fun recordOutcome(result: PeerOutcome) {
        outcome = result
    }

    private fun clearFailure() {
        if (outcome?.kind == PeerOutcomeKind.FAILED) outcome = null
    }
}
