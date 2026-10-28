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

class PhoneMenuTest {
    private val labels =
        object : MenuLabels {
            override fun idle(name: String) = "$name/idle"

            override fun bound(
                name: String,
                session: String,
            ) = "$name/bound:$session"

            override fun unpaired(name: String) = "$name/unpaired"

            override fun current(name: String) = "$name/current"

            override fun offline(
                name: String,
                status: ConnectionStatus?,
            ) = "$name/offline:$status"
        }

    private fun robot(
        id: String,
        paired: Boolean = true,
    ) = if (paired) RobotRecord(id, "bot-$id", ChannelKind.WECOM, ownerSenderId = "u", ownerChatId = "u") else RobotRecord(id, "bot-$id", ChannelKind.WECOM, pairCode = "1")

    private fun binding(
        id: String,
        tab: String,
    ) = Binding(RobotId(id), SessionTarget("/p", tab, AgentType.CODEX, "s-$tab"), 1)

    @Test
    fun `空闲、连接别处、连接本会话与未配对`() {
        val bindings = mapOf("b" to binding("b", "other"), "c" to binding("c", "this"))
        val entries =
            phoneMenuModel(
                listOf(robot("a"), robot("b"), robot("c"), robot("d", paired = false)),
                bindingOf = { bindings[it] },
                statusOf = { ConnectionStatus.Online },
                thisTabId = "this",
                titleOf = { "T:" + it.target.tabId },
                labels = labels,
            )
        assertEquals(
            listOf(
                PhoneMenuEntry.Robot("a", "bot-a/idle", true),
                PhoneMenuEntry.Robot("b", "bot-b/bound:T:other", true),
                PhoneMenuEntry.Robot("c", "bot-c/current", false),
                PhoneMenuEntry.Robot("d", "bot-d/unpaired", false),
                PhoneMenuEntry.AddRobot,
                PhoneMenuEntry.Disconnect("c"),
            ),
            entries,
        )
    }

    @Test
    fun `没有机器人`() {
        assertEquals(
            listOf(PhoneMenuEntry.NoRobots, PhoneMenuEntry.AddRobot),
            phoneMenuModel(emptyList(), bindingOf = { null }, statusOf = { null }, thisTabId = "t", titleOf = { "" }, labels = labels),
        )
    }

    @Test
    fun `空闲但不在线的机器人写明状态，仍可选择`() {
        val statuses = mapOf("a" to ConnectionStatus.Kicked, "b" to ConnectionStatus.Online)
        val entries =
            phoneMenuModel(
                listOf(robot("a"), robot("b"), robot("c")),
                bindingOf = { null },
                statusOf = { statuses[it] },
                thisTabId = "this",
                titleOf = { "" },
                labels = labels,
            )
        assertEquals(
            listOf(
                PhoneMenuEntry.Robot("a", "bot-a/offline:Kicked", true),
                PhoneMenuEntry.Robot("b", "bot-b/idle", true),
                PhoneMenuEntry.Robot("c", "bot-c/offline:null", true),
                PhoneMenuEntry.AddRobot,
            ),
            entries,
        )
    }
}
