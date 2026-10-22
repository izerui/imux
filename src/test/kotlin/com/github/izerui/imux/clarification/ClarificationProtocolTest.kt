package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClarificationProtocolTest {
    private val question =
        """
        {"understanding":"理解","directions":{"add":[{"tempId":"new-1","title":"恢复范围","why":"w","source":"s","fallback":"f"}],
         "drop":[{"id":"d3","reason":"r"}]},
         "answered":[{"id":"d1","status":"OPEN","conclusion":"c","openPoints":["p"]}],
         "notes":"n",
         "next":{"type":"questions","questions":[{"directionId":"new-1","header":"恢复范围","question":"Q","multiSelect":true,
           "options":[{"id":"a","label":"A","description":"DA","recommended":true},{"id":"b","label":"B","description":"DB","recommended":false}]}]}}
        """.trimIndent()

    private fun errorsOf(raw: String): List<String> =
        try {
            parseRoundResponse(raw)
            fail("应抛 ProtocolException"); emptyList()
        } catch (e: ProtocolException) {
            e.errors
        }

    private fun option(id: String, label: String = "L$id") = """{"id":"$id","label":"$label","description":"D$id","recommended":false}"""

    private fun oneQuestion(header: String = "H", options: String = "${option("a")},${option("b")}") =
        """{"directionId":"d1","header":"$header","question":"Q","options":[$options]}"""

    private fun batchOf(vararg questions: String) = """{"notes":"","next":{"type":"questions","questions":[${questions.joinToString(",")}]}}"""

    @Test
    fun `完整的提问返回被解析`() {
        val r = parseRoundResponse(question)
        assertEquals("理解", r.understanding)
        assertEquals(listOf(NewDirection("new-1", "恢复范围", "w", "s", "f")), r.added)
        assertEquals(listOf("d3"), r.dropped)
        assertEquals(listOf(AnsweredReport("d1", decided = false, conclusion = "c", openPoints = listOf("p"))), r.answered)
        val q = (r.next as Next.Ask).questions.single()
        assertEquals("new-1", q.directionId)
        assertEquals("恢复范围", q.header)
        assertTrue("multiSelect 读出", q.multiSelect)
        assertEquals(listOf("DA", "DB"), q.options.map { it.description })
        assertEquals(listOf(true, false), q.options.map { it.recommended })
    }

    @Test
    fun `没写 multiSelect 时按单选处理`() {
        val q = (parseRoundResponse(batchOf(oneQuestion())).next as Next.Ask).questions.single()
        assertFalse(q.multiSelect)
    }

    @Test
    fun `JSON 前后有说明文字或代码围栏时仍能解析`() {
        val r = parseRoundResponse("好的，结果如下：\n```json\n$question\n```\n")
        assertTrue(r.next is Next.Ask)
    }

    @Test
    fun `done 返回带出调整后的需求`() {
        val r = parseRoundResponse("""{"notes":"","next":{"type":"done","requirement":"调整后的需求"}}""")
        assertEquals(Next.Done("调整后的需求"), r.next)
        assertTrue(r.answered.isEmpty())
    }

    @Test
    fun `没有 JSON 时报错`() {
        assertTrue(errorsOf("我觉得没问题").isNotEmpty())
    }

    @Test
    fun `一批一到四题都被接受`() {
        for (n in 1..4) {
            val r = parseRoundResponse(batchOf(*Array(n) { oneQuestion() }))
            assertEquals("$n 题", n, (r.next as Next.Ask).questions.size)
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
        parseRoundResponse(batchOf(oneQuestion(options = "${option("a")},${option("b", "其他标签页一起重连")}")))
    }

    @Test
    fun `缺少标签或标签过长判为错误`() {
        assertTrue("缺少", errorsOf(batchOf(oneQuestion(header = ""))).any { it.contains("header") })
        assertTrue("过长", errorsOf(batchOf(oneQuestion(header = "一".repeat(MAX_HEADER_CHARS + 1)))).any { it.contains("header") })
        parseRoundResponse(batchOf(oneQuestion(header = "一".repeat(HEADER_CHARS))))
    }

    @Test
    fun `answered 状态不是 DECIDED 或 OPEN 时判为错误`() {
        assertTrue(errorsOf(question.replace("\"status\":\"OPEN\"", "\"status\":\"PAUSED\"")).any { it.contains("answered[0].status") })
    }

    @Test
    fun `生成结果阶段只取 requirement`() {
        assertEquals("需求", parseRequirementResponse("""{"requirement":"需求"}"""))
        try {
            parseRequirementResponse("""{"requirement":"  "}""")
            fail("空 requirement 应报错")
        } catch (_: ProtocolException) {
        }
    }

    @Test
    fun `批中某题的选项不合格时错误指明是第几题`() {
        val raw = batchOf(oneQuestion(), oneQuestion(options = option("a")))
        assertTrue(errorsOf(raw).any { it.contains("questions[1]") && it.contains("2-4") })
    }

    @Test
    fun `旧的单题格式不再被接受`() {
        val old = """{"notes":"","next":{"type":"question","directionId":"d1","question":"Q","options":[]}}"""
        assertTrue(errorsOf(old).any { it.contains("questions or done") })
    }
}
