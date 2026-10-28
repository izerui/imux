package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.peer.PeerOutcome
import com.github.izerui.imux.peer.PeerOutcomeKind
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Icon

/** 取文案。生产用插件当前语言；测试传固定语言，不依赖插件设置服务。 */
internal typealias PairMessages = (key: String, args: List<Any>) -> String

internal val pluginMessages: PairMessages = { key, args -> ImuxBundle.message(key, *args.toTypedArray()) }

private val RESULT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun resultTime(outcome: PeerOutcome, zone: ZoneId = ZoneId.systemDefault()): String =
    LocalTime.ofInstant(Instant.ofEpochMilli(outcome.finishedAtMillis), zone).format(RESULT_TIME)

/**
 * 上一轮审查结果的一句话，例如「上一轮 14:32 · Codex：没有新的反馈」；没有结果或上一轮失败时为 null。
 * 结果 chip 在窄屏会被收起，悬停说明和结对菜单里都附上这句，保证结果始终有地方看。
 */
internal fun lastResultLine(
    outcome: PeerOutcome?,
    mainAgent: String,
    message: PairMessages = pluginMessages,
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    if (outcome == null || outcome.kind == PeerOutcomeKind.FAILED) return null
    val detail =
        if (outcome.kind == PeerOutcomeKind.PASSED) message("action.peer.chip.passed.detail", emptyList())
        else message("action.peer.chip.feedback.detail", listOf(mainAgent))
    return message("action.peer.tool.last", listOf(resultTime(outcome, zone), outcome.targetAgentType.displayName, detail))
}

/**
 * 结对按钮此刻的样子。开着显示伙伴名（绿），审查中显示秒数（蓝），失败显示黄色短字；
 * 未开启（包括单次审查跑完自动解绑之后）只是图标，但悬停说明照样附上保留的上一轮结果。
 */
internal fun pairToolState(
    model: PeerSegmentModel,
    failed: PeerOutcome?,
    lastResult: String?,
    runningText: String,
    failedText: String?,
    busyIcon: Icon,
    message: PairMessages = pluginMessages,
): ToolState {
    val title = message("action.peer.group.text", emptyList())
    val name = model.agentName.orEmpty()
    fun withResult(base: String) = listOfNotNull(base, lastResult).joinToString(" ")
    return when {
        model.kind == PeerSegmentKind.RUNNING ->
            ToolState(
                busyIcon, title, runningText,
                label = message("action.peer.tool.running", listOf(model.elapsedSeconds)),
                tone = NoticeTone.INFO,
            )
        failed != null && failedText != null ->
            ToolState(AgentIcons.pair, title, failedText, message("action.peer.tool.failed", emptyList()), NoticeTone.WARNING)
        model.kind == PeerSegmentKind.IDLE ->
            ToolState(AgentIcons.pair, title, withResult(message("action.peer.segment.hint", emptyList())))
        else ->
            ToolState(
                AgentIcons.pair, title,
                withResult(message("action.peer.status.ready", listOf(name))),
                label = name,
                tone = NoticeTone.SUCCESS,
            )
    }
}
