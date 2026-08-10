package com.github.izerui.imux.monitor

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.ClaudeRuntimeIndex
import com.github.izerui.imux.session.ClaudeRuntimeSession
import com.github.izerui.imux.session.ClaudeSessionReader
import com.github.izerui.imux.session.KeyDrift
import com.github.izerui.imux.session.LiveSessionProbe
import com.github.izerui.imux.session.SessionListModel
import com.github.izerui.imux.session.SessionRepository
import com.github.izerui.imux.session.codexPids
import com.github.izerui.imux.session.driftOf
import com.github.izerui.imux.session.interactivePids
import com.github.izerui.imux.session.stillApplicable
import com.github.izerui.imux.session.readHeldRollouts
import com.github.izerui.imux.session.readTabId
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.TerminalHost
import com.github.izerui.imux.turn.RunningSessions
import com.github.izerui.imux.turn.RuntimeStatusTracker
import com.github.izerui.imux.turn.TurnNotifier
import com.github.izerui.imux.turn.waitingNotificationWanted
import com.github.izerui.imux.watch.SessionStoreWatcher
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.FrameTitleBuilder
import com.intellij.util.EventDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Paths
import java.time.Instant
import java.util.EventListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

fun interface SessionMonitorListener : EventListener {
    fun stateChanged()
}

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
internal fun openSessionFromNotification(project: Project, sessionId: String) {
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

    val model = SessionListModel(
        scan = { repository.scan(projectPath) },
        clock = { Instant.now() },
    )

    private val runtimeIndex = ClaudeRuntimeIndex(
        Paths.get(System.getProperty("user.home")).resolve(".claude"),
    )
    private val statusTracker = RuntimeStatusTracker()

    /** 轮次刚完成、用户还没回来看的会话 id。 */
    private val unread = ConcurrentHashMap.newKeySet<String>()

    /**
     * 当前活着的 Claude 进程，按会话 id 索引。
     *
     * 只服务于 resume 前的忙碌预检——那里要看 kind 与 status 两个字段。
     * 渲染看的是已经合成好的 [runningIds]：codex 没有运行态文件，
     * 它的执行中状态来自会话文件，两者必须先合并再渲染。
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
    private val checkingCompletedTurns = AtomicBoolean(false)
    private val probing = AtomicBoolean(false)

    /** 还允许尝试几次会话漂移探测，见 [requestDriftProbe]。 */
    private val driftProbeAttempts = AtomicInteger(0)

    /** 每次出现新的无主会话都会递增，用于区分探测期间到达的新触发器。 */
    private val driftProbeGeneration = AtomicInteger(0)

    init {
        // 扫描结果、新建 pending、pending 绑定真实 id 都由 model 产出。
        // monitor 必须透传这些变化，否则界面只能等下一次运行态轮询才刷新。
        model.addListener(::notifyListeners)
    }

    /**
     * 把刚发生的绑定告知终端宿主：新建会话的终端原本记在合成 key 下，
     * 拿到真实 id 后必须迁过去。
     *
     * 不做的话终端会一直挂在 pending key 下：运行中标识查不到，再点该会话又会以
     * 真实 id 重开一个 `--resume` 终端，与仍在运行的原终端抢同一个会话，
     * CLI 报「currently running as a background agent」。
     *
     * [SessionListModel.drainNewBindings] 是破坏性读取，取走即清空，所以只能有一个
     * 消费者，且这个消费者必须一直活着。原先它挂在会话树的重绘里，而树是工具窗口
     * 懒加载出来、可被销毁的东西——树没接住，这笔迁移就永远丢了。
     */
    private fun applyNewBindings() {
        if (project.isDisposed) return
        val bindings = model.drainNewBindings()
        if (bindings.isEmpty()) return

        val host = TerminalHost.getInstance(project)
        bindings.forEach { (pendingKey, sessionId) ->
            val session = model.sessionOf(sessionId)
            val title = session?.title ?: "会话 ${sessionId.take(8)}"
            // 迁移没成（目标已被别的终端占着）就别接着挂监控：那会把轮次盯到一个
            // 不归这个终端管的会话上
            if (!host.rebindKey(pendingKey, sessionId, title)) return@forEach
            // 新建的会话直到落盘才有文件路径，绑定这一刻才能纳入监控
            session?.let { host.startWatchingTurn(sessionId, it.agentType, it.filePath) }
        }
    }

    /**
     * 无主新会话出现时登记一次探测意图。
     *
     * **不能直接把它当成一次性的触发器**：探测未必一次就成——运行态文件可能还没更新、
     * `lsof` 可能超时、目标 key 可能被用户刚开的终端占着。而 `/clear` 只产生**一次**
     * 无主会话，触发器一旦消费掉就没有下一次了，终端会永久停在旧 id 上，
     * 且失败是静默的。所以登记的是**允许重试的次数**，由后续轮询推进。
     */
    private fun requestDriftProbe() {
        if (model.drainUnclaimedSessions().isEmpty()) return
        driftProbeGeneration.incrementAndGet()
        driftProbeAttempts.set(DRIFT_PROBE_ATTEMPTS)
        probeSessionDrift()
    }

    /**
     * 发现有终端在 `/clear`、`/new` 之后换了会话 id，把它迁到新 id 下。
     *
     * **为什么不能靠 pending 机制兜住**：pending 只在插件自己发起「新建」时登记。
     * 用户在终端里敲 `/clear`，CLI 换一个会话 id 而进程不变，插件这边没有任何人在
     * 等它——那个新会话就成了无主的，而终端一直记在旧 id 下。后果是标题停更、
     * 未读清不掉、轮次监控盯着一个不再增长的文件，再点新会话还会以真实 id 重开一个
     * `--resume` 终端，与仍在运行的原进程抢同一个会话。
     *
     * **只在登记过探测意图时才跑**，不是每轮都跑：codex 那侧要 `lsof`，成本不低，
     * 而无主新会话出现的那一刻正是换 id 发生的时刻，没必要平时空转。
     */
    private fun probeSessionDrift() {
        if (project.isDisposed) return
        if (driftProbeAttempts.get() <= 0) return
        // 正在探测就直接走开，重试次数原封不动留着——不能在这之前消费掉它
        if (!probing.compareAndSet(false, true)) return
        val generation = driftProbeGeneration.get()

        coroutineScope.launch(Dispatchers.IO) {
            var migrated = false
            try {
                val host = TerminalHost.getInstance(project)
                val openTabs = host.openTabsByTabId()
                if (openTabs.isEmpty()) return@launch
                val openTypes = host.openTabAgentTypes()

                // 必须重新加载而不是用 runtime 缓存：那是上一轮轮询的快照，
                // 而 CLI 换 id 后运行态文件立刻就更新了，用旧快照会慢一拍。
                val runtimeSessions = runtimeIndex.load(projectPath).values
                val byPid = runtimeSessions.associateBy { it.pid }
                // 后台 agent 继承了父进程的 IMUX_TAB，却有自己独立的会话 id，必须排除
                val claudePids = interactivePids(runtimeSessions)
                val live = LiveSessionProbe(
                    pidsOf = { type ->
                        when {
                            // 没开这类标签页就没有要认领的终端，别去翻进程表
                            type !in openTypes -> emptyList()
                            type == AgentType.CLAUDE -> claudePids
                            else -> codexPids()
                        }
                    },
                    tabIdOf = ::readTabId,
                    claudeSessionOf = { pid -> byPid[pid]?.sessionId },
                    rolloutsHeldBy = ::readHeldRollouts,
                ).probe()

                val drifts = driftOf(openTabs, live)
                if (drifts.isEmpty() || project.isDisposed) return@launch
                withContext(Dispatchers.EDT) { migrated = applyDrifts(drifts) }
            } finally {
                // 只结算本次启动时看到的那一代。探测期间若又出现了无主会话，
                // requestDriftProbe 已经为新一代重置次数，旧结果不能把它覆盖掉。
                if (driftProbeGeneration.get() == generation) {
                    if (migrated) driftProbeAttempts.set(0) else driftProbeAttempts.decrementAndGet()
                }
                probing.set(false)
                // 新触发器若是在 probing=true 时到达，当时无法启动；这里立即补跑一次。
                if (driftProbeGeneration.get() != generation) probeSessionDrift()
            }
        }
    }

    /**
     * 在 EDT 应用探测结果：换 key、换标题、把轮次监控挪到新会话文件上。
     *
     * 返回是否全部迁移成功——没成功的话调用方要保留重试次数。
     */
    private fun applyDrifts(drifts: List<KeyDrift>): Boolean {
        if (project.isDisposed) return false
        val host = TerminalHost.getInstance(project)

        // 探测是异步的，这期间标签页可能已经关掉、或被关掉后又重新打开成另一个终端。
        // 只认那些「tabId 还在、且仍记着我们探测时看到的旧 id」的结果。
        val applicable = stillApplicable(drifts, host.openTabsByTabId())
        if (applicable.isEmpty()) return false

        var allMigrated = true
        applicable.forEach { drift ->
            val session = model.sessionOf(drift.to)
            if (!host.rebindKey(drift.from, drift.to, session?.title ?: "会话 ${drift.to.take(8)}")) {
                // 目标被占着之类，这次迁不了。保留重试次数，别接着做后面的收尾动作
                allMigrated = false
                return@forEach
            }
            // 新会话直到落盘才有文件路径，迁移这一刻才能纳入监控
            session?.let { host.startWatchingTurn(drift.to, it.agentType, it.filePath) }
            // 旧 id 已经不是这个终端的身份了，挂在它上面的未读该一并撤掉
            clearUnread(drift.from)
        }
        notifyListeners()
        return allMigrated
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

    fun hasUnread(): Boolean = unread.isNotEmpty()

    fun isUnread(sessionId: String): Boolean = sessionId in unread

    fun markUnread(sessionId: String) {
        if (unread.add(sessionId)) {
            updateOpenTabIcons(setOf(sessionId))
            updateFrameTitle()
            notifyListeners()
        }
    }

    fun clearUnread(sessionId: String) {
        // 用户已经看到该会话，挂着的提醒气泡也该一并撤掉
        TurnNotifier.dismiss(sessionId)
        if (unread.remove(sessionId)) {
            updateOpenTabIcons(setOf(sessionId))
            updateFrameTitle()
            notifyListeners()
        }
    }

    fun cancelPendingSession(key: String) {
        model.cancelPending(key)
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
        // 接在 start 而非构造函数里：这两件都是运行时行为，需要 TerminalHost 服务已经可用。
        // 顺序有意义：先把 key 迁到真实 id，标题同步才查得到对应的会话。
        model.addListener {
            applyNewBindings()
            syncOpenTabTitles()
            requestDriftProbe()
        }
        refresh()
    }

    /**
     * 后台扫描会话库并应用。
     *
     * 扫描必须离开 EDT：本机实测 620 个 codex 会话文件，一次扫描 60–250ms，
     * 而刷新由轮询、工具窗口状态变化等多处触发，放在 EDT 上就是周期性卡顿。
     *
     * 用 in-flight 标志避免扫描堆积：若上一次尚未结束，本次直接跳过——
     * 反正结果会被下一轮覆盖，排队只会加剧拥堵。
     */
    fun refresh() {
        if (!scanning.compareAndSet(false, true)) return

        coroutineScope.launch(Dispatchers.IO) {
            val scanned = try {
                runCatching { repository.scan(projectPath) }.getOrNull()
            } finally {
                scanning.set(false)
            }
            if (scanned != null && !project.isDisposed) {
                withContext(Dispatchers.EDT) { model.applyScan(scanned) }
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
                // 上一次探测没成的话在这里续上，见 requestDriftProbe
                probeSessionDrift()

                val host = TerminalHost.getInstance(project)

                // 按项目过滤：运行态目录是全机器共享的，本机实测常年同时跑着五六个项目的
                // claude。不过滤就会为别的项目的会话弹提醒，而那些会话不在本项目列表里，
                // 连标题都查不到，只能显示一串会话 id。
                val snapshot = runtimeIndex.load(projectPath)
                val watcher = host.turnWatcher()
                // 必须先 poll 再读 workingIds：状态由 poll 推进，顺序反了拿到的是上一轮的
                val outcome = statusTracker.completedSince(snapshot)
                val completed = (outcome.completed + watcher.poll()).distinct()
                val running = RunningSessions.of(snapshot, watcher.workingIds(AgentType.CODEX))

                withContext(Dispatchers.EDT) {
                    val runningChanged = runningIds != running
                    runtime = snapshot
                    runningIds = running
                    // 标签图标与窗口标题读的是同一个信号，一并推
                    if (runningChanged) {
                        updateOpenTabIcons()
                        updateFrameTitle()
                    }
                    notifyListeners()

                    completed.forEach { sessionId ->
                        // 正被查看的会话同样要标记与提醒：tab 选中不等于人在屏幕前，
                        // 一声不吭的话，离开一会儿回来就不知道这轮早已跑完。
                        // 标记由终端官方输入事件在用户真正交互时消除。
                        val session = model.sessionOf(sessionId)
                        val title = session?.title ?: "会话 ${sessionId.take(8)}"
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
                                session?.title ?: "会话 ${waiting.sessionId.take(8)}",
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
        val watcher = SessionStoreWatcher(
            claudeHome = claudeHome,
            codexHome = home.resolve(".codex"),
            claudeProjectDirName = ClaudeSessionReader(claudeHome).projectDirName(projectPath),
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
        FileEditorManager.getInstance(project).selectedFiles
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
     * 把未读星号与运行中数量推到窗口标题上。
     *
     * [AgentFrameTitleBuilder] 只在平台自发重算标题时被调用（切文件、项目状态变化），
     * 未读或运行数刚变化的那一刻没有任何重算触发。而「切文件」本身往往就是清未读的
     * 动作——光靠 builder，星号亮起的那一刻用户根本看不到。
     *
     * 只设项目名那一段：[com.intellij.openapi.wm.impl.ProjectFrameHelper] 把项目名与
     * 文件名分成两个字段存，传项目名进去，文件名与分隔符仍由平台自己拼。
     */
    private fun updateFrameTitle() {
        coroutineScope.launch(Dispatchers.EDT) {
            if (project.isDisposed) return@launch
            val titleBuilder = serviceOrNull<FrameTitleBuilder>() ?: return@launch
            WindowManager.getInstance().getIdeFrame(project)
                ?.setFrameTitle(titleBuilder.getProjectTitle(project))
        }
    }

    override fun dispose() = Unit

    companion object {
        /**
         * 一次换 id 最多探测几轮。
         *
         * 重试由既有的运行态轮询推进（约 3 秒一轮），因此这个数字就是「给 CLI 多久
         * 把运行态或文件句柄更新到位」。取 5 约合 15 秒：本机实测 claude 换 id 后
         * 运行态文件几乎立刻更新，留这么多是给 codex 的 `lsof` 与慢盘兜底。
         * 有上限是必须的——用户在 IDE 外面自己开的会话永远是无主的，
         * 不封顶就会每轮都去翻一遍进程表。
         */
        private const val DRIFT_PROBE_ATTEMPTS = 5

        fun getInstance(project: Project): SessionMonitor = project.getService(SessionMonitor::class.java)
    }
}
