package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationLedgerTest {
    private fun dir(id: String, status: DirectionStatus = DirectionStatus.OPEN) = Direction(id, "title-$id", "why", "src", "fallback-$id", status)

    private fun option(id: String) = ChoiceOption(id, "label-$id", "description-$id", recommended = id == "a")

    private fun q(directionId: String, multiSelect: Boolean = false) =
        Question(directionId, "H-$directionId", "Q-$directionId", multiSelect, listOf(option("a"), option("b"), option("c")))

    private fun ask(vararg directionIds: String) = Next.Ask(directionIds.map { q(it) })

    private fun response(
        next: Next,
        added: List<NewDirection> = emptyList(),
        dropped: List<String> = emptyList(),
        answered: List<AnsweredReport> = emptyList(),
        notes: String = "",
    ) = RoundResponse(null, added, dropped, answered, notes, next)

    private val base = Ledger("需求", null, directions = listOf(dir("d1"), dir("d2")), nextDirectionNumber = 3)

    private fun decided(id: String) = AnsweredReport(id, decided = true, conclusion = "", openPoints = emptyList())

    // ---- 合并 ----

    @Test
    fun `首轮新增两个方向时问题关联到第二个`() {
        val out = Ledger("需求", null).merge(
            response(ask("new-2"), added = listOf(NewDirection("new-1", "第一", "w", "s", "f"), NewDirection("new-2", "第二", "w", "s", "f"))),
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
        val dup = base.merge(response(ask("new-1"), added = listOf(NewDirection("new-1", "x", "w", "s", "f"), NewDirection("new-1", "y", "w", "s", "f"))))
        assertTrue(dup.errors.any { it.contains("duplicate") })
        val prefix = base.merge(response(ask("d1"), added = listOf(NewDirection("tmp-1", "x", "w", "s", "f"))))
        assertTrue(prefix.errors.any { it.contains("new-") })
        val missing = base.merge(response(ask("d9")))
        assertTrue(missing.errors.any { it.contains("d9") })
    }

    @Test
    fun `出错时台账保持原样`() {
        val out = base.merge(response(ask("d9"), added = listOf(NewDirection("new-1", "x", "w", "s", "f"))))
        assertEquals(base, out.ledger)
        assertNull(out.next)
    }

    @Test
    fun `问题针对按默认处理的方向时判为错误`() {
        val ledger = base.recordAnswer(q("d1"), Answer.Skipped)
        val out = ledger.merge(response(ask("d1")))
        assertTrue(out.errors.any { it.contains("DEFAULTED") })
        assertEquals(DirectionStatus.DEFAULTED, out.ledger.direction("d1")!!.status)
    }

    @Test
    fun `模型去掉已定的、按默认的、刚回答的方向都被忽略，其余照去`() {
        val ledger =
            base.copy(directions = listOf(dir("d1", DirectionStatus.DECIDED), dir("d2", DirectionStatus.DEFAULTED), dir("d3"), dir("d4"), dir("d5")), nextDirectionNumber = 6)
                .recordAnswer(q("d3").copy(directionId = "d3"), Answer.Picked(listOf("a")))
        val out = ledger.merge(response(ask("d5"), dropped = listOf("d1", "d2", "d3", "d4"), answered = listOf(decided("d3"))))
        assertTrue(out.errors.toString(), out.errors.isEmpty())
        assertEquals("已定的不动", DirectionStatus.DECIDED, out.ledger.direction("d1")!!.status)
        assertEquals("按默认的不动", DirectionStatus.DEFAULTED, out.ledger.direction("d2")!!.status)
        assertEquals("刚回答的按交代定下", DirectionStatus.DECIDED, out.ledger.direction("d3")!!.status)
        assertEquals("刚回答的结论取用户的回答", "description-a", out.ledger.direction("d3")!!.conclusion)
        assertEquals("其余照去", DirectionStatus.DROPPED, out.ledger.direction("d4")!!.status)
    }

    @Test
    fun `已定方向的结论取用户的回答而不是模型的转述`() {
        val ledger = base.recordAnswer(q("d1"), Answer.Picked(listOf("a")))
        val out = ledger.merge(response(ask("d2"), answered = listOf(AnsweredReport("d1", decided = true, conclusion = "模型改写的结论", openPoints = emptyList()))))
        assertTrue(out.errors.isEmpty())
        assertEquals(DirectionStatus.DECIDED, out.ledger.direction("d1")!!.status)
        assertEquals("description-a", out.ledger.direction("d1")!!.conclusion)
    }

    @Test
    fun `未定的方向回到待确认并记下剩余疑点`() {
        val ledger = base.recordAnswer(q("d1"), Answer.Picked(listOf("a")))
        val out = ledger.merge(response(ask("d2"), answered = listOf(AnsweredReport("d1", decided = false, conclusion = "部分", openPoints = listOf("疑点")))))
        assertEquals(DirectionStatus.OPEN, out.ledger.direction("d1")!!.status)
        assertEquals(listOf("疑点"), out.ledger.direction("d1")!!.openPoints)
    }

    @Test
    fun `上一批回答过的方向缺少交代判为错误且台账不变`() {
        val ledger = base.recordAnswer(q("d1"), Answer.Picked(listOf("a")))
        val out = ledger.merge(response(Next.Done("REQ")))
        assertTrue(out.errors.any { it.contains("answered must report direction d1") })
        assertEquals(ledger, out.ledger)
    }

    @Test
    fun `交代了不在待交代之列的方向判为错误`() {
        val out = base.merge(response(ask("d2"), answered = listOf(decided("d1"))))
        assertTrue(out.errors.any { it.contains("did not answer") })
    }

    @Test
    fun `调查笔记超长判为错误`() {
        val out = base.merge(response(ask("d1"), notes = "x".repeat(MAX_NOTES_CHARS + 1)))
        assertTrue(out.errors.any { it.contains("notes") })
    }

    @Test
    fun `一批问题合并后各方向都处于当前且待交代清空`() {
        val ledger = base.copy(directions = base.directions + dir("d3"), nextDirectionNumber = 4).recordAnswer(q("d1"), Answer.Picked(listOf("a")))
        val out = ledger.merge(response(ask("d2", "d3"), answered = listOf(decided("d1"))))
        assertTrue(out.errors.isEmpty())
        assertEquals(DirectionStatus.ACTIVE, out.ledger.direction("d2")!!.status)
        assertEquals(DirectionStatus.ACTIVE, out.ledger.direction("d3")!!.status)
        assertTrue(out.ledger.awaitingReport.isEmpty())
    }

    @Test
    fun `同一批里两题针对同一方向判为错误`() {
        assertTrue(base.merge(response(ask("d1", "d1"))).errors.any { it.contains("different direction") })
    }

    @Test
    fun `同一批里临时 ID 与它的稳定 ID 指向同一方向也判为重复`() {
        val out = base.merge(response(ask("new-1", "d3"), added = listOf(NewDirection("new-1", "x", "w", "s", "f"))))
        assertTrue(out.errors.any { it.contains("different direction") })
    }

    @Test
    fun `模型返回 done 时直接采用`() {
        val out = base.merge(response(Next.Done("ADJUSTED")))
        assertEquals(MergedNext.Done("ADJUSTED"), out.next)
    }

    // ---- 记下回答 ----

    @Test
    fun `单选记下选项的说明`() {
        val ledger = base.recordAnswer(q("d1"), Answer.Picked(listOf("b")))
        assertEquals("description-b", ledger.history.last().recorded)
        assertEquals(DirectionStatus.ACTIVE, ledger.direction("d1")!!.status)
        assertEquals(listOf("d1"), ledger.awaitingReport)
    }

    @Test
    fun `多选按选项顺序以分号连接各项说明并接上其他原话`() {
        val ledger = base.recordAnswer(q("d1", multiSelect = true), Answer.Picked(listOf("c", "a"), other = " 还有标题 "))
        assertEquals("description-a；description-c；还有标题", ledger.history.last().recorded)
        assertEquals(listOf("d1"), ledger.awaitingReport)
    }

    @Test
    fun `原话照录`() {
        val ledger = base.recordAnswer(q("d1"), Answer.OwnWords("  我的原话  "))
        assertEquals("我的原话", ledger.history.last().recorded)
    }

    @Test
    fun `不回答时记下默认处理、方向按默认且不要求交代`() {
        val ledger = base.recordAnswer(q("d1"), Answer.Skipped)
        assertEquals("fallback-d1", ledger.history.last().recorded)
        assertEquals(DirectionStatus.DEFAULTED, ledger.direction("d1")!!.status)
        assertEquals("fallback-d1", ledger.direction("d1")!!.conclusion)
        assertTrue(ledger.awaitingReport.isEmpty())
    }

    @Test
    fun `对不上题目的回答按不回答处理`() {
        val unknown = base.recordAnswer(q("d1"), Answer.Picked(listOf("z")))
        assertEquals("选项不存在", Answer.Skipped, unknown.history.last().answer)
        val two = base.recordAnswer(q("d1"), Answer.Picked(listOf("a", "b")))
        assertEquals("单选选了两个", Answer.Skipped, two.history.last().answer)
        val blank = base.recordAnswer(q("d1"), Answer.OwnWords("   "))
        assertEquals("原话为空", Answer.Skipped, blank.history.last().answer)
        assertEquals("fallback-d1", blank.history.last().recorded)
    }

    @Test
    fun `离开时这一批回答过的方向保持当前而没回答的回到待确认`() {
        val asked = base.copy(directions = listOf(dir("d1", DirectionStatus.ACTIVE), dir("d2", DirectionStatus.ACTIVE)))
        val ledger = asked.recordAnswer(q("d1"), Answer.Picked(listOf("a"))).releaseUnanswered()
        assertEquals(DirectionStatus.ACTIVE, ledger.direction("d1")!!.status)
        assertEquals(DirectionStatus.OPEN, ledger.direction("d2")!!.status)
    }

    // ---- 已确认 ----

    @Test
    fun `confirmedConclusions 包含已定与按默认的结论`() {
        val ledger = base.copy(directions = listOf(dir("d1", DirectionStatus.DECIDED).copy(conclusion = "C1"), dir("d2", DirectionStatus.DEFAULTED).copy(conclusion = "C2")))
        assertEquals(listOf("C1", "C2"), ledger.confirmedConclusions())
    }

    @Test
    fun `confirmedConclusions 包含刚提交、还没交代的回答`() {
        val ledger = base.recordAnswer(q("d1"), Answer.Picked(listOf("a")))
        assertEquals(listOf("description-a"), ledger.confirmedConclusions())
    }

    @Test
    fun `confirmedConclusions 不包含从未回答的方向与去掉的方向上的旧回答`() {
        val answered = base.recordAnswer(q("d1"), Answer.Picked(listOf("a")))
        val dropped = answered.replace(answered.direction("d1")!!.copy(status = DirectionStatus.DROPPED))
        assertTrue(dropped.confirmedConclusions().isEmpty())
    }

    @Test
    fun `confirmedConclusions 对已定方向同时有记录时不重复`() {
        val answered = base.recordAnswer(q("d1"), Answer.Picked(listOf("a")))
        val out = answered.merge(response(ask("d2"), answered = listOf(decided("d1")))).ledger
        assertEquals(listOf("description-a"), out.confirmedConclusions())
    }

    @Test
    fun `兜底结果包含原需求与已确认的结论但不含未定的`() {
        val ledger =
            base.copy(directions = listOf(dir("d1", DirectionStatus.DECIDED).copy(conclusion = "C1"), dir("d2", DirectionStatus.OPEN).copy(conclusion = "PARTIAL")))
        val text = composeFallback(ledger)
        assertTrue(text.startsWith("需求"))
        assertTrue(text.contains("C1"))
        assertFalse(text.contains("PARTIAL"))
    }
}
