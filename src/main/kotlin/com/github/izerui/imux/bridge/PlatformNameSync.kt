package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 把平台上的机器人名称同步成本地名称。
 *
 * 同一机器人可能接连多次上线（自动重连、受阻恢复），名称请求并发、完成顺序不定：每次请求领一个序号，
 * 只接收该机器人最新一次请求的结果。“仍是最新请求、仍是当前连接”的校验与写入在同一把 [lock] 里完成，
 * 与连接的登记、摘除互斥，校验通过后连接不会再被换掉。
 */
internal class PlatformNameSync(
    private val scope: CoroutineScope,
    private val lock: Any,
    /** 在 [lock] 内调用；名称有变化并已写入时返回 true。 */
    private val write: (RobotId, String) -> Boolean,
    private val changed: () -> Unit,
) {
    /** 只在 [lock] 内读写。 */
    private val latest = HashMap<RobotId, Long>()
    private var nextTicket = 0L

    /**
     * [isCurrent] 在 [lock] 内调用，判断发起请求的连接是否仍是该机器人的当前连接。
     * 领序号前也要先查：旧连接的上线回调可能晚于新连接才执行到这里，若照样领号，会把新连接的请求作废。
     * 连接已失效时不发请求，返回 null。
     */
    fun sync(
        id: RobotId,
        isCurrent: () -> Boolean,
        fetch: suspend () -> String?,
    ): Job? {
        val ticket =
            synchronized(lock) {
                if (!isCurrent()) return null
                (++nextTicket).also { latest[id] = it }
            }
        return scope.launch {
            val name = fetch() ?: return@launch
            val wrote = synchronized(lock) { latest[id] == ticket && isCurrent() && write(id, name) }
            if (wrote) changed()
        }
    }
}
