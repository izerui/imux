package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.dialectOf
import com.github.izerui.imux.terminal.quote
import com.github.izerui.imux.terminal.shellArgs
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun peerCliCommand(
    shell: String,
    agentType: AgentType,
    projectPath: String,
): List<String> {
    val dialect = dialectOf(shell)
    val cli =
        when (agentType) {
            AgentType.CLAUDE ->
                "claude -p --safe-mode --permission-mode plan --tools default --no-session-persistence"

            AgentType.CODEX ->
                "codex exec --ephemeral --skip-git-repo-check --sandbox read-only --color never " +
                        "-C ${quote(dialect, projectPath)}"

            AgentType.PI ->
                "pi -p --no-session --tools read,grep,find,ls"
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

internal fun runPeerCli(
    command: List<String>,
    cwd: Path,
    prompt: String,
    timeoutSeconds: Long,
    onProcess: (Process?) -> Unit,
): String? {
    var process: Process? = null
    return try {
        val started =
            ProcessBuilder(command)
                .directory(cwd.toFile())
                .start()
        process = started
        onProcess(started)

        val output = StringBuffer()
        val stderr = StringBuffer()
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
                    val buffer = CharArray(2_048)
                    var markerFound = false
                    val markerBuf = StringBuilder()
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        if (markerFound) {
                            val room = MAX_PEER_OUTPUT_CHARS - output.length
                            if (room > 0) output.append(buffer, 0, minOf(room, count))
                        } else if (markerBuf.length < MAX_MARKER_SEARCH_CHARS) {
                            markerBuf.append(buffer, 0, count)
                            val idx = markerBuf.indexOf(PEER_OUTPUT_MARKER)
                            if (idx >= 0) {
                                markerFound = true
                                val afterMarker = markerBuf.substring(idx + PEER_OUTPUT_MARKER.length)
                                if (afterMarker.isNotEmpty()) {
                                    val room = MAX_PEER_OUTPUT_CHARS - output.length
                                    if (room > 0) output.append(afterMarker, 0, minOf(room, afterMarker.length))
                                }
                            }
                        }
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
        finishedReading.await(READ_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        finishedStderr.await(READ_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (started.exitValue() != 0) {
            val detail = stderr.toString().trim().ifEmpty { "exit code ${started.exitValue()}" }
            throw PeerCliException(detail)
        }
        output
            .toString()
            .trim()
            .takeIf(String::isNotEmpty)
    } finally {
        onProcess(null)
        process?.let(::destroyProcessTree)
    }
}

internal class PeerCliException(message: String) : RuntimeException(message)

internal fun destroyProcessTree(process: Process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly)
    process.destroyForcibly()
}

private const val MAX_PEER_OUTPUT_CHARS = 16_000
private const val MAX_MARKER_SEARCH_CHARS = 64_000
private const val MAX_STDERR_CHARS = 4_000
private const val READ_DRAIN_TIMEOUT_SECONDS = 2L
internal const val PEER_OUTPUT_MARKER = "__IMUX_PEER_OUTPUT_BEGIN__"
