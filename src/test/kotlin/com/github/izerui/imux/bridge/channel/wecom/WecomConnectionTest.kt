package com.github.izerui.imux.bridge.channel.wecom

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
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class WecomConnectionTest {
    /** 假传输：记录发出的帧；按 [acker] 决定是否确认、errcode 多少。 */
    private class FakeTransport(
        private val opens: AtomicInteger,
        private val acker: (cmd: String) -> Int?,
    ) : FrameTransport {
        val sent = CopyOnWriteArrayList<String>()
        var listener: FrameListener? = null

        override suspend fun open(
            url: String,
            listener: FrameListener,
        ) {
            opens.incrementAndGet()
            this.listener = listener
        }

        override suspend fun send(text: String) {
            sent += text
            val frame = JsonParser.parseString(text).asJsonObject
            val cmd = frame["cmd"].asString
            val reqId = frame["headers"].asJsonObject["req_id"].asString
            val errcode = acker(cmd) ?: return
            listener?.onText("""{"headers":{"req_id":"$reqId"},"errcode":$errcode,"errmsg":"e"}""")
        }

        override fun close() {
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

    @After
    fun tearDown() = scope.cancel()

    private fun connection(
        heartbeatMillis: Long = 60_000,
        limiter: SlidingWindowLimiter = SlidingWindowLimiter(30, 60_000),
        acker: (String) -> Int? = { 0 },
    ) = WecomConnection(
        robotId = RobotId("r"),
        botId = "B",
        secret = "S",
        scope = scope,
        transportFactory = { FakeTransport(opens, acker).also { transports += it } },
        random = Random(7),
        heartbeatMillis = heartbeatMillis,
        ackTimeoutMillis = 200,
        backoff = { 0L },
        limiter = limiter,
        maxRateWaitMillis = 50,
        download = HttpFileDownload { _, _, _ -> DownloadResult.Failed("test") },
    )

    private fun startAndAwaitOnline(conn: WecomConnection) =
        runBlocking {
            conn.start(InboundSink { received += it }) { statuses += it }
            withTimeout(2_000) { while (ConnectionStatus.Online !in statuses) delay(5) }
        }

    private fun singleText(
        msgId: String,
        user: String,
        text: String,
        chat: String = "single",
    ) = """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"$msgId","chattype":"$chat","from":{"userid":"$user"},"msgtype":"text","text":{"content":"$text"}}}"""

    @Test
    fun `连接后订阅并上线`() {
        startAndAwaitOnline(connection())
        val subscribe = JsonParser.parseString(transports.first().sent.first()).asJsonObject
        assertEquals("aibot_subscribe", subscribe["cmd"].asString)
        assertEquals("B", subscribe["body"].asJsonObject["bot_id"].asString)
    }

    @Test
    fun `只转交单聊文本并按 msgid 去重`() {
        startAndAwaitOnline(connection())
        val t = transports.first()
        t.emit(singleText("m1", "u1", "跑一下单测"))
        t.emit(singleText("m1", "u1", "跑一下单测"))
        t.emit(singleText("m2", "u2", "群里的话", chat = "group"))
        assertEquals(listOf(InboundMessage(RobotId("r"), "m1", "u1", "u1", "跑一下单测")), received.toList())
    }

    @Test
    fun `被踢后不再重连`() =
        runBlocking {
            startAndAwaitOnline(connection())
            transports.first().emit("""{"cmd":"aibot_event_callback","headers":{"req_id":"R"},"body":{"msgtype":"event","event":{"eventtype":"disconnected_event"}}}""")
            withTimeout(2_000) { while (ConnectionStatus.Kicked !in statuses) delay(5) }
            delay(100)
            assertEquals(1, opens.get())
        }

    @Test
    fun `认证连续失败 5 次后停止`() =
        runBlocking {
            val conn = connection(acker = { cmd -> if (cmd == "aibot_subscribe") 40001 else 0 })
            conn.start(InboundSink { }) { statuses += it }
            withTimeout(3_000) { while (statuses.none { it is ConnectionStatus.Failed }) delay(5) }
            assertEquals(5, opens.get())
        }

    @Test
    fun `认证失败与认证前断开交替出现时，仍在第 5 次认证失败后停止`() =
        runBlocking {
            val subscribes = AtomicInteger()
            // 奇数次订阅认证失败，偶数次不确认（订阅超时，按断开处理）
            val conn =
                connection(acker = { cmd ->
                    if (cmd != "aibot_subscribe") 0 else if (subscribes.incrementAndGet() % 2 == 1) 40001 else null
                })
            conn.start(InboundSink { }) { statuses += it }
            withTimeout(5_000) { while (statuses.none { it is ConnectionStatus.Failed }) delay(5) }
            assertEquals(9, opens.get())
        }

    @Test
    fun `连续 5 次连不上改报正在重试并带原因，仍继续重连`() =
        runBlocking {
            val conn = connection(acker = { cmd -> if (cmd == "aibot_subscribe") null else 0 })
            conn.start(InboundSink { }) { statuses += it }
            withTimeout(5_000) { while (statuses.none { it is ConnectionStatus.Retrying }) delay(5) }
            assertEquals(ConnectionStatus.Retrying("subscribe timeout"), statuses.first { it is ConnectionStatus.Retrying })
            assertEquals(5, statuses.takeWhile { it !is ConnectionStatus.Retrying }.count { it == ConnectionStatus.Connecting })
            withTimeout(2_000) { while (opens.get() < 7) delay(5) }
        }

    @Test
    fun `心跳连续两次未确认则重连`() =
        runBlocking {
            val conn = connection(heartbeatMillis = 20, acker = { cmd -> if (cmd == "ping") null else 0 })
            startAndAwaitOnline(conn)
            withTimeout(3_000) { while (opens.get() < 2) delay(5) }
        }

    @Test
    fun `服务端会话限频按限频返回，交给路由器稍后重发`() =
        runBlocking {
            val conn = connection(acker = { cmd -> if (cmd == "aibot_send_msg") 846607 else 0 })
            startAndAwaitOnline(conn)
            assertEquals(DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED), conn.send(OutboundMessage(RobotId("r"), "u1", "hi")))
        }

    @Test
    fun `主动推送单聊 markdown 并按确认结果返回`() =
        runBlocking {
            val conn = connection(acker = { cmd -> if (cmd == "aibot_send_msg") 93000 else 0 })
            startAndAwaitOnline(conn)
            val result = conn.send(OutboundMessage(RobotId("r"), "u1", "hi"))
            assertEquals(DeliveryResult.Rejected("93000 e"), result)
            val frame = JsonParser.parseString(transports.first().sent.last()).asJsonObject
            assertEquals(1, frame["body"].asJsonObject["chat_type"].asInt)
            assertEquals("u1", frame["body"].asJsonObject["chatid"].asString)
        }

    @Test
    fun `确认成功返回 Accepted，离线返回 Rejected`() =
        runBlocking {
            val conn = connection()
            assertEquals(DeliveryResult.Rejected("offline"), conn.send(OutboundMessage(RobotId("r"), "u1", "hi")))
            startAndAwaitOnline(conn)
            assertEquals(DeliveryResult.Accepted, conn.send(OutboundMessage(RobotId("r"), "u1", "hi")))
        }

    @Test
    fun `超出限流等待上限时拒绝`() =
        runBlocking {
            val conn = connection(limiter = SlidingWindowLimiter(1, 60_000))
            startAndAwaitOnline(conn)
            assertEquals(DeliveryResult.Accepted, conn.send(OutboundMessage(RobotId("r"), "u1", "a")))
            assertEquals(DeliveryResult.Rejected("rate limited"), conn.send(OutboundMessage(RobotId("r"), "u1", "b")))
        }

    @Test
    fun `停止后状态为 Stopped`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            conn.stop()
            withTimeout(2_000) { while (ConnectionStatus.Stopped !in statuses) delay(5) }
            assertTrue(opens.get() >= 1)
        }

    @Test
    fun `会话断开时进行中的发送返回 Unknown`() =
        runBlocking {
            val conn = connection(acker = { cmd -> if (cmd == "aibot_send_msg") null else 0 })
            startAndAwaitOnline(conn)
            val result = async(Dispatchers.Default) {
                conn.send(OutboundMessage(RobotId("r"), "u1", "hi"))
            }
            delay(30)
            transports.first().close()
            assertEquals(DeliveryResult.Unknown, result.await())
        }

    @Test
    fun `停止后重新启动产生独立的新运行`() =
        runBlocking {
            val statuses1 = CopyOnWriteArrayList<ConnectionStatus>()
            val statuses2 = CopyOnWriteArrayList<ConnectionStatus>()
            val conn = connection()
            conn.start(InboundSink { }) { statuses1 += it }
            withTimeout(2_000) { while (ConnectionStatus.Online !in statuses1) delay(5) }
            conn.stop()
            withTimeout(2_000) { while (ConnectionStatus.Stopped !in statuses1) delay(5) }
            val stoppedIdx = statuses1.indexOfLast { it == ConnectionStatus.Stopped }
            delay(50)
            assertEquals(stoppedIdx, statuses1.lastIndex)
            conn.start(InboundSink { }) { statuses2 += it }
            withTimeout(2_000) { while (ConnectionStatus.Online !in statuses2) delay(5) }
            delay(50)
            assertEquals(stoppedIdx, statuses1.lastIndex)
            assertTrue(ConnectionStatus.Online in statuses2)
        }

    @Test
    fun `引用文字随消息转交`() {
        startAndAwaitOnline(connection())
        transports.first().emit(
            """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m1","chattype":"single","from":{"userid":"u1"},"msgtype":"text","text":{"content":"这里"},"quote":{"msgtype":"text","text":{"content":"原文"}}}}""",
        )
        assertEquals("原文", received.single().quote)
    }

    @Test
    fun `进入会话事件不回欢迎语也不转交，之后的文字照常转交`() {
        startAndAwaitOnline(connection())
        val t = transports.first()
        t.emit(
            """{"cmd":"aibot_event_callback","headers":{"req_id":"EV1"},"body":{"msgid":"e1","msgtype":"event","from":{"userid":"u1"},"event":{"eventtype":"enter_chat"}}}""",
        )
        t.emit(singleText("m1", "u1", "继续"))
        // 回复帧可能是异步发出的：等满一个应答超时再断言，免得以后接回欢迎语时漏检
        runBlocking { delay(200) }
        // 只剩订阅帧：欢迎语暂不使用，事件既不回复也不进入终端
        assertEquals(listOf("aibot_subscribe"), t.sent.map { JsonParser.parseString(it).asJsonObject["cmd"].asString })
        assertEquals(listOf(InboundMessage(RobotId("r"), "m1", "u1", "u1", "继续")), received.toList())
    }

    @Test
    fun `退避从 1 秒翻倍到 30 秒封顶`() {
        assertEquals(1_000L, wecomBackoffMillis(1))
        assertEquals(2_000L, wecomBackoffMillis(2))
        assertEquals(30_000L, wecomBackoffMillis(10))
    }

    @Test
    fun `只有附件的单聊消息也转交，附件按需取回`() {
        startAndAwaitOnline(connection())
        transports.first().emit(
            """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m9","chattype":"single","from":{"userid":"u1"},"msgtype":"image","image":{"url":"https://i","aeskey":"k"}}}""",
        )
        val message = received.single()
        assertEquals("", message.text)
        assertEquals(listOf(AttachmentKind.IMAGE), message.attachments.map { it.kind })
    }
}
