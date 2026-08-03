package com.github.liuyuhua.imux.session

import com.github.liuyuhua.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClaudeSessionReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun reader() = ClaudeSessionReader(tmp.root.toPath())

    private fun projectDir() = File(tmp.root, "projects/-Users-demo-proj").apply { mkdirs() }

    /**
     * 目录名编码：'/' 与 '.' 都要变成 '-'。
     * 实测证据：/Users/liuyuhua/github/demo/.claude/worktrees/spec
     * 对应目录 -Users-liuyuhua-github-demo--claude-worktrees-spec
     */
    @Test
    fun `目录名编码将斜杠替换为连字符`() {
        assertEquals(
            "-Users-liuyuhua-github-maas-api",
            reader().projectDirName("/Users/liuyuhua/github/maas-api"),
        )
    }

    @Test
    fun `目录名编码同样处理隐藏目录中的点`() {
        assertEquals(
            "-Users-liuyuhua-github-demo--claude-worktrees-spec",
            reader().projectDirName("/Users/liuyuhua/github/demo/.claude/worktrees/spec"),
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
    fun `没有 ai-title 记录时回退为会话 id 短码`() {
        File(projectDir(), "bbbb-2222.jsonl")
            .writeText("""{"type":"user","message":{"content":"你好"}}""")

        assertEquals("会话 bbbb-222", reader().read("/Users/demo/proj")[0].title)
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
}
