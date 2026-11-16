package com.github.izerui.imux.overview.report

import com.github.izerui.imux.overview.OverviewNode

/**
 * 会话报告：智能体读完会话记录后产出的各节内容，加上插件扫描记录得出的事实（[facts]）。
 * 格式由插件定死，页面模板只管展示；各节条数上限见 `ReportJson.kt`。
 */
data class SessionReport(
    val goal: String? = null,
    val summary: String? = null,
    val status: ReportStatus? = null,
    val decisions: List<ReportDecision> = emptyList(),
    val changes: List<ReportChange> = emptyList(),
    val verification: List<ReportCheck> = emptyList(),
    val timeline: List<OverviewNode> = emptyList(),
    val issues: List<ReportIssue> = emptyList(),
    val nextSteps: List<String> = emptyList(),
    val facts: ReportFacts = ReportFacts(),
)

enum class ReportStatus(val wire: String) { DONE("done"), IN_PROGRESS("in_progress"), FAILED("failed") }

data class ReportDecision(val title: String, val reason: String?)

data class ReportChange(val file: String, val summary: String?, val lines: List<SnippetLine>, val note: String?)

data class SnippetLine(val kind: LineKind, val text: String)

enum class LineKind(val wire: String) { ADD("add"), DEL("del"), CTX("ctx") }

data class ReportCheck(val what: String, val result: CheckResult, val detail: String?)

enum class CheckResult(val wire: String) { PASSED("passed"), FAILED("failed"), NOT_RUN("not_run") }

data class ReportIssue(val severity: Severity, val text: String)

enum class Severity(val wire: String) { HIGH("high"), MEDIUM("medium"), LOW("low") }

/** 插件扫描会话记录得出的事实：会话时长（分钟）与改动过的文件。 */
data class ReportFacts(val durationMinutes: Long? = null, val files: List<String> = emptyList())
