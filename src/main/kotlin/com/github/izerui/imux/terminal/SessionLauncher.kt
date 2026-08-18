package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.intellij.openapi.project.Project

/** Starts a tracked session, optionally sending an initial prompt to the interactive CLI. */
internal fun startNewSession(
    project: Project,
    agentType: AgentType,
    initialPrompt: String? = null,
    tabTitle: String = ImuxBundle.message("action.new.session.text"),
) {
    val monitor = SessionMonitor.getInstance(project)
    val sessionId = preassignedSessionId(agentType)
    // Register before launching so a CLI that writes immediately still falls inside the binding window.
    val pending = monitor.model.registerPending(agentType, sessionId)
    TerminalHost
        .getInstance(project)
        .openNew(agentType, pending.key, tabTitle, sessionId, initialPrompt)
    monitor.refresh()
}
