package com.github.izerui.imux.progress

import com.github.izerui.imux.session.ClaudeRuntimeSession
import com.github.izerui.imux.turn.RunningSessions
import org.junit.Assert.*
import org.junit.Test

class ProgressIdleFallbackTest {
    private var now = 1_000L

    private fun observeClaude(record: ProgressRecord, status: String, sampledAt: Long = now) {
        val runtime = mapOf("s" to ClaudeRuntimeSession("s", 1, "interactive", status, "/project"))
        record.observe(
            active = "s" in RunningSessions.workingOf(runtime, emptySet()) || runtime.getValue("s").isWaiting,
            waiting = runtime.getValue("s").isWaiting,
            sampledAt = sampledAt,
            sessionRunning = "s" in RunningSessions.of(runtime, emptySet()),
        )
    }

    @Test
    fun `Claude开始检查被取消后由标签空闲状态收起进度`() {
        val record = ProgressRecord { now }
        record.start()
        now += 1_000
        observeClaude(record, "idle")
        assertFalse(record.snapshot().active)
    }

    @Test
    fun `漏掉停止通知且未采到忙碌时仍由空闲状态收起最后上报`() {
        val record = ProgressRecord { now }
        record.report(100, "完成")
        now += 1_000
        observeClaude(record, "idle")
        assertFalse(record.snapshot().active)
        assertEquals(TurnProgress(100, "完成"), record.snapshot().progress)
    }

    @Test
    fun `旧空闲采样不能收起读取期间的新上报`() {
        val record = ProgressRecord { now }
        val sampledAt = now
        now += 100
        record.report(30, "新一轮")
        observeClaude(record, "idle", sampledAt)
        assertTrue(record.snapshot().active)
        assertEquals(30, record.snapshot().progress?.percent)
    }

    @Test
    fun `旧空闲采样不能收起读取期间的新开始`() {
        val record = ProgressRecord { now }
        val sampledAt = now
        now += 100
        record.start()
        observeClaude(record, "idle", sampledAt)
        assertTrue(record.snapshot().active)
    }

    @Test
    fun `Claude等待确认时图标不转但进度仍保留`() {
        val record = ProgressRecord { now }
        record.report(40, "等待确认")
        now += 1_000
        observeClaude(record, "waiting")
        assertTrue(record.snapshot().active)
        assertTrue(record.snapshot().waiting)
        assertEquals(40, record.snapshot().progress?.percent)
    }

    @Test
    fun `外部等待信号不会被空闲兜底收起且作答后可收起`() {
        val record = ProgressRecord { now }
        record.report(40, "等待")
        record.setWaiting(true)
        now += 1_000
        record.observe(false, waiting = null, sessionRunning = false)
        assertTrue(record.snapshot().active)
        assertTrue(record.snapshot().waiting)
        record.setWaiting(false)
        now += 1_000
        record.observe(false, waiting = null, sessionRunning = false)
        assertFalse(record.snapshot().active)
    }

    @Test
    fun `后台命令仍在运行时不触发空闲兜底`() {
        val record = ProgressRecord { now }
        record.report(100, "完成")
        now += 1_000
        observeClaude(record, "shell")
        assertTrue(record.snapshot().active)
        now += 1_000
        observeClaude(record, "idle")
        assertFalse(record.snapshot().active)
    }
}
