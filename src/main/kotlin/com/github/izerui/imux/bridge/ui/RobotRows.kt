package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.robot.RobotRecord

/**
 * 状态圆点：绿点表示可用，黄点表示需要处理（待配对、连接中、失败、被接管）。
 * 已停止只在远程控制开着时算异常；关着时全部停止是正常的，不画。终端横幅也按这个判断。
 */
enum class RobotHealth { OK, ATTENTION, NONE }

data class RobotRow(
    val id: RobotId,
    val name: String,
    val channel: String,
    val status: String,
    val session: String?,
    val health: RobotHealth = RobotHealth.NONE,
)

interface RowLabels {
    fun channel(kind: ChannelKind): String

    fun status(
        status: ConnectionStatus?,
        paired: Boolean,
        bound: Boolean,
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

/** 连着会话的机器人是否需要处理（横幅、状态栏用）：与设置页的黄点同一判断。 */
fun needsAttention(status: ConnectionStatus?): Boolean = robotHealth(status, paired = true, bridgeEnabled = true) == RobotHealth.ATTENTION

fun robotRows(
    robots: List<RobotRecord>,
    statusOf: (RobotId) -> ConnectionStatus?,
    bindingOf: (RobotId) -> Binding?,
    titleOf: (Binding) -> String,
    labels: RowLabels,
    bridgeEnabled: Boolean,
): List<RobotRow> =
    robots.map { robot ->
        val status = statusOf(robot.id)
        val paired = robot.owner() != null
        val binding = bindingOf(robot.id)
        RobotRow(
            id = robot.id,
            name = robot.name,
            channel = labels.channel(robot.kind),
            status = labels.status(status, paired, bound = binding != null),
            session = binding?.let(titleOf),
            health = robotHealth(status, paired, bridgeEnabled),
        )
    }

fun channelLabel(kind: ChannelKind): String = ImuxBundle.message("bridge.channel.${kind.id}")

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

    // 列表比横幅多区分“在线 · 空闲”与“已连接会话”（原型的状态列）
    override fun status(
        status: ConnectionStatus?,
        paired: Boolean,
        bound: Boolean,
    ) = when {
        status == ConnectionStatus.Online && paired && bound -> ImuxBundle.message("bridge.robot.status.bound")
        status == ConnectionStatus.Online && paired -> ImuxBundle.message("bridge.robot.status.idle")
        else -> statusLabel(status, paired)
    }
}
