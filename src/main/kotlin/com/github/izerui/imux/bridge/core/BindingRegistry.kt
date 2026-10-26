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
 */
class BindingRegistry {
    private val byRobot = LinkedHashMap<RobotId, Binding>()
    private var lastEpoch = 0L

    @Synchronized
    fun bind(
        robotId: RobotId,
        target: SessionTarget,
    ): BindResult {
        byRobot.values
            .firstOrNull { it.target.tabId == target.tabId && it.robotId != robotId }
            ?.let { return BindResult.TargetTaken(it) }
        val replaced = byRobot[robotId]
        val binding = Binding(robotId, target, ++lastEpoch)
        byRobot[robotId] = binding
        return BindResult.Bound(binding, replaced)
    }

    @Synchronized
    fun unbind(robotId: RobotId): Binding? = byRobot.remove(robotId)

    @Synchronized
    fun bindingOf(robotId: RobotId): Binding? = byRobot[robotId]

    @Synchronized
    fun bindingForTab(tabId: String): Binding? = byRobot.values.firstOrNull { it.target.tabId == tabId }

    @Synchronized
    fun isCurrent(
        robotId: RobotId,
        epoch: Long,
    ): Boolean = byRobot[robotId]?.epoch == epoch

    /** `/new`、`/clear` 或新会话落盘后，同一终端换了原生会话。 */
    @Synchronized
    fun migrateSession(
        tabId: String,
        sessionId: String,
    ): Binding? {
        val current = byRobot.values.firstOrNull { it.target.tabId == tabId } ?: return null
        return current.copy(target = current.target.copy(sessionId = sessionId)).also { byRobot[current.robotId] = it }
    }

    /** 项目重开后同一原生会话以新标签恢复。若该标签已被其他机器人持有则拒绝，返回 null。 */
    @Synchronized
    fun reattach(
        robotId: RobotId,
        tabId: String,
    ): Binding? {
        val current = byRobot[robotId] ?: return null
        byRobot.values
            .firstOrNull { it.target.tabId == tabId && it.robotId != robotId }
            ?.let { return null }
        return current.copy(target = current.target.copy(tabId = tabId)).also { byRobot[robotId] = it }
    }

    @Synchronized
    fun snapshot(): List<Binding> = byRobot.values.toList()
}
