package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.robot.ChannelKind
import com.github.izerui.imux.bridge.robot.RobotRecord

/**
 * 状态圆点：绿点表示可用，黄点表示需要处理（待配对、连接中、失败、被接管）。
 * 已停止只在远程控制开着时算异常；关着时全部停止是正常的，不画。终端横幅也按这个判断。
 */
enum class RowTone { OK, ATTENTION, NONE }

data class RobotRow(
    val id: String,
    val name: String,
    val channel: String,
    val status: String,
    val session: String?,
    val tone: RowTone = RowTone.NONE,
)

interface RowLabels {
    fun channel(kind: ChannelKind): String

    fun status(
        status: ConnectionStatus?,
        paired: Boolean,
        bound: Boolean,
    ): String
}

fun rowTone(
    status: ConnectionStatus?,
    paired: Boolean,
    bridgeEnabled: Boolean,
): RowTone =
    when {
        status == ConnectionStatus.Online && paired -> RowTone.OK
        status == null -> RowTone.NONE
        status == ConnectionStatus.Stopped && !bridgeEnabled -> RowTone.NONE
        else -> RowTone.ATTENTION
    }

fun robotRows(
    robots: List<RobotRecord>,
    statusOf: (String) -> ConnectionStatus?,
    bindingOf: (String) -> Binding?,
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
            tone = rowTone(status, paired, bridgeEnabled),
        )
    }

fun channelLabel(kind: ChannelKind): String = ImuxBundle.message("bridge.channel.${kind.id}")

fun statusLabel(
    status: ConnectionStatus?,
    paired: Boolean,
): String =
    when {
        status is ConnectionStatus.Failed && status.reason == "noCredentials" -> ImuxBundle.message("bridge.robot.status.noCredentials")
        status is ConnectionStatus.Failed -> ImuxBundle.message("bridge.robot.status.failed", status.reason)
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
