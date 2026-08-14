package com.github.izerui.imux.turn

import com.github.izerui.imux.model.AgentType
import java.time.Duration

/**
 * 轮次完成提醒的副标题：`Agent · 耗时 2 分 13 秒`。
 *
 * 标题位置已经给了会话标题，这里放的是**光看标题看不出来的东西**：
 * 哪个 agent 跑的、跑了多久。项目名不放——通知本身就挂在项目窗口上，
 * 重复一遍只会挤占本就紧张的一行宽度。
 *
 * 拿不到的部分整段略去，不留多余的间隔号。
 */
internal fun completionSubtitle(
    agentType: AgentType?,
    duration: Duration?,
): String =
    listOfNotNull(
        agentType?.shortName,
        duration?.let { "Elapsed: ${formatDuration(it)}" },
    ).joinToString(" · ")

/**
 * 人话时长。只保留两级单位——秒级精度对「跑了多久」这件事已经足够，
 * 再细反而要多看一眼才能读懂。
 *
 * 负值按 0 处理：时钟回拨等极端情况下不该显示成「-3 秒」。
 */
internal fun formatDuration(duration: Duration): String {
    var seconds = duration.seconds.coerceAtLeast(0)
    val hours = seconds / 3_600
    seconds %= 3_600
    val minutes = seconds / 60
    seconds %= 60

    return when {
        hours > 0 -> {
            buildList {
                add(unit(hours, "hour"))
                if (minutes > 0) add(unit(minutes, "minute"))
            }
        }

        minutes > 0 -> {
            buildList {
                add(unit(minutes, "minute"))
                if (seconds > 0) add(unit(seconds, "second"))
            }
        }

        else -> {
            listOf(unit(seconds, "second"))
        }
    }.joinToString(" ")
}

private fun unit(
    value: Long,
    name: String,
): String = "$value $name${if (value == 1L) "" else "s"}"
