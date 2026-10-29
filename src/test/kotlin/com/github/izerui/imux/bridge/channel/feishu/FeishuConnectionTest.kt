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
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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
import java.io.IOException
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
        private val openError: Exception? = null,
        /** ping 发送永不返回，模拟写入挂起。 */
        private val hangPings: Boolean = false,
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
            openError?.let { throw it }
            this.listener = listener
        }

        override suspend fun send(text: String) = Unit

        override suspend fun sendBinary(bytes: ByteArray) {
            val frame = FeishuFrameCodec.decode(bytes)
            if (hangPings && frame.header("type") == "ping") awaitCancellation()
            frames += frame
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

    /** 取引用在此等待放行；用来确定地证明确认等待处理完成。 */
    @Volatile
    private var quoteGate: CompletableDeferred<Unit>? = null

    private val quoteEntered = CompletableDeferred<Unit>()

    /** 之后新建的连接一律握手失败（[connection] 的 reject 优先）；用来模拟在线后重连失败。 */
    @Volatile
    private var laterReject: WebSocketHandshakeRejected? = null

    @Volatile
    private var speechCode = 0

    /** 之后新建的连接 open 时抛出此异常。 */
    @Volatile
    private var laterOpenError: Exception? = null

    @Volatile
    private var hangPings = false

    /** 取地址在此等待放行；用来让链路停在“连接中”。 */
    @Volatile
    private var endpointGate: CompletableDeferred<Unit>? = null

    private val http =
        FakeHttpJson { method, url, _ ->
            when {
                url.endsWith("/callback/ws/endpoint") -> {
                    endpointGate?.await()
                    200 to endpointBody
                }

                url.endsWith("/tenant_access_token/internal") -> 200 to """{"code":0,"tenant_access_token":"T","expire":7200}"""
                method == "POST" && url.contains("/im/v1/messages?") -> 200 to """{"code":$sendCode,"msg":"m"}"""
                url.endsWith("/speech_to_text/v1/speech/file_recognize") -> 200 to """{"code":$speechCode,"msg":"m","data":{"recognition_text":"跑一下单测"}}"""
                method == "GET" && url.contains("/im/v1/messages/") -> {
                    quoteEntered.complete(Unit)
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
        transportFactory = {
            FakeTransport(opens, reject ?: laterReject, laterOpenError, hangPings).also { transports += it }
        },
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
    fun `收到事件处理成功后回写确认`() =
        runBlocking {
            startAndAwaitOnline(connection())
            val transport = transports.first()
            transport.emit(dataFrame("f1", textEvent("om_1", "跑一下单测")))
            withTimeout(2_000) { while (received.isEmpty() || acks(transport).isEmpty()) delay(5) }
            assertEquals(InboundMessage(RobotId("r"), "om_1", "ou_me", "ou_me", "跑一下单测"), received.single())
            val ack = acks(transport).single()
            assertEquals("f1", ack.header("message_id"))
            assertTrue(ack.header("biz_rt")!!.toLong() >= 0)
            assertEquals("""{"code":200}""", String(ack.payload!!, Charsets.UTF_8))
        }

    @Test
    fun `取引用完成并交出消息后才确认`() =
        runBlocking {
            // 业务已进入且阻塞时不得提前确认；放行后才允许成功确认。
            val gate = CompletableDeferred<Unit>()
            quoteGate = gate
            startAndAwaitOnline(connection())
            val transport = transports.first()
            transport.emit(dataFrame("f1", textEvent("om_1", "这个呢", parentId = "om_0")))
            withTimeout(2_000) { quoteEntered.await() }
            assertTrue(acks(transport).isEmpty())
            assertTrue(received.isEmpty())
            gate.complete(Unit)
            withTimeout(2_000) { while (received.isEmpty() || acks(transport).isEmpty()) delay(5) }
            assertEquals("原话", received.single().quote)
            assertEquals("""{"code":200}""", String(acks(transport).single().payload!!, Charsets.UTF_8))
        }

    @Test
    fun `业务处理异常回五百，重投可以重新处理`() =
        runBlocking {
            val attempts = AtomicInteger()
            val conn = connection()
            conn.start(InboundSink {
                if (attempts.incrementAndGet() == 1) error("failed")
                received += it
            }) { statuses += it }
            awaitLast(ConnectionStatus.Online)
            val transport = transports.first()
            val event = dataFrame("f1", textEvent("om_1", "重试"))
            transport.emit(event)
            withTimeout(2_000) { while (acks(transport).isEmpty()) delay(5) }
            assertEquals("""{"code":500}""", String(acks(transport).single().payload!!, Charsets.UTF_8))
            // 与官方 SDK 一致：biz_rt 只在处理成功后写入
            assertEquals(null, acks(transport).single().header("biz_rt"))
            assertTrue(received.isEmpty())
            transport.emit(event)
            withTimeout(2_000) { while (acks(transport).size < 2) delay(5) }
            assertEquals(listOf("重试"), received.map { it.text })
            assertEquals("""{"code":200}""", String(acks(transport).last().payload!!, Charsets.UTF_8))
            assertTrue(acks(transport).last().header("biz_rt")!!.toLong() >= 0)
        }

    @Test
    fun `缺片不确认，总数不一致不拼接，补齐才交出`() =
        runBlocking {
            startAndAwaitOnline(connection())
            val transport = transports.first()
            val payload = textEvent("om_1", "拼起来")
            val half = payload.length / 2
            transport.emit(dataFrame("f1", payload.substring(0, half), sum = 2, seq = 0))
            transport.emit(dataFrame("f1", payload.substring(half), sum = 3, seq = 1))
            assertTrue(acks(transport).isEmpty())
            assertTrue(received.isEmpty())
            transport.emit(dataFrame("f1", payload.substring(half), sum = 2, seq = 1))
            withTimeout(2_000) { while (acks(transport).isEmpty()) delay(5) }
            assertEquals(listOf("拼起来"), received.map { it.text })
            assertEquals(1, acks(transport).size)
        }

    @Test
    fun `非法分片元数据不交出也不确认`() =
        runBlocking {
            startAndAwaitOnline(connection())
            val transport = transports.first()
            val payload = textEvent("om_bad", "非法")
            val invalid = listOf(
                dataFrame("zero", payload, sum = 0),
                dataFrame("negative", payload, sum = -1),
                dataFrame("seq", payload, seq = 1),
                dataFrame("negativeSeq", payload, seq = -1),
                dataFrame("missing", payload).copy(
                    headers = listOf(
                        FeishuHeader("type", "event"),
                        FeishuHeader("message_id", "missing")
                    )
                ),
                dataFrame("json", "{"),
            )
            invalid.forEach(transport::emit)
            transport.emit(dataFrame("valid", textEvent("om_valid", "正常")))
            withTimeout(2_000) { while (acks(transport).isEmpty()) delay(5) }
            assertEquals(listOf("正常"), received.map { it.text })
            assertEquals(listOf("valid"), acks(transport).map { it.header("message_id") })
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
            // 屏障：按序交出，om_3 交出时重投的 f2 一定已处理完
            transport.emit(dataFrame("f3", textEvent("om_3", "三")))
            withTimeout(2_000) { while (received.size < 2) delay(5) }
            assertEquals(listOf("拼起来", "三"), received.map { it.text })
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
            val conn =
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
            start(conn)
            withTimeout(2_000) { while (statuses.none { it is ConnectionStatus.Failed }) delay(5) }
            // 运行循环报“失败”后立即退出；停止后“已停止”之前的就是全部状态
            stopAndAwait(conn)
            assertEquals(1, opens.get())
            assertEquals(1, statuses.count { it is ConnectionStatus.Failed })
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

    /** 发消息请求里的 md 正文。 */
    private fun sentTexts(from: Int) =
        http.calls.drop(from).filter { it.method == "POST" && it.url.contains("/im/v1/messages?") }.map { call ->
            val content = JsonParser.parseString(call.body).asJsonObject["content"].asString
            JsonParser.parseString(content).asJsonObject["zh_cn"].asJsonObject["content"]
                .asJsonArray[0].asJsonArray[0].asJsonObject["text"].asString
        }

    @Test
    fun `15000 字节全是引号的正文转义后超过 30 KB，在渠道内分段发出`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            val before = http.calls.size
            val text = "\"".repeat(15_000)
            assertEquals(DeliveryResult.Accepted, conn.send(OutboundMessage(RobotId("r"), "ou_me", text)))
            val bodies = http.calls.drop(before).filter { it.method == "POST" && it.url.contains("/im/v1/messages?") }
            assertTrue(bodies.size >= 2)
            assertTrue(bodies.all { it.body!!.toByteArray(Charsets.UTF_8).size <= FeishuApi.MAX_POST_BODY_BYTES })
            assertEquals(text, sentTexts(before).joinToString(""))
        }

    @Test
    fun `分段优先在换行处切开`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            val before = http.calls.size
            val first = "\"".repeat(5_000) + "\n"
            val second = "\"".repeat(6_000)
            assertEquals(DeliveryResult.Accepted, conn.send(OutboundMessage(RobotId("r"), "ou_me", first + second)))
            assertEquals(listOf(first, second), sentTexts(before))
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
            val conn = connection(WebSocketHandshakeRejected(403, emptyMap()))
            start(conn)
            withTimeout(2_000) { while (statuses.none { it is ConnectionStatus.Failed }) delay(5) }
            stopAndAwait(conn)
            assertEquals(1, opens.get())
            assertEquals(
                listOf(ConnectionStatus.Failed("handshake 403"), ConnectionStatus.Stopped),
                statuses.takeLast(2)
            )
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
            assertEquals(
                listOf(
                    ConnectionStatus.Connecting,
                    ConnectionStatus.Retrying("handshake 500"),
                    ConnectionStatus.Stopped
                ), statuses.toList()
            )
        }

    @Test
    fun `连续重连失败一直显示正在重试，不闪回连接中`() =
        runBlocking {
            endpointBody = endpoint(pingSeconds = 1_000, reconnectInterval = 1)
            val conn = connection(WebSocketHandshakeRejected(500, emptyMap()))
            start(conn)
            withTimeout(2_000) { while (opens.get() < 4) delay(5) }
            stopAndAwait(conn)
            assertEquals(
                listOf(
                    ConnectionStatus.Connecting,
                    ConnectionStatus.Retrying("handshake 500"),
                    ConnectionStatus.Stopped
                ), statuses.toList()
            )
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
            val conn = connection(WebSocketHandshakeRejected(500, emptyMap()))
            start(conn)
            withTimeout(2_000) { while (statuses.none { it is ConnectionStatus.Failed }) delay(5) }
            stopAndAwait(conn)
            assertEquals(3, opens.get())
            assertEquals(
                listOf(ConnectionStatus.Failed("handshake 500"), ConnectionStatus.Stopped),
                statuses.takeLast(2)
            )
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
            // 帧在 emit 内同步分拣、按序处理：f2 交出并确认时，此前的 card 帧已被丢弃
            withTimeout(2_000) { while (received.isEmpty() || acks(transport).isEmpty()) delay(5) }
            assertEquals(listOf("om_2"), received.map { it.externalMessageId })
            assertEquals(listOf("f2"), acks(transport).map { it.header("message_id") })
        }

    @Test
    fun `正文过大分段发送，每段各占一个限频名额`() =
        runBlocking {
            // 时钟冻结、不允许等待：名额一旦被占满，下一段会立即被拒为限频
            val conn = connection(limiter = SlidingWindowLimiter(5, 1_000) { 0L }, maxRateWaitMillis = 0)
            startAndAwaitOnline(conn)
            val before = http.calls.size
            // 9000 个引号转义后约 36 KB，切成两段
            assertEquals(DeliveryResult.Accepted, conn.send(OutboundMessage(RobotId("r"), "ou_me", "\"".repeat(9_000))))
            assertEquals(2, sentTexts(before).size)
            repeat(3) { assertEquals(DeliveryResult.Accepted, conn.send(OutboundMessage(RobotId("r"), "ou_me", "x"))) }
            assertEquals(
                DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED),
                conn.send(OutboundMessage(RobotId("r"), "ou_me", "x"))
            )
        }

    @Test
    fun `分段中某段失败即返回该失败，已发出的不撤回`() =
        runBlocking {
            val conn = connection(limiter = SlidingWindowLimiter(1, 1_000) { 0L }, maxRateWaitMillis = 0)
            startAndAwaitOnline(conn)
            val before = http.calls.size
            assertEquals(
                DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED),
                conn.send(OutboundMessage(RobotId("r"), "ou_me", "\"".repeat(9_000)))
            )
            assertEquals(1, sentTexts(before).size)
        }

    @Test
    fun `受阻后服务端断开重连，上线后仍显示发送受阻，发送成功才回到在线`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            sendCode = 99991403
            conn.send(OutboundMessage(RobotId("r"), "ou_me", "x"))
            awaitLast(ConnectionStatus.SendFailing("bridge.feishu.quota"))
            transports.first().close()
            withTimeout(2_000) {
                while (opens.get() < 2 || statuses.last() != ConnectionStatus.SendFailing("bridge.feishu.quota")) delay(5)
            }
            sendCode = 0
            assertEquals(DeliveryResult.Accepted, conn.send(OutboundMessage(RobotId("r"), "ou_me", "x")))
            stopAndAwait(conn)
            assertEquals(
                listOf(
                    ConnectionStatus.Connecting,
                    ConnectionStatus.Online,
                    ConnectionStatus.SendFailing("bridge.feishu.quota"),
                    ConnectionStatus.Connecting,
                    ConnectionStatus.SendFailing("bridge.feishu.quota"),
                    ConnectionStatus.Online,
                    ConnectionStatus.Stopped,
                ),
                statuses.toList(),
            )
        }

    @Test
    fun `链路重连期间发送被拒，记下但不显示，上线后显示发送受阻`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            val gate = CompletableDeferred<Unit>()
            endpointGate = gate
            transports.first().close()
            // 第二次取地址被挡住，链路停在“连接中”
            withTimeout(2_000) { while (endpointCalls() < 2) delay(5) }
            awaitLast(ConnectionStatus.Connecting)
            sendCode = 99991403
            assertTrue(conn.send(OutboundMessage(RobotId("r"), "ou_me", "x")) is DeliveryResult.Rejected)
            gate.complete(Unit)
            awaitLast(ConnectionStatus.SendFailing("bridge.feishu.quota"))
            stopAndAwait(conn)
            assertEquals(
                listOf(
                    ConnectionStatus.Connecting,
                    ConnectionStatus.Online,
                    ConnectionStatus.Connecting,
                    ConnectionStatus.SendFailing("bridge.feishu.quota"),
                    ConnectionStatus.Stopped,
                ),
                statuses.toList(),
            )
        }

    @Test
    fun `ping 发送挂起时仍按周期检查存活，判死重连`() =
        runBlocking {
            endpointBody = endpoint(pingSeconds = 1)
            hangPings = true
            startAndAwaitOnline(connection())
            withTimeout(2_000) { while (opens.get() < 2) delay(5) }
        }

    @Test
    fun `在线后被断开立即报连接中，不等重连抖动`() =
        runBlocking {
            // Random(7) 首个 nextDouble 约 0.73，ReconnectNonce=1000 时首次重连要等约 14.6 秒
            endpointBody = endpoint(pingSeconds = 1_000, reconnectNonce = 1_000)
            val conn = connection()
            startAndAwaitOnline(conn)
            transports.first().close()
            awaitLast(ConnectionStatus.Connecting)
            assertEquals(1, opens.get())
            stopAndAwait(conn)
            assertEquals(
                listOf(
                    ConnectionStatus.Connecting,
                    ConnectionStatus.Online,
                    ConnectionStatus.Connecting,
                    ConnectionStatus.Stopped,
                ),
                statuses.toList(),
            )
        }

    @Test
    fun `建连异常的原因只带异常类名，不带地址里的凭据`() =
        runBlocking {
            endpointBody = endpoint(pingSeconds = 1_000, reconnectNonce = 1_000)
            laterOpenError = IOException("connect wss://x/ws?service_id=7&access_key=secret failed")
            val conn = connection()
            start(conn)
            withTimeout(2_000) { while (statuses.none { it is ConnectionStatus.Retrying }) delay(5) }
            val reason = statuses.filterIsInstance<ConnectionStatus.Retrying>().single().reason
            assertTrue(reason, "secret" !in reason)
            assertEquals("IOException", reason)
            stopAndAwait(conn)
        }

}
