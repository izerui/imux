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
        assertEquals(
            "待整理的讨论记录，尚未形成最终任务。\n\n原始目标：\n目标\n\n" +
                "模型提问与用户回答：\n- H（Agent 看法：可能是等待的问题。\n你认同吗？） → 不认同。\n问题是打断。",
            composeFallback(record),
        )
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
        assertEquals(
            "待整理的讨论记录，尚未形成最终任务。\n\n原始目标：\n需求\n\n" +
                "模型提问与用户回答：\n- H（Q） → label-b（description-b）",
            composeFallback(record),
        )
        assertEquals("没有回答时也说明材料性质", "待整理的讨论记录，尚未形成最终任务。\n\n原始目标：\n需求", composeFallback(ClarificationRecord("需求")))
    }

    @Test
    fun `重复回答保留问题来源且默认处理不冒充主动选择`() {
        val record = ClarificationRecord(
            "目标",
            listOf(
                answerRecord(q().copy(text = "操作员看什么？"), Answer.OwnWords("仅查看")),
                answerRecord(q().copy(text = "管理员看什么？"), Answer.OwnWords("仅查看")),
                answerRecord(q().copy(fallback = "保留现状"), Answer.Skipped),
            ),
        )
        val text = composeFallback(record)
        assertTrue(text.contains("操作员看什么？） → 其他：仅查看"))
        assertTrue(text.contains("管理员看什么？） → 其他：仅查看"))
        assertTrue(text.contains("没有回答，按默认处理：保留现状"))
    }
}
