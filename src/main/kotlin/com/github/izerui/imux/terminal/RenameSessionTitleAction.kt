package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.session.normalizeManualTitle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.terminal.frontend.view.TerminalView

internal fun renameSessionTitleAction(
    project: Project,
    session: AgentSession,
): DumbAwareAction =
    object : DumbAwareAction(
        ImuxBundle.message("action.rename.title.text"),
        ImuxBundle.message("action.rename.title.description"),
        AllIcons.Actions.Edit,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = !SessionMonitor.getInstance(project).isRegeneratingTitle(session.id)
        }

        override fun actionPerformed(event: AnActionEvent) {
            promptRenameSessionTitle(project, session)
        }
    }

/**
 * 弹出以 CLI 存着的完整标题为初值的输入框，确认后交给 [SessionMonitor.renameTitle] 写回 CLI。
 *
 * 初值不能用 [AgentSession.title]，原因见 [com.github.izerui.imux.session.storedTitle]。
 */
internal fun promptRenameSessionTitle(
    project: Project,
    session: AgentSession,
) {
    val monitor = SessionMonitor.getInstance(project)
    monitor.loadEditableTitle(session) { original ->
        val input =
            Messages.showInputDialog(
                project,
                ImuxBundle.message("dialog.rename.title.message"),
                ImuxBundle.message("action.rename.title.text"),
                null,
                original,
                object : InputValidator {
                    override fun checkInput(inputString: String?): Boolean =
                        inputString != null && normalizeManualTitle(inputString) != null

                    override fun canClose(inputString: String?): Boolean = checkInput(inputString)
                },
            ) ?: return@loadEditableTitle
        monitor.renameTitle(session, input, original)
    }
}

/** Adds manual session-title renaming to the terminal content context menu. */
class RenameSessionTitleAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.text = ImuxBundle.message("action.rename.title.text")
        event.presentation.description = ImuxBundle.message("action.rename.title.description")
        event.presentation.icon = AllIcons.Actions.Edit
        val session = sourceSession(event)
        event.presentation.isEnabledAndVisible =
            session != null &&
                !SessionMonitor.getInstance(event.project!!).isRegeneratingTitle(session.id)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val session = sourceSession(event) ?: return
        promptRenameSessionTitle(event.project!!, session)
    }

    private fun sourceSession(event: AnActionEvent): AgentSession? {
        val project = event.project ?: return null
        val terminalView = event.getData(TerminalView.DATA_KEY) ?: return null
        val (_, sessionId) = TerminalHost.getInstance(project).sessionIdentityFor(terminalView) ?: return null
        return SessionMonitor.getInstance(project).model.sessionOf(sessionId)
    }
}
