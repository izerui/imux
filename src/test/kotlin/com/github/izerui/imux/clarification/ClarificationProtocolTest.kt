package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClarificationProtocolTest {
    private val question =
        """
        {"type":"questions","questions":[{"header":"恢复范围","question":"Q","multiSelect":true,"fallback":"只恢复连接",
           "options":[{"id":"a","label":"A","description":"DA","recommended":true},{"id":"b","label":"B","description":"DB","recommended":false}]}]}
        """.trimIndent()

    private fun errorsOf(raw: String): List<String> =
        try {
            parseReply(raw)
            fail("应抛 ProtocolException"); emptyList()
        } catch (e: ProtocolException) {
            e.errors
        }

    private fun option(id: String, label: String = "L$id") = """{"id":"$id","label":"$label","description":"D$id","recommended":false}"""

    private fun oneQuestion(header: String = "H", options: String = "${option("a")},${option("b")}", fallback: String = "F") =
        """{"header":"$header","question":"Q","fallback":"$fallback","options":[$options]}"""

    private fun batchOf(vararg questions: String) = """{"type":"questions","questions":[${questions.joinToString(",")}]}"""

    private fun questionsOf(raw: String) = (parseReply(raw) as AgentReply.Ask).questions

    @Test
    fun `完整的提问被解析，题目按顺序编号`() {
        val q = questionsOf(question).single()
        assertEquals("q1", q.id)
        assertEquals("恢复范围", q.header)
        assertEquals("只恢复连接", q.fallback)
        assertTrue("multiSelect 读出", q.multiSelect)
        assertEquals(listOf("DA", "DB"), q.options.map { it.description })
        assertEquals(listOf(true, false), q.options.map { it.recommended })
        assertEquals(listOf("q1", "q2", "q3"), questionsOf(batchOf(oneQuestion(), oneQuestion(), oneQuestion())).map { it.id })
    }

    @Test
    fun `没写 multiSelect 时按单选处理`() {
        val q = questionsOf(batchOf(oneQuestion())).single()
        assertFalse(q.multiSelect)
    }

    @Test
    fun `JSON 前后有说明文字或代码围栏时仍能解析`() {
        assertTrue(parseReply("好的，结果如下：\n```json\n$question\n```\n") is AgentReply.Ask)
    }

    @Test
    fun `done 带出调整后的需求，需求为空时判为错误`() {
        assertEquals(AgentReply.Done("调整后的需求"), parseReply("""{"type":"done","requirement":"调整后的需求"}"""))
        assertTrue(errorsOf("""{"type":"done","requirement":"  "}""").any { it.contains("requirement") })
    }

    @Test
    fun `没有 JSON 时报错`() {
        assertTrue(errorsOf("我觉得没问题").isNotEmpty())
    }

    @Test
    fun `一批一到四题都被接受`() {
        for (n in 1..4) {
            assertEquals("$n 题", n, questionsOf(batchOf(*Array(n) { oneQuestion() })).size)
        }
    }

    @Test
    fun `一批没有题或多于四题判为错误`() {
        assertTrue("0 题", errorsOf(batchOf()).any { it.contains("1-4") })
        assertTrue("5 题", errorsOf(batchOf(*Array(5) { oneQuestion() })).any { it.contains("1-4") })
    }

    @Test
    fun `选项少于两个或多于四个都判为错误`() {
        assertTrue("1 个", errorsOf(batchOf(oneQuestion(options = option("a")))).any { it.contains("2-4") })
        val five = (1..5).joinToString(",") { option("x$it") }
        assertTrue("5 个", errorsOf(batchOf(oneQuestion(options = five))).any { it.contains("2-4") })
    }

    @Test
    fun `选项缺少说明时判为错误`() {
        val noDescription = """{"id":"b","label":"B","description":"","recommended":false}"""
        assertTrue(errorsOf(batchOf(oneQuestion(options = "${option("a")},$noDescription"))).any { it.contains("description") })
    }

    @Test
    fun `模型自己给出其他选项时判为错误`() {
        for (label in listOf("其他", "Other", "其它（请说明）", "other: please specify")) {
            val raw = batchOf(oneQuestion(options = "${option("a")},${option("b", label)}"))
            assertTrue("「$label」", errorsOf(raw).any { it.contains("Other") })
        }
        // 只是以"其他"开头的正常选项不误判
        parseReply(batchOf(oneQuestion(options = "${option("a")},${option("b", "其他标签页一起重连")}")))
    }

    @Test
    fun `缺少标签或标签过长判为错误`() {
        assertTrue("缺少", errorsOf(batchOf(oneQuestion(header = ""))).any { it.contains("header") })
        assertTrue("过长", errorsOf(batchOf(oneQuestion(header = "一".repeat(MAX_HEADER_CHARS + 1)))).any { it.contains("header") })
        parseReply(batchOf(oneQuestion(header = "一".repeat(HEADER_CHARS))))
    }

    @Test
    fun `每题都要写明不回答时的默认处理`() {
        val errors = errorsOf(batchOf(oneQuestion(fallback = ""), oneQuestion(), oneQuestion(fallback = "")))
        assertTrue("第 1 题", errors.any { it.contains("questions[0].fallback") })
        assertTrue("第 3 题", errors.any { it.contains("questions[2].fallback") })
        assertTrue("写了的不报", errors.none { it.contains("questions[1]") })
    }

    @Test
    fun `批中某题的选项不合格时错误指明是第几题`() {
        val raw = batchOf(oneQuestion(), oneQuestion(options = option("a")))
        assertTrue(errorsOf(raw).any { it.contains("questions[1]") && it.contains("2-4") })
    }

    @Test
    fun `旧的台账格式与缺少 type 都不再被接受`() {
        val old = """{"notes":"","next":{"type":"questions","questions":[]}}"""
        assertTrue("旧格式", errorsOf(old).any { it.contains("questions or done") })
        assertTrue("type 写错", errorsOf("""{"type":"question"}""").any { it.contains("questions or done") })
    }

    @Test
    fun `通过校验的标签在界面上完整显示，截断按码点计数`() {
        val longest = "𠀀".repeat(MAX_HEADER_CHARS)
        parseReply(batchOf(oneQuestion(header = longest)))
        assertEquals("最长的合法标签不截断", longest, truncateCodePoints(longest, MAX_HEADER_CHARS))
        val tooLong = "𠀀".repeat(MAX_HEADER_CHARS + 1)
        assertTrue("多一个码点就判错", errorsOf(batchOf(oneQuestion(header = tooLong))).any { it.contains("header") })
        val cut = truncateCodePoints(tooLong, MAX_HEADER_CHARS)
        assertEquals("截断后正好 MAX_HEADER_CHARS 个码点", MAX_HEADER_CHARS, cut.codePointCount(0, cut.length))
        assertTrue(cut.endsWith("…"))
    }
}
