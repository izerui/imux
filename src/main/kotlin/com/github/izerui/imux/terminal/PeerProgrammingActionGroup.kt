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

/**
 * 结对编程的子菜单 actions。sessionId 可以是 pending key 或真实 id，
 * 不要求会话已落盘。
 */
internal fun peerProgrammingActions(
    project: Project,
    sessionId: String,
    targetTypes: List<AgentType> = ImuxSettings.getInstance().enabledAgentTypes,
): Array<AnAction> {
    val coordinator = SessionMonitor.getInstance(project).peerCoordinator
    val boundTarget = coordinator.boundTarget(sessionId)

    val actions = mutableListOf<AnAction>()
    targetTypes.forEach { target ->
        actions += PeerBindAction(project, sessionId, target, isBound = target == boundTarget)
    }
    if (boundTarget != null) {
        actions += Separator.getInstance()
        actions += PeerUnbindAction(project, sessionId)
    }
    return actions.toTypedArray()
}

/** 供树形列表右键菜单使用。 */
internal fun peerProgrammingActionGroup(
    project: Project,
    session: AgentSession,
    targetTypes: List<AgentType> = ImuxSettings.getInstance().enabledAgentTypes,
): ActionGroup = peerProgrammingActionGroup(project, session.id, targetTypes)

/** 供尚未落盘的 pending 会话使用。 */
internal fun peerProgrammingActionGroup(
    project: Project,
    sessionKey: String,
    targetTypes: List<AgentType> = ImuxSettings.getInstance().enabledAgentTypes,
): ActionGroup =
    object : ActionGroup(ImuxBundle.message("action.peer.group.text"), true), DumbAware {
        init {
            templatePresentation.icon = AllIcons.Actions.ProfileCPU
        }

        private val children = peerProgrammingActions(project, sessionKey, targetTypes)

        override fun getChildren(event: AnActionEvent?): Array<AnAction> = children
    }

private class PeerBindAction(
    private val project: Project,
    private val sessionId: String,
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
            coordinator.unbind(sessionId)
        } else {
            coordinator.bind(sessionId, target)
        }
    }
}

private class PeerUnbindAction(
    private val project: Project,
    private val sessionId: String,
) : DumbAwareAction(
    ImuxBundle.message("action.peer.disable.text"),
    ImuxBundle.message("action.peer.disable.text"),
    AllIcons.Actions.Cancel,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(event: AnActionEvent) {
        SessionMonitor.getInstance(project).peerCoordinator.unbind(sessionId)
    }
}

/**
 * Terminal context menu group — registered in plugin.xml.
 *
 * 只需要 sessionIdentity（agentType + sessionId），不要求会话已落盘。
 * 新建会话在 CLI 还没写下第一条记录时就能开启结对编程。
 */
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
        event.presentation.isEnabledAndVisible = sessionKey(event) != null
    }

    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        val sessionKey = event?.let(::sessionKey) ?: return emptyArray()
        val project = event.project ?: return emptyArray()
        return peerProgrammingActions(project, sessionKey)
    }

    private fun sessionKey(event: AnActionEvent): String? {
        val project = event.project ?: return null
        val terminalView = event.getData(TerminalView.DATA_KEY) ?: return null
        return TerminalHost.getInstance(project).sessionKeyFor(terminalView)
    }
}
