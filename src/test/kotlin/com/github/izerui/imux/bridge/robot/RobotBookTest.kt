package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.core.RobotOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RobotBookTest {
    private fun robot(id: String) = RobotRecord(id, "bot-$id", ChannelKind.WECOM, pairCode = "123456", pairCodeExpiresAtMillis = 99)

    @Test
    fun `重命名只改对应机器人`() {
        val book = RobotBook().add(robot("a")).add(robot("b")).rename("a", "开发助手")
        assertEquals("开发助手", book.robot("a")!!.name)
        assertEquals("bot-b", book.robot("b")!!.name)
    }

    @Test
    fun `认主后清除配对码`() {
        val owned = RobotBook().add(robot("a")).withOwner("a", RobotOwner("u1", "u1")).robot("a")!!
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
                .remove("a")
        assertEquals(listOf("b"), book.robots.map { it.id })
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
