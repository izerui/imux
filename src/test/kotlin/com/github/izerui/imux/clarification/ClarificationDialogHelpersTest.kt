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

    // ---- needsCenterRebuild：就地更新的另外两种情况 ----

    @Test
    fun `同一次调查只是进度变化不重建`() {
        val old = ClarificationState.Investigating(null, 100)
        assertFalse("startedAt 相同、进度变了 → 不重建", needsCenterRebuild(old, ClarificationState.Investigating("读取文件", 100)))
        assertTrue("startedAt 变了（新一轮）→ 重建", needsCenterRebuild(old, ClarificationState.Investigating(null, 200)))
    }

    @Test
    fun `同一次发送只是等待原因变化不重建`() {
        val old = ClarificationState.Sending("需求", waitingForTurn = true)
        assertFalse("等待原因变了 → 不重建", needsCenterRebuild(old, ClarificationState.Sending("需求", waitingForTurn = false)))
        assertTrue("需求文字变了 → 重建", needsCenterRebuild(old, ClarificationState.Sending("另一段", waitingForTurn = true)))
    }

    // ---- investigationKind ----

    private val direction = Direction("d1", "A", "", "", "")

    @Test
    fun `调查种类按能否生成结果与台账区分三种`() {
        assertEquals("不能生成结果 → 正在生成结果", InvestigationKind.RESULT, investigationKind(false, Ledger("r", null, directions = listOf(direction))))
        assertEquals("没有方向也没有问答 → 第一轮", InvestigationKind.FIRST_ROUND, investigationKind(true, Ledger("r", null)))
        assertEquals("已有方向 → 后续一轮", InvestigationKind.ROUND, investigationKind(true, Ledger("r", null, directions = listOf(direction))))
    }

    // ---- buttonBar ----

    private val laterLedger = Ledger("r", null, directions = listOf(direction))

    @Test
    fun `输入页只有关闭与开始澄清，开始澄清是默认按钮`() {
        val bar = buttonBar(ClarificationState.Input, false, Ledger("", null))
        assertEquals(emptyList<BarButton>(), bar.left)
        assertEquals(listOf(BarButton.CLOSE, BarButton.START), bar.right)
        assertEquals(BarButton.START, bar.default)
    }

    @Test
    fun `调查中第一轮不出现够了生成结果，后续一轮出现在左侧`() {
        val investigating = ClarificationState.Investigating(null, 0)
        val first = buttonBar(investigating, true, Ledger("r", null))
        assertEquals("第一轮左侧为空", emptyList<BarButton>(), first.left)
        assertEquals(listOf(BarButton.CLOSE, BarButton.CANCEL_INVESTIGATION), first.right)
        assertEquals(BarButton.CANCEL_INVESTIGATION, first.default)
        val later = buttonBar(investigating, true, laterLedger)
        assertEquals("后续一轮左侧是够了生成结果", listOf(BarButton.FINISH_LEFT), later.left)
        val generating = buttonBar(investigating, false, laterLedger)
        assertEquals("正在生成结果时不再出现", emptyList<BarButton>(), generating.left)
        assertEquals(BarButton.CANCEL_INVESTIGATION, generating.default)
    }

    @Test
    fun `提问页最后一题是确认，前面的题是确认下一题`() {
        val notLast = buttonBar(ClarificationState.Asking(listOf(q1, q2), 0), true, laterLedger)
        assertEquals(listOf(BarButton.SKIP_QUESTION), notLast.left)
        assertEquals(listOf(BarButton.FINISH, BarButton.CONFIRM_NEXT), notLast.right)
        assertEquals(BarButton.CONFIRM_NEXT, notLast.default)
        val last = buttonBar(ClarificationState.Asking(listOf(q1, q2), 1), true, laterLedger)
        assertEquals(listOf(BarButton.FINISH, BarButton.CONFIRM), last.right)
        assertEquals(BarButton.CONFIRM, last.default)
    }

    @Test
    fun `结果页、发送中、失败页各自的按钮栏`() {
        val result = buttonBar(ClarificationState.Result("x"), false, laterLedger)
        assertEquals(listOf(BarButton.CONTINUE, BarButton.COPY), result.left)
        assertEquals(listOf(BarButton.CLOSE, BarButton.SEND), result.right)
        assertEquals(BarButton.SEND, result.default)

        val sending = buttonBar(ClarificationState.Sending("x", true), false, laterLedger)
        assertEquals(emptyList<BarButton>(), sending.left)
        assertEquals(listOf(BarButton.CANCEL_SEND), sending.right)
        assertEquals(BarButton.CANCEL_SEND, sending.default)

        val failed = buttonBar(ClarificationState.Failed(FailureReason.TIMEOUT, "600", null), false, laterLedger)
        assertEquals(listOf(BarButton.FALLBACK), failed.left)
        assertEquals(listOf(BarButton.CLOSE, BarButton.RETRY), failed.right)
        assertEquals(BarButton.RETRY, failed.default)
    }

    @Test
    fun `每个状态的默认按钮都在右侧最后`() {
        val states =
            listOf(
                ClarificationState.Input,
                ClarificationState.Investigating(null, 0),
                ClarificationState.Asking(listOf(q1, q2), 0),
                ClarificationState.Asking(listOf(q1, q2), 1),
                ClarificationState.Result("x"),
                ClarificationState.Sending("x", false),
                ClarificationState.Failed(FailureReason.CANCELLED, "", null),
            )
        for (state in states) {
            val bar = buttonBar(state, true, laterLedger)
            assertEquals("$state 的默认按钮在右侧最后", bar.right.last(), bar.default)
        }
    }

    // ---- 文案 ----

    private val echo = ViewMessages { key, params -> key + params.joinToString(",", "[", "]") }

    @Test
    fun `题号说明只在一批多于一题时接上批次说明`() {
        assertEquals("clarify.question.header[1,1]", questionHeaderLine(0, 1, echo))
        assertEquals("clarify.question.header[2,3] · clarify.question.batch.note[]", questionHeaderLine(1, 3, echo))
    }

    @Test
    fun `清单标题只在有去掉的方向时附上去掉的数量`() {
        val kept = listOf(direction.copy(status = DirectionStatus.DECIDED))
        assertEquals("clarify.list.title[clarify.progress[1,0,0]]", listTitle(kept, echo))
        val withDropped = kept + direction.copy(id = "d2", status = DirectionStatus.DROPPED)
        assertEquals("clarify.list.title[clarify.progress[1,0,0] · clarify.progress.dropped[1]]", listTitle(withDropped, echo))
    }
}
