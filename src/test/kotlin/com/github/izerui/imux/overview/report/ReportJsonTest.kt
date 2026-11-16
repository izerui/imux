package com.github.izerui.imux.overview.report

import com.github.izerui.imux.overview.NodeSymbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReportJsonTest {
    private val full = """
        {
          "goal": "手机上发停止就能停下会话",
          "summary": "停止功能已完成。",
          "status": "in_progress",
          "decisions": [ { "title": "用 Esc 不用 Ctrl+C", "reason": "Ctrl+C 会退出" } ],
          "changes": [
            { "file": "a/B.kt", "summary": "停止词先处理",
              "snippet": { "lines": [ { "kind": "ctx", "text": "val x = 1" }, { "kind": "add", "text": "stop()" } ] },
              "note": "先于确认" }
          ],
          "verification": [
            { "what": "单元测试", "result": "passed", "detail": "全部通过" },
            { "what": "实测", "result": "not_run" }
          ],
          "timeline": [
            { "type": "MAIN", "time": "2026-11-16T09:05:00Z", "text": "确认方案" },
            { "type": "MAIN", "text": "实现停止词" },
            { "type": "CHANGE", "text": "后台任务也要停" }
          ],
          "issues": [ { "severity": "high", "text": "Codex 未实测" } ],
          "nextSteps": [ "实测三个 CLI", "写设计" ]
        }
    """.trimIndent()

    @Test
    fun `完整 JSON 解析出各节`() {
        val report = parseAgentReport(full)!!
        assertEquals("手机上发停止就能停下会话", report.goal)
        assertEquals("停止功能已完成。", report.summary)
        assertEquals(ReportStatus.IN_PROGRESS, report.status)
        assertEquals("用 Esc 不用 Ctrl+C", report.decisions[0].title)
        assertEquals(LineKind.ADD, report.changes[0].lines[1].kind)
        assertEquals(CheckResult.NOT_RUN, report.verification[1].result)
        assertEquals(3, report.timeline.size)
        assertEquals(NodeSymbol.CHANGE, report.timeline[2].symbol)
        assertEquals(Severity.HIGH, report.issues[0].severity)
        assertEquals(2, report.nextSteps.size)
    }

    @Test
    fun `围栏和前后说明文字之间的 JSON 能取出`() {
        val output = "好的，报告如下：\n```json\n$full\n```\n以上"
        val report = parseAgentReport(output)
        assertNotNull(report)
        assertEquals("手机上发停止就能停下会话", report!!.goal)
    }

    @Test
    fun `半截 JSON 解析失败返回 null`() {
        assertNull(parseAgentReport(full.substring(0, full.length / 2)))
    }

    @Test
    fun `时间线为空视为失败`() {
        val output = full.replace(Regex("\"timeline\": \\[[\\s\\S]*?\\],\\s*\"issues\""), "\"timeline\": [], \"issues\"")
        assertNull(parseAgentReport(output))
    }

    @Test
    fun `枚举越界按约定兜底`() {
        val output = """
            {
              "status": "wip",
              "changes": [ { "file": "x", "snippet": { "lines": [ { "kind": "x", "text": "a" } ] } } ],
              "verification": [ { "what": "t", "result": "ok" } ],
              "issues": [ { "severity": "urgent", "text": "i" } ],
              "timeline": [ { "type": "FOO", "text": "n" } ]
            }
        """.trimIndent()
        val report = parseAgentReport(output)!!
        assertNull(report.status)
        assertEquals(LineKind.CTX, report.changes[0].lines[0].kind)
        assertEquals(CheckResult.NOT_RUN, report.verification[0].result)
        assertEquals(Severity.LOW, report.issues[0].severity)
        assertEquals(NodeSymbol.MAIN, report.timeline[0].symbol)
    }

    @Test
    fun `超出上限的条目被截掉`() {
        fun items(n: Int, item: (Int) -> String) = (1..n).joinToString(",", "[", "]") { item(it) }
        val output = """
            {
              "timeline": ${items(25) { "{\"type\":\"MAIN\",\"text\":\"n$it\"}" }},
              "decisions": ${items(9) { "{\"title\":\"d$it\"}" }},
              "changes": ${items(8) { i ->
                  if (i == 1) "{\"file\":\"f1\",\"snippet\":{\"lines\":${items(30) { "{\"kind\":\"add\",\"text\":\"l$it\"}" }}}}"
                  else "{\"file\":\"f$i\"}"
              }},
              "verification": ${items(10) { "{\"what\":\"v$it\",\"result\":\"passed\"}" }},
              "issues": ${items(10) { "{\"severity\":\"low\",\"text\":\"i$it\"}" }},
              "nextSteps": ${items(10) { "\"s$it\"" }}
            }
        """.trimIndent()
        val report = parseAgentReport(output)!!
        assertEquals(MAX_TIMELINE, report.timeline.size)
        assertEquals(20, report.timeline.size)
        assertEquals(6, report.decisions.size)
        assertEquals(6, report.changes.size)
        assertEquals(20, report.changes[0].lines.size)
        assertEquals(8, report.verification.size)
        assertEquals(8, report.issues.size)
        assertEquals(8, report.nextSteps.size)
    }

    @Test
    fun `缺字段或类型错误的单项被丢弃不影响其他项`() {
        val output = """
            {
              "summary": "  ",
              "decisions": [ { "reason": "没有标题" }, { "title": 3 }, { "title": "正常" } ],
              "timeline": [ { "type": "MAIN", "text": "n" } ]
            }
        """.trimIndent()
        val report = parseAgentReport(output)!!
        assertEquals(listOf("正常"), report.decisions.map { it.title })
        assertNull(report.summary)
    }

    @Test
    fun `智能体字段经 agentReportJson 往返不变`() {
        val report = parseAgentReport(full)!!
        assertEquals(report, reportFromJson(agentReportJson(report)))
    }

    @Test
    fun `facts 往返不变且缺省时为空`() {
        val facts = ReportFacts(135, listOf("src/A.kt", "/tmp/b.md"))
        assertEquals(facts, factsFromJson(factsJson(facts)))
        assertEquals(ReportFacts(), factsFromJson(null))
    }

    @Test
    fun `枚举值不区分大小写`() {
        val output = """
            {
              "status": "Done",
              "changes": [ { "file": "x", "snippet": { "lines": [ { "kind": "ADD", "text": "a" } ] } } ],
              "verification": [ { "what": "t", "result": "PASSED" } ],
              "issues": [ { "severity": "High", "text": "i" } ],
              "timeline": [ { "type": "main", "text": "n" } ]
            }
        """.trimIndent()
        val report = parseAgentReport(output)!!
        assertEquals(ReportStatus.DONE, report.status)
        assertEquals(LineKind.ADD, report.changes[0].lines[0].kind)
        assertEquals(CheckResult.PASSED, report.verification[0].result)
        assertEquals(Severity.HIGH, report.issues[0].severity)
        assertEquals(NodeSymbol.MAIN, report.timeline[0].symbol)
    }

    @Test
    fun `说明文字里不配对的左括号不影响取出后面的报告`() {
        val report = parseAgentReport("字段格式以 { 开头，报告如下：\n$full")
        assertNotNull(report)
        assertEquals("手机上发停止就能停下会话", report!!.goal)
    }
}
