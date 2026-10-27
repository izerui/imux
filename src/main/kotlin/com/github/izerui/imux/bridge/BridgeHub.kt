package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.BindingRegistry
import com.github.izerui.imux.bridge.core.BridgeRouter
import com.github.izerui.imux.bridge.core.ChannelAdapter
import com.github.izerui.imux.bridge.core.DeliveryResult
import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.RobotOwner
import com.github.izerui.imux.bridge.terminal.ImuxTerminalSessionPort
import com.github.izerui.imux.bridge.terminal.ProjectAttachmentStore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 全应用唯一的桥接中枢：绑定表、路由器、已注册的渠道。
 *
 * 机器人连接与绑定都是全应用一份，多个项目窗口共用，避免同一机器人被重复连接而互踢。
 * 入站消息进同一个队列由单一消费者处理，保证按到达顺序提交到终端。
 */
@Service(Service.Level.APP)
class BridgeHub(parentScope: CoroutineScope) {
    private val scope =
        CoroutineScope(
            parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job]) +
                CoroutineExceptionHandler { _, e -> LOG.warn("桥接任务异常，已隔离", e) },
        )
    private val channels = ConcurrentHashMap<RobotId, ChannelAdapter>()
    private val owners = ConcurrentHashMap<RobotId, RobotOwner>()
    private val inbound = Channel<InboundMessage>(Channel.UNLIMITED)

    val bindings = BindingRegistry()
    val router =
        BridgeRouter(
            bindings,
            owners::get,
            ImuxTerminalSessionPort(),
            channels::get,
            BundleBridgeTexts,
            onDelivery = ::logDelivery,
            attachmentStore = ProjectAttachmentStore(),
        )

    /** 渠道收到消息后调用。开关关闭时直接丢弃，不进入终端。 */
    val inboundSink = InboundSink { message -> if (BridgeSettings.getInstance().state.enabled) inbound.trySend(message) }

    init {
        scope.launch {
            for (message in inbound) {
                runCatching { router.onInbound(message) }.onFailure { LOG.warn("处理入站消息失败", it) }
            }
        }
    }

    /** 由机器人管理在连接成功后调用；[owner] 为 null 表示尚未配对，消息一律忽略。 */
    fun registerRobot(
        adapter: ChannelAdapter,
        owner: RobotOwner?,
    ) {
        channels[adapter.robotId] = adapter
        if (owner != null) owners[adapter.robotId] = owner else owners.remove(adapter.robotId)
    }

    fun unregisterRobot(robotId: RobotId) {
        bindings.unbind(robotId)
        channels.remove(robotId)
        owners.remove(robotId)
    }

    /** 停止连接时调用：摘除渠道与本人，但保留绑定，重新连接后继续生效。 */
    fun detachRobot(robotId: RobotId) {
        channels.remove(robotId)
        owners.remove(robotId)
    }

    internal fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)

    companion object {
        private val LOG = logger<BridgeHub>()

        fun getInstance(): BridgeHub = ApplicationManager.getApplication().getService(BridgeHub::class.java)
    }
}

/** 一条出站结果的日志。[warn] 为 false 时记 INFO。 */
internal class DeliveryLog(
    val warn: Boolean,
    val text: String,
)

/**
 * 被拒或结果不明的出站要留痕（例如超过每会话 30 条/分钟被丢弃），成功的不记。
 * 只记机器人 id 与原因，不记正文与对端 id。
 */
internal fun deliveryLog(
    message: OutboundMessage,
    result: DeliveryResult,
): DeliveryLog? =
    when (result) {
        is DeliveryResult.Accepted -> null
        is DeliveryResult.Rejected -> DeliveryLog(true, "出站消息被拒绝：robot=${message.robotId.value} reason=${result.reason}")
        DeliveryResult.Unknown -> DeliveryLog(false, "出站消息结果不明：robot=${message.robotId.value}")
    }

private val DELIVERY_LOG = logger<BridgeRouter>()

private fun logDelivery(
    message: OutboundMessage,
    result: DeliveryResult,
) {
    val log = deliveryLog(message, result) ?: return
    if (log.warn) DELIVERY_LOG.warn(log.text) else DELIVERY_LOG.info(log.text)
}
