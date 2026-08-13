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
     * pi 默认不显示硬件光标（自绘一个），于是 262 的 cursor tracker 拿不到位置更新，
     * output model 的 cursorOffset 停在原处——输入法候选窗和组合文本都跟着定位到错处。
     *
     * pi 官方文档的 IntelliJ 一节就是这么说的：想要真实光标就设 PI_HARDWARE_CURSOR=1。
     * 与 claude 的 CLAUDE_CODE_NATIVE_CURSOR 是同一类问题、同一类解法。
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
    fun `未设置 SHELL 时回退到 zsh`() {
        assertEquals("/bin/zsh", resolveShell(null))
        assertEquals("/bin/zsh", resolveShell(""))
        assertEquals("/bin/bash", resolveShell("/bin/bash"))
    }
}
