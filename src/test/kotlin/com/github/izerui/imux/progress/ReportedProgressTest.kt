package com.github.izerui.imux.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportedProgressTest {
    @Test
    fun `无需步骤直接保存百分比和动作`() {
        val record = ProgressRecord()

        assertTrue(record.report(60, "正在测试"))

        assertEquals(60, record.snapshot().progress?.percent)
        assertEquals("正在测试", record.snapshot().progress?.message)
        assertNotNull(record.snapshot().reportedAt)
    }

    @Test
    fun `满格上报不自行结束轮次`() {
        val record = ProgressRecord()

        assertTrue(record.report(100, "正在收尾"))

        assertTrue(record.snapshot().active)
    }

    @Test
    fun `越界百分比拒绝且不改记录`() {
        val record = ProgressRecord()

        assertThrows(IllegalArgumentException::class.java) { record.report(-1, "分析") }
        assertThrows(IllegalArgumentException::class.java) { record.report(101, "分析") }
        assertEquals(null, record.snapshot().progress)
    }
}
