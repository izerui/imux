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
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

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
     * 解析请求体。不超过 [MAX_BODY_BYTES]（1 MB）时整份解析；读出的不是 JSON 对象回 400。
     *
     * 超过 1 MB 时只有 PostToolUse / PostToolUseFailure（Edit、Write 的 tool_response 带整份文件，丢掉会让电脑端允许对不上）与 SubagentStop 还要读：
     * 不超过 [MAX_TOOL_DONE_BYTES]（32 MB）就流式只取对号用到的字段（见 [slim]）；其余事件（PermissionRequest 等）回 200 {}，等于交给电脑。
     * 超过 32 MB 不读，回 200 {}。平台内置服务的聚合上限是 180 MB（`ide.netty.max.frame.size.in.mb`），32 MB 以内都能收到。
     */
    fun body(size: Int, open: () -> Reader): Body {
        if (size > MAX_TOOL_DONE_BYTES) return Body.Reply(200)
        if (size <= MAX_BODY_BYTES) {
            val json = runCatching { JsonParser.parseReader(open()) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            return json?.let(Body::Parsed) ?: Body.Reply(400)
        }
        val json = runCatching { open().use(::slim) }.getOrNull() ?: return Body.Reply(400)
        return if (json.string("hook_event_name") in TOOL_DONE_EVENTS) Body.Parsed(json) else Body.Reply(200)
    }

    /** 计划全文可能较长，上限比 MCP 入口的 64 KB 宽。 */
    private const val MAX_BODY_BYTES = 1024 * 1024

    /** PostToolUse / PostToolUseFailure 的上限。 */
    private const val MAX_TOOL_DONE_BYTES = 32 * 1024 * 1024

    /** 超过 1 MB 仍要读的事件：SubagentStop 带子代理最后一条回复，也可能很长，只需 agent_id。 */
    private val TOOL_DONE_EVENTS = setOf("PostToolUse", "PostToolUseFailure", "SubagentStop")

    /**
     * 流式读顶层对象，只留 `hook_event_name`、`tool_name`、`agent_id`、`tool_input`、`tool_response.answers`，
     * 其余字段跳过不建树。顶层不是对象时抛出。
     */
    private fun slim(reader: Reader): JsonObject {
        val json = JsonReader(reader)
        val result = JsonObject()
        json.beginObject()
        while (json.hasNext()) {
            when (val name = json.nextName()) {
                "hook_event_name", "tool_name", "agent_id" ->
                    if (json.peek() == JsonToken.STRING) result.addProperty(name, json.nextString()) else json.skipValue()
                "tool_input" -> result.add(name, JsonParser.parseReader(json))
                "tool_response" -> {
                    if (json.peek() != JsonToken.BEGIN_OBJECT) { json.skipValue(); continue }
                    val response = JsonObject()
                    json.beginObject()
                    while (json.hasNext()) {
                        if (json.nextName() == "answers") response.add("answers", JsonParser.parseReader(json)) else json.skipValue()
                    }
                    json.endObject()
                    result.add(name, response)
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        return result
    }

    /** SubagentStop 停下的子代理 `agent_id`；缺失返回 null。 */
    fun subagentStopped(body: JsonObject): String? = body.string("agent_id")

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
