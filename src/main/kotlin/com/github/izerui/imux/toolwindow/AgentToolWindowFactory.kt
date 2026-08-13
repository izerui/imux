package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.session.SessionListModel
import com.github.izerui.imux.settings.ImuxSettings
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.TerminalHost
import com.github.izerui.imux.terminal.preassignedSessionId
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory

/**
 * 只负责界面。
 *
 * 扫描、轮询、提醒、未读记账都在 [SessionMonitor] 里，由项目启动活动拉起——
 * 工具窗口的内容是懒加载的，把那些逻辑放在这里，从没展开过面板的项目就一片死寂。
 * 这里只是它的一个订阅者，随时可以被创建或销毁。
 */
class AgentToolWindowFactory : ToolWindowFactory, DumbAware {

    // 展示名与注册 id（imux）解耦：改 id 会让用户已保存的工具窗口布局状态失效。
    override fun init(toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "AI Agents"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        timed("构建工具窗口内容", CREATE_WARN_MS) { doCreateContent(project, toolWindow) }
    }

    private fun doCreateContent(project: Project, toolWindow: ToolWindow) {
        val monitor = SessionMonitor.getInstance(project)
        // 幂等。正常情况下启动活动已经跑过，这里只是兜底——
        // 万一启动活动因故没执行，至少打开面板还能把监听拉起来。
        monitor.start()

        val contentDisposable = Disposer.newDisposable("imux tool window content")
        val sessionTree = AgentSessionTree(project, monitor, contentDisposable)
        val panel = SimpleToolWindowPanel(true, true).apply {
            // 保留水平滚动条：标题默认收成省略号，但省略号看不出被吃掉的是什么，
            // 往右拖能把整条标题读完——渲染器按当前可视宽度截断，拖到哪就显示到哪。
            setContent(JBScrollPane(sessionTree.component()))
        }

        // 新 UI 默认把标题栏图标折叠成「鼠标悬停或窗口聚焦才浮现」，但新建/刷新是这里的高频入口，
        // 藏起来等于每次都要先摸一下才知道点哪。平台给了这个开关（见 InternalDecoratorImpl
        // .updateActiveAndHoverState），置位后图标常驻，等价于用户手动开 Advanced Settings 里的
        // ide.always.show.tool.window.header.icons，但只作用于本窗口，不动别人。
        toolWindow.component.putClientProperty(ToolWindowContentUi.DONT_HIDE_TOOLBAR_IN_HEADER, true)

        toolWindow.setTitleActions(
            listOf(
                NewSessionAction(),
                RefreshAction(),
            ),
        )

        // 挂在工具窗口自带的 ⋮ 齿轮菜单上，套一层 Behavior 与 IDEA 项目视图同构。
        // 这个菜单里并排着平台自带的 Move to / Resize / Remove from Sidebar 等英文项，
        // 中文项夹在其中最扎眼，所以此处文案跟着容器走，用英文。
        toolWindow.setAdditionalGearActions(
            DefaultActionGroup(
                DefaultActionGroup("Behavior", true).apply {
                    add(ToggleSingleClickAction())
                },
            ),
        )

        // 标签页开或关时立即重绘，不必等下一轮轮询。
        TerminalHost.getInstance(project).addSessionsChangedListener(contentDisposable) {
            sessionTree.reload()
        }

        // 终端换 key 时把选中挪过去。`/clear`、`/new` 与新建会话落盘都走这里，
        // 它们都不产生标签页切换，下面那条 selectionChanged 通路接不住。
        TerminalHost.getInstance(project).addSessionKeyMigratedListener(contentDisposable) { from, to ->
            sessionTree.reload()
            // rebindKey 里已经把 file.sessionKey 改成了新 id，所以拿新 id 比对
            val activeKey = (FileEditorManager.getInstance(project).selectedEditor?.file
                as? AgentTerminalVirtualFile)?.sessionKey
            sessionTree.migrateSelection(from, to, isActiveTab = activeKey == to)
        }

        // 工具窗口真正显示时兜底扫描一次。
        project.messageBus.connect(contentDisposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(shownToolWindow: ToolWindow) {
                    if (shownToolWindow === toolWindow) monitor.refresh()
                }
            },
        )

        // 切到终端标签页时同步列表选中态，并在平台焦点投递稳定后聚焦终端。
        project.messageBus.connect(contentDisposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile as? AgentTerminalVirtualFile ?: return
                    sessionTree.revealSession(file.sessionKey)
                    focusTerminal(event, file)
                }

                private fun focusTerminal(
                    event: FileEditorManagerEvent,
                    file: AgentTerminalVirtualFile,
                ) {
                    val activatedEditor = event.newEditor ?: return
                    val focusManager = IdeFocusManager.getInstance(project)
                    if (
                        project.isDisposed ||
                        event.manager.selectedEditor !== activatedEditor
                    ) {
                        return
                    }

                    val target = file.terminalView.preferredFocusableComponent
                    if (target.isShowing) {
                        focusManager.requestFocusInProject(target, project)
                    }
                }
            },
        )

        sessionTree.reload()

        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setPreferredFocusableComponent(sessionTree.component())
        content.setDisposer(contentDisposable)
        toolWindow.contentManager.addContent(content)
    }

    /** 只在超过阈值时记一条，平时零噪音；用于定位卡顿来自哪一段。 */
    private inline fun <T> timed(label: String, thresholdMs: Long, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val elapsed = (System.nanoTime() - start) / 1_000_000
        if (elapsed >= thresholdMs) LOG.warn("[imux] $label 耗时 ${elapsed}ms")
        return result
    }

    private companion object {
        val LOG = logger<AgentToolWindowFactory>()

        /** 阈值刻意定得低，便于定位；正常情况下不会打日志。 */
        const val CREATE_WARN_MS = 50L
    }
}

private class NewSessionAction :
    DumbAwareAction("新建会话", "新建一个 AI Agent 会话", AllIcons.General.Add) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        NewSessionPopup.show(event.inputEvent?.component, event.dataContext) { agentType ->
            createSession(project, agentType)
        }
    }

    private fun createSession(project: Project, agentType: AgentType) {
        val monitor = SessionMonitor.getInstance(project)
        val model: SessionListModel = monitor.model
        // pi 的会话 id 可以先定下来（见 preassignedSessionId），其余 agent 为 null
        val sessionId = preassignedSessionId(agentType)
        // 先登记再启动：startedAt 必须早于 CLI 可能的首次落盘，否则绑定会漏
        val pending = model.registerPending(agentType, sessionId)
        // 终端必须以 pending.key 记录：会话落盘后要靠这个 key 把终端迁到真实 id 下
        TerminalHost.getInstance(project).openNew(agentType, pending.key, "新会话", sessionId)
        monitor.refresh()
    }
}

private class RefreshAction :
    DumbAwareAction("刷新", "重新扫描会话库", AllIcons.Actions.Refresh) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { SessionMonitor.getInstance(it).refresh() }
    }
}

private class ToggleSingleClickAction :
    DumbAwareToggleAction("Open Sessions with Single Click") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(event: AnActionEvent): Boolean =
        ImuxSettings.getInstance().state.openWithSingleClick

    override fun setSelected(event: AnActionEvent, state: Boolean) {
        ImuxSettings.getInstance().state.openWithSingleClick = state
    }
}
