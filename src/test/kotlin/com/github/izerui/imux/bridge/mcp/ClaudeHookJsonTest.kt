package com.github.izerui.imux.bridge.mcp

import com.github.izerui.imux.bridge.core.HookDecision
import com.github.izerui.imux.bridge.core.HookReplies
import com.github.izerui.imux.bridge.core.PromptKind
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ClaudeHookJsonTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private fun decision(response: JsonObject) =
        response.getAsJsonObject("hookSpecificOutput").getAsJsonObject("decision")

    private val question = json(
        """{"questions":[{"question":"颜色？","header":"颜色","multiSelect":false,
            "options":[{"label":"红","description":"暖色"},{"label":"蓝","description":"冷色"}]}]}""",
    )

    @Test fun `任何决定的响应都不带 interrupt`() {
        val input = json("""{"command":"ls"}""")
        assertFalse(ClaudeHookJson.response(HookDecision.Pass, input).toString().contains("interrupt"))
        assertFalse(ClaudeHookJson.response(HookDecision.Allow(), input).toString().contains("interrupt"))
        assertFalse(ClaudeHookJson.response(HookDecision.Allow(answers = mapOf("颜色？" to "红")), question).toString().contains("interrupt"))
        assertFalse(ClaudeHookJson.response(HookDecision.Allow(modes = HookReplies.PLAN_FIRST_MODES), json("""{"plan":"p"}""")).toString().contains("interrupt"))
        assertFalse(ClaudeHookJson.response(HookDecision.Deny("no"), input).toString().contains("interrupt"))
    }

    @Test fun `交给电脑回空对象`() {
        assertEquals(JsonObject(), ClaudeHookJson.response(HookDecision.Pass, json("""{"command":"ls"}""")))
    }

    @Test fun `允许提问时原样带回题目并加答案`() {
        val response = ClaudeHookJson.response(HookDecision.Allow(answers = mapOf("颜色？" to "红")), question)
        assertEquals("PermissionRequest", response.getAsJsonObject("hookSpecificOutput")["hookEventName"].asString)
        val decision = decision(response)
        assertEquals("allow", decision["behavior"].asString)
        val updated = decision.getAsJsonObject("updatedInput")
        assertEquals(question["questions"], updated["questions"])
        assertEquals(json("""{"颜色？":"红"}"""), updated["answers"])
        assertFalse(decision.has("updatedPermissions"))
        // 原 tool_input 不被改动
        assertFalse(question.has("answers"))
    }

    @Test fun `计划回 1 依次下发 auto 与 bypassPermissions 两条 setMode，只作用于本会话`() {
        val input = json("""{"plan":"第一步","planFilePath":"/tmp/p.md"}""")
        val decision = decision(ClaudeHookJson.response(HookReplies.plan("1"), input))
        assertEquals("allow", decision["behavior"].asString)
        assertEquals(
            JsonParser.parseString(
                """[{"type":"setMode","mode":"auto","destination":"session"},
                    {"type":"setMode","mode":"bypassPermissions","destination":"session"}]""",
            ),
            decision["updatedPermissions"],
        )
        assertEquals(input, decision["updatedInput"])
    }

    @Test fun `计划回 2 只下发 default 一条 setMode`() {
        val input = json("""{"plan":"第一步"}""")
        val decision = decision(ClaudeHookJson.response(HookReplies.plan("2"), input))
        assertEquals(JsonParser.parseString("""[{"type":"setMode","mode":"default","destination":"session"}]"""), decision["updatedPermissions"])
    }

    @Test fun `拒绝只带 behavior 与 message`() {
        val decision = decision(ClaudeHookJson.response(HookDecision.Deny("不行"), json("""{"command":"ls"}""")))
        assertEquals(json("""{"behavior":"deny","message":"不行"}"""), decision)
    }

    @Test fun `matchKey 含代理 id`() {
        val main = json("""{"hook_event_name":"PostToolUse","tool_name":"Bash","tool_input":{"command":"ls"}}""")
        val agent = json("""{"hook_event_name":"PostToolUse","agent_id":"a1","tool_name":"Bash","tool_input":{"command":"ls"}}""")
        val other = json("""{"hook_event_name":"PostToolUse","agent_id":"a2","tool_name":"Bash","tool_input":{"command":"ls"}}""")
        assertNotEquals(ClaudeHookJson.matchKey(main), ClaudeHookJson.matchKey(agent))
        assertNotEquals(ClaudeHookJson.matchKey(agent), ClaudeHookJson.matchKey(other))
    }

    @Test fun `matchKey 忽略 answers 与键顺序`() {
        val asked = json(
            """{"tool_name":"AskUserQuestion","tool_input":{"questions":[{"question":"颜色？","multiSelect":false,
                "options":[{"label":"红","description":"暖色"}]}]}}""",
        )
        val done = json(
            """{"tool_input":{"answers":{"颜色？":"红"},"questions":[{"options":[{"description":"暖色","label":"红"}],
                "multiSelect":false,"question":"颜色？"}]},"tool_name":"AskUserQuestion"}""",
        )
        assertEquals(ClaudeHookJson.matchKey(asked), ClaudeHookJson.matchKey(done))
    }

    @Test fun `子代理请求带来源与要批准的内容`() {
        val body = json(
            """{"hook_event_name":"PermissionRequest","agent_id":"a1","agent_type":"general-purpose",
                "tool_name":"Bash","tool_input":{"command":"rm -rf build","description":"清理"}}""",
        )
        val request = ClaudeHookJson.request(body, 7)!!
        assertEquals(7L, request.id)
        assertEquals("Bash", request.toolName)
        assertEquals("a1", request.agentId)
        assertEquals("general-purpose", request.agentType)
        assertEquals("清理\nrm -rf build", request.detail)
        assertEquals(PromptKind.PERMISSION, request.kind)
        assertEquals(ClaudeHookJson.matchKey(body), request.matchKey)
    }

    @Test fun `没有 agent_id 的请求属于主会话，agent_type 不当作子代理来源`() {
        val body = json(
            """{"hook_event_name":"PermissionRequest","agent_type":"reviewer",
                "tool_name":"Bash","tool_input":{"command":"ls"}}""",
        )
        val request = ClaudeHookJson.request(body, 1)!!
        assertNull(request.agentId)
        assertNull(request.agentType)
    }

    @Test fun `请求体超过 1 MB 时不解析，回 200 交给电脑`() {
        assertEquals(ClaudeHookJson.Body.Reply(200), ClaudeHookJson.body(1024 * 1024 + 1) { error("超限时不应读取请求体") })
    }

    @Test fun `请求体恰好 1 MB 时照常解析`() {
        assertEquals(ClaudeHookJson.Body.Parsed(json("{}")), ClaudeHookJson.body(1024 * 1024) { "{}" })
    }

    @Test fun `请求体不是 JSON 对象时回 400`() {
        assertEquals(ClaudeHookJson.Body.Reply(400), ClaudeHookJson.body(2) { "[]" })
        assertEquals(ClaudeHookJson.Body.Reply(400), ClaudeHookJson.body(3) { "{x" })
    }

    @Test fun `提问带上题目`() {
        val body = json("""{"hook_event_name":"PermissionRequest","tool_name":"AskUserQuestion","tool_input":$question}""")
        val request = ClaudeHookJson.request(body, 1)!!
        assertEquals(PromptKind.QUESTION, request.kind)
        assertEquals(listOf("红", "蓝"), request.questions!!.single().options.map { it.label })
    }

    @Test fun `提问缺选项解析为 null`() {
        val body = json(
            """{"hook_event_name":"PermissionRequest","tool_name":"AskUserQuestion",
                "tool_input":{"questions":[{"question":"颜色？","options":[]}]}}""",
        )
        assertNull(ClaudeHookJson.request(body, 1))
    }

    @Test fun `电脑作答的答案排成行`() {
        val response = JsonParser.parseString("""{"answers":{"颜色？":"红","水果？":"苹果, 橙子"}}""")
        assertEquals("颜色？ → 红\n水果？ → 苹果, 橙子", ClaudeHookJson.localAnswers(response))
    }

    @Test fun `没有答案时为 null`() {
        assertNull(ClaudeHookJson.localAnswers(null))
        assertNull(ClaudeHookJson.localAnswers(JsonParser.parseString("\"ok\"")))
        assertNull(ClaudeHookJson.localAnswers(JsonParser.parseString("""{"answers":{}}""")))
    }
}
