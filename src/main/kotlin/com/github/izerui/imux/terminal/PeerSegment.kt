package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.peer.PeerStatus

/** 横幅上结对编程段的状态。 */
enum class PeerSegmentKind { IDLE, SCHEDULED, READY, RUNNING, PASSED }

data class PeerSegmentModel(
    val kind: PeerSegmentKind,
    val agentName: String? = null,
    val round: Int = 0,
    val elapsedSeconds: Long = 0,
)

/**
 * 未绑定时是空闲，预约了本轮结束后审查一次（[scheduled] 为审查者）时是已预约；
 * 已绑定时审查中优先，其次是上一轮通过，否则是结对中。
 */
fun peerSegmentModel(
    status: PeerStatus?,
    nowMillis: Long,
    scheduled: AgentType? = null,
): PeerSegmentModel {
    if (status == null) {
        return scheduled?.let { PeerSegmentModel(PeerSegmentKind.SCHEDULED, it.displayName) } ?: PeerSegmentModel(PeerSegmentKind.IDLE)
    }
    val name = status.targetAgentType.displayName
    val progress = status.progress
    return when {
        status.running && progress != null ->
            PeerSegmentModel(
                PeerSegmentKind.RUNNING,
                name,
                progress.round,
                ((nowMillis - progress.startedAtMillis) / 1_000).coerceAtLeast(0),
            )
        status.running -> PeerSegmentModel(PeerSegmentKind.RUNNING, name)
        status.lastReviewPassed -> PeerSegmentModel(PeerSegmentKind.PASSED, name)
        else -> PeerSegmentModel(PeerSegmentKind.READY, name)
    }
}
