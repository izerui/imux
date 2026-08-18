package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.IMUX_TAB_ENV
import com.github.izerui.imux.session.PiReportEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
            "pi --session-id 'new-id' -e '/tmp/reporter.js' 'Continue the work'",
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
        assertEquals("/bin/zsh", resolveShell(null))
        assertEquals("/bin/zsh", resolveShell(""))
        assertEquals("/bin/bash", resolveShell("/bin/bash"))
    }

    @Test
    fun `pi 带上上报扩展`() {
        val script =
            java.nio.file.Paths
                .get("/plugins/imux/scripts/pi-imux-reporter.js")

        assertEquals(
            "pi --session-id 'abc-123' -e '/plugins/imux/scripts/pi-imux-reporter.js'",
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
}
