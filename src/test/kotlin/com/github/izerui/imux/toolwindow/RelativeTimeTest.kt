package com.github.izerui.imux.toolwindow

import com.intellij.util.text.DateFormatUtil
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RelativeTimeTest {

    private val now: Instant = Instant.parse("2026-08-04T02:00:00Z")

    private fun format(instant: Instant) = RelativeTime.format(instant, now)

    @Test
    fun `相对时间展示与平台格式保持一致`() {
        listOf(
            now.minusSeconds(30),
            now.minusSeconds(5 * 60),
            now.minusSeconds(3 * 3600),
            now.minusSeconds(2 * 86400),
            Instant.parse("2026-07-20T02:00:00Z"),
            now.plusSeconds(120),
        ).forEach { instant ->
            assertEquals(
                DateFormatUtil.formatBetweenDates(instant.toEpochMilli(), now.toEpochMilli()),
                format(instant),
            )
        }
    }
}
