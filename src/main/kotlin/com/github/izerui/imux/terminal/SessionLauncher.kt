package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.intellij.openapi.project.Project

/**
 * 启动受跟踪的会话，并可向交互式 CLI 发送初始提示词。
 *
 * pending 必须先于终端创建登记：CLI 可能启动后立即落下首条会话记录，下一次扫描只有在
 * pending 已存在时才能把该记录绑定到当前终端。
 */
internal fun startNewSession(
    project: Project,
    agentType: AgentType,
    initialPrompt: String? = null,
    tabTitle: String = ImuxBundle.message("action.new.session.text"),
) {
    val monitor = SessionMonitor.getInstance(project)
    val sessionId = preassignedSessionId(agentType)
    val pending = monitor.model.registerPending(agentType, sessionId)
    TerminalHost
        .getInstance(project)
        .openNew(agentType, pending.key, tabTitle, sessionId, initialPrompt)
    monitor.refresh()
}
