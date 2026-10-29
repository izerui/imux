package com.github.izerui.imux.bridge.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * 渠道与终端之间的中介。两边互不认识，只通过绑定关系连接。
 *
 * 入站：校验本人 → 平台消息去重 → 找绑定 → 交给终端端口。不判断 Agent 忙闲，
 * 排队还是插入由 CLI 自己决定；这里只保证按到达顺序逐条提交。
 * 终端未就绪时在提交锁内有限重试，每次尝试前都复核绑定，换绑或解绑后立即停止。
 * 出站：每轮只推最终回复——最后一次工具调用之后的助手文本，在本轮结算时作为一条发出，
 * 中间的过程说明不推，避免逐条推送触发平台限频。轮次的划分与结算规则在 [TabState]；
 * 电脑端输入带标签单独转发；远程输入在记录中再次出现时不回显；等待确认立即推送。
 * 同一标签的 [onSessionEvents]、[onTurnCompleted] 由调用方串行调用。
 * 只做简单转接：换绑、断开的空档里漏掉的内容不补（见 AGENTS.md 手机桥接约定）。
 */
class BridgeRouter(
    private val bindings: BindingRegistry,
    private val ownerOf: (RobotId) -> RobotOwner?,
    private val sessionPort: TerminalSessionPort,
    private val channelOf: (RobotId) -> ChannelAdapter?,
    private val texts: BridgeTexts,
    private val onDelivery: (OutboundMessage, DeliveryResult) -> Unit = { _, _ -> },
    /** 入站附件落盘；默认不支持附件。 */
    private val attachmentStore: AttachmentStore = AttachmentStore.NONE,
    /** 终端未就绪时两次尝试之间的间隔；测试传 0。 */
    private val notReadyRetryDelayMs: Long = 300,
    /** 警告日志出口（core 不依赖 IDE 日志）；只传原因，不含路径、对端 id、正文与密钥。 */
    private val onWarning: (String) -> Unit = {},
) {
    /** 每个机器人一把提交锁：同一机器人按到达顺序提交，不同机器人互不阻塞。只在 [lock] 内取用。 */
    private val submitLocks = HashMap<RobotId, Mutex>()
    private val lock = Any()
    private val seen = HashMap<RobotId, LinkedHashSet<String>>()

    /** 各终端标签的轮次与回显状态，只在 [lock] 内访问。 */
    private val tabs = HashMap<String, TabState>()

    /** 加锁提交阶段的结果。 */
    private sealed interface SubmitPhase {
        data class Done(val result: SubmitResult) : SubmitPhase
        data object BindingGone : SubmitPhase
        data object BindingChanged : SubmitPhase
    }

    suspend fun onInbound(message: InboundMessage): InboundOutcome {
        val owner = ownerOf(message.robotId) ?: return InboundOutcome.NOT_PAIRED
        if (message.senderId != owner.senderId || message.chatId != owner.chatId) return InboundOutcome.UNAUTHORIZED
        if (!markSeen(message.robotId, message.externalMessageId)) return InboundOutcome.DUPLICATE

        val typed = message.text.trim()
        if (typed.isEmpty() && message.attachments.isEmpty()) {
            reply(message.robotId, owner, texts.emptyInput())
            return InboundOutcome.EMPTY
        }
        val binding = bindings.bindingOf(message.robotId)
        if (binding == null) {
            reply(message.robotId, owner, texts.unbound())
            return InboundOutcome.UNBOUND
        }
        // 确认本人、非重复、已绑定后才取回附件。下载放在提交锁之外，不阻塞其它机器人的提交；
        // 同一机器人的消息由上层按机器人逐条处理，下载期间该机器人的后续消息仍在排队
        val composed =
            if (message.attachments.isEmpty()) {
                typed
            } else {
                try {
                    withAttachments(message.robotId, owner, binding.target, typed, message.attachments)
                } catch (e: CancellationException) {
                    unmarkSeen(message.robotId, message.externalMessageId)
                    throw e
                }
            }
        val text = withQuote(message.quote, composed ?: return InboundOutcome.ATTACHMENT_FAILED)

        val phase =
            submitLock(message.robotId).withLock {
                // 加锁后复核绑定：排队期间可能已换绑或解绑
                val current = bindings.bindingOf(message.robotId)
                if (current == null) return@withLock SubmitPhase.BindingGone
                if (current.epoch != binding.epoch) return@withLock SubmitPhase.BindingChanged

                // 在锁内取定目标标签：之后所有回显登记与清除都用它，不再回读可能已换绑的绑定
                val tabId = current.target.tabId
                // 先登记再提交：CLI 可能在 submit 返回前就把这句写进记录
                tab(tabId) { expectEcho(text) }
                try {
                    val phase = submitWithRetry(message.robotId, current, text)
                    // 未提交成功，清除回显登记
                    if (phase != SubmitPhase.Done(SubmitResult.SUBMITTED_TO_TERMINAL)) tab(tabId) { forgetEcho(text) }
                    phase
                } catch (e: CancellationException) {
                    tab(tabId) { forgetEcho(text) }
                    unmarkSeen(message.robotId, message.externalMessageId)
                    throw e
                } catch (_: Exception) {
                    tab(tabId) { forgetEcho(text) }
                    SubmitPhase.Done(SubmitResult.OFFLINE)
                }
            }
        return when (phase) {
            SubmitPhase.BindingGone -> {
                reply(message.robotId, owner, texts.unbound())
                InboundOutcome.UNBOUND
            }
            SubmitPhase.BindingChanged -> {
                reply(message.robotId, owner, texts.rebound())
                InboundOutcome.REBOUND
            }
            is SubmitPhase.Done -> when (phase.result) {
                SubmitResult.SUBMITTED_TO_TERMINAL -> InboundOutcome.SUBMITTED_TO_TERMINAL
                SubmitResult.NOT_READY -> {
                    reply(message.robotId, owner, texts.notReady())
                    InboundOutcome.NOT_READY
                }
                SubmitResult.OFFLINE -> {
                    reply(message.robotId, owner, texts.offline())
                    InboundOutcome.OFFLINE
                }
            }
        }
    }

    /** 远程控制关闭时调用：丢弃尚未发出的回复与轮次状态，重新开启后不补发关闭前的内容。 */
    fun discardPending() {
        synchronized(lock) { tabs.clear() }
    }

    /** 引用的文字用 Markdown 引用块放在正文前，Agent 才知道“这里”指的是哪段。 */
    private fun withQuote(
        quote: String?,
        text: String,
    ): String {
        val quoted = quote?.trim()?.takeIf(String::isNotEmpty) ?: return text
        return quoted.lines().joinToString("\n") { "> $it".trimEnd() } + "\n\n" + text
    }

    /** 取回并落盘全部附件，拼装提交文字；附件全部失败且没有文字时返回 null。失败提示各回复一次。 */
    private suspend fun withAttachments(
        robotId: RobotId,
        owner: RobotOwner,
        target: SessionTarget,
        typed: String,
        attachments: List<InboundAttachment>,
    ): String? {
        val saved = mutableListOf<String>()
        var tooLarge = false
        var failed = false
        for (attachment in attachments) {
            val result =
                try {
                    attachmentStore.place(target, attachment)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PlaceResult.Failed(e.javaClass.simpleName)
                }
            when (result) {
                is PlaceResult.Saved -> saved += result.relativePath
                PlaceResult.TooLarge -> tooLarge = true
                is PlaceResult.Failed -> {
                    failed = true
                    onWarning("入站附件处理失败：reason=${result.reason}")
                }
            }
        }
        if (tooLarge) reply(robotId, owner, texts.attachmentTooLarge())
        if (failed) reply(robotId, owner, texts.attachmentFailed())
        return when {
            saved.isEmpty() -> typed.ifEmpty { null }
            typed.isEmpty() -> texts.attachmentsOnly(saved)
            else -> typed + "\n\n" + saved.joinToString("\n") { texts.attachmentLine(it) }
        }
    }

    /**
     * 须在这个机器人的提交锁内调用。终端未就绪时最多尝试 [NOT_READY_ATTEMPTS] 次；
     * 每次尝试前复核绑定代次，等待期间换绑或解绑则不再提交，按绑定已变处理。
     */
    private suspend fun submitWithRetry(
        robotId: RobotId,
        binding: Binding,
        text: String,
    ): SubmitPhase {
        repeat(NOT_READY_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(notReadyRetryDelayMs)
            if (!bindings.isCurrent(robotId, binding.epoch)) return SubmitPhase.BindingChanged
            val result = sessionPort.submit(binding.target, text)
            if (result != SubmitResult.NOT_READY) return SubmitPhase.Done(result)
        }
        return SubmitPhase.Done(SubmitResult.NOT_READY)
    }

    /** 读取期间换绑的，事件交给当前绑定；攒下的回复按代次作废，发出时只认产生它的那次绑定。 */
    suspend fun onSessionEvents(
        tabId: String,
        events: List<SessionEvent>,
    ) {
        val binding = bindings.bindingForTab(tabId) ?: return
        for (event in events) {
            when (event) {
                is SessionEvent.AssistantText -> tab(tabId) { appendReply(binding.epoch, event.text) }
                is SessionEvent.UserText -> {
                    // 上一轮没等到结算就开了新一轮：排着的状态先发，不能落到新一轮的输入后面
                    sendAfterReply(tabId)
                    val echo =
                        tab(tabId) {
                            startTurn()
                            consumeEcho(event.text)
                        }
                    if (!echo) send(binding, texts.localInputLabel() + "\n" + event.text)
                }
                SessionEvent.ToolStarted -> tab(tabId) { toolStarted() }
                SessionEvent.TurnStarted -> {
                    sendAfterReply(tabId)
                    tab(tabId) { startTurn() }
                }
                SessionEvent.TurnEnded -> {
                    sendReply(tabId, tab(tabId) { endByMarker() })
                    sendBackgroundRunning(tabId)
                    sendAfterReply(tabId)
                }
                is SessionEvent.BackgroundLaunched -> tab(tabId) { backgroundLaunched(event.toolUseId, event.description) }
                is SessionEvent.BackgroundStarted -> tab(tabId) { backgroundStarted(event.taskId, event.toolUseId, event.description) }
                is SessionEvent.BackgroundEnded -> tab(tabId) { backgroundEnded(event.taskId) }
            }
        }
        sendReply(tabId, tab(tabId) { takeIfSettled() })
    }

    /** 这个标签当前读到的轮次，供 IDE 完成信号标明它属于哪一轮。 */
    fun currentTurn(tabId: String): Long = synchronized(lock) { tabs[tabId]?.turn ?: 0L }

    /**
     * IDE 报告第 [turn] 轮完成：这一轮会话记录里没有结束标记时，以它为准把最终回复作为一条发出。
     * [turn] 不是当前轮（信号晚到，下一轮已经开始）或这一轮已由结束标记收尾时，什么也不做。
     */
    suspend fun onTurnCompleted(
        tabId: String,
        turn: Long,
    ) {
        val completed = tab(tabId) { completeByIde(turn) }
        if (completed) {
            sendReply(tabId, tab(tabId) { takeReply() })
            sendBackgroundRunning(tabId)
        }
        sendAfterReply(tabId)
    }

    /**
     * 说这一轮状态的提示（例如结对审查开始）：这一轮的回复已经发出就立即发，否则排在回复之后。
     * 回复晚到（结束标记还没写进记录）时，这样状态不会抢在回复前面。
     */
    suspend fun onAfterReply(
        tabId: String,
        text: String,
    ) {
        if (tab(tabId) { queueAfterReply(text) }) return
        onStatus(tabId, text)
    }

    /**
     * 完成信号到了但没能结算（读取边界不可靠）：后台任务提示照常补，排着的状态也不能一直压着。
     */
    suspend fun flushAfterReply(tabId: String) {
        sendBackgroundRunning(tabId)
        sendAfterReply(tabId)
    }

    /** 换了会话：之前记下的后台任务全部作废。 */
    fun resetBackground(tabId: String) = tab(tabId) { clearBackground() }

    private suspend fun sendAfterReply(tabId: String) {
        for (text in tab(tabId) { takeAfterReply() }) onStatus(tabId, text)
    }

    /**
     * 一轮结算后还有后台任务在运行：回复之后补一条，远程用户才知道这个会话还没真正做完。
     * 任务列表和上一次提示的一样就不重复。任务结束时 CLI 会再开一轮回应它，那一轮的回复照常发出，所以结束不另行提示。
     */
    private suspend fun sendBackgroundRunning(tabId: String) {
        val tasks = tab(tabId) { backgroundHint() } ?: return
        val binding = bindings.bindingForTab(tabId) ?: return
        send(binding, texts.backgroundRunning(tasks))
    }

    /** 发出本轮回复；换绑后旧绑定攒下的作废，不落到新目标的聊天里（切换空档漏消息可接受）。 */
    private suspend fun sendReply(
        tabId: String,
        reply: PendingReply?,
    ) {
        reply ?: return
        val binding = bindings.bindingForTab(tabId) ?: return
        if (binding.epoch != reply.epoch) return
        send(binding, reply.text.toString())
    }

    private fun submitLock(robotId: RobotId): Mutex = synchronized(lock) { submitLocks.getOrPut(robotId) { Mutex() } }

    private fun <T> tab(
        tabId: String,
        block: TabState.() -> T,
    ): T = synchronized(lock) { tabs.getOrPut(tabId) { TabState() }.block() }

    /** 推一条状态（例如结对审查开始、结束）到绑定这个标签的手机。 */
    suspend fun onStatus(
        tabId: String,
        text: String,
    ) {
        val binding = bindings.bindingForTab(tabId) ?: return
        send(binding, text)
    }

    suspend fun onWaiting(
        tabId: String,
        reason: String?,
    ) {
        val binding = bindings.bindingForTab(tabId) ?: return
        send(binding, texts.waiting(reason))
    }

    suspend fun onTranscriptRestarted(tabId: String) {
        resetBackground(tabId)
        val binding = bindings.bindingForTab(tabId) ?: return
        send(binding, texts.transcriptRestarted())
    }

    /**
     * 一次连接或换绑要发的提示，在同一个协程里按顺序发出：先给每个被断开的会话发断开提示，
     * 再给新会话发连接提示。前一条在限频上等待时后一条不能抢先，否则手机最后看到的是“已断开”。
     * [bound] 为 null 表示这次绑定没成功，只发断开提示。
     */
    suspend fun onRebound(
        released: List<Pair<Binding, String>>,
        bound: Binding?,
        project: String,
        agent: String,
        sessionTitle: String,
    ) {
        released.forEach { (binding, title) -> onReleased(binding, title) }
        bound?.let { onBound(it, project, agent, sessionTitle) }
    }

    /** 用户把机器人绑到会话后，告诉本人当前连接到哪里。走正常的代次校验。 */
    suspend fun onBound(
        binding: Binding,
        project: String,
        agent: String,
        sessionTitle: String,
    ) {
        send(binding, texts.connected(project, agent, sessionTitle))
    }

    /**
     * 绑定已被移除后调用：不再做 epoch 校验，只向原聊天发断开提示。
     * 释放时没有完成信号，缓存里只是过程说明，不发；旧代次的缓存之后按代次作废。
     */
    suspend fun onReleased(
        binding: Binding,
        sessionTitle: String,
    ) {
        val tabId = binding.target.tabId
        synchronized(lock) {
            // 换绑时新绑定先建立、旧绑定的清理后执行：标签已被接管时，它的状态属于新绑定
            if (bindings.bindingForTab(tabId) == null) tabs.remove(tabId)
        }
        val owner = ownerOf(binding.robotId) ?: return
        val channel = channelOf(binding.robotId) ?: return
        deliver(channel, OutboundMessage(binding.robotId, owner.chatId, texts.released(sessionTitle)))
    }

    /** 每段发送前都核对：分段途中换绑，剩余内容不落到新目标的聊天里。 */
    private suspend fun send(
        binding: Binding,
        text: String,
    ) {
        val owner = ownerOf(binding.robotId) ?: return
        val channel = channelOf(binding.robotId) ?: return
        for (segment in TextSegmenter.split(text, channel.maxTextBytes)) {
            // 每段发送前都核对：分段途中被换绑，剩余内容不能落到新目标的聊天里
            if (!bindings.isCurrent(binding.robotId, binding.epoch)) return
            deliver(channel, OutboundMessage(binding.robotId, owner.chatId, segment))
        }
    }

    private suspend fun reply(
        robotId: RobotId,
        owner: RobotOwner,
        text: String,
    ) {
        val channel = channelOf(robotId) ?: return
        deliver(channel, OutboundMessage(robotId, owner.chatId, text))
    }

    private suspend fun deliver(
        channel: ChannelAdapter,
        message: OutboundMessage,
    ) {
        val result =
            try {
                channel.send(message)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 渠道异常不能冒泡到终端或监控；结果不明，交给上层显示，不自动重发
                DeliveryResult.Unknown
            }
        onDelivery(message, result)
    }

    private fun markSeen(
        robotId: RobotId,
        externalMessageId: String,
    ): Boolean =
        synchronized(lock) {
            val ids = seen.getOrPut(robotId) { LinkedHashSet() }
            if (!ids.add(externalMessageId)) return false
            while (ids.size > SEEN_CAPACITY) ids.remove(ids.first())
            true
        }

    private fun unmarkSeen(
        robotId: RobotId,
        externalMessageId: String,
    ) = synchronized(lock) {
        seen[robotId]?.remove(externalMessageId)
    }

    private companion object {
        const val SEEN_CAPACITY = 1000
        const val NOT_READY_ATTEMPTS = 3
    }
}
