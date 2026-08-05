package com.github.izerui.imux.toolwindow

import com.intellij.util.text.DateFormatUtil
import java.time.Instant

/**
 * 把时间点格式化成「多久以前」。
 *
 * 时间基准由调用方传入，展示规则和本地化交给平台统一处理。
 */
object RelativeTime {

    fun format(instant: Instant, now: Instant): String =
        DateFormatUtil.formatBetweenDates(instant.toEpochMilli(), now.toEpochMilli())
}
