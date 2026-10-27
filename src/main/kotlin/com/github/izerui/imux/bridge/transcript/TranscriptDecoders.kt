package com.github.izerui.imux.bridge.transcript

import com.github.izerui.imux.bridge.core.SessionEvent
import com.github.izerui.imux.bridge.core.SessionEventKind
import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 把会话记录的一行解成桥接事件。全文保留，不截断、不压缩换行——
 * 这正是不能复用导航索引解析的原因（它为悬停卡片截断并压平了正文）。
 */
fun interface TranscriptDecoder {
    fun decode(line: String): List<SessionEvent>
}

object TranscriptDecoders {
    fun forAgent(agentType: AgentType): TranscriptDecoder =
        when (agentType) {
            AgentType.CLAUDE -> ClaudeTranscriptDecoder
            AgentType.CODEX -> CodexTranscriptDecoder
            AgentType.PI -> PiTranscriptDecoder
        }
}

object ClaudeTranscriptDecoder : TranscriptDecoder {
    override fun decode(line: String): List<SessionEvent> {
        val root = parseObject(line) ?: return emptyList()
        // 子代理、注入的元数据与压缩摘要都不是这条会话里人和助手说的话
        if (root.flag("isSidechain") || root.flag("isMeta") || root.flag("isCompactSummary")) return emptyList()
        val message = root.obj("message") ?: return emptyList()
        val content = message.get("content") ?: return emptyList()
        return when (message.str("role")) {
            "user" -> userEvents(content)
            // 一条消息的各个块分行写入且都带 stop_reason；只在含正文的那一行标记结束，结论才在结束之前
            "assistant" -> assistantEvents(content).let { events ->
                if (message.str("stop_reason") == "end_turn" && events.any { it.kind == SessionEventKind.ASSISTANT_TEXT }) {
                    events + SessionEvent(SessionEventKind.TURN_ENDED, "")
                } else {
                    events
                }
            }
            else -> emptyList()
        }
    }

    private fun userEvents(content: JsonElement): List<SessionEvent> {
        if (content.isJsonPrimitive) return listOfNotNull(userText(content.asString))
        return blocks(content).mapNotNull { block ->
            when (block.str("type")) {
                "text" -> block.str("text")?.let(::userText)
                "tool_result" -> SessionEvent(SessionEventKind.TOOL_FINISHED, "", block.str("tool_use_id"))
                else -> null
            }
        }
    }

    private fun userText(text: String): SessionEvent? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(CLAUDE_INTERRUPTED_PREFIX)) return null
        // 斜杠命令、后台任务通知等注入消息：不转发，但它们会开启新一轮
        if (isWrappedMarkup(trimmed)) return SessionEvent(SessionEventKind.TURN_STARTED, "")
        return SessionEvent(SessionEventKind.USER_TEXT, trimmed)
    }

    private fun assistantEvents(content: JsonElement): List<SessionEvent> {
        if (content.isJsonPrimitive) return listOfNotNull(assistantText(content.asString))
        return blocks(content).mapNotNull { block ->
            when (block.str("type")) {
                "text" -> block.str("text")?.let(::assistantText)
                "tool_use" -> SessionEvent(SessionEventKind.TOOL_STARTED, block.str("name").orEmpty(), block.str("id"))
                else -> null
            }
        }
    }

    // 与 SessionTitleRegenerator 中的私有常量同值；那边是标题逻辑的私有细节，不跨包引用
    private const val CLAUDE_INTERRUPTED_PREFIX = "[Request interrupted by user"
}

object CodexTranscriptDecoder : TranscriptDecoder {
    override fun decode(line: String): List<SessionEvent> {
        val root = parseObject(line) ?: return emptyList()
        // event_msg 里的 user_message/agent_message 是同一内容的镜像，只认 response_item；task_complete 标记本轮结束
        if (root.str("type") == "event_msg" && root.obj("payload")?.str("type") == "task_complete") {
            return listOf(SessionEvent(SessionEventKind.TURN_ENDED, ""))
        }
        if (root.str("type") != "response_item") return emptyList()
        val payload = root.obj("payload") ?: return emptyList()
        val type = payload.str("type") ?: return emptyList()
        // 工具种类会增加（function_call、custom_tool_call、web_search_call、tool_search_call…），按后缀认，
        // 漏认一种，它前面的过程说明就会混进最终回复
        return when {
            type == "message" -> messageEvents(payload)
            type.endsWith("_call") -> listOf(SessionEvent(SessionEventKind.TOOL_STARTED, payload.str("name") ?: type, payload.str("call_id")))
            type.endsWith("_output") -> listOf(SessionEvent(SessionEventKind.TOOL_FINISHED, "", payload.str("call_id")))
            else -> emptyList()
        }
    }

    private fun messageEvents(payload: JsonObject): List<SessionEvent> {
        val content = payload.get("content") ?: return emptyList()
        return when (payload.str("role")) {
            "user" -> {
                val inputs = blocks(content).filter { it.str("type") == "input_text" }.mapNotNull { it.str("text")?.trim() }.filter(String::isNotEmpty)
                val text = inputs.filter { !isWrappedMarkup(it) && !it.startsWith(CODEX_AGENTS_INSTRUCTIONS_PREFIX) }.joinToString("\n")
                when {
                    text.isNotEmpty() -> listOf(SessionEvent(SessionEventKind.USER_TEXT, text))
                    // 只有环境、AGENTS 等注入：不转发，但开启了新一轮
                    inputs.isNotEmpty() -> listOf(SessionEvent(SessionEventKind.TURN_STARTED, ""))
                    else -> emptyList()
                }
            }
            // 过程说明（commentary）不是最终回复；只有 final_answer 或没有标注的才算
            "assistant" -> if (payload.str("phase") == "commentary") {
                emptyList()
            } else {
                val text =
                    blocks(content)
                        .filter { it.str("type") == "output_text" }
                        .mapNotNull { it.str("text") }
                        .joinToString("\n")
                listOfNotNull(assistantText(text))
            }
            else -> emptyList()
        }
    }

    // 与 SessionTitleRegenerator 中的私有常量同值
    private const val CODEX_AGENTS_INSTRUCTIONS_PREFIX = "# AGENTS.md instructions"
}

object PiTranscriptDecoder : TranscriptDecoder {
    override fun decode(line: String): List<SessionEvent> {
        val root = parseObject(line) ?: return emptyList()
        if (root.str("type") != "message") return emptyList()
        val message = root.obj("message") ?: return emptyList()
        return when (message.str("role")) {
            "user" -> {
                val content = message.get("content") ?: return emptyList()
                val text =
                    if (content.isJsonPrimitive) {
                        content.asString
                    } else {
                        blocks(content).filter { it.str("type") == "text" }.mapNotNull { it.str("text") }.joinToString("\n")
                    }
                listOfNotNull(text.trim().takeIf(String::isNotEmpty)?.let { SessionEvent(SessionEventKind.USER_TEXT, it) })
            }
            "assistant" -> {
                val events =
                    blocks(message.get("content")).mapNotNull { block ->
                        when (block.str("type")) {
                            "text" -> block.str("text")?.let(::assistantText)
                            "toolCall" -> SessionEvent(SessionEventKind.TOOL_STARTED, block.str("name").orEmpty(), block.str("id"))
                            else -> null
                        }
                    }
                // stop 表示本轮正常结束；toolUse 还要继续，error/aborted 没有结论
                if (message.str("stopReason") == "stop") events + SessionEvent(SessionEventKind.TURN_ENDED, "") else events
            }
            "toolResult" -> listOf(SessionEvent(SessionEventKind.TOOL_FINISHED, "", message.str("toolCallId")))
            else -> emptyList()
        }
    }
}

private fun assistantText(text: String): SessionEvent? =
    text.takeIf(String::isNotBlank)?.let { SessionEvent(SessionEventKind.ASSISTANT_TEXT, it) }

/** 形如 `<command-name>…</command-name>` 的整段标记是 CLI 注入的，不是人写的。 */
private fun isWrappedMarkup(text: String): Boolean = text.startsWith("<") && text.endsWith(">")

private fun parseObject(line: String): JsonObject? =
    runCatching { JsonParser.parseString(line) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

private fun blocks(content: JsonElement?): List<JsonObject> =
    content?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
        ?: emptyList()

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.flag(key: String): Boolean =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
