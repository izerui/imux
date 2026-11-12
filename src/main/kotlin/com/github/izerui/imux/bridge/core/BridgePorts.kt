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

    /** 内部分段后若已发送前缀，限频时须返回 [DeliveryResult.RetryRemaining]，不能表示整条被拒。 */
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

    /** 计划确认：[plan] 是 Claude 写好的计划全文（Markdown），后面附上怎么在手机上回答。 */
    fun plan(plan: String): String

    /** 终端停在手机无法作答的框上，手机发来的话没有贴进终端。 */
    fun dialogOpen(): String

    /** 一道题排成的列表 [listing]，后面附上怎么回；不止一题时标明是第 [number] 题、共 [total] 题。 */
    fun question(
        listing: String,
        number: Int,
        total: Int,
    ): String

    /** 跟在多选题题目后面的标注。 */
    fun multiSelectMark(): String

    /**
     * 推给手机的提问 [step]（见 [HookRequest.step]）已在电脑上作答；[answer] 是每题「问题 → 答案」，已写明是哪道题，不再带 [step]；
     * 拿不到时为 null，改说 [step] 已处理。
     */
    fun promptAnsweredLocally(step: String, answer: String?): String

    /** 推给手机的计划 [step] 已在电脑上批准。 */
    fun planApprovedLocally(step: String): String

    /** 推给手机的提问或计划 [step] 已在电脑上处理（改稿后被新计划替换、新提问替换旧提问等，说不出具体选了什么）。 */
    fun promptHandledLocally(step: String): String

    /** 推给手机的确认还挂着时回复带了附件，没有作答。 */
    fun promptRejected(): String

    /** [session] 是写好的「💬 工程 · 智能体 · 标题」，与连接提示同一写法。 */
    fun released(session: String): String

    /** 工程或 IDE 关闭：绑定还在，重新打开后自动恢复，与解绑的 [released] 区分。 */
    fun suspended(session: String): String

    fun transcriptRestarted(): String

    /** 一轮结束了，但没有文字回复（只调了工具、只执行了命令）。 */
    fun turnWithoutReply(): String

    /** 本轮失败，只展示简短原因，不把异常终止当成成功。 */
    fun turnFailed(reason: String): String

    /** 这一轮在电脑上被中断。 */
    fun turnInterrupted(): String

    /** 一轮回复发出后还有后台任务在运行；[tasks] 是它们的说明，没有说明的为 null。 */
    fun backgroundRunning(tasks: List<String?>): String

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

    /** hook 送来的权限确认：[listing] 是排好版的工具与要批准的内容；手机只能允许这一次、拒绝或说要求。 */
    fun hookPermission(listing: String): String

    /** 推送的确认后面还有 [count] 个在排队。 */
    fun hookQueued(count: Int): String

    /** 手机说的话被当作对权限请求 [step] 的回复：已拒绝这一步，原话转给了 Claude。选了选项（允许、拒绝）不回执。 */
    fun hookWordsForwarded(step: String): String

    /** 手机说的话被当作对计划的修改意见，转给了 Claude。选了选项（1、2、不执行）不回执。 */
    fun hookPlanRevising(): String

    /** 计划确认回了「好」「ok」这类允许词：没有作答，提示回 1 或 2 选择怎么执行。 */
    fun hookPlanChoose(): String

    /** 有等确认的请求时手机发了别的话：上面就是那个请求（刚补推过），请先回答；这句没有发给会话。 */
    fun hookAnswerFirst(): String

    /** 推给手机的权限请求 [step] 已在电脑上允许。 */
    fun hookAllowedLocally(step: String): String

    /** 推给手机的权限请求 [step] 已在电脑上拒绝或按 Esc 取消（hook 连接被关闭）。 */
    fun hookDeniedLocally(step: String): String

    /**
     * 推给手机的确认 [step] 没等到手机回复就过去了：记录里已有它的出错结果（电脑上拒绝，或等太久被 Claude Code 自动拒绝），
     * 或它所在的一轮结束、中断，或发起它的子代理结束。告诉手机不用再回。
     */
    fun hookExpired(step: String): String

    /** 手机回复了权限请求 [step]，但回写时它已在电脑上拒绝或取消：回复没有生效。 */
    fun hookTooLate(step: String): String
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
