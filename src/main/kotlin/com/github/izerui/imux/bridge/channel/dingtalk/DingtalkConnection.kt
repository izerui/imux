package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ConnectionListener
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.channel.FrameListener
import com.github.izerui.imux.bridge.channel.FrameTransport
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.channel.JdkHttpFileDownload
import com.github.izerui.imux.bridge.channel.JdkWebSocketTransport
import com.github.izerui.imux.bridge.channel.SlidingWindowLimiter
import com.github.izerui.imux.bridge.core.DeliveryResult
import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import kotlin.coroutines.cancellation.CancellationException

/**
 * 钉钉 Stream 长连接（docs/superpowers/specs/2026-10-29-dingtalk-channel-design.md 第 5 至 7 节）。
 *
 * 收消息走 WebSocket（JSON 帧），发消息走 HTTP（batchSend），两者互不依赖：长连接重连期间照样能发。
 * 推送一到就回 ACK（不等处理），再由单个协程按到达顺序交给 [InboundSink]。
 * 钉钉多连接随机投递、没有踢线，不会上报 Kicked。
 */
class DingtalkConnection(
    override val robotId: RobotId,
    private val api: DingtalkApi,
    private val scope: CoroutineScope,
    private val transportFactory: () -> FrameTransport = { JdkWebSocketTransport() },
    private val download: HttpFileDownload = JdkHttpFileDownload(),
    private val now: () -> Long = System::currentTimeMillis,
    private val pingIntervalMillis: Long = 60_000,
    private val deadAfterMillis: Long = 120_000,
    private val backoffBaseMillis: Long = 2_000,
    private val backoffMaxMillis: Long = 60_000,
    private val limiter: SlidingWindowLimiter = SlidingWindowLimiter(5, 1_000, now),
    private val maxRateWaitMillis: Long = 20_000,
) : ChannelConnection {
    /** 单聊上限未公布；正文在 msgParam 里还要转义一层，6000 字节给 15000 字节的上限留足余量。 */
    override val maxTextBytes: Int = 6_000

    private sealed interface SessionEnd {
        /** 服务端要求换连接：立即重建，不算失败。 */
        data object Disconnect : SessionEnd

        /** 凭据被拒；累计多次（期间未成功上线）才认定失败，避免一次误判就停掉。 */
        data class AuthFailed(val reason: String) : SessionEnd

        data class Dropped(val reason: String) : SessionEnd
    }

    private class SessionState(
        val transport: FrameTransport,
        val ended: CompletableDeferred<SessionEnd>,
        startedAt: Long,
    ) {
        @Volatile
        var lastFrameAt: Long = startedAt

        /** 收到过任意文本帧或 pong：握手成功后立刻被关的连接不算连上，失败计数不清零。 */
        @Volatile
        var alive = false

        /** 只在 [DingtalkConnection.statusLock] 内读写。 */
        var onlineReported = false

        /** ACK 与 pong：收帧回调里只入队，由会话协程按序发出。 */
        val replies = Channel<String>(Channel.UNLIMITED)
    }

    /**
     * 一次 start() 到 stop() 的运行。状态先在 [statusLock] 内排进 [statuses]，再由该运行自己的通知协程按序交给监听器：
     * 绝不在持锁时回调监听器。调用方常在自己的锁里调 stop()，而监听器又会去拿那把锁，持锁回调会死锁。
     */
    private class Run(
        val job: Job,
        val statuses: Channel<ConnectionStatus>,
    )

    private val seen = LinkedHashSet<String>()

    @Volatile
    private var run: Run? = null

    @Volatile
    private var session: SessionState? = null

    /** 下面几项只在 [statusLock] 内读写；状态入队也在锁内，保证监听器收到的顺序与状态变化的顺序一致。 */
    private val statusLock = Any()

    /** 长连接的真实状态，只由运行循环和 stop() 写入。 */
    private var linkStatus: ConnectionStatus? = null

    /** 发送受阻（额度、权限、凭据）：下一次发送成功清除。跨重连保留，只在 stop() 时清除。 */
    private var sendBlocked: ConnectionStatus.SendFailing? = null

    /** 接收受阻（收消息额度用完、没有 staffId）：下一条正常消息清除。跨重连保留，只在 stop() 时清除。 */
    private var receiveBlocked: ConnectionStatus.SendFailing? = null

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
        session?.transport?.close()
        session = null
        // 只入队不回调，不会阻塞在监听器上
        synchronized(statusLock) {
            linkStatus = ConnectionStatus.Stopped
            sendBlocked = null
            receiveBlocked = null
            shown = ConnectionStatus.Stopped
            current.statuses.trySend(ConnectionStatus.Stopped)
            current.statuses.close()
        }
    }

    override suspend fun send(message: OutboundMessage): DeliveryResult {
        val current = run ?: return DeliveryResult.Rejected("offline")
        val param = DingtalkApi.markdownParam(DingtalkApi.title(message.text), message.text)
        // 先查大小：超限的消息不占限频名额
        if (param.toByteArray(StandardCharsets.UTF_8).size > DingtalkApi.MAX_MSG_PARAM_BYTES) return DeliveryResult.Rejected("too large")
        var wait = limiter.tryAcquire(message.chatId)
        if (wait > 0) {
            if (wait > maxRateWaitMillis) return DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED)
            delay(wait)
            wait = limiter.tryAcquire(message.chatId)
            if (wait > 0) return DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED)
        }
        return when (val result = api.send(message.chatId, param)) {
            is DingtalkResult.Ok ->
                when {
                    result.value.flowControlledStaffIds.isNotEmpty() -> DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED)
                    result.value.invalidStaffIds.isNotEmpty() -> {
                        blockSend(current, ConnectionStatus.SendFailing("bridge.dingtalk.permission", INVALID_STAFF))
                        DeliveryResult.Rejected(INVALID_STAFF)
                    }

                    else -> {
                        blockSend(current, null)
                        DeliveryResult.Accepted
                    }
                }

            is DingtalkResult.Network -> DeliveryResult.Unknown
            is DingtalkResult.Error -> {
                sendFailing(result)?.let { blockSend(current, it) }
                if (DingtalkCodes.isRateLimited(result.code)) {
                    DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED)
                } else {
                    DeliveryResult.Rejected("${result.code} ${result.message}".trim())
                }
            }
        }
    }

    private fun sendFailing(error: DingtalkResult.Error): ConnectionStatus.SendFailing? =
        when {
            error.code == DingtalkCodes.QUOTA_EXCEEDED -> ConnectionStatus.SendFailing("bridge.dingtalk.quota")
            error.code in DingtalkCodes.NO_PERMISSION -> ConnectionStatus.SendFailing("bridge.dingtalk.permission", error.code)
            error.code in DingtalkCodes.CREDENTIALS_INVALID -> ConnectionStatus.SendFailing("bridge.dingtalk.app", error.code)
            else -> null
        }

    /**
     * 上报长连接状态。收发受阻标记保留（只在 stop() 时清除）：重连上线后如实显示之前记下的受阻状态。
     * 与 stop() 同锁：stop() 先取消再在锁内报“已停止”，之后被取消的运行在锁内看到自己已不活跃，不会再把状态改回去。
     * 每次链路状态变化都上报（即使显示值相同），以便外层计数；收发受阻的去重由 [refresh] 负责。
     */
    private fun reportLink(
        current: Run,
        status: ConnectionStatus,
    ) = synchronized(statusLock) {
        if (!current.job.isActive) return@synchronized
        linkStatus = status
        // 链路在线时显示 sendBlocked ?: receiveBlocked ?: Online；不在线时如实显示链路状态
        val display =
            if (status == ConnectionStatus.Online) sendBlocked ?: receiveBlocked ?: ConnectionStatus.Online else status
        shown = display
        current.statuses.trySend(display)
    }

    /**
     * 首次 alive 时上报 Online：帧回调在 open() 返回前就可能触发（JDK 握手一完成就在执行器线程派发帧），
     * 此时 session 可能还没赋值；session = state 赋值后在同一把锁内补调一次，确保不遗漏。
     * 四个条件缺一不可：alive（收到过帧）、未上报过、session 已指向当前会话、会话未结束。
     */
    private fun tryReportOnline(
        owner: Run,
        state: SessionState,
    ) = synchronized(statusLock) {
        if (state.alive && !state.onlineReported && session === state && !state.ended.isCompleted) {
            state.onlineReported = true
            reportLink(owner, ConnectionStatus.Online)
        }
    }

    private fun blockSend(
        current: Run,
        status: ConnectionStatus.SendFailing?,
    ) = synchronized(statusLock) {
        if (run !== current) return@synchronized
        sendBlocked = status
        refresh(current)
    }

    private fun blockReceive(
        current: Run,
        status: ConnectionStatus.SendFailing?,
    ) = synchronized(statusLock) {
        if (run !== current) return@synchronized
        receiveBlocked = status
        refresh(current)
    }

    /**
     * 持 [statusLock] 调用。只有长连接在线时才显示收发受阻，发类优先（它决定回复能否送达）；
     * 长连接失败、重连中时如实显示长连接状态，以便界面能发起重连。
     */
    private fun refresh(current: Run) {
        if (linkStatus != ConnectionStatus.Online) return
        val display = sendBlocked ?: receiveBlocked ?: ConnectionStatus.Online
        if (display == shown) return
        shown = display
        current.statuses.trySend(display)
    }

    /**
     * 首次立即连接；失败后按 2、4、8…秒退避，最多 60 秒。连续失败 [REPORT_RETRYING_AFTER] 次后立即报“正在重试”，
     * 此后不再闪回“连接中”；凭据被拒累计 [MAX_AUTH_FAILURES] 次（期间未成功上线）直接报失败并停止；
     * disconnect 立即重建，不算失败。
     */
    private suspend fun runLoop(
        owner: Run,
        sink: InboundSink,
    ) {
        coroutineScope {
            val inbound = Channel<DingtalkInbound>(Channel.UNLIMITED)
            launch { for (event in inbound) handle(owner, event, sink) }
            var failures = 0
            var authFailures = 0
            var immediate = true
            while (isActive) {
                if (!immediate) delay(backoff(failures))
                immediate = false
                // 已在显示 Retrying 时保持，不闪回 Connecting
                if (failures < REPORT_RETRYING_AFTER) {
                    reportLink(owner, ConnectionStatus.Connecting)
                }
                when (
                    val end =
                        runSession(owner, inbound) {
                            failures = 0
                            authFailures = 0
                        }
                ) {
                    SessionEnd.Disconnect -> immediate = true
                    is SessionEnd.AuthFailed -> {
                        failures++
                        authFailures++
                        // 凭据累计被拒达到上限直接报失败，不经过 Retrying
                        if (authFailures >= MAX_AUTH_FAILURES) {
                            reportLink(owner, ConnectionStatus.Failed(end.reason))
                            break
                        }
                        if (failures >= REPORT_RETRYING_AFTER) {
                            reportLink(owner, ConnectionStatus.Retrying(end.reason))
                        }
                    }

                    is SessionEnd.Dropped -> {
                        failures++
                        // authFailures 不清零：会话结束时若本会话收到过帧（onAlive）才清零，网络错误不能掩盖凭据问题
                        if (failures >= REPORT_RETRYING_AFTER) {
                            reportLink(owner, ConnectionStatus.Retrying(end.reason))
                        }
                    }
                }
            }
            inbound.close()
        }
    }

    private fun backoff(failures: Int): Long = (backoffBaseMillis shl (failures - 1).coerceIn(0, 20)).coerceAtMost(backoffMaxMillis)

    private suspend fun runSession(
        owner: Run,
        inbound: SendChannel<DingtalkInbound>,
        onAlive: () -> Unit,
    ): SessionEnd {
        val endpoint =
            when (val result = api.openConnection()) {
                is DingtalkResult.Ok -> result.value
                is DingtalkResult.Network -> return SessionEnd.Dropped(result.reason)
                is DingtalkResult.Error -> {
                    val reason = "${result.code} ${result.message}".trim()
                    return if (DingtalkCodes.isCredentialError(result)) SessionEnd.AuthFailed(reason) else SessionEnd.Dropped(reason)
                }
            }
        val transport =
            try {
                transportFactory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return SessionEnd.Dropped(e.message ?: e.javaClass.simpleName)
            }
        val ended = CompletableDeferred<SessionEnd>()
        val state = SessionState(transport, ended, now())
        val frameListener =
            object : FrameListener {
                override fun onText(text: String) {
                    state.lastFrameAt = now()
                    state.alive = true
                    tryReportOnline(owner, state)
                    onFrame(state, text, inbound, ended)
                }

                override fun onPong() {
                    state.lastFrameAt = now()
                    state.alive = true
                    tryReportOnline(owner, state)
                }

                override fun onClosed(reason: String) {
                    ended.complete(SessionEnd.Dropped(reason))
                }
            }
        try {
            try {
                transport.open(endpoint.url(), frameListener)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 异常消息可能带着含 ticket 的完整地址（如 URISyntaxException），原因里只留类名
                return SessionEnd.Dropped(e.javaClass.simpleName.ifEmpty { e.javaClass.name })
            }
            // 已被 stop() 取消的旧运行不能覆盖新运行的会话
            currentCoroutineContext().ensureActive()
            // 赋值与补报在同一把锁内：open() 返回前帧回调可能已置 alive，此处补报 Online
            synchronized(statusLock) {
                session = state
                tryReportOnline(owner, state)
            }
            return coroutineScope {
                val writer = launch { for (reply in state.replies) reply(state, reply) }
                val keepAlive = launch { keepAlive(state, ended) }
                val end = ended.await()
                keepAlive.cancel()
                // disconnect 后服务端还会等 10 秒，已入队的 ACK 尽量发完
                state.replies.close()
                withTimeoutOrNull(REPLY_DRAIN_MILLIS) { writer.join() }
                writer.cancel()
                end
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SessionEnd.Dropped(e.message ?: e.javaClass.simpleName)
        } finally {
            // 清除会话引用，与 tryReportOnline 的 session === state 检查同锁，阻止迟到的帧上报 Online
            synchronized(statusLock) { if (session === state) session = null }
            // 握手后服务端立刻关连接时不清零，否则每 2 秒“连接中/在线”循环、永远到不了“正在重试”
            if (state.alive) onAlive()
            state.replies.close()
            transport.close()
        }
    }

    private suspend fun reply(
        state: SessionState,
        text: String,
    ) {
        try {
            state.transport.send(text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 没回出去：平台可能重推，靠 msgId 去重
        }
    }

    /** 每个周期发一次 WebSocket ping；超过 [deadAfterMillis] 没收到任何帧或 pong，判定连接已死。 */
    private suspend fun keepAlive(
        state: SessionState,
        ended: CompletableDeferred<SessionEnd>,
    ) {
        while (true) {
            delay(pingIntervalMillis)
            if (now() - state.lastFrameAt > deadAfterMillis) {
                ended.complete(SessionEnd.Dropped("heartbeat timeout"))
                return
            }
            try {
                // ping 可能一直挂起（发送锁被占、TCP 写缓冲满），限时才能保证每个周期都做存活检查
                withTimeoutOrNull(pingIntervalMillis) { state.transport.sendPing() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 发不出去由存活检查兜底
            }
        }
    }

    private fun onFrame(
        state: SessionState,
        text: String,
        inbound: SendChannel<DingtalkInbound>,
        ended: CompletableDeferred<SessionEnd>,
    ) {
        val frame = DingtalkEvents.frame(text) ?: return
        if (frame.type == DingtalkEvents.TYPE_SYSTEM) {
            when (frame.topic) {
                DingtalkEvents.TOPIC_PING -> frame.messageId?.let { state.replies.trySend(DingtalkEvents.pong(it, frame.data)) }
                DingtalkEvents.TOPIC_DISCONNECT -> ended.complete(SessionEnd.Disconnect)
            }
            return
        }
        // 先回 ACK 再处理：不等转交终端
        frame.messageId?.let { state.replies.trySend(DingtalkEvents.ack(it)) }
        if (frame.type != DingtalkEvents.TYPE_CALLBACK || frame.topic != DingtalkEvents.TOPIC_BOT_MESSAGE) return
        frame.data?.let(DingtalkEvents::message)?.let { inbound.trySend(it) }
    }

    private fun handle(
        owner: Run,
        event: DingtalkInbound,
        sink: InboundSink,
    ) {
        when (event) {
            is DingtalkInbound.QuotaExceeded -> blockReceive(owner, ConnectionStatus.SendFailing("bridge.dingtalk.receiveQuota"))
            is DingtalkInbound.MissingStaffId ->
                if (markSeen(event.msgId)) blockReceive(owner, ConnectionStatus.SendFailing("bridge.dingtalk.noStaffId"))

            is DingtalkInbound.Message -> {
                if (!markSeen(event.msgId)) return
                val attachments = event.media.map { DingtalkAttachment(it, api, download) }
                try {
                    sink.accept(InboundMessage(robotId, event.msgId, event.staffId, event.staffId, event.text.orEmpty(), attachments, event.quote))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 交出失败不能挡住后面的消息
                }
                // 先交出再恢复显示：界面回到“在线”时，这条消息已经交给桥接
                blockReceive(owner, null)
            }
        }
    }

    private fun markSeen(msgId: String): Boolean =
        synchronized(seen) {
            if (!seen.add(msgId)) return false
            while (seen.size > SEEN_CAPACITY) seen.remove(seen.first())
            true
        }

    private companion object {
        const val REPORT_RETRYING_AFTER = 5
        const val MAX_AUTH_FAILURES = 5
        const val SEEN_CAPACITY = 1000
        const val REPLY_DRAIN_MILLIS = 1_000L
        const val INVALID_STAFF = "invalidStaffIdList"
    }
}
