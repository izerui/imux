package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CodexSessionReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun reader() = CodexSessionReader(tmp.root.toPath())

    private fun writeRollout(
        uuid: String,
        cwd: String,
        body: String = "",
        source: String = "\"cli\"",
        threadSource: String? = null,
    ) {
        val dir = File(tmp.root, "sessions/2026/08/03").apply { mkdirs() }
        val threadSourceField =
            threadSource?.let { ""","thread_source":"$it"""" }.orEmpty()
        val meta =
            """{"timestamp":"2026-08-03T11:31:27.000Z","type":"session_meta","payload":{"id":"$uuid","cwd":"$cwd","source":$source$threadSourceField}}"""
        File(dir, "rollout-2026-08-03T11-31-27-$uuid.jsonl")
            .writeText(if (body.isEmpty()) meta else "$meta\n$body")
    }

    private fun userMessage(text: String) =
        """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"$text"}]}}"""

    @Test
    fun `只返回 cwd 与项目匹配的会话`() {
        writeRollout("uuid-mine", "/Users/demo/proj")
        writeRollout("uuid-other", "/Users/demo/other")

        val sessions = reader().read("/Users/demo/proj")

        assertEquals(1, sessions.size)
        assertEquals("uuid-mine", sessions[0].id)
        assertEquals(AgentType.CODEX, sessions[0].agentType)
    }

    @Test
    fun `过滤 review 子代理避免与父会话重复`() {
        val prompt = userMessage("Review the code changes against the base branch")
        writeRollout("uuid-parent", "/Users/demo/proj", prompt)
        writeRollout(
            "uuid-review",
            "/Users/demo/proj",
            prompt,
            source = """{"subagent":"review"}""",
            threadSource = "subagent",
        )

        val sessions = reader().read("/Users/demo/proj")

        assertEquals(listOf("uuid-parent"), sessions.map { it.id })
    }

    @Test
    fun `标题回退为首条用户消息`() {
        writeRollout("uuid-msg", "/Users/demo/proj", userMessage("帮我重构这个函数"))

        assertEquals("帮我重构这个函数", reader().read("/Users/demo/proj")[0].title)
    }

    @Test
    fun `过长的用户消息被截断并加省略号`() {
        writeRollout("uuid-long", "/Users/demo/proj", userMessage("很".repeat(120)))

        val title = reader().read("/Users/demo/proj")[0].title
        assertEquals(61, title.length)
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun `没有用户消息时回退为会话 id 短码`() {
        writeRollout("01abcdef-2222", "/Users/demo/proj")

        assertEquals("会话 01abcdef", reader().read("/Users/demo/proj")[0].title)
    }

    @Test
    fun `首行损坏的文件被跳过而不影响其他会话`() {
        writeRollout("uuid-ok", "/Users/demo/proj")
        File(tmp.root, "sessions/2026/08/03/rollout-2026-08-03T09-00-00-uuid-bad.jsonl")
            .writeText("这不是 json")

        assertEquals(1, reader().read("/Users/demo/proj").size)
    }

    @Test
    fun `会话根目录不存在时返回空列表`() {
        assertTrue(reader().read("/Users/demo/proj").isEmpty())
    }

    /**
     * 复现线上故障：真实 codex 会话中，一条用户消息的 text 值长达 11860 字符，
     * 用「交替分组 + 星号」的正则解析会按**被匹配值的长度**递归，直接 StackOverflowError。
     * 注意决定深度的是值本身而非整行长度——22KB 的 session_meta 行反而不炸。
     */
    @Test
    fun `超长的用户消息不会导致爆栈`() {
        writeRollout("uuid-huge", "/Users/demo/proj", userMessage("很长的内容".repeat(20_000)))

        val sessions = reader().read("/Users/demo/proj")

        assertEquals(1, sessions.size)
        assertEquals("uuid-huge", sessions[0].id)
        assertTrue("标题应被截断", sessions[0].title.endsWith("…"))
    }

    @Test
    fun `跨日期目录的会话都能读到`() {
        writeRollout("uuid-day3", "/Users/demo/proj")
        File(tmp.root, "sessions/2026/07/30").mkdirs()
        File(tmp.root, "sessions/2026/07/30/rollout-2026-07-30T10-00-00-uuid-day30.jsonl")
            .writeText("""{"type":"session_meta","payload":{"id":"uuid-day30","cwd":"/Users/demo/proj"}}""")

        assertEquals(2, reader().read("/Users/demo/proj").size)
    }

    // ---- 最后活动时刻 ----
    //
    // 与 claude 侧对齐：优先用记录自带的时刻，而不是文件 mtime。
    // mtime 反映的是「文件何时被写」，任何不含对话的追加、乃至外部工具 touch
    // 都会把它推到当下。而这个值不只用于列表排序——新建会话的绑定判据
    // （lastActiveAt >= pending.startedAt）也依赖它，错了会导致绑定静默失败。

    @Test
    fun `最后活动时刻取记录自带的时间戳而非 mtime`() {
        writeRollout(
            "uuid-ts",
            "/Users/demo/proj",
            """{"timestamp":"2026-08-03T12:00:00.000Z","type":"event_msg","payload":{"type":"task_complete"}}""",
        )

        // 文件是刚刚写的，mtime 就是此刻；取到 2026 那个值才说明用的是文件内时间戳
        assertEquals(
            java.time.Instant.parse("2026-08-03T12:00:00.000Z"),
            reader().read("/Users/demo/proj")[0].lastActiveAt,
        )
    }

    @Test
    fun `文件里没有时间戳时回退到 mtime`() {
        val dir = File(tmp.root, "sessions/2026/08/03").apply { mkdirs() }
        val file = File(dir, "rollout-2026-08-03T11-31-27-uuid-nots.jsonl").apply {
            writeText("""{"type":"session_meta","payload":{"id":"uuid-nots","cwd":"/Users/demo/proj"}}""")
        }

        assertEquals(
            file.lastModified(),
            reader().read("/Users/demo/proj")[0].lastActiveAt.toEpochMilli(),
        )
    }

    /** TurnWatcher 需要靠它定位文件做增量读取。 */
    @Test
    fun `会话带上自身文件路径`() {
        writeRollout("uuid-path", "/Users/demo/proj")

        val expected = File(tmp.root, "sessions/2026/08/03/rollout-2026-08-03T11-31-27-uuid-path.jsonl")
        assertEquals(expected.toPath(), reader().read("/Users/demo/proj")[0].filePath)
    }
}
