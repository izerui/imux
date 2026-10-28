package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.BindingRegistry
import com.github.izerui.imux.bridge.core.BridgeRouter
import com.github.izerui.imux.bridge.core.ChannelAdapter
import com.github.izerui.imux.bridge.core.DeliveryResult
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.RobotOwner
import com.github.izerui.imux.bridge.robot.RobotStore
import com.github.izerui.imux.bridge.robot.bindingsToSave
import com.github.izerui.imux.bridge.robot.restoredTarget
import com.github.izerui.imux.bridge.terminal.ImuxTerminalSessionPort
import com.github.izerui.imux.bridge.terminal.ProjectAttachmentStore
import com.github.izerui.imux.terminal.SessionDecorations
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 全应用唯一的桥接中枢：绑定表、路由器、已注册的渠道，以及界面刷新信号。
 *
 * 机器人连接与绑定都是全应用一份，多个项目窗口共用，避免同一机器人被重复连接而互踢。
 * 入站消息按机器人分队列处理：同一机器人按到达顺序提交到终端，不同机器人互不阻塞。
 * 绑定表在这里恢复与持久化：表一有改动就落盘，调用方不用各自同步。
 */
@Service(Service.Level.APP)
class BridgeHub(parentScope: CoroutineScope) {
    private val scope = isolatedScope(parentScope, LOG, "桥接任务")
    private val channels = ConcurrentHashMap<RobotId, ChannelAdapter>()
    private val owners = ConcurrentHashMap<RobotId, RobotOwner>()
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
            onWarning = { LOG.warn(it) },
        )

    private val _revision = MutableStateFlow(0L)

    /** 机器人、连接状态或绑定任何变化时加一；标签、会话树、提示条、状态栏与设置页据此刷新。 */
    val revision: StateFlow<Long> = _revision

    private val inbound = RobotInboundQueues(scope, onFailure = { LOG.warn("处理入站消息失败", it) }, handle = { router.onInbound(it) })

    /** 渠道收到消息后调用。开关关闭时直接丢弃，不进入终端。 */
    val inboundSink = InboundSink { message -> if (BridgeSettings.getInstance().state.enabled) inbound.offer(message) }

    init {
        val store = RobotStore.getInstance()
        store.book().bindings.forEach { saved -> restoredTarget(saved)?.let { bindings.bind(RobotId(saved.robotId), it) } }
        bindings.onChanged = {
            store.update { it.withBindings(bindingsToSave(bindings.snapshot())) }
            changed()
        }
        scope.launch {
            revision.collect {
                ApplicationManager.getApplication().invokeLater({ SessionDecorations.changed() }, ModalityState.any())
            }
        }
    }

    /** 通知界面刷新。 */
    fun changed() {
        _revision.update { it + 1 }
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
        DeliveryResult.Accepted -> null
        is DeliveryResult.Rejected -> DeliveryLog(true, "出站消息被拒绝：robot=${message.robotId.value} reason=${result.reason}")
        DeliveryResult.Unknown -> DeliveryLog(false, "出站消息结果不明：robot=${message.robotId.value}")
    }

private val DELIVERY_LOG = logger<BridgeHub>()

private fun logDelivery(
    message: OutboundMessage,
    result: DeliveryResult,
) {
    val log = deliveryLog(message, result) ?: return
    if (log.warn) DELIVERY_LOG.warn(log.text) else DELIVERY_LOG.info(log.text)
}
