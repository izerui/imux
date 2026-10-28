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
            "user" -> userEvents(content, humanOrigin(root))
            // 一条消息的各个块分行写入且都带 stop_reason；只在含正文的那一行标记结束，结论才在结束之前。
            // 除了还要调工具（tool_use、pause_turn），其余收尾都算本轮结束：end_turn 之外，
            // 输出超长截断、API 报错也会带着正文停下，漏标的话这一轮就没有结论发出
            "assistant" -> assistantEvents(content).let { events ->
                if (claudeTurnEnds(message.str("stop_reason")) && events.any { it.kind == SessionEventKind.ASSISTANT_TEXT }) {
                    events + SessionEvent(SessionEventKind.TURN_ENDED, "")
                } else {
                    events
                }
            }
            else -> emptyList()
        }
    }

    /**
     * 这条用户消息是不是从输入框提交的（人、副驾驶、手机）。Claude Code 在 `origin.kind` 里写明来源：
     * 输入是 `human`；后台任务通知、命令输出等是别的值或没有。不兼容 2.1.28 以前还没有这个字段的版本。
     */
    private fun humanOrigin(root: JsonObject): Boolean = root.obj("origin")?.str("kind") == "human"

    private fun userEvents(
        content: JsonElement,
        human: Boolean,
    ): List<SessionEvent> {
        if (content.isJsonPrimitive) return listOfNotNull(userText(content.asString, human))
        return blocks(content).mapNotNull { block ->
            when (block.str("type")) {
                "text" -> block.str("text")?.let { userText(it, human) }
                "tool_result" -> SessionEvent(SessionEventKind.TOOL_FINISHED, "", block.str("tool_use_id"))
                else -> null
            }
        }
    }

    private fun userText(
        text: String,
        human: Boolean,
    ): SessionEvent? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(CLAUDE_INTERRUPTED_PREFIX)) return null
        // 不是输入的（后台任务通知等）：不转发，但它们会开启新一轮
        if (!human) return SessionEvent(SessionEventKind.TURN_STARTED, "")
        // 输入的斜杠命令记成 <command-name> 标记，转成原样的“/命令 参数”
        return SessionEvent(SessionEventKind.USER_TEXT, slashCommand(trimmed) ?: trimmed)
    }

    private fun slashCommand(text: String): String? {
        val name = COMMAND_NAME.find(text)?.groupValues?.get(1)?.trim() ?: return null
        val args = COMMAND_ARGS.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        return if (args.isEmpty()) name else "$name $args"
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
        if (root.str("type") == "event_msg") return eventMsg(root.obj("payload") ?: return emptyList())
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

    /**
     * 用户输入以 Codex 自己记的用户消息事件（item_completed 里的 UserMessage，0.146 起）为准。
     * 它只含从输入框提交的内容（人、副驾驶、手机）；response_item 里的用户消息还混着环境信息、
     * AGENTS.md、子代理通知等发给模型的注入，不用来判断。task_started / task_complete 标记一轮的起止。
     */
    private fun eventMsg(payload: JsonObject): List<SessionEvent> =
        when (payload.str("type")) {
            "task_started" -> listOf(SessionEvent(SessionEventKind.TURN_STARTED, ""))
            "task_complete" -> listOf(SessionEvent(SessionEventKind.TURN_ENDED, ""))
            "item_completed" -> {
                val item = payload.obj("item")
                if (item?.str("type") != "UserMessage") {
                    emptyList()
                } else {
                    val text = blocks(item.get("content")).filter { it.str("type") == "text" }.mapNotNull { it.str("text") }.joinToString("\n").trim()
                    listOfNotNull(text.takeIf(String::isNotEmpty)?.let { SessionEvent(SessionEventKind.USER_TEXT, it) })
                }
            }
            else -> emptyList()
        }

    private fun messageEvents(payload: JsonObject): List<SessionEvent> {
        val content = payload.get("content") ?: return emptyList()
        return when (payload.str("role")) {
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
                // stop 正常结束，length 截断、error 出错也已停下；toolUse 还要继续，aborted 是被打断
                if (message.str("stopReason") in PI_TURN_ENDS) events + SessionEvent(SessionEventKind.TURN_ENDED, "") else events
            }
            "toolResult" -> listOf(SessionEvent(SessionEventKind.TOOL_FINISHED, "", message.str("toolCallId")))
            else -> emptyList()
        }
    }
}

private val PI_TURN_ENDS = setOf("stop", "length", "error")

private fun claudeTurnEnds(stopReason: String?): Boolean = stopReason != null && stopReason != "tool_use" && stopReason != "pause_turn"

private fun assistantText(text: String): SessionEvent? =
    text.takeIf(String::isNotBlank)?.let { SessionEvent(SessionEventKind.ASSISTANT_TEXT, it) }

private val COMMAND_NAME = Regex("""<command-name>(.*?)</command-name>""", RegexOption.DOT_MATCHES_ALL)
private val COMMAND_ARGS = Regex("""<command-args>(.*?)</command-args>""", RegexOption.DOT_MATCHES_ALL)

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
