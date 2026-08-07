package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.github.izerui.imux.turn.TurnWatcher
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import com.intellij.util.EventDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.plugins.terminal.block.util.TerminalDataContextUtils
import java.util.EventListener

fun interface TerminalSessionsListener : EventListener {
    fun sessionsChanged()
}

fun interface SessionKeyMigratedListener : EventListener {
    fun sessionKeyMigrated(from: String, to: String)
}

/**
 * 终端换 key 之后，列表里该选中谁。
 *
 * `/clear`、`/new` 不产生标签页切换事件，`selectionChanged` 那条既有通路不会响；
 * 而 [AgentSessionTree.reload] 只会拿重绘前的 id 去 restoreSelection，
 * 等于把选中按死在一个已经不属于这个终端的会话上。所以必须主动挪。
 *
 * 判据是**这个终端是不是用户正看着的那个**（[isActiveTab]），而不是树上眼下选中了什么：
 * 打开终端后焦点就在终端里敲字，树的选中未必还停在那个会话上——中间点过别的会话、
 * 或被某次重绘弄丢，靠它做判据会静默失效。
 *
 * 终端不在前台时退回保守规则：只有选中的正是被迁移的那个会话才跟着走，
 * 免得用户正在列表里翻别的会话却被抢走选中。
 */
internal fun selectionAfterMigration(
    current: String?,
    from: String,
    to: String,
    isActiveTab: Boolean,
): String? = when {
    isActiveTab -> to
    current == from -> to
    else -> current
}

/**
 * 拥有所有活着的终端 view，按 key 索引。
 *
 * 所有权规则：view 归本服务所有，但**关闭 editor tab 即结束会话**。
 *
 * 早期设计是「关标签页不杀进程」，为的是再点回来终端内容还在。实际用下来
 * 进程会不断累积——半天攒下六七个存活的 CLI，用户却没有收拾它们的入口。
 * 因此改为关标签页即终止；要接着聊仍可 resume，只是需要重新加载历史。
 */
@Service(Service.Level.PROJECT)
class TerminalHost(private val project: Project) : Disposable {

    private val views = mutableMapOf<String, TerminalView>()

    /**
     * 每个 key 对应的虚拟文件实例必须缓存并复用。
     * FileEditorManager 以 VirtualFile 实例为标签页身份：传同一个实例是「切到该标签页」，
     * 每次新建实例则会不断开出重复标签页。
     *
     * 必须是并发容器：写在 EDT（开关标签页），但 [openTabKeys] 会被后台轮询线程读，
     * 遍历时撞上写就会抛 ConcurrentModificationException。
     */
    private val files = java.util.concurrent.ConcurrentHashMap<String, AgentTerminalVirtualFile>()

    /** 轮次监控。只有经本插件打开过的会话才纳入，见设计文档的监控范围决策。 */
    private val turnWatcher = TurnWatcher()

    private val sessionsChangedDispatcher = EventDispatcher.create(TerminalSessionsListener::class.java)
    private val keyMigratedDispatcher = EventDispatcher.create(SessionKeyMigratedListener::class.java)

    /**
     * 启动一个全新会话（不带 resume）。
     *
     * [key] 必须由调用方给出，且必须与 SessionListModel 的 pending key 一致——
     * 会话落盘后 [rebindKey] 要靠它把终端迁到真实会话 id 下。
     * 若两边各生成各的 key，迁移会静默失败，之后点击该会话就会重开一个
     * `--resume` 终端，与仍在运行的原终端抢同一个会话，CLI 会报
     * 「currently running as a background agent」。
     */
    fun openNew(agentType: AgentType, key: String, tabTitle: String) {
        open(key, agentType, newCommand(agentType), tabTitle)
    }

    /**
     * 结束会话：终止进程并清理记账。
     *
     * 由 [AgentTerminalFileEditor] 在标签页真正关闭时调用。
     * 「关标签页 = 结束会话」是明确的产品决策：不这么做，进程会不断累积，
     * 用户半天就能攒下六七个隐形的 CLI 却无从收拾。
     */
    fun closeSession(file: AgentTerminalVirtualFile) {
        val key = file.sessionKey
        // key 会在会话迁移时变化，也可能被另一个终端重新占用。只有账上仍然是这个
        // VirtualFile，才允许清掉 key 对应的 view 与 watcher；否则只结束自身的 view。
        if (files.remove(key, file)) {
            turnWatcher.unwatch(key)
            if (views[key] === file.terminalView) views.remove(key)
            sessionsChangedDispatcher.multicaster.sessionsChanged()
        }
        file.terminalView.coroutineScope.cancel()
    }

    /** 标签页集合变化（打开或结束）时回调，供界面立即刷新标记。 */
    fun addSessionsChangedListener(
        parentDisposable: Disposable,
        listener: TerminalSessionsListener,
    ) {
        sessionsChangedDispatcher.addListener(listener, parentDisposable)
    }

    /**
     * 当前开着标签页的会话 key。
     *
     * 这是「已打开」标记的唯一依据。不要改用 CLI 运行态文件去推断：那是进程状态，
     * 由 CLI 自己异步落盘，点击后要等它写出来再等下一轮轮询才能读到，标记会明显滞后；
     * 而标签页开没开是本服务自己的账，点击那一刻就是确定的。
     */
    fun openTabKeys(): Set<String> = files.keys.toSet()

    /** 打开一个已有会话；若其终端已在运行则切到该标签页而不重启。 */
    fun openResume(agentType: AgentType, sessionId: String, tabTitle: String) {
        open(sessionId, agentType, resumeCommand(agentType, sessionId), tabTitle)
    }

    /**
     * 从完成通知打开会话，并显示最新输出。
     *
     * 普通列表点击仍保留原滚动位置；通知表达的是「查看刚完成的结果」，
     * 因此选中标签页后要明确滚到底部。
     */
    fun openResumeAtBottom(agentType: AgentType, sessionId: String, tabTitle: String) {
        openResume(agentType, sessionId, tabTitle)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            views[sessionId]?.let(::scrollToBottom)
        }
    }

    fun turnWatcher(): TurnWatcher = turnWatcher

    /** 把会话纳入轮次监控。文件路径来自扫描结果（AgentSession.filePath）。 */
    fun startWatchingTurn(sessionId: String, agentType: AgentType, file: java.nio.file.Path) {
        turnWatcher.watch(sessionId, agentType, file)
    }

    /**
     * 新建的会话在 CLI 落盘后才拿到真实 id，此时要把终端从 openNew 的合成 key
     * 迁到真实 id 下。不做这一步会有两个后果：运行中标识查不到；再次点击该会话
     * 会以真实 id 找不到已有终端，从而重开一个。
     *
     * 虚拟文件实例必须沿用同一个——它是标签页的身份，换实例会开出新标签页。
     */
    fun rebindKey(oldKey: String, newKey: String, newTitle: String): Boolean {
        if (oldKey == newKey) return true

        val view = views[oldKey]
        val file = files[oldKey]
        if (view == null || file == null) {
            // 不要静默返回：迁移失败意味着之后点击该会话会重开一个 --resume 终端，
            // 与仍在运行的原终端抢同一个会话。曾因两边 key 不一致而静默失败过。
            LOG.warn(
                "换 key 失败：找不到 key=$oldKey 的完整终端，目标 $newKey。" +
                    "view keys=${views.keys}, file keys=${files.keys}",
            )
            return false
        }

        // 探测期间用户可能已经点击了新会话，开出一个注定会 resume 冲突的重复终端。
        // 先按实例摘掉并关闭它，再把真正持有该会话的原终端迁过来。
        val activateMigrated =
            discardDuplicateTarget(newKey, sourceFile = file, sourceView = view)

        views.remove(oldKey)
        files.remove(oldKey)
        views[newKey] = view
        files[newKey] = file
        turnWatcher.unwatch(oldKey)

        // 必须同步：未读标记按 sessionKey 清除，留着旧的 pending key 会清不掉
        file.sessionKey = newKey
        updateTabTitle(file, newTitle)
        if (activateMigrated) FileEditorManager.getInstance(project).openFile(file, true)

        // 界面得知道 key 变了，否则列表的选中态会一直停在旧会话上——
        // `/clear`、`/new` 不产生标签页切换事件，selectionChanged 那条通路不会响。
        keyMigratedDispatcher.multicaster.sessionKeyMigrated(oldKey, newKey)
        return true
    }

    private fun discardDuplicateTarget(
        key: String,
        sourceFile: AgentTerminalVirtualFile,
        sourceView: TerminalView,
    ): Boolean {
        val duplicateFile = files.remove(key)
        val duplicateView = views.remove(key)
        if (duplicateFile == null && duplicateView == null) return false

        val fileEditorManager = FileEditorManager.getInstance(project)
        val wasSelected = duplicateFile != null &&
            fileEditorManager.selectedFiles.any { it === duplicateFile }
        turnWatcher.unwatch(key)
        if (duplicateFile != null && duplicateFile !== sourceFile) {
            fileEditorManager.closeFile(duplicateFile)
            duplicateFile.terminalView.coroutineScope.cancel()
        }
        if (duplicateView != null && duplicateView !== sourceView) {
            duplicateView.coroutineScope.cancel()
        }
        return wasSelected
    }

    /** 终端换 key 时回调，供界面把选中态一并迁走。在 EDT 调用。 */
    fun addSessionKeyMigratedListener(
        parentDisposable: Disposable,
        listener: SessionKeyMigratedListener,
    ) {
        keyMigratedDispatcher.addListener(listener, parentDisposable)
    }

    /**
     * 让已打开的标签页标题跟上最新的会话标题。
     *
     * 由 [com.github.izerui.imux.monitor.SessionMonitor] 在扫描结果变化时调用，
     * 不放在界面里：标签页开着而工具窗口没展开是常态，标题不该因此停更。
     *
     * 必须在 EDT 调用。
     */
    fun syncTabTitles(latestTitleOf: (String) -> String?) {
        val current = files.mapValues { (_, file) -> file.tabTitle }
        staleTabTitles(current, latestTitleOf).forEach { (key, title) ->
            files[key]?.let { updateTabTitle(it, title) }
        }
    }

    private fun updateTabTitle(file: AgentTerminalVirtualFile, newTitle: String) {
        if (file.tabTitle == newTitle) return
        file.tabTitle = newTitle
        FileEditorManager.getInstance(project).updateFilePresentation(file)
    }

    /**
     * 已打开的标签页：tabId -> 它当前记账的会话 id。
     *
     * 供 [com.github.izerui.imux.session.driftOf] 与探测结果比对。tabId 是终端的固有
     * 身份，会话 id 则会随 `/clear`、`/new` 变化，两者必须分开看。
     */
    fun openTabsByTabId(): Map<String, String> =
        files.values.associate { it.tabId to it.sessionKey }

    /**
     * 当前开着标签页的 agent 种类。
     *
     * 用来把探测限制在真正需要的范围：codex 那侧要遍历进程表再逐个 `lsof`，
     * 一个 codex 标签页都没开的时候跑这些纯属白费。
     */
    fun openTabAgentTypes(): Set<AgentType> =
        files.values.mapTo(mutableSetOf()) { it.agentType }

    private fun open(
        key: String,
        agentType: AgentType,
        command: List<String>,
        tabTitle: String,
    ) {
        discardIfTerminated(key)
        val file = files.getOrPut(key) {
            // tabId 必须在建 view 之前定下：它要作为环境变量随进程启动，
            // 之后从进程 env 里读回来才认得出这个终端。
            val tabId = newTabId()
            val view = views.getOrPut(key) { createView(agentType, command, tabTitle, tabId) }
            AgentTerminalVirtualFile(tabTitle, view, key, agentType, tabId)
                .also(::closeTabWhenTerminated)
        }
        FileEditorManager.getInstance(project).openFile(file, true)
        // 与 closeSession 对称。缺了这一下，标记就只能等下一轮 3 秒轮询才亮。
        sessionsChangedDispatcher.multicaster.sessionsChanged()
    }

    internal fun scrollToBottom(view: TerminalView) {
        val editor = currentTerminalEditor(view) ?: return
        val scrolling = editor.scrollingModel

        scrolling.disableAnimation()
        try {
            scrolling.scrollVertically(Int.MAX_VALUE)
        } finally {
            scrolling.enableAnimation()
        }
    }

    internal fun isScrolledToBottom(editor: Editor): Boolean {
        val visible = editor.scrollingModel.visibleArea
        return isViewportAtBottom(
            visibleTop = visible.y,
            visibleHeight = visible.height,
            contentHeight = editor.contentComponent.height,
        )
    }

    internal fun currentTerminalEditor(view: TerminalView): Editor? {
        val dataContext = DataManager.getInstance().getDataContext(view.component)
        return with(TerminalDataContextUtils) { dataContext.terminalEditor }
    }

    /**
     * CLI 进程一结束就关掉它的标签页。
     *
     * 这里跑的是 claude / codex 本身，底下**没有 shell 兜着**。进程没了，标签页就成了
     * 一个不含任何进程的空壳：敲什么都没反应，也没有提示符接管，看起来就是卡死——
     * 而唯一的出路（回列表再点一次该会话，由 [discardIfTerminated] 重开）没人猜得到。
     * 与既有的「关标签页 = 结束会话」正好对称。
     *
     * 认 [AgentTerminalVirtualFile] 实例而不认 key：新建会话落盘后 [rebindKey] 会换 key，
     * 拿旧 key 到那时已经查不到东西；而虚拟文件实例是标签页的身份，全程不变。
     *
     * 关标签页会触发 [AgentTerminalFileEditor.dispose]，进而走到 [closeSession] 完成记账清理。
     *
     * 已知代价：CLI 启动即失败时（例如 claude 不在 PATH）标签页会一闪而过，来不及看报错。
     */
    private fun closeTabWhenTerminated(file: AgentTerminalVirtualFile) {
        val view = file.terminalView
        view.coroutineScope.launch {
            view.sessionState.first { it is TerminalViewSessionState.Terminated }
            // 不能在这里直接关：本协程属于 view 的 scope，而关标签页会取消这个 scope
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).closeFile(file)
            }
        }
    }

    /**
     * 丢弃已终止的终端，让本次点击重新起一个。
     *
     * 否则点击一个 CLI 已退出的会话，只会切到一个死掉的标签页，什么也不会发生。
     */
    private fun discardIfTerminated(key: String) {
        val view = views[key] ?: return
        if (view.sessionState.value !is TerminalViewSessionState.Terminated) return

        views.remove(key)
        files.remove(key)?.let { FileEditorManager.getInstance(project).closeFile(it) }
        view.coroutineScope.cancel()
    }

    /**
     * 创建一个只属于编辑器的终端。
     *
     * IDEA 2026.2 的 [TerminalToolWindowTabsManager.detachTab] 会把 backend tab 标记为 detached：
     * 它继续承载当前进程，但不会作为 Terminal 工具窗口标签持久化，也不会在下次启动时恢复。
     * 公开 API 会先创建普通 tab，再立即 detach；关闭焦点请求可避免激活底部工具窗口，
     * 禁用延迟启动则保证 detached view 无需等待工具窗口显示就能启动进程。
     */
    private fun createView(
        agentType: AgentType,
        command: List<String>,
        tabTitle: String,
        tabId: String,
    ): TerminalView {
        val manager = TerminalToolWindowTabsManager.getInstance(project)
        val tab = manager
            .createTabBuilder()
            .workingDirectory(projectPath())
            .shellCommand(command)
            .envVariables(launchEnvironment(agentType, tabId))
            .tabName(tabTitle)
            .requestFocus(false)
            .deferSessionStartUntilUiShown(false)
            .createTab()

        return manager.detachTab(tab)
    }

    private fun projectPath(): String =
        project.basePath ?: System.getProperty("user.home")

    private fun newCommand(agentType: AgentType): List<String> =
        launchCommand(resolveShell(System.getenv("SHELL")), agentType, resumeId = null)

    private fun resumeCommand(agentType: AgentType, sessionId: String): List<String> =
        launchCommand(resolveShell(System.getenv("SHELL")), agentType, resumeId = sessionId)

    /**
     * 项目关闭时终结所有会话。这是唯一该杀进程的地方。
     *
     * 待实机验证：若 tabs manager 会兜底释放 scope，此处的显式 cancel 应改为无操作以免重复取消。
     */
    override fun dispose() {
        views.values.forEach { it.coroutineScope.cancel() }
        views.clear()
        files.clear()
    }

    /**
     * 终端的身份。
     *
     * 不用 pid：命令是 `shell -l -i -c "cli"`，CLI 是 shell 的子进程，
     * 而 shell 是否 exec 掉自己因 shell 与平台而异，pid 对不对得上没有保证。
     * 自己发一个 id 注入进去，读回来就是确定的。
     */
    private fun newTabId(): String = "imux-" + java.util.UUID.randomUUID()

    companion object {
        private val LOG = logger<TerminalHost>()

        fun getInstance(project: Project): TerminalHost = project.getService(TerminalHost::class.java)
    }
}

/**
 * 已打开的标签页里，标题落后于最新扫描结果的那些。
 *
 * 标题不是一次就位的：新建会话绑定的那一刻 CLI 往往还没起好标题，只能先用首条用户
 * 消息，而那常常是注入的系统内容（`# AGENTS.md instructions for …`）。等 CLI 事后
 * 生成了真正的标题，列表会随扫描更新，标签页也该跟上。
 *
 * 两种情况保持原样：查不到会话（新建会话尚未绑定，标签页还记在合成 key 下），
 * 以及新标题为空——那还不如留着旧的。
 */
internal fun staleTabTitles(
    current: Map<String, String>,
    latestTitleOf: (String) -> String?,
): Map<String, String> = current.mapNotNull { (key, title) ->
    val latest = latestTitleOf(key)
    if (latest.isNullOrEmpty() || latest == title) null else key to latest
}.toMap()

internal fun isViewportAtBottom(
    visibleTop: Int,
    visibleHeight: Int,
    contentHeight: Int,
): Boolean =
    visibleTop.toLong() + visibleHeight >= contentHeight.toLong() - 1L
