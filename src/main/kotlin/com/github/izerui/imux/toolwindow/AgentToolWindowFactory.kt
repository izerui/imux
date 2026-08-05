package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.session.SessionListModel
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.TerminalHost
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel

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

        val sessionTree = AgentSessionTree(project, monitor)

        val panel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(sessionTree.component()), BorderLayout.CENTER)
        }

        val actions = DefaultActionGroup(
            NewSessionAction(project, monitor),
            RefreshAction(monitor),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("imuxToolWindow", actions, true)
        toolbar.targetComponent = panel
        panel.add(toolbar.component, BorderLayout.NORTH)

        // 标签页开或关时立即重绘，不必等下一轮轮询——否则标记要过几秒才亮/才灭，手感很迟钝。
        // 这里只重绘不读运行态文件：绿色标记的依据是标签页开没开，那是内存里的账，
        // 而运行态文件是 CLI 异步落盘的，读它既慢又晚。
        TerminalHost.getInstance(project).addSessionsChangedListener { sessionTree.reload() }

        // 工具窗口由隐藏变为可见时兜底扫描一次，覆盖轮询可能错过的场景。
        //
        // 必须只在「变为可见」的那一刻触发：ToolWindowManagerListener 监听的是
        // 全 IDE 所有工具窗口的状态变化，任何窗口开关、拖动、焦点变化都会回调。
        // 若每次回调都刷新，点一下图标就会连发多轮全量扫描并重建列表。
        var wasVisible = false
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(manager: ToolWindowManager) {
                    val visible = toolWindow.isVisible
                    if (visible && !wasVisible) monitor.refresh()
                    wasVisible = visible
                }
            },
        )

        // 切到某个终端标签页时，把列表选中挪过去，与「点列表打开标签页」构成双向联动。
        // 消除未读不在这里做——那与界面无关，由 SessionMonitor 独立监听。
        // 切到非终端文件时不动列表：那与会话无关，清掉选中反而丢了上下文。
        project.messageBus.connect(toolWindow.disposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile as? AgentTerminalVirtualFile ?: return
                    sessionTree.revealSession(file.sessionKey)
                    focusTerminal(file)
                }

                /**
                 * 把焦点送进终端的编辑器组件，输入法候选窗才会跟着光标走
                 * （原因见 AgentTerminalFileEditor 的 focusForwarder 注释）。
                 *
                 * 与上面「不抢焦点」那条注释并不矛盾：那说的是别让**会话列表**偷走焦点，
                 * 而这里送的目标正是用户切过去要敲字的终端本身。
                 *
                 * 必须 invokeLater：selectionChanged 触发时平台自己的焦点投递还没跑完，
                 * 此刻抢先 request 会被随后的平台投递覆盖掉。
                 */
                private fun focusTerminal(file: AgentTerminalVirtualFile) {
                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        val target = file.terminalView.preferredFocusableComponent
                        if (target.isShowing) target.requestFocusInWindow()
                    }
                }
            },
        )

        sessionTree.reload()

        val content = ContentFactory.getInstance().createContent(panel, null, false)
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

private class NewSessionAction(
    private val project: Project,
    private val monitor: SessionMonitor,
) : AnAction("新建会话", "新建一个 AI Agent 会话", AllIcons.General.Add) {

    override fun actionPerformed(event: AnActionEvent) {
        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(
                "选择 Agent",
                DefaultActionGroup(
                    CreateAction(project, monitor, AgentType.CLAUDE, "Claude Code"),
                    CreateAction(project, monitor, AgentType.CODEX, "Codex"),
                ),
                event.dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false,
            )

        // 贴着触发它的工具栏按钮弹出。
        // 不能只用 showInBestPositionFor：工具栏按钮的 dataContext 不携带触发组件，
        // 平台拿不到锚点就会退回到面板中央偏下的默认位置。
        val anchor = event.inputEvent?.component
        if (anchor != null) popup.showUnderneathOf(anchor) else popup.showInBestPositionFor(event.dataContext)
    }
}

private class CreateAction(
    private val project: Project,
    private val monitor: SessionMonitor,
    private val agentType: AgentType,
    label: String,
) : AnAction(label) {

    override fun actionPerformed(event: AnActionEvent) {
        val model: SessionListModel = monitor.model
        // 先登记再启动：startedAt 必须早于 CLI 可能的首次落盘，否则绑定会漏
        val pending = model.registerPending(agentType)
        // 终端必须以 pending.key 记录：会话落盘后要靠这个 key 把终端迁到真实 id 下
        TerminalHost.getInstance(project).openNew(agentType, pending.key, "新会话")
        monitor.refresh()
    }
}

private class RefreshAction(private val monitor: SessionMonitor) :
    AnAction("刷新", "重新扫描会话库", AllIcons.Actions.Refresh) {

    override fun actionPerformed(event: AnActionEvent) {
        monitor.refresh()
    }
}
