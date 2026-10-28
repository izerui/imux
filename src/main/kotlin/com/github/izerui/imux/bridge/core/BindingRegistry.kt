package com.github.izerui.imux.bridge.core

data class Binding(
    val robotId: RobotId,
    val target: SessionTarget,
    /** 用户每次绑定/换绑都会换新；会话迁移和重新挂接沿用，表示仍是同一个对话目标。 */
    val epoch: Long,
)

sealed interface BindResult {
    data class Bound(
        val binding: Binding,
        val replaced: Binding?,
    ) : BindResult

    data class TargetTaken(val by: Binding) : BindResult
}

/**
 * 机器人 ↔ 终端的 1:1 绑定表。全应用一份（机器人连接也是全应用一份）。
 *
 * 只记关系，不碰终端、渠道和网络；所有方法短小且同步，可在任意线程调用。
 * 每次实际改动后在锁外调用 [onChanged]，由持有者负责持久化与刷新界面。
 */
class BindingRegistry {
    private val byRobot = LinkedHashMap<RobotId, Binding>()
    private var lastEpoch = 0L
    private val lock = Any()

    @Volatile
    var onChanged: () -> Unit = {}

    fun bind(
        robotId: RobotId,
        target: SessionTarget,
    ): BindResult =
        changing {
            byRobot.values
                .firstOrNull { it.target.tabId == target.tabId && it.robotId != robotId }
                ?.let { return@changing BindResult.TargetTaken(it) to false }
            val replaced = byRobot[robotId]
            val binding = Binding(robotId, target, ++lastEpoch)
            byRobot[robotId] = binding
            BindResult.Bound(binding, replaced) to true
        }

    fun unbind(robotId: RobotId): Binding? = changing { byRobot.remove(robotId).let { it to (it != null) } }

    fun bindingOf(robotId: RobotId): Binding? = synchronized(lock) { byRobot[robotId] }

    fun bindingForTab(tabId: String): Binding? = synchronized(lock) { byRobot.values.firstOrNull { it.target.tabId == tabId } }

    fun isCurrent(
        robotId: RobotId,
        epoch: Long,
    ): Boolean = synchronized(lock) { byRobot[robotId]?.epoch == epoch }

    /** `/new`、`/clear` 或新会话落盘后，同一终端换了原生会话。 */
    fun migrateSession(
        tabId: String,
        sessionId: String,
    ): Binding? =
        changing {
            val current = byRobot.values.firstOrNull { it.target.tabId == tabId } ?: return@changing null to false
            val next = current.copy(target = current.target.copy(sessionId = sessionId))
            byRobot[current.robotId] = next
            next to true
        }

    /** 项目重开后同一原生会话以新标签恢复。若该标签已被其他机器人持有则拒绝，返回 null。 */
    fun reattach(
        robotId: RobotId,
        tabId: String,
    ): Binding? =
        changing {
            val current = byRobot[robotId] ?: return@changing null to false
            if (byRobot.values.any { it.target.tabId == tabId && it.robotId != robotId }) return@changing null to false
            val next = current.copy(target = current.target.copy(tabId = tabId))
            byRobot[robotId] = next
            next to true
        }

    fun snapshot(): List<Binding> = synchronized(lock) { byRobot.values.toList() }

    /** 在锁内改表，返回（结果, 是否改动）；改动了才在锁外通知。 */
    private inline fun <T> changing(block: () -> Pair<T, Boolean>): T {
        val (result, changed) = synchronized(lock, block)
        if (changed) onChanged()
        return result
    }
}
