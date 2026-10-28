package com.github.izerui.imux.bridge.transcript

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionEvent
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.model.AgentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

class TailRegistryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val t0 = Instant.parse("2026-10-28T02:00:00Z")
    private var now = t0
    private var generation = 1L
    private var binding: Binding? = null
    private val files = HashMap<String, SessionFile>()

    private val registry =
        TailRegistry(
            bindingForTab = { tab -> binding?.takeIf { it.target.tabId == tab } },
            sessionFile = { files[it] },
            switchGeneration = { generation },
            now = { now },
        )

    /** Pi 记录：一行一条用户消息，带时间戳。 */
    private fun line(
        text: String,
        at: Instant,
    ) = """{"type":"message","timestamp":"$at","message":{"role":"user","content":"$text"}}""" + "\n"

    private fun session(
        id: String,
        createdAt: Instant,
        vararg lines: String,
    ): Path {
        val path = temp.newFile("$id.jsonl").toPath()
        Files.writeString(path, lines.joinToString(""))
        files[id] = SessionFile(path, createdAt)
        return path
    }

    private fun bind(
        sessionId: String,
        epoch: Long = 1,
    ) {
        binding = Binding(RobotId("r"), SessionTarget("/p", "tab", AgentType.PI, sessionId), epoch)
    }

    private fun append(
        path: Path,
        text: String,
    ) = Files.writeString(path, line(text, now), StandardOpenOption.APPEND)

    private fun texts(poll: TailPoll?) = poll?.events?.map { (it as SessionEvent.UserText).text }

    private fun read() = texts(runBlocking { registry.read("tab") })

    private fun readUpTo(mark: ReadMark?) = runBlocking { registry.readUpTo("tab", mark) }

    @Test
    fun `新绑定从记录末尾开始，之后写入的才读出`() {
        val path = session("s1", t0, line("旧", t0))
        bind("s1")
        assertEquals(emptyList<String>(), read())
        append(path, "新")
        assertEquals(listOf("新"), read())
    }

    @Test
    fun `换绑后尾随器作废，从当时的末尾重新开始`() {
        val path = session("s1", t0)
        bind("s1", epoch = 1)
        read()
        append(path, "换绑前")
        bind("s1", epoch = 2)
        assertEquals(emptyList<String>(), read())
        append(path, "换绑后")
        assertEquals(listOf("换绑后"), read())
    }

    @Test
    fun `迁移到新建的会话从头读，跳过复制进来的旧历史`() {
        session("s1", t0)
        bind("s1")
        read()
        now = t0.plusSeconds(600)
        // fork 出的新文件：带着原会话的历史（原时间戳），文件在迁移前几秒创建
        session("s2", now.minusSeconds(5), line("复制来的", t0), line("新一轮", now))
        bind("s2")
        registry.migrated("tab", epoch = 1)
        assertEquals(listOf("新一轮"), read())
    }

    @Test
    fun `迁移回旧会话从末尾开始`() {
        session("old", t0, line("旧会话的历史", t0))
        session("s1", t0)
        bind("s1")
        read()
        now = t0.plusSeconds(600)
        bind("old")
        registry.migrated("tab", epoch = 1)
        assertEquals(emptyList<String>(), read())
    }

    @Test
    fun `迁移记录只对登记时的那次绑定有效`() {
        session("s1", t0)
        bind("s1", epoch = 1)
        read()
        registry.migrated("tab", epoch = 1)
        // 迁移记录还没用掉就换绑：新绑定不能据它从头读
        session("s2", t0, line("不该读到", t0))
        bind("s2", epoch = 2)
        assertEquals(emptyList<String>(), read())
    }

    @Test
    fun `远程控制关了又开（代次变了），关闭期间写入的不读`() {
        val path = session("s1", t0)
        bind("s1")
        read()
        append(path, "关闭期间")
        generation++
        assertEquals(emptyList<String>(), read())
        append(path, "重新开启后")
        assertEquals(listOf("重新开启后"), read())
    }

    @Test
    fun `给了读取边界只读到那里，其后的留给下一次`() {
        val path = session("s1", t0)
        bind("s1")
        read()
        append(path, "本轮")
        val mark = registry.mark("tab")
        append(path, "下一轮")
        assertEquals(listOf("本轮"), texts((readUpTo(mark) as BoundedRead.Reached).poll))
        assertEquals(listOf("下一轮"), read())
    }

    @Test
    fun `读取位置已越过边界：普通读取抢先读了边界之后的内容，带边界读取不再交出任何东西`() {
        val path = session("s1", t0)
        bind("s1")
        read()
        append(path, "本轮")
        val mark = registry.mark("tab")
        append(path, "下一轮")
        assertEquals(listOf("本轮", "下一轮"), read())
        assertEquals(BoundedRead.Unreliable(), readUpTo(mark))
    }

    @Test
    fun `正好读到边界、还没越过时照常结算`() {
        val path = session("s1", t0)
        bind("s1")
        read()
        append(path, "本轮")
        val mark = registry.mark("tab")
        assertEquals(listOf("本轮"), read())
        assertEquals(BoundedRead.Reached(TailPoll(emptyList(), restarted = false)), readUpTo(mark))
    }

    @Test
    fun `尾随器在边界记下之后被重建（换绑）：无法确认信号属于哪一轮，按越过处理`() {
        session("s1", t0)
        bind("s1", epoch = 1)
        read()
        val mark = registry.mark("tab")
        bind("s1", epoch = 2)
        assertEquals(BoundedRead.Unreliable(), readUpTo(mark))
    }

    @Test
    fun `信号到达时还没有尾随器：没有可靠边界，不读`() {
        val path = session("s1", t0)
        bind("s1")
        assertEquals(BoundedRead.Unreliable(), readUpTo(null))
        // 不读：之后建立尾随器仍从当时的末尾开始
        assertEquals(emptyList<String>(), read())
        append(path, "新")
        assertEquals(listOf("新"), read())
    }

    @Test
    fun `标签不再绑定或还没有会话文件时不读`() {
        assertNull(read())
        bind("missing")
        assertNull(read())
    }

    @Test
    fun `判断新会话与复制历史的时间界线`() {
        assertEquals(true, readFromStart(createdAt = t0.minusSeconds(60), migratedAt = t0))
        assertEquals(false, readFromStart(createdAt = t0.minusSeconds(61), migratedAt = t0))
        assertEquals(t0.minusSeconds(32), copiedHistoryBefore(createdAt = t0.minusSeconds(30), migratedAt = t0))
        // 不支持创建时间的文件系统退回修改时间，可能晚于迁移：以迁移时间为准
        assertEquals(t0.minusSeconds(2), copiedHistoryBefore(createdAt = t0.plusSeconds(90), migratedAt = t0))
    }

    @Test
    fun `带边界读取时文件被截到已读位置之前：带上重启结果返回不可靠`() {
        val path = session("s1", t0, line("旧", t0))
        bind("s1")
        read()
        append(path, "本轮")
        val mark = registry.mark("tab")
        Files.writeString(path, "")
        val read = readUpTo(mark)
        assertEquals(true, (read as BoundedRead.Unreliable).poll?.restarted)
    }

    @Test
    fun `带边界读取时文件被截到已读位置与边界之间：没读到边界，返回不可靠`() {
        val path = session("s1", t0, line("旧", t0))
        bind("s1")
        read()
        val kept = Files.readString(path)
        append(path, "本轮")
        val mark = registry.mark("tab")
        Files.writeString(path, kept + "{\"partial\":")
        assertEquals(BoundedRead.Unreliable(TailPoll(emptyList(), restarted = false)), readUpTo(mark))
    }
}
