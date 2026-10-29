package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.FrameListener
import com.github.izerui.imux.bridge.channel.FrameTransport
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.channel.SlidingWindowLimiter
import com.github.izerui.imux.bridge.channel.WebSocketHandshakeRejected
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.DeliveryResult
import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FeishuConnectionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class FakeTransport(
        private val opens: AtomicInteger,
        private val reject: WebSocketHandshakeRejected?,
    ) : FrameTransport {
        val frames = CopyOnWriteArrayList<FeishuFrame>()

        @Volatile
        var listener: FrameListener? = null

        override suspend fun open(
            url: String,
            listener: FrameListener,
        ) {
            opens.incrementAndGet()
            reject?.let { throw it }
            this.listener = listener
        }

        override suspend fun send(text: String) = Unit

        override suspend fun sendBinary(bytes: ByteArray) {
            frames += FeishuFrameCodec.decode(bytes)
        }

        override fun close() {
            listener?.onClosed("closed")
            listener = null
        }

        fun emit(frame: FeishuFrame) = listener!!.onBinary(FeishuFrameCodec.encode(frame))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val statuses = CopyOnWriteArrayList<ConnectionStatus>()
    private val received = CopyOnWriteArrayList<InboundMessage>()
    private val opens = AtomicInteger()
    private val transports = CopyOnWriteArrayList<FakeTransport>()

    @Volatile
    private var sendCode = 0

    @Volatile
    private var endpointBody = endpoint(pingSeconds = 1_000)

    @Volatile
    private var quoteDelayMillis = 0L

    /** 取引用在此等待放行；用来确定地证明确认不等处理。 */
    @Volatile
    private var quoteGate: CompletableDeferred<Unit>? = null

    /** 之后新建的连接一律握手失败（[connection] 的 reject 优先）；用来模拟在线后重连失败。 */
    @Volatile
    private var laterReject: WebSocketHandshakeRejected? = null

    @Volatile
    private var speechCode = 0

    private val http =
        FakeHttpJson { method, url, _ ->
            when {
                url.endsWith("/callback/ws/endpoint") -> 200 to endpointBody
                url.endsWith("/tenant_access_token/internal") -> 200 to """{"code":0,"tenant_access_token":"T","expire":7200}"""
                method == "POST" && url.contains("/im/v1/messages?") -> 200 to """{"code":$sendCode,"msg":"m"}"""
                url.endsWith("/speech_to_text/v1/speech/file_recognize") -> 200 to """{"code":$speechCode,"msg":"m","data":{"recognition_text":"跑一下单测"}}"""
                method == "GET" && url.contains("/im/v1/messages/") -> {
                    quoteGate?.await()
                    delay(quoteDelayMillis)
                    200 to """{"code":0,"data":{"items":[{"msg_type":"text","body":{"content":"{\"text\":\"原话\"}"}}]}}"""
                }

                else -> 404 to "{}"
            }
        }

    @After
    fun tearDown() = scope.cancel()

    private fun endpoint(
        pingSeconds: Int,
        code: Int = 0,
        reconnectInterval: Int = 0,
        reconnectCount: Int = -1,
        reconnectNonce: Int = 0,
    ) =
        """{"code":$code,"msg":"e","data":{"URL":"wss://x/ws?device_id=d&service_id=7","ClientConfig":{"PingInterval":$pingSeconds,"ReconnectInterval":$reconnectInterval,"ReconnectNonce":$reconnectNonce,"ReconnectCount":$reconnectCount}}}"""

    private fun endpointCalls() = http.calls.count { it.url.endsWith("/callback/ws/endpoint") }

    private fun connection(
        reject: WebSocketHandshakeRejected? = null,
        limiter: SlidingWindowLimiter = SlidingWindowLimiter(5, 1_000),
        maxRateWaitMillis: Long = 20_000,
    ) = FeishuConnection(
        robotId = RobotId("r"),
        api = FeishuApi("cli", "sec", FeishuDomain.FEISHU, http),
        scope = scope,
        transportFactory = { FakeTransport(opens, reject ?: laterReject).also { transports += it } },
        download =
            HttpFileDownload { _, _, _ ->
                val file = temp.newFile().toPath()
                Files.write(file, javaClass.getResourceAsStream("/feishu/tone-16k-mono.opus")!!.readBytes())
                DownloadResult.Ok(file, null)
            },
        random = Random(7),
        secondMillis = 20,
        limiter = limiter,
        maxRateWaitMillis = maxRateWaitMillis,
    )

    private fun start(conn: FeishuConnection) = conn.start(InboundSink { received += it }) { statuses += it }

    private fun startAndAwaitOnline(conn: FeishuConnection) =
        runBlocking {
            start(conn)
            withTimeout(2_000) { while (ConnectionStatus.Online !in statuses) delay(5) }
        }

    /** 状态由通知协程异步交出，等到最后一个状态是 [status] 为止。 */
    private suspend fun awaitLast(status: ConnectionStatus) =
        withTimeout(2_000) { while (statuses.lastOrNull() != status) delay(5) }

    /**
     * 停止并等到“已停止”交出。状态按序入队，“已停止”之前的就是此前上报的全部状态，
     * 用它来断言“某次操作没有改状态”是确定的，不靠等待一段时间。
     */
    private suspend fun stopAndAwait(conn: FeishuConnection) {
        conn.stop()
        awaitLast(ConnectionStatus.Stopped)
    }

    private fun textEvent(
        messageId: String,
        text: String,
        parentId: String? = null,
    ) =
        """{"schema":"2.0","header":{"event_type":"im.message.receive_v1"},"event":{"sender":{"sender_id":{"open_id":"ou_me"},"sender_type":"user"},"message":{"message_id":"$messageId","chat_type":"p2p","message_type":"text","content":"{\"text\":\"$text\"}"${
            parentId?.let { ",\"parent_id\":\"$it\"" }.orEmpty()
        }}}}"""

    private fun dataFrame(
        frameId: String,
        payload: String,
        sum: Int = 1,
        seq: Int = 0,
    ) = FeishuFrame(
        service = 7,
        method = FeishuFrame.METHOD_DATA,
        headers =
            listOf(
                FeishuHeader("type", "event"),
                FeishuHeader("message_id", frameId),
                FeishuHeader("sum", "$sum"),
                FeishuHeader("seq", "$seq"),
                FeishuHeader("trace_id", "t"),
            ),
        payload = payload.toByteArray(Charsets.UTF_8),
    )

    private fun acks(transport: FakeTransport) = transport.frames.filter { it.method == FeishuFrame.METHOD_DATA }

    @Test
    fun `收到事件先回写确认，再把本人消息交出`() =
        runBlocking {
            startAndAwaitOnline(connection())
            val transport = transports.first()
            transport.emit(dataFrame("f1", textEvent("om_1", "跑一下单测")))
            withTimeout(2_000) { while (received.isEmpty() || acks(transport).isEmpty()) delay(5) }
            assertEquals(InboundMessage(RobotId("r"), "om_1", "ou_me", "ou_me", "跑一下单测"), received.single())
            val ack = acks(transport).single()
            assertEquals("f1", ack.header("message_id"))
            assertEquals("0", ack.header("biz_rt"))
            assertEquals("""{"code":200}""", String(ack.payload!!, Charsets.UTF_8))
        }

    @Test
    fun `确认不等处理：取引用很慢时确认照样先发出`() =
        runBlocking {
            // 取引用卡在闸门上，直到看到确认才放行：确认先于处理完成是确定的，不靠计时
            val gate = CompletableDeferred<Unit>()
            quoteGate = gate
            startAndAwaitOnline(connection())
            val transport = transports.first()
            transport.emit(dataFrame("f1", textEvent("om_1", "这个呢", parentId = "om_0")))
            withTimeout(2_000) { while (acks(transport).isEmpty()) delay(5) }
            assertTrue(received.isEmpty())
            gate.complete(Unit)
            withTimeout(2_000) { while (received.isEmpty()) delay(5) }
            assertEquals("原话", received.single().quote)
        }

    @Test
    fun `按到达顺序交出：前一条取引用慢，后一条也不会抢先`() =
        runBlocking {
            quoteDelayMillis = 200
            startAndAwaitOnline(connection())
            val transport = transports.first()
            transport.emit(dataFrame("f1", textEvent("om_1", "一", parentId = "om_0")))
            transport.emit(dataFrame("f2", textEvent("om_2", "二")))
            withTimeout(2_000) { while (received.size < 2) delay(5) }
            assertEquals(listOf("om_1", "om_2"), received.map { it.externalMessageId })
        }

    @Test
    fun `分片按 seq 拼接，重投按消息 id 去重`() =
        runBlocking {
            startAndAwaitOnline(connection())
            val transport = transports.first()
            val payload = textEvent("om_1", "拼起来")
            val half = payload.length / 2
            transport.emit(dataFrame("f1", payload.substring(half), sum = 2, seq = 1))
            transport.emit(dataFrame("f1", payload.substring(0, half), sum = 2, seq = 0))
            transport.emit(dataFrame("f2", payload))
            withTimeout(2_000) { while (received.isEmpty()) delay(5) }
            delay(100)
            assertEquals(listOf("拼起来"), received.map { it.text })
        }

    @Test
    fun `按服务端间隔发 ping；长时间没有任何帧就判定断线并重连`() =
        runBlocking {
            endpointBody = endpoint(pingSeconds = 1)
            startAndAwaitOnline(connection())
            withTimeout(2_000) { while (transports.first().frames.none { it.header("type") == "ping" }) delay(5) }
            val ping = transports.first().frames.first { it.header("type") == "ping" }
            assertEquals(7, ping.service)
            assertEquals(FeishuFrame.METHOD_CONTROL, ping.method)
            withTimeout(2_000) { while (opens.get() < 2) delay(5) }
        }

    @Test
    fun `连接数超限或 403 不重连`() =
        runBlocking {
            start(
                connection(
                    WebSocketHandshakeRejected(
                        400,
                        mapOf(
                            "handshake-status" to "514",
                            "handshake-autherrcode" to "1000040350",
                            "handshake-msg" to "limit"
                        )
                    )
                )
            )
            withTimeout(2_000) { while (statuses.none { it is ConnectionStatus.Failed }) delay(5) }
            delay(100)
            assertEquals(1, opens.get())
        }

    @Test
    fun `取地址返回凭据错误时不重连`() =
        runBlocking {
            endpointBody = endpoint(pingSeconds = 1_000, code = 10015)
            start(connection())
            withTimeout(2_000) { while (statuses.none { it is ConnectionStatus.Failed }) delay(5) }
            assertEquals(0, opens.get())
        }

    @Test
    fun `额度用完报发送受阻，之后发送成功恢复在线`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            sendCode = 99991403
            assertTrue(conn.send(OutboundMessage(RobotId("r"), "ou_me", "结论")) is DeliveryResult.Rejected)
            awaitLast(ConnectionStatus.SendFailing("bridge.feishu.quota"))
            sendCode = 0
            assertEquals(DeliveryResult.Accepted, conn.send(OutboundMessage(RobotId("r"), "ou_me", "结论")))
            stopAndAwait(conn)
            assertEquals(
                listOf(
                    ConnectionStatus.Connecting,
                    ConnectionStatus.Online,
                    ConnectionStatus.SendFailing("bridge.feishu.quota"),
                    ConnectionStatus.Online,
                    ConnectionStatus.Stopped
                ),
                statuses.toList(),
            )
        }

    @Test
    fun `缺权限带上错误码；限频不改状态`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            sendCode = 230013
            conn.send(OutboundMessage(RobotId("r"), "ou_me", "x"))
            awaitLast(ConnectionStatus.SendFailing("bridge.feishu.permission", "230013"))
            sendCode = 99991400
            assertEquals(
                DeliveryResult.Rejected("rate limited"),
                conn.send(OutboundMessage(RobotId("r"), "ou_me", "x"))
            )
            stopAndAwait(conn)
            assertEquals(
                listOf(
                    ConnectionStatus.SendFailing("bridge.feishu.permission", "230013"),
                    ConnectionStatus.Stopped
                ), statuses.takeLast(2)
            )
        }

    @Test
    fun `正文转义后超过 30 KB 时不发`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            val before = http.calls.size
            assertEquals(
                DeliveryResult.Rejected("too large"),
                conn.send(OutboundMessage(RobotId("r"), "ou_me", "\"".repeat(9_000)))
            )
            assertEquals(before, http.calls.size)
        }

    private fun audioEvent(messageId: String) =
        """{"schema":"2.0","header":{"event_type":"im.message.receive_v1"},"event":{"sender":{"sender_id":{"open_id":"ou_me"},"sender_type":"user"},"message":{"message_id":"$messageId","chat_type":"p2p","message_type":"audio","content":"{\"file_key\":\"a_1\",\"duration\":1000}"}}}"""

    @Test
    fun `语音识别成功时作为文字转交，不再附带音频`() =
        runBlocking {
            startAndAwaitOnline(connection())
            transports.first().emit(dataFrame("f1", audioEvent("om_v")))
            withTimeout(5_000) { while (received.isEmpty()) delay(5) }
            assertEquals("跑一下单测", received.single().text)
            assertTrue(received.single().attachments.isEmpty())
        }

    @Test
    fun `识别失败时退回音频附件`() =
        runBlocking {
            speechCode = 99991672
            startAndAwaitOnline(connection())
            transports.first().emit(dataFrame("f1", audioEvent("om_v")))
            withTimeout(5_000) { while (received.isEmpty()) delay(5) }
            assertEquals("", received.single().text)
            assertEquals(listOf(AttachmentKind.AUDIO), received.single().attachments.map { it.kind })
        }

    @Test
    fun `停止后上报已停止，不再发送`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            stopAndAwait(conn)
            assertEquals(DeliveryResult.Rejected("offline"), conn.send(OutboundMessage(RobotId("r"), "ou_me", "x")))
        }

    @Test
    fun `握手 403 不重连`() =
        runBlocking {
            start(connection(WebSocketHandshakeRejected(403, emptyMap())))
            withTimeout(2_000) { while (statuses.none { it is ConnectionStatus.Failed }) delay(5) }
            delay(100)
            assertEquals(1, opens.get())
            assertEquals(ConnectionStatus.Failed("handshake 403"), statuses.last())
        }

    @Test
    fun `长连接已失败时，发送受阻与发送成功都不改显示的状态`() =
        runBlocking {
            val conn = connection(WebSocketHandshakeRejected(403, emptyMap()))
            start(conn)
            withTimeout(2_000) { while (statuses.none { it is ConnectionStatus.Failed }) delay(5) }
            sendCode = 99991403
            assertTrue(conn.send(OutboundMessage(RobotId("r"), "ou_me", "结论")) is DeliveryResult.Rejected)
            sendCode = 0
            assertEquals(DeliveryResult.Accepted, conn.send(OutboundMessage(RobotId("r"), "ou_me", "结论")))
            stopAndAwait(conn)
            assertEquals(
                listOf(
                    ConnectionStatus.Connecting,
                    ConnectionStatus.Failed("handshake 403"),
                    ConnectionStatus.Stopped
                ), statuses.toList()
            )
        }

    /**
     * 复现 RobotManager 的形状：监听器要拿外部锁，另一线程持有该锁调 stop()，同时一次发送受阻正在上报。
     * 用闩锁强制交错：监听器已进入（正等外部锁）时才在持锁线程里 stop()。持锁回调监听器的实现会在这里死锁。
     */
    @Test
    fun `持外部锁调 stop 与发送受阻上报交错时不死锁`() =
        runBlocking {
            val external = Any()
            val inListener = CountDownLatch(1)
            val conn = connection()
            conn.start(InboundSink { received += it }) { status ->
                if (status is ConnectionStatus.SendFailing) inListener.countDown()
                synchronized(external) { statuses += status }
            }
            withTimeout(2_000) { while (ConnectionStatus.Online !in statuses) delay(5) }
            sendCode = 99991403
            val listenerEntered = AtomicBoolean()
            val stopReturned = CountDownLatch(1)
            val holder =
                Thread {
                    synchronized(external) {
                        scope.launch { conn.send(OutboundMessage(RobotId("r"), "ou_me", "x")) }
                        if (inListener.await(2, TimeUnit.SECONDS)) {
                            listenerEntered.set(true)
                            conn.stop()
                        }
                        stopReturned.countDown()
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            assertTrue("持锁线程没有返回，stop() 死锁", stopReturned.await(3, TimeUnit.SECONDS))
            assertTrue(listenerEntered.get())
            holder.join(1_000)
            awaitLast(ConnectionStatus.Stopped)
            assertEquals(
                listOf(ConnectionStatus.SendFailing("bridge.feishu.quota"), ConnectionStatus.Stopped),
                statuses.takeLast(2)
            )
        }

    @Test
    fun `首次连接失败立即报正在重试，不等下一次尝试`() =
        runBlocking {
            // Random(7) 首个 nextDouble 约 0.73，ReconnectNonce=1000 时首次重连要等约 14.6 秒
            endpointBody = endpoint(pingSeconds = 1_000, reconnectNonce = 1_000)
            val conn = connection(WebSocketHandshakeRejected(500, emptyMap()))
            start(conn)
            withTimeout(2_000) { while (statuses.none { it is ConnectionStatus.Retrying }) delay(5) }
            assertEquals(1, opens.get())
            stopAndAwait(conn)
            assertEquals(listOf(ConnectionStatus.Connecting, ConnectionStatus.Retrying("handshake 500"), ConnectionStatus.Stopped), statuses.toList())
        }

    @Test
    fun `连续重连失败一直显示正在重试，不闪回连接中`() =
        runBlocking {
            endpointBody = endpoint(pingSeconds = 1_000, reconnectInterval = 1)
            val conn = connection(WebSocketHandshakeRejected(500, emptyMap()))
            start(conn)
            withTimeout(2_000) { while (opens.get() < 4) delay(5) }
            stopAndAwait(conn)
            assertEquals(listOf(ConnectionStatus.Connecting, ConnectionStatus.Retrying("handshake 500"), ConnectionStatus.Stopped), statuses.toList())
        }

    @Test
    fun `在线后被断开，首次重连报连接中，这次重连失败立即报正在重试`() =
        runBlocking {
            // 重连间隔 20 秒：2 秒内看到“正在重试”说明是失败当下报的，而不是等到下一次尝试
            endpointBody = endpoint(pingSeconds = 1_000, reconnectInterval = 1_000)
            val conn = connection()
            startAndAwaitOnline(conn)
            laterReject = WebSocketHandshakeRejected(500, emptyMap())
            transports.first().close()
            withTimeout(2_000) { while (statuses.none { it is ConnectionStatus.Retrying }) delay(5) }
            assertEquals(2, opens.get())
            stopAndAwait(conn)
            assertEquals(
                listOf(
                    ConnectionStatus.Connecting,
                    ConnectionStatus.Online,
                    ConnectionStatus.Connecting,
                    ConnectionStatus.Retrying("handshake 500"),
                    ConnectionStatus.Stopped,
                ),
                statuses.toList(),
            )
        }

    @Test
    fun `在线后被服务端断开，重连时报连接中而不是正在重试`() =
        runBlocking {
            startAndAwaitOnline(connection())
            transports.first().close()
            withTimeout(2_000) { while (statuses.count { it == ConnectionStatus.Online } < 2) delay(5) }
            assertEquals(
                listOf(
                    ConnectionStatus.Connecting,
                    ConnectionStatus.Online,
                    ConnectionStatus.Connecting,
                    ConnectionStatus.Online
                ),
                statuses.toList(),
            )
        }

    @Test
    fun `重连次数用完报失败`() =
        runBlocking {
            endpointBody = endpoint(pingSeconds = 1_000, reconnectInterval = 1, reconnectCount = 2)
            start(connection(WebSocketHandshakeRejected(500, emptyMap())))
            withTimeout(2_000) { while (statuses.none { it is ConnectionStatus.Failed }) delay(5) }
            delay(100)
            assertEquals(3, opens.get())
            assertEquals(ConnectionStatus.Failed("handshake 500"), statuses.last())
        }

    @Test
    fun `取地址返回系统繁忙时重试`() =
        runBlocking {
            endpointBody = endpoint(pingSeconds = 1_000, code = 1)
            start(connection())
            withTimeout(2_000) { while (endpointCalls() < 2) delay(5) }
            assertEquals(0, opens.get())
            assertTrue(statuses.none { it is ConnectionStatus.Failed })
        }

    @Test
    fun `card 帧不交出也不回写确认`() =
        runBlocking {
            startAndAwaitOnline(connection())
            val transport = transports.first()
            val card = dataFrame("c1", textEvent("om_c", "卡片"))
            transport.emit(card.copy(headers = card.headers.map {
                if (it.key == "type") FeishuHeader(
                    "type",
                    "card"
                ) else it
            }, payload = card.payload))
            transport.emit(dataFrame("f2", textEvent("om_2", "二")))
            withTimeout(2_000) { while (received.isEmpty() || acks(transport).isEmpty()) delay(5) }
            delay(100)
            assertEquals(listOf("om_2"), received.map { it.externalMessageId })
            assertEquals(listOf("f2"), acks(transport).map { it.header("message_id") })
        }

    @Test
    fun `正文过大不占限频名额`() =
        runBlocking {
            // 时钟冻结、不允许等待：名额一旦被占满，第 6 条会立即被拒为限频
            val conn = connection(limiter = SlidingWindowLimiter(5, 1_000) { 0L }, maxRateWaitMillis = 0)
            startAndAwaitOnline(conn)
            repeat(5) {
                assertEquals(
                    DeliveryResult.Rejected("too large"),
                    conn.send(OutboundMessage(RobotId("r"), "ou_me", "\"".repeat(9_000)))
                )
            }
            assertEquals(DeliveryResult.Accepted, conn.send(OutboundMessage(RobotId("r"), "ou_me", "x")))
        }
}
