package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class PiSessionReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun reader() = PiSessionReader(tmp.root.toPath())

    /**
     * 目录名规则取自 pi 的 `dist/core/session-manager.js`：
     * `"--" + cwd 去掉开头斜杠、再把 / \ : 换成 - + "--"`。
     */
    private fun sessionDir(cwd: String): File {
        val encoded = "--" + cwd.removePrefix("/").replace(Regex("[/\\\\:]"), "-") + "--"
        return File(tmp.root, "agent/sessions/$encoded").apply { mkdirs() }
    }

    private fun writeSession(
        uuid: String,
        cwd: String,
        body: String = "",
        headCwd: String = cwd,
    ): File {
        val head =
            """{"type":"session","version":3,"id":"$uuid","timestamp":"2026-08-13T08:03:09.173Z","cwd":"$headCwd"}"""
        return File(sessionDir(cwd), "2026-08-13T08-03-09-173Z_$uuid.jsonl").apply {
            writeText(if (body.isEmpty()) head else "$head\n$body")
        }
    }

    private fun sessionInfo(name: String, at: String = "2026-08-13T08:04:00.000Z") =
        """{"type":"session_info","id":"n1","parentId":null,"timestamp":"$at","name":"$name"}"""

    private fun userMessage(text: String, at: String = "2026-08-13T08:03:20.000Z") =
        """{"type":"message","id":"u1","parentId":null,"timestamp":"$at","message":{"role":"user","content":[{"type":"text","text":"$text"}]}}"""

    private fun stringUserMessage(text: String, at: String = "2026-08-13T08:03:20.000Z") =
        """{"type":"message","id":"u1","parentId":null,"timestamp":"$at","message":{"role":"user","content":"$text"}}"""

    @Test
    fun `按项目路径编码后的目录读取会话`() {
        writeSession("uuid-mine", "/Users/demo/proj", userMessage("项目内消息"))
        writeSession("uuid-other", "/Users/demo/other", userMessage("其他项目消息"))

        val sessions = reader().read("/Users/demo/proj")

        assertEquals(1, sessions.size)
        assertEquals("uuid-mine", sessions[0].id)
        assertEquals(AgentType.PI, sessions[0].agentType)
    }

    /** 路径里的 `.` `_` `-` 都原样保留，只有分隔符被替换——错一个字符就整个目录读不到。 */
    @Test
    fun `目录编码保留点和下划线`() {
        writeSession(
            "uuid-dots",
            "/Users/demo/pi.probe/sub-dir_test",
            userMessage("检查路径编码"),
        )

        assertEquals(
            listOf("uuid-dots"),
            reader().read("/Users/demo/pi.probe/sub-dir_test").map { it.id },
        )
    }

    @Test
    fun `标题取会话显示名`() {
        writeSession(
            "uuid-named",
            "/Users/demo/proj",
            sessionInfo("重构鉴权模块") + "\n" + userMessage("开始处理"),
        )

        assertEquals("重构鉴权模块", reader().read("/Users/demo/proj")[0].title)
    }

    /** `/name` 可以改多次，每次追加一条 session_info，最后一条才是当前名字。 */
    @Test
    fun `改过名的会话取最后一条显示名`() {
        writeSession(
            "uuid-renamed",
            "/Users/demo/proj",
            sessionInfo("旧名字") + "\n" +
                userMessage("开始处理") + "\n" +
                sessionInfo("新名字", at = "2026-08-13T09:00:00.000Z"),
        )

        assertEquals("新名字", reader().read("/Users/demo/proj")[0].title)
    }

    @Test
    fun `改名记录不更新会话最近活动时间`() {
        writeSession(
            "uuid-renamed-time",
            "/Users/demo/proj",
            userMessage("开始处理", at = "2026-08-13T08:03:20.000Z") + "\n" +
                sessionInfo("新名字", at = "2026-08-31T04:00:00.000Z"),
        )

        assertEquals(
            Instant.parse("2026-08-13T08:03:20.000Z"),
            reader().read("/Users/demo/proj").single().lastActiveAt,
        )
    }

    @Test
    fun `没有显示名时回退为首条用户消息`() {
        writeSession("uuid-msg", "/Users/demo/proj", userMessage("帮我重构这个函数"))

        assertEquals("帮我重构这个函数", reader().read("/Users/demo/proj")[0].title)
    }

    @Test
    fun `字符串形式的用户 content 也可作为标题`() {
        writeSession("uuid-string-msg", "/Users/demo/proj", stringUserMessage("检查发布流程"))

        assertEquals("检查发布流程", reader().read("/Users/demo/proj")[0].title)
    }

    @Test
    fun `最后一次名称被清空后回退用户消息`() {
        writeSession(
            "uuid-cleared-name",
            "/Users/demo/proj",
            sessionInfo("旧名字") + "\n" +
                userMessage("回退标题") + "\n" +
                sessionInfo("   ", at = "2026-08-13T09:00:00.000Z"),
        )

        assertEquals("回退标题", reader().read("/Users/demo/proj")[0].title)
    }

    @Test
    fun `不带 name 的 session info 不清除已有名称`() {
        val unrelatedInfo =
            """{"type":"session_info","id":"n2","parentId":null,"timestamp":"2026-08-13T09:00:00.000Z"}"""
        writeSession(
            "uuid-name-kept",
            "/Users/demo/proj",
            sessionInfo("保留名字") + "\n" + userMessage("开始处理") + "\n" + unrelatedInfo,
        )

        assertEquals("保留名字", reader().read("/Users/demo/proj")[0].title)
    }

    @Test
    fun `过长的用户消息被截断并加省略号`() {
        writeSession("uuid-long", "/Users/demo/proj", userMessage("很".repeat(120)))

        val title = reader().read("/Users/demo/proj")[0].title
        assertEquals(61, title.length)
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun `没有用户消息的空会话不进入历史列表`() {
        writeSession("01abcdef-2222", "/Users/demo/proj")

        assertTrue(reader().read("/Users/demo/proj").isEmpty())
    }

    @Test
    fun `只有名称但没有用户消息的会话仍视为空会话`() {
        writeSession("uuid-name-only", "/Users/demo/proj", sessionInfo("尚未开始"))

        assertTrue(reader().read("/Users/demo/proj").isEmpty())
    }

    @Test
    fun `启动元数据很多时仍能识别后面的首条用户消息`() {
        val metadata = (1..60).joinToString("\n") { index ->
            """{"type":"model_change","id":"m$index","timestamp":"2026-08-13T08:03:10.000Z"}"""
        }
        writeSession(
            "uuid-late-user",
            "/Users/demo/proj",
            metadata + "\n" + userMessage("第六十行之后的真实消息"),
        )

        assertEquals("第六十行之后的真实消息", reader().read("/Users/demo/proj")[0].title)
    }

    /**
     * 编码把 `/` 和 `-` 映射到同一个字符，`/Users/demo/a-b` 与 `/Users/demo/a/b`
     * 会落进同一个目录名。首行的 cwd 是原始路径，用它排除撞进来的会话。
     */
    @Test
    fun `首行 cwd 与项目路径不符的会话被排除`() {
        writeSession(
            "uuid-collide",
            "/Users/demo/proj",
            body = userMessage("碰撞目录中的消息"),
            headCwd = "/Users/demo/pro/j",
        )

        assertTrue(reader().read("/Users/demo/proj").isEmpty())
    }

    @Test
    fun `首行损坏的文件被跳过而不影响其他会话`() {
        writeSession("uuid-ok", "/Users/demo/proj", userMessage("有效会话"))
        File(sessionDir("/Users/demo/proj"), "2026-08-13T07-00-00-000Z_uuid-bad.jsonl")
            .writeText("这不是 json")

        assertEquals(listOf("uuid-ok"), reader().read("/Users/demo/proj").map { it.id })
    }

    @Test
    fun `会话目录不存在时返回空列表`() {
        assertTrue(reader().read("/Users/demo/proj").isEmpty())
    }

    @Test
    fun `最后活动时刻取记录自带的时间戳而非 mtime`() {
        writeSession(
            "uuid-ts",
            "/Users/demo/proj",
            userMessage("你好", at = "2026-08-13T08:30:00.000Z"),
        )

        assertEquals(
            java.time.Instant.parse("2026-08-13T08:30:00.000Z"),
            reader().read("/Users/demo/proj")[0].lastActiveAt,
        )
    }

    /** TurnWatcher 需要靠它定位文件做增量读取。 */
    @Test
    fun `会话带上自身文件路径`() {
        val file = writeSession("uuid-path", "/Users/demo/proj", userMessage("检查文件路径"))

        assertEquals(file.toPath(), reader().read("/Users/demo/proj")[0].filePath)
    }
}
