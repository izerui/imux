package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.IdeaMcpEndpoint
import com.github.izerui.imux.terminal.dialectOf
import com.github.izerui.imux.terminal.ideaMcpCliArgument
import com.github.izerui.imux.terminal.ideaMcpEnvironment
import com.github.izerui.imux.terminal.quote
import com.github.izerui.imux.terminal.shellArgs
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun peerCliCommand(
    shell: String,
    agentType: AgentType,
    projectPath: String,
    ideaMcp: IdeaMcpEndpoint? = null,
    ideaMcpGuidance: String? = null,
    piIdeaMcpExtension: Path? = null,
): List<String> {
    val dialect = dialectOf(shell)
    val ideaMcpArgument = ideaMcpCliArgument(dialect, agentType, ideaMcp, ideaMcpGuidance)
    val cli =
        when (agentType) {
            AgentType.CLAUDE ->
                listOfNotNull(
                    "claude -p --permission-mode bypassPermissions --tools default --no-session-persistence",
                    ideaMcpArgument,
                    "--output-format stream-json --verbose",
                ).joinToString(" ")

            AgentType.CODEX ->
                listOfNotNull(
                    "codex exec --ephemeral --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox --color never",
                    ideaMcpArgument,
                    "--json -C ${quote(dialect, projectPath)}",
                ).joinToString(" ")

            AgentType.PI ->
                buildString {
                    append("pi -p --no-session")
                    piIdeaMcpExtension?.let { append(" -e ${quote(dialect, it.toString())}") }
                    append(" --mode json")
                }
        }
    val marker =
        when (dialect) {
            com.github.izerui.imux.terminal.ShellDialect.POSIX ->
                "printf '%s\\n' ${quote(dialect, PEER_OUTPUT_MARKER)}"

            com.github.izerui.imux.terminal.ShellDialect.POWERSHELL ->
                "Write-Output ${quote(dialect, PEER_OUTPUT_MARKER)}"
        }
    val script = "$marker; $cli"
    return listOf(shell) + shellArgs(dialect) + script
}

internal data class PeerCliInvocation(
    val command: List<String>,
    val environment: Map<String, String>,
)

internal fun buildPeerCliInvocation(
    shell: String,
    agentType: AgentType,
    projectPath: String,
    mcpConfig: PeerMcpConfig,
): PeerCliInvocation = PeerCliInvocation(
    command = peerCliCommand(shell, agentType, projectPath, mcpConfig.endpoint, mcpConfig.guidance, mcpConfig.piExtensionScript),
    environment = ideaMcpEnvironment(agentType, mcpConfig.endpoint, mcpConfig.guidance),
)

internal fun runPeerCli(
    agentType: AgentType,
    command: List<String>,
    cwd: Path,
    prompt: String,
    environment: Map<String, String>,
    timeoutSeconds: Long,
    onProcess: (Process?) -> Unit,
    onProgress: (PeerProgressEvent) -> Unit,
): String? {
    var process: Process? = null
    return try {
        val processBuilder =
            ProcessBuilder(command)
                .directory(cwd.toFile())
        processBuilder.environment().putAll(environment)
        val started = processBuilder.start()
        process = started
        onProcess(started)

        val fallbackOutput = StringBuffer()
        val stderr = StringBuffer()
        val parser = PeerJsonEventParser(agentType, onProgress)
        val finishedWriting = CountDownLatch(1)
        val finishedReading = CountDownLatch(1)
        val finishedStderr = CountDownLatch(1)
        Thread {
            runCatching {
                started.outputStream.bufferedWriter().use { it.write(prompt) }
            }
            finishedWriting.countDown()
        }.apply {
            isDaemon = true
            name = "imux-peer-cli-writer"
        }.start()
        Thread {
            runCatching {
                started.inputStream.bufferedReader().use { reader ->
                    var markerFound = false
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (!markerFound) {
                            markerFound = line.contains(PEER_OUTPUT_MARKER)
                            continue
                        }
                        if (!parser.accept(line)) appendCappedLine(fallbackOutput, line)
                    }
                }
            }
            finishedReading.countDown()
        }.apply {
            isDaemon = true
            name = "imux-peer-cli-reader"
        }.start()
        Thread {
            runCatching {
                started.errorStream.bufferedReader().use { reader ->
                    val buffer = CharArray(2_048)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        val room = MAX_STDERR_CHARS - stderr.length
                        if (room > 0) stderr.append(buffer, 0, minOf(room, count))
                    }
                }
            }
            finishedStderr.countDown()
        }.apply {
            isDaemon = true
            name = "imux-peer-cli-stderr"
        }.start()

        if (!started.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            throw PeerCliException("CLI timed out after ${timeoutSeconds}s")
        }
        finishedWriting.await(READ_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        awaitPeerCliDrain(finishedReading, "output", READ_DRAIN_TIMEOUT_SECONDS)
        finishedStderr.await(READ_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (started.exitValue() != 0) {
            val detail = stderr.toString().trim().ifEmpty { "exit code ${started.exitValue()}" }
            throw PeerCliException(detail)
        }
        parser.finalText()
            ?: fallbackOutput.toString().trim().takeIf(String::isNotEmpty)
    } finally {
        onProcess(null)
        process?.let(::destroyProcessTree)
    }
}

internal fun awaitPeerCliDrain(
    finished: CountDownLatch,
    streamName: String,
    timeoutSeconds: Long,
) {
    if (!finished.await(timeoutSeconds, TimeUnit.SECONDS)) {
        throw PeerCliException("CLI $streamName did not finish draining after ${timeoutSeconds}s")
    }
}

private fun appendCappedLine(output: StringBuffer, line: String) {
    val room = MAX_PEER_OUTPUT_CHARS - output.length
    if (room <= 0) return
    if (output.isNotEmpty()) output.append('\n')
    output.append(line, 0, minOf(line.length, MAX_PEER_OUTPUT_CHARS - output.length))
}

internal class PeerCliException(message: String) : RuntimeException(message)

internal fun destroyProcessTree(process: Process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly)
    process.destroyForcibly()
}

internal const val MAX_PEER_OUTPUT_CHARS = 16_000
private const val MAX_STDERR_CHARS = 4_000
private const val READ_DRAIN_TIMEOUT_SECONDS = 2L
internal const val PEER_OUTPUT_MARKER = "__IMUX_PEER_OUTPUT_BEGIN__"
