package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
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
            ) = if (!paired) "pairing" else status?.toString() ?: "none"
        }

    @Test
    fun `表格行包含渠道、状态、工程、智能体与当前会话`() {
        val robots =
            listOf(
                RobotRecord(RobotId("a"), "开发助手 1", ChannelKind.WECOM, ownerSenderId = "u", ownerChatId = "u"),
                RobotRecord(RobotId("b"), "开发助手 2", ChannelKind.WECOM, pairCode = "123456"),
            )
        val binding = Binding(RobotId("a"), SessionTarget("/p", "t", AgentType.CODEX, "s"), 1)
        val rows =
            robotRows(
                robots,
                statusOf = { if (it.value == "a") ConnectionStatus.Online else null },
                bindingOf = { if (it.value == "a") binding else null },
                sessionAvailable = { true },
                titleOf = { "修复终端滚动" },
                projectOf = { "imux" },
                labels = labels,
                bridgeEnabled = true,
            )
        assertEquals(
            listOf(
                RobotRow(RobotId("a"), "开发助手 1", "wecom", "Online", "修复终端滚动", RobotHealth.OK, "imux", AgentType.CODEX),
                RobotRow(RobotId("b"), "开发助手 2", "wecom", "pairing", null, RobotHealth.NONE),
            ),
            rows,
        )
    }

    @Test
    fun `关闭两个工程后各自的会话显示离线，机器人状态不受影响，其余两个工程保持连接`() {
        val robots = (1..4).map { id ->
            RobotRecord(RobotId("$id"), "bot-$id", ChannelKind.WECOM, ownerSenderId = "u", ownerChatId = "u")
        }
        val bindings = robots.associate { robot ->
            robot.id to Binding(robot.id, SessionTarget("/p${robot.id.value}", "t${robot.id.value}", AgentType.CODEX, "s"), 1)
        }
        val available = mutableSetOf("/p1", "/p2", "/p3", "/p4")
        fun rows() = robotRows(
            robots,
            statusOf = { ConnectionStatus.Online },
            bindingOf = bindings::get,
            sessionAvailable = { it.target.projectPath in available },
            titleOf = { "session-${it.robotId.value}" },
            projectOf = { it.target.projectPath },
            labels = labels,
            bridgeEnabled = true,
        )
        assertEquals(false, rows()[0].sessionOffline)
        available.remove("/p1")
        available.remove("/p3")
        val closed = rows()
        assertEquals(listOf(true, false, true, false), closed.map { it.sessionOffline })
        // 状态列与圆点只说手机连接
        assertEquals(List(4) { "Online" }, closed.map { it.status })
        assertEquals(List(4) { RobotHealth.OK }, closed.map { it.health })
        assertEquals("/p1", closed[0].project)
        assertEquals("session-1", closed[0].session)
        available.add("/p1")
        assertEquals(false, rows()[0].sessionOffline)
        assertEquals(true, rows()[2].sessionOffline)
    }

    @Test
    fun `工程关闭不会隐藏机器人的故障原因和警示圆点`() {
        val cases = listOf(
            ConnectionStatus.Failed("auth") to "Failed(reason=auth)",
            ConnectionStatus.Kicked to "Kicked",
            ConnectionStatus.Retrying("network") to "Retrying(reason=network)",
            ConnectionStatus.NoCredentials to "NoCredentials",
            ConnectionStatus.SendFailing("quota", "limit") to "SendFailing(messageKey=quota, detail=limit)",
            ConnectionStatus.Connecting to "Connecting",
        )
        val robot = RobotRecord(RobotId("a"), "bot", ChannelKind.WECOM, ownerSenderId = "u", ownerChatId = "u")
        val binding = Binding(robot.id, SessionTarget("/closed", "t", AgentType.CODEX, "s"), 1)
        for ((status, expected) in cases) {
            val row = robotRows(
                listOf(robot), { status }, { binding }, { false }, { "title" }, { "closed" }, labels, true,
            ).single()
            assertEquals("$status 的原因不可被会话离线覆盖", expected, row.status)
            assertEquals("$status 必须保留警示", RobotHealth.ATTENTION, row.health)
            assertEquals(true, row.sessionOffline)
        }
    }

    @Test
    fun `状态圆点：在线且已配对为绿点，待配对、连接中、失败、被接管为黄点，未连接不画`() {
        assertEquals(RobotHealth.OK, robotHealth(ConnectionStatus.Online, paired = true, bridgeEnabled = true))
        assertEquals(RobotHealth.ATTENTION, robotHealth(ConnectionStatus.Online, paired = false, bridgeEnabled = true))
        assertEquals(RobotHealth.ATTENTION, robotHealth(ConnectionStatus.Connecting, paired = true, bridgeEnabled = true))
        assertEquals(RobotHealth.ATTENTION, robotHealth(ConnectionStatus.Failed("x"), paired = true, bridgeEnabled = true))
        assertEquals(RobotHealth.ATTENTION, robotHealth(ConnectionStatus.Kicked, paired = true, bridgeEnabled = true))
        assertEquals(RobotHealth.NONE, robotHealth(null, paired = true, bridgeEnabled = true))
    }

    @Test
    fun `已停止：远程控制开着时为黄点，与横幅一致；关着时不画`() {
        assertEquals(RobotHealth.ATTENTION, robotHealth(ConnectionStatus.Stopped, paired = true, bridgeEnabled = true))
        assertEquals(RobotHealth.NONE, robotHealth(ConnectionStatus.Stopped, paired = true, bridgeEnabled = false))
    }

    @Test
    fun `发送受阻算需要注意`() {
        assertEquals(RobotHealth.ATTENTION, robotHealth(ConnectionStatus.SendFailing("k"), paired = true, bridgeEnabled = true))
        assertEquals(true, needsAttention(ConnectionStatus.SendFailing("k", "230013")))
    }

    @Test
    fun `只是状态变化时视为同一批机器人，增删或换序时不是`() {
        fun row(id: String, status: String = "在线") = RobotRow(RobotId(id), id, "企业微信", status, null)
        org.junit.Assert.assertTrue(sameRobotsInOrder(listOf(row("a"), row("b")), listOf(row("a", "连接中"), row("b"))))
        org.junit.Assert.assertFalse(sameRobotsInOrder(listOf(row("a")), listOf(row("a"), row("b"))))
        org.junit.Assert.assertFalse(sameRobotsInOrder(listOf(row("a"), row("b")), listOf(row("b"), row("a"))))
    }

    @Test
    fun `重新连接只在重连能解决问题时可用`() {
        assertEquals(false, canReconnect(ConnectionStatus.Online))
        assertEquals(false, canReconnect(ConnectionStatus.NoCredentials))
        assertEquals(false, canReconnect(ConnectionStatus.SendFailing("k")))
        listOf(null, ConnectionStatus.Connecting, ConnectionStatus.Retrying("x"), ConnectionStatus.Failed("x"), ConnectionStatus.Stopped, ConnectionStatus.Kicked)
            .forEach { assertEquals("$it", true, canReconnect(it)) }
    }
}
