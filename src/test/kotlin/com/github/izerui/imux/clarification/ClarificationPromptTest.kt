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
    fun `中英文实际开场要求先查项目再给具体建议`() {
        for (chinese in listOf(true, false)) {
            val opening = openingMessage(fresh, chinese, loadGuidance(chinese))
            assertTrue(
                "$chinese：具体建议前主动用工具调查",
                opening.contains(
                    if (chinese) "在提出具体判断和建议前，\n主动用工具查阅"
                    else "actively use tools to inspect enough relevant code, documents and conventions before offering concrete judgments or recommendations",
                ),
            )
            assertTrue(
                "$chinese：无需用户催促",
                opening.contains(if (chinese) "不等用户提醒或多次要求继续聊才调查" else "Do not wait for reminders or repeated requests to continue"),
            )
            assertTrue(
                "$chinese：缺乏证据不能冒充事实",
                opening.contains(if (chinese) "无法查证的判断明确作为假设" else "label unverified judgments as assumptions"),
            )
        }
    }

    @Test
    fun `中英文开场恢复与纠错协议要求交付任务正文`() {
        for (chinese in listOf(true, false)) {
            val messages = listOf(
                "开场" to openingMessage(fresh, chinese, loadGuidance(chinese)),
                "恢复" to openingMessage(
                    answered.copy(reviewedDrafts = listOf(ReviewedDraft("修订后的目标", "候选任务", ContentOrigin.AGENT, 1))),
                    chinese, loadGuidance(chinese),
                ),
                "纠错" to correctionMessage(listOf("invalid JSON"), chinese),
            )
            for ((phase, message) in messages) {
                assertTrue(
                    "$chinese $phase：回到原始目标和确认的修订",
                    message.contains(if (chinese) "以用户原始目标和已确认的修订为准" else "grounded in the user's original goal and confirmed revisions"),
                )
                assertTrue(
                    "$chinese $phase：交付任务而不是调查报告",
                    message.contains(if (chinese) "直接输出用户完善后的需求正文，不输出调查报告" else "Output the user's refined request itself, not an investigation report"),
                )
                assertTrue(
                    "$chinese $phase：保留用户表达角度",
                    message.contains(if (chinese) "沿用用户原来的人称、语气和表达角度" else "Preserve the user's original grammatical person, tone and perspective"),
                )
                assertTrue(
                    "$chinese $phase：不强制第一人称",
                    message.contains(if (chinese) "不机械统一成第一人称或固定模板" else "Do not copy verbal repetitions, force first-person phrasing or impose a template"),
                )
                assertTrue(
                    "$chinese $phase：不把候选建议写成决定",
                    message.contains(if (chinese) "涉及目标、范围、关键体验或重要代价的未确认取舍不得写成决定" else "do not settle unconfirmed tradeoffs about goals, scope, key experience or significant costs"),
                )
                assertTrue(
                    "$chinese $phase：保留探索任务的性质",
                    message.contains(if (chinese) "探索目标不得擅自改成实施任务" else "or turn exploratory goals into implementation tasks"),
                )
                assertTrue(
                    "$chinese $phase：合理细化不逐项审批",
                    message.contains(if (chinese) "不逐项要求用户批准" else "without asking for approval of each one"),
                )
                assertFalse(
                    "$chinese $phase：不再保留旧的泛化成果规则",
                    message.contains(if (chinese) "requirement 承载当前讨论成果" else "requirement holds the current outcome"),
                )
            }
        }
    }

    @Test
    fun `开场带指南、规则、需求与输出格式`() {
        val opening = openingMessage(fresh, isChinese = true, guidance = "指南正文")
        assertTrue(opening.startsWith("指南正文"))
        assertTrue("需求", opening.contains("给终端增加断线重连"))
        assertTrue("规则", opening.contains("1～$MAX_QUESTIONS_PER_ROUND"))
        assertTrue("格式", opening.contains("\"type\": \"questions\"") && opening.contains("\"fallback\""))
        assertTrue("清楚的任务不要求先提问", opening.contains("任务已清楚时可直接返回 type=done，不要求先提问"))
        assertFalse("没有额外的开场思考流程", opening.contains("## 现在"))
        assertFalse("首次开场没有已给出的回答", opening.contains("用户已经给出的回答"))
        assertTrue(opening.trimEnd().endsWith(JSON_ONLY_ZH))
    }

    @Test
    fun `对话中断后的开场带上已确认的问答并接着做`() {
        val opening = openingMessage(answered, isChinese = false, guidance = "g")
        assertTrue(opening.contains("Retained Discussion"))
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
            val message = openingMessage(
                fresh.copy(history = history, reviewedDrafts = listOf(ReviewedDraft("PLAN", "DRAFT", ContentOrigin.AGENT, 1))),
                chinese, "GUIDE",
            )
            assertTrue("$chinese：旧决定仍有历史来源", message.indexOf("OLD-CHOICE") in 0 until message.indexOf("DRAFT"))
            assertTrue("$chinese：方案在新回答前", message.indexOf("PLAN") < message.indexOf("NEW-CHOICE"))
            assertTrue("$chinese：新回答存在", message.contains("NEW-CHOICE"))
            assertTrue(
                "$chinese：明确优先级",
                message.contains(if (chinese) "用户最新明确修订优先，但只覆盖实际改变的要求" else "the user's latest explicit revision takes precedence only for requirements actually changed"),
            )
            assertFalse("$chinese：不再整体覆盖旧决定", message.contains(if (chinese) "取代修订前的决定" else "replaces decisions made before the revision"))
        }
    }

    @Test
    fun `中英文继续聊区分模型草稿和用户编辑`() {
        val plan = "保留当前交互，只探索内部机制，不承诺实施任何候选方案。"
        for (chinese in listOf(true, false)) {
            val untouched = continueMessage(chinese, ReviewedDraft(plan, plan, ContentOrigin.AGENT, 0))
            assertTrue("$chinese：模型草稿不等于确认", untouched.contains(if (chinese) "Agent 候选任务（不代表用户确认）" else "Agent candidate task (not user approval)"))
            assertFalse("$chinese：未编辑不能声称用户修订", untouched.contains(if (chinese) "用户编辑后的文本" else "User-edited text"))
            val edited = continueMessage(chinese, ReviewedDraft("EDITED", plan, ContentOrigin.AGENT, 0))
            assertTrue("$chinese：编辑前原文保留", edited.contains(plan))
            assertTrue("$chinese：明确编辑后的文字", edited.contains(if (chinese) "用户编辑后的文本：\nEDITED" else "User-edited text:\nEDITED"))
            val notes = continueMessage(chinese, ReviewedDraft("NOTES", "NOTES", ContentOrigin.NOTES, 0))
            assertTrue("$chinese：材料不被当作批准的计划", notes.contains(if (chinese) "不能直接当作已确认方案" else "not an approved plan"))
            assertFalse("$chinese：材料不声称由用户编辑", notes.contains(if (chinese) "用户编辑后的文本" else "User-edited text"))
        }
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

    @Test
    fun `核心指南只指向交付目标，交付细则只在输出协议中出现一次`() {
        for (chinese in listOf(true, false)) {
            val guidance = loadGuidance(chinese)
            val detail = if (chinese) "会议纪要" else "meeting summary"
            assertFalse("$chinese：核心指南不再复述交付细则", guidance.contains(detail))
            assertTrue("$chinese：核心指南指向输出协议", guidance.contains(if (chinese) "交付的具体要求见输出协议" else "The output protocol defines the handoff requirements"))
            val opening = openingMessage(fresh, chinese, guidance)
            assertEquals("$chinese：交付细则只出现一次", 1, opening.split(detail).size - 1)
            assertTrue(
                "$chinese：关键取舍未定时继续询问",
                opening.contains(if (chinese) "这类取舍阻碍任务成立时继续询问，不返回 done" else "when such a tradeoff prevents a coherent task, keep asking instead of returning done"),
            )
            assertTrue(
                "$chinese：交付前核对遗漏",
                opening.contains(if (chinese) "交付前核对是否遗漏用户的目标与明确要求" else "check that none of the user's goals or explicit requirements were omitted"),
            )
        }
    }

    @Test
    fun `未回答采用已披露的默认处理，且不冒充用户主动选择`() {
        for (chinese in listOf(true, false)) {
            val opening = openingMessage(answered, chinese, loadGuidance(chinese))
            assertFalse("$chinese：不再说未回答不代表赞同", opening.contains(if (chinese) "未回答不代表赞同" else "Silence is not agreement"))
            assertTrue(
                "$chinese：默认处理可以纳入任务",
                opening.contains(if (chinese) "未回答的题目采用该题事先披露的默认处理，可以据此推进并纳入任务" else "An unanswered item takes the default disclosed for it, which you may proceed with and include in the task"),
            )
            assertTrue(
                "$chinese：不说成主动选择",
                opening.contains(if (chinese) "但不要说成用户的主动选择" else "do not present it as the user's active choice"),
            )
            assertTrue(
                "$chinese：交付规则与之一致",
                opening.contains(if (chinese) "采用已披露默认处理的未回答题目不算未确认" else "unanswered items taking their disclosed default are not unconfirmed"),
            )
        }
    }

    @Test
    fun `材料修改只发上下文与增删内容，不发模型看不到原文的偏移`() {
        val draft = ReviewedDraft("材料前文\n补充：需要导出\n材料后文", "材料前文\n材料后文", ContentOrigin.NOTES, 0)
        for (chinese in listOf(true, false)) {
            val message = continueMessage(chinese, draft)
            assertFalse("$chinese：没有偏移", message.contains(if (chinese) "偏移" else "offset"))
            assertTrue("$chinese：保留新增内容", message.contains("补充：需要导出"))
            assertTrue("$chinese：保留前文定位", message.contains("材料前文"))
        }
    }
}
