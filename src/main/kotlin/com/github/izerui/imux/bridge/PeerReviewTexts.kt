package com.github.izerui.imux.bridge

import com.github.izerui.imux.peer.PeerOutcomeKind
import com.github.izerui.imux.peer.PeerReviewEvent
import com.github.izerui.imux.peer.PeerStopReason
import com.github.izerui.imux.terminal.PairMessages
import com.github.izerui.imux.terminal.pluginMessages

/**
 * 结对审查推到手机的文字。没开结对时手机只收每轮回复，什么也不加；开了结对编程，就单独说审查这件事：
 * 开始审查一条，审查有了不再引出新输入的结局再一条。审查进度与意见不推：提了意见时手机随后就会收到
 * 那条「电脑端输入」，主会话接着下一轮。返回 null 表示不推。
 */
internal fun peerReviewText(
    event: PeerReviewEvent,
    message: PairMessages = pluginMessages,
): String? {
    val reviewer = event.targetAgentType.displayName
    return when (event) {
        is PeerReviewEvent.Started -> message("bridge.peer.started", listOf(reviewer))
        is PeerReviewEvent.Finished -> {
            val outcome = event.outcome
            when (outcome.kind) {
                PeerOutcomeKind.PASSED -> message("bridge.peer.passed", listOf(reviewer))
                PeerOutcomeKind.FAILED -> message("bridge.peer.failed", listOf(reviewer, outcome.detail.orEmpty()))
                // 意见自动交给主会话后，手机上随后就是那条「电脑端输入」，不再重复说；
                // 只填进输入框的不会有那条输入，要说一声
                PeerOutcomeKind.FEEDBACK -> if (event.autoInjected) null else message("bridge.peer.feedback.staged", listOf(reviewer))
            }
        }
        is PeerReviewEvent.Stopped ->
            when (event.reason) {
                PeerStopReason.CANCELLED -> message("bridge.peer.cancelled", listOf(reviewer))
                PeerStopReason.UNBOUND -> message("bridge.peer.unbound", listOf(reviewer))
                // 新一轮是有人发了消息，手机上看得到
                PeerStopReason.TURN_STARTED -> null
                PeerStopReason.MAX_ROUNDS -> message("bridge.peer.maxRounds", listOf(event.maxRounds))
                PeerStopReason.INTERRUPTED -> message("bridge.peer.interrupted", listOf(reviewer))
                PeerStopReason.SESSION_CHANGED -> message("bridge.peer.sessionChanged", listOf(reviewer))
            }
    }?.trim()
}

/** 持续结对的审查开始、到上限不再审查，说的都是刚结束的那一轮，要排在这一轮的回复之后。 */
internal fun PeerReviewEvent.followsTurn(): Boolean =
    (this is PeerReviewEvent.Started && !oneShot) || (this is PeerReviewEvent.Stopped && reason == PeerStopReason.MAX_ROUNDS)
