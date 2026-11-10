package com.github.izerui.imux.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 一题一题来：每题排成和电脑上一样的编号列表；答案译成「题目 → 选项原文」，或原样作为这道题的回答。 */
class AskedQuestionsTest {
    private val fruit = AskedQuestion("选哪个？", false, listOf(AskedOption("苹果", "红"), AskedOption("香蕉", "黄"), AskedOption("葡萄", null)))
    private val toppings = AskedQuestion("要哪些配料？", true, listOf(AskedOption("糖", "甜"), AskedOption("冰", "凉")))

    @Test
    fun `单选题回编号（字母也认）译成选项原文`() {
        for (reply in listOf("2", "B", "b", " 选 2 ")) assertEquals(reply, "选哪个？ → 香蕉", AskedQuestions.answer(fruit, reply))
    }

    @Test
    fun `其他说法原样作为这道题的回答`() {
        for (reply in listOf("第二个吧", "1 3", "4", "香蕉")) assertEquals(reply, "选哪个？ → $reply", AskedQuestions.answer(fruit, reply))
    }

    @Test
    fun `多选题回几个编号，选项原文用顿号连起来`() {
        assertEquals("要哪些配料？ → 糖、冰", AskedQuestions.answer(toppings, "1 2"))
        assertEquals("要哪些配料？ → 冰", AskedQuestions.answer(toppings, "2"))
    }

    @Test
    fun `手机输入法的写法也认成选择：全角、结尾标点、「和」「and」`() {
        for (reply in listOf("２", "2。", "Ｂ！", "选b.")) assertEquals(reply, "选哪个？ → 香蕉", AskedQuestions.answer(fruit, reply))
        for (reply in listOf("1 和 2", "a and b", "１、２")) assertEquals(reply, "要哪些配料？ → 糖、冰", AskedQuestions.answer(toppings, reply))
        assertEquals("词里带 and 不是选择", "选哪个？ → sandwich", AskedQuestions.answer(fruit, "sandwich"))
    }

    @Test
    fun `空白回复不算回答`() {
        assertNull(AskedQuestions.answer(fruit, "  \n "))
    }

    @Test
    fun `多选题连着写的编号按单个数字拆开；单选题不拆`() {
        val three = AskedQuestion("哪些正常？", true, listOf(AskedOption("题号", null), AskedOption("编号", null), AskedOption("翻页", null)))
        assertEquals("哪些正常？ → 题号、编号、翻页", AskedQuestions.answer(three, "123"))
        assertEquals("哪些正常？ → 题号、翻页", AskedQuestions.answer(three, "13"))
        assertEquals("有重复或超出选项就不拆", "哪些正常？ → 113", AskedQuestions.answer(three, "113"))
        assertEquals("哪些正常？ → 14", AskedQuestions.answer(three, "14"))
        assertEquals("单选题的 12 不拆", "选哪个？ → 12", AskedQuestions.answer(fruit, "12"))
    }

    @Test
    fun `翻到下一题只按右键，不按编号`() {
        assertEquals(listOf(TerminalKeys.RIGHT), AskedQuestions.ADVANCE_KEYS)
    }

    @Test
    fun `多行的原话第二行起缩进，拼成一条消息后分得清属于哪题`() {
        assertEquals("选哪个？ → 都行\n  但别太酸", AskedQuestions.answer(fruit, "都行\n但别太酸\n"))
    }

    @Test
    fun `列表：选项用和电脑上一样的数字编号，说明接在后面，多选题标注`() {
        assertEquals("选哪个？  \n**1.** 苹果 · 红  \n**2.** 香蕉 · 黄  \n**3.** 葡萄", AskedQuestions.render(fruit, multiSelectMark = "（可多选）"))
        assertEquals("要哪些配料？（可多选）  \n**1.** 糖 · 甜  \n**2.** 冰 · 凉", AskedQuestions.render(toppings, multiSelectMark = "（可多选）"))
    }

    @Test
    fun `编号加粗、硬换行，正文里的星号转义，题目开头像标题时转义`() {
        val question = AskedQuestion("# 用 *哪个*？", false, listOf(AskedOption("a*b", null)))
        assertEquals("\\# 用 \\*哪个\\*？  \n**1.** a\\*b", AskedQuestions.render(question, multiSelectMark = ""))
    }
}
