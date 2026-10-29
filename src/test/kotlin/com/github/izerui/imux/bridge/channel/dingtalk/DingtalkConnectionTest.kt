package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.FrameListener
import com.github.izerui.imux.bridge.channel.FrameTransport
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.channel.SlidingWindowLimiter
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.DeliveryResult
import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.URISyntaxException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DingtalkConnectionTest {
    private class FakeTransport(
        private val opens: AtomicInteger,
        private val autoPong: Boolean,
        private val closeOnOpen: Boolean = false,
        private val openError: Exception? = null,
        private val hangPing: Boolean = false,
        /**
         * open() 返回前同步向 listener 发一帧（模拟 JDK 握手后执行器线程立即派发帧）。
         * 默认发一个系统 ping：连接收到第一帧才上线，且与官方 SDK 一致不在握手后主动 ping，没有这帧要等到首次空闲 ping。
         */
        private val emitOnOpen: String? = GREETING,
    ) : FrameTransport {
        val sent = CopyOnWriteArrayList<String>()
        val pings = AtomicInteger()

        @Volatile
        var url: String? = null

        @Volatile
        var listener: FrameListener? = null

        /** 真实 WebSocket 关闭后不再响应 ping。 */
        @Volatile
        private var closed = false

        override suspend fun open(
            url: String,
            listener: FrameListener,
        ) {
            opens.incrementAndGet()
            this.url = url
            openError?.let { throw it }
            this.listener = listener
            // open() 返回前就派发帧，复现 session 赋值前收到帧的窗口
            emitOnOpen?.let { listener.onText(it) }
            // 握手成功但服务端立刻关连接，一帧都没有
            if (closeOnOpen) {
                closed = true
                listener.onClosed("closed by server")
            }
        }

        override suspend fun send(text: String) {
            // 开场系统 ping 的回应不计入，各测试只看业务回包
            if (GREETING_ID in text) return
            sent += text
        }

        override suspend fun sendPing() {
            pings.incrementAndGet()
            if (closed) return
            if (hangPing) awaitCancellation()
            if (autoPong) listener?.onPong()
        }

        override fun close() {
            closed = true
            listener?.onClosed("closed")
            listener = null
        }

        fun emit(text: String) = listener!!.onText(text)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val statuses = CopyOnWriteArrayList<ConnectionStatus>()
    private val received = CopyOnWriteArrayList<InboundMessage>()
    private val opens = AtomicInteger()
    private val transports = CopyOnWriteArrayList<FakeTransport>()

    @Volatile
    private var openAnswer: Pair<Int, String> = 200 to """{"endpoint":"wss://x/connect","ticket":"t1"}"""

    @Volatile
    private var sendAnswer: Pair<Int, String> = 200 to """{"processQueryKey":"q","invalidStaffIdList":[],"flowControlledStaffIdList":[]}"""

    @Volatile
    private var tokenAnswer: Pair<Int, String> = 200 to """{"accessToken":"T","expireIn":7200}"""

    /** 为 true 时 batchSend 抛异常，模拟网络错误。 */
    @Volatile
    private var sendThrows = false

    /** 每次调 connections/open 的时刻（纳秒）。 */
    private val openCallTimes = CopyOnWriteArrayList<Long>()

    private val http =
        DingtalkFakeHttp { url, _ ->
            when {
                url.endsWith("/v1.0/gateway/connections/open") -> {
                    openCallTimes += System.nanoTime()
                    openAnswer
                }

                url.endsWith("/v1.0/oauth2/accessToken") -> tokenAnswer
                url.endsWith("/v1.0/robot/oToMessages/batchSend") -> if (sendThrows) throw IOException("reset") else sendAnswer
                url.endsWith("/v1.0/robot/messageFiles/download") -> 200 to """{"downloadUrl":"https://oss/x"}"""
                else -> 404 to "{}"
            }
        }

    @After
    fun tearDown() = scope.cancel()

    private fun connection(
        autoPong: Boolean = true,
        pingIntervalMillis: Long = 60_000,
        deadAfterMillis: Long = 120_000,
        limiter: SlidingWindowLimiter = SlidingWindowLimiter(5, 1_000),
        backoffBaseMillis: Long = 1,
        backoffMaxMillis: Long = 5,
        closeOnOpen: Boolean = false,
        openError: Exception? = null,
        hangPing: Boolean = false,
        emitOnOpen: String? = GREETING,
    ) = DingtalkConnection(
        robotId = RobotId("r"),
        api = DingtalkApi("cid", "sec", http),
        scope = scope,
        transportFactory = { FakeTransport(opens, autoPong, closeOnOpen, openError, hangPing, emitOnOpen).also { transports += it } },
        download = HttpFileDownload { _, _, _ -> DownloadResult.Failed("unused") },
        pingIntervalMillis = pingIntervalMillis,
        deadAfterMillis = deadAfterMillis,
        backoffBaseMillis = backoffBaseMillis,
        backoffMaxMillis = backoffMaxMillis,
        limiter = limiter,
    )

    private fun start(conn: DingtalkConnection) = conn.start(InboundSink { received += it }) { statuses += it }

    private fun startAndAwaitOnline(conn: DingtalkConnection) =
        runBlocking {
            start(conn)
            withTimeout(2_000) { while (ConnectionStatus.Online !in statuses) delay(5) }
        }

    private suspend fun awaitLast(status: ConnectionStatus) = withTimeout(2_000) { while (statuses.lastOrNull() != status) delay(5) }

    /** 等到第 [from] 条之后先出现“连接中”、再以 [status] 结尾：确认重连已发生，且上线后显示的是 [status]。 */
    private suspend fun awaitAfter(
        from: Int,
        status: ConnectionStatus,
    ) {
        withTimeout(2_000) { while (ConnectionStatus.Connecting !in statuses.drop(from)) delay(5) }
        awaitLast(status)
    }

    private suspend fun stopAndAwait(conn: DingtalkConnection) {
        conn.stop()
        awaitLast(ConnectionStatus.Stopped)
    }

    private fun callback(
        headerId: String,
        data: String,
    ) = JsonObject().apply {
        addProperty("specVersion", "1.0")
        addProperty("type", "CALLBACK")
        add(
            "headers",
            JsonObject().apply {
                addProperty("topic", "/v1.0/im/bot/messages/get")
                addProperty("messageId", headerId)
                addProperty("contentType", "application/json")
            },
        )
        addProperty("data", data)
    }.toString()

    private fun system(
        topic: String,
        headerId: String,
        data: String,
    ) = JsonObject().apply {
        addProperty("specVersion", "1.0")
        addProperty("type", "SYSTEM")
        add(
            "headers",
            JsonObject().apply {
                addProperty("topic", topic)
                addProperty("messageId", headerId)
            },
        )
        addProperty("data", data)
    }.toString()

    private fun textData(
        msgId: String,
        text: String,
        staffId: String? = "u1",
    ) = JsonObject().apply {
        addProperty("conversationType", "1")
        addProperty("msgId", msgId)
        staffId?.let { addProperty("senderStaffId", it) }
        addProperty("msgtype", "text")
        add("text", JsonObject().apply { addProperty("content", text) })
    }.toString()

    private fun replies(transport: FakeTransport) = transport.sent.map { JsonParser.parseString(it).asJsonObject }

    private fun send(conn: DingtalkConnection) = runBlocking { conn.send(OutboundMessage(RobotId("r"), "u1", "结论")) }

    @Test
    fun `收到消息先回 ACK，再交出`() =
        runBlocking {
            startAndAwaitOnline(connection())
            val transport = transports.first()
            assertEquals("wss://x/connect?ticket=t1", transport.url)
            transport.emit(callback("h1", textData("m1", "你好")))
            withTimeout(2_000) { while (received.isEmpty() || transport.sent.isEmpty()) delay(5) }
            assertEquals(InboundMessage(RobotId("r"), "m1", "u1", "u1", "你好"), received.single())
            val ack = replies(transport).single()
            assertEquals(200, ack["code"].asInt)
            assertEquals("h1", ack["headers"].asJsonObject["messageId"].asString)
            assertEquals("""{"response": null}""", ack["data"].asString)
        }

    @Test
    fun `系统 ping 原样回传`() =
        runBlocking {
            startAndAwaitOnline(connection())
            val transport = transports.first()
            transport.emit(system("ping", "p1", """{"opaque":"o"}"""))
            withTimeout(2_000) { while (transport.sent.isEmpty()) delay(5) }
            val pong = replies(transport).single()
            assertEquals("p1", pong["headers"].asJsonObject["messageId"].asString)
            assertEquals("""{"opaque":"o"}""", pong["data"].asString)
            assertTrue(received.isEmpty())
        }

    @Test
    fun `disconnect 后立即重建，不算失败`() =
        runBlocking {
            startAndAwaitOnline(connection())
            transports.first().emit(system("disconnect", "s1", "{}"))
            withTimeout(2_000) { while (statuses.count { it == ConnectionStatus.Online } < 2) delay(5) }
            assertEquals(2, http.calls("/v1.0/gateway/connections/open").size)
            assertTrue(statuses.none { it is ConnectionStatus.Retrying })
        }

    @Test
    fun `按 msgId 去重，每次推送都回 ACK`() =
        runBlocking {
            startAndAwaitOnline(connection())
            val transport = transports.first()
            transport.emit(callback("h1", textData("m1", "你好")))
            transport.emit(callback("h2", textData("m1", "你好")))
            transport.emit(callback("h3", textData("m2", "第二条")))
            withTimeout(2_000) { while (received.size < 2 || transport.sent.size < 3) delay(5) }
            delay(50)
            assertEquals(listOf("m1", "m2"), received.map { it.externalMessageId })
            assertEquals(listOf("h1", "h2", "h3"), replies(transport).map { it["headers"].asJsonObject["messageId"].asString })
        }

    @Test
    fun `按到达顺序交出`() =
        runBlocking {
            startAndAwaitOnline(connection())
            val transport = transports.first()
            (1..20).forEach { transport.emit(callback("h$it", textData("m$it", "第 $it 条"))) }
            withTimeout(2_000) { while (received.size < 20) delay(5) }
            assertEquals((1..20).map { "m$it" }, received.map { it.externalMessageId })
        }

    @Test
    fun `图片消息带附件引用`() =
        runBlocking {
            startAndAwaitOnline(connection())
            val data =
                JsonObject().apply {
                    addProperty("conversationType", "1")
                    addProperty("msgId", "m1")
                    addProperty("senderStaffId", "u1")
                    addProperty("msgtype", "picture")
                    add("content", JsonObject().apply { addProperty("downloadCode", "d1") })
                }.toString()
            transports.first().emit(callback("h1", data))
            withTimeout(2_000) { while (received.isEmpty()) delay(5) }
            assertEquals(AttachmentKind.IMAGE, received.single().attachments.single().kind)
            assertEquals("", received.single().text)
        }

    @Test
    fun `一直没有任何帧和 pong 就判定断线并重连`() =
        runBlocking {
            val conn = connection(autoPong = false, pingIntervalMillis = 20, deadAfterMillis = 60, emitOnOpen = null)
            start(conn)
            withTimeout(2_000) { while (opens.get() < 2) delay(5) }
            assertTrue(transports.first().pings.get() >= 1)
        }

    @Test
    fun `收到 pong 就算活着`() =
        runBlocking {
            startAndAwaitOnline(connection(autoPong = true, pingIntervalMillis = 20, deadAfterMillis = 500, emitOnOpen = null))
            withTimeout(2_000) { while (transports.first().pings.get() < 3) delay(5) }
            assertEquals(1, opens.get())
            assertTrue(transports.first().pings.get() >= 3)
        }

    @Test
    fun `在线后被断开，退避后重连`() =
        runBlocking {
            startAndAwaitOnline(connection())
            transports.first().listener!!.onClosed("bye")
            withTimeout(2_000) { while (opens.get() < 2) delay(5) }
        }

    @Test
    fun `凭据连续被拒五次报失败，不再重连`() =
        runBlocking {
            openAnswer = 401 to """{"code":"invalidClientIdOrSecret","message":"bad"}"""
            start(connection())
            withTimeout(2_000) { while (statuses.none { it is ConnectionStatus.Failed }) delay(5) }
            delay(50)
            assertEquals(5, http.calls("/v1.0/gateway/connections/open").size)
            assertEquals(0, opens.get())
            assertEquals(ConnectionStatus.Failed("invalidClientIdOrSecret bad"), statuses.last())
        }

    @Test
    fun `凭据错误与网络错误交替，累计五次凭据错误时报失败`() =
        runBlocking {
            // 奇数次调用返回凭据错误，偶数次返回网络错误；第 5 次凭据错误（第 9 次调用）时报 Failed
            val callCount = AtomicInteger()
            val testHttp =
                DingtalkFakeHttp { url, _ ->
                    when {
                        url.endsWith("/v1.0/gateway/connections/open") -> {
                            val n = callCount.incrementAndGet()
                            if (n % 2 == 1) 401 to """{"code":"invalidClientIdOrSecret","message":"bad"}"""
                            else 500 to """{"code":"ServiceUnavailable","message":"busy"}"""
                        }

                        url.endsWith("/v1.0/oauth2/accessToken") -> tokenAnswer
                        else -> 404 to "{}"
                    }
                }
            val conn =
                DingtalkConnection(
                    robotId = RobotId("r"),
                    api = DingtalkApi("cid", "sec", testHttp),
                    scope = scope,
                    transportFactory = { FakeTransport(opens, true).also { transports += it } },
                    download = HttpFileDownload { _, _, _ -> DownloadResult.Failed("unused") },
                    backoffBaseMillis = 1,
                    backoffMaxMillis = 5,
                )
            start(conn)
            withTimeout(5_000) { while (statuses.none { it is ConnectionStatus.Failed }) delay(5) }
            // 9 次调用：5 次凭据错误 + 4 次网络错误
            assertEquals(9, callCount.get())
            assertEquals(ConnectionStatus.Failed("invalidClientIdOrSecret bad"), statuses.last())
        }

    @Test
    fun `连续失败五次后报正在重试`() =
        runBlocking {
            openAnswer = 500 to """{"code":"ServiceUnavailable","message":"busy"}"""
            val conn = connection()
            start(conn)
            withTimeout(2_000) { while (statuses.none { it is ConnectionStatus.Retrying }) delay(5) }
            stopAndAwait(conn)
            val beforeRetrying = statuses.takeWhile { it !is ConnectionStatus.Retrying }
            assertEquals(List(5) { ConnectionStatus.Connecting }, beforeRetrying)
            assertEquals(ConnectionStatus.Retrying("ServiceUnavailable busy"), statuses[5])
            assertEquals(0, opens.get())
            // Retrying 之后不再闪回 Connecting
            val afterRetrying = statuses.drop(5).filter { it != ConnectionStatus.Stopped }
            assertTrue("Retrying 之后不应出现 Connecting", afterRetrying.none { it == ConnectionStatus.Connecting })
        }

    @Test
    fun `没有 staffId 时显示提示，下一条正常消息恢复在线`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            val transport = transports.first()
            transport.emit(callback("h1", textData("m1", "你好", staffId = null)))
            awaitLast(ConnectionStatus.SendFailing("bridge.dingtalk.noStaffId"))
            transport.emit(callback("h2", textData("m2", "再试")))
            awaitLast(ConnectionStatus.Online)
            stopAndAwait(conn)
            assertEquals(listOf("m2"), received.map { it.externalMessageId })
        }

    @Test
    fun `收消息额度用完时显示，恢复后回到在线`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            val transport = transports.first()
            transport.emit(callback("h1", """{"conversationType":"1","errorCode":20001,"errorMessage":"超量"}"""))
            awaitLast(ConnectionStatus.SendFailing("bridge.dingtalk.receiveQuota"))
            transport.emit(callback("h2", textData("m2", "又能收了")))
            awaitLast(ConnectionStatus.Online)
            assertTrue(received.single().text == "又能收了")
        }

    @Test
    fun `额度用完报受阻，之后发送成功恢复在线`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            sendAnswer = 403 to """{"code":"Forbidden.AccessDenied.ApiCountLimitForOrg","message":"quota"}"""
            assertTrue(send(conn) is DeliveryResult.Rejected)
            awaitLast(ConnectionStatus.SendFailing("bridge.dingtalk.quota"))
            sendAnswer = 200 to """{"invalidStaffIdList":[],"flowControlledStaffIdList":[]}"""
            assertEquals(DeliveryResult.Accepted, send(conn))
            stopAndAwait(conn)
            assertEquals(
                listOf(
                    ConnectionStatus.Connecting,
                    ConnectionStatus.Online,
                    ConnectionStatus.SendFailing("bridge.dingtalk.quota"),
                    ConnectionStatus.Online,
                    ConnectionStatus.Stopped,
                ),
                statuses.toList(),
            )
        }

    @Test
    fun `限流名单与限流错误码按限频处理，不改状态`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            sendAnswer = 200 to """{"invalidStaffIdList":[],"flowControlledStaffIdList":["u1"]}"""
            assertEquals(DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED), send(conn))
            sendAnswer = 400 to """{"code":"send.too.fast","message":"slow down"}"""
            assertEquals(DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED), send(conn))
            stopAndAwait(conn)
            assertEquals(listOf(ConnectionStatus.Connecting, ConnectionStatus.Online, ConnectionStatus.Stopped), statuses.toList())
        }

    @Test
    fun `缺权限与无效用户带上原因`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            sendAnswer = 400 to """{"code":"chatbotId.notAllow.sendOTO","message":"no"}"""
            assertEquals(DeliveryResult.Rejected("chatbotId.notAllow.sendOTO no"), send(conn))
            awaitLast(ConnectionStatus.SendFailing("bridge.dingtalk.permission", "chatbotId.notAllow.sendOTO"))
            sendAnswer = 200 to """{"invalidStaffIdList":["u1"],"flowControlledStaffIdList":[]}"""
            assertEquals(DeliveryResult.Rejected("invalidStaffIdList"), send(conn))
            awaitLast(ConnectionStatus.SendFailing("bridge.dingtalk.permission", "invalidStaffIdList"))
        }

    @Test
    fun `发送受阻优先于接收受阻显示`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            transports.first().emit(callback("h1", """{"conversationType":"1","errorCode":20001}"""))
            awaitLast(ConnectionStatus.SendFailing("bridge.dingtalk.receiveQuota"))
            sendAnswer = 403 to """{"code":"Forbidden.AccessDenied.ApiCountLimitForOrg","message":"quota"}"""
            send(conn)
            awaitLast(ConnectionStatus.SendFailing("bridge.dingtalk.quota"))
            sendAnswer = 200 to """{"invalidStaffIdList":[],"flowControlledStaffIdList":[]}"""
            send(conn)
            awaitLast(ConnectionStatus.SendFailing("bridge.dingtalk.receiveQuota"))
        }

    @Test
    fun `发送受阻跨重连保留，发送成功才恢复`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            sendAnswer = 403 to """{"code":"Forbidden.AccessDenied.ApiCountLimitForOrg","message":"quota"}"""
            send(conn)
            awaitLast(ConnectionStatus.SendFailing("bridge.dingtalk.quota"))
            // disconnect 触发重连；重连上线后仍显示发送受阻
            val before = statuses.size
            transports.first().emit(system("disconnect", "s1", "{}"))
            awaitAfter(before, ConnectionStatus.SendFailing("bridge.dingtalk.quota"))
            // 发送成功后才回到 Online
            sendAnswer = 200 to """{"invalidStaffIdList":[],"flowControlledStaffIdList":[]}"""
            assertEquals(DeliveryResult.Accepted, send(conn))
            awaitLast(ConnectionStatus.Online)
            stopAndAwait(conn)
        }

    @Test
    fun `接收受阻跨重连保留，正常消息才恢复`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            val transport = transports.first()
            transport.emit(callback("h1", """{"conversationType":"1","errorCode":20001}"""))
            awaitLast(ConnectionStatus.SendFailing("bridge.dingtalk.receiveQuota"))
            // disconnect 触发重连；重连上线后仍显示接收受阻
            val before = statuses.size
            transport.emit(system("disconnect", "s1", "{}"))
            awaitAfter(before, ConnectionStatus.SendFailing("bridge.dingtalk.receiveQuota"))
            // 收到正常消息后回到 Online
            transports.last().emit(callback("h2", textData("m2", "恢复了")))
            awaitLast(ConnectionStatus.Online)
            stopAndAwait(conn)
        }

    @Test
    fun `握手后立刻被关、从未收到帧，不清零失败计数，按退避重试并报正在重试`() =
        runBlocking {
            val conn = connection(closeOnOpen = true, backoffBaseMillis = 20, backoffMaxMillis = 80, emitOnOpen = null)
            start(conn)
            withTimeout(5_000) { while (statuses.none { it is ConnectionStatus.Retrying }) delay(5) }
            stopAndAwait(conn)
            val beforeRetrying = statuses.takeWhile { it !is ConnectionStatus.Retrying }
            // 握手成功但一帧都没收到，不报 Online；只有 Connecting
            assertEquals(List(5) { ConnectionStatus.Connecting }, beforeRetrying)
            assertEquals(ConnectionStatus.Retrying("closed by server"), statuses[beforeRetrying.size])
            // Retrying 期间也不出现 Online
            assertTrue("Retrying 之后不应出现 Online", statuses.drop(beforeRetrying.size).none { it == ConnectionStatus.Online })
            // 每两次 connections/open 之间至少隔退避时长 20、40、80、80…毫秒，而不是一直 20 毫秒
            val gaps = openCallTimes.zipWithNext { a, b -> (b - a) / 1_000_000 }
            assertTrue("至少重连 4 次：$gaps", gaps.size >= 4)
            listOf(20L, 40L, 80L, 80L).forEachIndexed { i, min -> assertTrue("第 ${i + 1} 次间隔 ${gaps[i]}ms 小于退避 ${min}ms", gaps[i] >= min) }
        }

    @Test
    fun `握手异常带完整地址时，原因里不带 ticket`() =
        runBlocking {
            openAnswer = 200 to """{"endpoint":"wss://x/connect","ticket":"secret"}"""
            val error = URISyntaxException("wss://x/connect?ticket=secret", "Illegal character in query")
            val conn = connection(openError = error)
            start(conn)
            withTimeout(2_000) { while (statuses.none { it is ConnectionStatus.Retrying }) delay(5) }
            stopAndAwait(conn)
            val retrying = statuses.first { it is ConnectionStatus.Retrying } as ConnectionStatus.Retrying
            assertEquals("URISyntaxException", retrying.reason)
            assertTrue(statuses.none { it.toString().contains("secret") })
        }

    @Test
    fun `一直发不出 ping 也照样判定断线`() =
        runBlocking {
            val conn = connection(autoPong = false, pingIntervalMillis = 20, deadAfterMillis = 60, hangPing = true, emitOnOpen = null)
            start(conn)
            withTimeout(2_000) { while (opens.get() < 2) delay(5) }
        }

    @Test
    fun `发送遇到网络异常返回结果不明，状态不变`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            sendThrows = true
            assertEquals(DeliveryResult.Unknown, send(conn))
            stopAndAwait(conn)
            assertEquals(listOf(ConnectionStatus.Connecting, ConnectionStatus.Online, ConnectionStatus.Stopped), statuses.toList())
        }

    @Test
    fun `取令牌时凭据被拒，发送拒绝并显示应用不可用`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            tokenAnswer = 401 to """{"code":"invalidClientIdOrSecret","message":"bad"}"""
            assertEquals(DeliveryResult.Rejected("invalidClientIdOrSecret bad"), send(conn))
            awaitLast(ConnectionStatus.SendFailing("bridge.dingtalk.app", "invalidClientIdOrSecret"))
            assertTrue(http.calls("/v1.0/robot/oToMessages/batchSend").isEmpty())
        }

    @Test
    fun `正文过大时不发，也不占限频名额`() =
        runBlocking {
            val limiter = SlidingWindowLimiter(1, 60_000)
            val conn = connection(limiter = limiter)
            startAndAwaitOnline(conn)
            val huge = "\"".repeat(8_000)
            assertEquals(DeliveryResult.Rejected("too large"), conn.send(OutboundMessage(RobotId("r"), "u1", huge)))
            assertTrue(http.calls("/v1.0/robot/oToMessages/batchSend").isEmpty())
            assertEquals(0, limiter.tryAcquire("u1"))
        }

    @Test
    fun `停止后上报已停止，不再发送`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            stopAndAwait(conn)
            assertEquals(DeliveryResult.Rejected("offline"), send(conn))
        }

    @Test
    fun `握手后不主动 ping，收到第一帧才上线`() =
        runBlocking {
            // 与官方 SDK 一致：只在空闲周期 ping；周期 60 秒内不应有 ping
            val conn = connection(pingIntervalMillis = 60_000, deadAfterMillis = 120_000, emitOnOpen = null)
            start(conn)
            withTimeout(2_000) { while (transports.isEmpty() || transports.first().listener == null) delay(5) }
            val transport = transports.first()
            transport.emit(callback("h1", textData("m1", "你好")))
            // 屏障：ACK 已发出、消息已交出，握手后若有立即 ping 此时早已发出
            withTimeout(2_000) { while (received.isEmpty() || transport.sent.isEmpty() || ConnectionStatus.Online !in statuses) delay(5) }
            assertEquals(0, transport.pings.get())
            assertEquals(listOf(ConnectionStatus.Connecting, ConnectionStatus.Online), statuses.toList())
        }

    @Test
    fun `空闲 ping 没有 pong 时不上线，收到文本帧才上线`() =
        runBlocking {
            val conn = connection(autoPong = false, pingIntervalMillis = 20, deadAfterMillis = 120_000, emitOnOpen = null)
            start(conn)
            // 屏障：已过两个空闲周期、发过两次 ping，都没有 pong
            withTimeout(2_000) { while (transports.isEmpty() || transports.first().pings.get() < 2) delay(5) }
            assertTrue("ping 没有 pong 时不应上线", statuses.none { it == ConnectionStatus.Online })
            transports.first().emit(callback("h1", textData("m1", "你好")))
            withTimeout(2_000) { while (ConnectionStatus.Online !in statuses) delay(5) }
            assertEquals(ConnectionStatus.Online, statuses.last())
        }

    @Test
    fun `首帧在 session 赋值前到达仍能上线`() =
        runBlocking {
            // emitOnOpen 在 open() 返回前同步调用 listener.onText：此时 session 还没赋值，
            // tryReportOnline 的 session === state 为 false 不上报；session 赋值后补调一次才上报
            val frame = system("ping", "p1", """{"opaque":"o"}""")
            val conn = connection(autoPong = false, emitOnOpen = frame)
            startAndAwaitOnline(conn)
            // 确认 Online 最终上报成功
            assertTrue(statuses.contains(ConnectionStatus.Online))
        }

    @Test
    fun `凭据失败四次后一次上线再失败四次仍不报 Failed`() =
        runBlocking {
            // 前 4 次凭据错误 → 第 5 次成功上线（清零） → 断开 → 再 4 次凭据错误 → 不到 5 次，不报 Failed
            // 第 10 次调用挡住，确保测试检查完才继续
            val callCount = AtomicInteger()
            val gate = CountDownLatch(1)
            val testHttp =
                DingtalkFakeHttp { url, _ ->
                    when {
                        url.endsWith("/v1.0/gateway/connections/open") -> {
                            val n = callCount.incrementAndGet()
                            when {
                                n <= 4 -> 401 to """{"code":"invalidClientIdOrSecret","message":"bad"}"""
                                n == 5 -> 200 to """{"endpoint":"wss://x/connect","ticket":"t1"}"""
                                n <= 9 -> 401 to """{"code":"invalidClientIdOrSecret","message":"bad"}"""
                                else -> {
                                    gate.await(5, TimeUnit.SECONDS)
                                    401 to """{"code":"invalidClientIdOrSecret","message":"bad"}"""
                                }
                            }
                        }

                        url.endsWith("/v1.0/oauth2/accessToken") -> tokenAnswer
                        else -> 404 to "{}"
                    }
                }
            val conn =
                DingtalkConnection(
                    robotId = RobotId("r"),
                    api = DingtalkApi("cid", "sec", testHttp),
                    scope = scope,
                    transportFactory = { FakeTransport(opens, true).also { transports += it } },
                    download = HttpFileDownload { _, _, _ -> DownloadResult.Failed("unused") },
                    backoffBaseMillis = 1,
                    backoffMaxMillis = 5,
                )
            start(conn)
            // 等上线（第 5 次调用成功，开场系统 ping 帧 → Online）
            withTimeout(5_000) { while (ConnectionStatus.Online !in statuses) delay(5) }
            // 断开触发重连
            transports.last().listener!!.onClosed("bye")
            // 等第 10 次调用：只有第 9 次凭据失败处理完且未因 Failed 退出时，runLoop 才会发起第 10 次调用
            withTimeout(5_000) { while (callCount.get() < 10) delay(5) }
            // 不应出现 Failed（上线清零后只有 4 次凭据错误，未达 5）
            assertTrue("不应报 Failed", statuses.none { it is ConnectionStatus.Failed })
            gate.countDown()
            stopAndAwait(conn)
        }

    @Test
    fun `监听器阻塞时 stop 不被卡住`() {
        val gate = CountDownLatch(1)
        val conn = connection()
        conn.start(InboundSink { }) { status ->
            statuses += status
            gate.await(5, TimeUnit.SECONDS)
        }
        runBlocking { withTimeout(2_000) { while (statuses.isEmpty()) delay(5) } }
        val stopped = CountDownLatch(1)
        Thread {
            conn.stop()
            stopped.countDown()
        }.start()
        assertTrue("stop 被监听器卡住", stopped.await(1, TimeUnit.SECONDS))
        gate.countDown()
    }

    private companion object {
        const val GREETING_ID = "greeting"
        val GREETING = JsonObject().apply {
            addProperty("specVersion", "1.0")
            addProperty("type", "SYSTEM")
            add(
                "headers",
                JsonObject().apply {
                    addProperty("topic", "ping")
                    addProperty("messageId", GREETING_ID)
                },
            )
            addProperty("data", "{}")
        }.toString()
    }
}
