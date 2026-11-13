package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.bridge.robot.RobotRecord
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMenuTest {
    private val labels =
        object : MenuLabels {
            override fun idle() = "idle"

            override fun bound() = "bound"

            override fun boundOffline() = "boundOffline"

            override fun unpaired() = "unpaired"

            override fun current() = "current"

            override fun currentOffline() = "currentOffline"

            override fun problem(status: ConnectionStatus?) = "problem:$status"
        }

    private fun robot(
        id: String,
        paired: Boolean = true,
    ) = if (paired) RobotRecord(RobotId(id), "bot-$id", ChannelKind.WECOM, ownerSenderId = "u", ownerChatId = "u") else RobotRecord(RobotId(id), "bot-$id", ChannelKind.WECOM, pairCode = "1")

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
                bindingOf = { bindings[it.value] },
                sessionAvailable = { true },
                statusOf = { ConnectionStatus.Online },
                thisTabId = "this",
                titleOf = { "T:" + it.target.tabId },
                labels = labels,
            )
        assertEquals(
            listOf(
                PhoneMenuEntry.Robot(RobotId("a"), "bot-a", RobotMenuState.IDLE, "idle", true),
                PhoneMenuEntry.Robot(RobotId("b"), "bot-b", RobotMenuState.CONNECTED, "bound", true, session = "T:other"),
                PhoneMenuEntry.Robot(RobotId("c"), "bot-c", RobotMenuState.CONNECTED, "current", false, current = true, session = "T:this"),
                PhoneMenuEntry.Robot(RobotId("d"), "bot-d", RobotMenuState.UNPAIRED, "unpaired", false),
                PhoneMenuEntry.AddRobot,
                PhoneMenuEntry.Disconnect(RobotId("c")),
            ),
            entries,
        )
    }

    @Test
    fun `菜单里的机器人名带上渠道，各种状态都用同一个名字`() {
        val bindings = mapOf("b" to binding("b", "other"))
        val entries =
            phoneMenuModel(
                listOf(robot("a"), robot("b")),
                bindingOf = { bindings[it.value] },
                sessionAvailable = { true },
                statusOf = { ConnectionStatus.Online },
                thisTabId = "this",
                titleOf = { "T" },
                labels = labels,
                nameOf = { "企微 · " + it.name },
            )
        assertEquals(
            listOf(Triple("企微 · bot-a", "idle", null), Triple("企微 · bot-b", "bound", "T")),
            entries.filterIsInstance<PhoneMenuEntry.Robot>().map { Triple(it.label, it.status, it.session) },
        )
    }

    @Test
    fun `渠道简称：中文把企业微信写作企微，其它渠道沿用原名`() {
        for (kind in ChannelKind.entries) for (language in com.github.izerui.imux.settings.PluginLanguage.entries) {
            val short = com.github.izerui.imux.ImuxBundle.message(language, "bridge.channel.${kind.id}.short")
            val full = com.github.izerui.imux.ImuxBundle.message(language, "bridge.channel.${kind.id}")
            val chinese = language.name.contains("CHINESE")
            if (kind == ChannelKind.WECOM && chinese) assertEquals("企微", short) else assertEquals(full, short)
        }
    }

    @Test
    fun `没有机器人`() {
        assertEquals(
            listOf(PhoneMenuEntry.NoRobots, PhoneMenuEntry.AddRobot),
            phoneMenuModel(emptyList(), bindingOf = { null }, sessionAvailable = { true }, statusOf = { null }, thisTabId = "t", titleOf = { "" }, labels = labels),
        )
    }

    @Test
    fun `空闲但不在线的机器人写明状态，仍可选择`() {
        val statuses = mapOf("a" to ConnectionStatus.Kicked, "b" to ConnectionStatus.Online)
        val entries =
            phoneMenuModel(
                listOf(robot("a"), robot("b"), robot("c")),
                bindingOf = { null },
                sessionAvailable = { true },
                statusOf = { statuses[it.value] },
                thisTabId = "this",
                titleOf = { "" },
                labels = labels,
            )
        assertEquals(
            listOf(
                PhoneMenuEntry.Robot(RobotId("a"), "bot-a", RobotMenuState.PROBLEM, "problem:Kicked", true),
                PhoneMenuEntry.Robot(RobotId("b"), "bot-b", RobotMenuState.IDLE, "idle", true),
                PhoneMenuEntry.Robot(RobotId("c"), "bot-c", RobotMenuState.PROBLEM, "problem:null", true),
                PhoneMenuEntry.AddRobot,
            ),
            entries,
        )
    }

    @Test
    fun `保留绑定但会话离线时菜单显示离线的会话且允许重新选择`() {
        val entries = phoneMenuModel(
            listOf(robot("a"), robot("b")),
            bindingOf = { binding(it.value, if (it.value == "a") "closed" else "live") },
            sessionAvailable = { it.target.tabId == "live" },
            statusOf = { ConnectionStatus.Online },
            thisTabId = "this",
            titleOf = { it.target.tabId },
            labels = labels,
        )
        assertEquals(PhoneMenuEntry.Robot(RobotId("a"), "bot-a", RobotMenuState.OFFLINE, "boundOffline", true, session = "closed"), entries[0])
        assertEquals(PhoneMenuEntry.Robot(RobotId("b"), "bot-b", RobotMenuState.CONNECTED, "bound", true, session = "live"), entries[1])
    }

    @Test
    fun `旧工程离线时菜单仍显示机器人失败或被接管的原因`() {
        val entries = phoneMenuModel(
            listOf(robot("a"), robot("b")),
            bindingOf = { binding(it.value, "closed-${it.value}") },
            sessionAvailable = { false },
            statusOf = { if (it.value == "a") ConnectionStatus.Failed("auth") else ConnectionStatus.Kicked },
            thisTabId = "this",
            titleOf = { "closed" },
            labels = labels,
        )
        assertEquals(PhoneMenuEntry.Robot(RobotId("a"), "bot-a", RobotMenuState.PROBLEM, "problem:Failed(reason=auth)", true, session = "closed"), entries[0])
        assertEquals(PhoneMenuEntry.Robot(RobotId("b"), "bot-b", RobotMenuState.PROBLEM, "problem:Kicked", true, session = "closed"), entries[1])
    }

    @Test
    fun `本标签尚未运行时不允许重复绑定自己但保留断开入口`() {
        val entries = phoneMenuModel(
            listOf(robot("a")),
            bindingOf = { binding("a", "this") },
            sessionAvailable = { false },
            statusOf = { ConnectionStatus.Online },
            thisTabId = "this",
            titleOf = { "title" },
            labels = labels,
        )
        assertEquals(PhoneMenuEntry.Robot(RobotId("a"), "bot-a", RobotMenuState.OFFLINE, "currentOffline", false, current = true, session = "title"), entries[0])
        assertEquals(PhoneMenuEntry.Disconnect(RobotId("a")), entries.last())
    }

    @Test
    fun `菜单里的长会话标题截短，免得撑宽弹窗`() {
        assertEquals("短标题", menuTitle("  短标题 "))
        val long = "Review the current code changes (staged, unstaged, and untracked)"
        val cut = menuTitle(long)
        assertEquals(24, cut.codePointCount(0, cut.length))
        assertTrue(cut.endsWith("…") && long.startsWith(cut.dropLast(1)))
    }
}
