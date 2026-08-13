package com.github.izerui.imux.monitor

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.session.KeyDrift
import com.github.izerui.imux.session.stillApplicable
import java.util.concurrent.ConcurrentHashMap

/**
 * 把「终端换了会话」这件事落到实处：换 key、换标题、把轮次监控挪到新会话文件上。
 *
 * 抽出来是为了能测。它自己不碰 EDT、不碰终端、不碰文件系统，所有外部动作以函数注入，
 * 因此「迁移之后到底有没有纳入监控」这类断言可以在无平台的单测里直接跑——
 * 而这恰恰是最容易静默失效、又最难在实机上发现的一环。
 *
 * **为什么需要 [retryPendingWatches] 这条补挂通路**：挂监控要的是会话**文件路径**，
 * 而路径只有扫描结果里才有。迁移与扫描谁先谁后并不确定：
 * - claude、codex 的迁移由 [SessionMonitor.probeSessionDrift] 发起，而它的源头正是
 *   刚跑完的那次扫描，所以扫描必定在前，`sessionOf` 一定查得到
 * - pi 反过来。它的扩展在 `session_start` 那一刻就把新会话上报过来，此时会话文件
 *   刚落盘、下一轮扫描还要等约 3 秒，`sessionOf` 返回 null
 *
 * pi 的那一路若在查不到时直接放弃，就**永久**不会被监控：之后没有任何路径会补挂
 * （`applyNewBindings` 的 key 已被上报迁走，`LiveSessionProbe` 按 `preassignsSessionId`
 * 把 pi 整个滤掉了），而 pi 没有运行态文件，完成提醒与运行转圈全靠轮次监控。
 * 症状是提醒静默消失，界面上一切正常。
 *
 * 同理，迁移**失败**的那笔也必须留着重试：claude、codex 能容忍单次失败是因为
 * [SessionMonitor.requestDriftProbe] 登记了多次重试由轮询推进；而 pi 的一次 `/new`
 * 只产生**一次** HTTP 上报，失败就再没有第二次机会。
 */
internal class SessionDriftApplier(
    private val sessionOf: (String) -> AgentSession?,
    private val openTabs: () -> Map<String, String>,
    private val rebindKey: (from: String, to: String, title: String) -> Boolean,
    private val startWatching: (AgentSession) -> Unit,
    private val clearUnread: (String) -> Unit,
) {

    /**
     * 已经迁过去、但当时还查不到文件路径的会话 id。
     *
     * 并发容器：写入发生在 EDT，但读写都可能被协程续体切在不同线程上恢复，
     * 不值得赌调度顺序。
     */
    private val awaitingWatch = ConcurrentHashMap.newKeySet<String>()

    /** 迁移没成、等下一轮再试的漂移，按 tabId 去重——同一个标签页只留最新那笔。 */
    private val awaitingRebind = ConcurrentHashMap<String, RetriedDrift>()

    /** 迁移一批漂移。返回是否全部成功——没成功的会自己留到下一轮，调用方不必再报一次。 */
    fun apply(drifts: List<KeyDrift>): Boolean {
        // 探测是异步的，这期间标签页可能已经关掉、或被关掉后又重新打开成另一个终端。
        // 只认那些「tabId 还在、且仍记着我们探测时看到的旧 id」的结果。
        val applicable = stillApplicable(drifts, openTabs())
        if (applicable.isEmpty()) return false
        return applicable.map(::applyOne).all { it }
    }

    /**
     * 每轮扫描之后调用：补挂等着的监控，重试没成的迁移。
     *
     * 挂在扫描之后是因为两件事等的都是扫描——文件路径由它给出，
     * 而占着目标 key 的那个重复终端通常也在这期间被收拾掉了。
     */
    fun retryPendingWatches() {
        retryRebinds()
        retryWatches()
    }

    private fun applyOne(drift: KeyDrift): Boolean {
        val session = sessionOf(drift.to)
        if (!rebindKey(drift.from, drift.to, session?.title ?: defaultTitle(drift.to))) {
            // 目标被占着之类，这次迁不了。留到下一轮，别接着做后面的收尾动作
            rememberFailedRebind(drift)
            return false
        }
        awaitingRebind.remove(drift.tabId)
        // 新会话直到落盘才有文件路径。此刻查不到就记下来等扫描，绝不能静默放弃
        if (session != null) markWatched(session) else awaitingWatch.add(drift.to)
        // 旧 id 已经不是这个终端的身份了，挂在它上面的未读该一并撤掉
        clearUnread(drift.from)
        return true
    }

    private fun rememberFailedRebind(drift: KeyDrift) {
        val attempts = (awaitingRebind[drift.tabId]?.takeIf { it.drift == drift }?.attempts ?: 0) + 1
        // 有上限是必须的：目标 key 若被一个长期存在的终端占着，重试永远不会成功，
        // 不封顶就是每轮扫描都白跑一遍并刷一条 WARN
        if (attempts > MAX_REBIND_ATTEMPTS) awaitingRebind.remove(drift.tabId)
        else awaitingRebind[drift.tabId] = RetriedDrift(drift, attempts)
    }

    private fun retryRebinds() {
        if (awaitingRebind.isEmpty()) return
        val tabs = openTabs()
        awaitingRebind.values.map { it.drift }.forEach { drift ->
            // 标签页关了、或已经不记着那个旧 id 了（用户关掉又开了别的终端）：
            // 硬迁就是张冠李戴，直接丢弃
            if (tabs[drift.tabId] != drift.from) awaitingRebind.remove(drift.tabId)
            else applyOne(drift)
        }
    }

    private fun retryWatches() {
        if (awaitingWatch.isEmpty()) return
        // 标签页已经关掉（或又迁去了别的会话）的一律丢弃：再挂上去就是盯着一个用户
        // 根本没在 imux 里跑的会话，完成时会弹出莫名其妙的提醒。这也是队列的上界。
        val openKeys = openTabs().values.toSet()
        awaitingWatch.toList().forEach { sessionId ->
            if (sessionId !in openKeys) {
                awaitingWatch.remove(sessionId)
                return@forEach
            }
            sessionOf(sessionId)?.let(::markWatched)
        }
    }

    private fun markWatched(session: AgentSession) {
        awaitingWatch.remove(session.id)
        startWatching(session)
    }

    private data class RetriedDrift(val drift: KeyDrift, val attempts: Int)

    private companion object {
        /**
         * 一笔迁移最多重试几轮。重试由扫描推进（约 3 秒一轮），5 次约合 15 秒——
         * 与 [SessionMonitor.DRIFT_PROBE_ATTEMPTS] 同源同理。
         */
        const val MAX_REBIND_ATTEMPTS = 5
    }
}

/** 查不到会话时的兜底标题，与列表里的占位保持一致。 */
internal fun defaultTitle(sessionId: String): String = "会话 ${sessionId.take(8)}"
