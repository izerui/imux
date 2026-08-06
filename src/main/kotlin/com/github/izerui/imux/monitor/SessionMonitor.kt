package com.github.izerui.imux.monitor

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.ClaudeRuntimeIndex
import com.github.izerui.imux.session.ClaudeRuntimeSession
import com.github.izerui.imux.session.ClaudeSessionReader
import com.github.izerui.imux.session.SessionListModel
import com.github.izerui.imux.session.SessionRepository
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.TerminalHost
import com.github.izerui.imux.turn.RunningSessions
import com.github.izerui.imux.turn.RuntimeStatusTracker
import com.github.izerui.imux.turn.TurnNotifier
import com.github.izerui.imux.watch.SessionStoreWatcher
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
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

fun interface SessionMonitorListener : EventListener {
    fun stateChanged()
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

    init {
        // 扫描结果、新建 pending、pending 绑定真实 id 都由 model 产出。
        // monitor 必须透传这些变化，否则界面只能等下一次运行态轮询才刷新。
        model.addListener(::notifyListeners)
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
            notifyListeners()
        }
    }

    fun clearUnread(sessionId: String) {
        // 用户已经看到该会话，挂着的提醒气泡也该一并撤掉
        TurnNotifier.dismiss(sessionId)
        if (unread.remove(sessionId)) {
            updateOpenTabIcons(setOf(sessionId))
            notifyListeners()
        }
    }

    /** 启动监听。幂等——工具窗口与启动活动都可能调到。 */
    fun start() {
        if (!started.runOnceResetOnFailure(::startWatching)) return
        clearUnreadOnTabSwitch()
        // 接在 start 而非构造函数里：这是运行时行为，需要 TerminalHost 服务已经可用
        model.addListener(::syncOpenTabTitles)
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
                val host = TerminalHost.getInstance(project)

                // 按项目过滤：运行态目录是全机器共享的，本机实测常年同时跑着五六个项目的
                // claude。不过滤就会为别的项目的会话弹提醒，而那些会话不在本项目列表里，
                // 连标题都查不到，只能显示一串会话 id。
                val snapshot = runtimeIndex.load(projectPath)
                val watcher = host.turnWatcher()
                // 必须先 poll 再读 workingIds：状态由 poll 推进，顺序反了拿到的是上一轮的
                val completed = (statusTracker.completedSince(snapshot) + watcher.poll()).distinct()
                val running = RunningSessions.of(snapshot, watcher.workingIds(AgentType.CODEX))

                withContext(Dispatchers.EDT) {
                    val runningChanged = runningIds != running
                    runtime = snapshot
                    runningIds = running
                    if (runningChanged) updateOpenTabIcons()
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

                        TurnNotifier.notifyCompleted(
                            project,
                            sessionId,
                            title,
                            session?.agentType,
                            duration,
                        ) {
                            model.sessionOf(sessionId)?.let {
                                host.openResumeAtBottom(it.agentType, it.id, it.title)
                            }
                            clearUnread(sessionId)
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

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): SessionMonitor = project.getService(SessionMonitor::class.java)
    }
}
