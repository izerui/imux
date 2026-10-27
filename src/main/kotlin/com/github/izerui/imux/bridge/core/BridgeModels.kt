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

enum class AttachmentKind { IMAGE, FILE, VIDEO }

/** 渠道取回并解密后的临时文件；调用方负责移走或删除。[suggestedName] 是平台给的原始文件名，未清理。 */
data class FetchedFile(
    val path: java.nio.file.Path,
    val suggestedName: String?,
)

sealed interface FetchResult {
    data class Ok(val file: FetchedFile) : FetchResult

    /** 超过上限；实现方不得留下临时文件。 */
    data object TooLarge : FetchResult

    data class Failed(val reason: String) : FetchResult
}

/** 入站附件。[fetch] 不得抛出 CancellationException 以外的异常。 */
interface InboundAttachment {
    val kind: AttachmentKind

    suspend fun fetch(maxBytes: Long): FetchResult
}

data class InboundMessage(
    val robotId: RobotId,
    /** 平台消息 id，用于抵御平台重投；不能用文本去重。 */
    val externalMessageId: String,
    val senderId: String,
    val chatId: String,
    val text: String,
    /** 渠道只解析不下载；路由器确认本人、非重复、已绑定后才调用 [InboundAttachment.fetch]。 */
    val attachments: List<InboundAttachment> = emptyList(),
    /** 用户引用的那条消息里的文字；没有引用或引用的不是文字时为 null。 */
    val quote: String? = null,
)

/**
 * 消息到达时的状态。[bindingEpoch] 为当时的绑定代次，未绑定为 null；
 * [switchGeneration] 为远程控制的开启代次，已关闭为 null。
 */
data class Arrival(
    val bindingEpoch: Long?,
    val switchGeneration: Long?,
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
    ATTACHMENT_FAILED,

    /** 排队或处理期间换绑：不提交到新终端，已提示本人重新发送。 */
    REBOUND,

    /** 排队或处理期间关闭过远程控制：不提交、不回复。 */
    SWITCHED_OFF,
}
