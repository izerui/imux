package com.github.izerui.imux.terminal

import com.github.izerui.imux.session.SessionUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionInfoSnapshotTest {
    @Test
    fun `刷新读取失败保留上次用量模型和技能并更新分支`() {
        val snapshot = SessionInfoSnapshot()
        val previous = SessionUsage(total = 123, model = "model", skills = listOf("review"), skillsReported = true)
        snapshot.update(previous, "main")

        snapshot.update(null, "feature")

        assertEquals(previous, snapshot.usage)
        assertEquals("feature", snapshot.branch)
        assertTrue(snapshot.readFailed)
    }

    @Test
    fun `首次读取失败仍显示分支且重试成功后清除失败状态`() {
        val snapshot = SessionInfoSnapshot()
        snapshot.update(null, "main")
        assertEquals("main", snapshot.branch)
        assertTrue(snapshot.readFailed)

        val loaded = SessionUsage(total = 456)
        snapshot.update(loaded, "main")
        assertEquals(loaded, snapshot.usage)
        assertFalse(snapshot.readFailed)
    }

    @Test
    fun `移除版本管理后刷新清除旧分支`() {
        val snapshot = SessionInfoSnapshot()
        snapshot.update(SessionUsage(total = 123), "main")
        snapshot.update(null, null)
        assertEquals(123L, snapshot.usage.total)
        assertEquals(null, snapshot.branch)
    }
}
