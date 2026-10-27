package com.github.izerui.imux.agent

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.AgentHomes
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.IOException
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

private val LOG = logger<AgentConversation>()

/**
 * 与智能体的一段持续对话：每次 [send] 是一轮——发一条用户消息，阻塞到这一轮结束，返回这一轮最后的回答。
 * 同一时间只有一个线程调用 [send]；[close] 可以在任意线程调用，正在进行的 [send] 随即抛出
 * [ConversationClosedException]（或进程被结束导致的其他 [OneShotAgentException]）。
 *
 * 每轮超时抛 [OneShotAgentTimeoutException]；超时或出错后这段对话就不再可用，调用方应当 [close]。
 */
internal interface AgentConversation {
    fun send(message: String, onProgress: (AgentProgressEvent) -> Unit = {}): String

    /** 结束对话：终止进程、清理这段对话留下的文件。可重复调用。返回截止时间内仍未退出的进程树。 */
    fun close(): List<TrackedProcess>
}

internal class ConversationClosedException : OneShotAgentException("The conversation was closed")

/** 按 CLI 选择对话方式；[invocationFor] 的参数是 Codex 续接的会话 ID（首轮为 null，Claude/Pi 只用 null）。 */
internal fun agentConversation(
    agentType: AgentType,
    invocationFor: (codexResumeId: String?) -> OneShotAgentInvocation,
    cwd: Path,
    turnTimeoutSeconds: Long,
): AgentConversation =
    when (agentType) {
        AgentType.CODEX -> CodexConversation(invocationFor, cwd, turnTimeoutSeconds)
        AgentType.CLAUDE, AgentType.PI -> LiveAgentConversation(agentType, invocationFor(null), cwd, turnTimeoutSeconds)
    }

/**
 * Claude、Pi 的常驻进程：第一次 [send] 时启动，之后每轮经 stdin 写一行消息，读 stdout 到这一轮结束。
 *
 * - Claude（`--input-format stream-json`）：写 `{"type":"user","message":{"role":"user","content":[{"type":"text","text":…}]}}`，
 *   读到 `{"type":"result",…}` 为止。
 * - Pi（`--mode rpc`）：写 `{"id":"imux-<轮次>","type":"prompt","message":…}`，读到 `{"type":"agent_settled"}` 为止；
 *   同 id 的 `response` 为 `success:false` 时这一轮失败；扩展发来的请求除了已知不需要回复的（notify、setStatus 等），
 *   一律回复取消——对话框（select/confirm/input/editor）不回复 Pi 会一直等，不认识的请求也按对话框处理。
 * - stderr 只留最后 [MAX_STDERR_CHARS] 个字符：常驻进程跑了很多轮之后报错时，看到的是最近的错误，而不是 shell 启动时的输出。
 */
internal class LiveAgentConversation(
    private val agentType: AgentType,
    private val invocation: OneShotAgentInvocation,
    private val cwd: Path,
    private val turnTimeoutSeconds: Long,
    private val start: ProcessStarter = ProcessStarter.DEFAULT,
    private val claudeHome: () -> Path? = { runCatching { AgentHomes.current().claude }.getOrNull() },
    private val descendantPollMillis: Long = DESCENDANT_POLL_MILLIS,
    private val exitTimeoutNanos: Long = PROCESS_EXIT_TIMEOUT_NANOS,
) : AgentConversation {
    init {
        require(agentType != AgentType.CODEX) { "Codex has no long-lived conversation mode; use CodexConversation" }
    }

    private val lock = Any()
    @Volatile private var closed = false
    private var tracked: TrackedProcess? = null
    private var stdin: Writer? = null
    private val lines = LinkedBlockingQueue<Any>()
    private val stderr = StringBuilder()
    private var turn = 0
    private val writes = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "imux-agent-conversation-writer").apply { isDaemon = true }
    }
    @Volatile private var claudeSessionId: String? = null

    override fun send(message: String, onProgress: (AgentProgressEvent) -> Unit): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(turnTimeoutSeconds)
        val writer = ensureStarted()
        val turnId = ++turn
        val parser =
            AgentJsonEventParser(agentType, onProgress = { event ->
                if (event.kind == AgentProgressKind.TOOL_STARTED || event.kind == AgentProgressKind.TOOL_FINISHED) {
                    synchronized(lock) { tracked }?.captureDescendants()
                }
                onProgress(event)
            })
        writeBeforeDeadline(writer, userLine(message, turnId), deadline)
        while (true) {
            if (closed) throw ConversationClosedException()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw OneShotAgentTimeoutException(turnTimeoutSeconds)
            val line = lines.poll(minOf(remaining, POLL_NANOS), TimeUnit.NANOSECONDS) ?: continue
            if (line === EndOfOutput) {
                if (closed) throw ConversationClosedException()
                throw OneShotAgentException(exitDetail())
            }
            val ended =
                when (agentType) {
                    AgentType.CLAUDE -> acceptClaude(line as String, parser)
                    else -> acceptPi(line as String, parser, turnId, writer, deadline)
                }
            if (ended) return turnAnswer(parser)
        }
    }

    override fun close(): List<TrackedProcess> {
        val process =
            synchronized(lock) {
                closed = true
                tracked.also { tracked = null }
            }
        lines.offer(EndOfOutput)
        writes.shutdownNow()
        val survivors = process?.let { if (destroyAndAwait(it, exitTimeoutNanos)) emptyList() else listOf(it) }.orEmpty()
        if (agentType == AgentType.CLAUDE) {
            claudeSessionId?.let { id -> claudeHome()?.let { removeClaudeScratchDirs(it, id) } }
        }
        return survivors
    }

    private fun ensureStarted(): Writer =
        synchronized(lock) {
            if (closed) throw ConversationClosedException()
            stdin?.let { return it }
            val process = start.start(invocation, cwd)
            val t = TrackedProcess(process)
            t.watchDescendants(descendantPollMillis)
            tracked = t
            daemon("imux-agent-conversation-reader") {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var markerFound = false
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (!markerFound) {
                                markerFound = line.contains(AGENT_OUTPUT_MARKER)
                                continue
                            }
                            lines.put(line)
                        }
                    }
                } catch (_: Throwable) {
                    // 进程被结束时读取会失败：与正常结束一样交给等待中的一轮处理
                } finally {
                    lines.put(EndOfOutput)
                }
            }
            daemon("imux-agent-conversation-stderr") {
                runCatching {
                    process.errorStream.bufferedReader().use { reader ->
                        val buffer = CharArray(2_048)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            synchronized(stderr) { appendTail(stderr, buffer, count) }
                        }
                    }
                }
            }
            process.outputStream.bufferedWriter().also { stdin = it }
        }

    private fun userLine(message: String, turnId: Int): String =
        when (agentType) {
            AgentType.CLAUDE -> claudeUserMessage(message)
            else -> piPromptCommand(message, piTurnId(turnId))
        }

    private fun writeLine(writer: Writer, line: String) {
        try {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        } catch (e: IOException) {
            if (closed) throw ConversationClosedException()
            throw OneShotAgentException("Message was not sent to the CLI: ${e.message}; ${exitDetail()}")
        }
    }

    /** 管道可能被尚未读取 stdin 的 CLI 填满，写入与等待回复共用本轮截止时间。 */
    private fun writeBeforeDeadline(writer: Writer, line: String, deadline: Long) {
        val task = synchronized(lock) {
            if (closed) throw ConversationClosedException()
            writes.submit { writeLine(writer, line) }
        }
        try {
            while (true) {
                if (closed) throw ConversationClosedException()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw OneShotAgentTimeoutException(turnTimeoutSeconds)
                try {
                    task.get(minOf(remaining, POLL_NANOS), TimeUnit.NANOSECONDS)
                    return
                } catch (_: TimeoutException) {
                    // 定期检查关闭状态，不必等到整轮超时。
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            task.cancel(true)
        }
    }

    /** 返回 true 表示这一轮结束。 */
    private fun acceptClaude(line: String, parser: AgentJsonEventParser): Boolean {
        val root = jsonObject(line) ?: return false
        parser.accept(line)
        parser.claudeSessionId?.let { claudeSessionId = it }
        return root.text("type") == "result"
    }

    private fun acceptPi(line: String, parser: AgentJsonEventParser, turnId: Int, writer: Writer, deadline: Long): Boolean {
        val root = jsonObject(line) ?: return false
        when (root.text("type")) {
            "response" -> {
                if (root.text("id") != piTurnId(turnId)) return false
                if (root.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean == false) {
                    throw OneShotAgentException("Pi rejected the prompt: ${root.text("error").orEmpty()}")
                }
                val disposition = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.text("disposition")
                if (disposition == "handled") throw OneShotAgentException("Pi handled the prompt without running the agent")
                return false
            }
            "extension_ui_request" -> {
                val method = root.text("method")
                if (method !in PI_NO_REPLY_METHODS) {
                    if (method !in PI_DIALOG_METHODS) LOG.info("需求澄清：Pi 发来不认识的扩展请求 $method，按对话框回复取消")
                    root.text("id")?.let { writeBeforeDeadline(writer, piCancelDialog(it), deadline) }
                }
                return false
            }
            "agent_settled" -> {
                if (root.get("aborted")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) throw OneShotAgentException("Pi run was aborted")
                return true
            }
        }
        parser.accept(line)
        return false
    }

    private fun turnAnswer(parser: AgentJsonEventParser): String {
        parser.failureDetail()?.let { throw OneShotAgentException(it.ifEmpty { "Agent turn failed" }) }
        val text = parser.finalText() ?: throw OneShotAgentException("No answer from the CLI")
        if (parser.outputOverflowed) {
            throw OneShotAgentException("Agent answer exceeded $MAX_AGENT_OUTPUT_CHARS characters and would be truncated")
        }
        return text
    }

    private fun exitDetail(): String {
        val err = synchronized(stderr) { stderr.toString().trim() }
        val code = synchronized(lock) { tracked }?.process?.let { p -> runCatching { p.exitValue() }.getOrNull() }
        return listOfNotNull("CLI exited", code?.let { "exit code $it" }, err.ifEmpty { null }).joinToString("; ")
    }

    private object EndOfOutput

    private companion object {
        val POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(200)
        val PI_DIALOG_METHODS = setOf("select", "confirm", "input", "editor")

        /** Pi 文档列出的 fire-and-forget 请求：不需要、也不应该回复。 */
        val PI_NO_REPLY_METHODS = setOf("notify", "setStatus", "setWidget", "setTitle", "set_editor_text")

        fun piTurnId(turnId: Int) = "imux-$turnId"
    }
}

/**
 * Codex 没有常驻对话模式：每轮一次 `codex exec`。首轮从 `thread.started` 取得会话 ID，之后以 `resume <id> -` 续接，
 * 消息都经 stdin 写入。会话文件落在 `<codex home>/sessions/` 下（来源 `exec`，imux 的会话列表本就不显示），
 * [close] 时只删除这个会话的 rollout 文件。
 */
internal class CodexConversation(
    private val invocationFor: (codexResumeId: String?) -> OneShotAgentInvocation,
    private val cwd: Path,
    private val turnTimeoutSeconds: Long,
    private val sessionsDir: () -> Path? = { runCatching { AgentHomes.current().codex.resolve("sessions") }.getOrNull() },
    private val start: ProcessStarter = ProcessStarter.DEFAULT,
    private val descendantPollMillis: Long = DESCENDANT_POLL_MILLIS,
    private val exitTimeoutNanos: Long = PROCESS_EXIT_TIMEOUT_NANOS,
) : AgentConversation {
    @Volatile var threadId: String? = null
        private set

    @Volatile private var closed = false
    private val running = ConcurrentLinkedQueue<AtomicReference<TrackedProcess?>>()

    override fun send(message: String, onProgress: (AgentProgressEvent) -> Unit): String {
        if (closed) throw ConversationClosedException()
        val resumeId = threadId
        try {
            return runTracked(running) { ref ->
                runOneShotAgent(
                    AgentType.CODEX,
                    invocationFor(resumeId),
                    cwd,
                    message,
                    turnTimeoutSeconds,
                    ref,
                    // 启动的同时已被关闭：立刻结束，不让它白跑完一整轮
                    onStarted = { if (closed) it.destroyForcibly() },
                    onProgress = onProgress,
                    descendantPollMillis = descendantPollMillis,
                    onCodexThread = { id ->
                        if (threadId == null) threadId = id
                        // 关闭之后才到的会话 ID：close 时还不知道它，文件此刻已经写下，立即删掉
                        if (closed) deleteRollout()
                    },
                    start = start,
                )
            }
        } catch (e: OneShotAgentException) {
            if (closed) throw ConversationClosedException()
            throw e
        } finally {
            // 进程没在截止时间内退出的，引用还在 running 里，close 时再清；关闭发生在这一轮中途时，会话 ID 可能刚刚才拿到
            if (closed) deleteRollout()
        }
    }

    override fun close(): List<TrackedProcess> {
        closed = true
        val left = cleanupProcesses(running, exitTimeoutNanos)
        deleteRollout()
        return left
    }

    private fun deleteRollout() {
        val id = threadId ?: return
        sessionsDir()?.let { deleteCodexRollout(it, id) }
    }
}

/** 删除 `<sessionsRoot>/YYYY/MM/DD/rollout-…-<threadId>.jsonl`；只删这一个会话的文件。threadId 必须是 UUID。 */
internal fun deleteCodexRollout(sessionsRoot: Path, threadId: String): List<Path> {
    if (!UUID_PATTERN.matches(threadId) || !Files.isDirectory(sessionsRoot)) return emptyList()
    val suffix = "-$threadId.jsonl"
    val removed = mutableListOf<Path>()
    runCatching {
        Files.walk(sessionsRoot, 4).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().let { name -> name.startsWith("rollout-") && name.endsWith(suffix) } }
                .toList()
                .forEach { file ->
                    runCatching { Files.deleteIfExists(file) }
                        .onSuccess { if (it) removed.add(file) }
                        .onFailure { LOG.warn("需求澄清：删除 Codex 会话文件失败 $file", it) }
                }
        }
    }.onFailure { LOG.warn("需求澄清：扫描 Codex 会话目录失败", it) }
    return removed
}

/** 追加到 [tail]，只保留最后 [max] 个字符。 */
internal fun appendTail(tail: StringBuilder, buffer: CharArray, count: Int, max: Int = MAX_STDERR_CHARS) {
    tail.appendRange(buffer, 0, count)
    if (tail.length > max) tail.delete(0, tail.length - max)
}

internal fun claudeUserMessage(text: String): String {
    val content = com.google.gson.JsonArray().apply { add(JsonObject().apply { addProperty("type", "text"); addProperty("text", text) }) }
    val message = JsonObject().apply { addProperty("role", "user"); add("content", content) }
    return JsonObject().apply { addProperty("type", "user"); add("message", message) }.toString()
}

internal fun piPromptCommand(text: String, id: String): String =
    JsonObject().apply { addProperty("id", id); addProperty("type", "prompt"); addProperty("message", text) }.toString()

internal fun piCancelDialog(id: String): String =
    JsonObject().apply { addProperty("type", "extension_ui_response"); addProperty("id", id); addProperty("cancelled", true) }.toString()

private fun jsonObject(line: String): JsonObject? =
    runCatching { JsonParser.parseString(line) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.text(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString
