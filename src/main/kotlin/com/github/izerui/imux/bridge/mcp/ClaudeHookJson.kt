package com.github.izerui.imux.bridge.mcp

import com.github.izerui.imux.bridge.core.HookDecision
import com.github.izerui.imux.bridge.core.HookRequest
import com.github.izerui.imux.bridge.json.obj
import com.github.izerui.imux.bridge.json.string
import com.github.izerui.imux.bridge.transcript.ClaudeToolInputs
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

const val CLAUDE_HOOK_PATH = "/imux/claude-hook"

/** Claude Code http hook 的请求解析与响应生成。字段形状见设计文档的实测结论。 */
internal object ClaudeHookJson {
    /** 解析不出（提问缺题目或选项）返回 null，入口直接回 {}。 */
    fun request(body: JsonObject, id: Long): HookRequest? {
        val toolName = body.string("tool_name") ?: return null
        val input = body.obj("tool_input") ?: JsonObject()
        val questions = if (toolName == ASK_USER_QUESTION) ClaudeToolInputs.questions(input) ?: return null else null
        return HookRequest(id, toolName, body.string("agent_type"), ClaudeToolInputs.detail(toolName, input), questions, matchKey(body))
    }

    /** agent_id + tool_name + 去掉 answers 键、键名递归排序后的 tool_input。 */
    fun matchKey(body: JsonObject): String {
        val input = body.obj("tool_input")?.deepCopy()?.apply { remove("answers") } ?: JsonObject()
        return JsonArray().apply {
            add(body.string("agent_id"))
            add(body.string("tool_name"))
            add(sorted(input))
        }.toString()
    }

    /** Pass → {}；Allow → updatedInput 为原 tool_input（提问加 answers）、mode 非空时加 setMode；Deny → message。不带 interrupt（实测 5）。 */
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
                decision.mode?.let { mode ->
                    add("updatedPermissions", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "setMode")
                            addProperty("mode", mode)
                            addProperty("destination", "session")
                        })
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
