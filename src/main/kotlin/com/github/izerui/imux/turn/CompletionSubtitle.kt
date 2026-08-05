package com.github.izerui.imux.turn

import com.github.izerui.imux.model.AgentType
import com.intellij.ide.nls.NlsMessages
import java.time.Duration

/**
 * 轮次完成提醒的副标题：`项目 · Agent · 耗时 2 分 13 秒`。
 *
 * 标题位置已经给了会话标题，这里放的是**光看标题看不出来的东西**：
 * 系统通知从通知中心弹出时脱离了 IDE 窗口，不写项目名就分不清是哪个项目的；
 * 同时开着好几个项目窗口时尤其如此。
 *
 * 拿不到的部分整段略去，不留多余的间隔号。
 */
internal fun completionSubtitle(
    projectName: String,
    agentType: AgentType?,
    duration: Duration?,
): String = listOfNotNull(
    projectName,
    agentType?.displayName,
    duration?.let { "耗时 ${formatDuration(it)}" },
).joinToString(" · ")

/**
 * 人话时长。只保留两级单位——秒级精度对「跑了多久」这件事已经足够，
 * 再细反而要多看一眼才能读懂。
 *
 * 负值按 0 处理：时钟回拨等极端情况下不该显示成「-3 秒」。
 */
internal fun formatDuration(duration: Duration): String {
    val millis = duration.toMillis().coerceAtLeast(0)
    return NlsMessages.formatDuration(millis)
}
