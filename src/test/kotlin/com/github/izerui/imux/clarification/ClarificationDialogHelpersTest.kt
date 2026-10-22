package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationDialogHelpersTest {

    // ---- progressCounts ----

    @Test
    fun `空方向列表的各项计数都是零`() {
        val counts = progressCounts(emptyList())
        assertEquals(0, counts.decided)
        assertEquals(0, counts.open)
        assertEquals(0, counts.defaulted)
        assertEquals(0, counts.dropped)
    }

    @Test
    fun `各状态的方向一律被正确计数`() {
        val directions = listOf(
            Direction("d1", "A", "", "", "", status = DirectionStatus.DECIDED),
            Direction("d2", "B", "", "", "", status = DirectionStatus.OPEN),
            Direction("d3", "C", "", "", "", status = DirectionStatus.ACTIVE),
            Direction("d4", "D", "", "", "", status = DirectionStatus.DEFAULTED),
            Direction("d5", "E", "", "", "", status = DirectionStatus.DROPPED),
            Direction("d6", "F", "", "", "", status = DirectionStatus.OPEN),
            Direction("d7", "G", "", "", "", status = DirectionStatus.DECIDED),
        )
        val counts = progressCounts(directions)
        assertEquals("DECIDED 计数", 2, counts.decided)
        assertEquals("OPEN 和 ACTIVE 合并为待定", 3, counts.open)
        assertEquals("DEFAULTED 计数", 1, counts.defaulted)
        assertEquals("DROPPED 计数", 1, counts.dropped)
    }

    @Test
    fun `ACTIVE 方向算作待定而非已定`() {
        val directions = listOf(
            Direction("d1", "A", "", "", "", status = DirectionStatus.ACTIVE),
        )
        val counts = progressCounts(directions)
        assertEquals("ACTIVE 归入 open（待定）", 1, counts.open)
        assertEquals("ACTIVE 不归入 decided", 0, counts.decided)
    }

    // ---- directionRowActions ----

    @Test
    fun `OPEN 方向可以跳过和先问这个两种操作`() {
        val actions = directionRowActions(DirectionStatus.OPEN)
        assertTrue(DirectionAction.SKIP in actions)
        assertTrue(DirectionAction.PIN in actions)
        assertFalse(DirectionAction.REOPEN in actions)
    }

    @Test
    fun `ACTIVE 方向只能跳过`() {
        val actions = directionRowActions(DirectionStatus.ACTIVE)
        assertTrue(DirectionAction.SKIP in actions)
        assertFalse(DirectionAction.PIN in actions)
        assertFalse(DirectionAction.REOPEN in actions)
    }

    @Test
    fun `DECIDED 和 DEFAULTED 方向都只能重新打开`() {
        for (status in listOf(DirectionStatus.DECIDED, DirectionStatus.DEFAULTED)) {
            val actions = directionRowActions(status)
            assertFalse("${status}不能跳过", DirectionAction.SKIP in actions)
            assertFalse("${status}不能先问这个", DirectionAction.PIN in actions)
            assertTrue("${status}可以重新打开", DirectionAction.REOPEN in actions)
        }
    }

    @Test
    fun `DROPPED 方向可以重新打开`() {
        val actions = directionRowActions(DirectionStatus.DROPPED)
        assertTrue(DirectionAction.REOPEN in actions)
        assertFalse(DirectionAction.SKIP in actions)
        assertFalse(DirectionAction.PIN in actions)
    }

    @Test
    fun `全部五种状态都有对应的操作集`() {
        for (status in DirectionStatus.entries) {
            val actions = directionRowActions(status)
            assertTrue("${status} 的操作集不能为空", actions.isNotEmpty())
        }
    }
}
