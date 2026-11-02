package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.RobotOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RobotBookTest {
    private fun robot(id: String) = RobotRecord(RobotId(id), "bot-$id", ChannelKind.WECOM, pairCode = "123456", pairCodeExpiresAtMillis = 99)

    @Test
    fun `重命名只改对应机器人`() {
        val book = RobotBook().add(robot("a")).add(robot("b")).rename(RobotId("a"), "开发助手")
        assertEquals("开发助手", book.robot(RobotId("a"))!!.name)
        assertEquals("bot-b", book.robot(RobotId("b"))!!.name)
    }

    @Test
    fun `平台名称变化才覆盖本地改名`() {
        val id = RobotId("a")
        val synced = RobotBook().add(robot("a")).syncPlatformName(id, "飞书助手")
        assertEquals("飞书助手", synced.robot(id)!!.name)
        assertEquals("飞书助手", synced.robot(id)!!.platformName)

        val renamed = synced.rename(id, "本地名")
        assertEquals("飞书助手", renamed.robot(id)!!.platformName)
        assertEquals("本地名", renamed.syncPlatformName(id, "飞书助手").robot(id)!!.name)

        val changed = renamed.syncPlatformName(id, "飞书新名").robot(id)!!
        assertEquals("飞书新名", changed.name)
        assertEquals("飞书新名", changed.platformName)
    }

    @Test
    fun `界面顺序按渠道分组，同渠道内按添加先后`() {
        fun record(id: String, kind: ChannelKind, createdAt: Long) = RobotRecord(RobotId(id), id, kind, createdAtMillis = createdAt)
        val robots =
            listOf(
                record("w1", ChannelKind.WECOM, 1),
                record("d1", ChannelKind.DINGTALK, 2),
                record("f1", ChannelKind.FEISHU, 3),
                record("w2", ChannelKind.WECOM, 4),
                record("f2", ChannelKind.FEISHU, 5),
            )
        assertEquals(listOf("w1", "w2", "f1", "f2", "d1"), robots.inDisplayOrder().map { it.id.value })
    }

    @Test
    fun `认主后清除配对码`() {
        val owned = RobotBook().add(robot("a")).withOwner(RobotId("a"), RobotOwner("u1", "u1")).robot(RobotId("a"))!!
        assertEquals(RobotOwner("u1", "u1"), owned.owner())
        assertNull(owned.pairCode)
        assertEquals(0L, owned.pairCodeExpiresAtMillis)
    }

    @Test
    fun `删除机器人同时删除它的绑定`() {
        val book =
            RobotBook()
                .add(robot("a"))
                .add(robot("b"))
                .withBindings(listOf(SavedBinding("a", "/p", "codex", "s1"), SavedBinding("b", "/p", "claude", "s2")))
                .remove(RobotId("a"))
        assertEquals(listOf("b"), book.robots.map { it.id.value })
        assertEquals(listOf("b"), book.bindings.map { it.robotId })
    }

    @Test
    fun `保存绑定时丢弃不存在的机器人`() {
        val book = RobotBook().add(robot("a")).withBindings(listOf(SavedBinding("ghost", "/p", "codex", "s1")))
        assertEquals(emptyList<SavedBinding>(), book.bindings)
    }

    @Test
    fun `未认主时没有本人`() {
        assertNull(robot("a").owner())
    }
}
