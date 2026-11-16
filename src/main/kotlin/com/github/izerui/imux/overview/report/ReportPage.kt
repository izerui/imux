package com.github.izerui.imux.overview.report

import com.github.izerui.imux.overview.safeSessionFileName
import com.github.izerui.imux.project.ensureImuxIgnored
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

/** 内置报告模板在类路径上的位置。 */
internal const val REPORT_TEMPLATE = "/report/session-report.html"

/** 报告页里由插件填的会话信息。 */
internal data class ReportSession(val title: String, val agent: String, val project: String, val generatedAt: Instant)

/** 一次写报告文件的结果：[changed] 为 false 表示内容与已有文件相同，没有重写。 */
internal data class ReportWrite(val path: Path, val changed: Boolean)

private const val PLACEHOLDER = """<script id="imux-data" type="application/json">{}</script>"""

private val GSON = GsonBuilder().disableHtmlEscaping().create()

/** 页面数据：插件字段（版本、语言、会话、时长、改动文件）加上智能体写的各节。时长未知时不写 `stats`。 */
internal fun pageDataJson(report: SessionReport, lang: String, session: ReportSession): String {
    val data = JsonObject().apply {
        addProperty("version", 1)
        addProperty("lang", lang)
        add("session", JsonObject().apply {
            addProperty("title", session.title)
            addProperty("agent", session.agent)
            addProperty("project", session.project)
            addProperty("generatedAt", session.generatedAt.toString())
        })
        report.facts.durationMinutes?.let { minutes -> add("stats", JsonObject().apply { addProperty("durationMinutes", minutes) }) }
        add("files", GSON.toJsonTree(report.facts.files))
        agentReportJson(report).entrySet().forEach { (key, value) -> add(key, value) }
    }
    return GSON.toJson(data)
}

/**
 * 把数据填进模板唯一的占位，得到自包含的报告页。数据里的 `<`、`>`、`&` 换成 JSON 转义，
 * 正文里的 `</script>` 截不断脚本，页面脚本只按文字显示。`<head>` 里记下模板摘要，模板随插件升级时文件内容随之变化。
 */
internal fun renderReportPage(template: String, dataJson: String): String {
    val first = template.indexOf(PLACEHOLDER)
    check(first >= 0 && template.indexOf(PLACEHOLDER, first + 1) < 0) { "报告模板里的数据占位必须恰好一处" }
    val escaped = dataJson.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    val page = template.replace(PLACEHOLDER, """<script id="imux-data" type="application/json">$escaped</script>""")
    val meta = """<meta name="imux-template" content="${templateDigest(template)}">"""
    return page.replaceFirst("<head>", "<head>\n$meta")
}

private fun templateDigest(template: String): String =
    MessageDigest.getInstance("SHA-256").digest(template.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }

/** `.imux/reports/` 下的报告文件，文件名与轨迹缓存共用 [safeSessionFileName]。 */
internal fun reportFile(projectRoot: Path, sessionKey: String): Path =
    projectRoot.resolve(".imux").resolve("reports").resolve(safeSessionFileName(sessionKey) + ".html")

/** 先确保 `.imux/` 不进 Git；内容与已有文件相同时不写，否则写临时文件后原子替换。 */
internal fun writeReportFile(projectRoot: Path, sessionKey: String, html: String): ReportWrite {
    // 先于内容比较：.gitignore 被删了，报告没变也要补回
    ensureImuxIgnored(projectRoot)
    val target = reportFile(projectRoot, sessionKey)
    val bytes = html.toByteArray(Charsets.UTF_8)
    if (Files.isRegularFile(target) && Files.readAllBytes(target).contentEquals(bytes)) return ReportWrite(target, changed = false)
    Files.createDirectories(target.parent)
    val tmp = Files.createTempFile(target.parent, "report-", ".tmp")
    try {
        Files.write(tmp, bytes)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Exception) {
        runCatching { Files.deleteIfExists(tmp) }
        throw e
    }
    return ReportWrite(target, changed = true)
}
