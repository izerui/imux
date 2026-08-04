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

    /** 用同一个驱动造一个结构一致的库，比塞二进制夹具可读得多。 */
    private fun createDb(vararg rows: Triple<String, String?, Long>) {
        val file = File(tmp.root, "state_5.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate("CREATE TABLE threads (id TEXT PRIMARY KEY, title TEXT, updated_at_ms INTEGER)")
            }
            conn.prepareStatement("INSERT INTO threads (id, title, updated_at_ms) VALUES (?, ?, ?)")
                .use { stmt ->
                    rows.forEach { (id, title, updated) ->
                        stmt.setString(1, id)
                        stmt.setString(2, title)
                        stmt.setLong(3, updated)
                        stmt.executeUpdate()
                    }
                }
        }
    }

    @Test
    fun `按会话 id 取到标题`() {
        createDb(Triple("019faba2-379e-7333-a4bd-9dc6f7ec81ed", "分析工程结构", 1_000L))

        assertEquals("分析工程结构", index().load()["019faba2-379e-7333-a4bd-9dc6f7ec81ed"])
    }

    @Test
    fun `多条记录都能取到`() {
        createDb(
            Triple("a", "标题甲", 1_000L),
            Triple("b", "标题乙", 2_000L),
        )

        val loaded = index().load()
        assertEquals("标题甲", loaded["a"])
        assertEquals("标题乙", loaded["b"])
    }

    @Test
    fun `标题为空的记录不参与`() {
        createDb(
            Triple("a", null, 1_000L),
            Triple("b", "   ", 2_000L),
        )

        val loaded = index().load()
        assertNull(loaded["a"])
        assertNull(loaded["b"])
    }

    @Test
    fun `数据库不存在时返回空表`() {
        assertTrue(index().load().isEmpty())
    }

    /** codex 换版本时表结构可能变，不能因此让整个会话列表崩掉。 */
    @Test
    fun `表结构不符时返回空表而不抛异常`() {
        val file = File(tmp.root, "state_5.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { it.executeUpdate("CREATE TABLE other (x TEXT)") }
        }

        assertTrue(index().load().isEmpty())
    }

    @Test
    fun `文件不是合法数据库时返回空表`() {
        File(tmp.root, "state_5.sqlite").writeText("这不是 sqlite 文件")

        assertTrue(index().load().isEmpty())
    }
}
