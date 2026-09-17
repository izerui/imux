package com.github.izerui.imux.monitor

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.ClaudeRuntimeIndex
import com.github.izerui.imux.session.KeyDrift
import com.github.izerui.imux.session.LiveSessionProbe
import com.github.izerui.imux.session.LiveTab
import com.github.izerui.imux.session.SessionListModel
import com.github.izerui.imux.session.claudeDriftPids
import com.github.izerui.imux.session.codexPids
import com.github.izerui.imux.session.driftOf
import com.github.izerui.imux.session.readHeldRollouts
import com.github.izerui.imux.session.readTabId
import com.github.izerui.imux.terminal.TerminalHost
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话漂移的探测与落地。
 *
 * 从 [SessionMonitor] 中提取：用户在终端里敲 `/clear`、`/new` 后 CLI 换了会话 id
 * 而进程不变，本协调器负责发现这件事并把终端迁到新 id 下。
 *
 * **绑定与漂移走同一条落地通路**（转成 [KeyDrift] 交给 [applyDrifts]），不是为了
 * 少写几行：pi 的终端可能在扫描之前就已经被上报迁到真实 id 上了，此时这笔绑定
 * 是一笔**已经完成**的迁移。分成两条路的话，`rebindKey("pending-N", …)` 会因为
 * 找不到 view 而失败并刷一条 WARN（那条日志本是用来抓真故障的），
 * 同时把紧随其后的挂监控一并跳过。交给
 * [com.github.izerui.imux.session.stillApplicable] 判断即可——
 * 标签页已经不记着 pending key 了，这笔自然就被滤掉，不会有任何多余动作。
 */
internal class DriftCoordinator(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
    private val projectPath: String,
    private val model: SessionListModel,
    private val runtimeIndex: ClaudeRuntimeIndex,
    private val terminalHost: () -> TerminalHost,
    private val notifyListeners: () -> Unit,
    private val clearUnread: (String) -> Unit,
) {
    private val probing = AtomicBoolean(false)

    /** 还允许尝试几次会话漂移探测，见 [requestDriftProbe]。 */
    private val driftProbeAttempts = AtomicInteger(0)

    /** 每次出现新的无主会话都会递增，用于区分探测期间到达的新触发器。 */
    private val driftProbeGeneration = AtomicInteger(0)

    /**
     * 会话迁移的落地器。**必须长期持有**：它记着「已迁移但还等着扫描给出文件路径」
     * 的会话，以及没迁成要重试的那些。每次现造一个就等于把这些队列扔掉，
     * pi 的会话会因此永远进不了轮次监控，见该类的说明。
     */
    private val driftApplier =
        SessionDriftApplier(
            sessionOf = { model.sessionOf(it) },
            openTabs = { terminalHost().openTabsByTabId() },
            rebindKey = { from, to, title ->
                terminalHost().rebindKey(from, to, title)
            },
            startWatching = {
                terminalHost().startWatchingTurn(it.id, it.agentType, it.filePath)
            },
            clearUnread = clearUnread,
        )

    /**
     * 把刚发生的绑定告知终端宿主：新建会话的终端原本记在合成 key 下，
     * 拿到真实 id 后必须迁过去。
     *
     * [SessionListModel.drainNewBindings] 是破坏性读取，取走即清空，所以只能有一个
     * 消费者，且这个消费者必须一直活着。原先它挂在会话树的重绘里，而树是工具窗口
     * 懒加载出来、可被销毁的东西——树没接住，这笔迁移就永远丢了。
     */
    fun applyNewBindings() {
        if (project.isDisposed) return
        val bindings = model.drainNewBindings()
        if (bindings.isEmpty()) return

        val tabIdOf =
            terminalHost()
                .openTabsByTabId()
                .entries
                .associate { (tabId, sessionKey) -> sessionKey to tabId }
        applyDrifts(
            bindings.mapNotNull { (pendingKey, sessionId) ->
                val tabId = tabIdOf[pendingKey] ?: return@mapNotNull null
                KeyDrift(tabId, from = pendingKey, to = sessionId)
            },
        )
    }

    /**
     * 无主新会话出现时登记一次探测意图。
     *
     * **不能直接把它当成一次性的触发器**：探测未必一次就成——运行态文件可能还没更新、
     * `lsof` 可能超时、目标 key 可能被用户刚开的终端占着。而 `/clear` 只产生**一次**
     * 无主会话，触发器一旦消费掉就没有下一次了，终端会永久停在旧 id 上，
     * 且失败是静默的。所以登记的是**允许重试的次数**，由后续轮询推进。
     */
    fun requestDriftProbe() {
        if (model.drainUnclaimedSessions().isEmpty()) return
        driftProbeGeneration.incrementAndGet()
        driftProbeAttempts.set(DRIFT_PROBE_ATTEMPTS)
        probeSessionDrift()
    }

    /**
     * 发现有终端在 `/clear`、`/new` 之后换了会话 id，把它迁到新 id 下。
     *
     * **为什么不能靠 pending 机制兜住**：pending 只在插件自己发起「新建」时登记。
     * 用户在终端里敲 `/clear`，CLI 换一个会话 id 而进程不变，插件这边没有任何人在
     * 等它——那个新会话就成了无主的，而终端一直记在旧 id 下。后果是标题停更、
     * 未读清不掉、轮次监控盯着一个不再增长的文件，再点新会话还会以真实 id 重开一个
     * `--resume` 终端，与仍在运行的原进程抢同一个会话。
     *
     * **只在登记过探测意图时才跑**，不是每轮都跑：codex 那侧要 `lsof`，成本不低，
     * 而无主新会话出现的那一刻正是换 id 发生的时刻，没必要平时空转。
     */
    fun probeSessionDrift() {
        if (project.isDisposed) return
        if (driftProbeAttempts.get() <= 0) return
        if (!probing.compareAndSet(false, true)) return
        val generation = driftProbeGeneration.get()

        coroutineScope.launch(Dispatchers.IO) {
            var migrated = false
            try {
                val host = terminalHost()
                val openTabs = host.openTabsByTabId()
                if (openTabs.isEmpty()) return@launch
                val openTypes = host.openTabAgentTypes()

                val runtimeSessions = runtimeIndex.load(projectPath).values
                val byPid = runtimeSessions.associateBy { it.pid }
                val claudePids = claudeDriftPids(runtimeSessions)
                val live =
                    LiveSessionProbe(
                        pidsOf = { type ->
                            when {
                                type !in openTypes -> emptyList()
                                type == AgentType.CLAUDE -> claudePids
                                else -> codexPids()
                            }
                        },
                        tabIdOf = ::readTabId,
                        claudeSessionOf = { pid -> byPid[pid]?.sessionId },
                        rolloutsHeldBy = ::readHeldRollouts,
                        isWindows = SystemInfo.isWindows,
                    ).probe()

                val drifts = driftOf(openTabs, live)
                if (drifts.isEmpty() || project.isDisposed) return@launch
                withContext(Dispatchers.EDT) { migrated = applyDrifts(drifts) }
            } finally {
                if (driftProbeGeneration.get() == generation) {
                    if (migrated) driftProbeAttempts.set(0) else driftProbeAttempts.decrementAndGet()
                }
                probing.set(false)
                if (driftProbeGeneration.get() != generation) probeSessionDrift()
            }
        }
    }

    /**
     * 在 EDT 应用探测结果：换 key、换标题、把轮次监控挪到新会话文件上。
     *
     * 返回是否全部迁移成功——没成功的话调用方要保留重试次数。
     */
    fun applyDrifts(drifts: List<KeyDrift>): Boolean {
        if (project.isDisposed) return false
        val allMigrated = driftApplier.apply(drifts)
        notifyListeners()
        return allMigrated
    }

    fun retryPendingWatches() {
        driftApplier.retryPendingWatches()
    }

    companion object {
        /**
         * 一次换 id 最多探测几轮。
         *
         * 重试由既有的运行态轮询推进（约 3 秒一轮），因此这个数字就是「给 CLI 多久
         * 把运行态或文件句柄更新到位」。取 5 约合 15 秒：本机实测 claude 换 id 后
         * 运行态文件几乎立刻更新，留这么多是给 codex 的 `lsof` 与慢盘兜底。
         * 有上限是必须的——用户在 IDE 外面自己开的会话永远是无主的，
         * 不封顶就会每轮都去翻一遍进程表。
         */
        private const val DRIFT_PROBE_ATTEMPTS = 5
    }
}
