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

    private fun q(directionId: String) = Question(directionId, "Q-$directionId", "R", "B", listOf(option("a"), option("b")))

    private fun ask(vararg directionIds: String) = Next.Ask(directionIds.map(::q))

    private fun response(
        next: Next,
        added: List<NewDirection> = emptyList(),
        dropped: List<DroppedDirection> = emptyList(),
        answered: List<AnsweredReport> = emptyList(),
        notes: String = "",
    ) = RoundResponse(null, added, dropped, answered, notes, next)

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
        val asked = (out.next as MergedNext.Ask).questions.first().directionId
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
        val question = q("d1")
        val ledger = base.recordAnswer(question, Answer.Picked("b"), correction = null)
        assertEquals("conclusion-b", ledger.history.last().recorded)
        assertEquals(listOf("d1"), ledger.awaitingReport)
    }

    @Test
    fun `其他想法原话照录`() {
        val ledger = base.recordAnswer(q("d1"), Answer.OwnWords("  我的原话  "), correction = null)
        assertEquals("我的原话", ledger.history.last().recorded)
    }

    @Test
    fun `跳过时记下默认处理且不要求 answered 交代`() {
        val ledger = base.recordAnswer(q("d1"), Answer.Skipped, correction = null)
        assertEquals("fallback-d1", ledger.history.last().recorded)
        assertEquals(DirectionStatus.DEFAULTED, ledger.direction("d1")!!.status)
        assertTrue(ledger.awaitingReport.isEmpty())
    }

    @Test
    fun `纠正理解随回答记下`() {
        val ledger = base.recordAnswer(q("d1"), Answer.Picked("a"), correction = "不是重连是重开")
        assertEquals(listOf("不是重连是重开"), ledger.corrections)
    }

    @Test
    fun `已定方向的结论取用户的回答而不是模型的转述`() {
        val ledger = base.recordAnswer(q("d1"), Answer.Picked("a"), null)
        val out = ledger.merge(response(ask("d2"), answered = listOf(AnsweredReport("d1", decided = true, conclusion = "模型改写的结论", openPoints = emptyList()))), emptySet())
        assertTrue(out.errors.isEmpty())
        assertEquals(DirectionStatus.DECIDED, out.ledger.direction("d1")!!.status)
        assertEquals("conclusion-a", out.ledger.direction("d1")!!.conclusion)
    }

    @Test
    fun `未定的方向回到待确认并记下剩余疑点`() {
        val ledger = base.recordAnswer(q("d1"), Answer.Picked("a"), null)
        val out = ledger.merge(response(ask("d2"), answered = listOf(AnsweredReport("d1", decided = false, conclusion = "部分结论", openPoints = listOf("疑点")))), emptySet())
        assertEquals(DirectionStatus.OPEN, out.ledger.direction("d1")!!.status)
        assertEquals(listOf("疑点"), out.ledger.direction("d1")!!.openPoints)
    }

    @Test
    fun `回答后的下一轮缺少 answered 交代判为错误`() {
        val ledger = base.recordAnswer(q("d1"), Answer.Picked("a"), null)
        assertTrue(ledger.merge(response(ask("d2")), emptySet()).errors.any { it.contains("answered must report direction d1") })
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
        val ledger = base.recordAnswer(q("d1"), Answer.Picked("b"), null)
        val out = ledger.merge(
            response(ask("d2"), dropped = listOf(DroppedDirection("d1", "不需要了")), answered = listOf(AnsweredReport("d1", decided = true, conclusion = "转述", openPoints = emptyList()))),
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
        val answered = base.recordAnswer(q("d1"), Answer.Picked("a"), null)
        assertEquals(DirectionStatus.ACTIVE, answered.releaseUnanswered().direction("d1")!!.status)
    }

    // ---- confirmedConclusions ----

    @Test
    fun `confirmedConclusions 包含 DECIDED 方向的结论`() {
        val ledger = base.copy(
            directions = listOf(dir("d1", DirectionStatus.DECIDED).copy(conclusion = "DECIDED-MARK")),
        )
        assertTrue(ledger.confirmedConclusions().contains("DECIDED-MARK"))
    }

    @Test
    fun `confirmedConclusions 包含 DEFAULTED 方向的结论`() {
        val ledger = base.copy(
            directions = listOf(dir("d1", DirectionStatus.DEFAULTED).copy(conclusion = "DEFAULTED-MARK")),
        )
        assertTrue(ledger.confirmedConclusions().contains("DEFAULTED-MARK"))
    }

    @Test
    fun `confirmedConclusions 包含 ACTIVE 方向上用户已回答的记录`() {
        val question = q("d1")
        val ledger = base.copy(
            directions = listOf(dir("d1", DirectionStatus.ACTIVE)),
        ).recordAnswer(question, Answer.Picked("a"), null)
        val confirmed = ledger.confirmedConclusions()
        assertTrue("ACTIVE answered should be included", confirmed.contains("conclusion-a"))
    }

    @Test
    fun `confirmedConclusions 包含 OPEN 方向上用户已回答的记录`() {
        // Simulate: user answered, then model reported answered status=OPEN (still has open points)
        val question = q("d1")
        val answered = base.recordAnswer(question, Answer.Picked("b"), null)
        // Merge with answered status=OPEN to put d1 back to OPEN
        val merged = answered.merge(
            response(ask("d2"), answered = listOf(AnsweredReport("d1", decided = false, conclusion = "partial", openPoints = listOf("point")))),
            emptySet(),
        )
        assertTrue(merged.errors.isEmpty())
        assertEquals(DirectionStatus.OPEN, merged.ledger.direction("d1")!!.status)
        val confirmed = merged.ledger.confirmedConclusions()
        assertTrue("OPEN answered should be included", confirmed.contains("conclusion-b"))
        assertFalse("model's partial conclusion must not appear", confirmed.contains("partial"))
    }

    @Test
    fun `confirmedConclusions 不包含从未回答的方向`() {
        val ledger = base.copy(
            directions = listOf(dir("d1", DirectionStatus.OPEN).copy(conclusion = "PARTIAL-X", fallback = "UNANSWERED-FB")),
        )
        val confirmed = ledger.confirmedConclusions()
        assertFalse("never-answered direction's fallback must not appear", confirmed.contains("UNANSWERED-FB"))
        assertFalse("never-answered direction's model conclusion must not appear", confirmed.contains("PARTIAL-X"))
        assertTrue("never-answered direction: empty confirmed", confirmed.isEmpty())
    }

    @Test
    fun `confirmedConclusions 不包含 DROPPED 方向上的旧回答`() {
        val question = q("d1")
        val answered = base.recordAnswer(question, Answer.Picked("a"), null)
        // Drop d1
        val dropped = answered.copy(
            directions = answered.directions.map { if (it.id == "d1") it.copy(status = DirectionStatus.DROPPED) else it },
        )
        val confirmed = dropped.confirmedConclusions()
        assertFalse("DROPPED answered must not appear", confirmed.contains("conclusion-a"))
    }

    @Test
    fun `confirmedConclusions 对 DECIDED 方向同时有 history 时不重复`() {
        val question = q("d1")
        val answered = base.recordAnswer(question, Answer.Picked("a"), null)
        // Mark d1 as DECIDED with the same conclusion
        val decided = answered.copy(
            directions = answered.directions.map {
                if (it.id == "d1") it.copy(status = DirectionStatus.DECIDED, conclusion = "conclusion-a") else it
            },
        )
        val confirmed = decided.confirmedConclusions()
        assertEquals("no duplicates", 1, confirmed.count { it == "conclusion-a" })
    }

    // ---- reopen voids earlier answers ----

    @Test
    fun `重新打开方向后旧回答被作废且不出现在 confirmedConclusions 中`() {
        // Answer d1 with a distinct marker, model reports DECIDED, then reopen
        val question = q("d1")
        val answered = base.recordAnswer(question, Answer.Picked("a"), null)
        // Simulate model reporting DECIDED
        val decided = answered.copy(
            directions = answered.directions.map {
                if (it.id == "d1") it.copy(status = DirectionStatus.DECIDED, conclusion = "OLD-MARK-3P") else it
            },
            history = answered.history.map {
                if (it.question.directionId == "d1") it.copy(recorded = "OLD-MARK-3P") else it
            },
        )
        assertTrue("before reopen, confirmedConclusions contains OLD-MARK-3P", decided.confirmedConclusions().contains("OLD-MARK-3P"))
        val reopened = decided.reopen("d1")
        // confirmedConclusions must not contain OLD-MARK-3P
        assertFalse("after reopen, confirmedConclusions must not contain OLD-MARK-3P", reopened.confirmedConclusions().contains("OLD-MARK-3P"))
        // history record still exists and is voided
        val record = reopened.history.single { it.question.directionId == "d1" }
        assertTrue("history record must still exist and be voided", record.voided)
    }

    @Test
    fun `重新打开后重新回答再 DECIDED 时结论只取新回答`() {
        // Answer d1 with OLD-MARK-3P, DECIDED, reopen, answer again with NEW-MARK-7H, merge DECIDED
        val q1 = q("d1")
        val answered = base.recordAnswer(q1, Answer.OwnWords("OLD-MARK-3P"), null)
        val decided = answered.copy(
            directions = answered.directions.map {
                if (it.id == "d1") it.copy(status = DirectionStatus.DECIDED, conclusion = "OLD-MARK-3P") else it
            },
        )
        val reopened = decided.reopen("d1")
        // Answer again with new marker
        val q2 = Question("d1", "Q2", "R", "B", listOf(option("a"), option("b")))
        val reAnswered = reopened.recordAnswer(q2, Answer.OwnWords("NEW-MARK-7H"), null)
        // Merge a response that reports answered DECIDED for d1
        val out = reAnswered.merge(
            response(ask("d2"), answered = listOf(AnsweredReport("d1", decided = true, conclusion = "模型转述", openPoints = emptyList()))),
            emptySet(),
        )
        assertTrue(out.errors.isEmpty())
        assertEquals("NEW-MARK-7H", out.ledger.direction("d1")!!.conclusion)
        assertTrue("confirmedConclusions contains NEW-MARK-7H", out.ledger.confirmedConclusions().contains("NEW-MARK-7H"))
        assertFalse("confirmedConclusions must not contain OLD-MARK-3P", out.ledger.confirmedConclusions().contains("OLD-MARK-3P"))
    }

    @Test
    fun `重新打开一个方向不影响另一个方向的记录`() {
        // d1 DECIDED with history, d2 DECIDED with history; reopen d1 only
        val q1 = q("d1")
        val q2 = Question("d2", "Q2", "R", "B", listOf(option("a"), option("b")))
        val answered = base
            .recordAnswer(q1, Answer.OwnWords("D1-MARK"), null)
            .recordAnswer(q2, Answer.OwnWords("D2-MARK"), null)
        // Set both to DECIDED so reopen is allowed
        val decided = answered.copy(
            directions = answered.directions.map { it.copy(status = DirectionStatus.DECIDED) },
        )
        val reopened = decided.reopen("d1")
        // d1's record is voided
        assertTrue(reopened.history.first { it.question.directionId == "d1" }.voided)
        // d2's record is NOT voided
        assertFalse(reopened.history.first { it.question.directionId == "d2" }.voided)
    }

    // ---- 每轮一批问题 ----

    private fun decided(id: String) = AnsweredReport(id, decided = true, conclusion = "", openPoints = emptyList())

    private val three = Ledger("需求", null, directions = listOf(dir("d1"), dir("d2"), dir("d3")), nextDirectionNumber = 4)

    @Test
    fun `一批问题合并后各方向都处于当前且待交代清空`() {
        val answered = three.recordAnswer(q("d1"), Answer.Picked("a"), null)
        val out = answered.merge(response(ask("d2", "d3"), answered = listOf(decided("d1"))), emptySet())
        assertTrue(out.errors.isEmpty())
        assertEquals(listOf("d2", "d3"), (out.next as MergedNext.Ask).questions.map { it.directionId })
        assertEquals(DirectionStatus.ACTIVE, out.ledger.direction("d2")!!.status)
        assertEquals(DirectionStatus.ACTIVE, out.ledger.direction("d3")!!.status)
        assertEquals(DirectionStatus.DECIDED, out.ledger.direction("d1")!!.status)
        assertTrue(out.ledger.awaitingReport.isEmpty())
    }

    @Test
    fun `一批里回答过的方向按回答顺序等待交代`() {
        val answered = three.recordAnswer(q("d2"), Answer.Picked("a"), null).recordAnswer(q("d1"), Answer.OwnWords("原话"), null)
        assertEquals(listOf("d2", "d1"), answered.awaitingReport)
    }

    @Test
    fun `这一批回答过的方向缺少交代判为错误且台账不变`() {
        val answered = three.recordAnswer(q("d1"), Answer.Picked("a"), null).recordAnswer(q("d2"), Answer.Picked("b"), null)
        val out = answered.merge(response(ask("d3"), answered = listOf(decided("d1"))), emptySet())
        assertTrue(out.errors.any { it.contains("answered must report direction d2") })
        assertFalse(out.invalidatedByUser)
        assertEquals(answered, out.ledger)
    }

    @Test
    fun `交代了不在待交代之列的方向判为错误`() {
        val answered = three.recordAnswer(q("d1"), Answer.Picked("a"), null)
        val out = answered.merge(response(ask("d3"), answered = listOf(decided("d1"), decided("d2"))), emptySet())
        assertTrue(out.errors.any { it.contains("d2") && it.contains("did not answer") })
        assertEquals(answered, out.ledger)
    }

    @Test
    fun `调查期间用户跳过了待交代的方向时模型对它的交代被忽略`() {
        val answered = three.recordAnswer(q("d1"), Answer.Picked("a"), null).skip("d1")
        val out = answered.merge(response(ask("d2"), answered = listOf(decided("d1"))), changedByUser = setOf("d1"))
        assertTrue(out.errors.isEmpty())
        assertEquals(DirectionStatus.DEFAULTED, out.ledger.direction("d1")!!.status)
    }

    @Test
    fun `同一批里两题针对同一方向判为错误`() {
        val out = three.merge(response(ask("d1", "d2", "d1")), emptySet())
        assertTrue(out.errors.any { it.contains("different direction") })
        assertNull(out.next)
        assertEquals(three, out.ledger)
    }

    @Test
    fun `同一批里临时 ID 与它的稳定 ID 指向同一方向也判为重复`() {
        val out = three.merge(response(ask("new-1", "d4"), added = listOf(NewDirection("new-1", "x", "w", "s", "f"))), emptySet())
        assertTrue(out.errors.any { it.contains("different direction") })
    }

    @Test
    fun `先问这个的方向不是第一题时判为错误`() {
        val out = three.pin("d2").merge(response(ask("d1", "d2")), emptySet())
        assertTrue(out.errors.any { it.contains("d2") && it.contains("first question") })
        assertFalse(out.invalidatedByUser)
        assertEquals("d2", out.ledger.pinned)
    }

    @Test
    fun `先问这个的方向是第一题时通过且指定解除`() {
        val out = three.pin("d2").merge(response(ask("d2", "d1")), emptySet())
        assertTrue(out.errors.isEmpty())
        assertNull(out.ledger.pinned)
    }

    @Test
    fun `调查期间被用户跳过的方向出现在批中时标为用户造成`() {
        val ledger = three.skip("d3")
        val out = ledger.merge(response(ask("d1", "d3")), changedByUser = setOf("d3"))
        assertTrue(out.invalidatedByUser)
        assertEquals(ledger, out.ledger)
    }

    @Test
    fun `批中有用户造成的问题也有模型自己的错时不算用户造成`() {
        val ledger = three.skip("d3")
        val out = ledger.merge(response(ask("d3", "d9")), changedByUser = setOf("d3"))
        assertFalse(out.invalidatedByUser)
        assertTrue(out.errors.any { it.contains("d9") })
    }

    @Test
    fun `离开时这一批回答过的方向都保持当前而没回答的回到待确认`() {
        val asked = three.merge(response(ask("d1", "d2", "d3")), emptySet()).ledger
        val answered = asked.recordAnswer(q("d1"), Answer.Picked("a"), null).recordAnswer(q("d2"), Answer.Picked("a"), null)
        val released = answered.releaseUnanswered()
        assertEquals(DirectionStatus.ACTIVE, released.direction("d1")!!.status)
        assertEquals(DirectionStatus.ACTIVE, released.direction("d2")!!.status)
        assertEquals(DirectionStatus.OPEN, released.direction("d3")!!.status)
    }

    @Test
    fun `模型去掉这一批回答过的方向被忽略`() {
        val answered = three.recordAnswer(q("d1"), Answer.Picked("a"), null).recordAnswer(q("d2"), Answer.Picked("a"), null)
        val out = answered.merge(
            response(ask("d3"), dropped = listOf(DroppedDirection("d2", "不需要了")), answered = listOf(decided("d1"), decided("d2"))),
            emptySet(),
        )
        assertTrue(out.errors.isEmpty())
        assertEquals(DirectionStatus.DECIDED, out.ledger.direction("d2")!!.status)
        assertEquals("conclusion-a", out.ledger.direction("d2")!!.conclusion)
    }

    @Test
    fun `confirmedConclusions 包含这一批回答过的每个方向`() {
        val asked = three.merge(response(ask("d1", "d2", "d3")), emptySet()).ledger
        val answered = asked.recordAnswer(q("d1"), Answer.OwnWords("ONE-MARK"), null).recordAnswer(q("d2"), Answer.OwnWords("TWO-MARK"), null)
        val confirmed = answered.confirmedConclusions()
        assertTrue(confirmed.contains("ONE-MARK"))
        assertTrue(confirmed.contains("TWO-MARK"))
        assertEquals("没问到的第三题不算", listOf("ONE-MARK", "TWO-MARK"), confirmed)
    }
}
