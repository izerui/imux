package com.github.liuyuhua.imux.toolwindow

import com.github.liuyuhua.imux.model.AgentType
import com.github.liuyuhua.imux.session.ClaudeSessionReader
import com.github.liuyuhua.imux.session.SessionListModel
import com.github.liuyuhua.imux.session.SessionRepository
import com.github.liuyuhua.imux.terminal.TerminalHost
import com.github.liuyuhua.imux.watch.SessionStoreWatcher
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
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
import java.util.concurrent.atomic.AtomicBoolean
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

        val refresh = backgroundRefresh(repository, projectPath, model)

        val actions = DefaultActionGroup(
            NewSessionAction(project, model, refresh),
            RefreshAction(refresh),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("imuxToolWindow", actions, true)
        toolbar.targetComponent = panel
        panel.add(toolbar.component, BorderLayout.NORTH)

        startWatching(toolWindow, projectPath, refresh)

        // 工具窗口重新可见时兜底扫描一次，覆盖轮询可能错过的场景
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(manager: ToolWindowManager) {
                    if (toolWindow.isVisible) refresh()
                }
            },
        )

        refresh()

        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * 返回一个「后台扫描 + EDT 应用」的刷新函数。
     *
     * 扫描必须离开 EDT：本机实测 620 个 codex 会话文件，一次扫描 60–250ms，
     * 而刷新由 3 秒轮询、工具窗口状态变化等多处触发，放在 EDT 上就是周期性卡顿。
     *
     * 用 in-flight 标志避免扫描堆积：若上一次尚未结束，本次直接跳过——
     * 反正结果会被下一轮覆盖，排队只会加剧拥堵。
     */
    private fun backgroundRefresh(
        repository: SessionRepository,
        projectPath: String,
        model: SessionListModel,
    ): () -> Unit {
        val scanning = AtomicBoolean(false)
        return {
            if (scanning.compareAndSet(false, true)) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    val scanned = try {
                        runCatching { repository.scan(projectPath) }.getOrNull()
                    } finally {
                        scanning.set(false)
                    }
                    if (scanned != null) {
                        ApplicationManager.getApplication().invokeLater { model.applyScan(scanned) }
                    }
                }
            }
        }
    }

    private fun startWatching(
        toolWindow: ToolWindow,
        projectPath: String,
        refresh: () -> Unit,
    ) {
        val home = Paths.get(System.getProperty("user.home"))
        val claudeHome = home.resolve(".claude")
        val watcher = SessionStoreWatcher(
            claudeHome = claudeHome,
            codexHome = home.resolve(".codex"),
            claudeProjectDirName = ClaudeSessionReader(claudeHome).projectDirName(projectPath),
            onChange = refresh,
        )
        watcher.start()
        Disposer.register(toolWindow.disposable, watcher)
    }
}

private class NewSessionAction(
    private val project: Project,
    private val model: SessionListModel,
    private val refresh: () -> Unit,
) : AnAction("新建会话", "新建一个 AI Agent 会话", AllIcons.General.Add) {

    override fun actionPerformed(event: AnActionEvent) {
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                "选择 Agent",
                DefaultActionGroup(
                    CreateAction(project, model, refresh, AgentType.CLAUDE, "Claude Code"),
                    CreateAction(project, model, refresh, AgentType.CODEX, "Codex"),
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
    private val refresh: () -> Unit,
    private val agentType: AgentType,
    label: String,
) : AnAction(label) {

    override fun actionPerformed(event: AnActionEvent) {
        // 先登记再启动：startedAt 必须早于 CLI 可能的首次落盘，否则绑定会漏
        model.registerPending(agentType)
        TerminalHost.getInstance(project).openNew(agentType, "新会话")
        refresh()
    }
}

private class RefreshAction(private val refresh: () -> Unit) :
    AnAction("刷新", "重新扫描会话库", AllIcons.Actions.Refresh) {

    override fun actionPerformed(event: AnActionEvent) {
        refresh()
    }
}
