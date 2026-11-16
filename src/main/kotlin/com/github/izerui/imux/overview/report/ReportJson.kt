package com.github.izerui.imux.overview.report

import com.github.izerui.imux.overview.NodeSymbol
import com.github.izerui.imux.overview.OverviewNode
import com.github.izerui.imux.overview.parseNodeTime
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 各节条数上限：提示词里写明，解析时再截一次，页面不会被超长输出撑爆。 */
internal const val MAX_TIMELINE = 20
internal const val MAX_DECISIONS = 6
internal const val MAX_CHANGES = 6
internal const val MAX_SNIPPET_LINES = 20
internal const val MAX_CHECKS = 8
internal const val MAX_ISSUES = 8
internal const val MAX_NEXT_STEPS = 8

/**
 * 解析智能体的输出。允许 JSON 前后夹着说明文字或 ```json 围栏；取不出对象、或清洗后时间线为空（等于没总结出东西）返回 null。
 * 单项缺字段、类型不对只丢这一项；枚举值越界按各自的兜底处理，不让整份报告失败。
 */
internal fun parseAgentReport(output: String): SessionReport? = candidateObjects(output).firstNotNullOfOrNull(::reportFromJson)

/** [agentReportJson] 的逆，同样清洗；供缓存读取。时间线为空返回 null。 */
internal fun reportFromJson(obj: JsonObject): SessionReport? {
    val report =
        SessionReport(
            goal = obj.text("goal"),
            summary = obj.text("summary"),
            status = obj.word("status")?.let { value -> ReportStatus.entries.firstOrNull { it.wire == value } },
            decisions = obj.objects("decisions").mapNotNull { item ->
                ReportDecision(item.text("title") ?: return@mapNotNull null, item.text("reason"))
            }.take(MAX_DECISIONS),
            changes = obj.objects("changes").mapNotNull { item ->
                val lines = (item.get("snippet") as? JsonObject)?.objects("lines").orEmpty().mapNotNull { line ->
                    val text = line.raw("text") ?: return@mapNotNull null
                    SnippetLine(LineKind.entries.firstOrNull { it.wire == line.word("kind") } ?: LineKind.CTX, text)
                }
                ReportChange(item.text("file") ?: return@mapNotNull null, item.text("summary"), lines.take(MAX_SNIPPET_LINES), item.text("note"))
            }.take(MAX_CHANGES),
            verification = obj.objects("verification").mapNotNull { item ->
                val result = CheckResult.entries.firstOrNull { it.wire == item.word("result") } ?: CheckResult.NOT_RUN
                ReportCheck(item.text("what") ?: return@mapNotNull null, result, item.text("detail"))
            }.take(MAX_CHECKS),
            timeline = obj.objects("timeline").mapNotNull { item ->
                val symbol = NodeSymbol.entries.firstOrNull { it.name == item.text("type")?.uppercase() } ?: NodeSymbol.MAIN
                OverviewNode(symbol, item.text("text") ?: return@mapNotNull null, item.text("time")?.let(::parseNodeTime))
            }.take(MAX_TIMELINE),
            issues = obj.objects("issues").mapNotNull { item ->
                val severity = Severity.entries.firstOrNull { it.wire == item.word("severity") } ?: Severity.LOW
                ReportIssue(severity, item.text("text") ?: return@mapNotNull null)
            }.take(MAX_ISSUES),
            nextSteps = obj.strings("nextSteps").take(MAX_NEXT_STEPS),
        )
    return report.takeIf { it.timeline.isNotEmpty() }
}

/** 只含智能体写的各节，不含 [SessionReport.facts]：用于增量提示词里的「上次的报告」和缓存。 */
internal fun agentReportJson(report: SessionReport): JsonObject =
    JsonObject().apply {
        report.goal?.let { addProperty("goal", it) }
        report.summary?.let { addProperty("summary", it) }
        report.status?.let { addProperty("status", it.wire) }
        add("decisions", array(report.decisions) { item ->
            JsonObject().apply {
                addProperty("title", item.title)
                item.reason?.let { addProperty("reason", it) }
            }
        })
        add("changes", array(report.changes) { item ->
            JsonObject().apply {
                addProperty("file", item.file)
                item.summary?.let { addProperty("summary", it) }
                if (item.lines.isNotEmpty()) {
                    add("snippet", JsonObject().apply {
                        add("lines", array(item.lines) { line ->
                            JsonObject().apply {
                                addProperty("kind", line.kind.wire)
                                addProperty("text", line.text)
                            }
                        })
                    })
                }
                item.note?.let { addProperty("note", it) }
            }
        })
        add("verification", array(report.verification) { item ->
            JsonObject().apply {
                addProperty("what", item.what)
                addProperty("result", item.result.wire)
                item.detail?.let { addProperty("detail", it) }
            }
        })
        add("timeline", array(report.timeline) { node ->
            JsonObject().apply {
                addProperty("type", node.symbol.name)
                node.time?.let { addProperty("time", it.toString()) }
                addProperty("text", node.text)
            }
        })
        add("issues", array(report.issues) { item ->
            JsonObject().apply {
                addProperty("severity", item.severity.wire)
                addProperty("text", item.text)
            }
        })
        add("nextSteps", JsonArray().apply { report.nextSteps.forEach(::add) })
    }

internal fun factsJson(facts: ReportFacts): JsonObject =
    JsonObject().apply {
        facts.durationMinutes?.let { addProperty("durationMinutes", it) }
        add("files", JsonArray().apply { facts.files.forEach(::add) })
    }

internal fun factsFromJson(obj: JsonObject?): ReportFacts {
    obj ?: return ReportFacts()
    val duration = obj.get("durationMinutes")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
    return ReportFacts(duration, obj.strings("files"))
}

/** 候选的 JSON 对象：整体是对象先给它；再从每个 `{` 起按括号配对（跳过字符串里的括号）截出一段试着解析。由调用方取第一个是报告的。 */
private fun candidateObjects(output: String): Sequence<JsonObject> =
    sequence {
        parseObject(output.trim())?.let { yield(it) }
        var start = output.indexOf('{')
        while (start >= 0) {
            val end = matchingBrace(output, start)
            // 配不上（说明文字里多了个 `{`）、或截出的不是报告（说明里的一小段 JSON 示例），都从下一个 `{` 再试
            if (end >= 0) parseObject(output.substring(start, end + 1))?.let { yield(it) }
            start = output.indexOf('{', start + 1)
        }
    }

private fun parseObject(text: String): JsonObject? =
    runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun matchingBrace(text: String, start: Int): Int {
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        when {
            escaped -> escaped = false
            inString && c == '\\' -> escaped = true
            c == '"' -> inString = !inString
            inString -> Unit
            c == '{' -> depth++
            c == '}' -> if (--depth == 0) return i
        }
    }
    return -1
}

/** 字符串字段：去首尾空白，空的视同缺失；不是字符串（数字、对象）也视同缺失。 */
private fun JsonObject.text(key: String): String? = raw(key)?.trim()?.ifEmpty { null }

/** 枚举取值：不区分大小写，智能体写成 `Done`、`PASSED` 也认。 */
private fun JsonObject.word(key: String): String? = text(key)?.lowercase()

/** 原样的字符串字段，不去空白：代码行的缩进要保留。 */
private fun JsonObject.raw(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.objects(key: String): List<JsonObject> =
    (get(key) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

private fun JsonObject.strings(key: String): List<String> =
    (get(key) as? JsonArray)?.mapNotNull { element ->
        element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()?.ifEmpty { null }
    }.orEmpty()

private fun <T> array(items: List<T>, toJson: (T) -> JsonElement): JsonArray =
    JsonArray().apply { items.forEach { add(toJson(it)) } }
