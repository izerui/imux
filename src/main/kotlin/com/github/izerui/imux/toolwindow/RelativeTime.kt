package com.github.izerui.imux.toolwindow

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * 把时间点格式化成「多久以前」。
 *
 * 纯函数，时间基准与时区都由调用方传入，便于测试。
 */
object RelativeTime {

    fun format(instant: Instant, now: Instant, zone: ZoneId): String {
        val elapsed = Duration.between(instant, now)

        // 时钟漂移或文件时间戳超前时不显示负数
        if (elapsed.isNegative || elapsed.toMinutes() < 1) return "刚刚"

        return when {
            elapsed.toHours() < 1 -> "${elapsed.toMinutes()} 分钟前"
            elapsed.toDays() < 1 -> "${elapsed.toHours()} 小时前"
            elapsed.toDays() < 7 -> "${elapsed.toDays()} 天前"
            else -> instant.atZone(zone).let { "${it.monthValue}月${it.dayOfMonth}日" }
        }
    }
}
