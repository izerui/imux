package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.bridge.robot.ChannelKind
import com.github.izerui.imux.bridge.robot.RobotRecord
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Test

class RobotRowsTest {
    private val labels =
        object : RowLabels {
            override fun channel(kind: ChannelKind) = kind.id

            override fun status(
                status: ConnectionStatus?,
                paired: Boolean,
            ) = if (!paired) "pairing" else (status?.toString() ?: "none")
        }

    @Test
    fun `表格行包含渠道、状态与当前会话`() {
        val robots =
            listOf(
                RobotRecord("a", "开发助手 1", ChannelKind.WECOM, ownerSenderId = "u", ownerChatId = "u"),
                RobotRecord("b", "开发助手 2", ChannelKind.WECOM, pairCode = "123456"),
            )
        val binding = Binding(RobotId("a"), SessionTarget("/p", "t", AgentType.CODEX, "s"), 1)
        val rows =
            robotRows(
                robots,
                statusOf = { if (it == "a") ConnectionStatus.Online else null },
                bindingOf = { if (it == "a") binding else null },
                titleOf = { "修复终端滚动" },
                labels = labels,
            )
        assertEquals(
            listOf(
                RobotRow("a", "开发助手 1", "wecom", "Online", "修复终端滚动"),
                RobotRow("b", "开发助手 2", "wecom", "pairing", null),
            ),
            rows,
        )
    }
}
