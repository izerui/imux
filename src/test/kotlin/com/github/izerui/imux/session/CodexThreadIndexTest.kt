package com.github.izerui.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager

class CodexThreadIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun index() = CodexThreadIndex(tmp.root.toPath())

    private fun createDb(vararg rows: Triple<String, String?, Long>) =
        createDb("state_5.sqlite", *rows)

    private fun createDevDb(vararg rows: Triple<String, String, Long>) {
        val dir = File(tmp.root, "sqlite").apply { mkdirs() }
        val file = File(dir, "codex-dev.db")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE local_thread_catalog (host_id TEXT, thread_id TEXT, display_title TEXT, " +
                        "source_created_at REAL, source_updated_at REAL, source_kind TEXT, " +
                        "observation_sequence INTEGER, missing_candidate INTEGER DEFAULT 0, " +
                        "source_recency_at REAL DEFAULT 0, pending_observed_title INTEGER DEFAULT 0, " +
                        "PRIMARY KEY (host_id, thread_id))",
                )
            }
            if (rows.isNotEmpty()) {
                conn.prepareStatement(
                    "INSERT INTO local_thread_catalog (host_id, thread_id, display_title, " +
                        "source_created_at, source_updated_at, source_kind, observation_sequence) " +
                        "VALUES ('host', ?, ?, ?, 0, 'cli', 0)",
                ).use { stmt ->
                    rows.forEach { (id, title, created) ->
                        stmt.setString(1, id)
                        stmt.setString(2, title)
                        stmt.setLong(3, created)
                        stmt.executeUpdate()
                    }
                }
            }
        }
    }

    /** 用同一个驱动造一个结构一致的库，比塞二进制夹具可读得多。 */
    private fun createDb(
        name: String,
        vararg rows: Triple<String, String?, Long>,
    ) {
        val file = File(tmp.root, name)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE threads (id TEXT PRIMARY KEY, name TEXT, title TEXT, created_at INTEGER, updated_at_ms INTEGER)",
                )
            }
            conn.prepareStatement("INSERT INTO threads (id, name, title, created_at, updated_at_ms) VALUES (?, NULL, ?, ?, ?)")
                .use { stmt ->
                    rows.forEach { (id, title, updated) ->
                        stmt.setString(1, id)
                        stmt.setString(2, title)
                        stmt.setLong(3, updated)
                        stmt.setLong(4, updated)
                        stmt.executeUpdate()
                    }
                }
        }
    }

    @Test
    fun `按会话 id 取到标题`() {
        createDb(Triple("019faba2-379e-7333-a4bd-9dc6f7ec81ed", "分析工程结构", 1_000L))

        assertEquals("分析工程结构", index().load()!!["019faba2-379e-7333-a4bd-9dc6f7ec81ed"])
    }

    @Test
    fun `用户设置的 name 优先于自动 title`() {
        createDb(Triple("thread-1", "自动标题", 1_000L))
        DriverManager.getConnection("jdbc:sqlite:${File(tmp.root, "state_5.sqlite").absolutePath}").use { conn ->
            conn.prepareStatement("UPDATE threads SET name = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, "重新生成的标题")
                stmt.setString(2, "thread-1")
                stmt.executeUpdate()
            }
        }

        assertEquals("重新生成的标题", index().load()!!["thread-1"])
    }

    @Test
    fun `多条记录都能取到`() {
        createDb(
            Triple("a", "标题甲", 1_000L),
            Triple("b", "标题乙", 2_000L),
        )

        val loaded = index().load()!!
        assertEquals("标题甲", loaded["a"])
        assertEquals("标题乙", loaded["b"])
    }

    @Test
    fun `标题为空的记录仍保留会话 id`() {
        createDb(
            Triple("a", null, 1_000L),
            Triple("b", "   ", 2_000L),
        )

        val loaded = index().load()!!
        assertEquals("", loaded["a"])
        assertEquals("", loaded["b"])
    }

    @Test
    fun `数据库不存在时返回 null`() {
        assertNull(index().load())
    }

    @Test
    fun `旧表没有 name 列时仍读取 title`() {
        val file = File(tmp.root, "state_5.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate("CREATE TABLE threads (id TEXT PRIMARY KEY, title TEXT)")
                it.executeUpdate("INSERT INTO threads VALUES ('old-1', '旧版标题')")
            }
        }

        assertEquals("旧版标题", index().load()!!["old-1"])
    }

    /** codex 换版本时表结构可能变，不能因此让整个会话列表崩掉。 */
    @Test
    fun `旧库表结构不符时返回空 map 而不抛异常`() {
        val file = File(tmp.root, "state_5.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { it.executeUpdate("CREATE TABLE other (x TEXT)") }
        }

        assertTrue(index().load()!!.isEmpty())
    }

    @Test
    fun `旧库文件损坏时返回空 map`() {
        File(tmp.root, "state_5.sqlite").writeText("这不是 sqlite 文件")

        assertTrue(index().load()!!.isEmpty())
    }

    // ---- codex-dev.db 优先级 ----

    @Test
    fun `codex-dev db 存在时优先读取`() {
        createDb(Triple("legacy-1", "旧库标题", 1_000L))
        createDevDb(Triple("dev-1", "新库标题", 2_000L))

        val loaded = index().load()!!
        assertEquals("新库标题", loaded["dev-1"])
        assertNull(loaded["legacy-1"])
    }

    @Test
    fun `空 dev 库不回退到有旧会话的 state 库`() {
        createDb(Triple("legacy-1", "旧库标题", 1_000L))
        createDevDb()

        assertTrue(index().load()!!.isEmpty())
    }

    @Test
    fun `state 有更新的会话时不被残留的 dev 库遮蔽`() {
        createDb(Triple("state-1", "state 标题", 2_000L))
        createDevDb(Triple("dev-1", "dev 旧标题", 1_000L))

        val loaded = index().load()!!
        assertEquals("state 标题", loaded["state-1"])
        assertNull(loaded["dev-1"])
    }

    @Test
    fun `dev 库损坏时读取可用的 state 库`() {
        createDb(Triple("legacy-1", "旧库标题", 1_000L))
        // 写一个损坏的 codex-dev.db
        File(tmp.root, "sqlite").mkdirs()
        File(tmp.root, "sqlite/codex-dev.db").writeText("损坏的数据库")

        val loaded = index().load()!!
        assertEquals("旧库标题", loaded["legacy-1"])
    }

    @Test
    fun `读取数值版本最高的 state 数据库`() {
        createDb("state_5.sqlite", Triple("thread-1", "旧库标题", 1_000L))
        createDb("state_10.sqlite", Triple("thread-1", "新库标题", 2_000L))

        assertEquals("新库标题", index().load()!!["thread-1"])
    }

    @Test
    fun `sqlite_home 指向外部目录时从外部最新版本读取`() {
        val sqliteDir = tmp.newFolder("sqlite-home")
        val configuredDb = File(sqliteDir, "state_8.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${configuredDb.absolutePath}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE threads (id TEXT PRIMARY KEY, name TEXT, title TEXT, updated_at_ms INTEGER)",
                )
                it.executeUpdate("INSERT INTO threads VALUES ('thread-1', NULL, '外部目录标题', 1)")
            }
        }
        File(tmp.root, "config.toml").writeText("sqlite_home = \"${sqliteDir.absolutePath}\"\n")

        assertEquals("外部目录标题", index().load()!!["thread-1"])
    }
}
