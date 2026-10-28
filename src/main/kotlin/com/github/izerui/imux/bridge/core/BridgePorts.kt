package com.github.izerui.imux.bridge.core

/** 机器人的本人身份：只接受该发送者在该聊天中的消息，输出也只发往该聊天。 */
data class RobotOwner(
    val senderId: String,
    val chatId: String,
)

/** 渠道适配器。只做收发，不认识 Agent、终端和绑定。 */
interface ChannelAdapter {
    val robotId: RobotId

    /** 单条文本允许的最大 UTF-8 字节数，取自对应平台限制，不得小于 [TextSegmenter.MIN_SEGMENT_BYTES]。 */
    val maxTextBytes: Int

    suspend fun send(message: OutboundMessage): DeliveryResult
}

/** 渠道收到消息后交给桥接的入口。实现方保证按调用顺序处理。 */
fun interface InboundSink {
    fun accept(message: InboundMessage)
}

/** 向现有终端提交一条文字。实现方不得启动或恢复 CLI。 */
fun interface TerminalSessionPort {
    suspend fun submit(
        target: SessionTarget,
        text: String,
    ): SubmitResult
}

/** 发往手机的提示文案，由资源包实现，测试用假实现。 */
interface BridgeTexts {
    fun unbound(): String

    fun offline(): String

    /** 消息排队期间会话连接已切换，这条没有发送。 */
    fun rebound(): String

    fun notReady(): String

    fun emptyInput(): String

    fun localInputLabel(): String

    fun waiting(reason: String?): String

    fun released(sessionTitle: String): String

    fun transcriptRestarted(): String

    fun connected(
        project: String,
        agent: String,
        sessionTitle: String,
    ): String

    /** 提交给终端的附件行，例如 `[附件] .imux/attachments/2026-10-27/a.png`。 */
    fun attachmentLine(path: String): String

    /** 只有附件、没有文字时提交给终端的内容。 */
    fun attachmentsOnly(paths: List<String>): String

    fun attachmentFailed(): String

    fun attachmentTooLarge(): String
}

sealed interface PlaceResult {
    /** [relativePath] 相对项目根，分隔符为 `/`。 */
    data class Saved(val relativePath: String) : PlaceResult

    data object TooLarge : PlaceResult

    data class Failed(val reason: String) : PlaceResult
}

/** 把入站附件放到目标会话能读到的位置。不得抛出 CancellationException 以外的异常。 */
fun interface AttachmentStore {
    suspend fun place(
        target: SessionTarget,
        attachment: InboundAttachment,
    ): PlaceResult

    companion object {
        const val MAX_BYTES: Long = 100L * 1024 * 1024

        /** 不支持附件时的默认实现。 */
        val NONE = AttachmentStore { _, _ -> PlaceResult.Failed("unsupported") }
    }
}
