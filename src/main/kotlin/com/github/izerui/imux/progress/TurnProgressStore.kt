package com.github.izerui.imux.progress

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList

/** 工程内保存每个会话最后一次直接上报；不读取或解析 CLI 会话文件。 */
@Service(Service.Level.PROJECT)
internal class TurnProgressStore : Disposable {
    private val entries = mutableMapOf<String, ProgressRecord>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Synchronized
    fun snapshot(sessionId: String): ProgressSnapshot =
        entries[sessionId]?.snapshot() ?: ProgressSnapshot()

    @Synchronized
    fun register(sessionId: String) {
        entries.getOrPut(sessionId) { ProgressRecord() }
    }

    fun observe(
        sessionId: String,
        active: Boolean,
        waiting: Boolean?,
        endReason: ProgressEndReason? = null,
        transitions: List<com.github.izerui.imux.turn.TurnTransition> = emptyList(),
        sampledAt: Long = System.currentTimeMillis(),
    ) {
        val changed =
            synchronized(this) {
                val record = entries.getOrPut(sessionId) { ProgressRecord() }
                val before = record.snapshot()
                record.observe(active, waiting, endReason, transitions, sampledAt)
                before != record.snapshot()
            }
        if (changed) publish()
    }

    fun start(sessionId: String, at: Long = System.currentTimeMillis(), sequence: Long? = null) =
        update(sessionId) { if (it.acceptExternalSequence(sequence)) it.start(at) }

    fun requestFinish(sessionId: String) = update(sessionId) { it.requestFinish() }

    fun setWaiting(sessionId: String, waiting: Boolean, at: Long = System.currentTimeMillis(), sequence: Long? = null) =
        update(sessionId) { if (it.acceptExternalSequence(sequence)) it.setWaiting(waiting, at) }

    private fun update(sessionId: String, action: (ProgressRecord) -> Unit) {
        val changed = synchronized(this) {
            val record = entries.getOrPut(sessionId) { ProgressRecord() }
            val before = record.snapshot()
            action(record)
            before != record.snapshot()
        }
        if (changed) publish()
    }

    fun report(sessionId: String, percent: Int, message: String): Boolean {
        val accepted =
            synchronized(this) {
                entries.getOrPut(sessionId) { ProgressRecord() }.report(percent, message)
            }
        if (accepted) publish()
        return accepted
    }

    fun forget(sessionId: String) {
        synchronized(this) { entries.remove(sessionId) }
        publish()
    }

    fun listen(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent, Disposable { listeners -= listener })
    }

    private val notificationPending = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun publish() {
        if (!notificationPending.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            notificationPending.set(false)
            listeners.forEach { it() }
        }
    }

    override fun dispose() {
        synchronized(this) { entries.clear() }
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): TurnProgressStore =
            project.getService(TurnProgressStore::class.java)
    }
}
