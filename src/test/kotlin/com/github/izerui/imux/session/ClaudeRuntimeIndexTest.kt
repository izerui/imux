package com.github.izerui.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClaudeRuntimeIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 默认认为所有 pid 都活着；需要测残留时再单独指定。 */
    private fun index(alive: (Long) -> Boolean = { true }) =
        ClaudeRuntimeIndex(tmp.root.toPath(), alive)

    private fun writeSession(
        pid: Long,
        sessionId: String,
        kind: String = "interactive",
        status: String? = "idle",
        cwd: String = "/Users/demo/proj",
    ) {
        val dir = File(tmp.root, "sessions").apply { mkdirs() }
        val statusField = if (status == null) "" else ""","status":"$status""""
        File(dir, "$pid.json").writeText(
            """{"pid":"$pid","sessionId":"$sessionId","cwd":"$cwd","kind":"$kind"$statusField}""",
        )
    }

    @Test
    fun `读出会话的运行态`() {
        writeSession(1001, "s1", kind = "bg", status = "busy")

        val entry = index().load()["s1"]!!
        assertEquals("bg", entry.kind)
        assertEquals("busy", entry.status)
        assertEquals(1001L, entry.pid)
    }

    @Test
    fun `后台且忙碌的会话被判为不可恢复`() {
        writeSession(1001, "s1", kind = "bg", status = "busy")

        val entry = index().load()["s1"]!!
        assertTrue(entry.isBackground)
        assertTrue(entry.isBusy)
    }

    @Test
    fun `后台但空闲的会话不算忙碌`() {
        writeSession(1001, "s1", kind = "bg", status = "idle")

        val entry = index().load()["s1"]!!
        assertTrue(entry.isBackground)
        assertFalse(entry.isBusy)
    }

    @Test
    fun `交互式会话不算后台`() {
        writeSession(1001, "s1", kind = "interactive", status = "idle")

        assertFalse(index().load()["s1"]!!.isBackground)
    }

    @Test
    fun `缺少 status 字段时不算忙碌`() {
        writeSession(1001, "s1", status = null)

        assertFalse(index().load()["s1"]!!.isBusy)
    }

    /** 进程崩溃可能留下文件，据此报「正在运行」会误导。 */
    @Test
    fun `进程已死的残留文件被忽略`() {
        writeSession(1001, "活着", kind = "interactive")
        writeSession(2002, "死了", kind = "interactive")

        val loaded = index(alive = { it == 1001L }).load()

        assertEquals(setOf("活着"), loaded.keys)
        assertNull(loaded["死了"])
    }

    @Test
    fun `目录不存在时返回空表`() {
        assertTrue(index().load().isEmpty())
    }

    @Test
    fun `损坏的文件被跳过`() {
        File(tmp.root, "sessions").mkdirs()
        File(tmp.root, "sessions/坏的.json").writeText("这不是 json")
        writeSession(1001, "好的")

        assertEquals(setOf("好的"), index().load().keys)
    }

    @Test
    fun `缺少会话 id 的文件被跳过`() {
        File(tmp.root, "sessions").mkdirs()
        File(tmp.root, "sessions/999.json").writeText("""{"pid":"999","kind":"bg"}""")

        assertTrue(index().load().isEmpty())
    }
}
