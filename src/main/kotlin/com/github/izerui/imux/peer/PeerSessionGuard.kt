package com.github.izerui.imux.peer

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class PeerRun {
    val cancelled = AtomicBoolean(false)
    val reviewing = AtomicBoolean(false)
    val process = AtomicReference<Process?>()
    private val progressLock = Any()
    private var round = 0
    private var startedAtMillis = System.currentTimeMillis()
    private var completedActions = 0
    private var current = PeerProgressEvent(PeerProgressKind.STARTING)

    fun startProgress(round: Int) = synchronized(progressLock) {
        this.round = round
        startedAtMillis = System.currentTimeMillis()
        completedActions = 0
        current = PeerProgressEvent(PeerProgressKind.STARTING)
    }

    fun recordProgress(event: PeerProgressEvent) = synchronized(progressLock) {
        current = event
        if (event.kind == PeerProgressKind.TOOL_FINISHED) completedActions++
    }

    fun progressSnapshot(): PeerProgressSnapshot = synchronized(progressLock) {
        PeerProgressSnapshot(
            round = round,
            startedAtMillis = startedAtMillis,
            completedActions = completedActions,
            current = current,
        )
    }

    fun attach(started: Process?) {
        process.set(started)
        if (started != null && cancelled.get()) destroyProcessTree(started)
    }

    fun cancel() {
        cancelled.set(true)
        process.getAndSet(null)?.let(::destroyProcessTree)
    }
}

internal class PeerSessionGuard {
    private val lock = Any()
    private var activeRun: PeerRun? = null
    private var pending = false

    val isReviewing: Boolean
        get() = synchronized(lock) { activeRun?.reviewing?.get() == true }

    val hasPending: Boolean
        get() = synchronized(lock) { pending }

    fun progressSnapshot(): PeerProgressSnapshot? = synchronized(lock) {
        activeRun?.takeIf { it.reviewing.get() }?.progressSnapshot()
    }

    fun tryStart(): PeerRun? = synchronized(lock) {
        if (activeRun != null) {
            pending = true
            null
        } else {
            PeerRun().also { activeRun = it }
        }
    }

    fun isActive(run: PeerRun): Boolean = synchronized(lock) { activeRun === run }

    fun onFinished(run: PeerRun): PeerRun? = synchronized(lock) {
        if (activeRun !== run) return@synchronized null
        run.cancel()
        activeRun = null
        if (pending) {
            pending = false
            PeerRun().also { activeRun = it }
        } else {
            null
        }
    }

    fun cancel() = synchronized(lock) {
        pending = false
        activeRun?.cancel()
        activeRun = null
    }
}
