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
         "previous":{"id":"d1","status":"OPEN","conclusion":"c","openPoints":["p"]},
         "notes":"n",
         "next":{"type":"question","directionId":"new-1","question":"Q","reason":"R","basis":"B",
           "options":[{"id":"a","label":"A","recommended":true,"conclusion":"CA"},{"id":"b","label":"B","recommended":false,"conclusion":"CB"}]}}
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
        assertEquals(Previous("d1", decided = false, conclusion = "c", openPoints = listOf("p")), r.previous)
        val ask = r.next as Next.Ask
        assertEquals("new-1", ask.question.directionId)
        assertEquals(listOf("CA", "CB"), ask.question.options.map { it.conclusion })
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
        assertNull(r.previous)
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
    fun `previous 状态不是 DECIDED 或 OPEN 时判为错误`() {
        assertTrue(errorsOf(question.replace("\"status\":\"OPEN\"", "\"status\":\"PAUSED\"")).any { it.contains("previous") })
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
}
