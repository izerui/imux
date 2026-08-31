package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant

class SessionTitleRegeneratorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val now = Instant.parse("2026-08-31T04:00:00Z")

    private fun session(
        type: AgentType,
        file: Path,
        id: String = "session-1",
    ) = AgentSession(id, "原标题", type, now, now, file)

    @Test
    fun `三种 CLI 的生成命令分别调用自己的非持久化模式`() {
        val claude = titleGenerationCommand("/bin/zsh", AgentType.CLAUDE, "/tmp/project", "提示")
        val codex = titleGenerationCommand("/bin/zsh", AgentType.CODEX, "/tmp/project", "提示")
        val pi = titleGenerationCommand("/bin/zsh", AgentType.PI, "/tmp/project", "提示")

        assertTrue(claude.last().contains("claude -p") && claude.last().contains("--no-session-persistence"))
        assertTrue(codex.last().contains("codex exec") && codex.last().contains("--ephemeral"))
        assertTrue(pi.last().contains("pi -p") && pi.last().contains("--no-session"))
    }

    @Test
    fun `PowerShell 命令保持提示词为单个安全参数`() {
        val command =
            titleGenerationCommand(
                "powershell.exe",
                AgentType.CLAUDE,
                "C:\\demo project",
                "修复 user's 登录",
            )

        assertTrue(command.contains("-NoProfile"))
        assertTrue(command.last().contains("'修复 user''s 登录'"))
    }

    @Test
    fun `生成标题去掉包装并限制为单行六十字符`() {
        assertEquals("修复会话标题刷新", normalizeGeneratedTitle("shell noise\n标题：修复会话标题刷新。\n"))
        assertEquals("修复会话标题刷新", normalizeGeneratedTitle("```\n修复会话标题刷新\n```\n"))
        assertEquals(
            "一".repeat(59) + "…",
            normalizeGeneratedTitle("一".repeat(80)),
        )
    }

    @Test
    fun `标题提示读取真实用户与助手消息`() {
        val file = temp.newFile("claude.jsonl").toPath()
        Files.writeString(
            file,
            """
            {"type":"user","message":{"role":"user","content":"修复登录流程"}}
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"我会检查认证代码"}]}}
            """.trimIndent(),
        )

        val prompt = titleGenerationPrompt(session(AgentType.CLAUDE, file))

        assertTrue(prompt.contains("USER: 修复登录流程"))
        assertTrue(prompt.contains("ASSISTANT: 我会检查认证代码"))
    }

    @Test
    fun `Claude 标题写回 custom-title 并被 Reader 读取`() {
        val home = temp.newFolder("claude-home").toPath()
        val reader = ClaudeSessionReader(home)
        val dir = home.resolve("projects").resolve(reader.projectDirName("/tmp/project"))
        Files.createDirectories(dir)
        val file = dir.resolve("claude-1.jsonl")
        Files.writeString(
            file,
            """
            {"type":"user","message":{"role":"user","content":"旧内容"}}
            {"type":"ai-title","aiTitle":"旧标题","sessionId":"claude-1"}
            """.trimIndent(),
        )

        writeGeneratedTitle(session(AgentType.CLAUDE, file, "claude-1"), "新标题", temp.root.toPath())

        assertEquals("新标题", reader.read("/tmp/project").single().title)
    }

    @Test
    fun `完整流程使用注入的 CLI 输出并写回标题`() {
        val home = temp.newFolder("flow-home").toPath()
        val reader = ClaudeSessionReader(home)
        val dir = home.resolve("projects").resolve(reader.projectDirName("/tmp/project"))
        Files.createDirectories(dir)
        val file = dir.resolve("flow.jsonl")
        Files.writeString(
            file,
            """{"type":"user","message":{"role":"user","content":"修复订单状态"}}""",
        )
        var receivedCommand: List<String>? = null
        val regenerator =
            SessionTitleRegenerator(
                userHome = home,
                shell = "/bin/zsh",
                runCli = { command, _, _ ->
                    receivedCommand = command
                    "订单状态修复"
                },
            )

        val title = regenerator.regenerate(session(AgentType.CLAUDE, file, "flow"), "/tmp/project")

        assertEquals("订单状态修复", title)
        assertTrue(receivedCommand!!.last().contains("claude -p"))
        assertEquals("订单状态修复", reader.read("/tmp/project").single().title)
    }

    @Test
    fun `Codex 标题写回 threads name 并被索引读取`() {
        val home = temp.newFolder("codex-home").toPath()
        val codexHome = home.resolve(".codex")
        Files.createDirectories(codexHome)
        val db = codexHome.resolve("state_5.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
            connection.createStatement().use {
                it.executeUpdate("CREATE TABLE threads (id TEXT PRIMARY KEY, name TEXT, title TEXT)")
                it.executeUpdate("INSERT INTO threads VALUES ('codex-1', NULL, '旧标题')")
            }
        }
        val rollout = temp.newFile("rollout.jsonl").toPath()

        writeGeneratedTitle(session(AgentType.CODEX, rollout, "codex-1"), "新标题", home)

        assertEquals("新标题", CodexThreadIndex(codexHome).load()["codex-1"])
    }

    @Test
    fun `Pi 标题追加 session_info 并被 Reader 读取`() {
        val home = temp.newFolder("pi-home").toPath()
        val reader = PiSessionReader(home)
        val dir = home.resolve("agent/sessions").resolve(reader.projectDirName("/tmp/project"))
        Files.createDirectories(dir)
        val file = dir.resolve("pi.jsonl")
        Files.writeString(
            file,
            """
            {"type":"session","version":3,"id":"pi-1","timestamp":"2026-08-31T03:00:00Z","cwd":"/tmp/project"}
            {"type":"message","id":"message-1","parentId":null,"timestamp":"2026-08-31T03:01:00Z","message":{"role":"user","content":"旧内容"}}
            """.trimIndent(),
        )

        val before = reader.read("/tmp/project").single().lastActiveAt
        writeGeneratedTitle(session(AgentType.PI, file, "pi-1"), "新标题", home)
        val renamed = reader.read("/tmp/project").single()

        assertEquals("新标题", renamed.title)
        assertEquals(before, renamed.lastActiveAt)
    }
}
