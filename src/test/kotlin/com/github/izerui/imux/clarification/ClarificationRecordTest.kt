package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationRecordTest {
    private fun option(id: String) = ChoiceOption(id, "label-$id", "description-$id", recommended = id == "a")

    private fun q(multiSelect: Boolean = false) =
        Question("q1", "H", "Q", multiSelect, listOf(option("a"), option("b"), option("c")), fallback = "fallback-q1")

    @Test
    fun `单选记下选项的说明`() {
        val record = answerRecord(q(), Answer.Picked(listOf("b")))
        assertEquals("description-b", record.recorded)
        assertEquals(Answer.Picked(listOf("b")), record.answer)
    }

    @Test
    fun `多选按选项顺序以分号连接各项说明并接上其他原话`() {
        assertEquals("description-a；description-c；还有标题", answerRecord(q(multiSelect = true), Answer.Picked(listOf("c", "a"), other = " 还有标题 ")).recorded)
    }

    @Test
    fun `原话照录`() {
        assertEquals("我的原话", answerRecord(q(), Answer.OwnWords("  我的原话  ")).recorded)
    }

    @Test
    fun `开放回答保留段落且空白只采用披露的处理`() {
        val open = q().copy(kind = QuestionKind.OPEN, options = emptyList(), fallback = "暂不推断")
        assertEquals("第一段\n第二段", answerRecord(open, Answer.OwnWords(" 第一段\n第二段 ")).recorded)
        val blank = answerRecord(open, Answer.OwnWords("\n "))
        assertEquals(Answer.Skipped, blank.answer)
        assertEquals("暂不推断", blank.recorded)
    }

    @Test
    fun `开放题兜底保留问题与看法避免回答脱离语境`() {
        val open = q().copy(kind = QuestionKind.OPEN, options = emptyList(), context = "可能是等待的问题。", text = "你认同吗？")
        val record = ClarificationRecord("目标", listOf(answerRecord(open, Answer.OwnWords("不认同。\n问题是打断。"))))
        assertEquals("目标\n\n- 可能是等待的问题。\n  你认同吗？\n  不认同。\n  问题是打断。", composeFallback(record))
    }

    @Test
    fun `不回答时记下这一题的默认处理`() {
        val record = answerRecord(q(), Answer.Skipped)
        assertEquals(Answer.Skipped, record.answer)
        assertEquals("fallback-q1", record.recorded)
    }

    @Test
    fun `对不上题目的回答按不回答处理`() {
        assertEquals("选项不存在", Answer.Skipped, answerRecord(q(), Answer.Picked(listOf("z"))).answer)
        assertEquals("单选选了两个", Answer.Skipped, answerRecord(q(), Answer.Picked(listOf("a", "b"))).answer)
        val blank = answerRecord(q(), Answer.OwnWords("   "))
        assertEquals("原话为空", Answer.Skipped, blank.answer)
        assertEquals("fallback-q1", blank.recorded)
    }

    @Test
    fun `已确认按提交顺序列出回答与默认处理并去重`() {
        val record =
            ClarificationRecord(
                "需求",
                listOf(
                    answerRecord(q(), Answer.Picked(listOf("a"))),
                    answerRecord(q(), Answer.Skipped),
                    answerRecord(q(), Answer.Picked(listOf("a"))),
                ),
            )
        assertEquals(listOf("description-a", "fallback-q1"), record.confirmed())
    }

    @Test
    fun `默认处理为空的不回答不算已确认`() {
        val record = ClarificationRecord("需求", listOf(answerRecord(q().copy(fallback = ""), Answer.Skipped)))
        assertTrue(record.confirmed().isEmpty())
    }

    @Test
    fun `兜底结果是原需求后接已确认的回答`() {
        val record = ClarificationRecord("  需求  ", listOf(answerRecord(q(), Answer.Picked(listOf("b")))))
        assertEquals("需求\n\n- description-b", composeFallback(record))
        assertEquals("没有回答时只有原需求", "需求", composeFallback(ClarificationRecord("需求")))
    }
}
