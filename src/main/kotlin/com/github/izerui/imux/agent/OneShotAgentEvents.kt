package com.github.izerui.imux.agent

import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class AgentProgressKind {
    STARTING,
    THINKING,
    TOOL_STARTED,
    TOOL_FINISHED,
    RETRYING,
    RESPONDING,
    COMPLETED,
    FAILED,
}

data class AgentProgressEvent(
    val kind: AgentProgressKind,
    val subject: String = "",
)

/**
 * 解析三种 CLI 的 JSON 事件流（Claude `stream-json`、Codex `--json`、Pi `--mode json`），
 * 产出进度事件、最终回答和失败详情。
 *
 * 参照副驾驶的事件解析另写一份，不依赖 `peer` 包：两边各自演进，互不影响。
 */
internal class AgentJsonEventParser(
    private val agentType: AgentType,
    private val onProgress: (AgentProgressEvent) -> Unit,
    private val maxOutputChars: Int = MAX_AGENT_OUTPUT_CHARS,
) {
    private val claudeTools = mutableMapOf<String, String>()
    private var latestFinalText: String? = null
    /**
     * 当前仍然有效的失败；确认恢复（Codex turn.completed、Claude 成功的 result）时清除。
     * 进度里的 FAILED 只是“这次尝试失败了”，CLI 可能自动重试，不能直接当最终结果。
     */
    private var lastFailureDetail: String? = null

    /** Pi 最后一条助手消息的停止原因；null 表示还没有助手消息。 */
    private var piLastStopReason: String? = null
    private var piLastErrorMessage: String? = null
    private var piSawAssistant = false

    /** Codex 当前这一轮是否已收到 turn.completed；没完成前的 agent_message 只是中途输出。 */
    private var codexTurnCompleted = false

    /** Claude `system/init` 事件里的会话 ID；用于调用结束后清理 Claude 写下的临时文件。 */
    var claudeSessionId: String? = null
        private set

    /** 最终回答超过 [maxOutputChars]：调用方应当报错，而不是拿截断的前半段当完整结果。 */
    var outputOverflowed: Boolean = false
        private set

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

    /**
     * 失败详情；null 表示正常完成。
     * Pi 即使没有输出 agent_end 也按最后一条助手消息的停止原因判断，不依赖事件是否完整。
     */
    fun failureDetail(): String? = lastFailureDetail ?: piTerminalFailure() ?: codexIncomplete()

    private fun acceptCodex(root: JsonObject) {
        when (root.string("type") ?: return) {
            "thread.started" -> emit(AgentProgressKind.STARTING)
            "turn.started" -> {
                // 新一轮的完成状态、回答和溢出标记都只属于这一轮，不能沿用上一轮的
                codexTurnCompleted = false
                latestFinalText = null
                outputOverflowed = false
                emit(AgentProgressKind.THINKING)
            }

            "item.started" -> {
                val item = root.objectValue("item") ?: return
                toolSubject(item)?.let {
                    // 回答之后又开始调用工具：前面那条只是中途输出，不能当最终回答
                    latestFinalText = null
                    outputOverflowed = false
                    emit(AgentProgressKind.TOOL_STARTED, it)
                }
            }

            "item.completed" -> {
                val item = root.objectValue("item") ?: return
                if (item.string("type") == "agent_message") {
                    item.string("text")?.let {
                        rememberFinalText(it)
                        emit(AgentProgressKind.RESPONDING)
                    }
                } else {
                    toolSubject(item)?.let { emit(AgentProgressKind.TOOL_FINISHED, it) }
                }
            }

            "turn.completed" -> {
                // 之前的 error（如流断开重连）已经恢复：这一轮正常完成了
                codexTurnCompleted = true
                lastFailureDetail = null
                emit(AgentProgressKind.COMPLETED)
            }
            "turn.failed", "error" ->
                fail(root.string("message") ?: root.objectValue("error")?.string("message").orEmpty())
        }
    }

    private fun acceptClaude(root: JsonObject) {
        when (root.string("type")) {
            "system" -> {
                when (root.string("subtype")) {
                    "init" -> {
                        root.string("session_id")?.let { claudeSessionId = it }
                        emit(AgentProgressKind.STARTING, root.string("model").orEmpty())
                    }

                    "api_retry" -> {
                        val attempt = root.integer("attempt")
                        val max = root.integer("max_retries")
                        emit(AgentProgressKind.RETRYING, listOfNotNull(attempt, max).joinToString("/"))
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
                            val subject = toolSubject(item.string("name").orEmpty(), item.objectValue("input"))
                            item.string("id")?.let { claudeTools[it] = subject }
                            emit(AgentProgressKind.TOOL_STARTED, subject)
                        }

                        "text" -> hasText = hasText || !item.string("text").isNullOrBlank()
                    }
                }
                if (hasText) emit(AgentProgressKind.RESPONDING)
            }

            "user" -> {
                val content = root.objectValue("message")?.arrayValue("content") ?: return
                content.forEach { block ->
                    val item = block.asObjectOrNull() ?: return@forEach
                    if (item.string("type") != "tool_result") return@forEach
                    emit(AgentProgressKind.TOOL_FINISHED, claudeTools.remove(item.string("tool_use_id")).orEmpty())
                }
            }

            "result" -> {
                root.string("session_id")?.let { if (claudeSessionId == null) claudeSessionId = it }
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
                    fail(detail)
                } else if (root.string("stop_reason") in CLAUDE_INCOMPLETE_STOP_REASONS) {
                    // is_error 为 false 但回答被输出上限截断或被拒绝：不能当作完整回答
                    fail("Claude stopped with stop_reason=${root.string("stop_reason")}")
                } else {
                    lastFailureDetail = null
                    emit(AgentProgressKind.COMPLETED)
                }
            }
        }
    }

    private fun acceptPi(root: JsonObject) {
        when (root.string("type")) {
            "session", "agent_start" -> emit(AgentProgressKind.STARTING)
            "turn_start" -> emit(AgentProgressKind.THINKING)
            "tool_execution_start" ->
                emit(AgentProgressKind.TOOL_STARTED, toolSubject(root.string("toolName").orEmpty(), root.objectValue("args")))

            "tool_execution_end" ->
                emit(AgentProgressKind.TOOL_FINISHED, toolSubject(root.string("toolName").orEmpty(), root.objectValue("args")))

            "message_end" -> {
                val message = root.objectValue("message") ?: return
                if (message.string("role") != "assistant") return
                // 停止原因和正文一起按最后一条覆盖：error/length 之后 Pi 可能自动重试，
                // 重试那条正文为空时也要清掉旧答案，不能拿前面半截的回答顶替
                piSawAssistant = true
                piLastStopReason = message.string("stopReason")
                piLastErrorMessage = message.string("errorMessage")
                val text = assistantText(message)
                if (text != null) {
                    rememberFinalText(text)
                    emit(AgentProgressKind.RESPONDING)
                } else {
                    latestFinalText = null
                    outputOverflowed = false
                }
            }

            // 只报告这次尝试的结果：Pi 失败后会先发 agent_end 再自动重试，
            // 最终成败由 failureDetail() 按最后一条助手消息判断，这里不记失败状态
            "agent_end" ->
                piTerminalFailure()?.let { emit(AgentProgressKind.FAILED, it) } ?: emit(AgentProgressKind.COMPLETED)
        }
    }

    /**
     * Pi 最后一条助手消息不是正常结束（`stop`）时的失败详情。
     * `length` 截断、`error`、`aborted` 是失败；`toolUse`/`pending`/`deferred` 说明还没给出最终回答。
     * 没有 stopReason 的旧版本按正常结束处理。
     */
    private fun piTerminalFailure(): String? {
        if (agentType != AgentType.PI || !piSawAssistant) return null
        val reason = piLastStopReason ?: return null
        if (reason == "stop") return null
        return "Pi stopped with stopReason=$reason" + piLastErrorMessage?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
    }

    /** Codex 只有收到 turn.completed 才算这一轮正常结束；事件流中途断掉时，已收到的回答不能用。 */
    private fun codexIncomplete(): String? =
        if (agentType == AgentType.CODEX && !codexTurnCompleted) "Codex turn did not complete (no turn.completed event)" else null

    private fun emit(kind: AgentProgressKind, subject: String = "") {
        onProgress(AgentProgressEvent(kind, subject.take(MAX_PROGRESS_SUBJECT_CHARS)))
    }

    /** 记下当前有效的失败并报告进度；之后确认恢复时由各 CLI 的完成事件清除。 */
    private fun fail(detail: String) {
        lastFailureDetail = detail.take(MAX_FAILURE_DETAIL_CHARS)
        emit(AgentProgressKind.FAILED, detail)
    }

    private fun rememberFinalText(text: String) {
        // 每次都按最新的回答判断：只有最终采用的那一份超限才算溢出
        outputOverflowed = text.length > maxOutputChars
        latestFinalText = text.take(maxOutputChars)
    }

    private fun toolSubject(item: JsonObject): String? =
        when (item.string("type") ?: return null) {
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

    /** Pi 的回答可能分成多个 text 块：全部拼起来再判断是否超限，不在拼接时截断。 */
    private fun assistantText(message: JsonObject): String? {
        val content = message.arrayValue("content") ?: return null
        val result = StringBuilder()
        for (block in content) {
            val text =
                block.asObjectOrNull()
                    ?.takeIf { it.string("type") == "text" }
                    ?.string("text")
                    ?: continue
            result.append(text)
        }
        return result.toString().takeIf(String::isNotBlank)
    }

    private companion object {
        const val MAX_PROGRESS_SUBJECT_CHARS = 300
        const val MAX_FAILURE_DETAIL_CHARS = 4_000
        const val MAX_TOOL_DETAIL_CHARS = 240
    }
}

internal const val MAX_AGENT_OUTPUT_CHARS = 64_000

/** Claude result 的 stop_reason：截断和拒绝说明最终回答不完整；end_turn、stop_sequence 或缺省都算正常结束。 */
private val CLAUDE_INCOMPLETE_STOP_REASONS = setOf("max_tokens", "refusal")

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
