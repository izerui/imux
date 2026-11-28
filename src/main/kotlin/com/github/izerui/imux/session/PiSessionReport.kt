package com.github.izerui.imux.session

/** pi 扩展报上来的一条：某个标签页此刻在跑哪个会话。 */
data class PiSessionReport(
    val type: PiReportType,
    val tabId: String,
    val sessionId: String,
    val cwd: String,
    val stopReason: PiStopReason? = null,
    val messageId: String? = null,
    val failedMcpServers: Set<String>? = null,
    val activeTools: List<String>? = null,
    val contextPercent: Int? = null,
    val skillNames: List<String>? = null,
    val skillLocations: Map<String, String> = emptyMap(),
    val skillDescriptions: Map<String, String> = emptyMap(),
    val sequence: Long? = null,
    val timestampMillis: Long? = null,
)

enum class PiReportType(
    val wireValue: String,
) {
    SESSION_START("session_start"),
    USER_MESSAGE("user_message"),
    AGENT_SETTLED("agent_settled"),
    UI_PROMPT_START("ui_prompt_start"),
    UI_PROMPT_END("ui_prompt_end"),
    ;

    companion object {
        fun fromWire(value: String?): PiReportType? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class PiStopReason(
    val wireValue: String,
) {
    STOP("stop"),
    LENGTH("length"),
    TOOL_USE("toolUse"),
    ERROR("error"),
    ABORTED("aborted"),
    ;

    companion object {
        fun fromWire(value: String?): PiStopReason? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * 解析上报体。
 *
 * 手写扫描而不是引 JSON 库：报文只有几个字符串字段，且这段代码跑在 netty 的
 * EventLoop 线程上，越简单越好。任何不合形状的输入一律返回 null——
 * 上报来自另一个进程，不能假定它的内容。
 */
internal fun parsePiReport(body: String): PiSessionReport? {
    val type = PiReportType.fromWire(JsonLineScanner.topLevelStringValue(body, "type")) ?: return null
    val tabId = JsonLineScanner.topLevelStringValue(body, "tabId")?.takeIf { it.isNotBlank() } ?: return null
    val sessionId =
        JsonLineScanner
            .topLevelStringValue(body, "sessionId")
            ?.takeIf(::looksLikePiSessionId)
            ?: return null
    val cwd = JsonLineScanner.topLevelStringValue(body, "cwd")?.takeIf { it.isNotBlank() } ?: return null
    val rawStopReason = JsonLineScanner.topLevelStringValue(body, "stopReason")
    val stopReason = rawStopReason?.let(PiStopReason::fromWire)
    if (rawStopReason != null && stopReason == null) return null
    val messageId = JsonLineScanner.topLevelStringValue(body, "messageId")?.takeIf { it.isNotBlank() }

    when (type) {
        PiReportType.SESSION_START -> {
            Unit
        }

        PiReportType.USER_MESSAGE, PiReportType.UI_PROMPT_START, PiReportType.UI_PROMPT_END -> {
            if (stopReason != null) return null
        }

        PiReportType.AGENT_SETTLED -> {
            if (
                (stopReason != PiStopReason.ERROR && stopReason != PiStopReason.LENGTH) ||
                messageId == null
            ) {
                return null
            }
        }
    }
    fun positiveLong(key: String) = JsonLineScanner.topLevelStringValue(body, key)?.toLongOrNull()?.takeIf { it > 0 }
    val sequence = positiveLong("sequence")
    val timestamp = positiveLong("timestampMillis")
    if (type in setOf(PiReportType.UI_PROMPT_START, PiReportType.UI_PROMPT_END) &&
        (sequence == null || timestamp == null)) return null
    val failures = if (type == PiReportType.SESSION_START) {
        JsonLineScanner.topLevelStringValue(body, "failedMcpServers")?.split(',')?.filter(String::isNotEmpty)?.toSet()
    } else null
    val tools = JsonLineScanner.topLevelStringValue(body, "activeTools")?.split(',')
        ?.filter { it.isNotBlank() }?.distinct()?.sorted()
    val percent = JsonLineScanner.topLevelStringValue(body, "contextPercent")?.toIntOrNull()?.takeIf { it in 0..100 }
    val skills = JsonLineScanner.topLevelStringValue(body, "skillNames")?.lines()?.filter(String::isNotBlank)?.distinct()?.sorted()
    val metadata = if (skills.isNullOrEmpty()) null else runCatching { com.google.gson.JsonParser.parseString(body).asJsonObject }.getOrNull()
    fun skillStrings(key: String) = metadata?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet().orEmpty()
        .filter { it.key in skills.orEmpty() && it.value.isJsonPrimitive && it.value.asJsonPrimitive.isString }
        .associate { it.key to it.value.asString }
    return PiSessionReport(type, tabId, sessionId, cwd, stopReason, messageId, failures, tools, percent, skills,
        skillStrings("skillLocations"), skillStrings("skillDescriptions"), sequence, timestamp)
}

private fun looksLikePiSessionId(value: String): Boolean = PI_SESSION_ID.matches(value)

private val PI_SESSION_ID = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$")
