package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ---- needsCenterRebuild ----

    private val q1 = Question("d1", "Q1", "r", "b", listOf(ChoiceOption("a", "A", true, "C")))
    private val q2 = Question("d2", "Q2", "r", "b", listOf(ChoiceOption("a", "A", false, "C")))

    @Test
    fun `同一题同一索引但批次缩短不重建`() {
        val old = ClarificationState.Asking(listOf(q1, q2), 0)
        val new = ClarificationState.Asking(listOf(q1), 0)
        assertFalse("当前题不变、批次缩短 → 不重建", needsCenterRebuild(old, new))
    }

    @Test
    fun `索引变化时必须重建`() {
        val old = ClarificationState.Asking(listOf(q1, q2), 0)
        val new = ClarificationState.Asking(listOf(q1, q2), 1)
        assertTrue("索引变了 → 重建", needsCenterRebuild(old, new))
    }

    @Test
    fun `当前题不同时必须重建`() {
        val old = ClarificationState.Asking(listOf(q1), 0)
        val new = ClarificationState.Asking(listOf(q2), 0)
        assertTrue("当前题变了 → 重建", needsCenterRebuild(old, new))
    }

    @Test
    fun `状态类型不同时必须重建`() {
        val asking = ClarificationState.Asking(listOf(q1), 0)
        assertTrue("Asking → Investigating", needsCenterRebuild(asking, ClarificationState.Investigating(null, 0)))
        assertTrue("Investigating → Asking", needsCenterRebuild(ClarificationState.Investigating(null, 0), asking))
        assertTrue("null → Asking（首次渲染）", needsCenterRebuild(null, asking))
    }

    @Test
    fun `完全相同的状态不重建`() {
        val state = ClarificationState.Asking(listOf(q1), 0)
        assertFalse("同一状态实例 → 不重建", needsCenterRebuild(state, state))
    }

    // ---- requirementTooltip ----

    @Test
    fun `空白需求没有 tooltip`() {
        assertNull(requirementTooltip(""))
        assertNull(requirementTooltip("   "))
    }

    @Test
    fun `tooltip 用 HTML 包裹且限宽`() {
        val tip = requirementTooltip("hello\nworld")
        assertNotNull(tip)
        assertTrue("以 <html> 开头", tip!!.startsWith("<html>"))
        assertTrue("换行变 <br>", tip.contains("<br>"))
        assertTrue("包含 width 限宽", tip.contains("width:"))
    }

    @Test
    fun `tooltip 截断超长文本`() {
        val long = "x".repeat(3000)
        val tip = requirementTooltip(long)!!
        // 2000 个 x + HTML 标签
        assertFalse("不含第 2001 个字符之后的内容", tip.contains("x".repeat(2001)))
    }
}
