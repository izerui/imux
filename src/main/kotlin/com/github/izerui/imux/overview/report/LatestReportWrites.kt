package com.github.izerui.imux.overview.report

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 报告文件的写入闸门：按会话发号，只写同一会话最新那次请求；同一会话的写入一次只进行一个。
 *
 * 号按会话而不是按面板算：拖动标签页会重建面板，旧面板已经排队的写入如果只和自己的序号比，
 * 会被当成最新，晚于新面板写完就把新报告盖回旧内容。按会话发号后，新面板一领号，旧的就过期了。
 *
 * 领号不加锁：界面线程在领号，绝不能等后台的渲染和磁盘读写。写入按会话各一把锁，只在后台线程上拿，
 * 不同会话互不阻塞。拿到锁后才比号：正在写的那次照常写完，排在后面的新请求随后覆盖，新报告不会被旧的盖掉。
 *
 * 谁写和谁显示分开：写好（或写失败）后通知所有监听者，同一会话在几个分屏里显示时，号过期没去写的面板也拿到最新的报告。
 * 通知按会话只发号更新的：旧请求写完后线程被暂停、新请求先写好并通知了，旧的那条（比如「写入失败」）晚到就丢掉，
 * 不会把已经显示好的新报告又盖成失败。判断与发出在同一把小锁里，这把锁只转一手通知，不碰磁盘，领号也不经过它。
 */
internal class LatestReportWrites(
    /** 测试钩子：写完、发通知之前调用，用来复现线程在这里被暂停。 */
    private val beforeNotify: (sessionKey: String, ticket: Long) -> Unit = { _, _ -> },
) {
    private val counter = AtomicLong()
    private val latest = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String, ReportWrite?) -> Unit>()

    /** 各会话已经通知到的号；只在 [notifyLocks] 里读写。 */
    private val notified = ConcurrentHashMap<String, Long>()
    private val notifyLocks = ConcurrentHashMap<String, Any>()

    /** 监听每次写好的报告（会话键与结果，写入失败为 null）；在后台线程回调。返回注销函数。 */
    fun addListener(listener: (String, ReportWrite?) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /** 领一个号；同时作废这个会话之前领的号（还没写的都不再写）。不阻塞，可在界面线程调用。 */
    fun ticket(sessionKey: String): Long =
        // 只保留较大的号：两个线程同时领号时，后发的号不会被先发的覆盖
        counter.incrementAndGet().also { ticket -> latest.merge(sessionKey, ticket, ::maxOf) }

    /**
     * 在后台线程调用：排到这个会话的写入后，[ticket] 还是最新的号才执行 [write]，并把结果通知所有监听者；返回是否执行了。
     * 号过期时什么也不做：更新的那次写好后会通知到每个面板。
     */
    fun runIfLatest(sessionKey: String, ticket: Long, write: () -> ReportWrite?): Boolean {
        val result =
            synchronized(locks.computeIfAbsent(sessionKey) { Any() }) {
                if (latest[sessionKey] != ticket) return false
                write()
            }
        beforeNotify(sessionKey, ticket)
        synchronized(notifyLocks.computeIfAbsent(sessionKey) { Any() }) {
            // 更新的那次已经通知过：这条是晚到的旧结果，丢掉
            if (ticket <= (notified[sessionKey] ?: 0L)) return true
            notified[sessionKey] = ticket
            listeners.forEach { it(sessionKey, result) }
        }
        return true
    }
}
