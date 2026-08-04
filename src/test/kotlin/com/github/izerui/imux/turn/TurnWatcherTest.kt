package com.github.izerui.imux.turn

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant

class TurnWatcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val started = """{"type":"event_msg","payload":{"type":"task_started"}}"""
    private val done = """{"type":"event_msg","payload":{"type":"task_complete"}}"""
    private val aborted = """{"type":"event_msg","payload":{"type":"turn_aborted"}}"""

    private fun newFile(name: String, content: String = ""): File =
        File(tmp.root, name).apply { writeText(content) }

    private fun File.append(line: String) = appendText("$line\n")

    /** 这条最关键：插件启动后，历史会话不该莫名其妙地被标记。 */
    @Test
    fun `开始监控时不因历史内容误报`() {
        val file = newFile("a.jsonl", "$started\n$done\n")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        assertTrue("历史内容不该触发提醒", watcher.poll().isEmpty())
    }

    @Test
    fun `新追加的完成信号触发提醒`() {
        val file = newFile("b.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        file.append(done)

        assertEquals(listOf("s1"), watcher.poll())
    }

    @Test
    fun `同一次完成只提醒一次`() {
        val file = newFile("c.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        file.append(done)
        watcher.poll()

        assertTrue(watcher.poll().isEmpty())
    }

    @Test
    fun `中断不触发提醒`() {
        val file = newFile("d.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        file.append(aborted)

        assertTrue(watcher.poll().isEmpty())
    }

    @Test
    fun `取消监控后不再提醒`() {
        val file = newFile("e.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())
        watcher.unwatch("s1")

        file.append(started)
        file.append(done)

        assertTrue(watcher.poll().isEmpty())
    }

    @Test
    fun `文件变短时重置而不崩溃`() {
        val file = newFile("f.jsonl", "$started\n$done\n$started\n$done\n")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.writeText("")
        assertTrue(watcher.poll().isEmpty())

        file.append(started)
        file.append(done)
        assertEquals(listOf("s1"), watcher.poll())
    }

    @Test
    fun `文件不存在时不崩溃`() {
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, File(tmp.root, "不存在.jsonl").toPath())

        assertTrue(watcher.poll().isEmpty())
    }

    @Test
    fun `多个会话各自独立`() {
        val a = newFile("g.jsonl")
        val b = newFile("h.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("sa", AgentType.CODEX, a.toPath())
        watcher.watch("sb", AgentType.CODEX, b.toPath())

        a.append(started)
        a.append(done)

        assertEquals(listOf("sa"), watcher.poll())
    }

    @Test
    fun `开始信号到达后计入执行中`() {
        val file = newFile("j.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())
        assertTrue("刚开始监控时应是空闲", watcher.workingIds(AgentType.CODEX).isEmpty())

        file.append(started)
        watcher.poll()

        assertEquals(setOf("s1"), watcher.workingIds(AgentType.CODEX))
    }

    @Test
    fun `完成信号到达后移出执行中`() {
        val file = newFile("k.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        watcher.poll()
        file.append(done)
        watcher.poll()

        assertTrue(watcher.workingIds(AgentType.CODEX).isEmpty())
    }

    /** 中断与完成对提醒的意义不同，但对「还在不在跑」是一样的：都停了。 */
    @Test
    fun `中断同样移出执行中`() {
        val file = newFile("l.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        watcher.poll()
        file.append(aborted)
        watcher.poll()

        assertTrue(watcher.workingIds(AgentType.CODEX).isEmpty())
    }

    @Test
    fun `取消监控后不再计入执行中`() {
        val file = newFile("m.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        watcher.poll()
        watcher.unwatch("s1")

        assertTrue(watcher.workingIds(AgentType.CODEX).isEmpty())
    }

    @Test
    fun `执行中会话可按 agent 类型筛选`() {
        val codexFile = newFile("n.jsonl")
        val claudeFile = newFile("o.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("codex", AgentType.CODEX, codexFile.toPath())
        watcher.watch("claude", AgentType.CLAUDE, claudeFile.toPath())

        codexFile.append(started)
        claudeFile.append("""{"type":"user","message":{"content":[]}}""")
        watcher.poll()

        assertEquals(setOf("codex"), watcher.workingIds(AgentType.CODEX))
    }

    @Test
    fun `claude 会话同样可监控`() {
        val file = newFile("i.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CLAUDE, file.toPath())

        file.append("""{"type":"user","message":{"content":[]}}""")
        file.append("""{"type":"assistant","message":{"stop_reason":"end_turn"}}""")

        assertEquals(listOf("s1"), watcher.poll())
    }

    private class FakeClock(var now: Instant = Instant.parse("2026-08-04T10:00:00Z")) : () -> Instant {
        override fun invoke(): Instant = now
        fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
    }

    @Test
    fun `记录从开始执行到完成的耗时`() {
        val file = newFile("dur.jsonl")
        val clock = FakeClock()
        val watcher = TurnWatcher(clock)
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        watcher.poll()          // 进入执行态，记下起点
        clock.advance(42)
        file.append(done)
        watcher.poll()          // 完成，结算耗时

        assertEquals(Duration.ofSeconds(42), watcher.lastDuration("s1"))
    }

    @Test
    fun `同一轮内多次轮询不影响起点`() {
        val file = newFile("dur2.jsonl")
        val clock = FakeClock()
        val watcher = TurnWatcher(clock)
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        watcher.poll()
        clock.advance(10)
        watcher.poll()          // 无新内容，不该重置起点
        clock.advance(10)
        file.append(done)
        watcher.poll()

        assertEquals(Duration.ofSeconds(20), watcher.lastDuration("s1"))
    }

    @Test
    fun `没跑完过的会话没有耗时`() {
        val file = newFile("dur3.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        watcher.poll()

        assertNull(watcher.lastDuration("s1"))
    }

    @Test
    fun `取消监控时一并清掉耗时记录`() {
        val file = newFile("dur4.jsonl")
        val clock = FakeClock()
        val watcher = TurnWatcher(clock)
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        watcher.poll()
        clock.advance(5)
        file.append(done)
        watcher.poll()
        watcher.unwatch("s1")

        assertNull(watcher.lastDuration("s1"))
    }

    @Test
    fun `同批开始并完成时清除上一轮耗时`() {
        val file = newFile("dur5.jsonl")
        val clock = FakeClock()
        val watcher = TurnWatcher(clock)
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        watcher.poll()
        clock.advance(8)
        file.append(done)
        watcher.poll()
        assertEquals(Duration.ofSeconds(8), watcher.lastDuration("s1"))

        file.append(started)
        file.append(done)
        watcher.poll()

        assertNull(watcher.lastDuration("s1"))
    }

    @Test
    fun `同批中止后重新开始会重置耗时起点`() {
        val file = newFile("dur6.jsonl")
        val clock = FakeClock()
        val watcher = TurnWatcher(clock)
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        watcher.poll()
        clock.advance(10)
        file.append(aborted)
        file.append(started)
        watcher.poll()
        clock.advance(5)
        file.append(done)
        watcher.poll()

        assertEquals(Duration.ofSeconds(5), watcher.lastDuration("s1"))
    }

    @Test
    fun `同批完成后重新开始会结算旧轮并记录新起点`() {
        val file = newFile("dur7.jsonl")
        val clock = FakeClock()
        val watcher = TurnWatcher(clock)
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        watcher.poll()
        clock.advance(10)
        file.append(done)
        file.append(started)

        assertEquals(listOf("s1"), watcher.poll())
        assertEquals(Duration.ofSeconds(10), watcher.lastDuration("s1"))

        clock.advance(5)
        file.append(done)
        watcher.poll()

        assertEquals(Duration.ofSeconds(5), watcher.lastDuration("s1"))
    }

    @Test
    fun `一次读取里跨越两轮时，新一轮的起点不丢`() {
        // 轮询间隔 1 秒，而 CLI 完全可能在这 1 秒内跑完一轮又开一轮，
        // 于是同一批新增行里既有完成信号又有开始信号。只看首尾状态就会漏掉新起点。
        val file = newFile("multi.jsonl")
        val clock = FakeClock()
        val watcher = TurnWatcher(clock)
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        watcher.poll()
        clock.advance(10)

        file.append(done)       // 第一轮完成
        file.append(started)    // 紧接着第二轮开始
        watcher.poll()

        clock.advance(30)
        file.append(done)       // 第二轮完成
        watcher.poll()

        assertEquals(Duration.ofSeconds(30), watcher.lastDuration("s1"))
    }
}
