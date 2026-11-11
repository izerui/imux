package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.robot.RobotRecord
import com.github.izerui.imux.model.AgentType

/**
 * 状态圆点：绿点表示可用，黄点表示需要处理（待配对、连接中、失败、被接管）。
 * 已停止只在远程控制开着时算异常；关着时全部停止是正常的，不画。终端横幅也按这个判断。
 */
enum class RobotHealth { OK, ATTENTION, NONE }

data class RobotRow(
    val id: RobotId,
    val name: String,
    val channel: String,
    /** 只说机器人与平台之间的手机连接；会话那一侧看 [session] 与 [sessionOffline]。 */
    val status: String,
    val session: String?,
    val health: RobotHealth = RobotHealth.NONE,
    /** 连着的会话所在工程与智能体，空闲时为空。 */
    val project: String? = null,
    val agent: AgentType? = null,
    /** 绑定还在，但终端没在运行（工程关闭、IDE 退出、恢复的标签还没打开）：手机消息一律不送达，打开后自动接回。 */
    val sessionOffline: Boolean = false,
)

interface RowLabels {
    fun channel(kind: ChannelKind): String

    fun status(
        status: ConnectionStatus?,
        paired: Boolean,
    ): String
}

fun robotHealth(
    status: ConnectionStatus?,
    paired: Boolean,
    bridgeEnabled: Boolean,
): RobotHealth =
    when {
        status == ConnectionStatus.Online && paired -> RobotHealth.OK
        status == null -> RobotHealth.NONE
        status == ConnectionStatus.Stopped && !bridgeEnabled -> RobotHealth.NONE
        else -> RobotHealth.ATTENTION
    }

/**
 * 设置页的「重新连接」只在重连能解决问题时可用：在线不需要；收发受阻要到平台处理，凭据丢失要重新添加，重连都解决不了。
 */
fun canReconnect(status: ConnectionStatus?): Boolean =
    status != ConnectionStatus.Online && status != ConnectionStatus.NoCredentials && status !is ConnectionStatus.SendFailing

/** 连着会话的机器人是否需要处理（横幅、状态栏用）：与设置页的黄点同一判断。 */
fun needsAttention(status: ConnectionStatus?): Boolean = robotHealth(status, paired = true, bridgeEnabled = true) == RobotHealth.ATTENTION

fun robotRows(
    robots: List<RobotRecord>,
    statusOf: (RobotId) -> ConnectionStatus?,
    bindingOf: (RobotId) -> Binding?,
    sessionAvailable: (Binding) -> Boolean,
    titleOf: (Binding) -> String,
    projectOf: (Binding) -> String,
    labels: RowLabels,
    bridgeEnabled: Boolean,
): List<RobotRow> =
    robots.map { robot ->
        val status = statusOf(robot.id)
        val paired = robot.owner() != null
        val binding = bindingOf(robot.id)
        // 状态列与圆点只看手机连接，会话离线放在会话列，两者互不遮盖
        RobotRow(
            id = robot.id,
            name = robot.name,
            channel = labels.channel(robot.kind),
            status = labels.status(status, paired),
            session = binding?.let(titleOf),
            health = robotHealth(status, paired, bridgeEnabled),
            project = binding?.let(projectOf),
            agent = binding?.target?.agentType,
            sessionOffline = binding != null && !sessionAvailable(binding),
        )
    }

fun channelLabel(kind: ChannelKind): String = ImuxBundle.message("bridge.channel.${kind.id}")

/** 菜单这类地方用的简称（企业微信写作「企微」）。 */
fun shortChannelLabel(kind: ChannelKind): String = ImuxBundle.message("bridge.channel.${kind.id}.short")

/** 菜单里区分同名机器人：「企微 · imux001」。 */
fun robotMenuName(robot: RobotRecord): String = shortChannelLabel(robot.kind) + " · " + robot.name

fun statusLabel(
    status: ConnectionStatus?,
    paired: Boolean,
): String =
    when {
        status == ConnectionStatus.NoCredentials -> ImuxBundle.message("bridge.robot.status.noCredentials")
        status is ConnectionStatus.SendFailing ->
            status.detail?.let { ImuxBundle.message(status.messageKey, it) } ?: ImuxBundle.message(status.messageKey)
        status is ConnectionStatus.Failed -> ImuxBundle.message("bridge.robot.status.failed", status.reason)
        status is ConnectionStatus.Retrying -> ImuxBundle.message("bridge.robot.status.retrying", status.reason)
        status == ConnectionStatus.Kicked -> ImuxBundle.message("bridge.robot.status.kicked")
        status == ConnectionStatus.Connecting -> ImuxBundle.message("bridge.robot.status.connecting")
        status == ConnectionStatus.Online && !paired -> ImuxBundle.message("bridge.robot.status.pairing")
        status == ConnectionStatus.Online -> ImuxBundle.message("bridge.robot.status.online")
        else -> ImuxBundle.message("bridge.robot.status.stopped")
    }

object BundleRowLabels : RowLabels {
    override fun channel(kind: ChannelKind) = channelLabel(kind)

    override fun status(
        status: ConnectionStatus?,
        paired: Boolean,
    ) = statusLabel(status, paired)
}
