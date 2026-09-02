package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

class NavigationTranscriptIndexTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun session(
        type: AgentType,
        file: Path,
    ) = AgentSession("session-1", "标题", type, NOW, NOW, file)

    private fun append(
        file: Path,
        text: String,
    ) {
        Files.writeString(file, text, StandardOpenOption.APPEND)
    }

    @Test
    fun `三种 CLI 的追加消息分别进入统一轮次`() {
        val fixtures =
            listOf(
                AgentType.CLAUDE to
                    Pair(
                        """{"type":"user","message":{"role":"user","content":"Claude 提问"}}""" + "\n",
                        """{"type":"assistant","message":{"role":"assistant","content":"Claude 回复"}}""" + "\n",
                    ),
                AgentType.CODEX to
                    Pair(
                        """{"type":"response_item","payload":{"role":"user","content":[{"type":"input_text","text":"Codex 提问"}]}}""" + "\n",
                        """{"type":"response_item","payload":{"role":"assistant","content":[{"type":"output_text","text":"Codex 回复"}]}}""" +
                            "\n",
                    ),
                AgentType.PI to
                    Pair(
                        """{"type":"message","message":{"role":"user","content":[{"type":"text","text":"Pi 提问"}]}}""" + "\n",
                        """{"type":"message","message":{"role":"assistant","content":[{"type":"text","text":"Pi 回复"}]}}""" + "\n",
                    ),
            )

        fixtures.forEach { (type, records) ->
            val file = temp.newFile("${type.cli}.jsonl").toPath()
            val index = NavigationTranscriptIndex()
            val currentSession = session(type, file)

            append(file, records.first)
            val userUpdate = index.refresh(currentSession)
            append(file, records.second)
            val assistantUpdate = index.refresh(currentSession)

            assertEquals("${type.cli} 应产生一轮", 1, assistantUpdate.exchanges.size)
            assertEquals(
                "${type.cli} 应保留用户文本",
                "${type.displayName.substringBefore(' ')} 提问",
                assistantUpdate.exchanges.single().userText,
            )
            assertEquals(
                "${type.cli} 应配对助手回复",
                "${type.displayName.substringBefore(' ')} 回复",
                assistantUpdate.exchanges.single().assistantReply,
            )
            assertTrue("${type.cli} 用户追加应报告变化", userUpdate.changed)
            assertTrue("${type.cli} 回复追加应报告变化", assistantUpdate.changed)
        }
    }

    @Test
    fun `增量读取失败后保留旧轮次并在重试时恢复`() {
        val file = temp.newFile("retry-read.jsonl").toPath()
        var failOpen = false
        val index =
            NavigationTranscriptIndex(
                openChannel = { path ->
                    if (failOpen) throw IOException("temporary failure")
                    FileChannel.open(path, StandardOpenOption.READ)
                },
            )
        val currentSession = session(AgentType.PI, file)
        append(file, """{"type":"message","message":{"role":"user","content":"旧问题"}}""" + "\n")
        index.refresh(currentSession)
        append(file, """{"type":"message","message":{"role":"user","content":"新问题"}}""" + "\n")

        failOpen = true
        val failed = index.refresh(currentSession)
        failOpen = false
        val recovered = index.refresh(currentSession)

        assertEquals(listOf("旧问题"), failed.exchanges.map(SessionExchange::userText))
        assertFalse("保留旧快照不应伪造语义变化", failed.changed)
        assertEquals(listOf("旧问题", "新问题"), recovered.exchanges.map(SessionExchange::userText))
        assertTrue(recovered.changed)
    }

    @Test
    fun `切到尚未落盘的会话时清除旧轮次`() {
        val oldFile = temp.newFile("old-session.jsonl").toPath()
        append(oldFile, """{"type":"message","message":{"role":"user","content":"旧问题"}}""" + "\n")
        val index = NavigationTranscriptIndex()
        index.refresh(session(AgentType.PI, oldFile))
        val missingFile = temp.root.toPath().resolve("new-session.jsonl")

        val switched = index.refresh(session(AgentType.PI, missingFile))

        assertTrue(switched.exchanges.isEmpty())
        assertTrue("清掉旧会话内容本身就是语义变化", switched.changed)
    }

    @Test
    fun `文件未增长时不重复读取 transcript`() {
        val file = temp.newFile("unchanged.jsonl").toPath()
        append(file, """{"type":"message","message":{"role":"user","content":"问题"}}""" + "\n")
        val index = NavigationTranscriptIndex()
        val currentSession = session(AgentType.PI, file)

        val first = index.refresh(currentSession)
        val bytesAfterFirstRead = index.totalBytesRead
        val second = index.refresh(currentSession)

        assertTrue(first.changed)
        assertFalse(second.changed)
        assertEquals(bytesAfterFirstRead, index.totalBytesRead)
    }

    @Test
    fun `末行缺少换行时发布一次且后续分隔符不重复`() {
        val file = temp.newFile("no-final-newline.jsonl").toPath()
        val index = NavigationTranscriptIndex()
        val currentSession = session(AgentType.PI, file)
        append(file, """{"type":"message","message":{"role":"user","content":"末行问题"}}""")

        val withoutNewline = index.refresh(currentSession)
        append(
            file,
            "\n" + """{"type":"message","message":{"role":"assistant","content":"末行回复"}}""" + "\n",
        )
        val completed = index.refresh(currentSession)

        assertEquals(listOf("末行问题"), withoutNewline.exchanges.map(SessionExchange::userText))
        assertEquals(1, completed.exchanges.size)
        assertEquals("末行回复", completed.exchanges.single().assistantReply)
    }

    @Test
    fun `半行等到换行落盘后才发布消息`() {
        val file = temp.newFile("partial.jsonl").toPath()
        val index = NavigationTranscriptIndex()
        val currentSession = session(AgentType.PI, file)
        val record = """{"type":"message","message":{"role":"user","content":"分段写入"}}"""

        append(file, record.substring(0, record.length / 2))
        val partial = index.refresh(currentSession)
        append(file, record.substring(record.length / 2) + "\n")
        val completed = index.refresh(currentSession)

        assertTrue(partial.exchanges.isEmpty())
        assertEquals("分段写入", completed.exchanges.single().userText)
    }

    @Test
    fun `超大未换行记录被丢弃后继续解析下一条`() {
        val file = temp.newFile("oversized-line.jsonl").toPath()
        val index = NavigationTranscriptIndex(maxPendingLineBytes = 256)
        val currentSession = session(AgentType.PI, file)

        append(file, "x".repeat(512) + "\n")
        index.refresh(currentSession)
        append(file, """{"type":"message","message":{"role":"user","content":"恢复解析"}}""" + "\n")
        val recovered = index.refresh(currentSession)

        assertEquals(listOf("恢复解析"), recovered.exchanges.map(SessionExchange::userText))
    }

    @Test
    fun `文件截短后丢弃旧轮次并重建`() {
        val file = temp.newFile("truncated.jsonl").toPath()
        append(file, """{"type":"message","message":{"role":"user","content":"长度更长的旧问题"}}""" + "\n")
        val index = NavigationTranscriptIndex()
        val currentSession = session(AgentType.PI, file)
        index.refresh(currentSession)

        Files.writeString(file, """{"type":"message","message":{"role":"user","content":"新问题"}}""" + "\n")
        val rebuilt = index.refresh(currentSession)

        assertEquals(listOf("新问题"), rebuilt.exchanges.map(SessionExchange::userText))
        assertTrue(rebuilt.changed)
    }

    @Test
    fun `首条回复落盘后后续助手消息不改变轮次预览`() {
        val file = temp.newFile("reply.jsonl").toPath()
        append(
            file,
            """{"type":"message","message":{"role":"user","content":"问题"}}
{"type":"message","message":{"role":"assistant","content":"首条回复"}}
""",
        )
        val index = NavigationTranscriptIndex()
        val currentSession = session(AgentType.PI, file)
        index.refresh(currentSession)
        val bytesBefore = index.totalBytesRead

        append(file, """{"type":"message","message":{"role":"assistant","content":"后续汇报"}}""" + "\n")
        val update = index.refresh(currentSession)

        assertFalse(update.changed)
        assertEquals("首条回复", update.exchanges.single().assistantReply)
        assertTrue(index.totalBytesRead > bytesBefore)
    }

    @Test
    fun `同批树节点解析结束后重建一次分支`() {
        val file = temp.newFile("batched-tree.jsonl").toPath()
        append(
            file,
            """
            {"type":"message","id":"u1","parentId":null,"message":{"role":"user","content":"问题一"}}
            {"type":"message","id":"a1","parentId":"u1","message":{"role":"assistant","content":"回复一"}}
            {"type":"message","id":"u2","parentId":"a1","message":{"role":"user","content":"问题二"}}
            {"type":"message","id":"a2","parentId":"u2","message":{"role":"assistant","content":"回复二"}}
            """.trimIndent() + "\n",
        )
        val index = NavigationTranscriptIndex()

        index.refresh(session(AgentType.PI, file))

        assertEquals(1, index.branchRebuildCount)
    }

    @Test
    fun `Pi 分支切换后只保留当前 lineage 的用户消息`() {
        val file = temp.newFile("pi-branch.jsonl").toPath()
        append(
            file,
            """
            {"type":"session","version":3,"id":"session-1","cwd":"/tmp"}
            {"type":"message","id":"u1","parentId":null,"message":{"role":"user","content":"根问题"}}
            {"type":"message","id":"a1","parentId":"u1","message":{"role":"assistant","content":"根回复"}}
            {"type":"message","id":"u-old","parentId":"a1","message":{"role":"user","content":"废弃分支"}}
            {"type":"message","id":"a-old","parentId":"u-old","message":{"role":"assistant","content":"废弃回复"}}
            {"type":"branch_summary","id":"branch","parentId":"a1","summary":"切换分支"}
            {"type":"message","id":"u-new","parentId":"branch","message":{"role":"user","content":"当前分支"}}
            {"type":"message","id":"a-new","parentId":"u-new","message":{"role":"assistant","content":"当前回复"}}
            """.trimIndent() + "\n",
        )

        val update = NavigationTranscriptIndex().refresh(session(AgentType.PI, file))

        assertEquals(listOf("根问题", "当前分支"), update.exchanges.map(SessionExchange::userText))
        assertEquals(listOf("根回复", "当前回复"), update.exchanges.map(SessionExchange::assistantReply))
    }

    @Test
    fun `Claude parentUuid 切换后排除废弃分支`() {
        val file = temp.newFile("claude-branch.jsonl").toPath()
        append(
            file,
            """
            {"type":"user","uuid":"u1","parentUuid":null,"message":{"role":"user","content":"根问题"}}
            {"type":"assistant","uuid":"a1","parentUuid":"u1","message":{"role":"assistant","content":"根回复"}}
            {"type":"user","uuid":"u-old","parentUuid":"a1","message":{"role":"user","content":"废弃分支"}}
            {"type":"user","uuid":"u-new","parentUuid":"a1","message":{"role":"user","content":"当前分支"}}
            {"type":"assistant","uuid":"a-new","parentUuid":"u-new","message":{"role":"assistant","content":"当前回复"}}
            """.trimIndent() + "\n",
        )

        val update = NavigationTranscriptIndex().refresh(session(AgentType.CLAUDE, file))

        assertEquals(listOf("根问题", "当前分支"), update.exchanges.map(SessionExchange::userText))
        assertEquals(listOf("根回复", "当前回复"), update.exchanges.map(SessionExchange::assistantReply))
    }

    @Test
    fun `同文本分支切换仍报告语义变化`() {
        val file = temp.newFile("same-text-branch.jsonl").toPath()
        append(
            file,
            """
            {"type":"message","id":"u1","parentId":null,"message":{"role":"user","content":"根问题"}}
            {"type":"message","id":"a1","parentId":"u1","message":{"role":"assistant","content":"根回复"}}
            {"type":"message","id":"u-old","parentId":"a1","message":{"role":"user","content":"重复文本"}}
            """.trimIndent() + "\n",
        )
        val index = NavigationTranscriptIndex()
        val currentSession = session(AgentType.PI, file)
        index.refresh(currentSession)

        append(
            file,
            """{"type":"message","id":"u-new","parentId":"a1","message":{"role":"user","content":"重复文本"}}""" +
                "\n",
        )
        val switched = index.refresh(currentSession)

        assertEquals(listOf("根问题", "重复文本"), switched.exchanges.map(SessionExchange::userText))
        assertTrue("节点 ID 已变化，即使文本相同也要重新定位", switched.changed)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-01T00:00:00Z")
    }
}
