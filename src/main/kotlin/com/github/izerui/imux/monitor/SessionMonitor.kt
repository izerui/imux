package com.github.izerui.imux.monitor

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.peer.PeerCoordinator
import com.github.izerui.imux.session.ClaudeRuntimeIndex
import com.github.izerui.imux.session.ClaudeRuntimeSession
import com.github.izerui.imux.session.ClaudeSessionReader
import com.github.izerui.imux.session.LiveTab
import com.github.izerui.imux.session.PiReportEndpointCache
import com.github.izerui.imux.session.PiReportType
import com.github.izerui.imux.session.PiSessionReader
import com.github.izerui.imux.session.PiSessionReport
import com.github.izerui.imux.session.SessionListModel
import com.github.izerui.imux.session.SessionRepository
import com.github.izerui.imux.session.SessionTitleRegenerator
import com.github.izerui.imux.session.driftOf
import com.github.izerui.imux.settings.ImuxSettings
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.TerminalHost
import com.github.izerui.imux.terminal.resolveShell
import com.github.izerui.imux.turn.RunningSessions
import com.github.izerui.imux.turn.RuntimeStatusTracker
import com.github.izerui.imux.turn.TurnNotifier
import com.github.izerui.imux.turn.waitingNotificationWanted
import com.github.izerui.imux.watch.SessionStoreWatcher
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.FrameTitleBuilder
import com.intellij.openapi.wm.impl.ProjectFrameHelper
import com.intellij.util.EventDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.terminal.settings.TerminalLocalOptions
import java.nio.file.Paths
import java.time.Instant
import java.util.EventListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

fun interface SessionMonitorListener : EventListener {
    fun stateChanged()
}

internal fun piReportBelongsToProject(
    report: PiSessionReport,
    projectPath: String,
): Boolean = report.cwd == projectPath

/**
 * 恢复这批标签之前，是否必须**等**上报端点算好。
 *
 * `PiReportEndpoint.current()` 的契约是「绝不等待」：算不出来就返回 null，调用方退回
 * 「不上报」。那对用户手点开的标签是对的（延迟不能落在 EDT 上），但对**启动恢复**
 * 是错的——恢复恰好发生在内置 HTTP 服务还没起来的那几百毫秒里，恢复出来的进程会
 * 拿不到 `IMUX_REPORT_URL` / `IMUX_TOKEN`，于是**一辈子不上报**。
 * 而 IDE 重启后恢复标签正是最常见的场景。
 *
 * **只有 pi 要等。** 它是唯一靠上报认领会话的 agent——claude 有运行态文件，
 * codex 在每个平台上都由 imux 自己观测：macOS 与 Linux 读它持有的会话文件句柄，
 * Windows 读它自己写的运行态 sqlite（见
 * [com.github.izerui.imux.session.CodexRuntimeIndex]）。两者都不需要端点，
 * 为对称白等一次 `BuiltInServerManager.waitForStart()` 只会拖慢启动恢复。
 *
 * [isWindows] 保留但当前不参与判定：这个判据是**按平台分岔过一次**的
 * （Windows 上的 codex 一度靠 hook 上报），形参留在这里，将来再有平台差异时
 * 不必把平台判断重新穿透一遍——同时也钉住「函数体内不读 `SystemInfo`」这条形状，
 * 那是它能被普通 JUnit 4 真调用的前提。平台判断由调用点注入。
 */
@Suppress("UNUSED_PARAMETER")
internal fun restoreNeedsReportEndpoint(
    savedAgentIds: List<String>,
    isWindows: Boolean,
): Boolean = savedAgentIds.any { it == AgentType.PI.cli }

/**
 * 从完成提醒打开会话。
 *
 * **必须是顶层函数**，不能换成捕获 project 或 monitor 的 lambda。系统通知的点击回调
 * 被平台的应用级静态单例攥着（`MacOsNotifications.myCallbacksByActivationId`，
 * 上限 32 条、满了才整体清空），捕获什么就等于把什么钉在那里——捕获 [SessionMonitor]
 * 就是把 [Project] 连同它整条服务链一起留住，项目关掉也回收不了。
 *
 * 顶层函数引用不带任何捕获，回调因此只带走两个字符串，Project 到点击那一刻才现查。
 */
internal fun openSessionFromNotification(
    project: Project,
    sessionId: String,
) {
    SessionMonitor.getInstance(project).openSession(sessionId)
}

/**
 * 原子地执行一次初始化；初始化抛异常时释放占位，允许后续重试。
 *
 * 返回 false 表示此前已经成功启动或当前正由其他调用方启动。
 */
internal inline fun AtomicBoolean.runOnceResetOnFailure(block: () -> Unit): Boolean {
    if (!compareAndSet(false, true)) return false
    try {
        block()
    } catch (error: Throwable) {
        set(false)
        throw error
    }
    return true
}

/**
 * 判定一个会话是否正在运行但没有打开标签页。
 *
 * 三种信号取并集：
 * - [inRunningIds]：TurnWatcher 从会话文件推断的执行态（覆盖 Codex、Pi）
 * - [runtimeOccupied]：Claude 运行态文件里的进程占用（覆盖 Claude 后台 agent）
 *
 * 有标签页时无论是否在跑都返回 false——关标签页能终止进程，删除流程可以走下去。
 */
internal fun isRunningWithoutTab(
    hasTab: Boolean,
    inRunningIds: Boolean,
    runtimeOccupied: Boolean,
): Boolean = !hasTab && (inRunningIds || runtimeOccupied)

enum class DeleteResult {
    /** 删除已受理：标签页已关闭、状态已清理，文件删除在 IO 线程异步进行。 */
    ACCEPTED,
    /** 会话正在运行且没有可关闭的标签页，imux 无法终止进程。 */
    RUNNING_WITHOUT_TAB,
    /** 用户在关闭标签页的确认框中取消了操作。 */
    CLOSE_REJECTED,
}

internal fun dispatchCompletedPeerReview(
    sessionId: String,
    running: Set<String>,
    onTurnCompleted: (String) -> Unit,
) {
    if (sessionId !in running) onTurnCompleted(sessionId)
}

/**
 * 会话状态的唯一持有者：扫描会话库、跟踪运行态、发完成提醒、记未读。
 *
 * **为什么不放在工具窗口里**：工具窗口的内容是懒加载的——平台把 factory 存进
 * [com.intellij.openapi.wm.impl.ToolWindowImpl] 的 contentFactory，等首次展开才调
 * createToolWindowContent。监听逻辑若写在那里，一个从没点开过 imux 面板的项目就
 * 完全静默，一条提醒都不会有，而用户根本意识不到自己漏了。
 *
 * 因此改由 [ImuxStartupActivity] 在项目打开时启动，界面只是它的一个订阅者。
 * 界面可有可无，提醒不能少。
 */
@Service(Service.Level.PROJECT)
class SessionMonitor(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : Disposable {
    private val projectPath = project.basePath ?: System.getProperty("user.home")
    private val repository = SessionRepository.forUserHome()

    val model =
        SessionListModel(
            scan = { repository.scan(projectPath) },
            clock = { Instant.now() },
        )

    private val runtimeIndex =
        ClaudeRuntimeIndex(
            Paths.get(System.getProperty("user.home")).resolve(".claude"),
        )
    private val statusTracker = RuntimeStatusTracker()

    private val unreadTracker =
        UnreadTracker(
            updateOpenTabIcons = ::updateOpenTabIcons,
            updateFrameTitle = ::updateFrameTitle,
            notifyListeners = ::notifyListeners,
        )

    /**
     * 当前活着的 Claude 进程，按会话 id 索引。
     *
     * 只服务于 resume 前的忙碌预检——那里要看 kind 与 status 两个字段。
     * 渲染看的是已经合成好的 [runningIds]：codex 不像 claude 那样有一个按 pid 命名、
     * 直接写着「在不在跑」的运行态文件，它的执行中状态来自会话文件，
     * 两者必须先合并再渲染。
     */
    var runtime: Map<String, ClaudeRuntimeSession> = emptyMap()
        private set

    /** 此刻正在执行的会话 id，见 [RunningSessions]。 */
    @Volatile
    var runningIds: Set<String> = emptySet()
        private set

    private val listenerDispatcher = EventDispatcher.create(SessionMonitorListener::class.java)
    private val started = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)
    private val refreshRequested = AtomicBoolean(false)
    private val checkingCompletedTurns = AtomicBoolean(false)
    private val probing = AtomicBoolean(false)
    private val regeneratingTitles = ConcurrentHashMap.newKeySet<String>()
    private val transcriptGenerations = ConcurrentHashMap<String, AtomicLong>()

    private val titleRegenerator by lazy {
        SessionTitleRegenerator(
            userHome = Paths.get(System.getProperty("user.home")),
            shell =
                resolveShell(
                    System.getenv("SHELL"),
                    isWindows = SystemInfo.isWindows,
                    configuredShell =
                        ApplicationManager.getApplication()?.let {
                            TerminalLocalOptions.getInstance().shellPath
                        },
                ),
        )
    }

    val peerCoordinator =
        PeerCoordinator(
            project = project,
            projectPath = projectPath,
            model = model,
            viewOf = { key -> TerminalHost.getInstance(project).terminalViewOf(key) },
            coroutineScope = coroutineScope,
            shell =
                resolveShell(
                    System.getenv("SHELL"),
                    isWindows = SystemInfo.isWindows,
                    configuredShell =
                        ApplicationManager.getApplication()?.let {
                            TerminalLocalOptions.getInstance().shellPath
                        },
                ),
        )

    private val driftCoordinator =
        DriftCoordinator(
            project = project,
            coroutineScope = coroutineScope,
            projectPath = projectPath,
            model = model,
            runtimeIndex = runtimeIndex,
            terminalHost = { TerminalHost.getInstance(project) },
            notifyListeners = ::notifyListeners,
            clearUnread = { clearUnread(it) },
        )

    init {
        Disposer.register(this, peerCoordinator)
        // 扫描结果、新建 pending、pending 绑定真实 id 都由 model 产出。
        // monitor 必须透传这些变化，否则界面只能等下一次运行态轮询才刷新。
        model.addListener(::notifyListeners)
        ImuxSettings.getInstanceOrNull()?.addLanguageListener(this) {
            refresh()
            updateFrameTitle()
        }
    }

    // applyNewBindings / requestDriftProbe / probeSessionDrift 见 DriftCoordinator

    /**
     * 收到 pi 扩展的会话上报：它换会话了（`/new`、`/resume`、`/fork`），把标签页迁过去。
     *
     * 与 claude、codex 的区别在于**信息怎么来的**，不在于迁移怎么做：那两个靠进程探测
     * （[probeSessionDrift]），pi 三条观测面全断只能由它自己上报。判定与落地完全复用
     * 同一套 [driftOf] + [applyDrifts]，「同一 tabId 报了不同会话就不动」
     * 「标签页已关就不迁」这些规则不必写第二遍。
     *
     * 由 netty 的 EventLoop 线程调用，因此这里只排期、不干活。
     */
    fun onPiSessionReported(report: PiSessionReport) {
        if (project.isDisposed || !piReportBelongsToProject(report, projectPath)) return

        coroutineScope.launch(Dispatchers.EDT) {
            if (project.isDisposed) return@launch
            val host = TerminalHost.getInstance(project)
            when (report.type) {
                PiReportType.SESSION_START -> {
                    val drifts = driftOf(host.openTabsByTabId(), listOf(LiveTab(report.tabId, report.sessionId)))
                    if (drifts.isNotEmpty()) driftCoordinator.applyDrifts(drifts)
                    // session_start 到达时 pi 已经创建会话文件，立即扫描即可拿到 watcher
                    // 所需路径，不必再等会话库 3 秒一轮的全量比对。
                    refresh()
                }

                PiReportType.USER_MESSAGE -> {
                    if (host.openTabsByTabId()[report.tabId] != report.sessionId) return@launch
                    // 用户已经开始新一轮，立即终止上一轮副驾驶；不能等下一次运行态轮询。
                    peerCoordinator.onTurnStarted(report.sessionId)
                    transcriptGenerations
                        .computeIfAbsent(report.sessionId) { AtomicLong() }
                        .incrementAndGet()
                    notifyListeners()
                }

                PiReportType.AGENT_SETTLED -> {
                    if (host.openTabsByTabId()[report.tabId] != report.sessionId) return@launch
                    val reason = report.stopReason ?: return@launch
                    val messageId = report.messageId ?: return@launch
                    host.turnWatcher().reportPiSettled(report.sessionId, reason, messageId)
                    checkCompletedTurns()
                }
            }
        }
    }

    /**
     * 标签页标题跟随会话标题变化。
     *
     * 标题只在绑定那一刻写一次是不够的：那时 CLI 往往还没起好标题，只能先用首条
     * 用户消息，而它常常是注入的系统内容。CLI 事后生成的真标题会随扫描进入 model，
     * 这里把它推给标签页。
     *
     * 放在 monitor 而非界面里：标签页开着但工具窗口没展开是常态，
     * 把它挂在树的重绘上，标题就会在那种情况下停更。
     */
    private fun syncOpenTabTitles() {
        if (project.isDisposed) return
        TerminalHost.getInstance(project).syncTabTitles { key -> model.sessionOf(key)?.title }
    }

    /** 状态有变化时回调，供界面重绘。在 EDT 调用。 */
    fun addListener(
        parentDisposable: Disposable,
        listener: SessionMonitorListener,
    ) {
        listenerDispatcher.addListener(listener, parentDisposable)
    }

    fun transcriptGeneration(sessionId: String): Long = transcriptGenerations[sessionId]?.get() ?: 0L

    fun hasUnread(): Boolean = unreadTracker.hasUnread()

    fun unreadCount(): Int = unreadTracker.unreadCount()

    fun isUnread(sessionId: String): Boolean = unreadTracker.isUnread(sessionId)

    fun markUnread(sessionId: String) = unreadTracker.markUnread(sessionId)

    fun clearUnread(sessionId: String) = unreadTracker.clearUnread(sessionId)

    fun isRegeneratingTitle(sessionId: String): Boolean = sessionId in regeneratingTitles

    /**
     * 用对应 CLI 的一次性模型请求重新生成标题，再写回它自己的会话标题字段。
     *
     * 不复用正在运行的终端：向原会话输入 `/rename` 或提示词会污染对话，还可能撞上
     * 正在执行的一轮。独立的非持久化调用只负责产出一行标题，源会话正文一个字不动。
     */
    fun regenerateTitle(session: AgentSession) {
        if (!regeneratingTitles.add(session.id)) return
        notifyListeners()

        coroutineScope.launch(Dispatchers.IO) {
            val result = runCatching { titleRegenerator.regenerate(session, projectPath) }
            withContext(Dispatchers.EDT) {
                regeneratingTitles.remove(session.id)
                if (project.isDisposed) return@withContext
                if (result.isSuccess) {
                    refresh()
                    titleNotification(
                        ImuxBundle.message("notification.title.regenerated", result.getOrThrow()),
                        NotificationType.INFORMATION,
                    )
                } else {
                    titleNotification(
                        ImuxBundle.message(
                            "notification.title.failed",
                            result.exceptionOrNull()?.message ?: ImuxBundle.message("notification.title.unknown.error"),
                        ),
                        NotificationType.WARNING,
                    )
                }
                notifyListeners()
            }
        }
    }

    private fun titleNotification(
        content: String,
        type: NotificationType,
    ) {
        NotificationGroupManager
            .getInstance()
            .getNotificationGroup(TITLE_NOTIFICATION_GROUP)
            .createNotification(ImuxBundle.message("action.regenerate.title.text"), content, type)
            .notify(project)
    }

    /** 标签关闭后立即撤销运行态，不能让窗口标题再等下一轮文件轮询。 */
    fun sessionClosed(key: String) {
        peerCoordinator.unbind(key)
        peerCoordinator.forgetFeedbackHints(key)
        model.cancelPending(key)
        transcriptGenerations.remove(key)
        if (key !in runningIds) return
        runningIds = runningIds - key
        updateOpenTabIcons(setOf(key))
        updateFrameTitle()
        notifyListeners()
    }

    /**
     * 该会话是否正在运行但没有打开标签页。
     *
     * 覆盖所有三种智能体：Claude 走 [runtime]（后台进程），Codex / Pi 走
     * [runningIds]（TurnWatcher 推断）。两者取并集再排除有标签页的。
     *
     * 没有标签页意味着 imux 无法终止进程——关标签页是唯一的终止路径。
     * 直接删除文件会让进程继续写入已删除的路径。
     */
    fun isRunningWithoutTab(sessionId: String): Boolean =
        isRunningWithoutTab(
            hasTab = TerminalHost.getInstance(project).openTabKeys().contains(sessionId),
            inRunningIds = sessionId in runningIds,
            runtimeOccupied = runtime[sessionId]?.isOccupied == true,
        )

    /**
     * 删除一个会话：关标签页 → 删文件 → 清状态 → 刷新列表。
     *
     * 必须在 EDT 调用。文件删除走 IO 线程，完成后刷新列表。
     *
     * [NonCancellable]：有意脱离 [coroutineScope] 的父 Job。用户确认删除后，
     * 即使项目立即关闭，文件也必须被删掉——否则下次打开项目它又会出现在列表里。
     * `Files.deleteIfExists` 是单次 syscall，项目关闭后继续执行是无害的；
     * EDT 回调仍检查 `project.isDisposed`，跳过已销毁项目的 UI 操作。
     */
    fun deleteSession(session: AgentSession): DeleteResult {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (isRunningWithoutTab(session.id)) return DeleteResult.RUNNING_WITHOUT_TAB
        val host = TerminalHost.getInstance(project)
        if (!host.closeTabBySessionKey(session.id)) return DeleteResult.CLOSE_REJECTED
        sessionClosed(session.id)

        coroutineScope.launch(NonCancellable + Dispatchers.IO) {
            val result = runCatching { java.nio.file.Files.deleteIfExists(session.filePath) }
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                result.onFailure { error ->
                    LOG.warn("删除会话文件失败: ${session.filePath}", error)
                    NotificationGroupManager
                        .getInstance()
                        .getNotificationGroup(TITLE_NOTIFICATION_GROUP)
                        .createNotification(
                            ImuxBundle.message("action.delete.session.text"),
                            ImuxBundle.message(
                                "notification.delete.failed",
                                session.title,
                                error.message ?: ImuxBundle.message("notification.title.unknown.error"),
                            ),
                            NotificationType.WARNING,
                        ).notify(project)
                }
                refresh()
            }
        }
        return DeleteResult.ACCEPTED
    }

    /**
     * 从完成提醒打开会话，并滚到最新输出。
     *
     * 抽成方法而不是就地写 lambda：系统通知的点击回调要跨越平台的静态持有，
     * 详见 [openSessionFromNotification]。
     */
    fun openSession(sessionId: String) {
        model.sessionOf(sessionId)?.let {
            TerminalHost.getInstance(project).openResumeAtBottom(it.agentType, it.id, it.title)
        }
        // 内部会一并撤掉挂着的提醒气泡
        clearUnread(sessionId)
    }

    /** 启动监听。幂等——工具窗口与启动活动都可能调到。 */
    fun start() {
        if (!started.runOnceResetOnFailure(::startWatching)) return
        clearUnreadOnTabSwitch()
        TerminalHost.getInstance(project).addSessionKeyMigratedListener(this) { from, to ->
            peerCoordinator.migrateSessionKey(from, to)
        }
        // 接在 start 而非构造函数里：这两件都是运行时行为，需要 TerminalHost 服务已经可用。
        // 顺序有意义：先把 key 迁到真实 id，标题同步才查得到对应的会话。
        model.addListener {
            driftCoordinator.applyNewBindings()
            // 必须在绑定之后：上一轮迁过去却因当时查不到文件路径而没挂上监控的会话，
            // 以及没迁成的那些，都等这次扫描的结果补齐
            driftCoordinator.retryPendingWatches()
            syncOpenTabTitles()
            driftCoordinator.requestDriftProbe()
        }
        refresh()
    }

    /**
     * 用同一轮会话与运行态快照恢复标签。
     *
     * 不能直接依赖 [model] 和 [runtime] 的缓存：启动时 [refresh] 与运行态轮询都在后台，
     * 恢复若抢在它们前面，会拿不到 TurnWatcher 所需的文件路径，也会绕过后台占用预检。
     */
    suspend fun restoreSavedTabs() {
        val restoration =
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext null
                val host = TerminalHost.getInstance(project)
                val saved = host.beginTabRestoration()
                if (saved.isEmpty()) null else host to saved
            }
        if (restoration == null) {
            PiReportEndpointCache.warmUp()
            return
        }
        val (host, saved) = restoration

        var applied = false
        try {
            // 恢复靠上报认领的标签之前，必须等端点真正可用；warmUp 只启动异步计算，
            // 不保证完成。哪些标签算「靠上报认领」见 restoreNeedsReportEndpoint——
            // 平台判断在这一层做完再传进去，那个函数体内不读 SystemInfo。
            if (restoreNeedsReportEndpoint(saved.map { it.agentId }, SystemInfo.isWindows)) {
                PiReportEndpointCache.awaitReady()
            } else {
                PiReportEndpointCache.warmUp()
            }
            val snapshot =
                withContext(Dispatchers.IO) {
                    val sessions =
                        runCatching { repository.scan(projectPath) }.getOrNull()
                            ?: return@withContext null
                    sessions to runtimeIndex.load(projectPath)
                } ?: return
            val (sessions, runtimeSnapshot) = snapshot
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                runtime = runtimeSnapshot
                model.applyScan(
                    sessions,
                    detectUnclaimedSessions = false,
                )
                host.restoreTabs(
                    saved = saved,
                    sessions = sessions.associateBy { it.id },
                    runtime = runtimeSnapshot,
                )
                applied = true
            }
        } finally {
            withContext(NonCancellable + Dispatchers.EDT) {
                host.finishTabRestoration(persistCurrentTabs = applied)
            }
        }
    }

    /**
     * 后台扫描会话库并应用。
     *
     * 扫描必须离开 EDT：本机实测 620 个 codex 会话文件，一次扫描 60–250ms，
     * 而刷新由轮询、工具窗口状态变化等多处触发，放在 EDT 上就是周期性卡顿。
     *
     * 用 in-flight 标志避免扫描堆积，但扫描期间到达的刷新不能直接丢掉：语言切换不会
     * 改变会话库文件指纹，若恰好撞上旧语言扫描，之后就没有事件再把兜底标题刷新过来。
     * 因此并发请求只合并成一轮补扫，不按调用次数排队。
     */
    fun refresh() {
        if (project.isDisposed) return
        refreshRequested.set(true)
        startRefreshWorker()
    }

    private fun startRefreshWorker() {
        if (!scanning.compareAndSet(false, true)) return
        coroutineScope.launch(Dispatchers.IO) {
            try {
                while (!project.isDisposed && refreshRequested.getAndSet(false)) {
                    val scanned = runCatching { repository.scan(projectPath) }.getOrNull()
                    if (scanned != null && !project.isDisposed) {
                        withContext(Dispatchers.EDT) { model.applyScan(scanned) }
                        // applyScan 的监听器会先完成 key 迁移并补挂 TurnWatcher；随后当场
                        // 重算运行态，避免新 pi 会话再等下一次 1 秒状态节拍才亮起标记。
                        checkCompletedTurns()
                    }
                }
            } finally {
                scanning.set(false)
                // 请求可能在 while 判空后、scanning 复位前到达；这里补接这一窄窗。
                if (refreshRequested.get() && !project.isDisposed) startRefreshWorker()
            }
        }
    }

    /**
     * 刷新运行状态，并检查有无刚完成的会话轮次——后者标记未读并弹通知。
     *
     * 文件与进程状态读取统一调度到服务作用域的 IO dispatcher；结果在 EDT 应用。
     */
    private fun checkCompletedTurns() {
        if (!checkingCompletedTurns.compareAndSet(false, true)) return

        coroutineScope.launch(Dispatchers.IO) {
            try {
                // 上一次探测没成的话在这里续上，见 DriftCoordinator.requestDriftProbe
                driftCoordinator.probeSessionDrift()

                // 迁移与补挂的重试也在这里续一拍。只挂在扫描监听上是不够的：
                // 那条通路要 applyScan 判定「结果有变化」才会通知，而占着目标 key
                // 的重复终端被收拾掉、或用户关掉标签页，都**不改变扫描结果**——
                // 队列会因此停摆。本轮询无条件按拍走，是重试的兜底节奏。
                withContext(Dispatchers.EDT) {
                    if (!project.isDisposed) driftCoordinator.retryPendingWatches()
                }

                val host = TerminalHost.getInstance(project)

                // 按项目过滤：运行态目录是全机器共享的，本机实测常年同时跑着五六个项目的
                // claude。不过滤就会为别的项目的会话弹提醒，而那些会话不在本项目列表里，
                // 连标题都查不到，只能显示一串会话 id。
                val snapshot = runtimeIndex.load(projectPath)
                val watcher = host.turnWatcher()
                // 必须先 poll 再读 workingIds：状态由 poll 推进，顺序反了拿到的是上一轮的
                val outcome = statusTracker.completedSince(snapshot)
                val completed = (outcome.completed + watcher.poll()).distinct()
                // 取全部而不点名某个 agent：凡是靠会话文件推断的都该算进来
                val running = RunningSessions.of(snapshot, watcher.workingIds())

                withContext(Dispatchers.EDT) {
                    val previousRunning = runningIds
                    val runningChanged = previousRunning != running
                    runtime = snapshot
                    runningIds = running
                    // 标签图标与窗口标题读的是同一个信号，一并推
                    if (runningChanged) {
                        updateOpenTabIcons()
                        updateFrameTitle()
                        (running - previousRunning).forEach { sessionId ->
                            peerCoordinator.onTurnStarted(sessionId)
                        }
                    }
                    notifyListeners()

                    completed.forEach { sessionId ->
                        dispatchCompletedPeerReview(sessionId, running, peerCoordinator::onTurnCompleted)

                        val session = model.sessionOf(sessionId)
                        val title =
                            session?.title ?: ImuxBundle.message("session.default", sessionId.take(8))
                        // 两个来源各记各的：claude 走运行态跃迁，codex 走会话文件信号
                        val duration =
                            statusTracker.lastDuration(sessionId) ?: watcher.lastDuration(sessionId)
                        markUnread(sessionId)

                        // 传顶层函数引用而不是 lambda：见 openSessionFromNotification
                        TurnNotifier.notifyCompleted(
                            project,
                            sessionId,
                            title,
                            session?.agentType,
                            duration,
                            ::openSessionFromNotification,
                        )
                    }

                    if (outcome.waiting.isNotEmpty()) {
                        val selected = selectedSessionKeys()
                        outcome.waiting.forEach { waiting ->
                            val session = model.sessionOf(waiting.sessionId)
                            markUnread(waiting.sessionId)

                            // 人正看着它就别弹了，CLI 的选择框已经占在屏幕上
                            if (!waitingNotificationWanted(waiting.sessionId, selected)) return@forEach

                            TurnNotifier.notifyWaiting(
                                project,
                                waiting.sessionId,
                                session?.title
                                    ?: ImuxBundle.message("session.default", waiting.sessionId.take(8)),
                                session?.agentType,
                                waiting.reason,
                                ::openSessionFromNotification,
                            )
                        }
                    }
                }
            } finally {
                checkingCompletedTurns.set(false)
            }
        }
    }

    private fun startWatching() {
        val home = Paths.get(System.getProperty("user.home"))
        val claudeHome = home.resolve(".claude")
        val piHome = home.resolve(".pi")
        val watcher =
            SessionStoreWatcher(
                claudeHome = claudeHome,
                codexHome = home.resolve(".codex"),
                piHome = piHome,
                claudeProjectDirName = ClaudeSessionReader(claudeHome).projectDirName(projectPath),
                piProjectDirName = PiSessionReader(piHome).projectDirName(projectPath),
                onChange = ::refresh,
                onTick = ::checkCompletedTurns,
                // 一个标签页都没开时没人看运行中标记，退回慢节奏
                fastTickWanted = { TerminalHost.getInstance(project).openTabKeys().isNotEmpty() },
            )
        watcher.start()
        Disposer.register(this, watcher)
    }

    /**
     * 此刻处于选中态的会话标签页。分屏时会有多个，故返回集合。
     *
     * 必须在 EDT 调用：`selectedFiles` 读的是编辑器 UI 状态。
     */
    private fun selectedSessionKeys(): Set<String> =
        FileEditorManager
            .getInstance(project)
            .selectedFiles
            .filterIsInstance<AgentTerminalVirtualFile>()
            .map { it.sessionKey }
            .toSet()

    /** 从别处切回该会话的标签页时消除未读。 */
    private fun clearUnreadOnTabSwitch() {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile as? AgentTerminalVirtualFile ?: return
                    clearUnread(file.sessionKey)
                }
            },
        )
    }

    private fun notifyListeners() {
        listenerDispatcher.multicaster.stateChanged()
    }

    /** 运行态或未读状态变化后，让平台重新向 FileIconProvider 查询标签图标。 */
    private fun updateOpenTabIcons(sessionIds: Set<String>? = null) {
        val manager = FileEditorManager.getInstance(project)
        manager.openFiles
            .filterIsInstance<AgentTerminalVirtualFile>()
            .filter { sessionIds == null || it.sessionKey in sessionIds }
            .forEach { file -> manager.updateFilePresentation(file) }
    }

    /**
     * 把未读与运行中数量推到窗口标题上。
     *
     * [AgentFrameTitleBuilder] 只在平台自发重算标题时被调用（切文件、项目状态变化），
     * 未读或运行数刚变化的那一刻没有任何重算触发。而「切文件」本身往往就是清未读的
     * 动作——光靠 builder，标记亮起的那一刻用户根本看不到。
     *
     * 必须走 `updateTitle` 而不是 `setFrameTitle`。后者只调 `IdeFrameImpl.setTitle`
     * 直接盖 AWT 标题，**不写项目名字段**；平台随后在切文件时用「缓存的未装饰项目名 +
     * 新文件名」重拼一遍，装饰就没了。而 `getProjectTitle` 全平台只在项目打开与改名
     * 时被调用，也补不回来。`updateTitle` 是写项目名字段的唯一公开入口，写进去的装饰
     * 才活得过平台重算。
     */
    private fun updateFrameTitle() {
        coroutineScope.launch(Dispatchers.EDT) {
            if (project.isDisposed) return@launch
            val titleBuilder = serviceOrNull<FrameTitleBuilder>() ?: return@launch
            val frameHelper =
                WindowManager.getInstance().getIdeFrame(project) as? ProjectFrameHelper
            frameHelper?.updateTitle(titleBuilder.getProjectTitle(project), project)
        }
    }

    override fun dispose() = Unit

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.logger<SessionMonitor>()
        private const val TITLE_NOTIFICATION_GROUP = "imux.turnCompleted"

        fun getInstance(project: Project): SessionMonitor = project.getService(SessionMonitor::class.java)
    }
}
