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
 * 出站：每轮只推最终回复——最后一次工具调用之后的助手文本，在轮次完成时作为一条发出，
 * 中间的过程说明不推，避免逐条推送触发平台限频。一轮是否结束只看完成信号（与 IDE 的“本轮完成”提醒同源），
 * 轮次进行中有人插话或注入消息，此前攒的都是过程说明，直接丢弃；电脑端输入带标签单独转发；
 * 远程输入在记录中再次出现时不回显；等待确认立即推送。
 * 换绑前后的回复宁可发到新机器人也不丢；状态提示（当前连接、等待确认等）在绑定已变时不发。
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
    /** 远程控制的开启代次：每次开启换新，关闭时为 null。提交前据此确认期间没有关闭过。 */
    private val switchGeneration: () -> Long? = { 0L },
) {
    private val submitLock = Mutex()
    private val lock = Any()
    private val seen = HashMap<RobotId, LinkedHashSet<String>>()
    private val pendingRemoteEcho = HashMap<String, ArrayDeque<String>>()

    /** 每个标签本轮待发的最终回复，连同产生它时的绑定代次；换绑后作废。 */
    private val pendingFinal = HashMap<String, PendingFinal>()

    /** 已报完成、尚未开始下一轮的标签：此后读到的回复是迟到的最终输出，立即发出。 */
    private val completedTabs = HashSet<String>()

    private class PendingFinal(
        val epoch: Long,
        val text: StringBuilder,
    )

    /** 加锁提交阶段的结果。 */
    private sealed interface SubmitPhase {
        data class Done(val result: SubmitResult) : SubmitPhase
        data object BindingGone : SubmitPhase
        data object BindingChanged : SubmitPhase
        data object SwitchedOff : SubmitPhase
    }

    /** 消息到达时调用，记下当时的绑定与开关；排队之后按它判断消息是否仍然有效。 */
    fun arrival(robotId: RobotId): Arrival = Arrival(bindings.bindingOf(robotId)?.epoch, switchGeneration())

    /**
     * [arrival] 须在消息到达时取得：排队或下载附件期间关闭过远程控制的不提交、不回复；
     * 期间换绑的不提交到新终端，回复本人重新发送。
     */
    suspend fun onInbound(
        message: InboundMessage,
        arrival: Arrival = arrival(message.robotId),
    ): InboundOutcome {
        if (!stillOn(arrival)) return InboundOutcome.SWITCHED_OFF
        val owner = ownerOf(message.robotId) ?: return InboundOutcome.NOT_PAIRED
        if (message.senderId != owner.senderId || message.chatId != owner.chatId) return InboundOutcome.UNAUTHORIZED
        if (!markSeen(message.robotId, message.externalMessageId)) return InboundOutcome.DUPLICATE

        val typed = message.text.trim()
        if (typed.isEmpty() && message.attachments.isEmpty()) {
            reply(message.robotId, owner, texts.emptyInput())
            return InboundOutcome.EMPTY
        }
        val binding = bindings.bindingOf(message.robotId)
        if (binding == null || arrival.bindingEpoch == null) {
            reply(message.robotId, owner, texts.unbound())
            return InboundOutcome.UNBOUND
        }
        if (binding.epoch != arrival.bindingEpoch) {
            reply(message.robotId, owner, texts.rebound())
            return InboundOutcome.REBOUND
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
            submitLock.withLock {
                // 加锁后复核绑定：排队期间可能已换绑或解绑
                val current = bindings.bindingOf(message.robotId)
                if (!stillOn(arrival)) return@withLock SubmitPhase.SwitchedOff
                if (current == null) return@withLock SubmitPhase.BindingGone
                if (current.epoch != binding.epoch) return@withLock SubmitPhase.BindingChanged

                // 在锁内取定目标标签：之后所有回显登记与清除都用它，不再回读可能已换绑的绑定
                val tabId = current.target.tabId
                // 先登记再提交：CLI 可能在 submit 返回前就把这句写进记录
                rememberRemote(tabId, text)
                try {
                    val phase = submitWithRetry(message.robotId, current, text, arrival)
                    // 未提交成功，清除回显登记
                    if (phase != SubmitPhase.Done(SubmitResult.SUBMITTED_TO_TERMINAL)) forgetRemote(tabId, text)
                    phase
                } catch (e: CancellationException) {
                    forgetRemote(tabId, text)
                    unmarkSeen(message.robotId, message.externalMessageId)
                    throw e
                } catch (_: Exception) {
                    forgetRemote(tabId, text)
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
            SubmitPhase.SwitchedOff -> InboundOutcome.SWITCHED_OFF
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
        synchronized(lock) {
            pendingFinal.clear()
            completedTabs.clear()
            pendingRemoteEcho.clear()
        }
    }

    private fun stillOn(arrival: Arrival): Boolean = arrival.switchGeneration != null && switchGeneration() == arrival.switchGeneration

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
     * 须在 submitLock 内调用。终端未就绪时最多尝试 [NOT_READY_ATTEMPTS] 次；
     * 每次尝试前复核开关与绑定代次，等待期间关闭、换绑或解绑则不再提交。
     */
    private suspend fun submitWithRetry(
        robotId: RobotId,
        binding: Binding,
        text: String,
        arrival: Arrival,
    ): SubmitPhase {
        repeat(NOT_READY_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(notReadyRetryDelayMs)
            if (!stillOn(arrival)) return SubmitPhase.SwitchedOff
            if (!bindings.isCurrent(robotId, binding.epoch)) return SubmitPhase.BindingChanged
            val result = sessionPort.submit(binding.target, text)
            if (result != SubmitResult.NOT_READY) return SubmitPhase.Done(result)
        }
        return SubmitPhase.Done(SubmitResult.NOT_READY)
    }

    /** 读取期间换绑的，事件交给当前绑定：宁可发到新机器人，也不丢。 */
    suspend fun onSessionEvents(
        tabId: String,
        events: List<SessionEvent>,
    ) {
        val binding = bindings.bindingForTab(tabId) ?: return
        for (event in events) {
            when (event.kind) {
                SessionEventKind.ASSISTANT_TEXT -> appendFinal(tabId, binding.epoch, event.text)
                SessionEventKind.USER_TEXT -> {
                    startTurn(tabId)
                    if (!consumeRemoteEcho(tabId, event.text)) sendContent(binding, texts.localInputLabel() + "\n" + event.text)
                }
                // 工具调用之前的文字只是过程说明，不推
                SessionEventKind.TOOL_STARTED -> synchronized(lock) { pendingFinal.remove(tabId) }
                SessionEventKind.TOOL_FINISHED -> Unit
                SessionEventKind.TURN_STARTED -> startTurn(tabId)
            }
        }
        if (synchronized(lock) { tabId in completedTabs }) flushFinal(tabId)
    }

    /**
     * 有人说话或注入消息开启了新一轮（也可能是轮次进行中插话）。已完成的轮次结论早已发出；
     * 没收到完成信号时攒下的只是过程说明，丢弃，免得混进之后的最终回复。
     */
    private fun startTurn(tabId: String) {
        synchronized(lock) {
            completedTabs.remove(tabId)
            pendingFinal.remove(tabId)
        }
    }

    /** 轮次完成：把本轮最终回复作为一条发出。 */
    suspend fun onTurnCompleted(tabId: String) {
        synchronized(lock) { completedTabs += tabId }
        flushFinal(tabId)
    }

    private fun appendFinal(
        tabId: String,
        epoch: Long,
        text: String,
    ) {
        synchronized(lock) {
            // 换绑前攒下、旧绑定还没来得及取走的回复并入新绑定，不丢
            val previous = pendingFinal[tabId]
            val current =
                when {
                    previous == null -> PendingFinal(epoch, StringBuilder())
                    previous.epoch == epoch -> previous
                    else -> PendingFinal(epoch, previous.text)
                }
            pendingFinal[tabId] = current
            if (current.text.isNotEmpty()) current.text.append("\n\n")
            current.text.append(text)
        }
    }

    private suspend fun flushFinal(tabId: String) {
        val pending = synchronized(lock) { pendingFinal.remove(tabId) } ?: return
        // 换绑后旧绑定的结果交给当前绑定：宁可发到新机器人，也不丢
        val binding = bindings.bindingForTab(tabId) ?: return
        sendContent(binding, pending.text.toString())
    }

    suspend fun onWaiting(
        tabId: String,
        reason: String?,
    ) {
        val binding = bindings.bindingForTab(tabId) ?: return
        send(binding, texts.waiting(reason))
    }

    suspend fun onTranscriptRestarted(tabId: String) {
        val binding = bindings.bindingForTab(tabId) ?: return
        send(binding, texts.transcriptRestarted())
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
     * 释放时没有完成信号，缓存里只是过程说明，不发给旧机器人：标签已无绑定就丢弃；
     * 已被新绑定接管则留给新绑定，本轮完成时若仍在最后一次工具调用之后，就是最终回复的一部分。
     */
    suspend fun onReleased(
        binding: Binding,
        sessionTitle: String,
    ) {
        val tabId = binding.target.tabId
        synchronized(lock) {
            // 换绑时新绑定先建立、旧绑定的清理后执行：标签已被接管时，缓存、回显登记与完成状态都属于新绑定
            if (bindings.bindingForTab(tabId) == null) {
                pendingFinal.remove(tabId)
                pendingRemoteEcho.remove(tabId)
                completedTabs.remove(tabId)
            }
        }
        val owner = ownerOf(binding.robotId) ?: return
        val channel = channelOf(binding.robotId) ?: return
        deliver(channel, OutboundMessage(binding.robotId, owner.chatId, binding.epoch, texts.released(sessionTitle)))
    }

    /** 回复内容：一旦开始发就全部发完，分段途中换绑也不截断，不丢内容。 */
    private suspend fun sendContent(
        binding: Binding,
        text: String,
    ) {
        val owner = ownerOf(binding.robotId) ?: return
        val channel = channelOf(binding.robotId) ?: return
        for (segment in TextSegmenter.split(text, channel.maxTextBytes)) {
            deliver(channel, OutboundMessage(binding.robotId, owner.chatId, binding.epoch, segment))
        }
    }

    /** 状态提示：绑定已变时内容不再成立，每段发送前都核对。 */
    private suspend fun send(
        binding: Binding,
        text: String,
    ) {
        val owner = ownerOf(binding.robotId) ?: return
        val channel = channelOf(binding.robotId) ?: return
        for (segment in TextSegmenter.split(text, channel.maxTextBytes)) {
            // 每段发送前都核对：分段途中被换绑，剩余内容不能落到新目标的聊天里
            if (!bindings.isCurrent(binding.robotId, binding.epoch)) return
            deliver(channel, OutboundMessage(binding.robotId, owner.chatId, binding.epoch, segment))
        }
    }

    private suspend fun reply(
        robotId: RobotId,
        owner: RobotOwner,
        text: String,
    ) {
        val channel = channelOf(robotId) ?: return
        deliver(channel, OutboundMessage(robotId, owner.chatId, bindingEpoch = null, text = text))
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

    private fun rememberRemote(
        tabId: String,
        text: String,
    ) = synchronized(lock) {
        val queue = pendingRemoteEcho.getOrPut(tabId) { ArrayDeque() }
        queue.addLast(normalize(text))
        while (queue.size > ECHO_CAPACITY) queue.removeFirst()
    }

    private fun forgetRemote(
        tabId: String,
        text: String,
    ) = synchronized(lock) {
        pendingRemoteEcho[tabId]?.remove(normalize(text))
    }

    private fun consumeRemoteEcho(
        tabId: String,
        text: String,
    ): Boolean = synchronized(lock) { pendingRemoteEcho[tabId]?.remove(normalize(text)) == true }

    /** CLI 写入记录时可能调整空白；只按空白归一后的全文匹配，匹配不上就当电脑输入。 */
    private fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    private companion object {
        const val SEEN_CAPACITY = 1000
        const val ECHO_CAPACITY = 20
        const val NOT_READY_ATTEMPTS = 3
    }
}
