package com.github.izerui.imux.bridge.transcript

import com.github.izerui.imux.bridge.core.AskedOption
import com.github.izerui.imux.bridge.core.AskedQuestion
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeToolInputsTest {
    @Test
    fun `命令显示说明和命令两行`() {
        val input = parse("""{"command":"rm -rf x","description":"删"}""")
        assertEquals("删\nrm -rf x", ClaudeToolInputs.detail("Bash", input))
    }

    @Test
    fun `计划取全文且按 8000 截短`() {
        val plan = "a".repeat(9000)
        val input = JsonObject().apply { addProperty("plan", plan) }
        val detail = ClaudeToolInputs.detail("ExitPlanMode", input)
        assertEquals(8000, detail.length)
        assertEquals("…", detail.last().toString())
    }

    @Test
    fun `其它工具依次取文件路径、网址、查询，否则参数原文`() {
        assertEquals("/a", ClaudeToolInputs.detail("Read", parse("""{"file_path":"/a"}""")))
        assertEquals("https://x", ClaudeToolInputs.detail("WebFetch", parse("""{"url":"https://x"}""")))
        assertEquals("select 1", ClaudeToolInputs.detail("SQL", parse("""{"query":"select 1"}""")))
        assertEquals("{\"x\":1}", ClaudeToolInputs.detail("Unknown", parse("""{"x":1}""")))
    }

    @Test
    fun `长命令按 1500 截短，未超长的计划不截`() {
        val long = ClaudeToolInputs.detail("Bash", parse("""{"command":"${"x".repeat(3000)}"}"""))
        assertEquals(1500, long.length)
        assertEquals("…", long.last().toString())
        assertEquals(5000, ClaudeToolInputs.detail("ExitPlanMode", parse("""{"plan":"${"x".repeat(5000)}"}""")).length)
    }

    @Test
    fun `缺选项的提问返回 null`() {
        // 一题有选项、一题 options 为空数组 → null
        val input = parse(
            """{"questions":[
                {"question":"题1","options":[{"label":"A"}]},
                {"question":"题2","options":[]}
            ]}"""
        )
        assertNull(ClaudeToolInputs.questions(input))
    }

    @Test
    fun `多选标记与选项说明保留`() {
        val input = parse(
            """{"questions":[{
                "question":"选颜色",
                "multiSelect":true,
                "options":[
                    {"label":"红","description":"暖色"},
                    {"label":"蓝"}
                ]
            }]}"""
        )
        val questions = ClaudeToolInputs.questions(input)!!
        assertEquals(1, questions.size)
        val q = questions[0]
        assertEquals("选颜色", q.question)
        assertEquals(true, q.multiSelect)
        assertEquals(
            listOf(AskedOption("红", "暖色"), AskedOption("蓝", null)),
            q.options,
        )
    }

    private fun parse(json: String): JsonObject =
        JsonParser.parseString(json).asJsonObject
}
