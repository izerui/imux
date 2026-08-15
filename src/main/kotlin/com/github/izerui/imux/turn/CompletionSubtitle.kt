package com.github.izerui.imux.turn

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.settings.PluginLanguage
import java.time.Duration

/** Builds the completion notification subtitle in the language selected by imux. */
internal fun completionSubtitle(
    agentType: AgentType?,
    duration: Duration?,
    language: PluginLanguage = ImuxBundle.currentLanguage(),
): String =
    listOfNotNull(
        agentType?.shortName,
        duration?.let { ImuxBundle.message(language, "duration.elapsed", formatDuration(it, language)) },
    ).joinToString(" · ")

/** Formats a duration with at most two units. */
internal fun formatDuration(
    duration: Duration,
    language: PluginLanguage = ImuxBundle.currentLanguage(),
): String {
    var seconds = duration.seconds.coerceAtLeast(0)
    val hours = seconds / 3_600
    seconds %= 3_600
    val minutes = seconds / 60
    seconds %= 60

    return when {
        hours > 0 -> {
            buildList {
                add(unit(hours, "hour", language))
                if (minutes > 0) add(unit(minutes, "minute", language))
            }
        }

        minutes > 0 -> {
            buildList {
                add(unit(minutes, "minute", language))
                if (seconds > 0) add(unit(seconds, "second", language))
            }
        }

        else -> {
            listOf(unit(seconds, "second", language))
        }
    }.joinToString(" ")
}

private fun unit(
    value: Long,
    name: String,
    language: PluginLanguage,
): String {
    val key = if (value == 1L) "duration.$name" else "duration.${name}s"
    return ImuxBundle.message(language, key, value)
}
