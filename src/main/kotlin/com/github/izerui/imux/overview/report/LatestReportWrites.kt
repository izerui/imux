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
 */
internal class LatestReportWrites {
    private val counter = AtomicLong()
    private val latest = ConcurrentHashMap<String, Long>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** 领一个号；同时作废这个会话之前领的号（还没写的都不再写）。不阻塞，可在界面线程调用。 */
    fun ticket(sessionKey: String): Long =
        // 只保留较大的号：两个线程同时领号时，后发的号不会被先发的覆盖
        counter.incrementAndGet().also { ticket -> latest.merge(sessionKey, ticket, ::maxOf) }

    /** 在后台线程调用：排到这个会话的写入后，[ticket] 还是最新的号才执行 [write]，返回是否执行了。 */
    fun runIfLatest(sessionKey: String, ticket: Long, write: () -> Unit): Boolean =
        synchronized(locks.computeIfAbsent(sessionKey) { Any() }) {
            if (latest[sessionKey] != ticket) return false
            write()
            true
        }
}
