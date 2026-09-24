package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class PeerProgressKind {
    STARTING,
    THINKING,
    TOOL_STARTED,
    TOOL_FINISHED,
    RETRYING,
    RESPONDING,
    COMPLETED,
    FAILED,
}

data class PeerProgressEvent(
    val kind: PeerProgressKind,
    val subject: String = "",
)

data class PeerProgressSnapshot(
    val round: Int,
    val startedAtMillis: Long,
    val completedActions: Int,
    val current: PeerProgressEvent,
)

internal class PeerJsonEventParser(
    private val agentType: AgentType,
    private val onProgress: (PeerProgressEvent) -> Unit,
) {
    private val claudeTools = mutableMapOf<String, String>()
    private var latestFinalText: String? = null
    private var lastFailureDetail: String? = null

    fun accept(line: String): Boolean {
        val root =
            runCatching { JsonParser.parseString(line).asJsonObject }
                .getOrNull()
                ?: return false
        when (agentType) {
            AgentType.CODEX -> acceptCodex(root)
            AgentType.CLAUDE -> acceptClaude(root)
            AgentType.PI -> acceptPi(root)
        }
        return true
    }

    fun finalText(): String? = latestFinalText?.trim()?.takeIf(String::isNotEmpty)

    fun failureDetail(): String? = lastFailureDetail

    private fun acceptCodex(root: JsonObject) {
        val type = root.string("type") ?: return
        when (type) {
            "thread.started" -> emit(PeerProgressKind.STARTING)
            "turn.started" -> emit(PeerProgressKind.THINKING)
            "item.started" -> {
                val item = root.objectValue("item") ?: return
                toolSubject(item)?.let { emit(PeerProgressKind.TOOL_STARTED, it) }
            }

            "item.completed" -> {
                val item = root.objectValue("item") ?: return
                if (item.string("type") == "agent_message") {
                    item.string("text")?.let {
                        rememberFinalText(it)
                        emit(PeerProgressKind.RESPONDING)
                    }
                } else {
                    toolSubject(item)?.let { emit(PeerProgressKind.TOOL_FINISHED, it) }
                }
            }

            "turn.completed" -> emit(PeerProgressKind.COMPLETED)
            "turn.failed", "error" ->
                emit(
                    PeerProgressKind.FAILED,
                    root.string("message") ?: root.objectValue("error")?.string("message").orEmpty(),
                )
        }
    }

    private fun acceptClaude(root: JsonObject) {
        when (root.string("type")) {
            "system" -> {
                when (root.string("subtype")) {
                    "init" -> emit(PeerProgressKind.STARTING, root.string("model").orEmpty())
                    "api_retry" -> {
                        val attempt = root.integer("attempt")
                        val max = root.integer("max_retries")
                        emit(PeerProgressKind.RETRYING, listOfNotNull(attempt, max).joinToString("/"))
                    }
                }
            }

            "assistant" -> {
                val content = root.objectValue("message")?.arrayValue("content") ?: return
                var hasText = false
                content.forEach { block ->
                    val item = block.asObjectOrNull() ?: return@forEach
                    when (item.string("type")) {
                        "tool_use" -> {
                            val name = item.string("name").orEmpty()
                            val subject = toolSubject(name, item.objectValue("input"))
                            item.string("id")?.let { claudeTools[it] = subject }
                            emit(PeerProgressKind.TOOL_STARTED, subject)
                        }

                        "text" -> hasText = hasText || !item.string("text").isNullOrBlank()
                    }
                }
                if (hasText) emit(PeerProgressKind.RESPONDING)
            }

            "user" -> {
                val content = root.objectValue("message")?.arrayValue("content") ?: return
                content.forEach { block ->
                    val item = block.asObjectOrNull() ?: return@forEach
                    if (item.string("type") != "tool_result") return@forEach
                    val id = item.string("tool_use_id")
                    emit(PeerProgressKind.TOOL_FINISHED, claudeTools.remove(id).orEmpty())
                }
            }

            "result" -> {
                val resultText = root.string("result")
                resultText?.let(::rememberFinalText)
                if (root.boolean("is_error") == true) {
                    val detail =
                        root.arrayValue("errors")
                            ?.mapNotNull(JsonElement::stringOrNull)
                            ?.joinToString("; ")
                            ?.takeIf(String::isNotEmpty)
                            ?: resultText?.takeIf(String::isNotEmpty)
                            ?: root.string("terminal_reason")
                            ?: root.integer("api_error_status")?.toString()
                            ?: ""
                    emit(PeerProgressKind.FAILED, detail)
                } else {
                    emit(PeerProgressKind.COMPLETED)
                }
            }
        }
    }

    private fun acceptPi(root: JsonObject) {
        when (root.string("type")) {
            "session", "agent_start" -> emit(PeerProgressKind.STARTING)
            "turn_start" -> emit(PeerProgressKind.THINKING)
            "tool_execution_start" ->
                emit(
                    PeerProgressKind.TOOL_STARTED,
                    toolSubject(root.string("toolName").orEmpty(), root.objectValue("args")),
                )

            "tool_execution_end" ->
                emit(
                    PeerProgressKind.TOOL_FINISHED,
                    toolSubject(root.string("toolName").orEmpty(), root.objectValue("args")),
                )

            "message_end" -> {
                val message = root.objectValue("message") ?: return
                if (message.string("role") != "assistant") return
                assistantText(message)?.let {
                    rememberFinalText(it)
                    emit(PeerProgressKind.RESPONDING)
                }
            }

            "agent_end" -> emit(PeerProgressKind.COMPLETED)
        }
    }

    private fun emit(kind: PeerProgressKind, subject: String = "") {
        if (kind == PeerProgressKind.FAILED) lastFailureDetail = subject.take(MAX_FAILURE_DETAIL_CHARS)
        onProgress(PeerProgressEvent(kind, subject.take(MAX_PROGRESS_SUBJECT_CHARS)))
    }

    private fun rememberFinalText(text: String) {
        latestFinalText = text.take(MAX_PEER_OUTPUT_CHARS)
    }

    private fun toolSubject(item: JsonObject): String? {
        val type = item.string("type") ?: return null
        return when (type) {
            "command_execution" -> item.string("command")?.let { compactCommand("shell", it) }
            "mcp_tool_call" ->
                toolSubject(
                    item.string("tool") ?: item.string("name").orEmpty(),
                    item.objectValue("arguments") ?: item.objectValue("args"),
                )

            "web_search" -> toolSubject("web search", item.objectValue("query"))
            "file_change" -> "file change"
            else -> null
        }
    }

    private fun toolSubject(name: String, args: JsonObject?): String {
        val detail =
            args?.let {
                sequenceOf("file_path", "path", "pattern", "query", "command")
                    .firstNotNullOfOrNull(it::string)
            }
        return if (detail.isNullOrBlank()) name.ifBlank { "tool" } else compactCommand(name, detail)
    }

    private fun compactCommand(name: String, value: String): String {
        val compact = value.replace(Regex("\\s+"), " ").trim()
        return "$name: ${compact.take(MAX_TOOL_DETAIL_CHARS)}"
    }

    private fun assistantText(message: JsonObject): String? {
        val content = message.arrayValue("content") ?: return null
        val result = StringBuilder()
        for (block in content) {
            val text =
                block.asObjectOrNull()
                    ?.takeIf { it.string("type") == "text" }
                    ?.string("text")
                    ?: continue
            val room = MAX_PEER_OUTPUT_CHARS - result.length
            if (room <= 0) break
            result.append(text, 0, minOf(room, text.length))
        }
        return result.toString().takeIf(String::isNotBlank)
    }

    companion object {
        private const val MAX_PROGRESS_SUBJECT_CHARS = 300
        private const val MAX_FAILURE_DETAIL_CHARS = 4_000
        private const val MAX_TOOL_DETAIL_CHARS = 240
    }
}

private fun JsonObject.string(name: String): String? = get(name)?.stringOrNull()

private fun JsonObject.integer(name: String): Int? =
    get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asInt

private fun JsonObject.boolean(name: String): Boolean? =
    get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
        ?.asBoolean

private fun JsonObject.objectValue(name: String): JsonObject? = get(name)?.asObjectOrNull()

private fun JsonObject.arrayValue(name: String) =
    get(name)
        ?.takeIf(JsonElement::isJsonArray)
        ?.asJsonArray

private fun JsonElement.asObjectOrNull(): JsonObject? = takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonElement.stringOrNull(): String? =
    takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
