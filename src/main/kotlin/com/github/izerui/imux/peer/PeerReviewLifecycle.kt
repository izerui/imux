package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.isUserInput
import com.github.izerui.imux.session.scanTail
import com.github.izerui.imux.session.transcriptMessage

internal enum class PeerScheduleResult { SCHEDULED, ALREADY_PAIRED, FEEDBACK_TURN, STATE_CHANGED, CLOSED }

internal data class ScheduledPeerReview(val target: AgentType, val instruction: String?)

/** 保留原始记录以区分内容相同但在不同轮次提交的消息。 */
internal data class PeerUserInput(val record: String, val text: String)

internal fun latestPeerUserInput(session: AgentSession): PeerUserInput? =
    scanTail(session.filePath, maxTailBytes = CONVERSATION_TAIL_BYTES) { lines ->
        lines.asReversed().firstNotNullOfOrNull { line ->
            transcriptMessage(line, session.agentType, Int.MAX_VALUE)
                ?.takeIf { it.isUserInput() }
                ?.let { PeerUserInput(line, it.text) }
        }
    }

/**
 * 一个会话的单次审查生命周期；写操作由协调器在 EDT 串行执行。
 *
 * 审查与反馈是两个阶段：审查收尾只解除预约/单次绑定，反馈保护到主会话轮次结束才解除。
 * 暂存不是提交，须看到新的用户记录且内容匹配才进入反馈处理阶段。
 * 主动绑定会开始新的审查意图；解绑、关闭或换会话则丢弃整个对象。
 */
internal class PeerReviewLifecycle {
    private sealed interface Review {
        data object Idle : Review
        data class Scheduled(val request: ScheduledPeerReview) : Review
        data class OneShot(val generation: Long) : Review
    }

    internal sealed interface Feedback {
        data class Staged(val text: String, val previousInput: PeerUserInput?) : Feedback
        data object Handling : Feedback
    }

    @Volatile private var review: Review = Review.Idle
    @Volatile var feedback: Feedback? = null
        private set
    @Volatile var outcome: PeerOutcome? = null
        private set

    val scheduled: ScheduledPeerReview? get() = (review as? Review.Scheduled)?.request
    val isOneShot: Boolean get() = review is Review.OneShot
    val isHandlingFeedback: Boolean get() = feedback == Feedback.Handling

    fun isOneShot(generation: Long): Boolean = (review as? Review.OneShot)?.generation == generation

    fun schedule(target: AgentType, instruction: String?) {
        check(!isOneShot && !isHandlingFeedback)
        review = Review.Scheduled(ScheduledPeerReview(target, instruction))
        feedback = null
        clearFailure()
    }

    fun takeScheduled(): ScheduledPeerReview? = scheduled?.also { review = Review.Idle }

    fun bind() {
        review = Review.Idle
        feedback = null
        clearFailure()
    }

    fun startOneShot(generation: Long) {
        review = Review.OneShot(generation)
    }

    fun keepPairing(): Boolean {
        if (!isOneShot) return false
        review = Review.Idle
        feedback = null
        return true
    }

    /** 自动收尾、新轮抢占或取消审查不能撤销已经送出的反馈。 */
    fun detachReview() {
        review = Review.Idle
    }

    fun feedbackDelivered(generation: Long, text: String, autoInjected: Boolean, previousInput: PeerUserInput?) {
        if (!isOneShot(generation)) return
        feedback = if (autoInjected) Feedback.Handling else Feedback.Staged(collapseWhitespace(text), previousInput)
    }

    fun observeSubmission(expected: Feedback.Staged, latest: PeerUserInput?): Boolean {
        if (feedback !== expected) return false
        if (latest == null || latest.record == expected.previousInput?.record) return true
        // 用户改写或替换了输入框内容时不猜测意图，只保护能确认提交的反馈。
        feedback = if (collapseWhitespace(latest.text) == expected.text) Feedback.Handling else null
        return true
    }

    fun turnCompleted() {
        feedback = null
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
