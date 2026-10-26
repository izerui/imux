package com.github.izerui.imux.bridge.core

import com.github.izerui.imux.model.AgentType

/** 机器人在 imux 内的稳定身份，跨渠道唯一，由机器人管理分配。 */
@JvmInline
value class RobotId(val value: String)

/**
 * 绑定目标。运行期按 [tabId] 定位终端（标签一生不变），
 * [sessionId] 用于项目重开后按原生会话重新挂接；新会话落盘前为 null。
 * [agentType] 只交给记录解码器，路由与渠道不得据此分支。
 */
data class SessionTarget(
    val projectPath: String,
    val tabId: String,
    val agentType: AgentType,
    val sessionId: String?,
)

data class InboundMessage(
    val robotId: RobotId,
    /** 平台消息 id，用于抵御平台重投；不能用文本去重。 */
    val externalMessageId: String,
    val senderId: String,
    val chatId: String,
    val text: String,
)

data class OutboundMessage(
    val robotId: RobotId,
    val chatId: String,
    /** 未绑定时的提示回复为 null。 */
    val bindingEpoch: Long?,
    val text: String,
)

enum class SessionEventKind { USER_TEXT, ASSISTANT_TEXT, TOOL_STARTED, TOOL_FINISHED }

data class SessionEvent(
    val kind: SessionEventKind,
    val text: String,
    val toolCallId: String? = null,
)

enum class SubmitResult { SUBMITTED_TO_TERMINAL, NOT_READY, OFFLINE }

sealed interface DeliveryResult {
    data class Accepted(val platformMessageId: String?) : DeliveryResult
    data class Rejected(val reason: String) : DeliveryResult
    data object Unknown : DeliveryResult
}

enum class InboundOutcome {
    NOT_PAIRED,
    UNAUTHORIZED,
    DUPLICATE,
    EMPTY,
    UNBOUND,
    SUBMITTED_TO_TERMINAL,
    NOT_READY,
    OFFLINE,
}
