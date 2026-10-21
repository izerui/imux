package com.github.izerui.imux.overview

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.agent.AgentMcpConfig
import com.github.izerui.imux.agent.AgentProgressEvent
import com.github.izerui.imux.agent.TrackedProcess
import com.github.izerui.imux.agent.UnexitedProcesses
import com.github.izerui.imux.agent.cleanupProcesses
import com.github.izerui.imux.agent.oneShotAgentInvocation
import com.github.izerui.imux.agent.runOneShotAgent
import com.github.izerui.imux.agent.runTracked
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.settings.PluginLanguage
import com.github.izerui.imux.terminal.resolveShell
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.plugins.terminal.settings.TerminalLocalOptions
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
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

/** 会话文件大小与摘要覆盖到的位置不同，即之后又有新的消息或工具输出。 */
internal fun isStale(cached: CachedOverview, sessionFile: Path): Boolean =
    runCatching { Files.size(sessionFile) }.getOrDefault(-1L) != cached.fileSize

internal data class CachedOverview(
    val result: OverviewResult,
    /** 摘要覆盖到的字节位置（截止行末尾）；文件比它大就是过期。 */
    val fileSize: Long,
    /** 摘要覆盖到的行数，增量生成从下一行读起；升级前的旧缓存没有，为 null。 */
    val lineCount: Long? = null,
)

/**
 * 本次生成的截止位置：快照之内最后一个完整行（以换行结尾）的行号，及该行末尾的字节位置。
 *
 * 提示词明确要求智能体只读到 [lines] 行，所以生成结果恰好覆盖到这里。生成期间会话继续追加的
 * 内容、快照时还没写完的半行都在截止之后，留给下一次增量。
 */
internal data class TrailCursor(val lines: Long, val bytes: Long)

internal fun trailCursor(file: Path, limitBytes: Long): TrailCursor {
    var lines = 0L
    var lastLineEnd = 0L
    var offset = 0L
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (offset < limitBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limitBytes - offset).toInt())
            if (read < 0) break
            for (i in 0 until read) {
                if (buffer[i] == '\n'.code.toByte()) {
                    lines++
                    lastLineEnd = offset + i + 1
                }
            }
            offset += read
        }
    }
    return TrailCursor(lines, lastLineEnd)
}

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

    /**
     * 保存摘要及它覆盖到的位置，返回是否已写入磁盘。不在这里判断过期：生成期间会话继续追加时
     * 也照样保存，否则这次已经总结过的部分下次还要重读；过期由 [isStale] 按 [fileSize] 判断。
     */
    fun put(
        sessionKey: String,
        result: OverviewResult,
        fileSize: Long,
        lineCount: Long? = null,
    ): Boolean {
        val saved = saveToDisk(sessionKey, CachedOverview(result, fileSize, lineCount))
        // 失败标记只留在内存，本次会话再打开仍提示未落盘；磁盘格式不含此字段
        memory[sessionKey] = CachedOverview(result.copy(saveFailed = !saved), fileSize, lineCount)
        if (!saved) LOG.warn("会话轨迹：摘要已生成但磁盘保存失败 sessionKey=$sessionKey")
        return saved
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
                cached.lineCount?.let { addProperty("lineCount", it) }
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
            val lineCount = obj.get("lineCount")?.takeUnless { it.isJsonNull }?.asLong
            CachedOverview(OverviewResult(nodes, generatedAt), fileSize, lineCount)
        }.getOrNull()
    }
}

/** 按截止位置写入缓存，并把保存失败、截止之后又有新内容（已过期）的状态带回给调用方展示。 */
internal fun storeOverview(
    cache: OverviewCache,
    sessionKey: String,
    result: OverviewResult,
    filePath: Path,
    cursor: TrailCursor,
): OverviewResult {
    val saved = cache.put(sessionKey, result, cursor.bytes, cursor.lines)
    val stale = runCatching { Files.size(filePath) }.getOrDefault(-1L) != cursor.bytes
    return result.copy(saveFailed = !saved, stale = stale)
}

/**
 * 一次生成：定下截止位置 → 选完整或增量提示词 → 调智能体 → 按截止位置保存。
 *
 * 与 CLI、IDE 服务无关的部分都在这里，生成期间会话继续追加的情形可以直接测试。
 */
internal fun runTrailGeneration(
    cache: OverviewCache,
    sessionKey: String,
    sessionFile: Path,
    buildPrompt: (cached: CachedOverview?, cursor: TrailCursor) -> String,
    runAgent: (String) -> String,
    isCancelled: () -> Boolean = { false },
): OverviewResult {
    val snapshotSize = runCatching { Files.size(sessionFile) }.getOrDefault(0L)
    val cursor = if (snapshotSize == 0L) TrailCursor(0, 0) else trailCursor(sessionFile, snapshotSize)
    if (cursor.lines == 0L) {
        return storeOverview(cache, sessionKey, OverviewResult(emptyList(), Instant.now()), sessionFile, cursor)
    }
    val cached = cache.get(sessionKey)
    // 截止位置与上次完全相同：要么已是最新，要么只多了一截还没写完的半行。旧结果正好覆盖到这里，
    // 再调智能体只会把同样的内容重读一遍，直接返回旧结果并重新判断是否过期。
    // 截止位置倒退（文件缩短、被改写）则由 trailPrompt 退回完整生成。
    if (cached != null && cached.lineCount == cursor.lines && cached.fileSize == cursor.bytes) {
        val result = cached.result
        // 上次只是没写进磁盘：重试保存即可，同样不必重新生成
        if (!result.saveFailed) return result.copy(stale = isStale(cached, sessionFile))
        return storeOverview(cache, sessionKey, result.copy(saveFailed = false), sessionFile, cursor)
    }
    val nodes = generateTrail(buildPrompt(cached, cursor), runAgent, isCancelled)
    return storeOverview(cache, sessionKey, OverviewResult(nodes, Instant.now()), sessionFile, cursor)
}

@Service(Service.Level.PROJECT)
class ProgressOverviewService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    internal val overviewCache = OverviewCache(
        project.basePath?.let { Path.of(it, ".idea", "imux", "overviews") },
    )

    /** 各会话标签页右上角面板共享的轨迹状态与生成任务。 */
    internal val trailStore = TrailStateStore(
        scope = coroutineScope,
        loadCached = ::cachedOverview,
        isAvailable = { SessionMonitor.getInstance(project).model.sessionOf(it) != null },
        generate = { key, onProgress -> generateOverview(key, onProgress) },
        errorText = ::overviewErrorText,
        onEdt = { action -> ApplicationManager.getApplication().invokeLater(action, ModalityState.any()) },
    )

    /** 清理截止时仍未退出的 CLI 进程树；下次生成前再尝试销毁。 */
    private val unexited = UnexitedProcesses()

    internal fun unexitedProcesses(): List<TrackedProcess> = unexited.snapshot()

    /** 有缓存就返回；会话有新交互（文件变大）时标记为过期，由用户决定是否重新生成。 */
    fun cachedOverview(sessionKey: String): CachedView? {
        val cached = overviewCache.get(sessionKey) ?: return null
        val session = SessionMonitor.getInstance(project).model.sessionOf(sessionKey) ?: return null
        return CachedView(cached.result, isStale(cached, session.filePath))
    }

    /**
     * 单次智能体调用生成时间线：只把会话文件路径交给与会话同类型的智能体，由它自己读取、总结。
     * 调用方式与权限参照副驾驶审查，见 [oneShotAgentInvocation]。
     */
    internal fun generateOverview(sessionKey: String, onProgress: (AgentProgressEvent) -> Unit = {}): OverviewResult {
        val session = SessionMonitor.getInstance(project).model.sessionOf(sessionKey)
            ?: throw IllegalStateException("Session not found: $sessionKey")

        val isChinese = ImuxBundle.currentLanguage() in setOf(PluginLanguage.SIMPLIFIED_CHINESE, PluginLanguage.TRADITIONAL_CHINESE)
        val shell = resolveShell(
            System.getenv("SHELL"),
            isWindows = SystemInfo.isWindows,
            configuredShell = ApplicationManager.getApplication()?.let {
                TerminalLocalOptions.getInstance().shellPath
            },
        )
        val projectPath = project.basePath ?: "."
        val invocation = oneShotAgentInvocation(
            shell,
            session.agentType,
            projectPath,
            AgentMcpConfig.configured(project, projectPath, session.agentType),
        )

        unexited.retry()
        // 每次调用登记进程；进程确认退出才注销，结束时兜底销毁仍登记的进程
        val running = ConcurrentLinkedQueue<AtomicReference<TrackedProcess?>>()
        return try {
            runTrailGeneration(
                overviewCache,
                sessionKey,
                session.filePath,
                buildPrompt = { cached, cursor ->
                    trailPrompt(isChinese, session.agentType, session.filePath, cached, cursor)
                },
                runAgent = { text ->
                    runTracked(running) { processRef ->
                        runOneShotAgent(
                            session.agentType,
                            invocation,
                            Path.of(projectPath),
                            text,
                            AGENT_TIMEOUT_SECONDS,
                            processRef,
                            onProgress = onProgress,
                        )
                    }
                },
                isCancelled = { Thread.currentThread().isInterrupted },
            )
        } finally {
            unexited.addAll(cleanupProcesses(running))
        }
    }

    companion object {
        fun getInstance(project: Project): ProgressOverviewService = project.service()
        internal val LOG = logger<ProgressOverviewService>()

        /** 长会话需要智能体分多次读取，实测 6000 万字节的会话约 2 分钟。 */
        internal const val AGENT_TIMEOUT_SECONDS = 600L
    }
}

/**
 * 调用智能体并解析时间线。回答里解析不出节点（比如只回了一段闲聊）时自动重试一次，
 * 仍然没有才报错；超时、CLI 失败、取消都直接抛出，不重试。
 */
internal fun generateTrail(
    prompt: String,
    runAgent: (String) -> String,
    isCancelled: () -> Boolean = { false },
): List<OverviewNode> {
    var lastOutput = ""
    repeat(TRAIL_ATTEMPTS) {
        if (isCancelled()) throw java.util.concurrent.CancellationException("Overview generation cancelled")
        val output = runAgent(prompt)
        val nodes = parseOverviewOutput(output)
        if (nodes.isNotEmpty()) return nodes
        lastOutput = output
    }
    throw OverviewParseException(lastOutput)
}

internal const val TRAIL_ATTEMPTS = 2

internal class OverviewParseException(val rawOutput: String) :
    RuntimeException("Overview output could not be parsed into timeline nodes")

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
