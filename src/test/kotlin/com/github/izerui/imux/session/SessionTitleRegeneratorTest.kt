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
    fun `用户消息与紧随其后的助手回复配成一轮`() {
        val file = temp.newFile("exchange-pair.jsonl").toPath()
        Files.writeString(
            file,
            """
            {"type":"user","message":{"role":"user","content":"帮我看下这个 bug"}}
            {"type":"assistant","message":{"role":"assistant","content":"我先读一下 CodexLspProbe"}}
            """.trimIndent(),
        )

        val exchanges = recentExchanges(session(AgentType.CLAUDE, file))

        assertEquals(1, exchanges.size)
        assertEquals("帮我看下这个 bug", exchanges.single().userText)
        assertEquals("我先读一下 CodexLspProbe", exchanges.single().assistantReply)
    }

    @Test
    fun `一轮里的多条助手回复只取第一条`() {
        val file = temp.newFile("exchange-multi-reply.jsonl").toPath()
        Files.writeString(
            file,
            """
            {"type":"user","message":{"role":"user","content":"支持多语言吧"}}
            {"type":"assistant","message":{"role":"assistant","content":"支持，我先补资源文件"}}
            {"type":"assistant","message":{"role":"assistant","content":"已经跑完测试了"}}
            """.trimIndent(),
        )

        val exchanges = recentExchanges(session(AgentType.CLAUDE, file))

        assertEquals("支持，我先补资源文件", exchanges.single().assistantReply)
    }

    @Test
    fun `末轮尚无助手回复时回复为空`() {
        val file = temp.newFile("exchange-pending.jsonl").toPath()
        Files.writeString(
            file,
            """
            {"type":"user","message":{"role":"user","content":"先问的一轮"}}
            {"type":"assistant","message":{"role":"assistant","content":"这轮答完了"}}
            {"type":"user","message":{"role":"user","content":"正在生成的一轮"}}
            """.trimIndent(),
        )

        val exchanges = recentExchanges(session(AgentType.CLAUDE, file))

        assertEquals(listOf("这轮答完了", ""), exchanges.map(SessionExchange::assistantReply))
    }

    @Test
    fun `三种 CLI 的对话轮次都可用于会话内导航`() {
        val claudeFile = temp.newFile("navigator-claude.jsonl").toPath()
        Files.writeString(
            claudeFile,
            """
            {"type":"user","message":{"role":"user","content":"Claude 用户消息"}}
            {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Claude 助手回复"}]}}
            """.trimIndent(),
        )
        val codexFile = temp.newFile("navigator-codex.jsonl").toPath()
        Files.writeString(
            codexFile,
            """
            {"type":"response_item","payload":{"role":"user","content":[{"type":"input_text","text":"Codex 用户消息"}]}}
            {"type":"response_item","payload":{"role":"assistant","content":[{"type":"output_text","text":"Codex 助手回复"}]}}
            """.trimIndent(),
        )
        val piFile = temp.newFile("navigator-pi.jsonl").toPath()
        Files.writeString(
            piFile,
            """
            {"type":"message","message":{"role":"user","content":[{"type":"text","text":"Pi 用户消息"}]}}
            {"type":"message","message":{"role":"assistant","content":[{"type":"text","text":"Pi 助手回复"}]}}
            """.trimIndent(),
        )

        assertEquals(
            listOf(SessionExchange("Claude 用户消息", "Claude 助手回复")),
            recentExchanges(session(AgentType.CLAUDE, claudeFile)),
        )
        assertEquals(
            listOf(SessionExchange("Codex 用户消息", "Codex 助手回复")),
            recentExchanges(session(AgentType.CODEX, codexFile)),
        )
        assertEquals(
            listOf(SessionExchange("Pi 用户消息", "Pi 助手回复")),
            recentExchanges(session(AgentType.PI, piFile)),
        )
    }

    @Test
    fun `Claude 大图片记录保留可见提示词`() {
        val file = temp.newFile("navigator-claude-image.jsonl").toPath()
        val image = "A".repeat(140_000)
        Files.writeString(
            file,
            """
            {"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Image #1] 帮我检查截图"},{"type":"image","source":{"type":"base64","data":"$image"}}]}}
            {"type":"assistant","message":{"role":"assistant","content":"我先检查截图中的报错"}}
            """.trimIndent(),
        )

        val exchanges = recentExchanges(session(AgentType.CLAUDE, file))

        assertEquals("[Image #1] 帮我检查截图", exchanges.single().userText)
        assertEquals("我先检查截图中的报错", exchanges.single().assistantReply)
    }

    @Test
    fun `Codex 大图片记录过滤包装文本并保留可见提示词`() {
        val file = temp.newFile("navigator-codex-image.jsonl").toPath()
        val image = "A".repeat(140_000)
        Files.writeString(
            file,
            """
            {"type":"response_item","payload":{"role":"user","content":[{"type":"input_text","text":"<image name=[Image #1] path=\"/tmp/screenshot.png\">"},{"type":"input_image","image_url":"data:image/png;base64,$image"},{"type":"input_text","text":"</image>"},{"type":"input_text","text":"[Image #1] 为什么会失败"}]}}
            {"type":"response_item","payload":{"role":"assistant","content":[{"type":"output_text","text":"我先检查失败原因"}]}}
            """.trimIndent(),
        )

        val exchanges = recentExchanges(session(AgentType.CODEX, file))

        assertEquals("[Image #1] 为什么会失败", exchanges.single().userText)
        assertEquals("我先检查失败原因", exchanges.single().assistantReply)
    }

    @Test
    fun `Claude 图片来源合成记录不新开轮次`() {
        val file = temp.newFile("navigator-claude-image-source.jsonl").toPath()
        Files.writeString(
            file,
            """
            {"type":"user","promptId":"prompt-1","message":{"role":"user","content":[{"type":"text","text":"[Image #2] 这个界面哪里有问题"}]}}
            {"type":"user","promptId":"prompt-1","isMeta":true,"message":{"role":"user","content":[{"type":"text","text":"[Image: source: /tmp/screenshot.png]"}]}}
            {"type":"assistant","message":{"role":"assistant","content":"按钮的对比度不足"}}
            """.trimIndent(),
        )

        val exchanges = recentExchanges(session(AgentType.CLAUDE, file))

        assertEquals(1, exchanges.size)
        assertEquals("[Image #2] 这个界面哪里有问题", exchanges.single().userText)
        assertEquals("按钮的对比度不足", exchanges.single().assistantReply)
    }

    @Test
    fun `Claude 隐藏 Meta 用户记录不新开轮次`() {
        val file = temp.newFile("navigator-claude-meta.jsonl").toPath()
        Files.writeString(
            file,
            """
            {"type":"user","message":{"role":"user","content":"继续修复导航器"}}
            {"type":"user","isMeta":true,"message":{"role":"user","content":"Skill contents: internal instructions"}}
            {"type":"assistant","message":{"role":"assistant","content":"我会继续处理导航器"}}
            """.trimIndent(),
        )

        val exchanges = recentExchanges(session(AgentType.CLAUDE, file))

        assertEquals(1, exchanges.size)
        assertEquals("继续修复导航器", exchanges.single().userText)
        assertEquals("我会继续处理导航器", exchanges.single().assistantReply)
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
