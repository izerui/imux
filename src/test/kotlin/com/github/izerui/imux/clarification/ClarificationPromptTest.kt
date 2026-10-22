// ClarificationPromptTest.kt
package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationPromptTest {
    private val ledger =
        Ledger(
            requirement = "给终端增加断线重连",
            background = "User: 之前的对话",
            directions = listOf(
                Direction("d1", "恢复范围", "影响状态保存", "TerminalSession.kt", "只恢复连接"),
                Direction("d2", "多标签页", "影响范围", "TerminalHost.kt", "逐个重连", status = DirectionStatus.DEFAULTED),
            ),
            awaitingReport = listOf("d1", "d2"),
        )

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
    fun `提示词包含指南与台账全部内容`() {
        val prompt = clarificationPrompt(Phase.ROUND, ledger, isChinese = true, guidance = "指南正文")
        assertTrue(prompt.startsWith("指南正文"))
        assertTrue(prompt.contains("给终端增加断线重连"))
        assertTrue(prompt.contains("User: 之前的对话"))
        assertTrue(prompt.contains("恢复范围"))
        assertTrue(prompt.contains("多标签页"))
    }

    @Test
    fun `提示词写明冻结方向与上一批需要交代的方向`() {
        val prompt = clarificationPrompt(Phase.ROUND, ledger, isChinese = false, guidance = "g")
        assertFalse("不再有先问这个", prompt.contains("mustAsk"))
        assertTrue(prompt.contains("\"frozen\": [\n      \"d2\"\n    ]"))
        assertTrue(prompt.contains("\"reportAnswered\": [\n      \"d1\",\n      \"d2\"\n    ]"))
    }

    @Test
    fun `上一次的错误附在提示词里`() {
        val prompt = clarificationPrompt(Phase.ROUND, ledger, isChinese = false, guidance = "g", errors = listOf("options must have 2-4 items"))
        assertTrue(prompt.contains("options must have 2-4 items"))
    }

    @Test
    fun `各阶段提示词都以只输出 JSON 且不修改文件结尾`() {
        assertTrue(clarificationPrompt(Phase.FIRST, ledger, true, "g").trimEnd().endsWith(JSON_ONLY_ZH))
        assertTrue(clarificationPrompt(Phase.ROUND, ledger, true, "g").trimEnd().endsWith(JSON_ONLY_ZH))
        assertTrue(clarificationPrompt(Phase.RESULT, ledger, true, "g").trimEnd().endsWith(JSON_ONLY_ZH))
        assertTrue(clarificationPrompt(Phase.RESULT, ledger, false, "g").trimEnd().endsWith(JSON_ONLY_EN))
    }

    @Test
    fun `生成结果阶段只要求返回 requirement`() {
        val prompt = clarificationPrompt(Phase.RESULT, ledger, isChinese = false, guidance = "g")
        assertTrue(prompt.contains("{\"requirement\": \"...\"}"))
        assertFalse(prompt.contains("\"next\""))
    }

    @Test
    fun `提问阶段的中英提示词都写明每批一到四题、短标签、说明即记录、不给其他`() {
        for (chinese in listOf(true, false)) {
            for (phase in listOf(Phase.FIRST, Phase.ROUND)) {
                val prompt = clarificationPrompt(phase, ledger, chinese, "g")
                val name = "${if (chinese) "zh" else "en"} $phase"
                assertTrue("$name：1～4 题", prompt.contains(if (chinese) "1～$MAX_QUESTIONS_PER_ROUND" else "1-$MAX_QUESTIONS_PER_ROUND"))
                assertTrue("$name：header 字数", prompt.contains("\"header\"") && prompt.contains("$HEADER_CHARS"))
                assertTrue("$name：description", prompt.contains("description"))
                assertTrue("$name：multiSelect", prompt.contains("multiSelect"))
                assertTrue("$name：不给其他", prompt.contains(if (chinese) "不要给\"其他\"选项" else "Do not include an \"Other\" option"))
            }
        }
    }

    @Test
    fun `history 里多选与原话的回答都写明`() {
        val q = Question("d1", "H", "Q", true, listOf(ChoiceOption("a", "A", "DA", true), ChoiceOption("b", "B", "DB", false)))
        val withHistory =
            ledger.copy(
                history = listOf(
                    AnswerRecord(q, Answer.Picked(listOf("a", "b"), "OWN"), "DA；DB；OWN"),
                    AnswerRecord(q, Answer.Skipped, "只恢复连接"),
                ),
            )
        val prompt = clarificationPrompt(Phase.ROUND, withHistory, isChinese = false, guidance = "g")
        assertTrue(prompt.contains("\"picked:a,b+own-words\""))
        assertTrue(prompt.contains("\"unanswered\""))
        assertTrue(prompt.contains("DA；DB；OWN"))
    }
}
