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
    private val submitLock = Mutex()
    private val lock = Any()
    private val seen = HashMap<RobotId, LinkedHashSet<String>>()
    private val pendingRemoteEcho = HashMap<String, ArrayDeque<String>>()

    /** 每个标签本轮待发的最终回复，连同产生它时的绑定代次；换绑后作废。 */
    private val pendingFinal = HashMap<String, PendingFinal>()

    /** 已报完成、尚未开始下一轮的标签：此后读到的回复是迟到的最终输出，立即发出。 */
    private val completedTabs = HashSet<String>()

    /**
     * 当前这一轮已由会话记录里的结束标记收尾的标签。按轮次记：下一轮开始即清掉，
     * 那一轮若没有结束标记，仍以 IDE 的完成信号兜底。
     */
    private val transcriptEnds = HashSet<String>()

    /**
     * 每个标签读到的第几轮，每开始一轮加一。IDE 的完成信号带着它发出时对应的轮次，
     * 轮次已经过去（下一轮已开始）的迟到信号作废，不会把新一轮的过程说明当结论发出去。
     */
    private val turns = HashMap<String, Long>()

    private class PendingFinal(
        val epoch: Long,
        val text: StringBuilder,
    )

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
            submitLock.withLock {
                // 加锁后复核绑定：排队期间可能已换绑或解绑
                val current = bindings.bindingOf(message.robotId)
                if (current == null) return@withLock SubmitPhase.BindingGone
                if (current.epoch != binding.epoch) return@withLock SubmitPhase.BindingChanged

                // 在锁内取定目标标签：之后所有回显登记与清除都用它，不再回读可能已换绑的绑定
                val tabId = current.target.tabId
                // 先登记再提交：CLI 可能在 submit 返回前就把这句写进记录
                rememberRemote(tabId, text)
                try {
                    val phase = submitWithRetry(message.robotId, current, text)
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
            transcriptEnds.clear()
            turns.clear()
        }
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
     * 须在 submitLock 内调用。终端未就绪时最多尝试 [NOT_READY_ATTEMPTS] 次；
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
                    if (!consumeRemoteEcho(tabId, event.text)) send(binding, texts.localInputLabel() + "\n" + event.text)
                }
                // 工具调用之前的文字只是过程说明，不推
                SessionEventKind.TOOL_STARTED -> synchronized(lock) { pendingFinal.remove(tabId) }
                SessionEventKind.TOOL_FINISHED -> Unit
                SessionEventKind.TURN_STARTED -> startTurn(tabId)
                SessionEventKind.TURN_ENDED -> {
                    synchronized(lock) {
                        transcriptEnds += tabId
                        completedTabs += tabId
                    }
                    flushFinal(tabId)
                }
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
            transcriptEnds.remove(tabId)
            turns[tabId] = (turns[tabId] ?: 0L) + 1
        }
    }

    /** 这个标签当前读到的轮次，供 IDE 完成信号标明它属于哪一轮。 */
    fun currentTurn(tabId: String): Long = synchronized(lock) { turns[tabId] ?: 0L }

    /**
     * IDE 报告第 [turn] 轮完成：这一轮会话记录里没有结束标记时，以它为准把最终回复作为一条发出。
     * [turn] 不是当前轮（信号晚到，下一轮已经开始）或这一轮已由结束标记收尾时，什么也不做。
     */
    suspend fun onTurnCompleted(
        tabId: String,
        turn: Long,
    ) {
        synchronized(lock) {
            if (turn != (turns[tabId] ?: 0L) || tabId in transcriptEnds) return
            completedTabs += tabId
        }
        flushFinal(tabId)
    }

    private fun appendFinal(
        tabId: String,
        epoch: Long,
        text: String,
    ) {
        synchronized(lock) {
            // 换绑后旧绑定攒下的作废，重新开始
            val current = pendingFinal[tabId]?.takeIf { it.epoch == epoch } ?: PendingFinal(epoch, StringBuilder()).also { pendingFinal[tabId] = it }
            if (current.text.isNotEmpty()) current.text.append("\n\n")
            current.text.append(text)
        }
    }

    private suspend fun flushFinal(tabId: String) {
        val pending = synchronized(lock) { pendingFinal.remove(tabId) } ?: return
        val binding = bindings.bindingForTab(tabId) ?: return
        // 换绑后旧结果作废，不落到新目标的聊天里（切换空档漏消息可接受）
        if (binding.epoch != pending.epoch) return
        send(binding, pending.text.toString())
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
     * 释放时没有完成信号，缓存里只是过程说明，不发；旧代次的缓存之后按代次作废。
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
                transcriptEnds.remove(tabId)
                turns.remove(tabId)
            }
        }
        val owner = ownerOf(binding.robotId) ?: return
        val channel = channelOf(binding.robotId) ?: return
        deliver(channel, OutboundMessage(binding.robotId, owner.chatId, binding.epoch, texts.released(sessionTitle)))
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
