package com.github.izerui.imux.bridge.transcript

import com.github.izerui.imux.bridge.core.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

class TranscriptTailTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val echo = TranscriptDecoder { listOf(SessionEvent.AssistantText(it)) }

    private fun texts(poll: TailPoll) = poll.events.map { (it as SessionEvent.AssistantText).text }

    private fun append(
        file: java.nio.file.Path,
        bytes: ByteArray,
    ) {
        Files.write(file, bytes, StandardOpenOption.APPEND)
    }

    @Test
    fun `从当前末尾开始，不倾倒历史`() {
        val file = temp.newFile("s.jsonl").toPath()
        Files.writeString(file, "old-1\nold-2\n")
        val tail = TranscriptTail(file, echo).apply { startAtEnd() }
        assertTrue(tail.poll().events.isEmpty())
        append(file, "new\n".toByteArray())
        assertEquals(listOf("new"), texts(tail.poll()))
    }

    @Test
    fun `从头读时跳过早于截止时间的复制历史，没有时间戳的行照常解码`() {
        val file = temp.newFile("s.jsonl").toPath()
        Files.writeString(
            file,
            """{"timestamp":"2026-10-28T01:00:00Z","n":"copied"}""" + "\n" +
                """{"n":"no-time"}""" + "\n" +
                """{"timestamp":"2026-10-28T02:00:01Z","n":"new"}""" + "\n",
        )
        val tail = TranscriptTail(file, echo, skipBefore = java.time.Instant.parse("2026-10-28T02:00:00Z"))
        assertEquals(listOf("""{"n":"no-time"}""", """{"timestamp":"2026-10-28T02:00:01Z","n":"new"}"""), texts(tail.poll()))
    }

    @Test
    fun `给了读取边界时只读到那里，其后的内容留给下一次`() {
        val file = temp.newFile("s.jsonl").toPath()
        val tail = TranscriptTail(file, echo).apply { startAtEnd() }
        append(file, "a\n".toByteArray())
        val mark = tail.size()
        append(file, "b\nc\n".toByteArray())
        assertEquals(listOf("a"), texts(tail.poll(mark)))
        assertEquals(listOf("b", "c"), texts(tail.poll()))
    }

    @Test
    fun `半行保留到换行到达，跨写入的多字节字符不乱码`() {
        val file = temp.newFile("s.jsonl").toPath()
        val tail = TranscriptTail(file, echo).apply { startAtEnd() }
        val line = "中文消息".toByteArray(Charsets.UTF_8)
        append(file, line.copyOfRange(0, 4))
        assertTrue(tail.poll().events.isEmpty())
        append(file, line.copyOfRange(4, line.size) + "\n".toByteArray())
        assertEquals(listOf("中文消息"), texts(tail.poll()))
    }

    @Test
    fun `文件变短视为被替换，从新末尾重新开始`() {
        val file = temp.newFile("s.jsonl").toPath()
        Files.writeString(file, "a\nb\n")
        val tail = TranscriptTail(file, echo).apply { startAtEnd() }
        Files.writeString(file, "x\n")
        val poll = tail.poll()
        assertTrue(poll.restarted)
        assertTrue(poll.events.isEmpty())
        append(file, "y\n".toByteArray())
        val next = tail.poll()
        assertFalse(next.restarted)
        assertEquals(listOf("y"), texts(next))
    }

    @Test
    fun `超长行被跳过，后续行正常`() {
        val file = temp.newFile("s.jsonl").toPath()
        val tail = TranscriptTail(file, echo, maxLineBytes = 8).apply { startAtEnd() }
        append(file, "0123456789abcdef\nok\n".toByteArray())
        assertEquals(listOf("ok"), texts(tail.poll()))
    }

    @Test
    fun `文件暂不存在时不报错`() {
        val file = temp.root.toPath().resolve("missing.jsonl")
        val tail = TranscriptTail(file, echo).apply { startAtEnd() }
        Files.writeString(file, "first\n")
        assertEquals(listOf("first"), texts(tail.poll()))
    }

    @Test
    fun `读取期间文件消失不抛异常`() {
        // 模拟 TOCTOU：attributes() 成功（同 inode、同 size）但 FileChannel.open 抛 IOException。
        // 方法：移除文件读权限——attributes() 仍能读取元数据，FileChannel.open 抛 AccessDeniedException。
        // 依赖 POSIX 权限：Windows 等不支持的平台跳过
        Assume.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        val file = temp.newFile("vanish.jsonl").toPath()
        Files.writeString(file, "line\n")
        val tail = TranscriptTail(file, echo).apply { startAtEnd() }
        append(file, "more\n".toByteArray())
        Files.setPosixFilePermissions(file, setOf(PosixFilePermission.OWNER_WRITE))
        try {
            // root 运行时移除读权限不生效，跳过而不是误判失败
            Assume.assumeFalse(Files.isReadable(file))
            val poll = tail.poll()
            assertTrue(poll.events.isEmpty())
            assertFalse(poll.restarted)
        } finally {
            // 恢复权限以便 TemporaryFolder 清理
            Files.setPosixFilePermissions(
                file,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}
