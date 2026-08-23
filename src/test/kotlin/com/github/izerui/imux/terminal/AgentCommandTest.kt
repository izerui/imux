package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.IMUX_TAB_ENV
import com.github.izerui.imux.session.PiReportEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class AgentCommandTest {
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

    @Test
    fun `新会话可携带安全转义的初始提示`() {
        assertEquals(
            "codex 'Read session '\\''abc'",
            launchCommand(
                "/bin/zsh",
                AgentType.CODEX,
                resumeId = null,
                initialPrompt = "Read session 'abc",
            ).last(),
        )
        assertEquals(
            "pi --session-id 'new-id' -e '${java.nio.file.Paths.get("/tmp/reporter.js")}' 'Continue the work'",
            launchCommand(
                "/bin/zsh",
                AgentType.PI,
                resumeId = "new-id",
                piExtension =
                    java.nio.file.Paths
                        .get("/tmp/reporter.js"),
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
            launchCommand("/bin/zsh", AgentType.PI, resumeId = "abc-123", piExtension = Path.of("/tmp/r.js")),
        )
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "claude --resume 'abc-123' 'say hi'"),
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
            listOf("/bin/zsh", "-l", "-i", "-c", "claude 'it'\\''s'"),
            launchCommand("/bin/zsh", AgentType.CLAUDE, resumeId = null, initialPrompt = "it's"),
        )
        assertEquals(
            listOf(
                "pwsh.exe", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                "claude 'it''s'",
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
            launchCommand("/bin/zsh", AgentType.PI, "abc-123", script).last(),
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
            launchCommand("/bin/zsh", AgentType.PI, "abc-123", null).last(),
        )
    }

    @Test
    fun `扩展只给 pi，不给另外两个 agent`() {
        val script =
            java.nio.file.Paths
                .get("/plugins/imux/scripts/pi-imux-reporter.js")

        assertEquals("claude --resume 'x'", launchCommand("/bin/zsh", AgentType.CLAUDE, "x", script).last())
        assertEquals("codex resume 'x'", launchCommand("/bin/zsh", AgentType.CODEX, "x", script).last())
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
                    "claude --resume 'abc-123' 'say hi'",
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
                "codex resume 'abc-123' 'say hi'",
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
