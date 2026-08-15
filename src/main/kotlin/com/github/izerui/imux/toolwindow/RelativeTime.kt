package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.settings.PluginLanguage
import java.time.Instant
import kotlin.math.abs

/** Formats a timestamp using the language selected in imux settings. */
object RelativeTime {
    fun format(
        instant: Instant,
        now: Instant,
        language: PluginLanguage = ImuxBundle.currentLanguage(),
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
        val direction = if (future) "future" else "past"
        val number = if (value == 1L) unit else "${unit}s"
        return ImuxBundle.message(language, "relative.$direction.$number", value)
    }
}
