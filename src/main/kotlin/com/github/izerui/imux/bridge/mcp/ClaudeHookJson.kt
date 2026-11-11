package com.github.izerui.imux.bridge.mcp

import com.github.izerui.imux.bridge.core.HookDecision
import com.github.izerui.imux.bridge.core.HookRequest
import com.github.izerui.imux.bridge.core.HookToolDone
import com.github.izerui.imux.bridge.json.obj
import com.github.izerui.imux.bridge.json.string
import com.github.izerui.imux.bridge.transcript.ClaudeToolInputs
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

const val CLAUDE_HOOK_PATH = "/imux/claude-hook"

/** Claude Code http hook 的请求解析与响应生成。字段形状见设计文档的实测结论。 */
internal object ClaudeHookJson {
    /** 解析不出（提问缺题目或选项）返回 null，入口直接回 {}。 */
    fun request(body: JsonObject, id: Long): HookRequest? {
        val toolName = body.string("tool_name") ?: return null
        val input = body.obj("tool_input") ?: JsonObject()
        val questions = if (toolName == ASK_USER_QUESTION) ClaudeToolInputs.questions(input) ?: return null else null
        // 子代理来源以 agent_id 为准：--agent 启动的主会话也带 agent_type，但不是子代理
        val agentId = body.string("agent_id")
        val agentType = agentId?.let { body.string("agent_type") }
        return HookRequest(id, toolName, agentId, agentType, ClaudeToolInputs.detail(toolName, input), questions, topLevel(input))
    }

    /** 请求体的解析结果：解析出的 JSON 对象，或要直接回的状态码。 */
    sealed interface Body {
        data class Parsed(val json: JsonObject) : Body

        data class Reply(val status: Int) : Body
    }

    /**
     * [size] 超过 [MAX_BODY_BYTES] 时不读请求体，回 200 {}：对 PermissionRequest 等于交给电脑，对 PostToolUse 无影响。
     * 读出的不是 JSON 对象回 400。
     */
    fun body(size: Int, text: () -> String): Body {
        if (size > MAX_BODY_BYTES) return Body.Reply(200)
        val json = runCatching { JsonParser.parseString(text()) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        return json?.let(Body::Parsed) ?: Body.Reply(400)
    }

    /** 计划全文可能较长，上限比 MCP 入口的 64 KB 宽。 */
    private const val MAX_BODY_BYTES = 1024 * 1024

    /** PostToolUse / PostToolUseFailure 解析成 [HookToolDone]；缺 tool_name 返回 null。 */
    fun toolDone(body: JsonObject): HookToolDone? {
        val toolName = body.string("tool_name") ?: return null
        return HookToolDone(body.string("agent_id"), toolName, topLevel(body.obj("tool_input") ?: JsonObject()))
    }

    /** tool_input 的顶层字段 → 键名递归排序后的 JSON 字符串。 */
    private fun topLevel(input: JsonObject): Map<String, String> =
        input.entrySet().associate { (key, value) -> key to sorted(value).toString() }

    /** Pass → {}；Allow → updatedInput 为原 tool_input（提问加 answers）、modes 非空时按顺序各加一条 setMode；Deny → message。不带 interrupt（实测 5）。 */
    fun response(decision: HookDecision, toolInput: JsonObject): JsonObject {
        val result = when (decision) {
            HookDecision.Pass -> return JsonObject()
            is HookDecision.Allow -> JsonObject().apply {
                addProperty("behavior", "allow")
                add("updatedInput", toolInput.deepCopy().apply {
                    decision.answers?.let { answers ->
                        add("answers", JsonObject().apply { answers.forEach { (question, answer) -> addProperty(question, answer) } })
                    }
                })
                if (decision.modes.isNotEmpty()) {
                    add("updatedPermissions", JsonArray().apply {
                        decision.modes.forEach { mode ->
                            add(JsonObject().apply {
                                addProperty("type", "setMode")
                                addProperty("mode", mode)
                                addProperty("destination", "session")
                            })
                        }
                    })
                }
            }
            is HookDecision.Deny -> JsonObject().apply {
                addProperty("behavior", "deny")
                addProperty("message", decision.message)
            }
        }
        return JsonObject().apply {
            add("hookSpecificOutput", JsonObject().apply {
                addProperty("hookEventName", "PermissionRequest")
                add("decision", result)
            })
        }
    }

    /** PostToolUse 的 tool_response.answers 排成「题目 → 答案」行，没有则 null。 */
    fun localAnswers(toolResponse: JsonElement?): String? {
        val answers = toolResponse?.takeIf { it.isJsonObject }?.asJsonObject?.obj("answers") ?: return null
        return answers.entrySet()
            .joinToString("\n") { (question, answer) ->
                "$question → ${if (answer.isJsonPrimitive) answer.asString else answer.toString()}"
            }
            .takeIf { it.isNotEmpty() }
    }

    private fun sorted(element: JsonElement): JsonElement = when {
        element.isJsonObject -> JsonObject().also { copy ->
            element.asJsonObject.entrySet().sortedBy { it.key }.forEach { (key, value) -> copy.add(key, sorted(value)) }
        }
        element.isJsonArray -> JsonArray().also { copy -> element.asJsonArray.forEach { copy.add(sorted(it)) } }
        else -> element
    }

    private const val ASK_USER_QUESTION = "AskUserQuestion"
}
