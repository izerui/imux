package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationLedgerTest {
    private fun dir(id: String, status: DirectionStatus = DirectionStatus.OPEN, userOwned: Boolean = false) =
        Direction(id, "title-$id", "why", "src", "fallback-$id", status, userOwned)

    private fun option(id: String) = ChoiceOption(id, "label-$id", recommended = id == "a", conclusion = "conclusion-$id")

    private fun ask(directionId: String) = Next.Ask(Question(directionId, "Q", "R", "B", listOf(option("a"), option("b"))))

    private fun response(
        next: Next,
        added: List<NewDirection> = emptyList(),
        dropped: List<DroppedDirection> = emptyList(),
        previous: Previous? = null,
        notes: String = "",
    ) = RoundResponse(null, added, dropped, previous, notes, next)

    private val base = Ledger("需求", null, directions = listOf(dir("d1"), dir("d2")), nextDirectionNumber = 3)

    @Test
    fun `首轮新增两个方向时问题关联到第二个`() {
        val out = Ledger("需求", null).merge(
            response(
                ask("new-2"),
                added = listOf(NewDirection("new-1", "第一", "w", "s", "f"), NewDirection("new-2", "第二", "w", "s", "f")),
            ),
            emptySet(),
        )
        assertTrue(out.errors.isEmpty())
        val second = out.ledger.directions.single { it.title == "第二" }
        val first = out.ledger.directions.single { it.title == "第一" }
        val asked = (out.next as MergedNext.Ask).question.directionId
        assertEquals(second.id, asked)
        assertNotEquals(first.id, asked)
        assertEquals(DirectionStatus.ACTIVE, second.status)
    }

    @Test
    fun `临时 ID 重复或前缀不对或引用不存在都判为错误`() {
        val dup = base.merge(response(ask("new-1"), added = listOf(NewDirection("new-1", "x", "w", "s", "f"), NewDirection("new-1", "y", "w", "s", "f"))), emptySet())
        assertTrue(dup.errors.any { it.contains("duplicate") })
        val prefix = base.merge(response(ask("d1"), added = listOf(NewDirection("tmp-1", "x", "w", "s", "f"))), emptySet())
        assertTrue(prefix.errors.any { it.contains("new-") })
        val missing = base.merge(response(ask("d9")), emptySet())
        assertTrue(missing.errors.any { it.contains("d9") })
    }

    @Test
    fun `出错时台账保持原样`() {
        val out = base.merge(response(ask("d9"), added = listOf(NewDirection("new-1", "x", "w", "s", "f"))), emptySet())
        assertEquals(base, out.ledger)
        assertNull(out.next)
    }

    @Test
    fun `模型去掉跳过的方向与用户添加的方向都被丢弃`() {
        val ledger = base.copy(directions = listOf(dir("d1", DirectionStatus.DEFAULTED), dir("user-1", userOwned = true), dir("d2")))
        val out = ledger.merge(response(ask("d2"), dropped = listOf(DroppedDirection("d1", "r"), DroppedDirection("user-1", "r"))), emptySet())
        assertTrue(out.errors.isEmpty())
        assertEquals(DirectionStatus.DEFAULTED, out.ledger.direction("d1")!!.status)
        assertEquals(DirectionStatus.OPEN, out.ledger.direction("user-1")!!.status)
    }

    @Test
    fun `问题针对已跳过的方向时判为错误`() {
        val ledger = base.skip("d1")
        val out = ledger.merge(response(ask("d1")), emptySet())
        assertFalse(out.errors.isEmpty())
        assertFalse(out.invalidatedByUser)
        assertEquals(DirectionStatus.DEFAULTED, out.ledger.direction("d1")!!.status)
    }

    @Test
    fun `调查期间被用户跳过的方向导致问题失效时标为用户造成`() {
        val ledger = base.skip("d1")
        val out = ledger.merge(response(ask("d1")), changedByUser = setOf("d1"))
        assertTrue(out.invalidatedByUser)
        assertEquals(DirectionStatus.DEFAULTED, out.ledger.direction("d1")!!.status)
    }

    @Test
    fun `先问这个之后模型指向别的方向判为错误`() {
        val out = base.pin("d2").merge(response(ask("d1")), emptySet())
        assertTrue(out.errors.any { it.contains("d2") })
    }

    @Test
    fun `先问这个的方向出题后指定解除`() {
        val out = base.pin("d2").merge(response(ask("d2")), emptySet())
        assertTrue(out.errors.isEmpty())
        assertNull(out.ledger.pinned)
    }

    @Test
    fun `选中选项记下的结论与选项显示的一致`() {
        val question = (ask("d1")).question
        val ledger = base.recordAnswer(question, Answer.Picked("b"), correction = null)
        assertEquals("conclusion-b", ledger.history.last().recorded)
        assertEquals("d1", ledger.lastAsked)
    }

    @Test
    fun `其他想法原话照录`() {
        val ledger = base.recordAnswer(ask("d1").question, Answer.OwnWords("  我的原话  "), correction = null)
        assertEquals("我的原话", ledger.history.last().recorded)
    }

    @Test
    fun `跳过时记下默认处理且不要求 previous`() {
        val ledger = base.recordAnswer(ask("d1").question, Answer.Skipped, correction = null)
        assertEquals("fallback-d1", ledger.history.last().recorded)
        assertEquals(DirectionStatus.DEFAULTED, ledger.direction("d1")!!.status)
        assertNull(ledger.lastAsked)
    }

    @Test
    fun `纠正理解随回答记下`() {
        val ledger = base.recordAnswer(ask("d1").question, Answer.Picked("a"), correction = "不是重连是重开")
        assertEquals(listOf("不是重连是重开"), ledger.corrections)
    }

    @Test
    fun `已定方向的结论取用户的回答而不是模型的转述`() {
        val ledger = base.recordAnswer(ask("d1").question, Answer.Picked("a"), null)
        val out = ledger.merge(response(ask("d2"), previous = Previous("d1", decided = true, conclusion = "模型改写的结论", openPoints = emptyList())), emptySet())
        assertTrue(out.errors.isEmpty())
        assertEquals(DirectionStatus.DECIDED, out.ledger.direction("d1")!!.status)
        assertEquals("conclusion-a", out.ledger.direction("d1")!!.conclusion)
    }

    @Test
    fun `未定的方向回到待确认并记下剩余疑点`() {
        val ledger = base.recordAnswer(ask("d1").question, Answer.Picked("a"), null)
        val out = ledger.merge(response(ask("d2"), previous = Previous("d1", decided = false, conclusion = "部分结论", openPoints = listOf("疑点"))), emptySet())
        assertEquals(DirectionStatus.OPEN, out.ledger.direction("d1")!!.status)
        assertEquals(listOf("疑点"), out.ledger.direction("d1")!!.openPoints)
    }

    @Test
    fun `回答后的下一轮缺少 previous 判为错误`() {
        val ledger = base.recordAnswer(ask("d1").question, Answer.Picked("a"), null)
        assertTrue(ledger.merge(response(ask("d2")), emptySet()).errors.any { it.contains("previous") })
    }

    @Test
    fun `调查笔记超长判为错误`() {
        val out = base.merge(response(ask("d1"), notes = "x".repeat(MAX_NOTES_CHARS + 1)), emptySet())
        assertTrue(out.errors.any { it.contains("notes") })
    }

    @Test
    fun `重新打开的方向归用户所有且回到待确认`() {
        val ledger = base.skip("d1").reopen("d1")
        assertEquals(DirectionStatus.OPEN, ledger.direction("d1")!!.status)
        assertTrue(ledger.direction("d1")!!.userOwned)
    }

    @Test
    fun `用户添加的方向编号与模型的不冲突`() {
        val (ledger, id) = base.addUserDirection("我的方向")
        assertEquals("user-1", id)
        assertTrue(ledger.direction("user-1")!!.userOwned)
        assertEquals(2, ledger.nextUserNumber)
    }

    @Test
    fun `兜底结果包含原需求与已定和默认的结论但不含未定的`() {
        val ledger = base.copy(
            directions = listOf(
                dir("d1", DirectionStatus.DECIDED).copy(conclusion = "已定结论"),
                dir("d2", DirectionStatus.DEFAULTED).copy(conclusion = "默认结论"),
                dir("d3", DirectionStatus.OPEN).copy(conclusion = "未定结论"),
            ),
        )
        val text = composeFallback(ledger)
        assertTrue(text.startsWith("需求"))
        assertTrue(text.contains("已定结论"))
        assertTrue(text.contains("默认结论"))
        assertFalse(text.contains("未定结论"))
    }

    @Test
    fun `模型去掉已定的方向被忽略`() {
        val ledger = base.copy(directions = listOf(dir("d1", DirectionStatus.DECIDED).copy(conclusion = "已定结论"), dir("d2")))
        val out = ledger.merge(response(ask("d2"), dropped = listOf(DroppedDirection("d1", "不需要了"))), emptySet())
        assertTrue(out.errors.isEmpty())
        assertEquals(DirectionStatus.DECIDED, out.ledger.direction("d1")!!.status)
        assertEquals("已定结论", out.ledger.direction("d1")!!.conclusion)
    }

    @Test
    fun `模型去掉用户刚回答的方向被忽略且结论仍取用户的回答`() {
        val ledger = base.recordAnswer(ask("d1").question, Answer.Picked("b"), null)
        val out = ledger.merge(
            response(ask("d2"), dropped = listOf(DroppedDirection("d1", "不需要了")), previous = Previous("d1", decided = true, conclusion = "转述", openPoints = emptyList())),
            emptySet(),
        )
        assertTrue(out.errors.isEmpty())
        assertEquals(DirectionStatus.DECIDED, out.ledger.direction("d1")!!.status)
        assertEquals("conclusion-b", out.ledger.direction("d1")!!.conclusion)
    }

    @Test
    fun `模型去掉先问这个的方向被忽略`() {
        val out = base.pin("d2").merge(response(ask("d2"), dropped = listOf(DroppedDirection("d2", "不需要了"))), emptySet())
        assertTrue(out.errors.isEmpty())
        assertEquals(DirectionStatus.ACTIVE, out.ledger.direction("d2")!!.status)
    }

    @Test
    fun `模型去掉的方向记下理由`() {
        val out = base.merge(response(ask("d1"), dropped = listOf(DroppedDirection("d2", "已由回答覆盖"))), emptySet())
        assertTrue(out.errors.isEmpty())
        assertEquals(DirectionStatus.DROPPED, out.ledger.direction("d2")!!.status)
        assertEquals("已由回答覆盖", out.ledger.direction("d2")!!.dropReason)
    }

    @Test
    fun `指定先问这个后模型返回 done 判为错误`() {
        val ledger = base.pin("d2")
        val out = ledger.merge(response(Next.Done("REQ")), emptySet())
        assertTrue(out.errors.any { it.contains("d2") })
        assertFalse(out.invalidatedByUser)
        assertNull(out.next)
        assertEquals(ledger, out.ledger)
    }

    @Test
    fun `调查期间用户改过方向时模型返回 done 标为用户造成`() {
        val (ledger, id) = base.addUserDirection("我的方向")
        val out = ledger.merge(response(Next.Done("REQ")), changedByUser = setOf(id))
        assertTrue(out.invalidatedByUser)
        assertNull(out.next)
        assertEquals(ledger, out.ledger)
    }

    @Test
    fun `离开问题页时被问而未回答的方向回到待确认`() {
        val asked = base.merge(response(ask("d1")), emptySet()).ledger
        assertEquals(DirectionStatus.ACTIVE, asked.direction("d1")!!.status)
        assertEquals(DirectionStatus.OPEN, asked.releaseUnanswered().direction("d1")!!.status)
    }

    @Test
    fun `离开时用户刚回答待交代的方向保持当前`() {
        val answered = base.recordAnswer(ask("d1").question, Answer.Picked("a"), null)
        assertEquals(DirectionStatus.ACTIVE, answered.releaseUnanswered().direction("d1")!!.status)
    }
}
