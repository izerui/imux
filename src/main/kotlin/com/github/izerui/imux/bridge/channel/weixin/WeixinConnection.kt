package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ConnectionListener
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.channel.JdkHttpFileDownload
import com.github.izerui.imux.bridge.channel.SlidingWindowLimiter
import com.github.izerui.imux.bridge.core.DeliveryResult
import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * 微信长轮询连接（docs/superpowers/specs/2026-10-30-weixin-channel-design.md 第 5、7 节）。
 *
 * 收消息用 getupdates 长轮询（服务端挂起即保活，没有心跳接口），发消息用 sendmessage，两者互不依赖。
 * 不补历史：游标只在内存，每次 start 从空游标开始，丢弃连接前一分钟以前的消息。
 * 单个协程按到达顺序交给 [InboundSink]；按用户记最新 context_token 供发送带回。
 */
class WeixinConnection(
    override val robotId: RobotId,
    private val api: WeixinApi,
    private val baseUrl: String,
    private val token: String,
    private val scope: CoroutineScope,
    private val download: HttpFileDownload = JdkHttpFileDownload(),
    private val now: () -> Long = System::currentTimeMillis,
    private val retryDelayMillis: Long = 2_000,
    private val backoffMillis: Long = 30_000,
    private val limiter: SlidingWindowLimiter = SlidingWindowLimiter(3, 1_000, now),
    private val maxRateWaitMillis: Long = 20_000,
    /** getupdates 返回 -14 后暂停多久再继续（官方 session-guard.ts 为 1 小时）。 */
    private val expiredPauseMillis: Long = 3_600_000,
) : ChannelConnection {
    /** 官方按 4000 字符分段；路由器按 UTF-8 字节切，4000 字节保证每段不超过 4000 字符。 */
    override val maxTextBytes: Int = 4_000

    /**
     * 一次 start() 到 stop() 的运行。状态先在 [statusLock] 内排进 [statuses]，再由该运行自己的通知协程按序交给监听器：
     * 绝不在持锁时回调监听器。调用方常在自己的锁里调 stop()，而监听器又会去拿那把锁，持锁回调会死锁。
     */
    private class Run(
        val job: Job,
        val statuses: Channel<ConnectionStatus>,
    )

    private val seen = LinkedHashSet<String>()

    /** 每个用户最新的 context_token，只在内存：缺失时按官方做法照发。 */
    private val contextTokens = ConcurrentHashMap<String, String>()

    @Volatile
    private var run: Run? = null

    /** 下面几项只在 [statusLock] 内读写；状态入队也在锁内，保证监听器收到的顺序与状态变化的顺序一致。 */
    private val statusLock = Any()

    /** 链路的真实状态，只由运行循环和 stop() 写入。 */
    private var linkStatus: ConnectionStatus? = null

    /** 发送受阻：下一次发送成功清除；跨重连保留，只在 stop() 时清除。 */
    private var sendBlocked: ConnectionStatus.SendFailing? = null

    /** 最近一次交给监听器的状态，避免重复上报相同的受阻状态。 */
    private var shown: ConnectionStatus? = null

    override fun start(
        sink: InboundSink,
        listener: ConnectionListener,
    ) {
        if (run != null) return
        lateinit var created: Run
        val job = scope.launch(start = CoroutineStart.LAZY) { runLoop(created, sink) }
        val statuses = Channel<ConnectionStatus>(Channel.UNLIMITED)
        created = Run(job, statuses)
        run = created
        scope.launch { notify(statuses, listener) }
        job.start()
    }

    /** 在任何锁之外按序回调；stop() 关闭队列后，交完最后的“已停止”即结束。 */
    private suspend fun notify(
        statuses: Channel<ConnectionStatus>,
        listener: ConnectionListener,
    ) {
        for (status in statuses) {
            try {
                listener.statusChanged(status)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 监听器出错不能让后续状态丢失
            }
        }
    }

    override fun stop() {
        val current = run ?: return
        run = null
        current.job.cancel()
        // 只入队不回调，不会阻塞在监听器上
        synchronized(statusLock) {
            linkStatus = ConnectionStatus.Stopped
            sendBlocked = null
            shown = ConnectionStatus.Stopped
            current.statuses.trySend(ConnectionStatus.Stopped)
            current.statuses.close()
        }
    }

    override suspend fun send(message: OutboundMessage): DeliveryResult {
        val current = run ?: return DeliveryResult.Rejected("offline")
        var wait = limiter.tryAcquire(message.chatId)
        if (wait > 0) {
            if (wait > maxRateWaitMillis) return DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED)
            delay(wait)
            wait = limiter.tryAcquire(message.chatId)
            if (wait > 0) return DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED)
        }
        val contextToken = contextTokens[message.chatId]
        var result = api.send(baseUrl, token, message.chatId, message.text, contextToken)
        if (result is WeixinResult.Error && result.expired && contextToken != null) {
            // -14 多半是这个用户的 context_token 过期；iLink 接受不带 token 的发送，丢掉它重发一次（hermes weixin.py:1020-1042）。
            // 同一条消息的重发不再占限频名额
            contextTokens.remove(message.chatId, contextToken)
            result = api.send(baseUrl, token, message.chatId, message.text, null)
        }
        return when (result) {
            is WeixinResult.Ok -> {
                blockSend(current, null)
                DeliveryResult.Accepted
            }

            is WeixinResult.Network -> DeliveryResult.Unknown
            is WeixinResult.Error -> {
                // 官方发送失败只抛普通错误、不影响收消息（api.ts:585-596）；其它错误码没有定义，统一如实提示（设计第 7 节）
                blockSend(current, ConnectionStatus.SendFailing("bridge.weixin.sendRejected", result.code))
                DeliveryResult.Rejected(reason(result))
            }
        }
    }

    private fun reason(error: WeixinResult.Error): String = "${error.code} ${error.message}".trim()

    /**
     * 上报链路状态。发送受阻标记保留（只在 stop() 时清除）：重新在线后如实显示之前记下的受阻。
     * 与 stop() 同锁：被取消的运行在锁内看到自己已不活跃，不会再把状态改回去。
     */
    private fun reportLink(
        current: Run,
        status: ConnectionStatus,
    ) = synchronized(statusLock) { reportLinkLocked(current, status) }

    /** 持 [statusLock] 调用。 */
    private fun reportLinkLocked(
        current: Run,
        status: ConnectionStatus,
    ) {
        if (!current.job.isActive) return
        linkStatus = status
        val display = if (status == ConnectionStatus.Online) sendBlocked ?: ConnectionStatus.Online else status
        shown = display
        current.statuses.trySend(display)
    }

    private fun blockSend(
        current: Run,
        status: ConnectionStatus.SendFailing?,
    ) = synchronized(statusLock) {
        if (run !== current) return@synchronized
        sendBlocked = status
        // 只有链路在线时才显示受阻；断线、重试中如实显示链路状态，以便界面能发起重连
        if (linkStatus != ConnectionStatus.Online) return@synchronized
        val display = sendBlocked ?: ConnectionStatus.Online
        if (display == shown) return@synchronized
        shown = display
        current.statuses.trySend(display)
    }

    /**
     * 长轮询：成功即在线；超时视为没有新消息，立即下一轮；其它失败 2 秒后重试，每连续 3 次退避 30 秒（官方 monitor.ts），
     * 连续失败 [REPORT_RETRYING_AFTER] 次起如实报“正在重试”。-14 照官方暂停 [expiredPauseMillis] 后从当前游标继续（monitor.ts:110-124）。
     */
    private suspend fun runLoop(
        owner: Run,
        sink: InboundSink,
    ) {
        coroutineScope {
            val inbound = Channel<WeixinMessage>(Channel.UNLIMITED)
            launch { for (message in inbound) deliver(message, sink) }
            val connectedAt = now()
            var cursor = ""
            var failures = 0
            var online = false
            var retrying: String? = null
            reportLink(owner, ConnectionStatus.Connecting)
            while (isActive) {
                val failure: String =
                    when (val result = api.updates(baseUrl, token, cursor)) {
                        is WeixinResult.Ok -> {
                            failures = 0
                            retrying = null
                            if (!online) {
                                online = true
                                reportLink(owner, ConnectionStatus.Online)
                            }
                            result.value.cursor?.let { cursor = it }
                            result.value.messages.forEach { accept(it, connectedAt, inbound) }
                            continue
                        }

                        is WeixinResult.Network -> if (result.timeout) continue else result.reason
                        is WeixinResult.Error -> {
                            if (result.expired) {
                                val paused = reason(result)
                                failures = 0
                                online = false
                                if (retrying != paused) {
                                    retrying = paused
                                    reportLink(owner, ConnectionStatus.Retrying(paused))
                                }
                                delay(expiredPauseMillis)
                                continue
                            }
                            reason(result)
                        }
                    }
                failures++
                if (online) {
                    online = false
                    reportLink(owner, ConnectionStatus.Connecting)
                }
                if (failures >= REPORT_RETRYING_AFTER && retrying != failure) {
                    retrying = failure
                    reportLink(owner, ConnectionStatus.Retrying(failure))
                }
                delay(if (failures % BACKOFF_EVERY == 0) backoffMillis else retryDelayMillis)
            }
            inbound.close()
        }
    }

    private fun accept(
        raw: com.google.gson.JsonObject,
        connectedAt: Long,
        inbound: Channel<WeixinMessage>,
    ) {
        val message = WeixinEvents.message(raw) ?: return
        // 空游标可能推送积压的历史；断开期间的消息不补（AGENTS.md）。留一分钟余量防时钟偏差把新消息误丢
        val created = message.createTimeMillis
        if (created != null && created < connectedAt - HISTORY_MARGIN_MILLIS) return
        val id = message.messageId
        if (id != null && !markSeen(id)) return
        message.contextToken?.let { contextTokens[message.fromUserId] = it }
        inbound.trySend(message)
    }

    private fun deliver(
        message: WeixinMessage,
        sink: InboundSink,
    ) {
        val attachments = message.media.map { WeixinAttachment(it, download) }
        try {
            sink.accept(
                InboundMessage(
                    robotId,
                    message.messageId ?: "weixin-${UUID.randomUUID()}",
                    message.fromUserId,
                    message.fromUserId,
                    message.text.orEmpty(),
                    attachments,
                    message.quote,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 交出失败不能挡住后面的消息
        }
    }

    private fun markSeen(id: String): Boolean =
        synchronized(seen) {
            if (!seen.add(id)) return false
            while (seen.size > SEEN_CAPACITY) seen.remove(seen.first())
            true
        }

    private companion object {
        const val REPORT_RETRYING_AFTER = 5
        const val BACKOFF_EVERY = 3
        const val SEEN_CAPACITY = 1000
        const val HISTORY_MARGIN_MILLIS = 60_000L
    }
}
