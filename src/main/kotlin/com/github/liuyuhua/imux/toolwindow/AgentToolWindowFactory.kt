package com.github.liuyuhua.imux.toolwindow

import com.github.liuyuhua.imux.model.AgentType
import com.github.liuyuhua.imux.session.ClaudeSessionReader
import com.github.liuyuhua.imux.session.SessionListModel
import com.github.liuyuhua.imux.session.SessionRepository
import com.github.liuyuhua.imux.terminal.TerminalHost
import com.github.liuyuhua.imux.watch.SessionStoreWatcher
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.nio.file.Paths
import java.time.Instant
import javax.swing.JPanel

class AgentToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val repository = SessionRepository.forUserHome()
        val projectPath = project.basePath ?: System.getProperty("user.home")

        val model = SessionListModel(
            scan = { repository.scan(projectPath) },
            clock = { Instant.now() },
        )
        val sessionTree = AgentSessionTree(project, model)

        val panel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(sessionTree.component()), BorderLayout.CENTER)
        }

        val actions = DefaultActionGroup(
            NewSessionAction(project, model),
            RefreshAction(model),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("imuxToolWindow", actions, true)
        toolbar.targetComponent = panel
        panel.add(toolbar.component, BorderLayout.NORTH)

        startWatching(project, toolWindow, model, projectPath)

        // 工具窗口重新可见时兜底扫描一次，覆盖轮询可能错过的场景
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(manager: ToolWindowManager) {
                    if (toolWindow.isVisible) model.refresh()
                }
            },
        )

        model.refresh()

        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    private fun startWatching(
        project: Project,
        toolWindow: ToolWindow,
        model: SessionListModel,
        projectPath: String,
    ) {
        val home = Paths.get(System.getProperty("user.home"))
        val claudeHome = home.resolve(".claude")
        val watcher = SessionStoreWatcher(
            claudeHome = claudeHome,
            codexHome = home.resolve(".codex"),
            claudeProjectDirName = ClaudeSessionReader(claudeHome).projectDirName(projectPath),
            onChange = { model.refresh() },
        )
        watcher.start()
        Disposer.register(toolWindow.disposable, watcher)
    }
}

private class NewSessionAction(
    private val project: Project,
    private val model: SessionListModel,
) : AnAction("新建会话", "新建一个 AI Agent 会话", AllIcons.General.Add) {

    override fun actionPerformed(event: AnActionEvent) {
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                "选择 Agent",
                DefaultActionGroup(
                    CreateAction(project, model, AgentType.CLAUDE, "Claude Code"),
                    CreateAction(project, model, AgentType.CODEX, "Codex"),
                ),
                event.dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false,
            )
            .showInBestPositionFor(event.dataContext)
    }
}

private class CreateAction(
    private val project: Project,
    private val model: SessionListModel,
    private val agentType: AgentType,
    label: String,
) : AnAction(label) {

    override fun actionPerformed(event: AnActionEvent) {
        // 先登记再启动：startedAt 必须早于 CLI 可能的首次落盘，否则绑定会漏
        model.registerPending(agentType)
        TerminalHost.getInstance(project).openNew(agentType, "新会话")
        model.refresh()
    }
}

private class RefreshAction(private val model: SessionListModel) :
    AnAction("刷新", "重新扫描会话库", AllIcons.Actions.Refresh) {

    override fun actionPerformed(event: AnActionEvent) {
        model.refresh()
    }
}
