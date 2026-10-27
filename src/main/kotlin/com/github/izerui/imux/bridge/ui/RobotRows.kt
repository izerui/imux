package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.robot.ChannelKind
import com.github.izerui.imux.bridge.robot.RobotRecord

data class RobotRow(
    val id: String,
    val name: String,
    val channel: String,
    val status: String,
    val session: String?,
)

interface RowLabels {
    fun channel(kind: ChannelKind): String

    fun status(
        status: ConnectionStatus?,
        paired: Boolean,
    ): String
}

fun robotRows(
    robots: List<RobotRecord>,
    statusOf: (String) -> ConnectionStatus?,
    bindingOf: (String) -> Binding?,
    titleOf: (Binding) -> String,
    labels: RowLabels,
): List<RobotRow> =
    robots.map { robot ->
        RobotRow(
            id = robot.id,
            name = robot.name,
            channel = labels.channel(robot.kind),
            status = labels.status(statusOf(robot.id), paired = robot.owner() != null),
            session = bindingOf(robot.id)?.let(titleOf),
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

    override fun status(
        status: ConnectionStatus?,
        paired: Boolean,
    ) = statusLabel(status, paired)
}
