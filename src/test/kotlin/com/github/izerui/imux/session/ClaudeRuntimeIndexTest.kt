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
    fun `按项目目录过滤，别的项目的进程不算数`() {
        // 本机常年同时跑着好几个项目的 claude。不过滤的话，这个项目的 IDE 窗口
        // 会为别的项目的会话弹通知，而那些会话根本不在本项目列表里，
        // 连标题都取不到，只能显示一串 id。
        writeSession(1001, "mine", cwd = "/Users/demo/proj")
        writeSession(1002, "other", cwd = "/Users/demo/another")

        val loaded = index().load("/Users/demo/proj")

        assertEquals(setOf("mine"), loaded.keys)
    }

    @Test
    fun `不传项目目录时返回全部`() {
        writeSession(1001, "mine", cwd = "/Users/demo/proj")
        writeSession(1002, "other", cwd = "/Users/demo/another")

        assertEquals(setOf("mine", "other"), index().load().keys)
    }

    @Test
    fun `缺少 cwd 的运行态文件在过滤时被排除`() {
        // 判不出归属就不该认领：宁可漏一条提醒，也不要在无关项目里弹
        val dir = File(tmp.root, "sessions").apply { mkdirs() }
        File(dir, "1003.json").writeText("""{"pid":"1003","sessionId":"nocwd","kind":"interactive"}""")

        assertTrue(index().load("/Users/demo/proj").isEmpty())
        assertEquals(setOf("nocwd"), index().load().keys)
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

    /**
     * `shell` 是 CLI 的第三种状态：本轮回答已经打完，只剩它派生的后台 shell 还在跑。
     * 本机实测的序列是 `busy` -> `shell` -> `idle`。对用户来说这一轮已经完了，
     * 标签页不该继续转菊花——尤其在状态图标会顶掉品牌图标之后，那意味着
     * 品牌图标要一直等到后台任务结束才回来。
     */
    @Test
    fun `只剩后台 shell 时不算忙碌`() {
        writeSession(1001, "s1", status = "shell")

        assertFalse(index().load()["s1"]!!.isBusy)
    }

    /** 但会话仍被这个进程占着，resume 预检要按占用处理。 */
    @Test
    fun `只剩后台 shell 时仍算被占用`() {
        writeSession(1001, "s1", kind = "bg", status = "shell")

        assertTrue(index().load()["s1"]!!.isOccupied)
    }

    @Test
    fun `空闲会话不算被占用`() {
        writeSession(1001, "s1", kind = "bg", status = "idle")

        assertFalse(index().load()["s1"]!!.isOccupied)
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
