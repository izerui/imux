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
                bound: Boolean,
            ) = if (!paired) "pairing" else (status?.toString() ?: "none") + if (bound) "+bound" else ""
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
                bridgeEnabled = true,
            )
        assertEquals(
            listOf(
                RobotRow("a", "开发助手 1", "wecom", "Online+bound", "修复终端滚动", RowTone.OK),
                RobotRow("b", "开发助手 2", "wecom", "pairing", null, RowTone.NONE),
            ),
            rows,
        )
    }

    @Test
    fun `状态圆点：在线且已配对为绿点，待配对、连接中、失败、被接管为黄点，未连接不画`() {
        assertEquals(RowTone.OK, rowTone(ConnectionStatus.Online, paired = true, bridgeEnabled = true))
        assertEquals(RowTone.ATTENTION, rowTone(ConnectionStatus.Online, paired = false, bridgeEnabled = true))
        assertEquals(RowTone.ATTENTION, rowTone(ConnectionStatus.Connecting, paired = true, bridgeEnabled = true))
        assertEquals(RowTone.ATTENTION, rowTone(ConnectionStatus.Failed("x"), paired = true, bridgeEnabled = true))
        assertEquals(RowTone.ATTENTION, rowTone(ConnectionStatus.Kicked, paired = true, bridgeEnabled = true))
        assertEquals(RowTone.NONE, rowTone(null, paired = true, bridgeEnabled = true))
    }

    @Test
    fun `已停止：远程控制开着时为黄点，与横幅一致；关着时不画`() {
        assertEquals(RowTone.ATTENTION, rowTone(ConnectionStatus.Stopped, paired = true, bridgeEnabled = true))
        assertEquals(RowTone.NONE, rowTone(ConnectionStatus.Stopped, paired = true, bridgeEnabled = false))
    }

    @Test
    fun `只是状态变化时视为同一批机器人，增删或换序时不是`() {
        fun row(id: String, status: String = "在线") = RobotRow(id, id, "企业微信", status, null)
        org.junit.Assert.assertTrue(sameRobotsInOrder(listOf(row("a"), row("b")), listOf(row("a", "连接中"), row("b"))))
        org.junit.Assert.assertFalse(sameRobotsInOrder(listOf(row("a")), listOf(row("a"), row("b"))))
        org.junit.Assert.assertFalse(sameRobotsInOrder(listOf(row("a"), row("b")), listOf(row("b"), row("a"))))
    }
}
