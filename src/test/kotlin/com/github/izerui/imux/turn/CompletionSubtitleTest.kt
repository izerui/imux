package com.github.izerui.imux.turn

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class CompletionSubtitleTest {
    @Test
    fun `joins agent and elapsed time`() {
        assertEquals(
            "Claude · Elapsed: 2 minutes 13 seconds",
            completionSubtitle(AgentType.CLAUDE, Duration.ofSeconds(133)),
        )
    }

    @Test
    fun `does not include project name`() {
        assertEquals(
            "Codex · Elapsed: 5 seconds",
            completionSubtitle(AgentType.CODEX, Duration.ofSeconds(5)),
        )
    }

    @Test
    fun `omits unknown agent without an extra separator`() {
        assertEquals("Elapsed: 5 seconds", completionSubtitle(null, Duration.ofSeconds(5)))
    }

    @Test
    fun `omits unknown duration`() {
        assertEquals("Codex", completionSubtitle(AgentType.CODEX, null))
    }

    @Test
    fun `returns empty text when all parts are unknown`() {
        assertEquals("", completionSubtitle(null, null))
    }

    @Test
    fun `formats durations with at most two units`() {
        val cases =
            mapOf(
                1_400L to "1 second",
                59_000L to "59 seconds",
                60_000L to "1 minute",
                133_000L to "2 minutes 13 seconds",
                3_599_000L to "59 minutes 59 seconds",
                3_600_000L to "1 hour",
                7_500_000L to "2 hours 5 minutes",
            )
        cases.forEach { (millis, expected) ->
            assertEquals(expected, formatDuration(Duration.ofMillis(millis)))
        }
    }

    @Test
    fun `subsecond duration is zero seconds`() {
        assertEquals("0 seconds", formatDuration(Duration.ofMillis(300)))
    }

    @Test
    fun `negative duration is clamped to zero`() {
        assertEquals("0 seconds", formatDuration(Duration.ofSeconds(-3)))
    }
}
