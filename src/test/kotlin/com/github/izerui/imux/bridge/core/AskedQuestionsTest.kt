package com.github.izerui.imux.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 提问不在框里按键：调用方先按 Esc 关掉框，再把 [AskedQuestions.answer] 的文字作为消息发给 Claude。 */
class AskedQuestionsTest {
    private val fruit = AskedQuestion("选哪个？", false, listOf(AskedOption("苹果", "红"), AskedOption("香蕉", "黄"), AskedOption("葡萄", null)))
    private val toppings = AskedQuestion("要哪些配料？", true, listOf(AskedOption("糖", "甜"), AskedOption("冰", "凉")))

    @Test
    fun `单选题回字母或编号译成「题目 → 选项原文」`() {
        for (reply in listOf("B", "b", "2", " 选 B ")) assertEquals(reply, "选哪个？ → 香蕉", AskedQuestions.answer(listOf(fruit), reply))
    }

    @Test
    fun `其他说法原样发出，由 Claude 结合提问理解`() {
        for (reply in listOf("第二个吧", "A C", "4", "香蕉")) assertEquals(reply, reply, AskedQuestions.answer(listOf(fruit), reply))
    }

    @Test
    fun `多选题回几个编号，选项原文用顿号连起来`() {
        assertEquals("要哪些配料？ → 糖、冰", AskedQuestions.answer(listOf(toppings), "A B"))
        assertEquals("要哪些配料？ → 冰", AskedQuestions.answer(listOf(toppings), "2"))
    }

    @Test
    fun `手机输入法的写法也认成选择：全角、结尾标点、「和」「and」`() {
        for (reply in listOf("Ｂ", "B。", "２！", "选b.")) assertEquals(reply, "选哪个？ → 香蕉", AskedQuestions.answer(listOf(fruit), reply))
        for (reply in listOf("A 和 B", "a and b", "Ａ、Ｂ")) assertEquals(reply, "要哪些配料？ → 糖、冰", AskedQuestions.answer(listOf(toppings), reply))
        assertEquals("词里带 and 不是选择", "sandwich", AskedQuestions.answer(listOf(fruit), "sandwich"))
    }

    @Test
    fun `多题时每行答一题，对得上的译成选项原文，对不上的那行原样`() {
        assertEquals("选哪个？ → 香蕉\n要哪些配料？ → 只要冰，少放", AskedQuestions.answer(listOf(fruit, toppings), "B\n\n只要冰，少放\n"))
        assertEquals("选哪个？ → 苹果\n要哪些配料？ → 糖、冰", AskedQuestions.answer(listOf(fruit, toppings), "a\nA B"))
    }

    @Test
    fun `多题时行数对不上，整段原样发出`() {
        assertEquals("第一题选香蕉，配料只要冰", AskedQuestions.answer(listOf(fruit, toppings), "第一题选香蕉，配料只要冰"))
    }

    @Test
    fun `空白回复不发`() {
        assertNull(AskedQuestions.answer(listOf(fruit), "  \n "))
    }

    @Test
    fun `列表：选项用字母编号，说明接在后面，多选题标注，多题时题目编号`() {
        assertEquals("选哪个？\nA. 苹果 · 红\nB. 香蕉 · 黄\nC. 葡萄", AskedQuestions.render(listOf(fruit), markdown = false, multiSelectMark = "（可多选）"))
        assertEquals(
            "1. 选哪个？\nA. 苹果 · 红\nB. 香蕉 · 黄\nC. 葡萄\n\n2. 要哪些配料？（可多选）\nA. 糖 · 甜\nB. 冰 · 凉",
            AskedQuestions.render(listOf(fruit, toppings), markdown = false, multiSelectMark = "（可多选）"),
        )
    }

    @Test
    fun `Markdown 渠道编号加粗、硬换行，正文里的星号转义，题目开头像标题时转义`() {
        val question = AskedQuestion("# 用 *哪个*？", false, listOf(AskedOption("a*b", null)))
        assertEquals("\\# 用 \\*哪个\\*？  \n**A.** a\\*b", AskedQuestions.render(listOf(question), markdown = true, multiSelectMark = ""))
    }
}
