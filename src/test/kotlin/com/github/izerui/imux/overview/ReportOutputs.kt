package com.github.izerui.imux.overview

import com.github.izerui.imux.overview.report.parseAgentReport
import com.google.gson.JsonArray
import com.google.gson.JsonObject

private val NODE_LINE = Regex("""^\[(\w+)(?:\|([^\]]*))?]\s*(.+)$""")

/**
 * 把节点行（`[TYPE|时间] 文字`，旧时间线格式）包成智能体输出的报告 JSON，只含时间线。
 * 以时间线为主的测试借它模拟智能体的回答；不像节点的行忽略，一个节点都没有时输出空时间线。
 */
internal fun reportOutput(lines: String): String {
    val timeline = JsonArray()
    lines.lines().map(String::trim).forEach { line ->
        val match = NODE_LINE.matchEntire(line) ?: return@forEach
        timeline.add(JsonObject().apply {
            addProperty("type", match.groupValues[1])
            match.groupValues[2].takeIf { it.isNotEmpty() }?.let { addProperty("time", it) }
            addProperty("text", match.groupValues[3].trim())
        })
    }
    return JsonObject().apply { add("timeline", timeline) }.toString()
}

/** 节点行经报告 JSON 解析后的时间线；解析失败为空。 */
internal fun timelineOf(lines: String): List<OverviewNode> = parseAgentReport(reportOutput(lines))?.timeline.orEmpty()
