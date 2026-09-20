package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.settings.ImuxSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.view.TerminalView

internal fun peerProgrammingActions(
    project: Project,
    session: AgentSession,
    targetTypes: List<AgentType> = ImuxSettings.getInstance().enabledAgentTypes,
): Array<AnAction> {
    val monitor = SessionMonitor.getInstance(project)
    val coordinator = monitor.peerCoordinator
    val boundTarget = coordinator.boundTarget(session.id)

    val actions = mutableListOf<AnAction>()

    targetTypes.forEach { target ->
        actions += PeerBindAction(project, session, target, isBound = target == boundTarget)
    }

    if (boundTarget != null) {
        actions += Separator.getInstance()
        actions += PeerUnbindAction(project, session)
    }

    return actions.toTypedArray()
}

internal fun peerProgrammingActionGroup(
    project: Project,
    session: AgentSession,
    targetTypes: List<AgentType> = ImuxSettings.getInstance().enabledAgentTypes,
): ActionGroup =
    object : ActionGroup(ImuxBundle.message("action.peer.group.text"), true), DumbAware {
        init {
            templatePresentation.icon = AllIcons.Actions.ProfileCPU
        }

        private val children = peerProgrammingActions(project, session, targetTypes)

        override fun getChildren(event: AnActionEvent?): Array<AnAction> = children
    }

private class PeerBindAction(
    private val project: Project,
    private val session: AgentSession,
    private val target: AgentType,
    private val isBound: Boolean,
) : DumbAwareAction(
    if (isBound) "${target.displayName} ✓" else target.displayName,
    ImuxBundle.message("action.peer.target.description", target.displayName),
    AgentIcons.forAgent(target),
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(event: AnActionEvent) {
        val coordinator = SessionMonitor.getInstance(project).peerCoordinator
        if (isBound) {
            coordinator.unbind(session.id)
        } else {
            coordinator.bind(session.id, target)
        }
    }
}

private class PeerUnbindAction(
    private val project: Project,
    private val session: AgentSession,
) : DumbAwareAction(
    ImuxBundle.message("action.peer.disable.text"),
    ImuxBundle.message("action.peer.disable.text"),
    AllIcons.Actions.Cancel,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(event: AnActionEvent) {
        SessionMonitor.getInstance(project).peerCoordinator.unbind(session.id)
    }
}

/** Terminal context menu group — registered in plugin.xml. */
class PeerProgrammingActionGroup :
    ActionGroup(ImuxBundle.message("action.peer.group.text"), true),
    DumbAware {
    init {
        templatePresentation.icon = AllIcons.Actions.ProfileCPU
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.text = ImuxBundle.message("action.peer.group.text")
        event.presentation.icon = AllIcons.Actions.ProfileCPU
        event.presentation.isEnabledAndVisible = sourceSession(event) != null
    }

    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        val source = event?.let(::sourceSession) ?: return emptyArray()
        val project = event.project ?: return emptyArray()
        return peerProgrammingActions(project, source)
    }

    private fun sourceSession(event: AnActionEvent): AgentSession? {
        val project = event.project ?: return null
        val terminalView = event.getData(TerminalView.DATA_KEY) ?: return null
        val (_, sessionId) = TerminalHost.getInstance(project).sessionIdentityFor(terminalView) ?: return null
        return SessionMonitor.getInstance(project).model.sessionOf(sessionId)
    }
}
