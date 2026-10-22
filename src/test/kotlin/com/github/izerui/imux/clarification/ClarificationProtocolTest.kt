package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
         "next":{"type":"questions","questions":[{"directionId":"new-1","question":"Q","reason":"R","basis":"B",
           "options":[{"id":"a","label":"A","recommended":true,"conclusion":"CA"},{"id":"b","label":"B","recommended":false,"conclusion":"CB"}]}]}}
        """.trimIndent()

    private fun errorsOf(raw: String): List<String> =
        try {
            parseRoundResponse(raw)
            fail("应抛 ProtocolException"); emptyList()
        } catch (e: ProtocolException) {
            e.errors
        }

    @Test
    fun `完整的提问返回被解析`() {
        val r = parseRoundResponse(question)
        assertEquals("理解", r.understanding)
        assertEquals(listOf(NewDirection("new-1", "恢复范围", "w", "s", "f")), r.added)
        assertEquals(listOf(DroppedDirection("d3", "r")), r.dropped)
        assertEquals(listOf(AnsweredReport("d1", decided = false, conclusion = "c", openPoints = listOf("p"))), r.answered)
        val ask = r.next as Next.Ask
        assertEquals("new-1", ask.questions.single().directionId)
        assertEquals(listOf("CA", "CB"), ask.questions.single().options.map { it.conclusion })
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
    fun `选项少于两个或多于四个都判为错误`() {
        val one = question.replace(""",{"id":"b","label":"B","recommended":false,"conclusion":"CB"}""", "")
        assertTrue(errorsOf(one).any { it.contains("2-4") })
        val five = question.replace(
            """{"id":"b","label":"B","recommended":false,"conclusion":"CB"}""",
            (1..4).joinToString(",") { """{"id":"x$it","label":"L","recommended":false,"conclusion":"C"}""" },
        )
        assertTrue(errorsOf(five).any { it.contains("2-4") })
    }

    @Test
    fun `选项缺少结论时判为错误`() {
        assertTrue(errorsOf(question.replace("\"conclusion\":\"CB\"", "\"conclusion\":\"\"")).any { it.contains("conclusion") })
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

    private val oneQuestion =
        """{"directionId":"d1","question":"Q","reason":"R","basis":"B",
             "options":[{"id":"a","label":"A","recommended":true,"conclusion":"CA"},{"id":"b","label":"B","recommended":false,"conclusion":"CB"}]}"""

    private fun batchOf(n: Int) = """{"notes":"","next":{"type":"questions","questions":[${List(n) { oneQuestion }.joinToString(",")}]}}"""

    @Test
    fun `一批多题按顺序解析`() {
        val r = parseRoundResponse(batchOf(5))
        assertEquals(5, (r.next as Next.Ask).questions.size)
    }

    @Test
    fun `一批多于五题判为错误`() {
        assertTrue(errorsOf(batchOf(6)).any { it.contains("1-5") })
    }

    @Test
    fun `一批没有题判为错误`() {
        assertTrue(errorsOf(batchOf(0)).any { it.contains("1-5") })
    }

    @Test
    fun `批中某题的选项不合格时错误指明是第几题`() {
        val bad = oneQuestion.replace(""",{"id":"b","label":"B","recommended":false,"conclusion":"CB"}""", "")
        val raw = """{"notes":"","next":{"type":"questions","questions":[$oneQuestion,$bad]}}"""
        assertTrue(errorsOf(raw).any { it.contains("questions[1]") && it.contains("2-4") })
    }

    @Test
    fun `旧的单题格式不再被接受`() {
        val old = """{"notes":"","next":{"type":"question","directionId":"d1","question":"Q","options":[]}}"""
        assertTrue(errorsOf(old).any { it.contains("questions or done") })
    }
}
