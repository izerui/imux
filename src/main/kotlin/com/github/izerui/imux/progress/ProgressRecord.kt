package com.github.izerui.imux.progress

import com.github.izerui.imux.turn.TurnEvent
import com.github.izerui.imux.turn.TurnState
import com.github.izerui.imux.turn.TurnTransition

internal data class ProgressSnapshot(
    val reportedAt: Long? = null,
    val progress: TurnProgress? = null,
    val active: Boolean = false,
    val waiting: Boolean = false,
    val endReason: ProgressEndReason? = null,
)

/** 上报直接归到调用它的会话；运行态只负责开始、等待和结束。 */
internal class ProgressRecord(private val clock: () -> Long = System::currentTimeMillis) {
    private var value = ProgressSnapshot()
    private var runtimeSeenActive = false
    private var externalWaiting = false
    private var waitingChangedAt: Long? = null
    private var startedAt: Long? = null
    private var finishRequestedAt: Long? = null
    private var endedAt: Long? = null
    private var externalSequence = 0L

    @Synchronized
    fun acceptExternalSequence(sequence: Long?): Boolean {
        if (sequence == null) return true
        if (sequence <= externalSequence) return false
        externalSequence = sequence
        return true
    }

    @Synchronized
    fun start(at: Long = clock()) {
        if (startedAt?.let { it > at } == true) return
        if (endedAt?.let { it > at } == true) return
        if (value.reportedAt?.let { it > at } != true) value = ProgressSnapshot(active = true)
        runtimeSeenActive = false
        if (waitingChangedAt?.let { it < at } != false) {
            externalWaiting = false
            waitingChangedAt = null
        }
        startedAt = at
        finishRequestedAt = null
    }

    /** A Stop hook precedes other hooks; wait for an idle sample before concluding the turn. */
    @Synchronized
    fun requestFinish() {
        finishRequestedAt = clock()
    }

    @Synchronized
    fun setWaiting(waiting: Boolean, at: Long = clock()) {
        if (endedAt?.let { it > at || it == at && !value.active } == true) return
        if (startedAt?.let { it > at } == true) return
        if (externalWaiting == waiting) return
        externalWaiting = waiting
        waitingChangedAt = at
        if (value.active) value = value.copy(waiting = waiting)
    }

    @Synchronized
    fun snapshot() = value

    @Synchronized
    fun observe(
        active: Boolean,
        waiting: Boolean? = false,
        endReason: ProgressEndReason? = null,
        transitions: List<TurnTransition> = emptyList(),
        sampledAt: Long = clock(),
        sessionRunning: Boolean? = null,
    ) {
        var staleEnd = false
        for (transition in transitions) {
            staleEnd = false
            val at = transition.timestampMillis ?: sampledAt
            val newerReport = value.reportedAt?.let { it > at } == true
            val newerStart = startedAt?.let { it > at } == true
            if (newerReport || newerStart) {
                runtimeSeenActive = transition.to == TurnState.WORKING
                staleEnd = !runtimeSeenActive
                continue
            }
            if (transition.to == TurnState.WORKING) {
                val firstUnstampedStart = transition.timestampMillis == null && !runtimeSeenActive && value.active
                if (!firstUnstampedStart) {
                    value = ProgressSnapshot(active = true)
                }
                if (waitingChangedAt?.let { it <= at } != false) externalWaiting = false
                runtimeSeenActive = true
                finishRequestedAt = null
            } else {
                finish(
                    if (transition.event == TurnEvent.ABORTED) ProgressEndReason.INTERRUPTED
                    else endReason ?: ProgressEndReason.NORMAL,
                    at,
                )
            }
        }
        // The coarse completion summary describes the same old boundary, not the newer report.
        if (staleEnd && !active) return
        // Reading files happens off the EDT. An intervening report or UI event is newer than that sample.
        val latestUpdate = listOfNotNull(value.reportedAt, startedAt, waitingChangedAt, finishRequestedAt).maxOrNull()
        if (latestUpdate != null && latestUpdate > sampledAt) return
        if (active) {
            if (!value.active) value = ProgressSnapshot(active = true)
            value = value.copy(active = true, waiting = waiting ?: externalWaiting, endReason = null)
            runtimeSeenActive = true
            return
        }
        // Reuse the tab's running state when a start/report never received a matching end.
        // Waiting is deliberately excluded: a paused tab is not a finished turn.
        val idleFallback = sessionRunning == false && !(waiting ?: externalWaiting)
        if (runtimeSeenActive || endReason != null || finishRequestedAt != null || idleFallback && value.active) {
            finish(endReason ?: ProgressEndReason.NORMAL, sampledAt)
        }
    }

    private fun finish(reason: ProgressEndReason, at: Long) {
        value = value.copy(active = false, waiting = false, endReason = reason)
        runtimeSeenActive = false
        finishRequestedAt = null
        endedAt = maxOf(endedAt ?: Long.MIN_VALUE, at)
        if (waitingChangedAt?.let { it <= at } != false) externalWaiting = false
    }

    @Synchronized
    fun report(percent: Int, message: String): Boolean {
        val progress = TurnProgress(percent, message)
        if (!value.active) value = ProgressSnapshot(active = true, waiting = externalWaiting)
        value = value.copy(
            reportedAt = clock(),
            progress = progress,
            active = true,
            endReason = null,
        )
        return true
    }
}
