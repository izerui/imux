package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.IMUX_TAB_ENV
import com.github.izerui.imux.session.PiReportEndpoint
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class AgentCommandTest {
    private companion object {
        val IDEA_MCP = IdeaMcpEndpoint(port = 64342, projectPath = "/workspace")
    }

    @Test
    fun `经登录且交互的 shell 启动，而不是直接 exec`() {
        // 直接 exec 拿不到用户的 PATH：IDE 从 Dock 启动时 PATH 只有系统那几个目录，
        // /opt/homebrew/bin 之类根本不在里面。-l 读 profile 拿 PATH，
        // -i 读 rc 才能让 `claude` 这种 alias 生效。
        val command = launchCommand("/bin/zsh", AgentType.CLAUDE, resumeId = null)

        assertEquals(listOf("/bin/zsh", "-l", "-i", "-c", "claude"), command)
    }

    @Test
    fun `resume 带上会话 id`() {
        val command = launchCommand("/bin/zsh", AgentType.CLAUDE, resumeId = "abc-123")

        assertEquals("claude --resume 'abc-123'", command.last())
    }

    @Test
    fun `codex 的 resume 子命令与 claude 不同`() {
        assertEquals("codex", launchCommand("/bin/zsh", AgentType.CODEX, null).last())
        assertEquals(
            "codex resume 'abc-123'",
            launchCommand("/bin/zsh", AgentType.CODEX, "abc-123").last(),
        )
    }

    @Test
    fun `Claude 新建与续聊都临时注入 IDEA MCP`() {
        val config =
            """{"mcpServers":{"idea":{"type":"http","url":"${IDEA_MCP.url}",""" +
                """"headers":{"IJ_MCP_SERVER_PROJECT_PATH":"/workspace"}}}}"""

        assertEquals(
            "claude --mcp-config '$config'",
            launchCommand("/bin/zsh", AgentType.CLAUDE, null, ideaMcp = IDEA_MCP).last(),
        )
        assertEquals(
            "claude --mcp-config '$config' --resume 'abc-123'",
            launchCommand("/bin/zsh", AgentType.CLAUDE, "abc-123", ideaMcp = IDEA_MCP).last(),
        )
    }

    @Test
    fun `Codex 新建与续聊都临时注入 IDEA MCP`() {
        val args =
            """-c 'mcp_servers.idea.url="${IDEA_MCP.url}"' """ +
                """-c 'mcp_servers.idea.http_headers.IJ_MCP_SERVER_PROJECT_PATH="/workspace"'"""

        assertEquals(
            "codex $args",
            launchCommand("/bin/zsh", AgentType.CODEX, null, ideaMcp = IDEA_MCP).last(),
        )
        assertEquals(
            "codex $args resume 'abc-123'",
            launchCommand("/bin/zsh", AgentType.CODEX, "abc-123", ideaMcp = IDEA_MCP).last(),
        )
    }

    @Test
    fun `Claude 开启引导时追加系统提示词`() {
        val command =
            launchCommand(
                "/bin/zsh",
                AgentType.CLAUDE,
                null,
                ideaMcp = IDEA_MCP,
                ideaMcpGuidance = "Prefer IDEA MCP",
            ).last()

        assertTrue(command.contains("--append-system-prompt 'Prefer IDEA MCP'"))
    }

    @Test
    fun `PowerShell 上 Claude 自定义引导的双引号安全抵达`() {
        val command =
            launchCommand(
                "powershell.exe",
                AgentType.CLAUDE,
                null,
                ideaMcp = IDEA_MCP,
                ideaMcpGuidance = """Prefer "IDEA" MCP""",
            ).last()

        assertFalse("自定义引导里的裸双引号会被 Windows 命令行吃掉：$command", command.contains('"'))
        assertTrue("双引号必须由 PowerShell 在进程内拼回去：$command", command.contains("[char]34"))
    }

    @Test
    fun `Codex 开启引导时设置本次会话开发者指令`() {
        val command =
            launchCommand(
                "/bin/zsh",
                AgentType.CODEX,
                null,
                ideaMcp = IDEA_MCP,
                ideaMcpGuidance = "Prefer IDEA MCP",
            ).last()

        assertTrue(command.contains("""-c 'developer_instructions="Prefer IDEA MCP"'"""))
    }

    @Test
    fun `Claude 配置保留 Windows 路径中的反斜杠与引号`() {
        val path = """C:\work\"quoted"\app"""
        val config = claudeIdeaMcpConfig(IdeaMcpEndpoint(64342, path))
        val parsed =
            JsonParser
                .parseString(config)
                .asJsonObject
                .getAsJsonObject("mcpServers")
                .getAsJsonObject("idea")
                .getAsJsonObject("headers")
                .get(IDEA_MCP_PROJECT_HEADER)
                .asString

        assertEquals(path, parsed)
    }

    @Test
    fun `Codex 配置分别转义 Windows 路径的反斜杠与引号`() {
        assertEquals(
            """"C:\\work\\app"""",
            tomlBasicString("""C:\work\app"""),
        )
        assertEquals(
            """"C:\\work\\\"quoted\\\""""",
            tomlBasicString("""C:\work\"quoted\""""),
        )
    }

    /**
     * **项目定向请求头必须随注入一起到位。**
     *
     * 本机开着 3 个项目时实测：不带这个头，`get_project_modules` 这类不写 projectPath
     * 实参的调用会被服务端拒绝，返回「Unable to determine the target project」并要求模型
     * 反过来问用户选项目；带上之后同一个调用直接落到正确项目。
     *
     * 两种 agent 各断一条：它们走的是完全不同的配置通道（Claude 的 JSON `headers`
     * 与 Codex 的 `http_headers`），改坏一个不会牵动另一个。
     */
    @Test
    fun `Claude 与 Codex 都带上项目定向请求头`() {
        val claude = launchCommand("/bin/zsh", AgentType.CLAUDE, null, ideaMcp = IDEA_MCP).last()
        val codex = launchCommand("/bin/zsh", AgentType.CODEX, null, ideaMcp = IDEA_MCP).last()

        assertTrue(
            "Claude 少了项目定向头，多项目窗口下工具调用会被服务端拒绝：$claude",
            claude.contains(""""headers":{"IJ_MCP_SERVER_PROJECT_PATH":"/workspace"}"""),
        )
        assertTrue(
            "Codex 少了项目定向头，多项目窗口下工具调用会被服务端拒绝：$codex",
            codex.contains("""mcp_servers.idea.http_headers.IJ_MCP_SERVER_PROJECT_PATH="/workspace""""),
        )
    }

    /**
     * **PowerShell 上注入的两个参数里一个双引号都不许出现。**
     *
     * 两个参数内部都带双引号（JSON 与 TOML 的值）。用 [quote] 只加单引号，双引号原样留在
     * 脚本里；而这条脚本还要过一层 Windows 的命令行拼接，`CommandLineToArgvW` 可能把内层
     * 引号吃掉——CLI 收到非法 JSON / 非法 TOML 直接退出，而 MCP 注入默认开启，
     * 症状是 Windows 上每个 Claude/Codex 标签**一片空白**，且不报错。
     *
     * 断言分成三条，各守各的：
     * 1. 脚本里没有裸双引号（这是坑本身）
     * 2. 双引号确实以 `[char]34` 的形式在（光删掉引号也能让第 1 条过）
     * 3. 两种 agent 各断一次（只改一个分支时另一个不能跟着变绿）
     */
    @Test
    fun `PowerShell 上 Claude 与 Codex 的注入参数都不含裸双引号`() {
        val claude = launchCommand("powershell.exe", AgentType.CLAUDE, null, ideaMcp = IDEA_MCP).last()
        val codex = launchCommand("powershell.exe", AgentType.CODEX, null, ideaMcp = IDEA_MCP).last()

        assertFalse("Claude 的注入参数含裸双引号，Windows 上会被吃掉：$claude", claude.contains('"'))
        assertFalse("Codex 的注入参数含裸双引号，Windows 上会被吃掉：$codex", codex.contains('"'))
        assertTrue("Claude 的双引号必须以 [char]34 拼出来，而不是被删掉：$claude", claude.contains("[char]34"))
        assertTrue("Codex 的双引号必须以 [char]34 拼出来，而不是被删掉：$codex", codex.contains("[char]34"))
    }

    /** PowerShell 求值那串拼接之后，CLI 真正收到的应当与 POSIX 上一模一样。 */
    @Test
    fun `PowerShell 拼出的注入参数求值后与 POSIX 等价`() {
        assertEquals(
            """--mcp-config {"mcpServers":{"idea":{"type":"http","url":"${IDEA_MCP.url}",""" +
                """"headers":{"IJ_MCP_SERVER_PROJECT_PATH":"/workspace"}}}}""",
            evaluatePowerShellLiterals(
                launchCommand("powershell.exe", AgentType.CLAUDE, null, ideaMcp = IDEA_MCP)
                    .last()
                    .removePrefix("claude "),
            ),
        )
        assertEquals(
            """-c mcp_servers.idea.url="${IDEA_MCP.url}" """ +
                """-c mcp_servers.idea.http_headers.IJ_MCP_SERVER_PROJECT_PATH="/workspace"""",
            evaluatePowerShellLiterals(
                launchCommand("powershell.exe", AgentType.CODEX, null, ideaMcp = IDEA_MCP)
                    .last()
                    .removePrefix("codex "),
            ),
        )
    }

    /**
     * 把 `('a' + [char]34 + 'b')` 这种 PowerShell 字面量拼接求值成它实际会产生的字符串。
     *
     * 只认这一种形状——测试要验的就是 [quoteEmbeddingDoubleQuotes] 生成的东西，
     * 写成通用 PowerShell 解释器反而会把「生成了别的形状」这类错误吞掉。
     */
    private fun evaluatePowerShellLiterals(argument: String): String =
        Regex("""\(([^()]*)\)""").replace(argument) { match ->
            match.groupValues[1].split(" + ").joinToString("") { part ->
                when {
                    part == "[char]34" -> "\""
                    part.startsWith("'") && part.endsWith("'") -> part.removeSurrounding("'").replace("''", "'")
                    else -> throw AssertionError("不认识的 PowerShell 片段：$part（整段：$argument）")
                }
            }
        }

    /**
     * 两个扩展**各断一条**。
     *
     * 合成一条字符串断言时，只要整行对得上就绿——而这两个扩展守的是两件互不相干的事：
     * 上报扩展没了，pi 标签的标题与未读状态全停；IDEA MCP 扩展没了，pi 少掉整个 IDE
     * 能力。任一缺失都必须让一条断言单独变红（见 `AGENTS.md` 的用例命名规则）。
     */
    @Test
    fun `pi 同时加载上报与 IDEA MCP 两个扩展`() {
        val reporter = Path.of("/tmp/pi-imux-reporter.js")
        val idea = Path.of("/tmp/pi-imux-idea-mcp.js")
        val script =
            launchCommand(
                "/bin/zsh",
                AgentType.PI,
                "abc-123",
                piExtensions = listOf(reporter, idea),
                ideaMcp = IDEA_MCP,
            ).last()

        assertTrue("少了上报扩展，pi 标签的标题与未读状态会整个停更：$script", script.contains("-e '$reporter'"))
        assertTrue("少了 IDEA MCP 扩展，pi 拿不到任何 IDE 能力：$script", script.contains("-e '$idea'"))
        assertEquals("pi --session-id 'abc-123' -e '$reporter' -e '$idea'", script)
    }

    /**
     * pi 的会话 id 由 imux 预先生成：`--session-id` 对已存在的 id 是打开、不存在则以该 id 创建，
     * 所以新建与续聊是同一条命令。这样标签页与会话 id 从启动那一刻就是确定的，
     * 不需要像 codex 那样事后靠 lsof 反推绑定。
     */
    @Test
    fun `pi 新建与续聊都用 session-id 预绑定`() {
        assertEquals(
            "pi --session-id 'abc-123'",
            launchCommand("/bin/zsh", AgentType.PI, "abc-123").last(),
        )
    }

    @Test
    fun `pi 缺少会话 id 时退回裸命令`() {
        // 正常路径不会走到这里（新建也会给 id），但缺了 id 也得能起得来，
        // 让 pi 自己生成 id，总好过拼出 `pi --session-id` 这种残命令。
        assertEquals("pi", launchCommand("/bin/zsh", AgentType.PI, null).last())
    }

    @Test
    fun `只有 pi 在新建时预先确定会话 id`() {
        // claude 与 codex 的 id 由 CLI 自己生成，插件事后再认领；
        // 替它们预分配 id 既做不到，也会让绑定逻辑凭空多一条假设。
        assertNull(preassignedSessionId(AgentType.CLAUDE))
        assertNull(preassignedSessionId(AgentType.CODEX))
        assertEquals("uuid-1", preassignedSessionId(AgentType.PI) { "uuid-1" })
    }

    @Test
    fun `claude 使用原生终端光标供输入法定位`() {
        assertEquals(
            "1",
            launchEnvironment(AgentType.CLAUDE, "tab-1")["CLAUDE_CODE_NATIVE_CURSOR"],
        )
        assertNull(launchEnvironment(AgentType.CODEX, "tab-1")["CLAUDE_CODE_NATIVE_CURSOR"])
    }

    /**
     * IDEA 262 的输入法请求只在 terminal cursor 可见时使用它的位置，因此仍需打开硬件光标。
     * pi 的反色假光标由随进程加载的 imux editor 包装器去掉，不能在这里关闭定位来源。
     */
    @Test
    fun `pi 显示硬件光标供输入法定位`() {
        assertEquals("1", launchEnvironment(AgentType.PI, "tab-1")["PI_HARDWARE_CURSOR"])
        assertNull(launchEnvironment(AgentType.CODEX, "tab-1")["PI_HARDWARE_CURSOR"])
        assertNull(launchEnvironment(AgentType.CLAUDE, "tab-1")["PI_HARDWARE_CURSOR"])
    }

    @Test
    fun `两种 agent 都带上终端标记`() {
        // CLI 在 /clear、/new 后会换会话 id 而进程不变，这个标记是把进程认回
        // 对应终端的唯一依据，两边都不能少。见 LiveSessionProbe。
        AgentType.entries.forEach { type ->
            assertEquals(
                "$type 的终端必须带 IMUX_TAB",
                "tab-7",
                launchEnvironment(type, "tab-7")[IMUX_TAB_ENV],
            )
        }
    }

    @Test
    fun `会话 id 里的单引号被转义`() {
        // id 正常是 UUID，但它来自文件名，不该假定内容安全——
        // 拼进 shell 命令行的东西一律当作不可信
        val command = launchCommand("/bin/zsh", AgentType.CLAUDE, "a'b")

        assertTrue(
            "单引号必须被转义，否则会截断引号并把后面的内容当命令执行：${command.last()}",
            command.last().contains("""'a'\''b'"""),
        )
    }

    /**
     * **初始 prompt 前必须有 `--`，否则 claude 的 variadic 选项会把它吞掉。**
     *
     * `--mcp-config` 可接多个配置，紧随其后的位置参数会被当成第二个配置**文件路径**。
     * 实测（claude 2.1.236）：
     * ```
     * claude --mcp-config '<json>' '说 hi'
     * Error: Invalid MCP configuration: MCP config file not found: /private/tmp/e2e/说 hi
     * ```
     * 而注入默认开启、新建时 claude 又没有别的 flag 垫在中间——「交接到…」开出来的
     * 每个 Claude 标签都会**启动即失败**。
     *
     * 断言分开写：一条钉 claude 带注入这个真实炸点，另一条钉「没有注入时也照样加」
     * ——否则把 `--` 挪进 `ideaMcpArgument` 分支里也能让第一条过，而那样一来
     * 用户关掉注入后同一个位置又成了裸奔。
     */
    @Test
    fun `初始 prompt 前有分隔符，不会被 variadic 选项吞掉`() {
        val withMcp = launchCommand("/bin/zsh", AgentType.CLAUDE, null, ideaMcp = IDEA_MCP, initialPrompt = "说 hi").last()
        val withoutMcp = launchCommand("/bin/zsh", AgentType.CLAUDE, null, initialPrompt = "说 hi").last()

        assertTrue(
            "prompt 紧跟在 --mcp-config 后面会被当成配置文件路径，标签启动即失败：$withMcp",
            withMcp.endsWith(" -- '说 hi'"),
        )
        assertEquals("claude -- '说 hi'", withoutMcp)
    }

    @Test
    fun `新会话可携带安全转义的初始提示`() {
        assertEquals(
            "codex -- 'Read session '\\''abc'",
            launchCommand(
                "/bin/zsh",
                AgentType.CODEX,
                resumeId = null,
                initialPrompt = "Read session 'abc",
            ).last(),
        )
        assertEquals(
            "pi --session-id 'new-id' -e '${java.nio.file.Paths.get("/tmp/reporter.js")}' -- 'Continue the work'",
            launchCommand(
                "/bin/zsh",
                AgentType.PI,
                resumeId = "new-id",
                piExtensions =
                    listOf(
                        java.nio.file.Paths
                            .get("/tmp/reporter.js"),
                    ),
                initialPrompt = "Continue the work",
            ).last(),
        )
    }

    @Test
    fun `未设置 SHELL 时回退到 zsh`() {
        assertEquals("/bin/zsh", resolveShell(null, isWindows = false, configuredShell = null))
        assertEquals("/bin/zsh", resolveShell("", isWindows = false, configuredShell = null))
        assertEquals("/bin/bash", resolveShell("/bin/bash", isWindows = false, configuredShell = null))
    }

    @Test
    fun `非 Windows 上的 shell 解析与现网逐字节相同`() {
        // 这是 macOS 上正在工作的行为，改它等于改用户机器上正在跑的东西
        assertEquals("/bin/zsh", resolveShell(null, isWindows = false, configuredShell = null))
        assertEquals("/bin/zsh", resolveShell("", isWindows = false, configuredShell = null))
        assertEquals("/bin/zsh", resolveShell("   ", isWindows = false, configuredShell = null))
        assertEquals("/bin/bash", resolveShell("/bin/bash", isWindows = false, configuredShell = null))
    }

    @Test
    fun `非 Windows 上不理会 IDE 配置的 shell`() {
        // 换数据源会改变 macOS 行为，与「原有平台不能出问题」冲突
        assertEquals(
            "/bin/bash",
            resolveShell("/bin/bash", isWindows = false, configuredShell = "/usr/local/bin/fish"),
        )
        // SHELL 为空时也不能拿 configuredShell 顶上去，否则兜底路径从 /bin/zsh 变了
        assertEquals(
            "/bin/zsh",
            resolveShell(null, isWindows = false, configuredShell = "/usr/local/bin/fish"),
        )
    }

    @Test
    fun `Windows 上采用 IDE 配置的 shell`() {
        // 路径含空格时 IDE 会用双引号包裹——这是平台实际存的形状
        assertEquals(
            "C:\\Program Files\\PowerShell\\7\\pwsh.exe",
            resolveShell(
                shellEnv = null,
                isWindows = true,
                configuredShell = "\"C:\\Program Files\\PowerShell\\7\\pwsh.exe\"",
            ),
        )
    }

    @Test
    fun `Windows 上保留用户配置的 Git Bash`() {
        // 路径含空格时 IDE 会用双引号包裹——这是平台实际存的形状
        assertEquals(
            "C:\\Program Files\\Git\\bin\\bash.exe",
            resolveShell(null, isWindows = true, configuredShell = "\"C:\\Program Files\\Git\\bin\\bash.exe\""),
        )
    }

    @Test
    fun `Windows 上按 JetBrains 文档配的 Git Bash 命令行仍被保留`() {
        assertEquals(
            "C:\\Program Files\\Git\\bin\\bash.exe",
            resolveShell(
                null,
                isWindows = true,
                configuredShell = "\"C:\\Program Files\\Git\\bin\\bash.exe\" --login -i",
            ),
        )
    }

    @Test
    fun `shellExecutableOf 从命令行取出可执行文件`() {
        assertEquals(
            "C:\\Program Files\\Git\\bin\\bash.exe",
            shellExecutableOf("\"C:\\Program Files\\Git\\bin\\bash.exe\" --login -i"),
        )
        assertEquals(
            "C:\\Program Files\\Git\\bin\\bash.exe",
            shellExecutableOf("\"C:\\Program Files\\Git\\bin\\bash.exe\""),
        )
        assertEquals("pwsh.exe", shellExecutableOf("pwsh.exe -NoLogo"))
        assertEquals("/bin/zsh", shellExecutableOf("/bin/zsh"))
        assertNull(shellExecutableOf(null))
        assertNull(shellExecutableOf("   "))
    }

    @Test
    fun `Windows 上配的是 cmd 时改用 PowerShell`() {
        // 全文唯一一处不听用户配置：cmd 的转义规则写错会把初始 prompt 拼成另一条命令
        assertEquals(
            "powershell.exe",
            resolveShell(null, isWindows = true, configuredShell = "C:\\Windows\\System32\\cmd.exe"),
        )
    }

    @Test
    fun `Windows 上取不到配置时退回 PowerShell 而不是 bin zsh`() {
        assertEquals("powershell.exe", resolveShell(null, isWindows = true, configuredShell = null))
        assertEquals("powershell.exe", resolveShell(null, isWindows = true, configuredShell = "  "))
        // Windows 上 SHELL 通常没有值；就算有，也不该拿 POSIX 路径去 ProcessBuilder
        assertEquals("powershell.exe", resolveShell("/bin/zsh", isWindows = true, configuredShell = null))
    }

    @Test
    fun `macOS 形态的启动命令逐字节不变`() {
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "claude"),
            launchCommand("/bin/zsh", AgentType.CLAUDE, resumeId = null),
        )
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "claude --resume 'abc-123'"),
            launchCommand("/bin/zsh", AgentType.CLAUDE, resumeId = "abc-123"),
        )
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "codex resume 'abc-123'"),
            launchCommand("/bin/zsh", AgentType.CODEX, resumeId = "abc-123"),
        )
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "pi --session-id 'abc-123'"),
            launchCommand("/bin/zsh", AgentType.PI, resumeId = "abc-123"),
        )
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "pi --session-id 'abc-123' -e '${Path.of("/tmp/r.js")}'"),
            launchCommand("/bin/zsh", AgentType.PI, resumeId = "abc-123", piExtensions = listOf(Path.of("/tmp/r.js"))),
        )
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "claude --resume 'abc-123' -- 'say hi'"),
            launchCommand("/bin/zsh", AgentType.CLAUDE, resumeId = "abc-123", initialPrompt = "say hi"),
        )
    }

    @Test
    fun `PowerShell 形态用 PowerShell 的参数与引号`() {
        assertEquals(
            listOf(
                "pwsh.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                "claude --resume 'abc-123'",
            ),
            launchCommand("pwsh.exe", AgentType.CLAUDE, resumeId = "abc-123"),
        )
    }

    @Test
    fun `初始 prompt 里的单引号按方言转义`() {
        // prompt 是用户自由输入，是整条命令行里最不可信的一段
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "claude -- 'it'\\''s'"),
            launchCommand("/bin/zsh", AgentType.CLAUDE, resumeId = null, initialPrompt = "it's"),
        )
        assertEquals(
            listOf(
                "pwsh.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                "claude -- 'it''s'",
            ),
            launchCommand("pwsh.exe", AgentType.CLAUDE, resumeId = null, initialPrompt = "it's"),
        )
    }

    @Test
    fun `pi 带上上报扩展`() {
        val script =
            java.nio.file.Paths
                .get("/plugins/imux/scripts/pi-imux-reporter.js")

        assertEquals(
            "pi --session-id 'abc-123' -e '$script'",
            launchCommand("/bin/zsh", AgentType.PI, "abc-123", listOf(script)).last(),
        )
    }

    /**
     * 脚本缺失（安装不完整）时绝不能拼出半截 -e：pi 加载不到扩展会启动失败，
     * 代价是整个会话起不来，而少了上报只是标签页不自动跟随。
     */
    @Test
    fun `扩展脚本缺失时不加 -e`() {
        assertEquals(
            "pi --session-id 'abc-123'",
            launchCommand("/bin/zsh", AgentType.PI, "abc-123", emptyList()).last(),
        )
    }

    @Test
    fun `扩展只给 pi，不给另外两个 agent`() {
        val script =
            java.nio.file.Paths
                .get("/plugins/imux/scripts/pi-imux-reporter.js")

        assertEquals("claude --resume 'x'", launchCommand("/bin/zsh", AgentType.CLAUDE, "x", listOf(script)).last())
        assertEquals("codex resume 'x'", launchCommand("/bin/zsh", AgentType.CODEX, "x", listOf(script)).last())
    }

    @Test
    fun `POSIX 上传了 pid 文件也不改变启动命令`() {
        // macOS 与 Linux 靠环境变量认领，命令行必须与改动前逐字节相同
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "claude"),
            launchCommand("/bin/zsh", AgentType.CLAUDE, resumeId = null, pidFile = "/tmp/x.pid"),
        )
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "codex resume 'abc-123'"),
            launchCommand("/bin/zsh", AgentType.CODEX, resumeId = "abc-123", pidFile = "/tmp/x.pid"),
        )
    }

    @Test
    fun `PowerShell 上 pid 自报排在 CLI 之前`() {
        assertEquals(
            listOf(
                "pwsh.exe",
                "-NoLogo",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                "\$PID | Set-Content -LiteralPath 'C:\\t\\x.pid' -Encoding ascii; claude",
            ),
            launchCommand("pwsh.exe", AgentType.CLAUDE, resumeId = null, pidFile = "C:\\t\\x.pid"),
        )
    }

    /**
     * 分隔符必须是 `;` 而不是 `&&`。
     *
     * 写 pid 文件失败（目录没建起来、磁盘满、杀毒软件挡住）只该让这个标签认不出漂移，
     * 绝不能连带让整个会话起不来——`&&` 会在第一条失败时短路掉 CLI，
     * 把一个软失败升级成硬失败。症状是「点了会话，标签页一闪就没了」。
     */
    @Test
    fun `pid 自报与 CLI 之间用分号而不是与号`() {
        val script = launchCommand("pwsh.exe", AgentType.CLAUDE, resumeId = null, pidFile = "C:\\t\\x.pid").last()

        assertTrue(
            "pid 自报写失败不能短路掉 CLI，两条命令之间必须是分号：$script",
            script.contains("ascii; claude"),
        )
        assertFalse(
            "用 && 会把写 pid 文件的软失败升级成整个会话起不来：$script",
            script.contains("&&"),
        )
    }

    @Test
    fun `不传 pid 文件时命令与从前一致`() {
        assertEquals(
            listOf(
                "pwsh.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                "claude",
            ),
            launchCommand("pwsh.exe", AgentType.CLAUDE, resumeId = null, pidFile = null),
        )
    }

    @Test
    fun `pid 自报排在初始 prompt 之前且整条只有一次`() {
        assertEquals(
            listOf(
                "pwsh.exe",
                "-NoLogo",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                "\$PID | Set-Content -LiteralPath 'C:\\t\\x.pid' -Encoding ascii; " +
                    "claude --resume 'abc-123' -- 'say hi'",
            ),
            launchCommand(
                "pwsh.exe",
                AgentType.CLAUDE,
                resumeId = "abc-123",
                initialPrompt = "say hi",
                pidFile = "C:\\t\\x.pid",
            ),
        )
    }

    @Test
    fun `pi 拿到上报地址与令牌`() {
        val endpoint = PiReportEndpoint("http://127.0.0.1:63342/imux/pi-session", "tok-1")
        val env = launchEnvironment(AgentType.PI, "tab-1", endpoint)

        assertEquals("http://127.0.0.1:63342/imux/pi-session", env["IMUX_REPORT_URL"])
        assertEquals("tok-1", env["IMUX_TOKEN"])
    }

    @Test
    fun `只有 pi 拿到 IDEA MCP 桥接环境`() {
        val endpoint = IdeaMcpEndpoint(64342, "/workspace")

        assertEquals("http://127.0.0.1:64342/stream", launchEnvironment(AgentType.PI, "tab-1", ideaMcp = endpoint)["IMUX_IDEA_MCP_URL"])
        assertEquals("/workspace", launchEnvironment(AgentType.PI, "tab-1", ideaMcp = endpoint)["IMUX_IDEA_MCP_PROJECT"])
        assertNull(launchEnvironment(AgentType.CLAUDE, "tab-1", ideaMcp = endpoint)["IMUX_IDEA_MCP_URL"])
        assertNull(launchEnvironment(AgentType.CODEX, "tab-1", ideaMcp = endpoint)["IMUX_IDEA_MCP_URL"])
    }

    @Test
    fun `pi 开启引导时拿到自定义提示词`() {
        val endpoint = IdeaMcpEndpoint(64342, "/workspace")
        val env =
            launchEnvironment(
                AgentType.PI,
                "tab-1",
                ideaMcp = endpoint,
                ideaMcpGuidance = "Prefer IDEA MCP",
            )

        assertEquals("Prefer IDEA MCP", env["IMUX_IDEA_MCP_GUIDANCE"])
        assertNull(launchEnvironment(AgentType.CLAUDE, "tab-1", ideaMcp = endpoint, ideaMcpGuidance = "x")["IMUX_IDEA_MCP_GUIDANCE"])
        assertNull(launchEnvironment(AgentType.CODEX, "tab-1", ideaMcp = endpoint, ideaMcpGuidance = "x")["IMUX_IDEA_MCP_GUIDANCE"])
    }

    /** 令牌是这个接口唯一的门禁：平台在 HttpRequestHandler 这层不做任何校验。 */
    @Test
    fun `令牌不发给 pi 以外的 agent`() {
        val endpoint = PiReportEndpoint("http://127.0.0.1:63342/imux/pi-session", "tok-1")

        assertNull(launchEnvironment(AgentType.CLAUDE, "tab-1", endpoint)["IMUX_TOKEN"])
        assertNull(launchEnvironment(AgentType.CODEX, "tab-1", endpoint)["IMUX_TOKEN"])
    }

    @Test
    fun `内置服务不可用时 pi 照常启动`() {
        val env = launchEnvironment(AgentType.PI, "tab-1", null)

        assertNull(env["IMUX_REPORT_URL"])
        assertNull(env["IMUX_TOKEN"])
        assertEquals("tab-1", env[IMUX_TAB_ENV])
    }

    /**
     * codex 在**任何**平台上都不拿令牌。
     *
     * 三个平台各有自己的观测面，一条都不靠上报：macOS 走 `lsof`、Linux 走 `/proc`
     * 读 codex 持有的会话文件句柄，Windows 读 codex 自己写的运行态 sqlite
     * （`CodexRuntimeIndex`）。而令牌是上报接口唯一的门禁，多发一个进程就多一份泄漏面。
     *
     * **「任何平台」这句话必须由方法体自己兑现，而不是靠名字宣称。**
     * `launchEnvironment` 现在**没有平台轴**——`isWindows` 形参随 codex 的 hook 上报
     * 一起删掉了，所以行为断言再怎么写也只有一支可传。光靠两条 `assertEquals`
     * （传/不传 endpoint）撑不起「任何平台」这四个字：谁把 Windows 的令牌下发接回来
     * （加一个平台形参 + Windows 分支），它们走默认实参照样全绿。
     *
     * 因此这里把**「平台无关是构造性的」本身**变成断言：钉住 `launchEnvironment`
     * 的签名里一个平台形参都没有。重新加回 `isWindows` 的那一刻它就变红，
     * 而那正是需要有人重新审视令牌下发面的时刻。
     *
     * 行为侧则断言**整张环境变量表**相等，而不只是缺了哪两个键——顺手把令牌塞回来
     * 会当场变红。
     */
    @Test
    fun `codex 在任何平台上都不拿令牌`() {
        val endpoint = PiReportEndpoint("http://127.0.0.1:63342/imux/pi-session", "tok-1")

        assertEquals(
            "带着 pi 的端点调用也不能漏给 codex：令牌是上报接口唯一的门禁",
            mapOf(IMUX_TAB_ENV to "tab-1"),
            launchEnvironment(AgentType.CODEX, "tab-1", endpoint),
        )
        assertEquals(
            "不传端点时同样只有 IMUX_TAB",
            mapOf(IMUX_TAB_ENV to "tab-1"),
            launchEnvironment(AgentType.CODEX, "tab-1"),
        )

        // 上面两条只覆盖「传不传 endpoint」这一个轴。「任何平台」这句话由下面这条兑现：
        // 函数签名里没有平台形参，平台分支因此在构造上不可能存在。
        val source = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt")
        source.assertSameCode(
            "重新加回平台形参就意味着又要按平台决定令牌发不发——那是上报接口唯一的" +
                "门禁，必须有人当场重新审视，而不是让一条名为「任何平台」的用例走默认" +
                "实参悄悄绿过去。若只是动了排版，照下面的「期望」抄回去即可。",
            """
            (
                agentType: AgentType,
                tabId: String,
                piReport: PiReportEndpoint? = null,
                ideaMcp: IdeaMcpEndpoint? = null,
                ideaMcpGuidance: String? = null,
            )
            """,
            source.bodyAfter("internal fun launchEnvironment", '('),
        )
    }

    /**
     * pid 自报与初始 prompt 在 codex 上共存的完整形态。
     *
     * **codex 也写 pid 文件**：`tabPidFileFor` 只看 `SystemInfo.isWindows`，不看
     * agent 类型。Windows 上「这个 shell 属于哪个标签」是由 pid 文件认的，
     * 运行态 sqlite 只回答「此刻在跑哪个会话」——两半缺一不可，
     * 见 [com.github.izerui.imux.session.tabIdByParentChain]。
     */
    @Test
    fun `codex 的 pid 自报与初始 prompt 共存`() {
        assertEquals(
            "\$PID | Set-Content -LiteralPath 'C:\\t\\x.pid' -Encoding ascii; " +
                "codex resume 'abc-123' -- 'say hi'",
            launchCommand(
                "pwsh.exe",
                AgentType.CODEX,
                resumeId = "abc-123",
                initialPrompt = "say hi",
                pidFile = "C:\\t\\x.pid",
            ).last(),
        )
    }
}
