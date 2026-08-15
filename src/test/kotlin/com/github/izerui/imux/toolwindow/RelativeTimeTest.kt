package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.settings.PluginLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RelativeTimeTest {
    private val now: Instant = Instant.parse("2026-08-04T02:00:00Z")

    private fun format(instant: Instant) = RelativeTime.format(instant, now)

    @Test
    fun `formats relative times in fixed English`() {
        assertEquals("30 seconds ago", format(now.minusSeconds(30)))
        assertEquals("5 minutes ago", format(now.minusSeconds(5 * 60)))
        assertEquals("3 hours ago", format(now.minusSeconds(3 * 3600)))
        assertEquals("2 days ago", format(now.minusSeconds(2 * 86400)))
        assertEquals("15 days ago", format(Instant.parse("2026-07-20T02:00:00Z")))
        assertEquals("in 2 minutes", format(now.plusSeconds(120)))
    }

    @Test
    fun `formats simplified Chinese independently of the IDE locale`() {
        assertEquals(
            "5 分钟前",
            RelativeTime.format(now.minusSeconds(5 * 60), now, PluginLanguage.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            "2 分钟后",
            RelativeTime.format(now.plusSeconds(120), now, PluginLanguage.SIMPLIFIED_CHINESE),
        )
    }

    @Test
    fun `uses singular units`() {
        assertEquals("1 second ago", format(now.minusSeconds(1)))
        assertEquals("1 minute ago", format(now.minusSeconds(60)))
        assertEquals("1 hour ago", format(now.minusSeconds(3600)))
        assertEquals("1 day ago", format(now.minusSeconds(86400)))
    }
}
