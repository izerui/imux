package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.core.RobotId
import org.junit.Assert.assertEquals
import org.junit.Test

class RobotStoreMappingTest {
    @Test
    fun `映射往返保持一致`() {
        val book =
            RobotBook(
                robots =
                    listOf(
                        RobotRecord(RobotId("a"), "开发助手 1", ChannelKind.WECOM, pairCode = "123456", pairCodeExpiresAtMillis = 10, createdAtMillis = 5),
                        RobotRecord(RobotId("b"), "审查", ChannelKind.FEISHU, ownerSenderId = "o", ownerChatId = "o", createdAtMillis = 6),
                    ),
                bindings = listOf(SavedBinding("b", "/p", "codex", "s1")),
            )
        assertEquals(book, toBook(fromBook(book)))
    }

    @Test
    fun `未知渠道与空 id 的记录被丢弃`() {
        val state =
            RobotStore.State(
                robots =
                    listOf(
                        RobotStore.RobotXml(id = "a", name = "x", kind = "telegram"),
                        RobotStore.RobotXml(id = "", name = "y", kind = "wecom"),
                        RobotStore.RobotXml(id = "c", name = "z", kind = "wecom"),
                    ),
                bindings = listOf(RobotStore.BindingXml(robotId = "c", projectPath = "/p", agentCli = "codex", sessionId = "")),
            )
        val book = toBook(state)
        assertEquals(listOf("c"), book.robots.map { it.id.value })
        assertEquals(emptyList<SavedBinding>(), book.bindings)
    }
}
