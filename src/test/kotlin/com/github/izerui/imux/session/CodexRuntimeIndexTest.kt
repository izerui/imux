package com.github.izerui.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager
import java.time.Instant

class CodexRuntimeIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val sqliteDir = TemporaryFolder()

    // 用同一个驱动造结构一致的库，比塞二进制夹具可读得多。
    // 生产代码**不得**这么用 DriverManager，理由见 CodexRuntimeIndex 的 KDoc。
    private fun createLogsDbIn(dir: File, name: String, vararg rows: Triple<String, String?, Long>) {
        val file = File(dir, name)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE logs (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "ts INTEGER, ts_nanos INTEGER DEFAULT 0, " +
                        "thread_id TEXT, process_uuid TEXT)",
                )
            }
            conn.prepareStatement("INSERT INTO logs (process_uuid, thread_id, ts) VALUES (?, ?, ?)")
                .use { stmt ->
                    rows.forEach { (uuid, thread, ts) ->
                        stmt.setString(1, uuid)
                        stmt.setString(2, thread)
                        stmt.setLong(3, ts)
                        stmt.executeUpdate()
                    }
                }
        }
    }

    private fun createLogsDb(name: String, vararg rows: Triple<String, String?, Long>) =
        createLogsDbIn(tmp.root, name, *rows)

    private fun createStateDbIn(dir: File, name: String, vararg rows: Pair<String, String>) {
        val file = File(dir, name)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate("CREATE TABLE threads (id TEXT PRIMARY KEY, rollout_path TEXT NOT NULL)")
            }
            conn.prepareStatement("INSERT INTO threads (id, rollout_path) VALUES (?, ?)").use { stmt ->
                rows.forEach { (id, path) ->
                    stmt.setString(1, id)
                    stmt.setString(2, path)
                    stmt.executeUpdate()
                }
            }
        }
    }

    private fun createStateDb(name: String, vararg rows: Pair<String, String>) =
        createStateDbIn(tmp.root, name, *rows)

    /**
     * 默认注入「取不到启动时刻」。
     *
     * 不注入的话默认实参会去问 ProcessHandle，而本机此刻恰好有没有 pid 4197
     * 是不确定的：有的话它的启动时刻是今天，夹具里的 ts=100（1970 年）就会被
     * 当成陈旧运行挡掉，这批用例会随机器而红。陈旧判断本身由下面那组用例专测。
     */
    private fun index(processStartOf: (Long) -> Instant? = { null }) =
        CodexRuntimeIndex(tmp.root.toPath(), processStartOf)

    // ---- 纯函数：版本化文件名 ----

    @Test
    fun `版本化文件名取版本号最大的那个`() {
        // codex 的库名带 schema 版本（logs_2 / state_5 / goals_1），升级会换名。
        // 写死名字会在下一次 schema 变更时静默失效——症状与「功能没做」不可区分。
        assertEquals(
            "logs_10.sqlite",
            latestVersionedDb(listOf("logs_2.sqlite", "logs_10.sqlite", "logs_9.sqlite"), "logs"),
        )
    }

    @Test
    fun `版本号按数值比而不是按字典序`() {
        assertEquals("logs_10.sqlite", latestVersionedDb(listOf("logs_9.sqlite", "logs_10.sqlite"), "logs"))
    }

    @Test
    fun `只认自己那个前缀`() {
        assertNull(latestVersionedDb(listOf("state_5.sqlite", "goals_1.sqlite"), "logs"))
        assertEquals("state_5.sqlite", latestVersionedDb(listOf("state_5.sqlite", "logs_2.sqlite"), "state"))
    }

    @Test
    fun `wal 与 shm 旁文件不算数据库`() {
        // 目录里每个库都跟着 -wal 和 -shm，选错会打开一个不是数据库的文件
        assertNull(latestVersionedDb(listOf("logs_2.sqlite-wal"), "logs"))
        assertNull(latestVersionedDb(listOf("logs_2.sqlite-shm"), "logs"))
    }

    @Test
    fun `没有匹配时返回 null`() {
        assertNull(latestVersionedDb(emptyList(), "logs"))
        assertNull(latestVersionedDb(listOf("logs.sqlite", "logs_x.sqlite"), "logs"))
    }

    // ---- 纯函数：sqlite_home ----

    @Test
    fun `顶层的 sqlite_home 会被取出来`() {
        assertEquals("/tmp/codexdb", codexSqliteHomeFrom("model = \"gpt-5\"\nsqlite_home = \"/tmp/codexdb\"\n"))
    }

    @Test
    fun `段落里的同名键不算顶层`() {
        // sqlite_home 只有作为顶层键才是库目录；[某段] 之后的同名键是别的东西
        assertNull(codexSqliteHomeFrom("[tui]\nsqlite_home = \"/tmp/x\"\n"))
    }

    @Test
    fun `没有 sqlite_home 或读不到配置时返回 null`() {
        assertNull(codexSqliteHomeFrom("model = \"gpt-5\"\n"))
        assertNull(codexSqliteHomeFrom(null))
        assertNull(codexSqliteHomeFrom(""))
    }

    @Test
    fun `行内注释不会被当成路径的一部分`() {
        assertEquals("/tmp/a", codexSqliteHomeFrom("sqlite_home = \"/tmp/a\"  # 注释\n"))
    }

    // ---- 端到端 ----

    @Test
    fun `由 pid 查出 rollout 路径`() {
        val thread = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        val rollout = "/Users/me/.codex/sessions/2026/08/19/rollout-2026-08-19T21-15-42-$thread.jsonl"
        createLogsDb("logs_2.sqlite", Triple("pid:4197:9b0b-uuid", thread, 100L))
        createStateDb("state_5.sqlite", thread to rollout)

        assertEquals(rollout, index().rolloutPathOf(4197))
    }

    @Test
    fun `同一个 pid 有多行时取时间戳最新的那一行`() {
        // 用户敲 /new 之后同一个进程会写出新 thread 的日志行；旧行仍在
        val old = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        val new = "01a02cda-8081-7113-b026-607a21c0da98"
        createLogsDb(
            "logs_2.sqlite",
            Triple("pid:4197:u", old, 100L),
            Triple("pid:4197:u", new, 200L),
        )
        createStateDb("state_5.sqlite", old to "/old.jsonl", new to "/new.jsonl")

        assertEquals("/new.jsonl", index().rolloutPathOf(4197))
    }

    @Test
    fun `同一秒内按 ts_nanos 取更晚的那行`() {
        // ts 是秒，亚秒在独立的 ts_nanos 列；不做 tie-break 会由查询计划任意裁决。
        // 故意让 new（ts_nanos 大）先插入拿到较小的 id，使 id 排序与 ts_nanos 排序相反，
        // 这样去掉 ts_nanos DESC 就会选到 old。
        val old = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        val new = "01a02cda-8081-7113-b026-607a21c0da98"
        val file = File(tmp.root, "logs_2.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE logs (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "ts INTEGER, ts_nanos INTEGER DEFAULT 0, " +
                        "thread_id TEXT, process_uuid TEXT)",
                )
            }
            conn.prepareStatement(
                "INSERT INTO logs (process_uuid, thread_id, ts, ts_nanos) VALUES (?, ?, ?, ?)",
            ).use { stmt ->
                // new 先插入 → id=1，old 后插入 → id=2
                stmt.setString(1, "pid:4197:u"); stmt.setString(2, new)
                stmt.setLong(3, 100L); stmt.setLong(4, 900L); stmt.executeUpdate()
                stmt.setString(1, "pid:4197:u"); stmt.setString(2, old)
                stmt.setLong(3, 100L); stmt.setLong(4, 500L); stmt.executeUpdate()
            }
        }
        createStateDb("state_5.sqlite", old to "/old.jsonl", new to "/new.jsonl")

        assertEquals("/new.jsonl", index().rolloutPathOf(4197))
    }

    @Test
    fun `pid 前缀必须整段匹配`() {
        // pid:419 不能命中 pid:4197；pid:41970 也不能
        val thread = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        createLogsDb(
            "logs_2.sqlite",
            Triple("pid:41970:u", thread, 100L),
            Triple("pid:419:u", thread, 100L),
        )
        createStateDb("state_5.sqlite", thread to "/x.jsonl")

        assertNull(index().rolloutPathOf(4197))
    }

    @Test
    fun `thread_id 为空的行被跳过`() {
        // codex 有大量与会话无关的日志行，thread_id 为 NULL
        val thread = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        createLogsDb(
            "logs_2.sqlite",
            Triple("pid:4197:u", thread, 100L),
            Triple("pid:4197:u", null, 300L),
        )
        createStateDb("state_5.sqlite", thread to "/x.jsonl")

        assertEquals("/x.jsonl", index().rolloutPathOf(4197))
    }

    @Test
    fun `查不到时返回 null 而不是猜`() {
        val thread = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        createLogsDb("logs_2.sqlite", Triple("pid:4197:u", thread, 100L))
        createStateDb("state_5.sqlite", thread to "/x.jsonl")

        // 没有这个 pid
        assertNull(index().rolloutPathOf(9999))
    }

    @Test
    fun `logs 库缺失时返回 null`() {
        createStateDb("state_5.sqlite", "t" to "/x.jsonl")
        assertNull(index().rolloutPathOf(4197))
    }

    @Test
    fun `state 库缺失时返回 null`() {
        createLogsDb("logs_2.sqlite", Triple("pid:4197:u", "t", 100L))
        assertNull(index().rolloutPathOf(4197))
    }

    @Test
    fun `表结构变了也只是返回 null`() {
        // codex 的私有实现细节，schema 变更不得影响会话列表本身
        val file = File(tmp.root, "logs_2.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { it.executeUpdate("CREATE TABLE logs (unrelated TEXT)") }
        }
        createStateDb("state_5.sqlite", "t" to "/x.jsonl")

        assertNull(index().rolloutPathOf(4197))
    }

    // ---- 陈旧运行：pid 被复用之后不能认上一任的 thread ----

    @Test
    fun `日志行早于进程启动时刻时不认领`() {
        // Windows 的 pid 池小、按 4 递增，复用频繁。新 codex 拿到一个被复用的 pid、
        // 还一行日志都没写出来时，日志里那条属于上一任的行会被整条链老老实实认下来：
        // rollout_path 是真路径、UUID 合法、KeyDrift 成立，没有一环有机会说「这不对」。
        val thread = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        createLogsDb("logs_2.sqlite", Triple("pid:4197:oldrun", thread, 100L))
        createStateDb("state_5.sqlite", thread to "/old.jsonl")

        assertNull(index { Instant.ofEpochSecond(5_000) }.rolloutPathOf(4197))
    }

    @Test
    fun `日志行晚于进程启动时刻时正常认领`() {
        val thread = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        createLogsDb("logs_2.sqlite", Triple("pid:4197:run", thread, 5_100L))
        createStateDb("state_5.sqlite", thread to "/live.jsonl")

        assertEquals("/live.jsonl", index { Instant.ofEpochSecond(5_000) }.rolloutPathOf(4197))
    }

    @Test
    fun `略早于启动时刻但落在宽容窗内仍然认领`() {
        // codex 的 ts 与 OS 报的启动时刻来自同一台机器，但精度与取整方式不同，
        // 卡死会误杀刚起来那一瞬间写下的行
        val thread = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        createLogsDb("logs_2.sqlite", Triple("pid:4197:run", thread, 4_996L))
        createStateDb("state_5.sqlite", thread to "/live.jsonl")

        assertEquals("/live.jsonl", index { Instant.ofEpochSecond(5_000) }.rolloutPathOf(4197))
    }

    @Test
    fun `早出宽容窗的日志行被挡掉`() {
        val thread = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        createLogsDb("logs_2.sqlite", Triple("pid:4197:oldrun", thread, 4_994L))
        createStateDb("state_5.sqlite", thread to "/old.jsonl")

        assertNull(index { Instant.ofEpochSecond(5_000) }.rolloutPathOf(4197))
    }

    @Test
    fun `取不到启动时刻时不做陈旧判断`() {
        // 进程已退出、权限不足都会走到这里。少一道保险好过把能用的变成不能用的。
        val thread = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        createLogsDb("logs_2.sqlite", Triple("pid:4197:run", thread, 100L))
        createStateDb("state_5.sqlite", thread to "/live.jsonl")

        assertEquals("/live.jsonl", index { null }.rolloutPathOf(4197))
    }

    @Test
    fun `启动时刻取用的是被查那个 pid 的`() {
        // 拿错 pid 去问启动时刻，症状与「判断没生效」不可区分
        val asked = mutableListOf<Long>()
        createLogsDb("logs_2.sqlite", Triple("pid:4197:run", "t", 100L))
        createStateDb("state_5.sqlite", "t" to "/x.jsonl")

        index { pid -> asked += pid; null }.rolloutPathOf(4197)

        assertEquals(listOf(4197L), asked)
    }

    // ---- 运行判别符：process_uuid 尾部那段 uuid ----

    @Test
    fun `新运行还没写出带 thread 的日志行时不回退到旧运行的 thread`() {
        // 这是 pid 复用最常见的那一瞬：新进程已经写了日志（所以陈旧判断放行），
        // 但还没写到带 thread_id 的那一行。只按 `pid:<PID>:%` 匹配会跳回上一次运行。
        val old = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        createLogsDb(
            "logs_2.sqlite",
            Triple("pid:4197:oldrun", old, 100L),
            Triple("pid:4197:newrun", null, 200L),
        )
        createStateDb("state_5.sqlite", old to "/old.jsonl")

        assertNull(index().rolloutPathOf(4197))
    }

    @Test
    fun `只取最近那次运行的 thread 行而不是旧运行的`() {
        val old = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        val new = "01a02cda-8081-7113-b026-607a21c0da98"
        createLogsDb(
            "logs_2.sqlite",
            Triple("pid:4197:oldrun", old, 100L),
            Triple("pid:4197:newrun", new, 200L),
        )
        createStateDb("state_5.sqlite", old to "/old.jsonl", new to "/new.jsonl")

        assertEquals("/new.jsonl", index().rolloutPathOf(4197))
    }

    // ---- 查询时间上界 ----

    @Test
    fun `超时的查询会被打断而不是跑到底`() {
        // setQueryTimeout 在 sqlite-jdbc 上只落到 busy timeout，不限制查询时长
        // （实测 setQueryTimeout(3) 之后一条 45.8 秒的递归 CTE 照样跑完）。
        // 真正管用的是 cancel() → sqlite3_interrupt，这条用例守的就是它还接得上。
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt ->
                val started = System.currentTimeMillis()
                val outcome = runCatching {
                    withQueryDeadline(stmt, 200L) {
                        stmt.executeQuery(
                            "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c " +
                                "WHERE x < 400000000) SELECT max(x) FROM c",
                        ).use { rows -> if (rows.next()) rows.getLong(1) else -1L }
                    }
                }
                val elapsed = System.currentTimeMillis() - started
                assertNotNull("超时应当以异常中止", outcome.exceptionOrNull())
                assertTrue("被打断后应当很快返回，实际 $elapsed ms", elapsed < 10_000)
            }
        }
    }

    @Test
    fun `没超时的查询照常拿到结果`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt ->
                val value = withQueryDeadline(stmt, 3_000L) {
                    stmt.executeQuery("SELECT 42").use { rows -> if (rows.next()) rows.getLong(1) else -1L }
                }
                assertEquals(42L, value)
            }
        }
    }

    // ---- sqlite_home 接线 ----

    @Test
    fun `sqlite_home 配置指向别处时从那里读库`() {
        val thread = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        val rollout = "/Users/me/.codex/sessions/rollout.jsonl"
        createLogsDbIn(sqliteDir.root, "logs_2.sqlite", Triple("pid:4197:u", thread, 100L))
        createStateDbIn(sqliteDir.root, "state_5.sqlite", thread to rollout)
        File(tmp.root, "config.toml").writeText("sqlite_home = \"${sqliteDir.root.absolutePath}\"\n")

        assertEquals(rollout, index().rolloutPathOf(4197))
    }

    @Test
    fun `没有 sqlite_home 配置时从 codexHome 读库`() {
        val thread = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        val rollout = "/Users/me/.codex/sessions/rollout.jsonl"
        createLogsDb("logs_2.sqlite", Triple("pid:4197:u", thread, 100L))
        createStateDb("state_5.sqlite", thread to rollout)
        // config.toml 存在但没有 sqlite_home 键——落在 codexHome
        File(tmp.root, "config.toml").writeText("model = \"gpt-5\"\n")

        assertEquals(rollout, index().rolloutPathOf(4197))
    }
}
