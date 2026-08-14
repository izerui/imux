package com.github.izerui.imux.session

/** pi 扩展报上来的一条：某个标签页此刻在跑哪个会话。 */
data class PiSessionReport(
    val type: PiReportType,
    val tabId: String,
    val sessionId: String,
    val cwd: String,
    val stopReason: PiStopReason? = null,
    val messageId: String? = null,
)

enum class PiReportType(val wireValue: String) {
    SESSION_START("session_start"),
    AGENT_SETTLED("agent_settled"),
    ;

    companion object {
        fun fromWire(value: String?): PiReportType? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class PiStopReason(val wireValue: String) {
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
    val sessionId = JsonLineScanner.topLevelStringValue(body, "sessionId")
        ?.takeIf(::looksLikePiSessionId)
        ?: return null
    val cwd = JsonLineScanner.topLevelStringValue(body, "cwd")?.takeIf { it.isNotBlank() } ?: return null
    val rawStopReason = JsonLineScanner.topLevelStringValue(body, "stopReason")
    val stopReason = rawStopReason?.let(PiStopReason::fromWire)
    if (rawStopReason != null && stopReason == null) return null
    val messageId = JsonLineScanner.topLevelStringValue(body, "messageId")?.takeIf { it.isNotBlank() }

    if (
        type == PiReportType.AGENT_SETTLED &&
        (
            stopReason != PiStopReason.ERROR &&
                stopReason != PiStopReason.LENGTH ||
                messageId == null
            )
    ) {
        return null
    }
    return PiSessionReport(type, tabId, sessionId, cwd, stopReason, messageId)
}

private fun looksLikePiSessionId(value: String): Boolean =
    PI_SESSION_ID.matches(value)

private val PI_SESSION_ID = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$")
