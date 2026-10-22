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
            pinned = "d1",
            lastAsked = "d1",
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
    fun `提示词写明先问这个与冻结方向与需要交代的上一轮方向`() {
        val prompt = clarificationPrompt(Phase.ROUND, ledger, isChinese = false, guidance = "g")
        assertTrue(prompt.contains("\"mustAsk\": \"d1\""))
        assertTrue(prompt.contains("\"frozen\": [\n      \"d2\"\n    ]"))
        assertTrue(prompt.contains("\"reportPrevious\": \"d1\""))
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
        assertTrue(clarificationPrompt(Phase.CORRECTED, ledger, true, "g").trimEnd().endsWith(JSON_ONLY_ZH))
        assertTrue(clarificationPrompt(Phase.RESULT, ledger, true, "g").trimEnd().endsWith(JSON_ONLY_ZH))
        assertTrue(clarificationPrompt(Phase.RESULT, ledger, false, "g").trimEnd().endsWith(JSON_ONLY_EN))
    }

    @Test
    fun `生成结果阶段只要求返回 requirement`() {
        val prompt = clarificationPrompt(Phase.RESULT, ledger, isChinese = false, guidance = "g")
        assertTrue(prompt.contains("{\"requirement\": \"...\"}"))
        assertFalse(prompt.contains("\"next\""))
    }
}
