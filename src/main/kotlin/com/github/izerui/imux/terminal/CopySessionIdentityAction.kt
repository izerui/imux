package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.terminal.frontend.view.TerminalView

internal fun sessionClipboardText(
    agentType: AgentType,
    sessionId: String,
): String = "Session type: ${agentType.displayName}\nSession ID: $sessionId"

/** Adds the imux session identity to the terminal content context menu. */
class CopySessionIdentityAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = sessionIdentity(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val (agentType, sessionId) = sessionIdentity(event) ?: return
        CopyPasteManager.copyTextToClipboard(sessionClipboardText(agentType, sessionId))
    }

    private fun sessionIdentity(event: AnActionEvent): Pair<AgentType, String>? {
        val terminalView = event.getData(TerminalView.DATA_KEY) ?: return null
        val project = event.project ?: return null
        return TerminalHost.getInstance(project).sessionIdentityFor(terminalView)
    }
}
