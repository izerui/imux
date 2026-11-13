package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.robot.RobotRecord

sealed interface PhoneMenuEntry {
    data class Robot(
        val robotId: RobotId,
        /** 机器人名，菜单左侧。 */
        val label: String,
        /** 状态，菜单右侧的灰字；状态词打头（空闲 / 已连接 / 离线……），竖着扫一眼就能分清。 */
        val status: String,
        val enabled: Boolean,
        /** 就是这个会话现在连着的机器人：打勾显示，不可选。 */
        val current: Boolean = false,
    ) : PhoneMenuEntry

    data object NoRobots : PhoneMenuEntry

    data object AddRobot : PhoneMenuEntry

    data class Disconnect(val robotId: RobotId) : PhoneMenuEntry
}

/** 菜单右侧的状态文字，不含机器人名。 */
interface MenuLabels {
    fun idle(): String

    fun bound(session: String): String

    /** 连着的会话离线了（工程关闭、CLI 退出）：绑定还在，恢复后自动接回。 */
    fun boundOffline(session: String): String

    fun unpaired(): String

    /** 机器人的手机连接不正常（连接中、失败、被接管……）：如实写出状态，不标“空闲”。 */
    fun problem(status: ConnectionStatus?): String

    fun current(): String

    fun currentOffline(): String
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
                robot.owner() == null -> PhoneMenuEntry.Robot(robot.id, name, labels.unpaired(), false)
                binding != null && binding.target.tabId == thisTabId -> {
                    currentRobot = robot.id
                    val label = when {
                        needsAttention(status) -> labels.problem(status)
                        !sessionAvailable(binding) -> labels.currentOffline()
                        else -> labels.current()
                    }
                    PhoneMenuEntry.Robot(robot.id, name, label, false, current = true)
                }
                binding != null && !sessionAvailable(binding) ->
                    PhoneMenuEntry.Robot(
                        robot.id,
                        name,
                        if (needsAttention(status)) labels.problem(status) else labels.boundOffline(titleOf(binding)),
                        true,
                    )
                binding != null -> PhoneMenuEntry.Robot(robot.id, name, labels.bound(titleOf(binding)), true)
                // 手机连接不正常也可以先连上，恢复后自动收发；只是不能谎称“空闲”
                status != ConnectionStatus.Online -> PhoneMenuEntry.Robot(robot.id, name, labels.problem(status), true)
                else -> PhoneMenuEntry.Robot(robot.id, name, labels.idle(), true)
            }
    }
    if (robots.isEmpty()) entries += PhoneMenuEntry.NoRobots
    entries += PhoneMenuEntry.AddRobot
    currentRobot?.let { entries += PhoneMenuEntry.Disconnect(it) }
    return entries
}

object BundleMenuLabels : MenuLabels {
    override fun idle() = ImuxBundle.message("bridge.action.robot.idle")

    override fun bound(session: String) = ImuxBundle.message("bridge.action.robot.bound", menuTitle(session))

    override fun boundOffline(session: String) = ImuxBundle.message("bridge.action.robot.bound.offline", menuTitle(session))

    override fun unpaired() = ImuxBundle.message("bridge.action.robot.unpaired")

    override fun problem(status: ConnectionStatus?) = statusLabel(status, paired = true)

    override fun current() = ImuxBundle.message("bridge.action.robot.current")

    override fun currentOffline() = ImuxBundle.message("bridge.action.robot.current.offline")
}

/** 菜单里的会话标题限长，免得一条长标题把整个弹窗撑宽。 */
internal fun menuTitle(title: String): String {
    val trimmed = title.trim()
    return if (trimmed.codePointCount(0, trimmed.length) <= MAX_MENU_TITLE_CHARS) {
        trimmed
    } else {
        trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_MENU_TITLE_CHARS - 1)) + "…"
    }
}

private const val MAX_MENU_TITLE_CHARS = 24
