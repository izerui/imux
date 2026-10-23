package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationPromptTest {
    private val question =
        Question("q1", "恢复范围", "断线后恢复哪些内容？", true, listOf(ChoiceOption("a", "只恢复连接", "DA", true), ChoiceOption("b", "连同滚动缓冲", "DB", false)), "只恢复连接")
    private val fresh = ClarificationRecord("给终端增加断线重连")
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
    fun `中英文开场将只读边界与核心指南分开`() {
        for (chinese in listOf(true, false)) {
            val guidance = loadGuidance(chinese)
            val opening = openingMessage(fresh, chinese, guidance)
            val boundary = if (chinese) "不修改文件、不运行构建或测试" else "do not modify files, run builds or tests"
            val boundaryHeader = if (chinese) "## 运行边界" else "## Operating Boundaries"
            assertFalse("$chinese：核心指南不承担运行限制", guidance.contains(boundary))
            assertTrue("$chinese：开场仍有只读限制", opening.contains(boundary))
            assertTrue("$chinese：边界在核心指南之后", opening.indexOf(boundaryHeader) > guidance.lastIndex)
            assertTrue(
                "$chinese：三种形式同等可用",
                opening.contains(if (chinese) "开放、单选、多选同等可用" else "Open, single-choice and multiple-choice forms are equally available"),
            )
        }
    }

    @Test
    fun `开场带指南、规则、需求与输出格式`() {
        val opening = openingMessage(fresh, isChinese = true, guidance = "指南正文")
        assertTrue(opening.startsWith("指南正文"))
        assertTrue("需求", opening.contains("给终端增加断线重连"))
        assertTrue("规则", opening.contains("1～$MAX_QUESTIONS_PER_ROUND"))
        assertTrue("格式", opening.contains("\"type\": \"questions\"") && opening.contains("\"fallback\""))
        assertTrue("不要求先提问", opening.contains("不要求先提问或产出实施方案"))
        assertFalse("没有额外的开场思考流程", opening.contains("## 现在"))
        assertFalse("首次开场没有已给出的回答", opening.contains("用户已经给出的回答"))
        assertTrue(opening.trimEnd().endsWith(JSON_ONLY_ZH))
    }

    @Test
    fun `对话中断后的开场带上已确认的问答并接着做`() {
        val opening = openingMessage(answered, isChinese = false, guidance = "g")
        assertTrue(opening.contains("Answers the User Has Already Given"))
        assertTrue("选中的选项与原话", opening.contains("只恢复连接 (DA); 连同滚动缓冲 (DB); Other: OWN"))
        assertTrue("不回答的写明默认处理", opening.contains("Not answered; use the default: 只恢复连接"))
        assertTrue("恢复时说明历史顺序", opening.contains("in chronological order"))
        assertTrue("缺失调查不冒充已知", opening.contains("Missing investigation findings are not established facts"))
        assertTrue(opening.trimEnd().endsWith(JSON_ONLY_EN))
    }

    @Test
    fun `一批的回答只有短标签到回答与一句格式提醒，中英两版都是`() {
        val records = listOf(answerRecord(question, Answer.OwnWords("我的想法")), answerRecord(question, Answer.Skipped))
        val zh = answersMessage(records, isChinese = true)
        assertEquals(
            "用户对这一批问题的回答：\n\n" +
                "- 恢复范围（断线后恢复哪些内容？） → 其他：我的想法\n" +
                "- 恢复范围（断线后恢复哪些内容？） → 没有回答，按默认处理：只恢复连接\n\n" +
                "回复仍只用约定的 JSON：{\"type\": \"questions\", ...} 或 {\"type\": \"done\", ...}。",
            zh,
        )
        val en = answersMessage(records, isChinese = false)
        assertEquals(
            "The user's answers to this batch:\n\n" +
                "- 恢复范围 (断线后恢复哪些内容？) → Other: 我的想法\n" +
                "- 恢复范围 (断线后恢复哪些内容？) → Not answered; use the default: 只恢复连接\n\n" +
                "Reply with the agreed JSON only: {\"type\": \"questions\", ...} or {\"type\": \"done\", ...}.",
            en,
        )
    }

    @Test
    fun `修订后重开对话的中英文提示明确后续回答优先`() {
        val history =
            listOf(
                answerRecord(question, Answer.OwnWords("OLD-CHOICE")),
                answerRecord(question, Answer.OwnWords("NEW-CHOICE")),
            )
        for (chinese in listOf(true, false)) {
            val message = openingMessage(fresh.copy(history = history), chinese, "GUIDE", "PLAN", 1)
            assertFalse("$chinese：不重新确认旧决定", message.contains("OLD-CHOICE"))
            assertTrue("$chinese：方案在新回答前", message.indexOf("PLAN") < message.indexOf("NEW-CHOICE"))
            assertTrue("$chinese：新回答存在", message.contains("NEW-CHOICE"))
            assertTrue(
                "$chinese：明确优先级",
                message.contains(if (chinese) "若与方案冲突，以后续回答为准" else "if they conflict with the plan, the later answers take precedence"),
            )
        }
    }

    @Test
    fun `中英文继续聊仅发送意图修订文本和协议提醒`() {
        val plan = "保留当前交互，只探索内部机制，不承诺实施任何候选方案。"
        val zh = continueMessage(true, plan)
        val en = continueMessage(false, plan)
        assertEquals(
            "用户希望在以下当前成果的基础上继续探讨。这份文本取代修订前的决定。\n\n" +
                plan + "\n\n回复仍只用约定的 JSON：{\"type\": \"questions\", ...} 或 {\"type\": \"done\", ...}。",
            zh,
        )
        assertEquals(
            "The user wants to continue the discussion from the current outcome below. This text replaces decisions made before the revision.\n\n" +
                plan + "\n\nReply with the agreed JSON only: {\"type\": \"questions\", ...} or {\"type\": \"done\", ...}.",
            en,
        )
    }

    @Test
    fun `开放回答续聊与恢复都保留看法和原话且不称为其他`() {
        val open = Question("q1", "体验", "你怎么看？", false, emptyList(), "暂不推断", QuestionKind.OPEN, "我的看法可能不成立。")
        val records = listOf(answerRecord(open, Answer.OwnWords("我不认同。\n困扰来自打断。")))
        for (chinese in listOf(true, false)) {
            val followup = answersMessage(records, chinese)
            val reopening = openingMessage(fresh.copy(history = records), chinese, "GUIDE")
            for ((phase, message) in listOf("续聊" to followup, "恢复" to reopening)) {
                assertTrue("$chinese $phase：保留被回应的看法", message.contains("我的看法可能不成立。"))
                assertTrue("$chinese $phase：保留问题", message.contains("你怎么看？"))
                assertTrue("$chinese $phase：原话保留换行", message.contains("→ 我不认同。\n困扰来自打断。"))
                assertFalse("$chinese $phase：不是选择题的其他", message.contains("Other: 我不认同") || message.contains("其他：我不认同"))
            }
        }
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
            val prompt = openingMessage(fresh, chinese, "g")
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
