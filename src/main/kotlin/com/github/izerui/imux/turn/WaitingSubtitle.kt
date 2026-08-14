package com.github.izerui.imux.turn

import com.github.izerui.imux.model.AgentType

/**
 * 等待用户操作提醒的副标题：`Claude · 等待权限确认`。
 *
 * 与 [completionSubtitle] 同构，但不报耗时——这一轮并没有结束，
 * 报出的任何数字都是错的。位置留给「在等什么」，那才是用户此刻要判断的：
 * 是随手按一下确认，还是得读完选项再决定。
 *
 * 原因取自 Claude 运行态文件的 `waitingFor` 字段。
 */
internal fun waitingSubtitle(
    agentType: AgentType?,
    waitingFor: String?,
): String =
    listOfNotNull(
        agentType?.shortName,
        waitingReason(waitingFor),
    ).joinToString(" · ")

/**
 * 把 CLI 的 `waitingFor` 译成人话。
 *
 * 只单列日常真会遇到的三种。`worker request`（子代理请求）与
 * `sandbox request`（沙箱请求）走兜底：它们几乎不出现，单列的文案没人会读到。
 * 未知取值同样落到兜底，CLI 将来新增取值时不至于显示成空白。
 */

/**
 * 这条等待提醒该不该弹气泡。
 *
 * 与「轮次完成」刻意不同：完成时即使用户正看着该标签页也要提醒，因为
 * tab 选中不等于人在屏幕前。等待则相反——CLI 的选择框就占在屏幕上，
 * 再弹一个 IDE 气泡属于重复告知。它顺带压掉了连环气泡：一轮里连续几次
 * 权限确认会产生 `waiting -> busy -> waiting`，用户正逐个确认，不该每次都被打扰。
 *
 * 未读标记不受此影响，照常打——那本来就是给离开后回来的人看的。
 */
internal fun waitingNotificationWanted(
    sessionId: String,
    selectedSessionKeys: Set<String>,
): Boolean = sessionId !in selectedSessionKeys

private fun waitingReason(waitingFor: String?): String =
    when (waitingFor) {
        "permission prompt" -> "Waiting for permission"
        "dialog open" -> "Waiting for your selection"
        "input needed" -> "Waiting for input"
        else -> "Waiting for your confirmation"
    }
