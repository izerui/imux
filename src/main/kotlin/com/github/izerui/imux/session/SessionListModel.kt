package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.application.ApplicationManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

data class PendingSession(
    val key: String,
    val agentType: AgentType,
    val startedAt: Instant,
    /** 启动前就已确定的会话 id，见 [SessionListModel.registerPending]。 */
    val sessionId: String? = null,
)

sealed interface ListEntry {
    data class Existing(
        val session: AgentSession,
    ) : ListEntry

    data class Pending(
        val pending: PendingSession,
    ) : ListEntry
}

/**
 * 列表的全部可变状态集中于此：扫描结果、未绑定的新会话临时条目、绑定关系。
 *
 * 依赖以函数形式注入（scan / clock），使本类完全脱离文件系统与墙钟，便于测试。
 *
 * 绑定规则：只认确定的身份，不猜。
 * 1. 启动前就定下 id 的 pending（claude、pi 用 `--session-id`）只认领同 id 的会话
 * 2. 定不下 id 的（codex 没有这个参数）不在这里认领：新会话一律报成无主，由进程探测按终端身份迁移，
 *    迁移落地时经 [claimPending] 认领。原先按「时间窗内启动最晚的 pending」猜，同时新建两个会话、
 *    在另一个标签里 `/new`、或在 IDE 外开会话时都会认错，认错后也不会再有无主会话触发纠正
 * 3. 一个 pending 一旦绑定就不再参与后续绑定
 * 4. 超过 PENDING_TTL 仍未绑定的 pending 在 refresh 时被清除
 */
class SessionListModel(
    private val scan: () -> List<AgentSession>,
    private val clock: () -> Instant,
) {
    private var sessions: List<AgentSession> = emptyList()
    private val pendings = mutableListOf<PendingSession>()
    private val bindings = mutableMapOf<String, String>()

    /**
     * 刚发生、尚未被消费的绑定：pendingKey -> 会话真实 id。
     *
     * 终端是以 openNew 时的合成 key 记录的，绑定后必须换成会话真实 id，
     * 否则运行中标识失效、再次点击会重开一个终端。消费方取走后即清空。
     */
    private val newBindings = mutableListOf<Pair<String, String>>()

    /**
     * 本轮出现、却没有任何 pending 认领的新会话。
     *
     * 终端里 `/clear`（claude）、`/new`（codex）就是这个形态：CLI 换了会话 id 而进程
     * 不变，插件这边没有任何人在等它。消费方据此去探测进程，把终端迁到新 id 下。
     * 与 [newBindings] 一样是破坏性读取。
     */
    private val unclaimed = mutableListOf<String>()
    private var knownIds: Set<String> = emptySet()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private var pendingSeq = 0

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun registerPending(
        agentType: AgentType,
        sessionId: String? = null,
    ): PendingSession {
        ApplicationManager.getApplication()?.assertIsDispatchThread()
        val pending =
            PendingSession(
                key = "pending-${pendingSeq++}",
                agentType = agentType,
                startedAt = clock(),
                sessionId = sessionId,
            )
        pendings.add(pending)
        notifyListeners()
        return pending
    }

    fun boundIdFor(key: String): String? = bindings[key]

    /**
     * 取消尚未产生可恢复历史的新会话。
     *
     * 通常关闭时传入的是 pending key；pi 例外：它会在首次扫描前通过扩展上报，
     * 把终端提前迁到预分配的真实 sessionId。此时仍要能找到原 pending，否则
     * 只有 session header 的空会话会一直显示到 30 分钟超时。
     */
    fun cancelPending(key: String): Boolean {
        ApplicationManager.getApplication()?.assertIsDispatchThread()
        val pending = pendings.firstOrNull { it.key == key || it.sessionId == key } ?: return false
        if (pending.key in bindings) return false
        val removed = pendings.remove(pending)
        if (removed) notifyListeners()
        return removed
    }

    /**
     * 进程探测把 [pendingKey] 的终端迁到了 [sessionId]（见 DriftCoordinator.applyDrifts）：记为已认领。
     * 终端那边已经换过 key，所以不再产出换 key 信息；占位等这个会话出现在列表里才隐藏（见 [showsPending]）。
     * [pendingKey] 不是未认领的 pending 时什么也不做。
     *
     * 预知 id 的 pending（claude、pi）也不在这里认领：扫描时会按 id 精确认领。pi 会在会话文件只有文件头时
     * 就上报迁移，这时认领了，关掉标签再取消就会因「已认领」被拒绝，占位一直留着。
     */
    fun claimPending(
        pendingKey: String,
        sessionId: String,
    ) {
        ApplicationManager.getApplication()?.assertIsDispatchThread()
        val pending = pendings.firstOrNull { it.key == pendingKey } ?: return
        if (pending.sessionId != null || pendingKey in bindings) return
        bindings[pendingKey] = sessionId
        notifyListeners()
    }

    /** 取走并清空尚未处理的绑定，供终端宿主据此换 key。 */
    fun drainNewBindings(): List<Pair<String, String>> {
        val drained = newBindings.toList()
        newBindings.clear()
        return drained
    }

    /** 取走并清空本轮的无主新会话，见 [unclaimed]。 */
    fun drainUnclaimedSessions(): List<String> {
        val drained = unclaimed.toList()
        unclaimed.clear()
        return drained
    }

    /** 同步扫描并应用。扫描是耗时操作，生产代码请走 [applyScan] + 后台线程。 */
    fun refresh() = applyScan(scan())

    /**
     * 应用一份已经扫描好的结果。
     *
     * 与扫描分离是为了让扫描能在后台线程进行：本机实测 620 个 codex 会话文件，
     * 一次扫描 60–250ms，放在 EDT 上每 3 秒来一次会有明显卡顿。
     * 本方法只做内存里的合并与通知，必须在 EDT 调用。
     */
    fun applyScan(
        scanned: List<AgentSession>,
        detectUnclaimedSessions: Boolean = true,
    ) {
        ApplicationManager.getApplication()?.assertIsDispatchThread()
        val pendingsBefore = visiblePendingKeys()

        bindNewSessions(scanned, detectUnclaimedSessions)
        expirePendings()

        val changed = scanned != sessions || visiblePendingKeys() != pendingsBefore

        sessions = scanned
        knownIds = scanned.mapTo(mutableSetOf()) { it.id }

        // 无变化就不通知：每次通知都会重建整棵树。工具窗口状态变化等事件
        // 会频繁触发刷新，无谓重建既费 EDT 又会丢失选中与展开状态。
        if (changed) notifyListeners()
    }

    private fun visiblePendingKeys(): List<String> = pendings.filter(::showsPending).map { it.key }

    /**
     * 占位要等它认领的会话出现在列表里才隐藏：认领可能早于会话能显示（会话文件刚建、还没有可显示的内容），
     * 这时先隐藏占位，列表里就暂时什么都没有。
     */
    private fun showsPending(pending: PendingSession): Boolean {
        val bound = bindings[pending.key] ?: return true
        return sessions.none { it.id == bound }
    }

    fun sessionOf(id: String): AgentSession? = sessions.firstOrNull { it.id == id }

    fun entries(agentType: AgentType): List<ListEntry> {
        val existing =
            sessions
                .filter { it.agentType == agentType }
                .map { ListEntry.Existing(it) }
        // pending 放前面：新建的会话应出现在分组顶部，与「最近活动优先」的排序意图一致
        val stillPending =
            pendings
                .filter { it.agentType == agentType && showsPending(it) }
                .map { ListEntry.Pending(it) }
        return stillPending + existing
    }

    private fun bindNewSessions(
        scanned: List<AgentSession>,
        detectUnclaimedSessions: Boolean,
    ) {
        val fresh = scanned.filter { it.id !in knownIds }
        for (session in fresh.sortedBy { it.lastActiveAt }) {
            val free = pendings.filter { it.key !in bindings }
            // 只认预知的 id；其余交给进程探测按终端身份认领（见类说明的绑定规则）
            val candidate = free.firstOrNull { it.sessionId == session.id }
            if (candidate == null) {
                // 恢复阶段的首次扫描只是在建立基线，历史会话不能触发漂移探测。
                if (!detectUnclaimedSessions) continue
                // 没有预知它 id 的终端。几种可能：codex 新建的终端刚落盘、某个已打开的终端在 /clear、/new 之后
                // 换了会话 id（进程没变），或者用户在 IDE 外面自己开了一个。
                // 分辨要靠进程探测，这里只负责报告。
                unclaimed.add(session.id)
                continue
            }
            bindings[candidate.key] = session.id
            newBindings.add(candidate.key to session.id)
        }
    }

    private fun expirePendings() {
        val deadline = clock().minus(PENDING_TTL)
        pendings.removeAll { it.key !in bindings && it.startedAt.isBefore(deadline) }
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    private companion object {
        val PENDING_TTL: Duration = Duration.ofMinutes(30)
    }
}
