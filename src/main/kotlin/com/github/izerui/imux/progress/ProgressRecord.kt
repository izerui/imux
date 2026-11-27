package com.github.izerui.imux.progress

internal data class ProgressSnapshot(
    val reportedAt: Long? = null,
    val progress: TurnProgress? = null,
    val active: Boolean = false,
    val waiting: Boolean = false,
    val endReason: ProgressEndReason? = null,
)

/** 上报直接归到调用它的会话；运行态只负责开始、等待和结束。 */
internal class ProgressRecord {
    private var value = ProgressSnapshot()
    private var runtimeSeenActive = false

    @Synchronized
    fun snapshot() = value

    @Synchronized
    fun observe(active: Boolean, waiting: Boolean = false, endReason: ProgressEndReason? = null) {
        if (active) {
            if (!value.active) value = ProgressSnapshot(active = true)
            value = value.copy(active = true, waiting = waiting, endReason = null)
            runtimeSeenActive = true
            return
        }
        if (runtimeSeenActive || endReason != null) {
            value = value.copy(
                active = false,
                waiting = false,
                endReason = endReason ?: ProgressEndReason.NORMAL,
            )
            runtimeSeenActive = false
        }
    }

    @Synchronized
    fun report(percent: Int, message: String): Boolean {
        val progress = TurnProgress(percent, message)
        if (!value.active) value = ProgressSnapshot(active = true)
        value = value.copy(
            reportedAt = System.currentTimeMillis(),
            progress = progress,
            active = true,
            endReason = null,
        )
        return true
    }
}
