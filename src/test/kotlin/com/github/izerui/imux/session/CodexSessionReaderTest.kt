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

    // isWindows 显式传：默认值取 SystemInfo，那样每条用例的行为都会随主机平台变。
    private fun reader(isWindows: Boolean = false) = CodexSessionReader(tmp.root.toPath(), isWindows)

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
    fun `标题回退为最后一条用户消息`() {
        writeRollout(
            "uuid-msg",
            "/Users/demo/proj",
            userMessage("第一句话") + "\n" + userMessage("最后一句话"),
        )

        assertEquals("最后一句话", reader().read("/Users/demo/proj")[0].title)
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

        assertEquals("Session 01abcdef", reader().read("/Users/demo/proj")[0].title)
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

    // ---- codex-dev.db 过滤 ----

    private fun createLegacyDb(vararg rows: Pair<String, String>) {
        val file = File(tmp.root, "state_5.sqlite")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate("CREATE TABLE IF NOT EXISTS threads (id TEXT PRIMARY KEY, name TEXT, title TEXT)")
            }
            conn.prepareStatement("INSERT INTO threads (id, title) VALUES (?, ?)").use { stmt ->
                rows.forEach { (id, title) -> stmt.setString(1, id); stmt.setString(2, title); stmt.executeUpdate() }
            }
        }
    }

    private fun createDevDb(vararg ids: String) {
        val dir = File(tmp.root, "sqlite").apply { mkdirs() }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${File(dir, "codex-dev.db").absolutePath}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE local_thread_catalog (host_id TEXT, thread_id TEXT, display_title TEXT, " +
                        "source_created_at REAL, source_updated_at REAL, source_kind TEXT, " +
                        "observation_sequence INTEGER, missing_candidate INTEGER DEFAULT 0, " +
                        "source_recency_at REAL DEFAULT 0, pending_observed_title INTEGER DEFAULT 0, " +
                        "PRIMARY KEY (host_id, thread_id))",
                )
            }
            if (ids.isNotEmpty()) {
                conn.prepareStatement(
                    "INSERT INTO local_thread_catalog (host_id, thread_id, display_title, " +
                        "source_created_at, source_updated_at, source_kind, observation_sequence) " +
                        "VALUES ('host', ?, '', 0, 0, 'cli', 0)",
                ).use { stmt ->
                    ids.forEach { id -> stmt.setString(1, id); stmt.executeUpdate() }
                }
            }
        }
    }

    @Test
    fun `codex-dev db 存在时只显示其中的会话`() {
        writeRollout("uuid-in-db", "/Users/demo/proj", userMessage("DB 内的会话"))
        writeRollout("uuid-not-in-db", "/Users/demo/proj", userMessage("DB 外的会话"))
        createDevDb("uuid-in-db")

        val sessions = reader().read("/Users/demo/proj")
        assertEquals(listOf("uuid-in-db"), sessions.map { it.id })
    }

    /**
     * codex-dev.db 存在但表为空 → 所有 rollout 都被过滤掉，即使旧库里有该会话。
     * 有 DB 说明用户在用新版 Codex，空表就是没有会话，不该回退显示旧数据。
     */
    @Test
    fun `codex-dev db 为空时不显示任何 rollout 即使旧库有记录`() {
        writeRollout("uuid-orphan", "/Users/demo/proj", userMessage("旧会话"))
        createDevDb() // 空表
        // 旧库里有这个会话的标题——不该因此让它重新出现
        createLegacyDb("uuid-orphan" to "旧库标题")

        assertTrue(reader().read("/Users/demo/proj").isEmpty())
    }

    @Test
    fun `数据库全部不可读时仍显示正常 rollout 并回退用户消息`() {
        writeRollout("uuid-readable", "/Users/demo/proj", userMessage("继续修复"))
        val state = File(tmp.root, "state_5.sqlite")
        state.writeText("损坏的 state")
        val singleFailure = reader().read("/Users/demo/proj")
        assertEquals("仅 state 损坏时仍显示 rollout", listOf("uuid-readable"), singleFailure.map { it.id })
        assertEquals("仅 state 损坏时使用用户消息", "继续修复", singleFailure.single().title)

        File(tmp.root, "sqlite").mkdirs()
        val dev = File(tmp.root, "sqlite/codex-dev.db")
        dev.writeText("损坏的 dev")
        assertTrue(state.delete())
        val devFailure = reader().read("/Users/demo/proj")
        assertEquals("仅 dev 损坏时仍显示 rollout", listOf("uuid-readable"), devFailure.map { it.id })
        assertEquals("仅 dev 损坏时使用用户消息", "继续修复", devFailure.single().title)

        state.writeText("损坏的 state")
        val sessions = reader().read("/Users/demo/proj")

        assertEquals("双库损坏时仍显示 rollout", listOf("uuid-readable"), sessions.map { it.id })
        assertEquals("双库损坏时使用用户消息", "继续修复", sessions.single().title)
    }

    @Test
    fun `dev 损坏而 state 可读时仍按 state 过滤`() {
        writeRollout("uuid-in-state", "/Users/demo/proj", userMessage("当前任务"))
        writeRollout("uuid-orphan", "/Users/demo/proj", userMessage("旧任务"))
        createLegacyDb("uuid-in-state" to "数据库标题")
        File(tmp.root, "sqlite").mkdirs()
        File(tmp.root, "sqlite/codex-dev.db").writeText("损坏的 dev")

        val sessions = reader().read("/Users/demo/proj")
        assertEquals(listOf("uuid-in-state"), sessions.map { it.id })
        assertEquals("数据库标题", sessions.single().title)
    }

    @Test
    fun `state 损坏而 dev 可读时仍按 dev 过滤`() {
        writeRollout("uuid-in-dev", "/Users/demo/proj", userMessage("当前任务"))
        writeRollout("uuid-orphan", "/Users/demo/proj", userMessage("旧任务"))
        createDevDb("uuid-in-dev")
        File(tmp.root, "state_5.sqlite").writeText("损坏的 state")

        assertEquals(listOf("uuid-in-dev"), reader().read("/Users/demo/proj").map { it.id })
    }

    @Test
    fun `成功读取的空 dev 库仍过滤孤立 rollout`() {
        writeRollout("uuid-orphan", "/Users/demo/proj", userMessage("旧内容"))
        createDevDb()

        assertTrue(reader().read("/Users/demo/proj").isEmpty())
    }

    @Test
    fun `残留 dev 库不遮蔽 state 中的新会话`() {
        writeRollout("uuid-new", "/Users/demo/proj", userMessage("新任务"))
        createDevDb("uuid-old")
        val state = File(tmp.root, "state_5.sqlite")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${state.absolutePath}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate("CREATE TABLE threads (id TEXT PRIMARY KEY, title TEXT, created_at INTEGER)")
                it.executeUpdate("INSERT INTO threads VALUES ('uuid-new', '', 2000)")
            }
        }

        val model = SessionListModel(
            scan = { reader().read("/Users/demo/proj") },
            clock = { java.time.Instant.parse("2026-08-03T11:00:00Z") },
        )
        val pending = model.registerPending(AgentType.CODEX)
        model.refresh()

        val sessions = reader().read("/Users/demo/proj")
        assertEquals(listOf("uuid-new"), sessions.map { it.id })
        assertEquals("新任务", sessions.single().title)
        assertEquals("uuid-new", model.boundIdFor(pending.key))
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
        val file =
            File(dir, "rollout-2026-08-03T11-31-27-uuid-nots.jsonl").apply {
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

    /**
     * Windows 上 rollout 里的 cwd 是原生反斜杠，而 `Project.getBasePath()` 标着
     * `@SystemIndependent`、返回正斜杠。不换算的话**一个 codex 会话都列不出来**——
     * 会话列表整片空白，且不报错；本任务做的漂移探测也就无处落地。
     */
    @Test
    fun `Windows 写法的 cwd 也能匹配上项目`() {
        // JSON 字符串里的一个反斜杠要写成两个
        writeRollout("uuid-win", "C:\\\\Users\\\\demo\\\\proj")

        val sessions = reader(isWindows = true).read("C:/Users/demo/proj")

        assertEquals(1, sessions.size)
        assertEquals("uuid-win", sessions[0].id)
    }

    /**
     * 换算必须**两侧都做**。
     *
     * 上一条（rollout 反斜杠、projectPath 正斜杠）只逼出「换算 rollout 那一侧」；
     * 这一条把两边对调——rollout 正斜杠、projectPath 反斜杠——只换算 rollout 那一侧
     * 就会红。两条合起来才把「两侧都换」钉死。
     */
    @Test
    fun `projectPath 那一侧的反斜杠同样要换算`() {
        writeRollout("uuid-a", "C:/Users/demo/proj")

        val sessions = reader(isWindows = true).read("C:\\Users\\demo\\proj")

        assertEquals(1, sessions.size)
        assertEquals("uuid-a", sessions[0].id)
    }

    /**
     * macOS 与 Linux 上换算必须是**恒等变换**：两侧本来就都是正斜杠，一个字节都不能变。
     *
     * 这条同时钉住反面——不匹配的项目仍然要被排除，别为了「宽容」把两个项目混到一起。
     */
    @Test
    fun `POSIX 上的匹配与改动前逐字节一致`() {
        writeRollout("uuid-posix", "/Users/demo/proj")
        writeRollout("uuid-other", "/Users/demo/other")

        val sessions = reader().read("/Users/demo/proj")

        assertEquals(1, sessions.size)
        assertEquals("uuid-posix", sessions[0].id)
        assertTrue(reader().read("/Users/demo/nowhere").isEmpty())
    }

    /** 只换分隔符，不折叠盘符大小写：认不出就跳过，认错比不列出糟得多。 */
    @Test
    fun `不同盘符大小写不算同一个目录`() {
        assertTrue(sameCodexCwd("C:\\Users\\demo", "C:/Users/demo", isWindows = true))
        assertEquals(false, sameCodexCwd("c:/Users/demo", "C:/Users/demo", isWindows = true))
    }

    /**
     * **POSIX 上换算是彻底的恒等变换，连反斜杠都不换。**
     *
     * `\` 在 POSIX 上是合法的目录名字符，无条件替换会把 `/tmp/a\b` 与 `/tmp/a/b`
     * 判成同一个目录——那是把标签认到**别的项目**上，比匹配不上糟得多。
     * `fileNameOf` 与 `executableMatches` 的 POSIX 侧是同一族的另外两处。
     */
    @Test
    fun `POSIX 路径的换算是恒等变换`() {
        assertEquals("/Users/demo/proj", codexCwdKey("/Users/demo/proj", isWindows = false))
        assertEquals("", codexCwdKey("", isWindows = false))
        assertEquals("反斜杠在 POSIX 上是目录名的一部分", "/tmp/a\\b", codexCwdKey("/tmp/a\\b", isWindows = false))
        assertEquals(
            "POSIX 上这两个是不同的目录，混为一谈就是把标签认到别的项目上",
            false,
            sameCodexCwd("/tmp/a\\b", "/tmp/a/b", isWindows = false),
        )
        // 反向：Windows 上它们确实是同一个目录，否则一个 codex 会话都匹配不上
        assertTrue(sameCodexCwd("C:\\a\\b", "C:/a/b", isWindows = true))
    }
}
