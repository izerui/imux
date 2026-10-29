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

    fun unpaired(name: String): String

    /** 空闲但不在线（连接中、失败、被接管……）：如实写出状态，不标“空闲”。 */
    fun offline(
        name: String,
        status: ConnectionStatus?,
    ): String

    fun current(name: String): String
}

fun phoneMenuModel(
    robots: List<RobotRecord>,
    bindingOf: (RobotId) -> Binding?,
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
        entries +=
            when {
                robot.owner() == null -> PhoneMenuEntry.Robot(robot.id, labels.unpaired(name), false)
                binding?.target?.tabId == thisTabId -> {
                    currentRobot = robot.id
                    PhoneMenuEntry.Robot(robot.id, labels.current(name), false, current = true)
                }
                binding != null -> PhoneMenuEntry.Robot(robot.id, labels.bound(name, titleOf(binding)), true)
                // 不在线也可以先连上，恢复在线后自动收发；只是不能谎称“空闲”
                statusOf(robot.id) != ConnectionStatus.Online -> PhoneMenuEntry.Robot(robot.id, labels.offline(name, statusOf(robot.id)), true)
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

    override fun unpaired(name: String) = ImuxBundle.message("bridge.action.robot.unpaired", name)

    override fun offline(
        name: String,
        status: ConnectionStatus?,
    ) = ImuxBundle.message("bridge.action.robot.status", name, statusLabel(status, paired = true))

    override fun current(name: String) = ImuxBundle.message("bridge.action.robot.current", name)
}
