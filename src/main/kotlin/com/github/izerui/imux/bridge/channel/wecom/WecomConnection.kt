package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.channel.JdkHttpFileDownload
import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ConnectionListener
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.channel.FrameListener
import com.github.izerui.imux.bridge.channel.FrameTransport
import com.github.izerui.imux.bridge.channel.JdkWebSocketTransport
import com.github.izerui.imux.bridge.channel.SlidingWindowLimiter
import com.github.izerui.imux.bridge.channel.awaitMediaSlot
import com.github.izerui.imux.bridge.core.MediaSender
import com.github.izerui.imux.bridge.core.MediaKind
import com.github.izerui.imux.bridge.core.MediaRejected
import com.github.izerui.imux.bridge.core.MediaAttempt
import com.github.izerui.imux.bridge.core.PreparedMedia
import com.github.izerui.imux.bridge.core.UploadedMedia
import com.github.izerui.imux.bridge.core.DeliveryResult
import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.TextSegmenter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

fun wecomBackoffMillis(attempt: Int): Long = minOf(1_000L shl (attempt - 1).coerceIn(0, 15), 30_000L)

/**
 * 企业微信智能机器人长连接（见 docs/channel-protocols.md 第 1 节）。
 *
 * 一个机器人一条连接；被别处接管（disconnected_event）后停止，不自动重连。
 * 所有出站一律走单聊主动推送：配对消息已经满足"用户先发过消息"的前提。
 */
class WecomConnection(
    override val robotId: RobotId,
    private val botId: String,
    private val secret: String,
    private val scope: CoroutineScope,
    private val transportFactory: () -> FrameTransport = { JdkWebSocketTransport() },
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom(),
    private val heartbeatMillis: Long = 30_000,
    private val ackTimeoutMillis: Long = 5_000,
    private val backoff: (Int) -> Long = ::wecomBackoffMillis,
    private val limiter: SlidingWindowLimiter = SlidingWindowLimiter(30, 60_000, now),
    private val maxRateWaitMillis: Long = 20_000,
    private val download: HttpFileDownload = JdkHttpFileDownload(),
) : ChannelConnection, MediaSender {
    override val maxTextBytes: Int = 20_000
    private val uploadLimiter = SlidingWindowLimiter(30, 60_000, now)
    override val mediaCapabilities get() = wecomMediaCapabilities

    override suspend fun uploadMedia(chatId: String, file: PreparedMedia, kind: MediaKind): UploadedMedia {
        val state = session ?: throw MediaRejected("offline", canFallback = false)
        val transport = state.transport ?: throw MediaRejected("offline", canFallback = false)
        if (!uploadLimiter.awaitMediaSlot(botId)) throw MediaRejected("rate_limited", canFallback = false)
        return uploadWecomMedia(file, kind) { command, body ->
            if (session !== state) throw MediaRejected("offline", canFallback = false)
            val id = WecomProtocol.reqId(command, now(), random)
            request(state, transport, id, wecomMediaFrame(command, id, body))
        }
    }

    override suspend fun sendMedia(chatId: String, file: PreparedMedia, uploaded: UploadedMedia, commit: () -> Boolean): MediaAttempt {
        val state = session ?: return MediaAttempt.Rejected(MediaRejected("offline", canFallback = false))
        val transport = state.transport ?: return MediaAttempt.Rejected(MediaRejected("offline", canFallback = false))
        if (!limiter.awaitMediaSlot(chatId)) return MediaAttempt.Rejected(MediaRejected("rate_limited", canFallback = false))
        if (session !== state || !commit()) return MediaAttempt.Rejected(MediaRejected("binding_changed", canFallback = false))
        val id = WecomProtocol.reqId("aibot_send_msg", now(), random)
        val ack = request(state, transport, id, wecomMediaFrame("aibot_send_msg", id, wecomMediaBody(chatId, uploaded)))
            ?: return MediaAttempt.Unknown
        run?.let { recordSendResult(it, ack) }
        return if (ack.errcode == 0) MediaAttempt.Accepted()
        else MediaAttempt.Rejected(MediaRejected(ack.errcode.toString(), canFallback = ack.errcode != ERRCODE_RATE_LIMITED))
    }

    private sealed interface SessionEnd {
        data object Kicked : SessionEnd

        data class AuthFailed(val reason: String) : SessionEnd

        data class Dropped(val reason: String) : SessionEnd
    }

    /** 一次连接会话的可变状态；断线重连后换一份新的，旧会话的待确认请求不会串到新会话。 */
    private class SessionState {
        val pending = ConcurrentHashMap<String, CompletableDeferred<WecomFrame.Ack>>()

        @Volatile
        var transport: FrameTransport? = null

        fun endPending() {
            val ex = IOException("session ended")
            pending.values.forEach { it.completeExceptionally(ex) }
            pending.clear()
        }
    }

    /**
     * 一次 start() 到 stop() 的运行。状态先在 [statusLock] 内排进 [statuses]，再由该运行自己的通知协程按序交给监听器：
     * 绝不在持锁时回调监听器。调用方常在自己的锁里调 stop()，而监听器又会去拿那把锁，持锁回调会死锁。
     * 连接循环与发送在不同协程里上报，经同一个队列才能保证监听器收到的顺序与状态变化的顺序一致。
     */
    private class Run(
        val job: Job,
        val statuses: Channel<ConnectionStatus>,
    )

    private val seen = LinkedHashSet<String>()

    @Volatile
    private var session: SessionState? = null

    @Volatile
    private var run: Run? = null

    /** 下面几项只在 [statusLock] 内读写。 */
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
        session?.transport?.close()
        session = null
        // 只入队不回调，不会阻塞在监听器上
        synchronized(statusLock) {
            linkStatus = ConnectionStatus.Stopped
            sendBlocked = null
            shown = ConnectionStatus.Stopped
            current.statuses.trySend(ConnectionStatus.Stopped)
            current.statuses.close()
        }
    }

    /**
     * 上报链路状态。发送受阻标记保留（只在 stop() 时清除）：重新在线后如实显示之前记下的受阻。
     * 与 stop() 同锁：被取消的运行在锁内看到自己已不活跃，不会再把状态改回去。
     */
    private fun reportLink(
        current: Run,
        status: ConnectionStatus,
    ) = synchronized(statusLock) {
        if (!current.job.isActive) return@synchronized
        linkStatus = status
        val display = if (status == ConnectionStatus.Online) sendBlocked ?: ConnectionStatus.Online else status
        shown = display
        current.statuses.trySend(display)
    }

    /**
     * 按发送回执记下或清除发送受阻：成功清除；被拒（限频除外，那是路由器稍后重发的事）记下错误码，
     * 与飞书、钉钉、微信一致，让设置页和手机按钮如实显示「发送受阻」，而不是一直标着在线。
     */
    private fun recordSendResult(
        current: Run,
        ack: WecomFrame.Ack,
    ) = when (ack.errcode) {
        0 -> blockSend(current, null)
        ERRCODE_RATE_LIMITED -> Unit
        else -> blockSend(current, ConnectionStatus.SendFailing("bridge.wecom.sendRejected", "${ack.errcode} ${ack.errmsg.orEmpty()}".trim()))
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

    override suspend fun send(message: OutboundMessage): DeliveryResult {
        // think 标记转换会增加 UTF-8 字节数，路由器切好的正文仍需按实际发送内容检查。
        val parts = TextSegmenter.split(WecomProtocol.defuseThinkTags(message.text), maxTextBytes)
        for ((index, part) in parts.withIndex()) {
            val result = sendPart(message.copy(text = part))
            if (result != DeliveryResult.Accepted) {
                return if (index > 0 && result == DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED)) {
                    DeliveryResult.RetryRemaining(parts.drop(index).joinToString("\n"))
                } else {
                    result
                }
            }
        }
        return DeliveryResult.Accepted
    }

    private suspend fun sendPart(message: OutboundMessage): DeliveryResult {
        val current = run ?: return DeliveryResult.Rejected("offline")
        var s = session ?: return DeliveryResult.Rejected("offline")
        var transport = s.transport ?: return DeliveryResult.Rejected("offline")
        var wait = limiter.tryAcquire(message.chatId)
        if (wait > 0) {
            if (wait > maxRateWaitMillis) return DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED)
            delay(wait)
            // 限频等待期间可能已重连或断线，重新读取当前会话
            s = session ?: return DeliveryResult.Rejected("offline")
            transport = s.transport ?: return DeliveryResult.Rejected("offline")
            wait = limiter.tryAcquire(message.chatId)
            if (wait > 0) return DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED)
        }
        val reqId = WecomProtocol.reqId("aibot_send_msg", now(), random)
        val ack =
            try {
                request(s, transport, reqId, WecomProtocol.sendMarkdown(reqId, message.chatId, message.text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return DeliveryResult.Unknown(e.javaClass.name)
            } catch (e: IllegalStateException) {
                // 取消挂起的 JDK WebSocket send 后，下一次 sendText 可能抛 IllegalStateException
                return DeliveryResult.Unknown(e.javaClass.name)
            } ?: return DeliveryResult.Unknown("no ack")
        recordSendResult(current, ack)
        return when (ack.errcode) {
            0 -> DeliveryResult.Accepted
            // 服务端的会话限频（每会话 30 条/分钟、1000 条/小时）
            ERRCODE_RATE_LIMITED -> DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED)
            else -> DeliveryResult.Rejected("${ack.errcode} ${ack.errmsg.orEmpty()}".trim())
        }
    }

    private suspend fun runLoop(
        current: Run,
        sink: InboundSink,
    ) {
        val myJob = coroutineContext.job
        var attempt = 0
        var authFailures = 0
        var lastFailure: String? = null
        while (myJob.isActive) {
            val failure = lastFailure
            val status = if (attempt >= REPORT_RETRYING_AFTER && failure != null) ConnectionStatus.Retrying(failure) else ConnectionStatus.Connecting
            reportLink(current, status)
            // 认证真正通过（上线）才清零失败计数；认证前的网络中断不算，否则“认证失败、断网”交替时永远到不了上限
            when (val end =
                runSession(current, sink) {
                    attempt = 0
                    authFailures = 0
                }) {
                SessionEnd.Kicked -> {
                    reportLink(current, ConnectionStatus.Kicked)
                    return
                }
                is SessionEnd.AuthFailed -> {
                    lastFailure = end.reason
                    authFailures++
                    if (authFailures >= MAX_AUTH_FAILURES) {
                        reportLink(current, ConnectionStatus.Failed(end.reason))
                        return
                    }
                }
                is SessionEnd.Dropped -> lastFailure = end.reason
            }
            attempt++
            delay(backoff(attempt))
        }
    }

    private suspend fun runSession(
        current: Run,
        sink: InboundSink,
        onOnline: () -> Unit,
    ): SessionEnd {
        val state = SessionState()
        val transport = transportFactory()
        val ended = CompletableDeferred<SessionEnd>()
        val frameListener =
            object : FrameListener {
                override fun onText(text: String) {
                    when (val frame = WecomProtocol.parse(text)) {
                        is WecomFrame.Ack -> state.pending.remove(frame.reqId)?.complete(frame)
                        is WecomFrame.Message -> deliver(frame, sink)
                        WecomFrame.Kicked -> ended.complete(SessionEnd.Kicked)
                        else -> Unit
                    }
                }

                override fun onClosed(reason: String) {
                    ended.complete(SessionEnd.Dropped(reason))
                }
            }
        try {
            transport.open(WecomProtocol.URL, frameListener)
            state.transport = transport
            val subscribeId = WecomProtocol.reqId("aibot_subscribe", now(), random)
            val ack = request(state, transport, subscribeId, WecomProtocol.subscribe(subscribeId, botId, secret))
            when {
                ack == null -> return SessionEnd.Dropped("subscribe timeout").also { transport.close() }
                ack.errcode != 0 -> return SessionEnd.AuthFailed("${ack.errcode} ${ack.errmsg.orEmpty()}".trim()).also { transport.close() }
            }
            // 已被 stop() 取消的旧运行不能覆盖新运行的会话
            coroutineContext.ensureActive()
            this@WecomConnection.session = state
            onOnline()
            reportLink(current, ConnectionStatus.Online)
            return coroutineScope {
                val heartbeat = launch { heartbeat(state, transport, ended) }
                val end = ended.await()
                heartbeat.cancel()
                end
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SessionEnd.Dropped(e.message ?: e.javaClass.simpleName)
        } finally {
            if (this@WecomConnection.session === state) {
                this@WecomConnection.session = null
            }
            state.transport = null
            transport.close()
            state.endPending()
        }
    }

    private suspend fun heartbeat(
        state: SessionState,
        transport: FrameTransport,
        ended: CompletableDeferred<SessionEnd>,
    ) {
        var missed = 0
        while (true) {
            delay(heartbeatMillis)
            val id = WecomProtocol.reqId("ping", now(), random)
            val ack =
                try {
                    request(state, transport, id, WecomProtocol.ping(id))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            missed = if (ack == null) missed + 1 else 0
            if (missed >= MAX_MISSED_PINGS) {
                ended.complete(SessionEnd.Dropped("heartbeat timeout"))
                return
            }
        }
    }

    private suspend fun request(
        state: SessionState,
        transport: FrameTransport,
        reqId: String,
        frame: String,
    ): WecomFrame.Ack? {
        val deferred = CompletableDeferred<WecomFrame.Ack>()
        state.pending[reqId] = deferred
        try {
            // 发送与等回执整体计时：网络假死时 send 缓冲写满会永久挂住，
            // 放进超时后心跳的 request(ping) 也能正常超时、missed 递增、判死重连
            return withTimeoutOrNull(ackTimeoutMillis) {
                transport.send(frame)
                deferred.await()
            }
        } finally {
            state.pending.remove(reqId)
        }
    }

    private fun deliver(
        frame: WecomFrame.Message,
        sink: InboundSink,
    ) {
        // 既没有文字也没有附件（兼容来源、缺字段等）才丢弃；附件在路由器确认本人、已绑定后才下载
        if (frame.text == null && frame.media.isEmpty()) return
        if (!frame.single || frame.fromUserId.isEmpty() || frame.msgId.isEmpty()) return
        synchronized(seen) {
            if (!seen.add(frame.msgId)) return
            while (seen.size > SEEN_CAPACITY) seen.remove(seen.first())
        }
        val attachments = frame.media.map { WecomAttachment(it.kind, it.url, it.aesKey, download) }
        sink.accept(InboundMessage(robotId, frame.msgId, frame.fromUserId, frame.fromUserId, frame.text.orEmpty(), attachments, frame.quote))
    }

    private companion object {
        private const val ERRCODE_RATE_LIMITED = 846607
        const val MAX_AUTH_FAILURES = 5

        // 连续这么多次没连上（上线即清零），状态从“连接中”改报“连接失败，正在重试”
        const val REPORT_RETRYING_AFTER = 5
        const val MAX_MISSED_PINGS = 2
        const val SEEN_CAPACITY = 1000
    }
}
