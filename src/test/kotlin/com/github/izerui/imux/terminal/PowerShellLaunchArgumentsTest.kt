package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Run with IMUX_TEST_PWSH set when pwsh is not on PATH. Uses real native argument passing. */
@RunWith(Parameterized::class)
class PowerShellLaunchArgumentsTest(
    private val mode: String,
    private val agent: AgentType,
    private val newSession: Boolean,
    private val launcher: String,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}-{1}-new={2}-{3}")
        fun cases(): List<Array<Any>> =
            listOf("Legacy", "Standard", "Windows").flatMap { mode ->
                AgentType.entries.flatMap { agent ->
                    listOf(true, false).flatMap { fresh ->
                        val launchers = if (System.getProperty("os.name").startsWith("Windows")) {
                            listOf("ps1")
                        } else {
                            listOf("ps1", "native", "cmd-suffix")
                        }
                        launchers.map { arrayOf<Any>(mode, agent, fresh, it) }
                    }
                }
            }
    }

    @Test
    fun `启动参数经PowerShell转交原生进程后保持原样`() {
        val shell = System.getenv("IMUX_TEST_PWSH") ?: "pwsh"
        val version = runCatching {
            ProcessBuilder(shell, "-NoProfile", "-Command", "\$PSVersionTable.PSVersion.ToString()")
                .redirectErrorStream(true).start().let { process ->
                    try {
                        check(process.waitFor(15, TimeUnit.SECONDS))
                        check(process.exitValue() == 0)
                        process.inputStream.bufferedReader().use { it.readText() }.trim()
                    } finally {
                        if (process.isAlive) process.destroyForcibly()
                    }
                }
        }
        if (System.getenv("IMUX_TEST_PWSH") != null) version.getOrThrow()
        assumeTrue("Requires PowerShell 7.3+; set IMUX_TEST_PWSH", version.isSuccess)
        val parts = version.getOrThrow().split('.').mapNotNull(String::toIntOrNull)
        assumeTrue("Requires PowerShell 7.3+", parts[0] > 7 || parts[0] == 7 && parts[1] >= 3)

        val directory = Files.createTempDirectory("imux native arguments ")
        try {
            val receiver = directory.resolve("receiver.cjs")
            val receiverSource = """
                if (process.argv.includes('--help')) {
                  console.log('  --no-alt-screen\n  --tui-mode <mode>');
                } else {
                  console.log(JSON.stringify(process.argv.slice(2)));
                }
            """.trimIndent()
            Files.writeString(receiver, receiverSource)
            val shim = if (launcher == "ps1") {
                directory.resolve("${agent.cli}.ps1").also {
                    Files.writeString(it, "& node ${quote(ShellDialect.POWERSHELL, receiver.toString())} @args")
                }
            } else {
                // On Unix this exercises PowerShell's .cmd fallback, not Windows cmd.exe parsing.
                directory.resolve(agent.cli + if (launcher == "cmd-suffix") ".cmd" else "").also {
                    Files.writeString(it, "#!/usr/bin/env node\n$receiverSource")
                    check(it.toFile().setExecutable(true))
                }
            }
            val wrapper = "Set-Alias ${agent.cli} ${quote(ShellDialect.POWERSHELL, shim.toString())}"
            val endpoint = IdeaMcpEndpoint(64342, "C:\\项目 空格\\O'Brien\\app")
            val server = TerminalMcpServer("imux_mobile", "http://127.0.0.1:63342/imux/mobile-mcp", "IMUX_MOBILE_TOKEN")
            val settings = claudeHookSettings("http://127.0.0.1:63342/imux/claude-hook", "IMUX_MOBILE_TOKEN")
            val guidance = "保留 \"双引号\"、O'Brien、\$IMUX_MOBILE_TOKEN\nC:\\目录 with space\\"
            val prompt = "检查 \\\"JSON\\\" 与 O'Brien；不要执行 \$(Write-Output unexpected)\n第二行"
            val extension = directory.resolve("扩展 with space.js")
            val id = "b7848d12-c684-4caf-bbc0-7197392c702d"
            val command = launchCommand(
                shell, agent, id.takeUnless { newSession && agent == AgentType.CODEX },
                piExtensions = listOf(extension), ideaMcp = endpoint, ideaMcpGuidance = guidance,
                initialPrompt = prompt, additionalMcpServers = listOf(server),
                claudeSettings = settings, newSession = newSession,
            ).toMutableList()
            command[command.lastIndex] = "\$PSNativeCommandArgumentPassing = '$mode'; $wrapper; ${command.last()}"
            val log = directory.resolve("process.log").toFile()
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
            try {
                assertTrue("Native receiver timed out", process.waitFor(30, TimeUnit.SECONDS))
                assertEquals(log.readText(), 0, process.exitValue())
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            val received = JsonParser.parseString(log.readText().trim()).asJsonArray.map { it.asString }
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
            assertEquals("Native argv changed in $mode mode", expected, received)
            if (agent == AgentType.CLAUDE) {
                assertTrue(JsonParser.parseString(received[received.indexOf("--settings") + 1]).asJsonObject.has("hooks"))
            } else {
                assertFalse(received.contains("--settings"))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
