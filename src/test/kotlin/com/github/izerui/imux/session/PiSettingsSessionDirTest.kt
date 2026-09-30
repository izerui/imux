package com.github.izerui.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * pi 设置里的 `sessionDir`。语义取自 pi 的 `dist/core/settings-manager.js`
 * （项目设置按键覆盖全局）与 `dist/utils/paths.js` 的 `normalizePath`。
 *
 * 路径都取自 [TemporaryFolder]，期望值由这些路径构造，Windows 上走的是盘符路径。
 */
class PiSettingsSessionDirTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var home: Path
    private lateinit var project: Path
    private lateinit var elsewhere: Path

    @Before
    fun setUp() {
        home = tmp.newFolder("home").toPath()
        project = tmp.newFolder("proj").toPath()
        elsewhere = tmp.newFolder("elsewhere").toPath()
    }

    /** JSON 字符串里的反斜杠要转义，Windows 路径原样拼进去是非法 JSON。 */
    private fun json(path: Path) = path.toString().replace("\\", "\\\\")

    private fun settingsDir(
        global: String? = null,
        project: String? = null,
    ) = piSettingsSessionDir(global, project, this.project.toString(), home)

    @Test
    fun `两份设置都没有 sessionDir 时返回 null`() {
        assertNull(settingsDir())
        assertNull(settingsDir(global = """{"theme":"dark"}""", project = "{}"))
    }

    @Test
    fun `全局设置的 sessionDir 生效`() {
        val dir = elsewhere.resolve("global")
        assertEquals(dir, settingsDir(global = """{"sessionDir":"${json(dir)}"}"""))
    }

    @Test
    fun `项目设置覆盖全局设置`() {
        val global = elsewhere.resolve("global")
        val proj = elsewhere.resolve("project")
        assertEquals(
            proj,
            settingsDir(
                global = """{"sessionDir":"${json(global)}"}""",
                project = """{"sessionDir":"${json(proj)}"}""",
            ),
        )
    }

    @Test
    fun `项目设置里的空值也会盖掉全局值，结果是没配置`() {
        // deepMergeSettings 只跳过 undefined；"" 与 null 照样覆盖，随后因为为假退回默认目录
        val global = """{"sessionDir":"${json(elsewhere)}"}"""
        assertNull(settingsDir(global = global, project = """{"sessionDir":""}"""))
        assertNull(settingsDir(global = global, project = """{"sessionDir":null}"""))
    }

    @Test
    fun `解析失败的设置当作空对象，另一份照常生效`() {
        val dir = elsewhere.resolve("global")
        assertEquals(dir, settingsDir(global = """{"sessionDir":"${json(dir)}"}""", project = "{坏掉的"))
    }

    @Test
    fun `不是字符串的值视为没配置`() {
        assertNull(settingsDir(global = """{"sessionDir":42}"""))
        assertNull(settingsDir(global = """{"sessionDir":["a"]}"""))
    }

    @Test
    fun `波浪号按用户主目录展开`() {
        assertEquals(home.resolve("pi-sessions"), settingsDir(global = """{"sessionDir":"~/pi-sessions"}"""))
        assertEquals(home.resolve("pi-sessions"), settingsDir(global = """{"sessionDir":"~\\pi-sessions"}"""))
        assertEquals(home, settingsDir(global = """{"sessionDir":"~"}"""))
    }

    @Test
    fun `相对路径按项目目录解析`() {
        // pi 把相对路径原样交给 Node，按进程 cwd 解析；imux 总在项目目录里启动 pi
        assertEquals(project.resolve(".pi-sessions"), settingsDir(project = """{"sessionDir":".pi-sessions"}"""))
    }

    @Test
    fun `file URL 转成路径`() {
        val dir = elsewhere.resolve("from-url")
        assertEquals(dir, settingsDir(global = """{"sessionDir":"${dir.toUri()}"}"""))
    }

    @Test
    fun `开头带 BOM 的设置文件也能读`() {
        val dir = elsewhere.resolve("bom")
        assertEquals(dir, settingsDir(global = "\uFEFF" + """{"sessionDir":"${json(dir)}"}"""))
    }

    // ---- 与 reader 联动 ----

    private fun writeSession(
        dir: Path,
        uuid: String,
        cwd: String,
    ) {
        Files.createDirectories(dir)
        // cwd 写进 JSON 前同样要转义反斜杠
        val cwdJson = cwd.replace("\\", "\\\\")
        File(dir.toFile(), "2026-08-13T08-03-09-173Z_$uuid.jsonl").writeText(
            """{"type":"session","version":3,"id":"$uuid","timestamp":"2026-08-13T08:03:09.173Z","cwd":"$cwdJson"}""" + "\n" +
                """{"type":"message","id":"u1","parentId":null,"timestamp":"2026-08-13T08:03:20.000Z","message":{"role":"user","content":"消息-$uuid"}}""",
        )
    }

    @Test
    fun `reader 按项目设置的 sessionDir 读平铺会话`() {
        val agentDir = home.resolve(".pi").resolve("agent")
        val flat = elsewhere.resolve("sessions")
        Files.createDirectories(project.resolve(".pi"))
        Files.writeString(project.resolve(".pi").resolve("settings.json"), """{"sessionDir":"${json(flat)}"}""")
        writeSession(flat, "uuid-mine", project.toString())
        writeSession(flat, "uuid-other", elsewhere.toString())

        val reader = PiSessionReader(agentDir, userHome = home)

        assertEquals(flat, reader.sessionDir(project.toString()))
        assertEquals(listOf("uuid-mine"), reader.read(project.toString()).map { it.id })
    }

    @Test
    fun `环境变量优先于设置`() {
        val agentDir = home.resolve(".pi").resolve("agent")
        val fromEnv = elsewhere.resolve("env")
        Files.createDirectories(agentDir)
        Files.writeString(agentDir.resolve("settings.json"), """{"sessionDir":"${json(elsewhere.resolve("settings"))}"}""")

        assertEquals(fromEnv, PiSessionReader(agentDir, fromEnv, home).sessionDir(project.toString()))
    }

    @Test
    fun `没有任何配置时退回按 cwd 编码的默认目录`() {
        val agentDir = home.resolve(".pi").resolve("agent")
        val reader = PiSessionReader(agentDir, userHome = home)

        assertEquals(
            agentDir.resolve("sessions").resolve(reader.projectDirName(project.toString())),
            reader.sessionDir(project.toString()),
        )
    }
}
