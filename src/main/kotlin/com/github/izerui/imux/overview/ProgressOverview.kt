package com.github.izerui.imux.overview

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.session.MAX_JSON_LINE_CHARS
import com.github.izerui.imux.session.redactNavigatorImagePayloads
import com.github.izerui.imux.session.transcriptMessage
import com.google.gson.JsonParser
import com.github.izerui.imux.terminal.dialectOf
import com.github.izerui.imux.terminal.quote
import com.github.izerui.imux.terminal.shellArgs
import com.github.izerui.imux.settings.PluginLanguage
import com.github.izerui.imux.terminal.resolveShell
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.plugins.terminal.settings.TerminalLocalOptions
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

enum class NodeSymbol {
    MAIN, SIDE, ROLLBACK_START, ROLLBACK_END, CHANGE, ERROR, IN_PROGRESS,
}

data class OverviewNode(
    val symbol: NodeSymbol,
    val text: String,
)

data class OverviewResult(
    val nodes: List<OverviewNode>,
    val generatedAt: Instant,
    /** 摘要已生成但未能写入磁盘；仅用于本次展示，不持久化。 */
    val saveFailed: Boolean = false,
    /** 生成期间会话又有新交互，摘要已落后；仅用于本次展示，不持久化。 */
    val stale: Boolean = false,
)

data class CachedView(
    val result: OverviewResult,
    val stale: Boolean,
)

/** 会话文件大小与生成时快照不同，即生成后又有新的消息或工具输出。 */
internal fun isStale(cached: CachedOverview, sessionFile: Path): Boolean =
    runCatching { Files.size(sessionFile) }.getOrDefault(-1L) != cached.fileSize

internal enum class CacheWrite { STALE, SAVED, SAVE_FAILED }

internal data class CachedOverview(
    val result: OverviewResult,
    val fileSize: Long,
)

internal class OverviewCache(private val storageDir: Path?) {
    private val LOG = logger<OverviewCache>()
    private val memory = ConcurrentHashMap<String, CachedOverview>()

    fun get(sessionKey: String): CachedOverview? {
        memory[sessionKey]?.let { return it }
        if (storageDir == null) return null
        val loaded = loadFromDisk(sessionKey) ?: return null
        memory[sessionKey] = loaded
        return loaded
    }

    fun putIfUnchanged(
        sessionKey: String,
        result: OverviewResult,
        filePath: Path,
        snapshotSize: Long,
    ): CacheWrite {
        val currentSize = runCatching { Files.size(filePath) }.getOrDefault(0L)
        if (currentSize != snapshotSize) return CacheWrite.STALE
        val saved = saveToDisk(sessionKey, CachedOverview(result, snapshotSize))
        // 失败标记只留在内存，本次会话再打开仍提示未落盘；磁盘格式不含此字段
        memory[sessionKey] = CachedOverview(result.copy(saveFailed = !saved), snapshotSize)
        if (saved) return CacheWrite.SAVED
        LOG.warn("会话轨迹：摘要已生成但磁盘保存失败 sessionKey=$sessionKey")
        return CacheWrite.SAVE_FAILED
    }

    fun remove(sessionKey: String) {
        memory.remove(sessionKey)
        if (storageDir != null) {
            runCatching { Files.deleteIfExists(cacheFile(sessionKey)) }
        }
    }

    private fun cacheFile(sessionKey: String): Path {
        val safeName = sessionKey.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return storageDir!!.resolve("$safeName.json")
    }

    /**
     * 用临时文件写入后原子替换，避免写入中途损坏已有摘要。
     * 返回 true 表示写入成功。
     */
    private fun saveToDisk(sessionKey: String, cached: CachedOverview): Boolean {
        if (storageDir == null) return true
        return runCatching<Unit> {
            Files.createDirectories(storageDir)
            val json = com.google.gson.GsonBuilder().create()
            val obj = com.google.gson.JsonObject().apply {
                addProperty("fileSize", cached.fileSize)
                addProperty("generatedAt", cached.result.generatedAt.toEpochMilli())
                val nodesArray = com.google.gson.JsonArray()
                for (node in cached.result.nodes) {
                    val nodeObj = com.google.gson.JsonObject()
                    nodeObj.addProperty("symbol", node.symbol.name)
                    nodeObj.addProperty("text", node.text)
                    nodesArray.add(nodeObj)
                }
                add("nodes", nodesArray)
            }
            val target = cacheFile(sessionKey)
            val tmp = Files.createTempFile(storageDir, "overview-", ".tmp")
            try {
                Files.writeString(tmp, json.toJson(obj))
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                runCatching { Files.deleteIfExists(tmp) }
                throw e
            }
        }.isSuccess
    }

    private fun loadFromDisk(sessionKey: String): CachedOverview? {
        if (storageDir == null) return null
        val file = cacheFile(sessionKey)
        if (!Files.isRegularFile(file)) return null
        return runCatching {
            val text = Files.readString(file)
            val obj = com.google.gson.JsonParser.parseString(text).asJsonObject
            val fileSize = obj.get("fileSize").asLong
            val generatedAt = Instant.ofEpochMilli(obj.get("generatedAt").asLong)
            val nodesArray = obj.getAsJsonArray("nodes")
            val nodes = nodesArray.map { elem ->
                val nodeObj = elem.asJsonObject
                OverviewNode(
                    symbol = NodeSymbol.valueOf(nodeObj.get("symbol").asString),
                    text = nodeObj.get("text").asString,
                )
            }
            CachedOverview(OverviewResult(nodes, generatedAt), fileSize)
        }.getOrNull()
    }
}

/** 写入缓存，并把保存失败、生成期间已过期的状态带回给调用方展示。 */
internal fun storeOverview(
    cache: OverviewCache,
    sessionKey: String,
    result: OverviewResult,
    filePath: Path,
    snapshotSize: Long,
): OverviewResult =
    when (cache.putIfUnchanged(sessionKey, result, filePath, snapshotSize)) {
        CacheWrite.SAVED -> result
        CacheWrite.SAVE_FAILED -> result.copy(saveFailed = true)
        CacheWrite.STALE -> result.copy(stale = true)
    }

@Service(Service.Level.PROJECT)
class ProgressOverviewService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : ProgressOverviewGenerator {
    internal val overviewCache = OverviewCache(
        project.basePath?.let { Path.of(it, ".idea", "imux", "overviews") },
    )

    /** 有缓存就返回；会话有新交互（文件变大）时标记为过期，由用户决定是否重新生成。 */
    fun cachedOverview(sessionKey: String): CachedView? {
        val cached = overviewCache.get(sessionKey) ?: return null
        val session = SessionMonitor.getInstance(project).model.sessionOf(sessionKey) ?: return null
        return CachedView(cached.result, isStale(cached, session.filePath))
    }

    fun generateOverview(sessionKey: String): OverviewResult {
        val model = SessionMonitor.getInstance(project).model
        val session = model.sessionOf(sessionKey)
            ?: throw IllegalStateException("Session not found: $sessionKey")

        val snapshotSize = runCatching { Files.size(session.filePath) }.getOrDefault(0L)
        val entries = readConversationEntries(session.filePath, session.agentType)
        if (entries.isEmpty()) {
            val result = OverviewResult(emptyList(), Instant.now())
            return storeOverview(overviewCache, sessionKey, result, session.filePath, snapshotSize)
        }

        val isChinese = ImuxBundle.currentLanguage() in setOf(PluginLanguage.SIMPLIFIED_CHINESE, PluginLanguage.TRADITIONAL_CHINESE)
        val promptBuilder: (String) -> String = if (isChinese) ::overviewPromptZh else ::overviewPromptEn

        val shell = resolveShell(
            System.getenv("SHELL"),
            isWindows = SystemInfo.isWindows,
            configuredShell = ApplicationManager.getApplication()?.let {
                TerminalLocalOptions.getInstance().shellPath
            },
        )
        val projectPath = project.basePath ?: "."
        val cwd = Path.of(projectPath)
        val command = overviewCliCommand(shell, session.agentType, projectPath)
        val currentProcess = AtomicReference<Process?>()

        val runCli: (String) -> String? = { prompt ->
            runOverviewCli(command, cwd, prompt, CLI_TIMEOUT_SECONDS, currentProcess)
        }

        val isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted }

        val nodes = try {
            generateFromEntries(entries, promptBuilder, runCli, isCancelled = isCancelled)
        } finally {
            val proc = currentProcess.getAndSet(null)
            if (proc != null) {
                proc.descendants().forEach(ProcessHandle::destroyForcibly)
                proc.destroyForcibly()
            }
        }
        val result = OverviewResult(nodes, Instant.now())
        return storeOverview(overviewCache, sessionKey, result, session.filePath, snapshotSize)
    }


    fun createChildScope(): CoroutineScope = CoroutineScope(SupervisorJob(coroutineScope.coroutineContext[Job]))

    companion object {
        fun getInstance(project: Project): ProgressOverviewService = project.service()
        internal val LOG = logger<ProgressOverviewService>()
        internal const val CLI_TIMEOUT_SECONDS = 120L
    }
}

/**
 * 可测试的生成逻辑接口，与 IDE 依赖解耦。
 */
internal interface ProgressOverviewGenerator

internal const val MAX_CLI_OUTPUT_CHARS = 64_000
private const val READ_DRAIN_TIMEOUT_SECONDS = 2L

/**
 * 只读模式的 Agent CLI 命令（不含 prompt，prompt 走 stdin）。
 *
 * 权限与 [com.github.izerui.imux.session.titleGenerationCommand] 相同：
 * Claude `--safe-mode --tools ""`、Codex `--sandbox read-only`、Pi `--no-tools`。
 * 不注入 MCP，不赋予写权限。
 */
internal fun overviewCliCommand(
    shell: String,
    agentType: AgentType,
    projectPath: String,
): List<String> {
    val dialect = dialectOf(shell)
    val script = when (agentType) {
        AgentType.CLAUDE ->
            "claude -p --safe-mode --tools ${quote(dialect, "")} --no-session-persistence"
        AgentType.CODEX ->
            "codex exec --ephemeral --skip-git-repo-check --sandbox read-only --color never " +
                "-C ${quote(dialect, projectPath)}"
        AgentType.PI ->
            "pi -p --no-session --no-tools"
    }
    return listOf(shell) + shellArgs(dialect) + script
}

/**
 * 以只读模式调用 Agent CLI，prompt 通过 stdin 传入，输出从 stdout 读取。
 *
 * 命令行不含 prompt 正文，避免 Windows 32K 命令行上限和 shell 转义问题。
 */
internal fun runOverviewCli(
    command: List<String>,
    cwd: Path,
    prompt: String,
    timeoutSeconds: Long,
    processRef: AtomicReference<Process?>,
): String? {
    var process: Process? = null
    return try {
        val started = ProcessBuilder(command)
            .directory(cwd.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process = started
        processRef.set(started)

        val finishedWriting = java.util.concurrent.CountDownLatch(1)
        val writeError = AtomicReference<Throwable?>()
        Thread {
            try {
                started.outputStream.bufferedWriter().use { it.write(prompt) }
            } catch (e: Throwable) {
                writeError.set(e)
            } finally {
                finishedWriting.countDown()
            }
        }.apply {
            isDaemon = true
            name = "imux-overview-cli-writer"
        }.start()

        val output = StringBuilder()
        val finishedReading = java.util.concurrent.CountDownLatch(1)
        val readError = AtomicReference<Throwable?>()
        val outputOverflowed = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread {
            try {
                started.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        val room = MAX_CLI_OUTPUT_CHARS - output.length
                        // 超限后继续读空管道，避免子进程阻塞；但标记为溢出，不返回截断的前缀
                        if (count > room) outputOverflowed.set(true)
                        if (room > 0) output.appendRange(buffer, 0, minOf(room, count))
                    }
                }
            } catch (e: Throwable) {
                readError.set(e)
            } finally {
                finishedReading.countDown()
            }
        }.apply {
            isDaemon = true
            name = "imux-overview-cli-reader"
        }.start()

        if (!started.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
            throw RuntimeException("Overview CLI timed out after ${timeoutSeconds}s")
        }
        // 正文没有完整送达时，CLI 即便正常退出并输出合法节点，也只是总结了部分会话
        if (!finishedWriting.await(READ_DRAIN_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
            throw OverviewCliException("Session record was not fully sent to the CLI")
        }
        writeError.get()?.let {
            throw OverviewCliException("Session record was not fully sent to the CLI: ${it.message}")
        }
        if (!finishedReading.await(READ_DRAIN_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
            throw OverviewCliException("CLI output did not finish draining")
        }
        readError.get()?.let { throw OverviewCliException("Failed to read CLI output: ${it.message}") }
        if (outputOverflowed.get()) {
            throw OverviewCliException("CLI output exceeded $MAX_CLI_OUTPUT_CHARS characters and would be truncated")
        }
        if (started.exitValue() != 0) {
            throw RuntimeException("Overview CLI failed (exit code ${started.exitValue()})")
        }
        output.toString().trim().takeIf(String::isNotEmpty)
    } finally {
        processRef.set(null)
        process?.descendants()?.forEach(ProcessHandle::destroyForcibly)
        process?.destroyForcibly()
    }
}

internal const val MAX_CHUNK_CHARS = 100_000

/**
 * 从会话文件读取全部对话条目（完整文件，不截断单条消息正文）。
 *
 * 超过 JSON 行上限的记录（通常是带截图的消息），先去掉图片 Base64 载荷
 * 再提取文字，避免连同图片一起发送的需求文字被整条丢弃。
 *
 * `transcriptMessage` 只接受 user/assistant 角色，Codex 的 `function_call_output`
 * 和 Pi 的 `toolResult` 会被跳过。总览需要看到工具执行结果（测试失败、命令输出等），
 * 这里对跳过的行再尝试提取工具结果。
 */
internal fun readConversationEntries(
    filePath: Path,
    agentType: AgentType,
): List<String> {
    if (!Files.isRegularFile(filePath)) return emptyList()
    val entries = mutableListOf<String>()
    Files.newBufferedReader(filePath).useLines { lines ->
        for (line in lines) {
            // 超长行先去掉图片载荷；剩下的若仍超过导航器上限（如大段构建日志），也完整保留交给分块器
            val parseableLine = if (line.length > MAX_JSON_LINE_CHARS) redactNavigatorImagePayloads(line) else line
            if (agentType == AgentType.CLAUDE) {
                val blocks = extractClaudeContentBlocks(parseableLine)
                if (blocks != null) {
                    entries.addAll(blocks)
                    continue
                }
            } else {
                val toolResult = extractToolResult(parseableLine, agentType)
                if (toolResult != null) {
                    entries.add("Tool: $toolResult")
                    continue
                }
            }
            val message = transcriptMessage(parseableLine, agentType, Int.MAX_VALUE, includeToolContent = true, maxLineChars = Int.MAX_VALUE)
            if (message != null && !message.hiddenFromTerminal) {
                val label = message.role.replaceFirstChar(Char::uppercase)
                entries.add("$label: ${message.text}")
            }
        }
    }
    return entries
}

/**
 * Claude 的 user 消息的 `content` 数组里可能交错出现 `tool_result` 和 `text`。
 * 按数组顺序逐块产出 `Tool:` / `User:` 条目，保留原始时间顺序。
 * 不含 tool_result 的普通 user 消息返回 null，交给 [transcriptMessage] 处理。
 */
internal fun extractClaudeContentBlocks(line: String): List<String>? {
    val root = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return null
    val message = root.getAsJsonObject("message") ?: return null
    if (message.get("role")?.asString != "user") return null
    val contentElement = message.get("content")
    if (contentElement == null || !contentElement.isJsonArray) return null
    val content = contentElement.asJsonArray
    val hasToolResult = content.any {
        it.isJsonObject && it.asJsonObject.get("type")?.asString == "tool_result"
    }
    if (!hasToolResult) return null
    val blocks = mutableListOf<String>()
    for (elem in content) {
        if (!elem.isJsonObject) continue
        val obj = elem.asJsonObject
        when (obj.get("type")?.asString) {
            "tool_result" -> {
                val text = extractJsonText(obj.get("content"))
                if (!text.isNullOrBlank()) blocks.add("Tool: $text")
            }
            "text" -> {
                val text = obj.get("text")?.asString
                if (!text.isNullOrBlank()) blocks.add("User: $text")
            }
        }
    }
    return blocks.takeIf(List<String>::isNotEmpty)
}

/**
 * 从会话记录行中提取工具执行结果（完整正文，不截断，交给分块器处理总量）。
 *
 * - Claude: `{"type":"user","message":{"role":"user","content":[{"type":"tool_result",...,"content":"..."}]}}`
 * - Codex: `{"type":"response_item","payload":{"type":"function_call_output","output":"..."}}`
 * - Pi: `{"type":"message","message":{"role":"toolResult","content":[{"type":"text","text":"..."}]}}`
 */
internal fun extractToolResult(line: String, agentType: AgentType): String? {
    val root = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return null
    return when (agentType) {
        AgentType.CLAUDE -> null
        AgentType.CODEX -> {
            if (root.get("type")?.asString != "response_item") return null
            val payload = root.getAsJsonObject("payload") ?: return null
            if (payload.get("type")?.asString != "function_call_output") return null
            payload.get("output")?.asString
        }
        AgentType.PI -> {
            val message = root.getAsJsonObject("message") ?: return null
            if (message.get("role")?.asString != "toolResult") return null
            val content = message.getAsJsonArray("content") ?: return null
            val texts = content.mapNotNull { elem ->
                if (elem.isJsonObject && elem.asJsonObject.get("type")?.asString == "text") {
                    elem.asJsonObject.get("text")?.asString
                } else null
            }
            texts.joinToString("\n").takeIf(String::isNotEmpty)
        }
    }
}

private fun extractJsonText(element: com.google.gson.JsonElement?): String? = when {
    element == null || element.isJsonNull -> null
    element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString
    element.isJsonArray -> element.asJsonArray.mapNotNull { extractJsonText(it) }
        .joinToString("\n").takeIf(String::isNotEmpty)
    element.isJsonObject -> {
        val obj = element.asJsonObject
        if (obj.get("type")?.asString == "text") obj.get("text")?.asString else null
    }
    else -> null
}

/**
 * 把对话条目分块、逐块调 CLI 摘要、合并为统一时间线。
 *
 * 单块时直接返回；多块时先逐块生成中间节点，再做一次合并摘要 CLI 调用，
 * 让 AI 看到各段中间结果后统一标注主线、回滚和最终状态。
 */
internal fun generateFromEntries(
    entries: List<String>,
    promptBuilder: (String) -> String,
    runCli: (String) -> String?,
    maxChunkChars: Int = MAX_CHUNK_CHARS,
    isCancelled: () -> Boolean = { false },
    mergePromptBuilder: (String) -> String = ::mergePrompt,
): List<OverviewNode> {
    val chunks = chunkEntries(entries, maxChunkChars)
    if (chunks.size <= 1) {
        if (isCancelled()) throw java.util.concurrent.CancellationException("Overview generation cancelled")
        val conversation = chunks.firstOrNull()?.joinToString("\n\n") ?: return emptyList()
        val prompt = promptBuilder(conversation)
        val output = runCli(prompt) ?: throw OverviewCliException("No output from CLI")
        val nodes = parseOverviewOutput(output)
        if (nodes.isEmpty()) throw OverviewParseException(output)
        return nodes
    }

    val chunkSummaries = mutableListOf<String>()
    for ((index, chunk) in chunks.withIndex()) {
        if (isCancelled()) throw java.util.concurrent.CancellationException("Overview generation cancelled")
        val conversation = chunk.joinToString("\n\n")
        // 分段说明是给模型的指令，放在记录边界之外，不能混进历史正文
        val prompt = segmentNote(index, chunks.size) + "\n\n" + promptBuilder(conversation)
        val output = runCli(prompt) ?: throw OverviewCliException("No output from CLI")
        // 任一块解析失败就整体失败：否则合并调用可能产出合法节点，把这段遗漏掩盖成完整成功
        val segmentNodes = parseOverviewOutput(output)
        if (segmentNodes.isEmpty()) throw OverviewParseException(output)
        chunkSummaries.add(
            "--- Segment ${index + 1} ---\n" + segmentNodes.joinToString("\n") { "[${it.symbol.name}] ${it.text}" },
        )
    }

    if (isCancelled()) throw java.util.concurrent.CancellationException("Overview generation cancelled")
    val mergeInput = chunkSummaries.joinToString("\n\n")
    val mergeOutput = runCli(mergePromptBuilder(mergeInput)) ?: throw OverviewCliException("No output from merge CLI")
    val nodes = parseOverviewOutput(mergeOutput)
    if (nodes.isEmpty()) throw OverviewParseException(mergeOutput)
    return nodes
}

internal fun segmentNote(index: Int, total: Int): String =
    "[Segment ${index + 1} of $total of one session. Summarize only the milestones inside this segment's record.]"

internal class OverviewCliException(message: String) : RuntimeException(message)

internal class OverviewParseException(val rawOutput: String) :
    RuntimeException("Overview output could not be parsed into timeline nodes")

/**
 * 把条目列表切成连续的 chunk，每个 chunk 的总字符数不超过 [maxChunkChars]。
 * 单条超长条目会被拆成多个子片段，每片独占一个 chunk。
 * 保证原始文本不丢失、不重复。
 */
internal fun chunkEntries(
    entries: List<String>,
    maxChunkChars: Int,
): List<List<String>> {
    if (entries.isEmpty()) return emptyList()
    val chunks = mutableListOf<List<String>>()
    var currentChunk = mutableListOf<String>()
    var currentLength = 0
    for (entry in entries) {
        if (entry.length > maxChunkChars) {
            if (currentChunk.isNotEmpty()) {
                chunks.add(currentChunk)
                currentChunk = mutableListOf()
                currentLength = 0
            }
            var offset = 0
            while (offset < entry.length) {
                val end = minOf(offset + maxChunkChars, entry.length)
                chunks.add(listOf(entry.substring(offset, end)))
                offset = end
            }
            continue
        }
        val entryLength = entry.length + 2
        if (currentLength + entryLength > maxChunkChars && currentChunk.isNotEmpty()) {
            chunks.add(currentChunk)
            currentChunk = mutableListOf()
            currentLength = 0
        }
        currentChunk.add(entry)
        currentLength += entryLength
    }
    if (currentChunk.isNotEmpty()) chunks.add(currentChunk)
    return chunks
}

private val NODE_PATTERN = Regex("""^\[(\w+)]\s*(.+)$""")

internal fun parseOverviewOutput(output: String): List<OverviewNode> {
    return output.lines()
        .mapNotNull { line ->
            val trimmed = line.trim()
            val match = NODE_PATTERN.matchEntire(trimmed) ?: return@mapNotNull null
            val type = match.groupValues[1]
            val text = match.groupValues[2].trim()
            val symbol = when (type.uppercase()) {
                "MAIN" -> NodeSymbol.MAIN
                "SIDE" -> NodeSymbol.SIDE
                "ROLLBACK_START" -> NodeSymbol.ROLLBACK_START
                "ROLLBACK_END" -> NodeSymbol.ROLLBACK_END
                "CHANGE" -> NodeSymbol.CHANGE
                "ERROR" -> NodeSymbol.ERROR
                "IN_PROGRESS" -> NodeSymbol.IN_PROGRESS
                else -> NodeSymbol.MAIN
            }
            OverviewNode(symbol, text)
        }
}
