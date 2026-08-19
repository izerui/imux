package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.settings.ImuxSettings
import com.github.izerui.imux.terminal.startNewSession
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** Adds the enabled AI agents to the Project view's New menu. */
class ProjectNewAgentActionGroup :
    ActionGroup(ImuxBundle.message("action.project.new.agent.group.text"), true),
    DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.text = ImuxBundle.message("action.project.new.agent.group.text")
        event.presentation.isEnabledAndVisible =
            event.project != null && ImuxSettings.getInstance().state.showProjectNewAgentMenu
    }

    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        val project = event?.project ?: return emptyArray()
        return agentActionGroup(
            agentTypes = ImuxSettings.getInstance().enabledAgentTypes,
            onChosen = { agentType -> startNewSession(project, agentType) },
        ).childActionsOrStubs
    }
}
