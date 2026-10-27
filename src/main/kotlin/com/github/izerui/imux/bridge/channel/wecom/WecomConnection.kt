package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ConnectionListener
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.channel.FrameListener
import com.github.izerui.imux.bridge.channel.FrameTransport
import com.github.izerui.imux.bridge.channel.JdkWebSocketTransport
import com.github.izerui.imux.bridge.channel.SlidingWindowLimiter
import com.github.izerui.imux.bridge.core.DeliveryResult
import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
) : ChannelConnection {
    override val maxTextBytes: Int = 20_000

    private sealed interface SessionEnd {
        data object Kicked : SessionEnd

        data class AuthFailed(val reason: String) : SessionEnd

        data class Dropped(val reason: String) : SessionEnd
    }

    /** Per-session mutable state, isolated from other sessions. */
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

    private val seen = LinkedHashSet<String>()

    @Volatile
    private var session: SessionState? = null

    @Volatile
    private var job: Job? = null

    @Volatile
    private var activeListener: ConnectionListener = ConnectionListener { }

    override fun start(
        sink: InboundSink,
        listener: ConnectionListener,
    ) {
        if (job != null) return
        activeListener = listener
        job = scope.launch { runLoop(sink, listener) }
    }

    override fun stop() {
        val j = job ?: return
        job = null
        j.cancel()
        session?.transport?.close()
        session = null
        activeListener.statusChanged(ConnectionStatus.Stopped)
    }

    override suspend fun send(message: OutboundMessage): DeliveryResult {
        val s = session ?: return DeliveryResult.Rejected("offline")
        val transport = s.transport ?: return DeliveryResult.Rejected("offline")
        var wait = limiter.tryAcquire(message.chatId)
        if (wait > 0) {
            if (wait > maxRateWaitMillis) return DeliveryResult.Rejected("rate limited")
            delay(wait)
            wait = limiter.tryAcquire(message.chatId)
            if (wait > 0) return DeliveryResult.Rejected("rate limited")
        }
        val reqId = WecomProtocol.reqId("aibot_send_msg", now(), random)
        val ack =
            try {
                request(s, transport, reqId, WecomProtocol.sendMarkdown(reqId, message.chatId, message.text))
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                return DeliveryResult.Unknown
            } ?: return DeliveryResult.Unknown
        return if (ack.errcode == 0) DeliveryResult.Accepted(null) else DeliveryResult.Rejected("${ack.errcode} ${ack.errmsg.orEmpty()}".trim())
    }

    private suspend fun runLoop(
        sink: InboundSink,
        listener: ConnectionListener,
    ) {
        val myJob = coroutineContext.job
        var attempt = 0
        var authFailures = 0
        while (myJob.isActive) {
            if (myJob.isActive) listener.statusChanged(ConnectionStatus.Connecting)
            // 认证真正通过（上线）才清零失败计数；认证前的网络中断不算，否则“认证失败、断网”交替时永远到不了上限
            when (val end =
                runSession(sink, listener) {
                    attempt = 0
                    authFailures = 0
                }) {
                SessionEnd.Kicked -> {
                    if (myJob.isActive) listener.statusChanged(ConnectionStatus.Kicked)
                    return
                }
                is SessionEnd.AuthFailed -> {
                    authFailures++
                    if (authFailures >= MAX_AUTH_FAILURES) {
                        if (myJob.isActive) listener.statusChanged(ConnectionStatus.Failed(end.reason))
                        return
                    }
                }
                is SessionEnd.Dropped -> Unit
            }
            attempt++
            delay(backoff(attempt))
        }
    }

    private suspend fun runSession(
        sink: InboundSink,
        listener: ConnectionListener,
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
            val myJob = coroutineContext.job
            if (myJob.isActive) listener.statusChanged(ConnectionStatus.Online)
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
            transport.send(frame)
            return withTimeoutOrNull(ackTimeoutMillis) { deferred.await() }
        } finally {
            state.pending.remove(reqId)
        }
    }

    private fun deliver(
        frame: WecomFrame.Message,
        sink: InboundSink,
    ) {
        val text = frame.text ?: return
        if (!frame.single || frame.fromUserId.isEmpty() || frame.msgId.isEmpty()) return
        synchronized(seen) {
            if (!seen.add(frame.msgId)) return
            while (seen.size > SEEN_CAPACITY) seen.remove(seen.first())
        }
        sink.accept(InboundMessage(robotId, frame.msgId, frame.fromUserId, frame.fromUserId, text))
    }

    private companion object {
        const val MAX_AUTH_FAILURES = 5
        const val MAX_MISSED_PINGS = 2
        const val SEEN_CAPACITY = 1000
    }
}
