package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * 「最终回复说人话」的启动注入。
 *
 * 用户看到的效果只有一个：开着这个选项，imux 打开的会话做完事后用大白话汇报。
 * 断言因此落在「这段话有没有真的进到 CLI 的系统提示里」，并且分别守住三件容易坏的事：
 * 关掉 IDEA MCP 后还在、与 MCP 引导合并后两段都在、只给会话终端不给一次性调用。
 */
class PlainReplyLaunchTest {
    private companion object {
        val IDEA_MCP = IdeaMcpEndpoint(port = 64342, projectPath = "/workspace")
        const val REPLY = "Explain plainly"
    }

    @Test
    fun `未注入 IDEA MCP 时 Claude 新建与续聊仍追加回复引导`() {
        val created = launchCommand("/bin/zsh", AgentType.CLAUDE, "id-1", replyGuidance = REPLY, newSession = true).last()
        val resumed = launchCommand("/bin/zsh", AgentType.CLAUDE, "id-1", replyGuidance = REPLY).last()

        assertEquals("claude --append-system-prompt 'Explain plainly' --session-id 'id-1'", created)
        assertEquals("claude --append-system-prompt 'Explain plainly' --resume 'id-1'", resumed)
    }

    @Test
    fun `未注入 IDEA MCP 时 Codex 新建与续聊仍设置回复引导`() {
        val created = launchCommand("/bin/zsh", AgentType.CODEX, null, replyGuidance = REPLY).last()
        val resumed = launchCommand("/bin/zsh", AgentType.CODEX, "id-1", replyGuidance = REPLY).last()

        assertTrue(created, created.endsWith(""" -c 'developer_instructions="Explain plainly"'"""))
        assertTrue(resumed, resumed.endsWith(""" -c 'developer_instructions="Explain plainly"' resume 'id-1'"""))
    }

    /** claude 与 codex 都只认一个值：分两次传，后一个会把 MCP 引导或回复引导盖掉。 */
    @Test
    fun `Claude 与 Codex 都把 MCP 引导和回复引导合并成同一个参数`() {
        val claude =
            launchCommand("/bin/zsh", AgentType.CLAUDE, null, ideaMcp = IDEA_MCP, ideaMcpGuidance = "Prefer IDEA", replyGuidance = REPLY).last()
        val codex =
            launchCommand("/bin/zsh", AgentType.CODEX, null, ideaMcp = IDEA_MCP, ideaMcpGuidance = "Prefer IDEA", replyGuidance = REPLY).last()

        assertEquals(1, Regex("--append-system-prompt").findAll(claude).count())
        assertTrue(claude, claude.contains("--append-system-prompt 'Prefer IDEA\n\nExplain plainly'"))
        assertEquals(1, Regex("developer_instructions").findAll(codex).count())
        assertTrue(codex, codex.contains("""-c 'developer_instructions="Prefer IDEA\n\nExplain plainly"'"""))
    }

    @Test
    fun `关闭回复引导时 Claude 与 Codex 的命令与从前一致`() {
        assertEquals("claude", launchCommand("/bin/zsh", AgentType.CLAUDE, null, replyGuidance = null).last())
        assertEquals("claude", launchCommand("/bin/zsh", AgentType.CLAUDE, null, replyGuidance = "  ").last())
        assertFalse(launchCommand("/bin/zsh", AgentType.CODEX, null, replyGuidance = null).last().contains("developer_instructions"))
    }

    @Test
    fun `PowerShell 上回复引导里的双引号不裸露`() {
        val claude = launchCommand("powershell.exe", AgentType.CLAUDE, null, replyGuidance = """say "done" first""").last()
        val codex = launchCommand("powershell.exe", AgentType.CODEX, null, replyGuidance = """say "done" first""").last()

        assertFalse(claude, claude.substringAfter("--append-system-prompt").contains('"'))
        assertTrue(claude, claude.contains("[char]34"))
        assertFalse(codex, codex.substringAfter("developer_instructions").contains('"'))
    }

    @Test
    fun `pi 的回复引导走环境变量而不进命令行`() {
        val command = launchCommand("/bin/zsh", AgentType.PI, "id-1", replyGuidance = REPLY).last()
        assertFalse(command, command.contains(REPLY))

        assertEquals(REPLY, launchEnvironment(AgentType.PI, "tab", replyGuidance = REPLY)[PI_REPLY_STYLE_ENV])
        assertNull(launchEnvironment(AgentType.PI, "tab", replyGuidance = null)[PI_REPLY_STYLE_ENV])
        assertNull(launchEnvironment(AgentType.CLAUDE, "tab", replyGuidance = REPLY)[PI_REPLY_STYLE_ENV])
        assertNull(launchEnvironment(AgentType.CODEX, "tab", replyGuidance = REPLY)[PI_REPLY_STYLE_ENV])
    }

    @Test
    fun `joinGuidance 跳过空段并在全空时返回 null`() {
        assertEquals("a\n\nb", joinGuidance(" a ", null, "", "b"))
        assertNull(joinGuidance(null, "  "))
    }

    @Test
    fun `随包的 pi 回复扩展可按插件目录定位`() {
        val dir = kotlin.io.path.createTempDirectory()
        try {
            val script = dir.resolve("scripts").also { java.nio.file.Files.createDirectories(it) }.resolve("pi-imux-reply-style.js")
            assertNull(piReplyStyleScriptIn(dir))
            java.nio.file.Files.writeString(script, "// x")
            assertEquals(script, piReplyStyleScriptIn(dir))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * 接线守卫：新建、续聊、建终端三处都要从设置取回复引导并传下去；pi 还要带上扩展。
     * 任何一处漏传，对应情形下会话就静默地不说人话，而其余测试全绿。
     */
    @Test
    fun `会话终端的新建、续聊与建终端三处都传入回复引导`() {
        val host = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt")
        listOf("private fun newCommand(", "private fun resumeCommand(").forEach { entry ->
            val body = host.compact(host.bodyAfter(entry, '{'))
            assertTrue(entry, body.contains("valreplyGuidance=configuredPlainReplyGuidance()"))
            assertTrue(entry, body.contains("replyGuidance=replyGuidance)"))
            assertTrue(entry, body.contains("piExtensionsFor(agentType,ideaMcp,replyGuidance)"))
        }
        val create = host.compact(host.bodyAfter("private fun createView(", '{'))
        assertTrue(create.contains("valreplyGuidance=configuredPlainReplyGuidance()"))
        assertTrue(create, create.contains("replyGuidance=replyGuidance)+contribution?.environment.orEmpty()"))
        val extensions = host.compact(host.bodyAfter("private fun piExtensionsFor(", '{'))
        assertTrue(extensions, extensions.contains("if(replyGuidance!=null)piReplyStyleScript()?.let(::add)"))
    }

    /** 副驾驶审查、需求澄清等一次性调用的输出由程序解析，改写法会破坏它们的格式。 */
    @Test
    fun `一次性调用与副驾驶都不注入回复引导`() {
        listOf(
            "src/main/kotlin/com/github/izerui/imux/agent/OneShotAgentCommand.kt",
            "src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt",
            "src/main/kotlin/com/github/izerui/imux/peer/PeerCli.kt",
        ).forEach { path ->
            val source = java.io.File(path)
            assertTrue("被守护的文件不存在，这条断言会恒真：$path", source.isFile)
            assertFalse(path, source.readText().contains("PlainReply"))
        }
    }
}
