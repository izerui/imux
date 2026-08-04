package com.github.liuyuhua.imux.toolwindow

import com.github.liuyuhua.imux.model.AgentType
import com.github.liuyuhua.imux.session.ClaudeSessionReader
import com.github.liuyuhua.imux.session.SessionListModel
import com.github.liuyuhua.imux.session.SessionRepository
import com.github.liuyuhua.imux.terminal.TerminalHost
import com.github.liuyuhua.imux.watch.SessionStoreWatcher
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
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
        timed("构建工具窗口内容", CREATE_WARN_MS) { doCreateContent(project, toolWindow) }
    }

    private fun doCreateContent(project: Project, toolWindow: ToolWindow) {
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
                    if (visible && !wasVisible) refresh()
                    wasVisible = visible
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
                        timed("后台扫描会话库", SCAN_WARN_MS) {
                            runCatching { repository.scan(projectPath) }.getOrNull()
                        }
                    } finally {
                        scanning.set(false)
                    }
                    if (scanned != null) {
                        ApplicationManager.getApplication().invokeLater {
                            timed("应用扫描结果并重建列表", EDT_WARN_MS) { model.applyScan(scanned) }
                        }
                    }
                }
            }
        }
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
        const val SCAN_WARN_MS = 150L
        const val EDT_WARN_MS = 30L
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
        val popup = JBPopupFactory.getInstance()
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

        // 贴着触发它的工具栏按钮弹出。
        // 不能只用 showInBestPositionFor：工具栏按钮的 dataContext 不携带触发组件，
        // 平台拿不到锚点就会退回到面板中央偏下的默认位置。
        val anchor = event.inputEvent?.component
        if (anchor != null) popup.showUnderneathOf(anchor) else popup.showInBestPositionFor(event.dataContext)
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
        val pending = model.registerPending(agentType)
        // 终端必须以 pending.key 记录：会话落盘后要靠这个 key 把终端迁到真实 id 下
        TerminalHost.getInstance(project).openNew(agentType, pending.key, "新会话")
        refresh()
    }
}

private class RefreshAction(private val refresh: () -> Unit) :
    AnAction("刷新", "重新扫描会话库", AllIcons.Actions.Refresh) {

    override fun actionPerformed(event: AnActionEvent) {
        refresh()
    }
}
