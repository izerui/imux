package com.github.izerui.imux.bridge.channel

/** 按会话的滑动窗口限流。线程安全。 */
class SlidingWindowLimiter(
    private val limit: Int,
    private val windowMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val history = HashMap<String, ArrayDeque<Long>>()

    /** 放行时记账并返回 0；否则返回还需等待的毫秒数，不记账。 */
    @Synchronized
    fun tryAcquire(key: String): Long {
        val current = now()
        val times = history.getOrPut(key) { ArrayDeque() }
        while (times.isNotEmpty() && current - times.first() >= windowMillis) times.removeFirst()
        if (times.size < limit) {
            times.addLast(current)
            return 0
        }
        return times.first() + windowMillis - current
    }
}
