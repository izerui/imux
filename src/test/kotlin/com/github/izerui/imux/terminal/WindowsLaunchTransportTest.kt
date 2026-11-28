package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.concurrent.TimeUnit

/** Exercises the production command through Windows processes, including npm's actual shims. */
@RunWith(Parameterized::class)
class WindowsLaunchTransportTest(private val launcher: String, private val agent: AgentType) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<Any>> = listOf("native", "npm-ps1", "npm-cmd").flatMap { kind ->
            AgentType.entries.map { arrayOf<Any>(kind, it) }
        }
    }

    @Test
    fun `新建会话的配置和提示原样抵达进程`() = verify(newSession = true)

    @Test
    fun `恢复会话的配置和提示原样抵达进程`() = verify(newSession = false)

    private fun verify(newSession: Boolean) {
        // Local macOS/Linux runs explicitly skip; Windows CI requires fixtures and must fail if missing.
        assumeTrue("Requires Windows processes", System.getProperty("os.name").startsWith("Windows"))
        val shell = System.getenv("IMUX_TEST_SHELL") ?: "powershell.exe"
        val fixtureRoot = File(checkNotNull(System.getenv("IMUX_WINDOWS_PROBE_ROOT")) {
            "Run the Windows Actions probe setup first"
        })
        val bin = File(fixtureRoot, launcher)
        val suffix = when (launcher) { "native" -> "exe"; "npm-ps1" -> "ps1"; else -> "cmd" }
        assertTrue("Missing probe: $bin/${agent.cli}.$suffix", File(bin, "${agent.cli}.$suffix").isFile)
        val mode = if (newSession) "new" else "resume"
        val report = File("build/reports/windows-launch/$shell-$launcher-${agent.cli}-$mode").absoluteFile
        report.mkdirs()
        val receivedFile = File(report, "received.json").apply { delete() }
        val pidFile = File(report, "tab pid.txt").apply { delete() }
        val endpoint = IdeaMcpEndpoint(64342, "C:\\项目 空格\\O'Brien\\app")
        val server = TerminalMcpServer("imux_mobile", "http://127.0.0.1:63342/imux/mobile-mcp", "IMUX_MOBILE_TOKEN")
        val settings = claudeHookSettings("http://127.0.0.1:63342/imux/claude-hook", "IMUX_MOBILE_TOKEN")
        val guidance = "保留 \"双引号\"、O'Brien、\$IMUX_MOBILE_TOKEN 和 C:\\work\\app"
        val prompt = "检查 \"JSON\" 与 O'Brien；不要执行 \$(Write-Output unexpected)"
        val id = "b7848d12-c684-4caf-bbc0-7197392c702d"
        val extension = File(report, "扩展 with space.js").toPath()
        val command = launchCommand(
            shell, agent, id.takeUnless { newSession && agent == AgentType.CODEX },
            piExtensions = listOf(extension), ideaMcp = endpoint, ideaMcpGuidance = guidance,
            initialPrompt = prompt, pidFile = pidFile.path, additionalMcpServers = listOf(server),
            claudeSettings = settings, newSession = newSession,
        )
        File(report, "command.json").writeText(com.google.gson.Gson().toJson(command))
        val log = File(report, "process.log")
        val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log)
        val environment = builder.environment()
        val pathKey = environment.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "Path"
        environment[pathKey] = bin.absolutePath + File.pathSeparator + environment[pathKey].orEmpty()
        environment["IMUX_ARGV_OUTPUT"] = receivedFile.path
        environment["IMUX_MOBILE_TOKEN"] = "test-only-token-must-not-replace-placeholder"
        val process = builder.start()
        try {
            assertTrue("Process timed out; see $log", process.waitFor(30, TimeUnit.SECONDS))
            assertEquals("Process failed: ${log.readText()}", 0, process.exitValue())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        assertTrue("Receiver did not execute; see $log", receivedFile.isFile)
        val received = JsonParser.parseString(receivedFile.readText()).asJsonArray.map { it.asString }
        val expected = when (agent) {
            AgentType.CLAUDE -> listOf(
                "--mcp-config", claudeIdeaMcpConfig(endpoint, listOf(server)),
                "--append-system-prompt", guidance, "--settings", settings,
                if (newSession) "--session-id" else "--resume", id,
            )
            AgentType.CODEX -> listOf(
                "--no-alt-screen", "-c", "mcp_servers.idea.url=${tomlBasicString(endpoint.url)}",
                "-c", "mcp_servers.idea.http_headers.$IDEA_MCP_PROJECT_HEADER=${tomlBasicString(endpoint.projectPath)}",
                "-c", "mcp_servers.imux_mobile.url=${tomlBasicString(server.url)}",
                "-c", "mcp_servers.imux_mobile.bearer_token_env_var=${tomlBasicString(server.tokenEnvironment)}",
                "-c", "mcp_servers.imux_mobile.tool_timeout_sec=180",
                "-c", "developer_instructions=${tomlBasicString(guidance)}",
            ) + if (newSession) emptyList() else listOf("resume", id)
            AgentType.PI -> listOf("--tui-mode", "regular", "--session-id", id, "-e", extension.toString())
        } + listOf("--", prompt)
        File(report, "expected.json").writeText(com.google.gson.Gson().toJson(expected))
        assertEquals("Arguments changed between JVM, $shell and $launcher; see $report", expected, received)
        assertTrue("Windows tab PID was not recorded", pidFile.isFile && pidFile.readText().trim().toLongOrNull() != null)
        if (agent == AgentType.CLAUDE) {
            val json = JsonParser.parseString(received[received.indexOf("--settings") + 1]).asJsonObject
            assertTrue("Claude must receive hook configuration", json.has("hooks"))
        } else {
            assertFalse("Only Claude accepts --settings", received.contains("--settings"))
        }
    }
}
