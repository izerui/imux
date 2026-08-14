package com.github.izerui.imux.toolwindow

import java.time.Instant
import kotlin.math.abs

/** Formats a timestamp as a compact, fixed-English relative time. */
object RelativeTime {
    fun format(
        instant: Instant,
        now: Instant,
    ): String {
        val delta = now.epochSecond - instant.epochSecond
        val future = delta < 0
        val seconds = abs(delta)
        val (value, unit) =
            when {
                seconds < 60 -> seconds to "second"
                seconds < 3_600 -> seconds / 60 to "minute"
                seconds < 86_400 -> seconds / 3_600 to "hour"
                else -> seconds / 86_400 to "day"
            }
        val text = "$value $unit${if (value == 1L) "" else "s"}"
        return if (future) "in $text" else "$text ago"
    }
}
