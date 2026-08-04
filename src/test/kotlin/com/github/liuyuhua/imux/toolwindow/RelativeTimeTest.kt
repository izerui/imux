package com.github.liuyuhua.imux.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RelativeTimeTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: Instant = Instant.parse("2026-08-04T02:00:00Z")

    private fun format(instant: Instant) = RelativeTime.format(instant, now, zone)

    @Test
    fun `一分钟内显示刚刚`() {
        assertEquals("刚刚", format(now.minusSeconds(30)))
    }

    @Test
    fun `一小时内显示分钟`() {
        assertEquals("5 分钟前", format(now.minusSeconds(5 * 60)))
        assertEquals("59 分钟前", format(now.minusSeconds(59 * 60)))
    }

    @Test
    fun `一天内显示小时`() {
        assertEquals("3 小时前", format(now.minusSeconds(3 * 3600)))
        assertEquals("23 小时前", format(now.minusSeconds(23 * 3600)))
    }

    @Test
    fun `一周内显示天`() {
        assertEquals("2 天前", format(now.minusSeconds(2 * 86400)))
        assertEquals("6 天前", format(now.minusSeconds(6 * 86400)))
    }

    @Test
    fun `超过一周显示日期`() {
        // 2026-07-20T02:00:00Z 在上海是 7 月 20 日 10:00
        assertEquals("7月20日", format(Instant.parse("2026-07-20T02:00:00Z")))
    }

    @Test
    fun `未来时间当作刚刚处理`() {
        assertEquals("刚刚", format(now.plusSeconds(120)))
    }
}
