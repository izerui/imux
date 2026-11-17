package com.github.izerui.imux.overview.report

/**
 * 报告文件的写入闸门：按会话发号，只写同一会话最新那次请求；写入一次只进行一个。
 *
 * 号按会话而不是按面板算：拖动标签页会重建面板，旧面板已经排队的写入如果只和自己的序号比，
 * 会被当成最新，晚于新面板写完就把新报告盖回旧内容。按会话发号后，新面板一领号，旧的就过期了。
 */
internal class LatestReportWrites {
    private val latest = HashMap<String, Long>()
    private var counter = 0L

    /** 领一个号；同时作废这个会话之前领的号（还没写的都不再写）。 */
    @Synchronized
    fun ticket(sessionKey: String): Long = (++counter).also { latest[sessionKey] = it }

    /** [ticket] 还是 [sessionKey] 最新的号才执行 [write]，返回是否执行了。 */
    @Synchronized
    fun runIfLatest(sessionKey: String, ticket: Long, write: () -> Unit): Boolean {
        if (latest[sessionKey] != ticket) return false
        write()
        return true
    }
}
