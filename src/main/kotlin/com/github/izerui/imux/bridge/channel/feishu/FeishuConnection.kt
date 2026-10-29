package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ConnectionListener
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.channel.FrameListener
import com.github.izerui.imux.bridge.channel.FrameTransport
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.channel.JdkHttpFileDownload
import com.github.izerui.imux.bridge.channel.JdkWebSocketTransport
import com.github.izerui.imux.bridge.channel.SlidingWindowLimiter
import com.github.izerui.imux.bridge.channel.WebSocketHandshakeRejected
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.DeliveryResult
import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
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
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Random
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * 飞书长连接（docs/superpowers/specs/2026-10-28-feishu-channel-design.md 第 5 至 7 节）。
 *
 * 收消息走 WebSocket（protobuf 帧），发消息走 HTTP，两者互不依赖：长连接重连期间照样能发。
 * 与官方 SDK 一致，拼齐事件后先处理再确认：成功回 200、异常回 500；单个协程按到达顺序交给 [InboundSink]。
 * 飞书没有踢线事件，不会上报 Kicked。
 */
class FeishuConnection(
    override val robotId: RobotId,
    private val api: FeishuApi,
    private val scope: CoroutineScope,
    private val transportFactory: () -> FrameTransport = { JdkWebSocketTransport() },
    private val download: HttpFileDownload = JdkHttpFileDownload(),
    private val speech: FeishuSpeech = FeishuSpeech(api, download),
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom(),
    /** 服务端下发的间隔以秒为单位；测试把一秒缩短。 */
    private val secondMillis: Long = 1_000,
    private val limiter: SlidingWindowLimiter = SlidingWindowLimiter(5, 1_000, now),
    private val maxRateWaitMillis: Long = 20_000,
) : ChannelConnection {
    /** 富文本请求体上限 30 KB，正文在两层 JSON 里还要转义，取一半作余量。 */
    override val maxTextBytes: Int = 15_000

    private sealed interface SessionEnd {
        /** 配置或权限问题，重连也没用。 */
        data class Fatal(val reason: String) : SessionEnd

        data class Dropped(val reason: String) : SessionEnd
    }

    private class SessionState(
        val transport: FrameTransport,
        val serviceId: Int,
        startedAt: Long,
    ) {
        @Volatile
        var lastFrameAt: Long = startedAt
    }

    private class Partial(
        val parts: Array<ByteArray?>,
        val createdAt: Long,
    )

    private class PendingEvent(
        val state: SessionState,
        val frame: FeishuFrame,
        val payload: String,
    )

    private val seen = LinkedHashSet<String>()
    private val partials = HashMap<String, Partial>()

    @Volatile
    private var config = FeishuClientConfig.DEFAULT

    @Volatile
    private var session: SessionState? = null

    /**
     * 一次 start() 到 stop() 的运行。状态先在 [statusLock] 内排进 [statuses]，再由该运行自己的通知协程按序交给监听器：
     * 绝不在持锁时回调监听器。调用方常在自己的锁里调 stop()，而监听器又会去拿那把锁，持锁回调会死锁。
     */
    private class Run(
        val job: Job,
        val statuses: Channel<ConnectionStatus>,
    )

    @Volatile
    private var run: Run? = null

    /** 下面两项只在 [statusLock] 内读写；状态入队也在锁内，保证监听器收到的顺序与状态变化的顺序一致。 */
    private val statusLock = Any()

    /** 长连接的真实状态，只由运行循环和 stop() 写入。 */
    private var linkStatus: ConnectionStatus? = null

    /** 发送受阻标记：跨重连保留，只在 stop() 或下一次发送成功时清除；只在长连接在线时显示。 */
    private var sendBlocked: ConnectionStatus.SendFailing? = null

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
        synchronized(partials) { partials.clear() }
        // 只入队不回调，不会阻塞在监听器上
        synchronized(statusLock) {
            linkStatus = ConnectionStatus.Stopped
            sendBlocked = null
            current.statuses.trySend(ConnectionStatus.Stopped)
            current.statuses.close()
        }
    }

    override suspend fun send(message: OutboundMessage): DeliveryResult {
        val current = run ?: return DeliveryResult.Rejected("offline")
        return sendText(current, message.chatId, message.text)
    }

    /**
     * 路由器按 [maxTextBytes] 切段，但引号、反斜杠多的正文经两层转义后请求体仍可能超过 30 KB，整段拒发会丢掉这段回复；
     * 超限时在这里对半切开分别发送（递归），每段照常过限频。遇到第一段失败即返回该失败，已发出的不撤回。
     */
    private suspend fun sendText(
        current: Run,
        chatId: String,
        text: String,
    ): DeliveryResult {
        // 先查大小再取限频名额：超限的整段不占名额，切开后每段各占一个
        val body = FeishuApi.markdownBody(chatId, text)
        if (body.toByteArray(StandardCharsets.UTF_8).size > FeishuApi.MAX_POST_BODY_BYTES) {
            val cut = splitIndex(text) ?: return DeliveryResult.Rejected("too large")
            val head = sendText(current, chatId, text.substring(0, cut))
            if (head != DeliveryResult.Accepted) return head
            return sendText(current, chatId, text.substring(cut))
        }
        var wait = limiter.tryAcquire(chatId)
        if (wait > 0) {
            if (wait > maxRateWaitMillis) return DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED)
            delay(wait)
            wait = limiter.tryAcquire(chatId)
            if (wait > 0) return DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED)
        }
        return when (val result = api.send(body)) {
            is FeishuResult.Ok -> {
                sendRecovered(current)
                DeliveryResult.Accepted
            }

            is FeishuResult.Network -> DeliveryResult.Unknown
            is FeishuResult.Error -> {
                sendFailing(result.code)?.let { sendBlocked(current, it) }
                if (result.code in FeishuCodes.RATE_LIMITED) DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED) else DeliveryResult.Rejected(
                    "${result.code} ${result.msg}".trim()
                )
            }
        }
    }

    /**
     * 切分点：中点附近（中间一半范围内）有换行就切在最近的换行之后，否则切在中点；不切开代理对。
     * 切不出两段非空文本（只剩一个字符）时返回 null，防止无限递归。
     */
    private fun splitIndex(text: String): Int? {
        val middle = text.length / 2
        val range = maxOf(text.length / 4, 1)..minOf(text.length - text.length / 4, text.length - 1)
        val before = text.lastIndexOf('\n', middle).takeIf { it >= 0 }?.plus(1)
        val after = text.indexOf('\n', middle).takeIf { it >= 0 }?.plus(1)
        var cut = listOfNotNull(before, after).filter { it in range }.minByOrNull { abs(it - middle) } ?: middle
        if (cut in 1 until text.length && Character.isLowSurrogate(text[cut]) && Character.isHighSurrogate(text[cut - 1])) cut--
        return cut.takeIf { it in 1 until text.length }
    }

    private fun sendFailing(code: Int): ConnectionStatus.SendFailing? =
        when {
            code == FeishuCodes.QUOTA_EXCEEDED -> ConnectionStatus.SendFailing("bridge.feishu.quota")
            code in FeishuCodes.NO_PERMISSION -> ConnectionStatus.SendFailing(
                "bridge.feishu.permission",
                code.toString()
            )

            code in FeishuCodes.APP_UNAVAILABLE -> ConnectionStatus.SendFailing("bridge.feishu.app", code.toString())
            else -> null
        }

    /**
     * 上报长连接状态。发送受阻标记保留：额度、权限问题不因重连消失，重连上线后如实显示之前记下的受阻状态。
     * 与 stop() 同锁：stop() 先取消再在锁内报“已停止”，之后被取消的运行在锁内看到自己已不活跃，不会再把状态改回去。
     */
    private fun reportLink(
        current: Run,
        status: ConnectionStatus,
    ) = synchronized(statusLock) {
        if (!current.job.isActive) return@synchronized
        linkStatus = status
        current.statuses.trySend(if (status == ConnectionStatus.Online) sendBlocked ?: status else status)
    }

    /** 总是记下；只有长连接在线时才显示，长连接失败、重连中时如实显示长连接状态，以便界面能发起重连。 */
    private fun sendBlocked(
        current: Run,
        status: ConnectionStatus.SendFailing,
    ) = synchronized(statusLock) {
        if (run !== current) return@synchronized
        sendBlocked = status
        if (linkStatus == ConnectionStatus.Online) current.statuses.trySend(status)
    }

    /** 发送恢复：清掉受阻标记；长连接在线时改回“在线”，不在线时显示的本就是长连接状态，不动。 */
    private fun sendRecovered(current: Run) =
        synchronized(statusLock) {
            if (run !== current || sendBlocked == null) return@synchronized
            sendBlocked = null
            if (linkStatus == ConnectionStatus.Online) current.statuses.trySend(ConnectionStatus.Online)
        }

    /**
     * 重连节奏遵循服务端参数：断线后先随机等待 [0, ReconnectNonce) 秒，之后每次间隔 ReconnectInterval 秒；
     * ReconnectCount ≥ 0 时用完次数即失败。首次连接或某次重连失败时立即报“连接失败，正在重试”；
     * 在线后被断开立即报“连接中”（不等随机抖动，否则界面会在断线后仍显示“在线”最多约 30 秒），这次重连失败才报“正在重试”。
     */
    private suspend fun runLoop(
        owner: Run,
        sink: InboundSink,
    ) {
        coroutineScope {
            val inbound = Channel<PendingEvent>(Channel.UNLIMITED)
            launch { for (event in inbound) handleEvent(event, sink) }
            var reconnects = 0
            var lastFailure: String? = null
            var first = true
            // 正在显示的“连接失败，正在重试”的原因；下一次尝试开始时不闪回“连接中”
            var retrying: String? = null
            // 掉线时已报过“连接中”，下一次尝试开始时不重复上报
            var connectingShown = false
            while (isActive) {
                if (!first) {
                    val current = config
                    // 与官方 SDK 一致：ReconnectCount ≥ 0 是重连尝试次数（`for i in range(reconnect_count)`），0 即不重连直接失败
                    if (current.reconnectCount >= 0 && reconnects >= current.reconnectCount) {
                        reportLink(owner, ConnectionStatus.Failed(lastFailure ?: "unreachable"))
                        break
                    }
                    val wait =
                        if (reconnects == 0) {
                            (random.nextDouble() * current.reconnectNonceSeconds * secondMillis).toLong()
                        } else {
                            current.reconnectIntervalSeconds * secondMillis
                        }
                    delay(wait)
                    reconnects++
                }
                first = false
                if (retrying == null && !connectingShown) reportLink(owner, ConnectionStatus.Connecting)
                connectingShown = false
                var reachedOnline = false
                when (val end = runSession(owner, inbound) {
                    reconnects = 0
                    lastFailure = null
                    reachedOnline = true
                    retrying = null
                }) {
                    is SessionEnd.Fatal -> {
                        reportLink(owner, ConnectionStatus.Failed(end.reason))
                        break
                    }

                    is SessionEnd.Dropped -> {
                        lastFailure = end.reason
                        if (reachedOnline) {
                            // 在线后被断开不算失败：立即报“连接中”，再等待重连
                            reportLink(owner, ConnectionStatus.Connecting)
                            connectingShown = true
                        } else if (retrying != end.reason) {
                            // 首次连接或一次重连本身失败：立即如实上报，不等下一次尝试（之间可能隔着 ReconnectInterval）
                            reportLink(owner, ConnectionStatus.Retrying(end.reason))
                            retrying = end.reason
                        }
                    }
                }
            }
            inbound.close()
        }
    }

    private suspend fun runSession(
        owner: Run,
        inbound: SendChannel<PendingEvent>,
        onOnline: () -> Unit,
    ): SessionEnd {
        val endpoint =
            when (val result = api.endpoint()) {
                is FeishuResult.Ok -> result.value
                is FeishuResult.Network -> return SessionEnd.Dropped(result.reason)
                is FeishuResult.Error -> {
                    val reason = "${result.code} ${result.msg}".trim()
                    return if (result.code in FeishuCodes.ENDPOINT_RETRYABLE) SessionEnd.Dropped(reason) else SessionEnd.Fatal(
                        reason
                    )
                }
            }
        config = endpoint.config
        val serviceId =
            queryParam(endpoint.url, "service_id")?.toIntOrNull() ?: return SessionEnd.Dropped("no service_id")
        val transport =
            try {
                transportFactory()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return SessionEnd.Dropped(e.message ?: e.javaClass.simpleName)
            }
        val state = SessionState(transport, serviceId, now())
        val ended = CompletableDeferred<SessionEnd>()
        val frameListener =
            object : FrameListener {
                override fun onText(text: String) = Unit

                override fun onBinary(bytes: ByteArray) {
                    state.lastFrameAt = now()
                    onFrame(state, bytes, inbound)
                }

                override fun onClosed(reason: String) {
                    ended.complete(SessionEnd.Dropped(reason))
                }
            }
        try {
            try {
                transport.open(endpoint.url, frameListener)
            } catch (e: WebSocketHandshakeRejected) {
                return handshakeEnd(e)
            }
            // 已被 stop() 取消的旧运行不能覆盖新运行的会话
            currentCoroutineContext().ensureActive()
            session = state
            onOnline()
            reportLink(owner, ConnectionStatus.Online)
            return coroutineScope {
                val heartbeat = launch { heartbeat(state, ended) }
                val end = ended.await()
                heartbeat.cancel()
                end
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 异常消息可能带完整 wss 地址（含 ticket 等凭据），而原因会经上层写进日志：只留异常类名
            return SessionEnd.Dropped(e.javaClass.simpleName.ifEmpty { e.javaClass.name })
        } finally {
            if (session === state) session = null
            transport.close()
        }
    }

    /** 与官方 SDK 一致：403，或 514 且 autherrcode 为连接数超限时不重连；其余重连。 */
    private fun handshakeEnd(e: WebSocketHandshakeRejected): SessionEnd {
        val status = e.headers["handshake-status"]?.toIntOrNull() ?: e.status
        val reason = "handshake $status ${e.headers["handshake-msg"].orEmpty()}".trim()
        val authCode = e.headers["handshake-autherrcode"]?.toIntOrNull()
        val fatal =
            status == FeishuCodes.HANDSHAKE_FORBIDDEN ||
                    (status == FeishuCodes.HANDSHAKE_AUTH_FAILED && authCode == FeishuCodes.EXCEED_CONN_LIMIT)
        return if (fatal) SessionEnd.Fatal(reason) else SessionEnd.Dropped(reason)
    }

    /**
     * 每个心跳周期发一次 ping；超过 [DEAD_AFTER_PINGS] 个周期没收到任何帧，判定连接已死（官方 SDK 没有这条，是我们加的保护）。
     * ping 发送限时一个周期：半死的连接上写入可能一直挂起，不限时就再也走不到存活检查。
     */
    private suspend fun heartbeat(
        state: SessionState,
        ended: CompletableDeferred<SessionEnd>,
    ) {
        while (true) {
            val interval = config.pingIntervalSeconds.coerceAtLeast(1) * secondMillis
            delay(interval)
            if (now() - state.lastFrameAt > interval * DEAD_AFTER_PINGS) {
                ended.complete(SessionEnd.Dropped("heartbeat timeout"))
                return
            }
            val ping = FeishuFrame(
                service = state.serviceId,
                method = FeishuFrame.METHOD_CONTROL,
                headers = listOf(FeishuHeader("type", "ping"))
            )
            withTimeoutOrNull(interval) {
                try {
                    state.transport.sendBinary(FeishuFrameCodec.encode(ping))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 发不出去由存活检查兜底
                }
            }
        }
    }

    private fun onFrame(
        state: SessionState,
        bytes: ByteArray,
        inbound: SendChannel<PendingEvent>,
    ) {
        val frame = runCatching { FeishuFrameCodec.decode(bytes) }.getOrNull() ?: return
        when (frame.method) {
            FeishuFrame.METHOD_CONTROL -> if (frame.header("type") == "pong") pongConfig(frame)?.let { config = it }
            FeishuFrame.METHOD_DATA -> onData(state, frame, inbound)
        }
    }

    private fun pongConfig(frame: FeishuFrame): FeishuClientConfig? {
        val payload = frame.payload?.takeIf { it.isNotEmpty() } ?: return null
        val json = runCatching { JsonParser.parseString(String(payload, StandardCharsets.UTF_8)) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return FeishuClientConfig.from(json, config)
    }

    /** card 类型与官方 SDK 一样不处理、不回写。 */
    private fun onData(
        state: SessionState,
        frame: FeishuFrame,
        inbound: SendChannel<PendingEvent>,
    ) {
        if (frame.header("type") != "event") return
        val frameId = frame.header("message_id") ?: return
        val sum = frame.header("sum")?.toIntOrNull() ?: return
        val seq = frame.header("seq")?.toIntOrNull() ?: return
        val payload = combine(frameId, sum, seq, frame.payload ?: ByteArray(0)) ?: return
        val text = String(payload, StandardCharsets.UTF_8)
        // Node SDK 在 mergeData 内解析 JSON；坏包不进入业务回调，也不回成功确认。
        if (runCatching { JsonParser.parseString(text).isJsonObject }.getOrDefault(false)) {
            inbound.trySend(PendingEvent(state, frame, text))
        }
    }

    private suspend fun handleEvent(event: PendingEvent, sink: InboundSink) {
        val startedAt = now()
        var claimedId: String? = null
        val code = try {
            val message = FeishuEvents.parseMessage(event.payload)
            if (message != null && markSeen(message.messageId)) {
                claimedId = message.messageId
                deliver(message, sink)
            }
            200
        } catch (e: CancellationException) {
            claimedId?.let { synchronized(seen) { seen.remove(it) } }
            throw e
        } catch (_: Exception) {
            // 处理失败不能留下去重标记，否则平台重投会被当成已处理。
            claimedId?.let { synchronized(seen) { seen.remove(it) } }
            500
        }
        acknowledge(event.state, event.frame, code, now() - startedAt)
    }

    private suspend fun acknowledge(
        state: SessionState,
        frame: FeishuFrame,
        code: Int,
        elapsedMillis: Long,
    ) {
        // biz_rt 记录本次业务处理耗时，不包含排队时间。
        val ack = frame.withHeader("biz_rt", elapsedMillis.coerceAtLeast(0).toString())
            .copy(payload = """{"code":$code}""".toByteArray(StandardCharsets.UTF_8))
        try {
            state.transport.sendBinary(FeishuFrameCodec.encode(ack))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 确认没发出去，平台会重投，靠消息 id 去重
        }
    }

    /** 与 Node SDK 一样校验元数据并按 seq 拼接，未完成缓存保留 10 秒。 */
    private fun combine(
        frameId: String,
        sum: Int,
        seq: Int,
        part: ByteArray,
    ): ByteArray? =
        synchronized(partials) {
            val current = now()
            partials.entries.removeIf { current - it.value.createdAt > COMBINE_TTL_MILLIS }
            if (sum <= 0 || seq !in 0 until sum) return null
            val partial = partials.getOrPut(frameId) { Partial(arrayOfNulls(sum), current) }
            if (partial.parts.size != sum) return null
            partial.parts[seq] = part
            if (partial.parts.any { it == null }) return null
            partials.remove(frameId)
            val out = ByteArrayOutputStream()
            partial.parts.forEach { out.write(it!!) }
            out.toByteArray()
        }

    private fun markSeen(messageId: String): Boolean =
        synchronized(seen) {
            if (!seen.add(messageId)) return false
            while (seen.size > SEEN_CAPACITY) seen.remove(seen.first())
            true
        }

    private suspend fun deliver(
        message: FeishuMessage,
        sink: InboundSink,
    ) {
        val quote = message.parentId?.let { api.messageText(it) }
        // 语音先交给飞书识别；识别成功就当作本人发的文字（与企业微信的语音转写一致），失败则作为音频附件转交
        val voice = message.media.firstOrNull { it.kind == AttachmentKind.AUDIO }
        val transcript = voice?.let { speech.transcribe(message.messageId, it) }
        val media = if (transcript != null) message.media.filter { it !== voice } else message.media
        val text = listOfNotNull(message.text, transcript).joinToString("\n")
        val attachments = media.map { FeishuAttachment(message.messageId, it, api, download) }
        sink.accept(
            InboundMessage(
                robotId,
                message.messageId,
                message.senderOpenId,
                message.senderOpenId,
                text,
                attachments,
                quote
            )
        )
    }

    private fun queryParam(
        url: String,
        name: String,
    ): String? =
        runCatching { URI(url).rawQuery }.getOrNull()
            ?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it[0] == name }
            ?.getOrNull(1)
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }

    private companion object {
        const val DEAD_AFTER_PINGS = 3
        const val COMBINE_TTL_MILLIS = 10_000L
        const val SEEN_CAPACITY = 1000
    }
}
