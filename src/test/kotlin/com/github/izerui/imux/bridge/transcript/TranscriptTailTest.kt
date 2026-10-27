package com.github.izerui.imux.bridge.transcript

import com.github.izerui.imux.bridge.core.SessionEvent
import com.github.izerui.imux.bridge.core.SessionEventKind
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

    private val echo = TranscriptDecoder { listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, it)) }

    private fun texts(poll: TailPoll) = poll.events.map { it.text }

    private fun append(
        file: java.nio.file.Path,
        bytes: ByteArray,
    ) {
        Files.write(file, bytes, StandardOpenOption.APPEND)
    }

    @Test
    fun `从绑定时记下的边界开始：之后才建立尾随器，也读到边界之后写入的回复`() {
        val file = temp.newFile("s.jsonl").toPath()
        Files.writeString(file, "history\n")
        val boundary = TranscriptTail.boundaryOf(file)
        append(file, "first-reply\n".toByteArray())
        val tail = TranscriptTail(file, echo).apply { startAt(boundary) }
        assertEquals(listOf("first-reply"), texts(tail.poll()))
    }

    @Test
    fun `绑定时文件尚不存在，边界为 0，出现后从头读取`() {
        val file = temp.root.toPath().resolve("later.jsonl")
        val boundary = TranscriptTail.boundaryOf(file)
        Files.writeString(file, "first\n")
        val tail = TranscriptTail(file, echo).apply { startAt(boundary) }
        assertEquals(listOf("first"), texts(tail.poll()))
    }

    @Test
    fun `边界之后文件被替换，按重启处理不倒灌`() {
        val file = temp.newFile("s.jsonl").toPath()
        Files.writeString(file, "history-long-enough\n")
        val boundary = TranscriptTail.boundaryOf(file)
        Files.delete(file)
        Files.writeString(file, "x\n")
        val tail = TranscriptTail(file, echo).apply { startAt(boundary) }
        val poll = tail.poll()
        assertTrue(poll.restarted)
        assertTrue(poll.events.isEmpty())
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
    fun `超长行被跳过并计数，后续行正常`() {
        val file = temp.newFile("s.jsonl").toPath()
        val tail = TranscriptTail(file, echo, maxLineBytes = 8).apply { startAtEnd() }
        append(file, "0123456789abcdef\nok\n".toByteArray())
        assertEquals(listOf("ok"), texts(tail.poll()))
        assertEquals(1, tail.skippedOversizeLines)
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
