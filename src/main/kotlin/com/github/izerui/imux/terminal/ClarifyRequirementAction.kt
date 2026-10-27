package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.clarification.ClarificationService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.terminal.frontend.view.TerminalView

/** 终端正文右键菜单的「先聊再做」，在 plugin.xml 注册。 */
class ClarifyRequirementAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.text = ImuxBundle.message("clarify.action.text")
        event.presentation.description = ImuxBundle.message("clarify.action.description")
        event.presentation.icon = AllIcons.Actions.IntentionBulb
        event.presentation.isEnabledAndVisible = fileOf(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = fileOf(event) ?: return
        ClarificationService.getInstance(project).open(file.sessionKey, file.tabId, file.agentType)
    }

    private fun fileOf(event: AnActionEvent): AgentTerminalVirtualFile? {
        val project = event.project ?: return null
        val view = event.getData(TerminalView.DATA_KEY) ?: return null
        return TerminalHost.getInstance(project).fileFor(view)
    }
}
