package com.github.izerui.imux.monitor

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** 会话在 imux 之外被删掉时未读原先留着：窗口标题一直显示未读，列表里却找不到可点的那一行。 */
    @Test
    fun `会话不在了时清掉它的未读并刷新标题，其余未读保留`() {
        var titleUpdates = 0
        val tracker = UnreadTracker(updateOpenTabIcons = {}, updateFrameTitle = { titleUpdates++ }, notifyListeners = {})
        tracker.markUnread("deleted")
        tracker.markUnread("kept")
        titleUpdates = 0

        tracker.forget(listOf("deleted"))

        assertFalse(tracker.isUnread("deleted"))
        assertTrue(tracker.isUnread("kept"))
        assertEquals(1, titleUpdates)
    }

    @Test
    fun `消失的会话本来就没有未读时不刷新标题`() {
        var titleUpdates = 0
        val tracker = UnreadTracker(updateOpenTabIcons = {}, updateFrameTitle = { titleUpdates++ }, notifyListeners = {})

        tracker.forget(listOf("read-already"))

        assertEquals(0, titleUpdates)
    }

    @Test
    fun `扫描发现会话消失时清掉它的未读`() {
        val code = SourceCode("src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt")
        assertTrue(code.compact(code.normalized).contains("model.addListener{unreadTracker.forget(model.drainRemovedSessions())"))
    }
}
