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
import com.github.izerui.imux.overview.report.LatestReportWrites
import com.github.izerui.imux.overview.report.REPORT_TEMPLATE
import com.github.izerui.imux.overview.report.ReportFacts
import com.github.izerui.imux.overview.report.ReportSession
import com.github.izerui.imux.overview.report.ReportWrite
import com.github.izerui.imux.overview.report.SessionReport
import com.github.izerui.imux.overview.report.agentReportJson
import com.github.izerui.imux.overview.report.factsFromJson
import com.github.izerui.imux.overview.report.factsJson
import com.github.izerui.imux.overview.report.pageDataJson
import com.github.izerui.imux.overview.report.parseAgentReport
import com.github.izerui.imux.overview.report.renderReportPage
import com.github.izerui.imux.overview.report.reportFromJson
import com.github.izerui.imux.overview.report.reportPrompt
import com.github.izerui.imux.overview.report.transcriptFacts
import com.github.izerui.imux.overview.report.writeReportFile
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
    /** 这一步开始的时间，智能体从会话记录的时间戳抄出；没有或校验不通过为 null。 */
    val time: Instant? = null,
)

data class OverviewResult(
    val nodes: List<OverviewNode>,
    val generatedAt: Instant,
    /** 摘要已生成但未能写入磁盘；仅用于本次展示，不持久化。 */
    val saveFailed: Boolean = false,
    /** 生成期间会话又有新交互，摘要已落后；仅用于本次展示，不持久化。 */
    val stale: Boolean = false,
    /** 会话报告；时间线与 [nodes] 相同。升级前的旧缓存只有时间线，按 [legacy] 补出一份只有时间线的报告。 */
    val report: SessionReport? = null,
    /** 读自升级前的旧缓存（只有时间线）：按过期处理，更新时完整生成。 */
    val legacy: Boolean = false,
)

data class CachedView(
    val result: OverviewResult,
    val stale: Boolean,
)

/** 会话键转成可作文件名的字符：轨迹缓存与报告文件共用，同一会话两边的文件名才对得上。 */
internal fun safeSessionFileName(sessionKey: String): String = sessionKey.replace(Regex("[^a-zA-Z0-9_-]"), "_")

/** 会话文件大小与摘要覆盖到的位置不同，即之后又有新的消息或工具输出。 */
internal fun isStale(cached: CachedOverview, sessionFile: Path): Boolean =
    runCatching { Files.size(sessionFile) }.getOrDefault(-1L) != cached.fileSize

/** 界面看到的缓存：旧格式的缓存也算过期，提示用户更新成完整报告。 */
internal fun cachedView(cached: CachedOverview, sessionFile: Path): CachedView =
    CachedView(cached.result, isStale(cached, sessionFile) || cached.result.legacy)

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

    private fun cacheFile(sessionKey: String): Path = storageDir!!.resolve("${safeSessionFileName(sessionKey)}.json")

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
                    node.time?.let { nodeObj.addProperty("time", it.toEpochMilli()) }
                    nodesArray.add(nodeObj)
                }
                add("nodes", nodesArray)
                cached.result.report?.let { report ->
                    add("report", agentReportJson(report).apply { add("facts", factsJson(report.facts)) })
                }
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
                    time = nodeObj.get("time")?.takeUnless { it.isJsonNull }?.asLong?.let(Instant::ofEpochMilli),
                )
            }
            val lineCount = obj.get("lineCount")?.takeUnless { it.isJsonNull }?.asLong
            val reportObj = obj.get("report") as? com.google.gson.JsonObject
            // facts 写坏了只当没有统计，不让整份缓存作废
            val report = reportObj?.let(::reportFromJson)
                ?.copy(timeline = nodes, facts = factsFromJson(reportObj.get("facts") as? com.google.gson.JsonObject))
            val result =
                when {
                    report != null -> OverviewResult(nodes, generatedAt, report = report)
                    // 升级前的旧缓存只有时间线；报告部分读不出来（写坏了）也一样：显示时间线并提示更新
                    nodes.isNotEmpty() -> OverviewResult(nodes, generatedAt, report = SessionReport(timeline = nodes), legacy = true)
                    else -> OverviewResult(nodes, generatedAt)
                }
            CachedOverview(result, fileSize, lineCount)
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
    facts: (TrailCursor) -> ReportFacts = { ReportFacts() },
): OverviewResult {
    val snapshotSize = runCatching { Files.size(sessionFile) }.getOrDefault(0L)
    val cursor = if (snapshotSize == 0L) TrailCursor(0, 0) else trailCursor(sessionFile, snapshotSize)
    if (cursor.lines == 0L) {
        return storeOverview(cache, sessionKey, OverviewResult(emptyList(), Instant.now()), sessionFile, cursor)
    }
    val cached = cache.get(sessionKey)
    // 截止位置与上次完全相同：要么已是最新，要么只多了一截还没写完的半行。旧结果正好覆盖到这里，
    // 再调智能体只会把同样的内容重读一遍，直接返回旧结果并重新判断是否过期。
    // 截止位置倒退（文件缩短、被改写）则由 reportPrompt 退回完整生成。
    // 旧格式的缓存不走这条：截止位置没变也要完整生成一份报告
    if (cached != null && !cached.result.legacy && cached.lineCount == cursor.lines && cached.fileSize == cursor.bytes) {
        val result = cached.result
        // 上次只是没写进磁盘：重试保存即可，同样不必重新生成
        if (!result.saveFailed) return result.copy(stale = isStale(cached, sessionFile))
        return storeOverview(cache, sessionKey, result.copy(saveFailed = false), sessionFile, cursor)
    }
    val startedAt = Instant.now()
    val report = generateReport(buildPrompt(cached, cursor), runAgent, isCancelled)
    val nodes = sanitizeNodeTimes(report.timeline, startedAt)
    // 时长与改动文件只是附加信息：统计出错（比如会话文件恰好被删）就当没有，不能把智能体写好的报告一起丢掉
    val reportFacts = runCatching { facts(cursor) }.getOrElse { ReportFacts() }
    // 统计期间被取消时，中断让读文件抛错、上面当作「统计不出来」吞掉了；不能接着把这份缺了统计的报告存进缓存，
    // 否则界面已恢复成旧报告，下次从缓存加载却是残缺的新报告，还按最新进度记着、不提示过期
    if (isCancelled()) throw java.util.concurrent.CancellationException("Overview generation cancelled")
    val result = OverviewResult(nodes, Instant.now(), report = report.copy(timeline = nodes, facts = reportFacts))
    return storeOverview(cache, sessionKey, result, sessionFile, cursor)
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
        generate = { key, onProgress -> generateOverview(key, onProgress).also { writeGeneratedReport(key, it, reportWrites, ::writeReport) } },
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
        return cachedView(cached, session.filePath)
    }

    /** 报告文件的写入闸门：按会话只写最新那次，一次只写一个（见 SessionTrailOverlay.showReport）。 */
    internal val reportWrites = LatestReportWrites()

    private val reportTemplate: String by lazy {
        ProgressOverviewService::class.java.getResourceAsStream(REPORT_TEMPLATE)!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }

    /**
     * 把 [result] 渲染成 `.imux/reports/` 下的报告文件；会话标题、智能体、工程名与界面语言都在此刻现取，
     * 改了标题或切换了插件语言，重新调用即生效，不必重跑智能体。内容没变时不重写。
     * 没有报告、工程没有目录或写入失败时返回 null（失败记日志）。
     */
    internal fun writeReport(sessionKey: String, result: OverviewResult): ReportWrite? {
        val report = result.report ?: return null
        val root = project.basePath?.let(Path::of) ?: return null
        val session = SessionMonitor.getInstance(project).model.sessionOf(sessionKey)
        val reportSession = ReportSession(session?.title.orEmpty(), session?.agentType?.displayName.orEmpty(), project.name, result.generatedAt)
        return runCatching {
            writeReportFile(root, sessionKey, renderReportPage(reportTemplate, pageDataJson(report, ImuxBundle.currentLanguage().id, reportSession)))
        }.onFailure { LOG.warn("会话报告写入失败 sessionKey=$sessionKey", it) }.getOrNull()
    }

    /**
     * 单次智能体调用生成时间线：只把会话文件路径交给与会话同类型的智能体，由它自己读取、总结。
     * 调用方式与权限参照副驾驶审查，见 [oneShotAgentInvocation]。
     */
    internal fun generateOverview(sessionKey: String, onProgress: (AgentProgressEvent) -> Unit = {}): OverviewResult {
        val session = SessionMonitor.getInstance(project).model.sessionOf(sessionKey)
            ?: throw IllegalStateException("Session not found: $sessionKey")

        val language = ImuxBundle.currentLanguage()
        val isChinese = language in setOf(PluginLanguage.SIMPLIFIED_CHINESE, PluginLanguage.TRADITIONAL_CHINESE)
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
            // 报告只读会话记录，用不上 IDE：不挂工具、不带引导词，省掉每次生成时一整段无关说明。
            AgentMcpConfig.NONE,
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
                    reportPrompt(isChinese, language, session.agentType, session.filePath, cached, cursor)
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
                facts = { cursor -> transcriptFacts(session.agentType, session.filePath, cursor.bytes, project.basePath?.let(Path::of)) },
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
 * 生成完成就把报告写成文件，不等面板展开：面板收着时生成完、或者从没展开过，`.imux/reports/` 里也是最新的。
 * 经同一个写入闸门 [writes] 领号再写，与面板的写入按会话排队；写好后照常通知各面板（开着的面板据此更新）。
 * 没有报告（会话太短、总结不出东西）时不写：闸门会把「没写成」当作写入失败通知出去，正显示旧报告的面板会误报保存失败。
 */
internal fun writeGeneratedReport(
    sessionKey: String,
    result: OverviewResult,
    writes: LatestReportWrites,
    write: (String, OverviewResult) -> ReportWrite?,
) {
    if (result.report == null) return
    writes.runIfLatest(sessionKey, writes.ticket(sessionKey)) { write(sessionKey, result) }
}

/**
 * 调用智能体并解析报告。回答里解析不出报告（比如只回了一段闲聊）时自动重试一次，
 * 仍然没有才报错；超时、CLI 失败、取消都直接抛出，不重试。
 */
internal fun generateReport(
    prompt: String,
    runAgent: (String) -> String,
    isCancelled: () -> Boolean = { false },
): SessionReport {
    var lastOutput = ""
    repeat(TRAIL_ATTEMPTS) {
        if (isCancelled()) throw java.util.concurrent.CancellationException("Overview generation cancelled")
        val output = runAgent(prompt)
        parseAgentReport(output)?.let { return it }
        lastOutput = output
    }
    throw OverviewParseException(lastOutput)
}

internal const val TRAIL_ATTEMPTS = 2

internal class OverviewParseException(val rawOutput: String) :
    RuntimeException("Overview output could not be parsed into a session report")

/** 时间写坏了不影响节点本身，只是不显示时间。 */
internal fun parseNodeTime(raw: String): Instant? {
    val value = raw.trim().ifEmpty { return null }
    return runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()
}

/**
 * 时间是智能体从记录里抄的，可能抄错：晚于本次生成开始（留 1 分钟误差）、或比前一个有时间的节点还早
 * （顺序倒退）的都去掉，只显示可信的。
 */
internal fun sanitizeNodeTimes(nodes: List<OverviewNode>, startedAt: Instant): List<OverviewNode> {
    val latest = startedAt.plusSeconds(60)
    var previous: Instant? = null
    return nodes.map { node ->
        val time = node.time?.takeIf { !it.isAfter(latest) && (previous == null || !it.isBefore(previous)) }
        if (time != null) previous = time
        if (time == node.time) node else node.copy(time = time)
    }
}
