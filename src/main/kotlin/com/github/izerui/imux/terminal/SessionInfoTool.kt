package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.SessionUsage
import com.github.izerui.imux.session.readSessionUsage
import com.github.izerui.imux.session.projectGitBranch
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.nio.file.Path
import javax.swing.JComponent

internal class SessionInfoTool(
    private val project: Project,
    private val file: AgentTerminalVirtualFile,
    private val parent: Disposable,
    private val buttonStrip: () -> JComponent?,
) : SessionTool {
    override fun state() = ToolState(AllIcons.General.ShowInfos, msg("session.info.title"), msg("session.info.runtime"))

    override fun perform(anchor: JComponent) {
        val key = file.sessionKey
        val monitor = SessionMonitor.getInstance(project)
        val host = TerminalHost.getInstance(project)
        lateinit var panel: SessionInfoPanel
        lateinit var popup: JBPopup
        var job: Job? = null
        val snapshot = SessionInfoSnapshot()
        fun render(usage: SessionUsage, branch: String? = null) {
            val number = NumberFormat.getIntegerInstance()
            val pi = file.piCapabilitiesFor(key)
            val coordinator = monitor.peerCoordinator
            val runtime = buildList {
                add(InfoRow(msg("session.info.cwd"), project.basePath ?: System.getProperty("user.home")))
                branch?.let { add(InfoRow(msg("session.info.branch"), it)) }
                usage.model?.let { add(InfoRow(msg("session.info.model"), it)) }
                usage.last?.let { add(InfoRow(msg("session.info.last"), number.format(it))) }
                usage.window?.let { add(InfoRow(msg("session.info.window"), number.format(it))) }
                add(InfoRow(msg("action.peer.group.text"), coordinator.status(key)?.targetAgentType?.displayName ?: msg("session.info.pairOff")))
                add(InfoRow(msg("action.peer.once.hint"), coordinator.scheduledReview(key)?.displayName ?: msg("session.info.unscheduled")))
                add(InfoRow(msg("bridge.tool.title"), msg(if (SessionDecorations.phoneConnected(project, file.tabId))
                    "session.info.bound" else "session.info.unbound")))
            }
            val services = host.configuredServerNames(file.tabId).map { name ->
                val description = host.serverLabelKey(file.tabId, name)?.let(::msg)
                    ?: if (name == "idea") "JetBrains IDE" else ""
                InfoRow(name, description, msg("session.info.configured"))
            } + host.unavailableTools(file.tabId).map {
                InfoRow(msg(it), msg("terminal.tools.retry"), msg("terminal.tools.unavailable"), failed = true)
            }
            val sections = buildList {
                add(InfoPage(msg("session.info.runtime"), runtime, searchable = false))
                add(InfoPage("MCP", services))
                val skillNames = pi?.skillNames ?: usage.skills.takeIf { usage.skillsReported }
                add(InfoPage(msg("session.info.skills"), skillNames.orEmpty().map {
                    InfoRow(it, detail = pi?.skillDescriptions?.get(it) ?: usage.skillDescriptions[it].orEmpty(),
                        location = pi?.skillLocations?.get(it) ?: usage.skillLocations[it])
                }, searchable = !skillNames.isNullOrEmpty(), reported = skillNames != null,
                    emptyKey = if (skillNames == null) "session.info.skillsUnknown" else "session.info.skillsEmpty"))
                if (file.agentType == AgentType.PI) {
                    val tools = pi?.activeTools
                    add(InfoPage(msg("session.info.tools"), tools.orEmpty().map(::InfoRow),
                        searchable = !tools.isNullOrEmpty(), reported = tools != null,
                        emptyKey = if (tools == null) "session.info.toolsUnknown" else "session.info.toolsEmpty"))
                }
            }
            val context = (pi?.contextPercent ?: usage.reportedContextPercent)?.let { "$it%" } ?: usage.window?.takeIf { it > 0 }?.let { window ->
                usage.last?.let { last -> "≈ " + NumberFormat.getPercentInstance().format(last.toDouble() / window) }
            } ?: msg("session.info.contextUnknown")
            panel.display(usage.total?.let(number::format) ?: msg("session.info.usagePending"), context, sections)
            javax.swing.SwingUtilities.invokeLater {
                if (popup.isDisposed || !panel.isShowing) return@invokeLater
                panel.validate()
                val screen = com.intellij.ui.ScreenUtil.getScreenRectangle(panel)
                val size = popup.size
                val available = screen.y + screen.height - panel.locationOnScreen.y - size.height - com.intellij.util.ui.JBUI.scale(4)
                val extra = minOf(panel.missingRuntimeHeight(), available)
                if (extra > 0) popup.setSize(java.awt.Dimension(size.width, size.height + extra))
            }
        }
        fun reload() {
            job?.cancel()
            if (file.sessionKey != key) { popup.cancel(); return }
            val transcript = monitor.model.sessionOf(key)?.takeIf { it.agentType == file.agentType }?.filePath
            panel.reading()
            job = file.terminalView.coroutineScope.launch(Dispatchers.IO) {
                val branch = project.basePath?.let { projectGitBranch(Path.of(it)) }
                val usage = try {
                    if (transcript == null) SessionUsage()
                    else readSessionUsage(file.agentType, transcript) { ensureActive() }
                } catch (_: java.io.IOException) { null }
                withContext(Dispatchers.EDT) {
                    if (!popup.isDisposed) {
                        when {
                            file.sessionKey != key -> popup.cancel()
                            else -> {
                                snapshot.update(usage, branch)
                                render(snapshot.usage, snapshot.branch)
                                if (snapshot.readFailed) panel.readFailed()
                            }
                        }
                    }
                }
            }
        }
        val contentLifetime = Disposer.newDisposable()
        panel = SessionInfoPanel(project, contentLifetime, file.tabTitle, "${file.agentType.displayName} · ${project.name}", AgentIcons.forAgent(file.agentType),
            ::reload, { popup.cancel() })
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel)
            .setRequestFocus(true).setResizable(true).createPopup()
        Disposer.register(parent, popup)
        Disposer.register(popup, contentLifetime)
        Disposer.register(popup, Disposable { job?.cancel() })
        project.messageBus.connect(popup).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                if (event.oldFile === file && event.newFile !== file) popup.cancel()
            }
        })
        render(SessionUsage())
        val strip = buttonStrip() ?: anchor
        val offset = java.awt.Point(strip.width - panel.preferredSize.width, strip.height + com.intellij.util.ui.JBUI.scale(4))
        popup.show(com.intellij.ui.awt.RelativePoint(strip, offset))
        reload()
    }

    private fun msg(key: String) = ImuxBundle.message(key)
}
