package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.monitor.SessionMonitor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.view.TerminalView

internal fun regenerateSessionTitleAction(
    project: Project,
    session: AgentSession,
): DumbAwareAction =
    object : DumbAwareAction(
        ImuxBundle.message("action.regenerate.title.text"),
        ImuxBundle.message("action.regenerate.title.description"),
        AllIcons.Actions.Refresh,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            val monitor = SessionMonitor.getInstance(project)
            event.presentation.isEnabled = !monitor.isRegeneratingTitle(session.id)
        }

        override fun actionPerformed(event: AnActionEvent) {
            SessionMonitor.getInstance(project).regenerateTitle(session)
        }
    }

/** Adds model-generated session-title regeneration to the terminal content context menu. */
class RegenerateSessionTitleAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.text = ImuxBundle.message("action.regenerate.title.text")
        event.presentation.description = ImuxBundle.message("action.regenerate.title.description")
        event.presentation.icon = AllIcons.Actions.Refresh
        val session = sourceSession(event)
        event.presentation.isEnabledAndVisible =
            session != null &&
                !SessionMonitor.getInstance(event.project!!).isRegeneratingTitle(session.id)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val session = sourceSession(event) ?: return
        SessionMonitor.getInstance(event.project!!).regenerateTitle(session)
    }

    private fun sourceSession(event: AnActionEvent): AgentSession? {
        val project = event.project ?: return null
        val terminalView = event.getData(TerminalView.DATA_KEY) ?: return null
        val (_, sessionId) = TerminalHost.getInstance(project).sessionIdentityFor(terminalView) ?: return null
        return SessionMonitor.getInstance(project).model.sessionOf(sessionId)
    }
}
