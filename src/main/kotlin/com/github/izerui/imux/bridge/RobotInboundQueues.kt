package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.Arrival
import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * 按机器人分队列处理入站消息：同一机器人的消息按到达顺序逐条处理，不同机器人互不阻塞，
 * 一个机器人下载大附件时不会耽误其它机器人（企业微信媒体链接 5 分钟后失效）。
 * 队列在第一条消息到达时创建；单条处理失败交给 [onFailure]，不中断该队列。
 */
internal class RobotInboundQueues(
    private val scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit,
    private val handle: suspend (InboundMessage, Arrival) -> Unit,
) {
    private val queues = ConcurrentHashMap<RobotId, Channel<Pair<InboundMessage, Arrival>>>()

    /** [arrival] 须在消息到达时取得，处理时据此判断排队期间是否换绑或关闭过。 */
    fun offer(
        message: InboundMessage,
        arrival: Arrival,
    ) {
        queues.computeIfAbsent(message.robotId) { start() }.trySend(message to arrival)
    }

    private fun start(): Channel<Pair<InboundMessage, Arrival>> {
        val queue = Channel<Pair<InboundMessage, Arrival>>(Channel.UNLIMITED)
        scope.launch {
            for ((message, arrival) in queue) {
                try {
                    handle(message, arrival)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 与原单队列的 runCatching 一致：任何失败都只记下，队列继续
                    onFailure(e)
                }
            }
        }
        return queue
    }
}
