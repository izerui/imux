package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationPromptTest {
    private val question =
        Question("q1", "恢复范围", "断线后恢复哪些内容？", true, listOf(ChoiceOption("a", "只恢复连接", "DA", true), ChoiceOption("b", "连同滚动缓冲", "DB", false)), "只恢复连接")
    private val fresh = ClarificationRecord("给终端增加断线重连", "User: 之前的对话")
    private val answered = fresh.copy(history = listOf(answerRecord(question, Answer.Picked(listOf("a", "b"), "OWN")), answerRecord(question, Answer.Skipped)))

    private fun rawResource(name: String): String =
        ClarificationPromptTest::class.java.getResourceAsStream("/clarification/$name")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `去掉 front matter 只留正文`() {
        assertEquals("# 标题\n正文", stripFrontMatter("---\nname: x\n---\n# 标题\n正文"))
        assertEquals("没有头", stripFrontMatter("没有头"))
    }

    @Test
    fun `中英两套指南各自读到对应资源的正文`() {
        val zh = loadGuidance(isChinese = true)
        val en = loadGuidance(isChinese = false)
        assertEquals(stripFrontMatter(rawResource("requirement-clarifier.zh.md")).trim(), zh)
        assertEquals(stripFrontMatter(rawResource("requirement-clarifier.en.md")).trim(), en)
        assertFalse(zh.startsWith("---"))
        assertFalse(en.startsWith("---"))
    }

    @Test
    fun `中英两套指南都不再要求方向清单、复述理解与逐个交代`() {
        for ((name, guidance) in listOf("zh" to loadGuidance(true), "en" to loadGuidance(false))) {
            assertTrue("$name：说明是同一段对话", guidance.contains(if (name == "zh") "持续的对话" else "continuing conversation"))
            for (gone in listOf("首轮列出所有", "复述你的理解", "提议去掉", "In the first round list", "Play back your understanding", "propose dropping")) {
                assertFalse("$name：不再有 $gone", guidance.contains(gone))
            }
        }
    }

    @Test
    fun `开场带指南、规则、需求、背景与输出格式`() {
        val opening = openingMessage(fresh, isChinese = true, guidance = "指南正文", finishNow = false)
        assertTrue(opening.startsWith("指南正文"))
        assertTrue("需求", opening.contains("给终端增加断线重连"))
        assertTrue("背景", opening.contains("User: 之前的对话"))
        assertTrue("规则", opening.contains("1～$MAX_QUESTIONS_PER_ROUND"))
        assertTrue("格式", opening.contains("\"type\": \"questions\"") && opening.contains("\"fallback\""))
        assertTrue("首次开场要求先调查", opening.contains("先调查项目"))
        assertFalse("首次开场没有已给出的回答", opening.contains("用户已经给出的回答"))
        assertTrue(opening.trimEnd().endsWith(JSON_ONLY_ZH))
    }

    @Test
    fun `对话中断后的开场带上已确认的问答并接着做`() {
        val opening = openingMessage(answered, isChinese = false, guidance = "g", finishNow = false)
        assertTrue(opening.contains("Answers the User Has Already Given"))
        assertTrue("选中的选项与原话", opening.contains("只恢复连接 (DA); 连同滚动缓冲 (DB); Other: OWN"))
        assertTrue("不回答的写明默认处理", opening.contains("Not answered; use the default: 只恢复连接"))
        assertTrue("接着问下一批", opening.contains("ask the next batch"))
        assertTrue(opening.trimEnd().endsWith(JSON_ONLY_EN))
    }

    @Test
    fun `要求直接给出结果的开场只要 done`() {
        val opening = openingMessage(answered, isChinese = false, guidance = "g", finishNow = true)
        assertTrue(opening.contains("asked for the result now"))
        assertFalse(opening.contains("ask the next batch"))
    }

    @Test
    fun `一批的回答只有短标签到回答与一句格式提醒，中英两版都是`() {
        val records = listOf(answerRecord(question, Answer.OwnWords("我的想法")), answerRecord(question, Answer.Skipped))
        val zh = answersMessage(records, isChinese = true)
        assertEquals(
            "用户对这一批问题的回答：\n\n" +
                "- 恢复范围（断线后恢复哪些内容？） → 其他：我的想法\n" +
                "- 恢复范围（断线后恢复哪些内容？） → 没有回答，按默认处理：只恢复连接\n\n" +
                "带着这些回答继续：给出下一批问题，或在没有需要用户拍板的方向时给出调整后的需求。 回复仍只用约定的 JSON：{\"type\": \"questions\", ...} 或 {\"type\": \"done\", ...}。",
            zh,
        )
        val en = answersMessage(records, isChinese = false)
        assertTrue("英文：原话", en.contains("- 恢复范围 (断线后恢复哪些内容？) → Other: 我的想法"))
        assertTrue("英文：默认处理", en.contains("→ Not answered; use the default: 只恢复连接"))
        assertTrue("英文：格式提醒", en.endsWith("Reply with the agreed JSON only: {\"type\": \"questions\", ...} or {\"type\": \"done\", ...}."))
    }

    @Test
    fun `够了的消息附上已填写的回答，没有时只要求结果`() {
        val with = finishMessage(listOf(answerRecord(question, Answer.Picked(listOf("b")))), isChinese = true)
        assertTrue(with.contains("连同滚动缓冲（DB）"))
        assertTrue(with.contains("用户要求现在生成结果"))
        val without = finishMessage(emptyList(), isChinese = true)
        assertFalse(without.contains("已经填写的回答"))
        assertTrue(without.startsWith("用户要求现在生成结果"))
    }

    @Test
    fun `纠错消息列出错误并重申格式`() {
        val message = correctionMessage(listOf("questions[0].options must have 2-4 items"), isChinese = false)
        assertTrue(message.contains("- questions[0].options must have 2-4 items"))
        assertTrue(message.contains("\"type\": \"questions\""))
        assertTrue(message.trimEnd().endsWith(JSON_ONLY_EN))
    }

    @Test
    fun `开场的中英提问规则都写明每批一到四题、短标签、默认处理、说明即记录、不给其他`() {
        for (chinese in listOf(true, false)) {
            val prompt = openingMessage(fresh, chinese, "g", finishNow = false)
            val name = if (chinese) "zh" else "en"
            assertTrue("$name：1～4 题", prompt.contains(if (chinese) "1～$MAX_QUESTIONS_PER_ROUND" else "1-$MAX_QUESTIONS_PER_ROUND"))
            assertTrue("$name：header 字数", prompt.contains("header") && prompt.contains("$HEADER_CHARS"))
            assertTrue("$name：fallback", prompt.contains("fallback"))
            assertTrue("$name：description", prompt.contains("description"))
            assertTrue("$name：multiSelect", prompt.contains("multiSelect"))
            assertTrue("$name：不给其他", prompt.contains(if (chinese) "不要给\"其他\"选项" else "Do not include an \"Other\" option"))
        }
    }
}
