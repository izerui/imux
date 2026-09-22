package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.monitor.DeleteResult
import com.github.izerui.imux.monitor.SessionMonitor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages

internal fun deleteSessionAction(
    project: Project,
    monitor: SessionMonitor,
    session: AgentSession,
    running: Boolean,
): DumbAwareAction =
    object : DumbAwareAction(
        ImuxBundle.message("action.delete.session.text"),
        ImuxBundle.message("action.delete.session.description"),
        AllIcons.General.Remove,
    ) {
        override fun actionPerformed(event: AnActionEvent) {
            if (monitor.isRunningWithoutTab(session.id)) {
                Messages.showWarningDialog(
                    project,
                    ImuxBundle.message("session.delete.blocked.background", session.title),
                    ImuxBundle.message("session.delete.confirm.title"),
                )
                return
            }

            val messageKey =
                if (running) "session.delete.confirm.message.running" else "session.delete.confirm.message"
            val confirmed =
                MessageDialogBuilder
                    .yesNo(
                        ImuxBundle.message("session.delete.confirm.title"),
                        ImuxBundle.message(messageKey, session.agentType.displayName, session.title),
                    ).yesText(ImuxBundle.message("session.delete.confirm.delete"))
                    .noText(ImuxBundle.message("session.delete.confirm.cancel"))
                    .asWarning()
                    .ask(project)
            if (!confirmed) return

            when (monitor.deleteSession(session)) {
                DeleteResult.ACCEPTED -> Unit
                DeleteResult.RUNNING_WITHOUT_TAB ->
                    Messages.showWarningDialog(
                        project,
                        ImuxBundle.message("session.delete.blocked.background", session.title),
                        ImuxBundle.message("session.delete.confirm.title"),
                    )
                DeleteResult.CLOSE_REJECTED -> Unit
            }
        }
    }
