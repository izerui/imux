package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.session.SessionListModel
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.TerminalHost
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
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
            setContent(JBScrollPane(sessionTree.component()))
        }

        toolWindow.setTitleActions(
            listOf(
                NewSessionAction(),
                RefreshAction(),
            ),
        )

        // 标签页开或关时立即重绘，不必等下一轮轮询。
        TerminalHost.getInstance(project).addSessionsChangedListener(contentDisposable) {
            sessionTree.reload()
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
        event.project ?: return
        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(
                "选择 Agent",
                DefaultActionGroup(
                    CreateAction(AgentType.CLAUDE, "Claude Code"),
                    CreateAction(AgentType.CODEX, "Codex"),
                ),
                event.dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false,
            )

        val anchor = event.inputEvent?.component
        if (anchor != null) {
            popup.showUnderneathOf(anchor)
        } else {
            popup.showInBestPositionFor(event.dataContext)
        }
    }
}

private class CreateAction(
    private val agentType: AgentType,
    label: String,
) : DumbAwareAction(label) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val monitor = SessionMonitor.getInstance(project)
        val model: SessionListModel = monitor.model
        // 先登记再启动：startedAt 必须早于 CLI 可能的首次落盘，否则绑定会漏
        val pending = model.registerPending(agentType)
        // 终端必须以 pending.key 记录：会话落盘后要靠这个 key 把终端迁到真实 id 下
        TerminalHost.getInstance(project).openNew(agentType, pending.key, "新会话")
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
