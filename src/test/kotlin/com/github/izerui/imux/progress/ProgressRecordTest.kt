package com.github.izerui.imux.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressRecordTest {
    @Test
    fun `直接上报不需要来源文件或轮次身份`() {
        val record = ProgressRecord()

        assertTrue(record.report(60, "正在测试"))

        assertTrue(record.snapshot().active)
        assertEquals(60, record.snapshot().progress?.percent)
        assertEquals("正在测试", record.snapshot().progress?.message)
    }

    @Test
    fun `上报后旧空闲采样不立即隐藏进度`() {
        val record = ProgressRecord()
        record.report(20, "正在启动")

        record.observe(active = false)

        assertTrue(record.snapshot().active)
        assertEquals(20, record.snapshot().progress?.percent)
    }

    @Test
    fun `观察到运行后结束会隐藏并保留最后记录`() {
        val record = ProgressRecord()
        record.report(60, "正在测试")
        record.observe(active = true)

        record.observe(active = false)

        assertFalse(record.snapshot().active)
        assertEquals(60, record.snapshot().progress?.percent)
    }

    @Test
    fun `下一轮开始清除上一轮的上报`() {
        val record = ProgressRecord()
        record.report(100, "上一轮完成")
        record.observe(active = true)
        record.observe(active = false)

        record.observe(active = true)

        assertTrue(record.snapshot().active)
        assertNull(record.snapshot().progress)
    }

    @Test
    fun `等待时保留进度和可见性`() {
        val record = ProgressRecord()
        record.report(40, "等待确认")
        record.observe(active = true, waiting = true)

        assertTrue(record.snapshot().active)
        assertTrue(record.snapshot().waiting)
        assertEquals(40, record.snapshot().progress?.percent)
    }
}
