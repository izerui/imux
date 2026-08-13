package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
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
    data class Existing(val session: AgentSession) : ListEntry
    data class Pending(val pending: PendingSession) : ListEntry
}

/**
 * 列表的全部可变状态集中于此：扫描结果、未绑定的新会话临时条目、绑定关系。
 *
 * 依赖以函数形式注入（scan / clock），使本类完全脱离文件系统与墙钟，便于测试。
 *
 * 绑定规则（消除歧义，见设计文档 6.3）：
 * 1. 扫描到的会话若 id 未在上轮出现、agentType 相同、且 lastActiveAt >= pending.startedAt，
 *    则它是该 pending 的候选
 * 2. 多个 pending 竞争同一会话时，绑定到 startedAt 最晚且不迟于该会话活动时刻的那个
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

    fun registerPending(agentType: AgentType, sessionId: String? = null): PendingSession {
        val pending = PendingSession(
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

    fun cancelPending(key: String): Boolean {
        if (key in bindings) return false
        val removed = pendings.removeIf { it.key == key }
        if (removed) notifyListeners()
        return removed
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
    fun applyScan(scanned: List<AgentSession>) {
        val pendingsBefore = visiblePendingKeys()

        bindNewSessions(scanned)
        expirePendings()

        val changed = scanned != sessions || visiblePendingKeys() != pendingsBefore

        sessions = scanned
        knownIds = scanned.mapTo(mutableSetOf()) { it.id }

        // 无变化就不通知：每次通知都会重建整棵树。工具窗口状态变化等事件
        // 会频繁触发刷新，无谓重建既费 EDT 又会丢失选中与展开状态。
        if (changed) notifyListeners()
    }

    private fun visiblePendingKeys(): List<String> =
        pendings.filter { it.key !in bindings }.map { it.key }

    fun sessionOf(id: String): AgentSession? = sessions.firstOrNull { it.id == id }

    fun entries(agentType: AgentType): List<ListEntry> {
        val existing = sessions
            .filter { it.agentType == agentType }
            .map { ListEntry.Existing(it) }
        // pending 放前面：新建的会话应出现在分组顶部，与「最近活动优先」的排序意图一致
        val stillPending = pendings
            .filter { it.agentType == agentType && it.key !in bindings }
            .map { ListEntry.Pending(it) }
        return stillPending + existing
    }

    private fun bindNewSessions(scanned: List<AgentSession>) {
        val fresh = scanned.filter { it.id !in knownIds }
        for (session in fresh.sortedBy { it.lastActiveAt }) {
            val free = pendings.filter { it.key !in bindings }
            // 预知 id 的 pending（pi）走精确匹配，不参与下面的时间窗竞争：
            // id 在启动前就定了，没有可猜的余地，也不该被别的会话抢走。
            val candidate = free.firstOrNull { it.sessionId == session.id }
                ?: free
                    .filter { it.sessionId == null }
                    .filter { it.agentType == session.agentType }
                    .filter { !it.startedAt.isAfter(session.lastActiveAt) }
                    .maxByOrNull { it.startedAt }
            if (candidate == null) {
                // 没人在等这个会话。两种可能：某个已打开的终端在 /clear、/new 之后
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
