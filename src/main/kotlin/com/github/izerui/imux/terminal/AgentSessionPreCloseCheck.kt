package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.settings.ImuxSettings
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFilePreCloseCheck
import com.intellij.terminal.frontend.view.TerminalViewSessionState

/** Confirms user-initiated closure before the editor disposes and terminates live sessions. */
class AgentSessionPreCloseCheck : VirtualFilePreCloseCheck {
    override fun canCloseFile(file: VirtualFile): Boolean = canCloseFiles(listOf(file))

    override fun canCloseFiles(files: Collection<VirtualFile>): Boolean {
        val sessions =
            files
                .filterIsInstance<AgentTerminalVirtualFile>()
                .filter(::needsConfirmation)
                .distinct()
        if (sessions.isEmpty() || !ImuxSettings.getInstance().state.confirmBeforeClosingSession) {
            return true
        }

        val multiple = sessions.size > 1
        val titleKey =
            if (multiple) "session.close.confirm.title.multiple" else "session.close.confirm.title"
        val closeKey =
            if (multiple) "session.close.confirm.close.multiple" else "session.close.confirm.close"
        val message =
            if (!multiple) {
                ImuxBundle.message(
                    "session.close.confirm.message",
                    sessions.single().agentType.displayName,
                    sessions.single().tabTitle,
                )
            } else {
                ImuxBundle.message("session.close.confirm.message.multiple", sessions.size)
            }

        return MessageDialogBuilder
            .yesNo(ImuxBundle.message(titleKey), message)
            .yesText(ImuxBundle.message(closeKey))
            .noText(ImuxBundle.message("session.close.confirm.cancel"))
            .asWarning()
            .ask(sessions.first().terminalView.component)
    }

    private fun needsConfirmation(file: AgentTerminalVirtualFile): Boolean =
        file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) != true &&
            file.terminalView.sessionState.value !is TerminalViewSessionState.Terminated
}
