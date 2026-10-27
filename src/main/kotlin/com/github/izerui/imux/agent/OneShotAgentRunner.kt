package com.github.izerui.imux.agent

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.AgentHomes
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private val LOG = logger<OneShotAgentException>()

internal open class OneShotAgentException(message: String) : RuntimeException(message)

internal class OneShotAgentTimeoutException(val timeoutSeconds: Long) :
    OneShotAgentException("Agent CLI timed out after ${timeoutSeconds}s")

/**
 * 运行一次智能体 CLI：prompt 经 stdin 写入，逐行解析 stdout 的 JSON 事件，返回最终回答。
 *
 * - 进度经 [onProgress] 回调（在读取线程上调用，调用方自行切线程）。
 * - 超时抛 [OneShotAgentTimeoutException]；prompt 未完整送达、输出读取失败、回答超限、
 *   CLI 报告失败或退出码非零，都抛 [OneShotAgentException]，带上 stderr 便于排查。
 * - CLI 存活期间定时扫描、并在每个工具事件时记录子进程，CLI 先退出也能清理工具留下的后台进程。
 * - 返回前销毁并等待整个进程树退出；没在截止时间内退出的，引用留在 [processRef] 里，
 *   调用方可交给 [cleanupProcesses] / [UnexitedProcesses] 兜底。
 * - Claude 即使不保存会话，工具输出过大时仍会写临时文件，结束后按会话 ID 清掉。
 */
internal fun runOneShotAgent(
    agentType: AgentType,
    invocation: OneShotAgentInvocation,
    cwd: Path,
    prompt: String,
    timeoutSeconds: Long,
    processRef: AtomicReference<TrackedProcess?> = AtomicReference(),
    onStarted: (Process) -> Unit = {},
    claudeHome: () -> Path? = { runCatching { AgentHomes.current().claude }.getOrNull() },
    onProgress: (AgentProgressEvent) -> Unit = {},
    descendantPollMillis: Long = DESCENDANT_POLL_MILLIS,
    onCodexThread: (String) -> Unit = {},
    start: ProcessStarter = ProcessStarter.DEFAULT,
): String {
    var tracked: TrackedProcess? = null
    val parser = AgentJsonEventParser(agentType, onCodexThread = onCodexThread, onProgress = { event ->
        // 工具开始/结束时 CLI 还活着，它启动的后台进程此刻一定能从父进程找到：立即记下，
        // 不必等定时扫描（CLI 若紧接着退出，下一次扫描就来不及了）
        if (event.kind == AgentProgressKind.TOOL_STARTED || event.kind == AgentProgressKind.TOOL_FINISHED) {
            tracked?.captureDescendants()
        }
        onProgress(event)
    })
    try {
        val started = start.start(invocation, cwd)
        tracked = TrackedProcess(started)
        // CLI 存活期间就开始记录子进程：它先退出时，工具启动的后台进程已被托管，事后查不到
        tracked.watchDescendants(descendantPollMillis)
        processRef.set(tracked)
        onStarted(started)

        val finishedWriting = CountDownLatch(1)
        val writeError = AtomicReference<Throwable?>()
        daemon("imux-agent-cli-writer") {
            try {
                started.outputStream.bufferedWriter().use { it.write(prompt) }
            } catch (e: Throwable) {
                writeError.set(e)
            } finally {
                finishedWriting.countDown()
            }
        }

        val fallbackOutput = StringBuilder()
        val fallbackOverflowed = AtomicBoolean(false)
        val finishedReading = CountDownLatch(1)
        val readError = AtomicReference<Throwable?>()
        daemon("imux-agent-cli-reader") {
            try {
                started.inputStream.bufferedReader().use { reader ->
                    var markerFound = false
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (!markerFound) {
                            markerFound = line.contains(AGENT_OUTPUT_MARKER)
                            continue
                        }
                        if (!parser.accept(line) && !appendCapped(fallbackOutput, line)) fallbackOverflowed.set(true)
                    }
                }
            } catch (e: Throwable) {
                readError.set(e)
            } finally {
                finishedReading.countDown()
            }
        }

        val stderr = StringBuilder()
        val finishedStderr = CountDownLatch(1)
        daemon("imux-agent-cli-stderr") {
            runCatching {
                started.errorStream.bufferedReader().use { reader ->
                    val buffer = CharArray(2_048)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        synchronized(stderr) {
                            val room = MAX_STDERR_CHARS - stderr.length
                            if (room > 0) stderr.appendRange(buffer, 0, minOf(room, count))
                        }
                    }
                }
            }
            finishedStderr.countDown()
        }

        if (!started.waitFor(timeoutSeconds, TimeUnit.SECONDS)) throw OneShotAgentTimeoutException(timeoutSeconds)
        // prompt 没有完整送达时，CLI 即便正常退出，也只是回答了一部分输入
        if (!finishedWriting.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw OneShotAgentException("Prompt was not fully sent to the CLI")
        }
        writeError.get()?.let { throw OneShotAgentException("Prompt was not fully sent to the CLI: ${it.message}") }
        if (!finishedReading.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw OneShotAgentException("CLI output did not finish draining")
        }
        readError.get()?.let { throw OneShotAgentException("Failed to read CLI output: ${it.message}") }
        finishedStderr.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val stderrText = synchronized(stderr) { stderr.toString().trim() }
        val failure = parser.failureDetail()
        if (failure != null || started.exitValue() != 0) {
            val detail =
                listOfNotNull(stderrText.ifEmpty { null }, failure?.ifEmpty { null })
                    .joinToString("; ")
                    .ifEmpty { "exit code ${started.exitValue()}" }
            throw OneShotAgentException(detail)
        }
        val finalText = parser.finalText()
        if (finalText != null) {
            if (parser.outputOverflowed) {
                throw OneShotAgentException("Agent answer exceeded $MAX_AGENT_OUTPUT_CHARS characters and would be truncated")
            }
            return finalText
        }
        if (fallbackOverflowed.get()) {
            throw OneShotAgentException("CLI output exceeded $MAX_AGENT_OUTPUT_CHARS characters and would be truncated")
        }
        return fallbackOutput.toString().trim().ifEmpty { throw OneShotAgentException("No answer from the CLI") }
    } finally {
        // 先清理再注销：只有确认退出才清空引用，未退出的进程仍可被兜底清理拿到
        tracked?.let { releaseProcess(it, processRef) }
        if (agentType == AgentType.CLAUDE) {
            parser.claudeSessionId?.let { id -> claudeHome()?.let { removeClaudeScratchDirs(it, id) } }
        }
    }
}

/** 启动 CLI 进程；测试注入假进程。 */
internal fun interface ProcessStarter {
    fun start(invocation: OneShotAgentInvocation, cwd: Path): Process

    companion object {
        val DEFAULT = ProcessStarter { invocation, cwd ->
            ProcessBuilder(invocation.command)
                .directory(cwd.toFile())
                .apply { environment().putAll(invocation.environment) }
                .start()
        }
    }
}

/**
 * 删除 Claude 为这次调用写下的临时目录 `<claudeHome>/projects/<任意项目>/<sessionId>/`。
 *
 * 只删存在于某个项目目录下、且旁边没有同名 `<sessionId>.jsonl` 的目录：有会话文件说明它是正常保存的会话，不能动。
 * sessionId 必须是 UUID，防止异常输入拼出别的路径。返回删除的目录。
 */
internal fun removeClaudeScratchDirs(claudeHome: Path, sessionId: String): List<Path> {
    if (!UUID_PATTERN.matches(sessionId)) return emptyList()
    val projects = claudeHome.resolve("projects")
    if (!Files.isDirectory(projects)) return emptyList()
    val removed = mutableListOf<Path>()
    runCatching {
        Files.list(projects).use { dirs ->
            dirs.filter(Files::isDirectory).forEach { projectDir ->
                val scratch = projectDir.resolve(sessionId)
                if (Files.isDirectory(scratch) && !Files.exists(projectDir.resolve("$sessionId.jsonl"))) {
                    runCatching {
                        scratch.toFile().deleteRecursively().also { if (it) removed.add(scratch) }
                    }.onFailure { LOG.warn("单次智能体调用：清理 Claude 临时目录失败 $scratch", it) }
                }
            }
        }
    }.onFailure { LOG.warn("单次智能体调用：扫描 Claude 项目目录失败", it) }
    return removed
}

/** 追加一行；超出上限返回 false（调用方据此报错，而不是返回截断的内容）。 */
private fun appendCapped(output: StringBuilder, line: String): Boolean {
    val needed = line.length + if (output.isNotEmpty()) 1 else 0
    if (output.length + needed > MAX_AGENT_OUTPUT_CHARS) return false
    if (output.isNotEmpty()) output.append('\n')
    output.append(line)
    return true
}

internal fun daemon(name: String, body: () -> Unit) {
    Thread(body, name).apply { isDaemon = true }.start()
}

internal val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
internal const val MAX_STDERR_CHARS = 4_000
private const val DRAIN_TIMEOUT_SECONDS = 2L
