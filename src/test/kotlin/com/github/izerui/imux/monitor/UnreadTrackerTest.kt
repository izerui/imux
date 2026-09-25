package com.github.izerui.imux.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreadTrackerTest {
    @Test
    fun `排除运行会话时保留未读状态但不重复计数`() {
        val tracker =
            UnreadTracker(
                updateOpenTabIcons = {},
                updateFrameTitle = {},
                notifyListeners = {},
            )
        tracker.markUnread("running")
        tracker.markUnread("idle")

        assertEquals("原始未读集合不能被排除操作修改", 2, tracker.unreadCount())
        assertEquals("运行中的会话不应重复计入标题未读数", 1, tracker.unreadCount(setOf("running")))
        assertTrue("运行停止后还要恢复未读提示", tracker.isUnread("running"))
    }
}
