package com.github.liuyuhua.imux.turn

import com.github.liuyuhua.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
    fun `claude 会话同样可监控`() {
        val file = newFile("i.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CLAUDE, file.toPath())

        file.append("""{"type":"user","message":{"content":[]}}""")
        file.append("""{"type":"assistant","message":{"stop_reason":"end_turn"}}""")

        assertEquals(listOf("s1"), watcher.poll())
    }
}
