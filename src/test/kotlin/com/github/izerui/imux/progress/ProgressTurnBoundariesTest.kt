package com.github.izerui.imux.progress

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.turn.TurnWatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class ProgressTurnBoundariesTest {
    @get:Rule val temp = TemporaryFolder()
    private var now = 1_000L

    private fun event(type: String, at: Long) =
        """{"timestamp":"${Instant.ofEpochMilli(at)}","type":"event_msg","payload":{"type":"$type"}}""" + "\n"

    @Test
    fun `Claude开始钩子清除旧进度且停止钩子等待空闲确认`() {
        val record = ProgressRecord { now }
        record.report(100, "上一轮")
        now += 100
        record.start()
        assertNull(record.snapshot().progress)
        record.report(30, "新一轮")
        record.requestFinish()
        record.observe(true)
        assertTrue("其它停止钩子让CLI继续运行时不能提前结束", record.snapshot().active)
        record.observe(false)
        assertFalse(record.snapshot().active)
    }

    @Test
    fun `Claude短轮次无需先采到忙碌也能在停止后收起`() {
        val record = ProgressRecord { now }
        record.start()
        record.report(100, "完成")
        record.requestFinish()
        record.observe(false)
        assertFalse(record.snapshot().active)
    }

    @Test
    fun `外部等待信号在轮询中保持并可恢复执行`() {
        val record = ProgressRecord { now }
        record.report(40, "执行")
        record.setWaiting(true)
        record.observe(true, waiting = null)
        assertTrue(record.snapshot().waiting)
        assertEquals(40, record.snapshot().progress?.percent)
        record.setWaiting(false)
        record.observe(true, waiting = null)
        assertFalse(record.snapshot().waiting)
        assertEquals(40, record.snapshot().progress?.percent)
    }

    @Test
    fun `Pi等待恢复的乱序上报不会回到旧等待状态`() {
        val record = ProgressRecord { now }
        record.report(40, "执行")
        assertTrue(record.acceptExternalSequence(2))
        record.setWaiting(false, now)
        assertFalse(record.acceptExternalSequence(1))
        record.observe(true, waiting = null)
        assertFalse(record.snapshot().waiting)
    }

    @Test
    fun `等待事件先于轮次采样时仍在开始后显示等待`() {
        val record = ProgressRecord { now }
        record.setWaiting(true, 1_100)
        record.observe(true, waiting = null, transitions = listOf(
            com.github.izerui.imux.turn.TurnTransition(
                com.github.izerui.imux.turn.TurnState.IDLE,
                com.github.izerui.imux.turn.TurnState.WORKING,
                com.github.izerui.imux.turn.TurnEvent.NONE,
                900,
            )
        ), sampledAt = 1_200)
        assertTrue(record.snapshot().active)
        assertTrue(record.snapshot().waiting)
    }

    @Test
    fun `一次采样内开始并完成的短轮次会收起进度`() {
        val file = temp.newFile()
        val watcher = TurnWatcher()
        watcher.watch("s", AgentType.CODEX, file.toPath())
        val record = ProgressRecord { now }
        file.appendText(event("task_started", 900))
        record.report(100, "完成")
        file.appendText(event("task_complete", 1_100))
        assertEquals(listOf("s"), watcher.poll())
        record.observe(false, transitions = watcher.progressTransitions("s"), sampledAt = 1_200)
        assertFalse(record.snapshot().active)
    }

    @Test
    fun `一次采样内开始又中断的轮次收起且不发完成提醒`() {
        val file = temp.newFile()
        val watcher = TurnWatcher()
        watcher.watch("s", AgentType.CODEX, file.toPath())
        val record = ProgressRecord { now }
        file.appendText(event("task_started", 900))
        record.report(20, "执行中")
        file.appendText(event("turn_aborted", 1_100))
        assertTrue(watcher.poll().isEmpty())
        record.observe(false, transitions = watcher.progressTransitions("s"), sampledAt = 1_200)
        assertFalse(record.snapshot().active)
        assertEquals(ProgressEndReason.INTERRUPTED, record.snapshot().endReason)
    }

    @Test
    fun `同批完成并开始下一轮时清除上一轮上报`() {
        val file = temp.newFile()
        val watcher = TurnWatcher()
        watcher.watch("s", AgentType.CODEX, file.toPath())
        val record = ProgressRecord { now }
        record.observe(true)
        record.report(100, "上一轮")
        file.appendText(event("task_started", 900) + event("task_complete", 1_100) + event("task_started", 1_200))
        watcher.poll()
        record.observe(true, transitions = watcher.progressTransitions("s"), sampledAt = 1_300)
        assertTrue(record.snapshot().active)
        assertNull(record.snapshot().progress)
    }

    @Test
    fun `新一轮已上报时迟到的旧轮边界不擦掉新进度`() {
        val file = temp.newFile()
        val watcher = TurnWatcher()
        watcher.watch("s", AgentType.CODEX, file.toPath())
        val record = ProgressRecord { now }
        record.observe(true)
        record.report(100, "上一轮")
        file.appendText(event("task_started", 900) + event("task_complete", 1_100) + event("task_started", 1_200))
        now = 1_250
        record.report(30, "新一轮")
        watcher.poll()
        record.observe(true, transitions = watcher.progressTransitions("s"), sampledAt = 1_300)
        assertTrue(record.snapshot().active)
        assertEquals(TurnProgress(30, "新一轮"), record.snapshot().progress)
    }

    @Test
    fun `迟到的旧结束不能通过完成汇总或下一次空闲采样收起新进度`() {
        val file = temp.newFile()
        val watcher = TurnWatcher()
        watcher.watch("s", AgentType.CODEX, file.toPath())
        val record = ProgressRecord { now }
        record.observe(true)
        file.appendText(event("task_started", 900) + event("task_complete", 1_100))
        now = 1_250
        record.report(30, "新一轮")
        watcher.poll()
        record.observe(false, endReason = ProgressEndReason.NORMAL,
            transitions = watcher.progressTransitions("s"), sampledAt = 1_300)
        assertTrue(record.snapshot().active)
        record.observe(false, sampledAt = 1_400)
        assertTrue(record.snapshot().active)
        assertEquals(TurnProgress(30, "新一轮"), record.snapshot().progress)
    }

    @Test
    fun `读取状态期间的新上报不会被旧空闲采样覆盖`() {
        val record = ProgressRecord { now }
        record.observe(true)
        val sampledAt = now
        now += 100
        record.report(50, "继续")
        record.observe(false, sampledAt = sampledAt)
        assertTrue(record.snapshot().active)
        assertEquals(50, record.snapshot().progress?.percent)
    }

    @Test
    fun `Pi短轮次由实际用户消息和完成记录收起`() {
        val file = temp.newFile()
        val watcher = TurnWatcher()
        watcher.watch("pi", AgentType.PI, file.toPath())
        val record = ProgressRecord { now }
        file.appendText("""{"type":"message","id":"u","timestamp":"1970-01-01T00:00:00.900Z","message":{"role":"user","content":"test"}}""" + "\n")
        record.report(100, "完成")
        file.appendText("""{"type":"message","id":"a","timestamp":"1970-01-01T00:00:01.100Z","message":{"role":"assistant","stopReason":"stop"}}""" + "\n")
        assertEquals(listOf("pi"), watcher.poll())
        record.observe(false, transitions = watcher.progressTransitions("pi"), sampledAt = 1_200)
        assertFalse(record.snapshot().active)
    }
}
