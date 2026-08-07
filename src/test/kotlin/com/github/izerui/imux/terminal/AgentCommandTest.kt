package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.IMUX_TAB_ENV
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

    @Test
    fun `claude 使用原生终端光标供输入法定位`() {
        assertEquals(
            "1",
            launchEnvironment(AgentType.CLAUDE, "tab-1")["CLAUDE_CODE_NATIVE_CURSOR"],
        )
        assertNull(launchEnvironment(AgentType.CODEX, "tab-1")["CLAUDE_CODE_NATIVE_CURSOR"])
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
    fun `未设置 SHELL 时回退到 zsh`() {
        assertEquals("/bin/zsh", resolveShell(null))
        assertEquals("/bin/zsh", resolveShell(""))
        assertEquals("/bin/bash", resolveShell("/bin/bash"))
    }
}
