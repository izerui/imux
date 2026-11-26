package com.github.izerui.imux.code

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditCheckTest {
    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    @Test fun `发话是一轮开始，要结束是一轮结束并带上这一轮是否拦过，其它事件不管`() {
        assertEquals("发话", EditCheck.Event.TurnStart, EditCheck.event(json("""{"hook_event_name":"UserPromptSubmit","prompt":"改一下"}""")))
        assertEquals("第一次要结束", EditCheck.Event.TurnEnd(alreadyBlocked = false), EditCheck.event(json("""{"hook_event_name":"Stop","stop_hook_active":false}""")))
        assertEquals("拦过之后再要结束", EditCheck.Event.TurnEnd(alreadyBlocked = true), EditCheck.event(json("""{"hook_event_name":"Stop","stop_hook_active":true}""")))
        assertEquals("用完工具", EditCheck.Event.Other, EditCheck.event(json("""{"hook_event_name":"PostToolUse","tool_name":"Edit"}""")))
        assertEquals("请求体不是 JSON", EditCheck.Event.Other, EditCheck.event(null))
    }

    @Test fun `结束时拦过就放行、开关关着、IDEA MCP 没开、这一轮没改动都不查，否则查`() {
        val first = EditCheck.Event.TurnEnd(alreadyBlocked = false)
        val changed = setOf("/p/a.kt")
        assertTrue("都满足", editCheckWanted(first, changed, enabled = true, ideaMcp = true))
        assertFalse("拦过一次", editCheckWanted(EditCheck.Event.TurnEnd(alreadyBlocked = true), changed, enabled = true, ideaMcp = true))
        assertFalse("开关关着", editCheckWanted(first, changed, enabled = false, ideaMcp = true))
        assertFalse("IDEA MCP 没开", editCheckWanted(first, changed, enabled = true, ideaMcp = false))
        assertFalse("这一轮没改动", editCheckWanted(first, emptySet(), enabled = true, ideaMcp = true))
        assertFalse("这一轮没开始记", editCheckWanted(first, null, enabled = true, ideaMcp = true))
    }

    private val lint = json(
        """{"items":[{"filePath":"src/a.kt","problems":[
            {"severity":"ERROR","description":"Unresolved reference 'role'.","lineText":"  agentGuidance(role)","line":72,"column":5},
            {"severity":"WARNING","description":"Unused variable","lineText":"val x = 1","line":80,"column":9}]},
            {"filePath":"src/b.kt","problems":[{"severity":"ERROR","description":"Type mismatch","lineText":"f(1)","line":3}]}]}""",
    )

    @Test fun `中文报告按文件列出错误、带行号和那一行代码，警告不报`() {
        val text = EditCheck.report(lint, fileCount = 2, chinese = true)!!
        assertTrue(text.contains("IDE 检查了这一轮改过的 2 个文件，发现 2 处错误："))
        assertTrue(text.contains("- `src/a.kt` 第 72 行：Unresolved reference 'role'.（`agentGuidance(role)`）"))
        assertTrue(text.contains("- `src/b.kt` 第 3 行：Type mismatch（`f(1)`）"))
        assertTrue("原有错误可以说明", text.contains("如果某处错误在你改之前就有、或是有意留着的，说明一下即可"))
        assertFalse("警告不报", text.contains("Unused variable"))
    }

    @Test fun `英文报告按文件列出错误、带行号和那一行代码，警告不报`() {
        val text = EditCheck.report(lint, fileCount = 2, chinese = false)!!
        assertTrue(text.contains("The IDE checked the 2 file(s) changed in this turn and found 2 error(s):"))
        assertTrue(text.contains("- `src/a.kt` line 72: Unresolved reference 'role'. (`agentGuidance(role)`)"))
        assertTrue(text.contains("- `src/b.kt` line 3: Type mismatch (`f(1)`)"))
        assertTrue("原有错误可以说明", text.contains("If an error was there before your change, or is intended, say so"))
        assertFalse("警告不报", text.contains("Unused variable"))
    }

    @Test fun `没有错误时不报告，超过上限时说明还有几处没列出`() {
        assertNull("只有警告", EditCheck.report(json("""{"items":[{"filePath":"a.kt","problems":[{"severity":"WARNING","description":"w","line":1}]}]}"""), 1, true))
        assertNull("没有问题", EditCheck.report(json("""{"items":[]}"""), 1, true))
        val many = json("""{"items":[{"filePath":"a.kt","problems":[${(1..20).joinToString(",") { """{"severity":"ERROR","description":"e$it","line":$it}""" }}]}]}""")
        val text = EditCheck.report(many, 1, chinese = true)!!
        assertTrue(text.contains("第 15 行：e15"))
        assertFalse("第 16 条起不列", text.contains("第 16 行"))
        assertTrue(text.contains("……还有 5 处没列出。"))
    }

    @Test fun `没有错误时放行，有错误时拦下并把报告作为理由交给 Claude`() {
        assertEquals("{}", EditCheck.stopResponse(null))
        val out = json(EditCheck.stopResponse("有错"))
        assertEquals("block", out["decision"].asString)
        assertEquals("有错", out["reason"].asString)
    }

    @Test fun `Claude 的 hook 订阅发话和要结束两个事件，发到检查地址，令牌占位符原样保留，结束时等得够久`() {
        val settings = json(editCheckHookSettings("http://127.0.0.1:63342$CODE_EDIT_CHECK_PATH", "IMUX_CODE_TOKEN"))
        val hooks = settings.getAsJsonObject("hooks")
        assertEquals("只订阅这两个事件", setOf("UserPromptSubmit", "Stop"), hooks.keySet())
        fun hook(event: String) = hooks.getAsJsonArray(event).single().asJsonObject.getAsJsonArray("hooks").single().asJsonObject
        for (event in listOf("UserPromptSubmit", "Stop")) {
            assertEquals(event, "http://127.0.0.1:63342/imux/code-edit-check", hook(event)["url"].asString)
            assertEquals(event, "Bearer \$IMUX_CODE_TOKEN", hook(event).getAsJsonObject("headers")["Authorization"].asString)
            assertEquals(event, "IMUX_CODE_TOKEN", hook(event).getAsJsonArray("allowedEnvVars").single().asString)
        }
        assertTrue("结束时的等待上限比检查预算长", hook("Stop")["timeout"].asInt * 1000 > EDIT_CHECK_LINT_TIMEOUT_MILLIS)
    }

    @Test fun `调 IDEA MCP 依次初始化、确认、调用检查，带上项目和会话号，一次查多个文件，取回结构化结果`() {
        val calls = ArrayList<Pair<Map<String, String>, JsonObject>>()
        val client = IdeaMcpLintClient("http://x/stream", "/proj") { _, headers, body ->
            val request = json(body)
            calls += headers to request
            when (request["method"].asString) {
                "initialize" -> mapOf("mcp-session-id" to "S1") to """{"jsonrpc":"2.0","id":1,"result":{}}"""
                "notifications/initialized" -> emptyMap<String, String>() to ""
                else -> emptyMap<String, String>() to "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"isError\":false,\"structuredContent\":{\"items\":[]}}}\n"
            }
        }
        val result = client.lint(listOf("src/a.kt", "src/b.kt"), 20_000)
        assertEquals("""{"items":[]}""", result.toString())
        assertEquals(listOf("initialize", "notifications/initialized", "tools/call"), calls.map { it.second["method"].asString })
        assertTrue("每一步都带项目", calls.all { it.first["IJ_MCP_SERVER_PROJECT_PATH"] == "/proj" })
        assertNull("初始化不带会话号", calls[0].first["Mcp-Session-Id"])
        assertEquals("之后带会话号", listOf("S1", "S1"), calls.drop(1).map { it.first["Mcp-Session-Id"] })
        val arguments = calls[2].second.getAsJsonObject("params").getAsJsonObject("arguments")
        assertEquals("lint_files", calls[2].second.getAsJsonObject("params")["name"].asString)
        assertEquals(listOf("src/a.kt", "src/b.kt"), arguments.getAsJsonArray("files").map { it.asString })
        assertEquals("error", arguments["min_severity"].asString)
        assertEquals(20_000, arguments["timeout"].asInt)
    }

    @Test fun `连不上、工具报错时都当没查出问题`() {
        assertNull("连不上", IdeaMcpLintClient("http://x/stream", "/proj") { _, _, _ -> null }.lint(listOf("a.kt"), 1000))
        val failing = IdeaMcpLintClient("http://x/stream", "/proj") { _, _, body ->
            if (json(body)["method"].asString == "tools/call") emptyMap<String, String>() to """{"jsonrpc":"2.0","id":2,"result":{"isError":true,"content":[]}}"""
            else emptyMap<String, String>() to "{}"
        }
        assertNull("工具报错", failing.lint(listOf("a.kt"), 1000))
    }
}
