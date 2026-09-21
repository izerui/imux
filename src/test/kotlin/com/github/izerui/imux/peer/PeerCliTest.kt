package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.IdeaMcpEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import kotlin.time.measureTime

class PeerCliTest {
    private val ideaMcp = IdeaMcpEndpoint(64342, "/workspace")

    @Test
    fun `Claude 副驾驶启用全部工具并跳过权限检查`() {
        val script = peerCliCommand("/bin/zsh", AgentType.CLAUDE, "/tmp/project").last()

        assertFalse(script.contains("--safe-mode"))
        assertTrue(script.contains("--permission-mode bypassPermissions"))
        assertTrue(script.contains("--tools default"))
        assertTrue(script.contains("--no-session-persistence"))
        assertTrue(script.contains("--output-format stream-json"))
        assertTrue(script.contains("--verbose"))
        assertFalse(script.contains("--mcp-config"))
    }

    @Test
    fun `Codex 副驾驶不限制工具执行并且不保留会话`() {
        val script = peerCliCommand("/bin/zsh", AgentType.CODEX, "/tmp/project").last()

        assertTrue(script.contains("--dangerously-bypass-approvals-and-sandbox"))
        assertFalse(script.contains("--sandbox read-only"))
        assertTrue(script.contains("--ephemeral"))
        assertTrue(script.contains("--json"))
        assertTrue(script.contains("-C '/tmp/project'"))
        assertFalse(script.contains("mcp_servers.idea"))
    }

    @Test
    fun `Pi 副驾驶不设置工具白名单并且不保留会话`() {
        val script = peerCliCommand("/bin/zsh", AgentType.PI, "/tmp/project").last()

        assertFalse(script.contains("--tools"))
        assertFalse(script.contains("--no-tools"))
        assertTrue(script.contains("--no-session"))
        assertTrue(script.contains("--mode json"))
        assertFalse(script.contains("idea_mcp"))
    }

    @Test
    fun `PowerShell 命令使用对应方言参数`() {
        val command = peerCliCommand("powershell.exe", AgentType.CODEX, "C:\\work dir")

        assertTrue(command.contains("-ExecutionPolicy"))
        assertTrue(command.last().contains("Write-Output '$PEER_OUTPUT_MARKER'"))
        assertTrue(command.last().contains("-C 'C:\\work dir'"))
    }

    @Test
    fun `Claude 副驾驶临时注入 IDEA MCP 且不保留会话`() {
        val script =
            peerCliCommand(
                "/bin/zsh",
                AgentType.CLAUDE,
                "/tmp/project",
                ideaMcp,
                "Prefer IDEA MCP",
            ).last()

        assertTrue(script.contains("--mcp-config"))
        assertTrue(script.contains(ideaMcp.url))
        assertTrue(script.contains("IJ_MCP_SERVER_PROJECT_PATH"))
        assertTrue(script.contains("--append-system-prompt 'Prefer IDEA MCP'"))
        assertTrue(script.contains("--no-session-persistence"))
    }

    @Test
    fun `Codex 副驾驶临时注入 IDEA MCP 且不保留会话`() {
        val script =
            peerCliCommand(
                "/bin/zsh",
                AgentType.CODEX,
                "/tmp/project",
                ideaMcp,
                "Prefer IDEA MCP",
            ).last()

        assertTrue(script.contains("""mcp_servers.idea.url="${ideaMcp.url}""""))
        assertTrue(script.contains("mcp_servers.idea.http_headers.IJ_MCP_SERVER_PROJECT_PATH"))
        assertTrue(script.contains("""developer_instructions="Prefer IDEA MCP""""))
        assertTrue(script.contains("--ephemeral"))
    }

    @Test
    fun `Pi 副驾驶加载 IDEA MCP 扩展且不保留会话`() {
        val extension = Path.of("/tmp/pi-imux-idea-mcp.js")
        val script =
            peerCliCommand(
                "/bin/zsh",
                AgentType.PI,
                "/tmp/project",
                ideaMcp,
                "Prefer IDEA MCP",
                extension,
            ).last()

        assertTrue(script.contains("-e '/tmp/pi-imux-idea-mcp.js'"))
        assertFalse(script.contains("--tools"))
        assertTrue(script.contains("--no-session"))
    }

    @Test
    fun `peerCliCommand 传入 null endpoint 时 Claude 命令不含 MCP 参数`() {
        val script = peerCliCommand("/bin/zsh", AgentType.CLAUDE, "/tmp/project", null, null).last()

        assertFalse(script.contains("--mcp-config"))
        assertFalse(script.contains("IJ_MCP_SERVER"))
        assertTrue(script.contains("--no-session-persistence"))
    }

    @Test
    fun `peerCliCommand 传入 null endpoint 时 Codex 命令不含 MCP 参数`() {
        val script = peerCliCommand("/bin/zsh", AgentType.CODEX, "/tmp/project", null, null).last()

        assertFalse(script.contains("mcp_servers.idea"))
        assertTrue(script.contains("--ephemeral"))
    }

    @Test
    fun `peerCliCommand 传入 null piExtensionScript 时 Pi 命令不含 idea_mcp 工具`() {
        val script = peerCliCommand("/bin/zsh", AgentType.PI, "/tmp/project", ideaMcp, "Prefer IDEA MCP", null).last()

        assertFalse(script.contains("idea_mcp"))
        assertFalse(script.contains("-e "))
        assertFalse(script.contains("--tools"))
    }

    @Test
    fun `peerCliCommand 传入非 null endpoint 时命令包含对应端口的 MCP 参数`() {
        val customEndpoint = IdeaMcpEndpoint(59999, "/workspace")
        val claudeScript =
            peerCliCommand("/bin/zsh", AgentType.CLAUDE, "/tmp/project", customEndpoint, "Guide").last()
        val codexScript =
            peerCliCommand("/bin/zsh", AgentType.CODEX, "/tmp/project", customEndpoint, "Guide").last()

        assertTrue(claudeScript.contains("--mcp-config"))
        assertTrue(claudeScript.contains(customEndpoint.url))
        assertTrue(codexScript.contains("""mcp_servers.idea.url="${customEndpoint.url}""""))
    }

    @Test
    fun `buildPeerCliInvocation 端点为 null 时三种 Agent 的 command 和 environment 均不含 MCP`() {
        val config = PeerMcpConfig(endpoint = null, guidance = null, piExtensionScript = null)
        for (agent in AgentType.entries) {
            val inv = buildPeerCliInvocation("/bin/zsh", agent, "/tmp/project", config)
            assertFalse("$agent: command 不应含 MCP", inv.command.last().contains("mcp") || inv.command.last().contains("idea_mcp"))
            assertTrue("$agent: environment 应为空", inv.environment.isEmpty())
        }
    }

    @Test
    fun `buildPeerCliInvocation 有端点时 Claude command 含 mcp-config 和 guidance、environment 为空`() {
        val inv = buildPeerCliInvocation("/bin/zsh", AgentType.CLAUDE, "/tmp/project", PeerMcpConfig(ideaMcp, "Guide", null))

        assertTrue("command 应含 --mcp-config", inv.command.last().contains("--mcp-config"))
        assertTrue("command 应含端点 URL", inv.command.last().contains(ideaMcp.url))
        assertTrue("command 应含 guidance", inv.command.last().contains("Guide"))
        assertTrue("environment 应为空", inv.environment.isEmpty())
    }

    @Test
    fun `buildPeerCliInvocation 有端点时 Codex command 含 URL、请求头和 guidance`() {
        val inv = buildPeerCliInvocation("/bin/zsh", AgentType.CODEX, "/tmp/project", PeerMcpConfig(ideaMcp, "Guide", null))

        assertTrue("command 应含 mcp_servers.idea.url", inv.command.last().contains("""mcp_servers.idea.url="${ideaMcp.url}""""))
        assertTrue("command 应含请求头", inv.command.last().contains("mcp_servers.idea.http_headers.IJ_MCP_SERVER_PROJECT_PATH"))
        assertTrue("command 应含 guidance", inv.command.last().contains("""developer_instructions="Guide""""))
        assertTrue("environment 应为空", inv.environment.isEmpty())
    }

    @Test
    fun `buildPeerCliInvocation 有端点和脚本时 Pi command 加载脚本且 environment 含 URL 和 guidance`() {
        val piExtension = Path.of("/tmp/pi-imux-idea-mcp.js")
        val inv = buildPeerCliInvocation("/bin/zsh", AgentType.PI, "/tmp/project", PeerMcpConfig(ideaMcp, "Guide", piExtension))

        assertTrue("command 应含扩展脚本", inv.command.last().contains("pi-imux-idea-mcp.js"))
        assertEquals(ideaMcp.url, inv.environment["IMUX_IDEA_MCP_URL"])
        assertEquals(ideaMcp.projectPath, inv.environment["IMUX_IDEA_MCP_PROJECT"])
        assertEquals("Guide", inv.environment["IMUX_IDEA_MCP_GUIDANCE"])
    }

    @Test
    fun `buildPeerCliInvocation 有端点但无 Pi 扩展脚本时 Pi 不加载 idea_mcp 但 environment 仍含端点`() {
        val piInv = buildPeerCliInvocation("/bin/zsh", AgentType.PI, "/tmp/project", PeerMcpConfig(ideaMcp, "Guide", null))

        assertFalse("Pi command 不应含 idea_mcp", piInv.command.last().contains("idea_mcp"))
        assertFalse("Pi command 不应含 -e", piInv.command.last().contains("-e "))
        assertEquals("Pi environment 仍应含端点 URL", ideaMcp.url, piInv.environment["IMUX_IDEA_MCP_URL"])
    }

    @Test
    fun `shell 启动输出不会进入副驾驶反馈`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command =
            listOf(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                PeerCliTest::class.java.name,
                "output",
            )

        val output = runPeerCli(AgentType.CODEX, command, Path.of("."), "prompt", emptyMap(), 5, {}, {})

        assertEquals("真正的副驾驶反馈", output)
    }

    @Test
    fun `结构化输出同时产生进度事件与最终反馈`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command =
            listOf(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                PeerCliTest::class.java.name,
                "json-output",
            )
        val events = mutableListOf<PeerProgressEvent>()

        val output = runPeerCli(AgentType.CODEX, command, Path.of("."), "prompt", emptyMap(), 5, {}, events::add)

        assertEquals("最终反馈", output)
        assertEquals(PeerProgressKind.TOOL_STARTED, events[0].kind)
        assertTrue(events[0].subject.contains("rg TODO"))
        assertEquals(PeerProgressKind.TOOL_FINISHED, events[1].kind)
        assertEquals(PeerProgressKind.RESPONDING, events[2].kind)
    }

    @Test
    fun `输出流耗尽超时会抛出异常`() {
        var thrown: PeerCliException? = null

        try {
            awaitPeerCliDrain(CountDownLatch(1), "output", 0)
        } catch (e: PeerCliException) {
            thrown = e
        }

        assertTrue("超时应抛出 PeerCliException", thrown != null)
        assertTrue("异常消息应说明输出流未耗尽", thrown!!.message!!.contains("output did not finish draining"))
    }

    @Test
    fun `副驾驶环境变量传入子进程`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command =
            listOf(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                PeerCliTest::class.java.name,
                "environment",
            )

        val output =
            runPeerCli(
                AgentType.PI,
                command,
                Path.of("."),
                "prompt",
                mapOf("IMUX_TEST_PEER_ENV" to "connected"),
                5,
                {},
                {},
            )

        assertEquals("connected", output)
    }

    @Test
    fun `CLI 超时会在进程关闭输出流之前返回`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command =
            listOf(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                PeerCliTest::class.java.name,
                "sleep",
            )

        var thrown: PeerCliException? = null
        val elapsed =
            measureTime {
                try {
                    runPeerCli(
                        AgentType.CODEX,
                        command,
                        Path.of("."),
                        "x".repeat(1_000_000),
                        emptyMap(),
                        1,
                        {},
                        {},
                    )
                } catch (e: PeerCliException) {
                    thrown = e
                }
            }

        assertTrue("超时应抛出 PeerCliException", thrown != null)
        assertTrue("异常消息应包含超时信息", thrown!!.message!!.contains("timed out"))
        assertTrue("超时应在数秒内生效，实际 $elapsed", elapsed.inWholeSeconds < 5)
    }

    @Test
    fun `非零退出码携带 stderr 内容进入异常消息`() {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command =
            listOf(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                PeerCliTest::class.java.name,
                "fail",
            )

        var thrown: PeerCliException? = null
        try {
            runPeerCli(AgentType.CODEX, command, Path.of("."), "prompt", emptyMap(), 5, {}, {})
        } catch (e: PeerCliException) {
            thrown = e
        }

        assertTrue("非零退出应抛出 PeerCliException", thrown != null)
        assertTrue(
            "异常消息应包含 stderr 内容，实际: ${thrown!!.message}",
            thrown.message!!.contains("authentication failed"),
        )
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            when (args.firstOrNull()) {
                "sleep" -> Thread.sleep(30_000)
                "output" -> {
                    println("[proxy] enabled via 127.0.0.1:7890")
                    println(PEER_OUTPUT_MARKER)
                    println("真正的副驾驶反馈")
                }

                "json-output" -> {
                    println(PEER_OUTPUT_MARKER)
                    println("""{"type":"item.started","item":{"type":"command_execution","command":"rg TODO"}}""")
                    println("""{"type":"item.completed","item":{"type":"command_execution","command":"rg TODO"}}""")
                    println("""{"type":"item.completed","item":{"type":"agent_message","text":"最终反馈"}}""")
                }

                "environment" -> {
                    println(PEER_OUTPUT_MARKER)
                    println(System.getenv("IMUX_TEST_PEER_ENV"))
                }

                "fail" -> {
                    System.err.print("authentication failed: invalid token")
                    exitProcess(3)
                }
            }
        }
    }
}
