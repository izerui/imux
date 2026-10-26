package com.github.izerui.imux.bridge.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * 渠道与终端之间的中介。两边互不认识，只通过绑定关系连接。
 *
 * 入站：校验本人 → 平台消息去重 → 找绑定 → 交给终端端口。不判断 Agent 忙闲，
 * 排队还是插入由 CLI 自己决定；这里只保证按到达顺序逐条提交。
 * 出站：助手文本原文转发；电脑端输入带标签转发；远程输入在记录中再次出现时不回显；
 * 工具调用只计数，在轮次完成时汇总，避免逐条通知触发平台限频。
 */
class BridgeRouter(
    private val bindings: BindingRegistry,
    private val ownerOf: (RobotId) -> RobotOwner?,
    private val sessionPort: TerminalSessionPort,
    private val channelOf: (RobotId) -> ChannelAdapter?,
    private val texts: BridgeTexts,
    private val onDelivery: (OutboundMessage, DeliveryResult) -> Unit = { _, _ -> },
) {
    private val submitLock = Mutex()
    private val lock = Any()
    private val seen = HashMap<RobotId, LinkedHashSet<String>>()
    private val pendingRemoteEcho = HashMap<String, ArrayDeque<String>>()
    private val toolCalls = HashMap<String, Int>()

    suspend fun onInbound(message: InboundMessage): InboundOutcome {
        val owner = ownerOf(message.robotId) ?: return InboundOutcome.NOT_PAIRED
        if (message.senderId != owner.senderId || message.chatId != owner.chatId) return InboundOutcome.UNAUTHORIZED
        if (!markSeen(message.robotId, message.externalMessageId)) return InboundOutcome.DUPLICATE

        val text = message.text.trim()
        if (text.isEmpty()) {
            reply(message.robotId, owner, texts.emptyInput())
            return InboundOutcome.EMPTY
        }
        val binding = bindings.bindingOf(message.robotId)
        if (binding == null) {
            reply(message.robotId, owner, texts.unbound())
            return InboundOutcome.UNBOUND
        }

        val tabId = binding.target.tabId
        val result =
            submitLock.withLock {
                // 先登记再提交：CLI 可能在 submit 返回前就把这句写进记录
                rememberRemote(tabId, text)
                sessionPort.submit(binding.target, text)
            }
        if (result != SubmitResult.SUBMITTED_TO_TERMINAL) forgetRemote(tabId, text)
        return when (result) {
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

    suspend fun onSessionEvents(
        tabId: String,
        events: List<SessionEvent>,
    ) {
        val binding = bindings.bindingForTab(tabId) ?: return
        for (event in events) {
            when (event.kind) {
                SessionEventKind.ASSISTANT_TEXT -> send(binding, event.text)
                SessionEventKind.USER_TEXT ->
                    if (!consumeRemoteEcho(tabId, event.text)) send(binding, texts.localInputLabel() + "\n" + event.text)
                SessionEventKind.TOOL_STARTED -> synchronized(lock) { toolCalls.merge(tabId, 1, Int::plus) }
                SessionEventKind.TOOL_FINISHED -> Unit
            }
        }
    }

    suspend fun onTurnCompleted(tabId: String) {
        val binding = bindings.bindingForTab(tabId) ?: return
        val count = synchronized(lock) { toolCalls.remove(tabId) ?: 0 }
        send(binding, texts.completed(count))
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

    /** 绑定已被移除后调用：不再做 epoch 校验，提示发往原聊天。 */
    suspend fun onReleased(
        binding: Binding,
        sessionTitle: String,
    ) {
        synchronized(lock) {
            pendingRemoteEcho.remove(binding.target.tabId)
            toolCalls.remove(binding.target.tabId)
        }
        val owner = ownerOf(binding.robotId) ?: return
        val channel = channelOf(binding.robotId) ?: return
        deliver(channel, OutboundMessage(binding.robotId, owner.chatId, binding.epoch, texts.released(sessionTitle)))
    }

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
    }
}
