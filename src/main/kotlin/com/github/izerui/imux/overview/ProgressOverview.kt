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
) {
    internal val overviewCache = OverviewCache(
        project.basePath?.let { Path.of(it, ".idea", "imux", "overviews") },
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

        // 先记下大小：生成期间会话又有新交互时，结果按过期处理，见 storeOverview
        val snapshotSize = runCatching { Files.size(session.filePath) }.getOrDefault(0L)
        if (snapshotSize == 0L) {
            return storeOverview(overviewCache, sessionKey, OverviewResult(emptyList(), Instant.now()), session.filePath, snapshotSize)
        }

        val isChinese = ImuxBundle.currentLanguage() in setOf(PluginLanguage.SIMPLIFIED_CHINESE, PluginLanguage.TRADITIONAL_CHINESE)
        val prompt = overviewPrompt(isChinese, session.agentType, session.filePath)
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
        val nodes = try {
            generateTrail(
                prompt,
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
        return storeOverview(overviewCache, sessionKey, OverviewResult(nodes, Instant.now()), session.filePath, snapshotSize)
    }

    fun createChildScope(): CoroutineScope = CoroutineScope(SupervisorJob(coroutineScope.coroutineContext[Job]))

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
