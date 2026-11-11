package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.robot.RobotRecord

sealed interface PhoneMenuEntry {
    data class Robot(
        val robotId: RobotId,
        val label: String,
        val enabled: Boolean,
        /** 就是这个会话现在连着的机器人：打勾显示，不可选。 */
        val current: Boolean = false,
    ) : PhoneMenuEntry

    data object NoRobots : PhoneMenuEntry

    data object AddRobot : PhoneMenuEntry

    data class Disconnect(val robotId: RobotId) : PhoneMenuEntry
}

interface MenuLabels {
    fun idle(name: String): String

    fun bound(
        name: String,
        session: String,
    ): String

    /** 连着的会话离线了（工程关闭、CLI 退出）：绑定还在，恢复后自动接回。 */
    fun boundOffline(
        name: String,
        session: String,
    ): String

    fun unpaired(name: String): String

    /** 机器人的手机连接不正常（连接中、失败、被接管……）：如实写出状态，不标“空闲”。 */
    fun problem(
        name: String,
        status: ConnectionStatus?,
    ): String

    fun current(name: String): String

    fun currentOffline(name: String): String
}

fun phoneMenuModel(
    robots: List<RobotRecord>,
    bindingOf: (RobotId) -> Binding?,
    sessionAvailable: (Binding) -> Boolean,
    statusOf: (RobotId) -> ConnectionStatus?,
    thisTabId: String,
    titleOf: (Binding) -> String,
    labels: MenuLabels,
    /** 菜单里显示的机器人名，默认只用名字；IDE 里带上渠道简称，见 [robotMenuName]。 */
    nameOf: (RobotRecord) -> String = { it.name },
): List<PhoneMenuEntry> {
    val entries = mutableListOf<PhoneMenuEntry>()
    var currentRobot: RobotId? = null
    robots.forEach { robot ->
        val binding = bindingOf(robot.id)
        val name = nameOf(robot)
        val status = statusOf(robot.id)
        entries +=
            when {
                robot.owner() == null -> PhoneMenuEntry.Robot(robot.id, labels.unpaired(name), false)
                binding != null && binding.target.tabId == thisTabId -> {
                    currentRobot = robot.id
                    val label = when {
                        needsAttention(status) -> labels.problem(name, status)
                        !sessionAvailable(binding) -> labels.currentOffline(name)
                        else -> labels.current(name)
                    }
                    PhoneMenuEntry.Robot(robot.id, label, false, current = true)
                }
                binding != null && !sessionAvailable(binding) ->
                    PhoneMenuEntry.Robot(
                        robot.id,
                        if (needsAttention(status)) labels.problem(name, status) else labels.boundOffline(name, titleOf(binding)),
                        true,
                    )
                binding != null -> PhoneMenuEntry.Robot(robot.id, labels.bound(name, titleOf(binding)), true)
                // 手机连接不正常也可以先连上，恢复后自动收发；只是不能谎称“空闲”
                status != ConnectionStatus.Online -> PhoneMenuEntry.Robot(robot.id, labels.problem(name, status), true)
                else -> PhoneMenuEntry.Robot(robot.id, labels.idle(name), true)
            }
    }
    if (robots.isEmpty()) entries += PhoneMenuEntry.NoRobots
    entries += PhoneMenuEntry.AddRobot
    currentRobot?.let { entries += PhoneMenuEntry.Disconnect(it) }
    return entries
}

object BundleMenuLabels : MenuLabels {
    override fun idle(name: String) = ImuxBundle.message("bridge.action.robot.idle", name)

    override fun bound(
        name: String,
        session: String,
    ) = ImuxBundle.message("bridge.action.robot.bound", name, session)

    override fun boundOffline(
        name: String,
        session: String,
    ) = ImuxBundle.message("bridge.action.robot.bound.offline", name, session)

    override fun unpaired(name: String) = ImuxBundle.message("bridge.action.robot.unpaired", name)

    override fun problem(
        name: String,
        status: ConnectionStatus?,
    ) = ImuxBundle.message("bridge.action.robot.status", name, statusLabel(status, paired = true))

    override fun current(name: String) = ImuxBundle.message("bridge.action.robot.current", name)

    override fun currentOffline(name: String) = ImuxBundle.message("bridge.action.robot.current.offline", name)
}
