package com.github.izerui.imux.peer

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class PeerRun {
    val cancelled = AtomicBoolean(false)
    val reviewing = AtomicBoolean(false)
    val process = AtomicReference<Process?>()

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
