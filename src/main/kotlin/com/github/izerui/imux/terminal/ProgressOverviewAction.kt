package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.overview.showProgressOverviewPopup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.terminal.frontend.view.TerminalView

class ProgressOverviewAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.text = ImuxBundle.message("action.overview.text")
        event.presentation.description = ImuxBundle.message("action.overview.description")
        event.presentation.isEnabledAndVisible = resolveSessionKey(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val sessionKey = resolveSessionKey(event) ?: return
        val project = event.project ?: return
        val component = event.inputEvent?.component ?: return
        showProgressOverviewPopup(project, sessionKey, component)
    }

    private fun resolveSessionKey(event: AnActionEvent): String? {
        val terminalView = event.getData(TerminalView.DATA_KEY) ?: return null
        val project = event.project ?: return null
        return TerminalHost.getInstance(project).sessionKeyFor(terminalView)
    }
}
