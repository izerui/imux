package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.settings.ImuxSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.terminal.frontend.view.TerminalView

class ReviewOnceAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.text = ImuxBundle.message("action.peer.once.hint")
        val project = event.project
        val file = fileOf(event)
        event.presentation.isVisible = file != null
        event.presentation.isEnabled =
            project != null && !project.isDisposed && file != null &&
                    ImuxSettings.getInstance().enabledAgentTypes.isNotEmpty() &&
                    SessionMonitor.getInstance(project).peerCoordinator.boundTarget(file.sessionKey) == null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = fileOf(event) ?: return
        showReviewOnceDialog(project, file)
    }

    private fun fileOf(event: AnActionEvent): AgentTerminalVirtualFile? {
        val project = event.project?.takeUnless { it.isDisposed } ?: return null
        val view = event.getData(TerminalView.DATA_KEY) ?: return null
        return TerminalHost.getInstance(project).fileFor(view)
    }
}
