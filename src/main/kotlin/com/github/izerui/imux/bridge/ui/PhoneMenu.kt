package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.robot.RobotRecord
import com.github.izerui.imux.model.AgentType

sealed interface PhoneMenuEntry {
    /**
     * 一行机器人：圆点 + 名字 + 状态词，连着的会话标题作右侧灰字。
     * 状态靠最左边的圆点和紧跟名字的状态词表达，竖着扫一眼就能分清。
     */
    data class Robot(
        val robotId: RobotId,
        /** 机器人名。 */
        val label: String,
        val state: RobotMenuState,
        /** 状态词：空闲、已连接、离线……与 [state] 对应。 */
        val status: String,
        val enabled: Boolean,
        /** 就是这个会话现在连着的机器人：不可选。 */
        val current: Boolean = false,
        /** 连着的会话标题（已限长），没连会话时为 null。 */
        val session: String? = null,
        /** 连着的会话是哪个智能体，标题前画它的图标。 */
        val agent: AgentType? = null,
    ) : PhoneMenuEntry

    data object NoRobots : PhoneMenuEntry

    data object AddRobot : PhoneMenuEntry

    data class Disconnect(val robotId: RobotId) : PhoneMenuEntry
}

/** 菜单行最左边圆点的颜色：绿=已连接，灰=离线，空心=空闲，黄=手机连接异常。 */
enum class RobotMenuState { CONNECTED, OFFLINE, IDLE, PROBLEM, UNPAIRED }

/** 紧跟机器人名的状态词，不含机器人名与会话标题。 */
interface MenuLabels {
    fun idle(): String

    fun bound(): String

    /** 连着的会话离线了（工程关闭、CLI 退出）：绑定还在，恢复后自动接回。 */
    fun boundOffline(): String

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
        val session = binding?.let { menuTitle(titleOf(it)) }
        fun row(
            state: RobotMenuState,
            label: String,
            enabled: Boolean = true,
            current: Boolean = false,
        ) = PhoneMenuEntry.Robot(robot.id, name, state, label, enabled, current, session, binding?.target?.agentType)
        entries +=
            when {
                robot.owner() == null -> row(RobotMenuState.UNPAIRED, labels.unpaired(), enabled = false)
                binding != null && binding.target.tabId == thisTabId -> {
                    currentRobot = robot.id
                    when {
                        needsAttention(status) -> row(RobotMenuState.PROBLEM, labels.problem(status), false, current = true)
                        !sessionAvailable(binding) -> row(RobotMenuState.OFFLINE, labels.currentOffline(), false, current = true)
                        else -> row(RobotMenuState.CONNECTED, labels.current(), false, current = true)
                    }
                }
                binding != null && needsAttention(status) && !sessionAvailable(binding) ->
                    row(RobotMenuState.PROBLEM, labels.problem(status))
                binding != null && !sessionAvailable(binding) -> row(RobotMenuState.OFFLINE, labels.boundOffline())
                binding != null -> row(RobotMenuState.CONNECTED, labels.bound())
                // 手机连接不正常也可以先连上，恢复后自动收发；只是不能谎称“空闲”
                status != ConnectionStatus.Online -> row(RobotMenuState.PROBLEM, labels.problem(status))
                else -> row(RobotMenuState.IDLE, labels.idle())
            }
    }
    if (robots.isEmpty()) entries += PhoneMenuEntry.NoRobots
    entries += PhoneMenuEntry.AddRobot
    currentRobot?.let { entries += PhoneMenuEntry.Disconnect(it) }
    return entries
}

object BundleMenuLabels : MenuLabels {
    override fun idle() = ImuxBundle.message("bridge.action.robot.idle")

    override fun bound() = ImuxBundle.message("bridge.action.robot.bound")

    override fun boundOffline() = ImuxBundle.message("bridge.action.robot.bound.offline")

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
