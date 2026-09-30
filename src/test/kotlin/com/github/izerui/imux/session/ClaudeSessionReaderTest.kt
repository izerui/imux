package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClaudeSessionReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // isWindows 显式传：默认值取 SystemInfo，那样用例行为会随主机平台变。
    private fun reader(isWindows: Boolean = false) = ClaudeSessionReader(tmp.root.toPath(), isWindows)

    private fun projectDir() = File(tmp.root, "projects/-Users-demo-proj").apply { mkdirs() }

    private val longPath = "/Users/demo/" + "very_long_directory_name/".repeat(9) + "proj"

    @Test
    fun `目录名编码把所有非字母数字字符换成连字符`() {
        assertEquals("-Users-demo-my-app-v2-", reader().projectDirName("/Users/demo/my_app v2/"))
        assertEquals("-Users-demo------proj", reader().projectDirName("/Users/demo/中文目录/proj"))
        assertEquals("C--Users-demo-proj", reader().projectDirName("C:\\Users\\demo\\proj"))
    }

    /** 期望值由 Node 版 Claude Code 的 sanitizePath（djb2）实算得出。 */
    @Test
    fun `超长目录名截断到 200 字符并追加 djb2 哈希`() {
        assertEquals(
            "-Users-demo-" + "very-long-directory-name-".repeat(7) + "very-long-dir-mtnprz",
            reader().projectDirName(longPath),
        )
    }

    @Test
    fun `超长目录名优先采用磁盘上 cwd 吻合的现有目录`() {
        val prefix = reader().projectDirName(longPath).substringBeforeLast('-')
        // 同前缀但属于别的项目的目录不能被认领
        File(tmp.root, "projects/$prefix-other").apply { mkdirs() }
            .resolve("s0.jsonl").writeText("""{"type":"user","cwd":"${longPath}x"}""")
        File(tmp.root, "projects/$prefix-bunhash1").apply { mkdirs() }
            .resolve("s1.jsonl").writeText("""{"type":"user","cwd":"$longPath","message":{"content":"你好"}}""")

        val reader = reader()
        assertEquals("$prefix-bunhash1", reader.projectDirName(longPath))
        assertEquals(listOf("s1"), reader.read(longPath).map { it.id })
    }

    private val longWindowsPath = "C:/Users/demo/" + "very_long_directory_name/".repeat(9) + "proj"

    /**
     * IDE 给的是正斜杠，Claude 记录与求哈希用的是原生反斜杠。
     * 期望值由 Node 对 `C:\Users\demo\...` 实算得出。
     */
    @Test
    fun `Windows 上兜底哈希按原生反斜杠路径计算`() {
        assertEquals(
            "C--Users-demo-" + "very-long-directory-name-".repeat(7) + "very-long-d-2ewf7i",
            reader(isWindows = true).projectDirName(longWindowsPath),
        )
    }

    @Test
    fun `Windows 上正斜杠项目路径能认出记录反斜杠 cwd 的长路径目录`() {
        val prefix = reader(isWindows = true).projectDirName(longWindowsPath).substringBeforeLast('-')
        // 会话里的 cwd 是 JSON 转义后的反斜杠写法
        val nativeCwd = longWindowsPath.replace("/", "\\\\")
        File(tmp.root, "projects/$prefix-bunhash2").apply { mkdirs() }
            .resolve("w1.jsonl").writeText("""{"type":"user","cwd":"$nativeCwd","message":{"content":"你好"}}""")

        val reader = reader(isWindows = true)
        assertEquals("$prefix-bunhash2", reader.projectDirName(longWindowsPath))
        assertEquals(listOf("w1"), reader.read(longWindowsPath).map { it.id })
    }

    /** 分隔符归一化只在 Windows 上做，POSIX 上 `\` 是合法的目录名字符。 */
    @Test
    fun `非 Windows 上不把反斜杠 cwd 当成同一目录`() {
        val prefix = reader().projectDirName(longWindowsPath).substringBeforeLast('-')
        val nativeCwd = longWindowsPath.replace("/", "\\\\")
        File(tmp.root, "projects/$prefix-bunhash2").apply { mkdirs() }
            .resolve("w1.jsonl").writeText("""{"type":"user","cwd":"$nativeCwd"}""")

        assertTrue(reader().read(longWindowsPath).isEmpty())
    }

    /**
     * 目录名编码：'/' 与 '.' 都要变成 '-'。
     * 实测证据：/Users/izerui/github/demo/.claude/worktrees/spec
     * 对应目录 -Users-izerui-github-demo--claude-worktrees-spec
     */
    @Test
    fun `目录名编码将斜杠替换为连字符`() {
        assertEquals(
            "-Users-izerui-github-maas-api",
            reader().projectDirName("/Users/izerui/github/maas-api"),
        )
    }

    @Test
    fun `目录名编码同样处理隐藏目录中的点`() {
        assertEquals(
            "-Users-izerui-github-demo--claude-worktrees-spec",
            reader().projectDirName("/Users/izerui/github/demo/.claude/worktrees/spec"),
        )
    }

    @Test
    fun `读取会话时以文件名为 id 并取最后一条 ai-title 作为标题`() {
        File(projectDir(), "aaaa-1111.jsonl").writeText(
            """
            {"type":"user","message":{"content":"你好"}}
            {"type":"ai-title","aiTitle":"旧标题","sessionId":"aaaa-1111"}
            {"type":"ai-title","aiTitle":"最新标题","sessionId":"aaaa-1111"}
            """.trimIndent(),
        )

        val sessions = reader().read("/Users/demo/proj")

        assertEquals(1, sessions.size)
        assertEquals("aaaa-1111", sessions[0].id)
        assertEquals("最新标题", sessions[0].title)
        assertEquals(AgentType.CLAUDE, sessions[0].agentType)
    }

    @Test
    fun `custom-title 优先于后续自动生成的 ai-title`() {
        File(projectDir(), "renamed.jsonl").writeText(
            """
            {"type":"ai-title","aiTitle":"原自动标题","sessionId":"renamed"}
            {"type":"custom-title","customTitle":"模型重新生成标题","sessionId":"renamed"}
            {"type":"ai-title","aiTitle":"后续自动标题","sessionId":"renamed"}
            """.trimIndent(),
        )

        assertEquals("模型重新生成标题", reader().read("/Users/demo/proj").single().title)
    }

    @Test
    fun `没有 ai-title 时回退为最后一条用户消息`() {
        File(projectDir(), "bbbb-2222.jsonl").writeText(
            """
            {"type":"user","message":{"content":"第一句话"}}
            {"type":"user","message":{"content":"最后一句话"}}
            """.trimIndent(),
        )

        assertEquals("最后一句话", reader().read("/Users/demo/proj")[0].title)
    }

    @Test
    fun `回退时跳过工具结果、系统注入内容和中断标记`() {
        File(projectDir(), "bbbb-3333.jsonl").writeText(
            """
            {"type":"user","message":{"content":"<ide_opened_file>打开了某文件</ide_opened_file>"}}
            {"type":"user","message":{"content":[{"type":"tool_result","content":"工具输出"}]}}
            {"type":"user","message":{"content":"这才是我说的话"}}
            {"type":"user","message":{"content":"[Request interrupted by user]"}}
            """.trimIndent(),
        )

        assertEquals("这才是我说的话", reader().read("/Users/demo/proj")[0].title)
    }

    @Test
    fun `方括号开头的正常用户消息不被跳过`() {
        File(projectDir(), "bbbb-5555.jsonl").writeText(
            """
            {"type":"user","message":{"content":"第一句话"}}
            {"type":"user","message":{"content":"[proxy] enabled via 127.0.0.1:7890"}}
            """.trimIndent(),
        )

        assertEquals("[proxy] enabled via 127.0.0.1:7890", reader().read("/Users/demo/proj")[0].title)
    }

    @Test
    fun `content 为数组时从 text 块中提取标题`() {
        File(projectDir(), "bbbb-6666.jsonl").writeText(
            """
            {"type":"user","message":{"content":"第一句话"}}
            {"type":"user","message":{"content":[{"type":"text","text":"最后一句多模态消息"}]}}
            """.trimIndent(),
        )

        assertEquals("最后一句多模态消息", reader().read("/Users/demo/proj")[0].title)
    }

    @Test
    fun `连用户消息都没有时才退到会话 id 短码`() {
        File(projectDir(), "bbbb-4444.jsonl")
            .writeText("""{"type":"system","subtype":"init"}""")

        assertEquals("Session bbbb-444", reader().read("/Users/demo/proj")[0].title)
    }

    @Test
    fun `损坏的行不影响其余解析`() {
        File(projectDir(), "cccc-3333.jsonl").writeText(
            """
            这不是 json
            {"type":"ai-title","aiTitle":"仍然读到","sessionId":"cccc-3333"}
            """.trimIndent(),
        )

        assertEquals("仍然读到", reader().read("/Users/demo/proj")[0].title)
    }

    @Test
    fun `项目目录不存在时返回空列表而不抛异常`() {
        assertTrue(reader().read("/Users/demo/不存在").isEmpty())
    }

    @Test
    fun `忽略非 jsonl 文件与子目录`() {
        val dir = projectDir()
        File(dir, "dddd-4444.jsonl")
            .writeText("""{"type":"ai-title","aiTitle":"真会话","sessionId":"dddd-4444"}""")
        File(dir, "dddd-4444").mkdirs()
        File(dir, "notes.txt").writeText("无关文件")

        assertEquals(1, reader().read("/Users/demo/proj").size)
    }

    /** 与 codex 侧同源的缺陷类：被匹配的值过长时正则会递归爆栈。 */
    @Test
    fun `超长的标题值不会导致爆栈`() {
        val huge = "标题".repeat(20_000)
        File(projectDir(), "ffff-6666.jsonl")
            .writeText("""{"type":"ai-title","aiTitle":"$huge","sessionId":"ffff-6666"}""")

        assertEquals(huge, reader().read("/Users/demo/proj")[0].title)
    }

    @Test
    fun `标题中的转义引号被还原`() {
        File(projectDir(), "eeee-5555.jsonl")
            .writeText("""{"type":"ai-title","aiTitle":"关于 \"引号\" 的讨论","sessionId":"eeee-5555"}""")

        assertEquals("关于 \"引号\" 的讨论", reader().read("/Users/demo/proj")[0].title)
    }

    /** TurnWatcher 需要靠它定位文件做增量读取。 */
    @Test
    fun `会话带上自身文件路径`() {
        val file = File(projectDir(), "aaaa-9999.jsonl")
        file.writeText("""{"type":"ai-title","aiTitle":"标题","sessionId":"aaaa-9999"}""")

        assertEquals(file.toPath(), reader().read("/Users/demo/proj")[0].filePath)
    }
}
