package com.github.izerui.imux.clarification

import com.github.izerui.imux.agent.AgentProgressEvent
import com.github.izerui.imux.agent.AgentProgressKind
import com.github.izerui.imux.overview.toolActionText

/** 对话进度独立于会话轨迹；工具动作描述可共用，回复不能称为生成时间线。 */
internal fun clarificationProgressText(event: AgentProgressEvent, messages: ViewMessages = pluginMessages): String? =
    when (event.kind) {
        AgentProgressKind.STARTING -> messages.get("clarify.progress.starting")
        AgentProgressKind.THINKING -> messages.get("clarify.progress.thinking")
        AgentProgressKind.TOOL_STARTED -> toolActionText(event.subject)
        AgentProgressKind.RETRYING -> messages.get("clarify.progress.retrying", event.subject).trim()
        AgentProgressKind.RESPONDING -> messages.get("clarify.progress.responding")
        AgentProgressKind.TOOL_FINISHED, AgentProgressKind.COMPLETED, AgentProgressKind.FAILED -> null
    }
