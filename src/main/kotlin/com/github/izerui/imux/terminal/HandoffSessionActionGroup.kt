package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.settings.ImuxSettings
import com.github.izerui.imux.settings.PluginLanguage
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.view.TerminalView

internal fun handoffPrompt(
    session: AgentSession,
    language: PluginLanguage = ImuxBundle.currentLanguage(),
): String =
    ImuxBundle.message(
        language,
        "handoff.prompt",
        sessionClipboardText(session.agentType, session.id, language),
    )

internal fun handoffActions(
    project: Project,
    session: AgentSession,
    targetTypes: List<AgentType> = ImuxSettings.getInstance().enabledAgentTypes,
): Array<AnAction> =
    targetTypes
        .map { target -> HandoffSessionAction(project, session, target) }
        .toTypedArray()

internal fun handoffActionGroup(
    project: Project,
    session: AgentSession,
    targetTypes: List<AgentType> = ImuxSettings.getInstance().enabledAgentTypes,
): ActionGroup =
    object : ActionGroup(ImuxBundle.message("action.handoff.group.text"), true), DumbAware {
        init {
            templatePresentation.icon = AllIcons.Actions.MoveTo2
        }

        private val children = handoffActions(project, session, targetTypes)

        override fun getChildren(event: AnActionEvent?): Array<AnAction> = children
    }

private class HandoffSessionAction(
    private val project: Project,
    private val source: AgentSession,
    private val target: AgentType,
) : DumbAwareAction(
        target.displayName,
        ImuxBundle.message("action.handoff.target.description", target.displayName),
        AgentIcons.forAgent(target),
    ) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.text = target.displayName
        event.presentation.description = ImuxBundle.message("action.handoff.target.description", target.displayName)
    }

    override fun actionPerformed(event: AnActionEvent) {
        startNewSession(
            project,
            target,
            initialPrompt = handoffPrompt(source),
            tabTitle = ImuxBundle.message("action.handoff.tab.title", target.displayName),
        )
    }
}

/** Contributes handoff commands for enabled agents to the reworked terminal context menu. */
class HandoffSessionActionGroup :
    ActionGroup(ImuxBundle.message("action.handoff.group.text"), true),
    DumbAware {
    init {
        templatePresentation.icon = AllIcons.Actions.MoveTo2
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.text = ImuxBundle.message("action.handoff.group.text")
        event.presentation.icon = AllIcons.Actions.MoveTo2
        event.presentation.isEnabledAndVisible = sourceSession(event) != null
    }

    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        val source = event?.let(::sourceSession) ?: return emptyArray()
        val project = event.project ?: return emptyArray()
        return handoffActions(project, source)
    }

    private fun sourceSession(event: AnActionEvent): AgentSession? {
        val project = event.project ?: return null
        val terminalView = event.getData(TerminalView.DATA_KEY) ?: return null
        val (_, sessionId) = TerminalHost.getInstance(project).sessionIdentityFor(terminalView) ?: return null
        return SessionMonitor.getInstance(project).model.sessionOf(sessionId)
    }
}
