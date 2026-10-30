package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.channel.DownloadResult
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.http.HttpTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WeixinConnectionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val statuses = CopyOnWriteArrayList<ConnectionStatus>()
    private val received = CopyOnWriteArrayList<InboundMessage>()
    private val clock = 1_700_000_000_000L

    /** getupdates 的应答按入队顺序给出；队列空时挂起（模拟长轮询）。元素为 null 表示抛超时，"!" 开头表示抛 IOException。 */
    private val polls = Channel<String?>(Channel.UNLIMITED)

    @Volatile
    private var sendAnswer: Pair<Int, String> = 200 to """{"ret":0}"""

    /** 先按序给出这里的 sendmessage 应答，用完后给 [sendAnswer]。 */
    private val sendAnswers = ConcurrentLinkedQueue<Pair<Int, String>>()

    private val http =
        WeixinFakeHttp { _, url, _ ->
            when {
                "/getupdates" in url -> {
                    val next = polls.receive()
                    when {
                        next == null -> throw HttpTimeoutException("slow")
                        next.startsWith("!") -> throw IOException(next)
                        else -> 200 to next
                    }
                }

                "/sendmessage" in url -> sendAnswers.poll() ?: sendAnswer
                else -> 404 to "{}"
            }
        }

    @After
    fun tearDown() = scope.cancel()

    private fun connection(
        limiter: SlidingWindowLimiter = SlidingWindowLimiter(3, 1_000),
        expiredPauseMillis: Long = 3_600_000,
    ) =
        WeixinConnection(
            robotId = RobotId("r"),
            api = WeixinApi(http, http),
            baseUrl = "https://b",
            token = "T",
            scope = scope,
            download = HttpFileDownload { _, _, _ -> DownloadResult.Failed("unused") },
            now = { clock },
            retryDelayMillis = 1,
            backoffMillis = 5,
            limiter = limiter,
            expiredPauseMillis = expiredPauseMillis,
        )

    private fun start(conn: WeixinConnection) = conn.start(InboundSink { received += it }) { statuses += it }

    private suspend fun awaitLast(status: ConnectionStatus) = withTimeout(2_000) { while (statuses.lastOrNull() != status) delay(5) }

    private suspend fun stopAndAwait(conn: WeixinConnection) {
        conn.stop()
        awaitLast(ConnectionStatus.Stopped)
    }

    private fun text(
        id: String,
        body: String,
        time: Long? = clock,
        token: String? = "CT-$id",
    ) = """{"message_id":$id,"from_user_id":"u@im.wechat","message_type":1${time?.let { ",\"create_time_ms\":$it" }.orEmpty()}${
        token?.let { ",\"context_token\":\"$it\"" }.orEmpty()
    },"item_list":[{"type":1,"text_item":{"text":"$body"}}]}"""

    private fun updates(
        vararg msgs: String,
        cursor: String = "",
    ) = """{"ret":0,"msgs":[${msgs.joinToString(",")}],"get_updates_buf":"$cursor"}"""

    private fun pollCount() = http.calls("/getupdates").size

    private suspend fun awaitPolls(n: Int) = withTimeout(2_000) { while (pollCount() < n) delay(5) }

    private fun send(conn: WeixinConnection) = runBlocking { conn.send(OutboundMessage(RobotId("r"), "u@im.wechat", "结论")) }

    @Test
    fun `首次成功报在线，消息按序交出`() =
        runBlocking {
            start(connection())
            polls.send(updates(text("1", "一"), text("2", "二")))
            withTimeout(2_000) { while (received.size < 2) delay(5) }
            // 消息与状态由两个协程分别交出，先等状态到齐
            awaitLast(ConnectionStatus.Online)
            assertEquals(listOf(ConnectionStatus.Connecting, ConnectionStatus.Online), statuses.toList())
            assertEquals(InboundMessage(RobotId("r"), "1", "u@im.wechat", "u@im.wechat", "一"), received[0])
            assertEquals("2", received[1].externalMessageId)
        }

    @Test
    fun `游标只在非空时替换`() =
        runBlocking {
            start(connection())
            polls.send(updates(cursor = "c1"))
            polls.send(updates(cursor = ""))
            awaitPolls(3)
            val cursors = http.calls("/getupdates").take(3).map { JsonParser.parseString(it.body).asJsonObject["get_updates_buf"].asString }
            assertEquals(listOf("", "c1", "c1"), cursors)
        }

    @Test
    fun `丢弃早于连接时刻一分钟的消息，没有时间的照常处理`() =
        runBlocking {
            start(connection())
            polls.send(updates(text("1", "旧", time = clock - 61_000), text("2", "近", time = clock - 59_000), text("3", "无时间", time = null)))
            polls.send(updates(text("4", "屏障")))
            withTimeout(2_000) { while (received.none { it.externalMessageId == "4" }) delay(5) }
            assertEquals(listOf("2", "3", "4"), received.map { it.externalMessageId })
        }

    @Test
    fun `按 message_id 去重`() =
        runBlocking {
            start(connection())
            polls.send(updates(text("1", "一")))
            polls.send(updates(text("1", "一"), text("2", "二")))
            withTimeout(2_000) { while (received.none { it.externalMessageId == "2" }) delay(5) }
            assertEquals(listOf("1", "2"), received.map { it.externalMessageId })
        }

    @Test
    fun `发送带上该用户最新的 context_token`() =
        runBlocking {
            val conn = connection()
            start(conn)
            assertEquals(DeliveryResult.Accepted, send(conn))
            val first = JsonParser.parseString(http.calls("/sendmessage").single().body).asJsonObject["msg"].asJsonObject
            assertTrue("还没收到消息时不带", !first.has("context_token"))
            polls.send(updates(text("1", "一", token = "CT-A"), text("2", "二", token = "CT-B")))
            withTimeout(2_000) { while (received.size < 2) delay(5) }
            assertEquals(DeliveryResult.Accepted, send(conn))
            val second = JsonParser.parseString(http.calls("/sendmessage").last().body).asJsonObject["msg"].asJsonObject
            assertEquals("CT-B", second["context_token"].asString)
        }

    @Test
    fun `图片消息带附件`() =
        runBlocking {
            start(connection())
            polls.send(
                updates("""{"message_id":9,"from_user_id":"u@im.wechat","message_type":1,"create_time_ms":$clock,"item_list":[{"type":2,"image_item":{"media":{"full_url":"https://cdn/i"}}}]}"""),
            )
            withTimeout(2_000) { while (received.isEmpty()) delay(5) }
            assertEquals(AttachmentKind.IMAGE, received.single().attachments.single().kind)
        }

    @Test
    fun `长轮询超时不算失败，立即下一轮`() =
        runBlocking {
            val conn = connection()
            start(conn)
            polls.send(updates())
            polls.send(null)
            polls.send(null)
            awaitPolls(4)
            stopAndAwait(conn)
            assertEquals(listOf(ConnectionStatus.Connecting, ConnectionStatus.Online, ConnectionStatus.Stopped), statuses.toList())
        }

    @Test
    fun `连续失败退避，五次起报正在重试，恢复后在线`() =
        runBlocking {
            start(connection())
            polls.send(updates())
            repeat(5) { polls.send("!reset") }
            withTimeout(2_000) { while (statuses.none { it is ConnectionStatus.Retrying }) delay(5) }
            polls.send(updates())
            awaitLast(ConnectionStatus.Online)
            val retrying = statuses.filterIsInstance<ConnectionStatus.Retrying>()
            assertEquals(listOf(ConnectionStatus.Retrying("IOException")), retrying)
            assertEquals(ConnectionStatus.Connecting, statuses[2])
        }

    @Test
    fun `收消息返回 -14 报正在重试，暂停后从当前游标继续，成功后在线`() =
        runBlocking {
            start(connection(expiredPauseMillis = 1))
            polls.send(updates(cursor = "c1"))
            awaitLast(ConnectionStatus.Online)
            polls.send("""{"ret":-14,"errmsg":"session timeout"}""")
            awaitLast(ConnectionStatus.Retrying("-14/0 session timeout"))
            polls.send(updates())
            awaitPolls(3)
            awaitLast(ConnectionStatus.Online)
            assertEquals(
                listOf(ConnectionStatus.Connecting, ConnectionStatus.Online, ConnectionStatus.Retrying("-14/0 session timeout"), ConnectionStatus.Online),
                statuses.toList(),
            )
            val cursors = http.calls("/getupdates").take(3).map { JsonParser.parseString(it.body).asJsonObject["get_updates_buf"].asString }
            assertEquals("暂停后从当前游标继续", listOf("", "c1", "c1"), cursors)
        }

    private fun sentToken(index: Int) =
        JsonParser.parseString(http.calls("/sendmessage")[index].body).asJsonObject["msg"].asJsonObject["context_token"]?.asString

    /** 收一条带 context_token 的消息，链路在线。 */
    private suspend fun onlineWithToken(conn: WeixinConnection) {
        start(conn)
        polls.send(updates(text("1", "一", token = "CT-A")))
        withTimeout(2_000) { while (received.isEmpty()) delay(5) }
        awaitLast(ConnectionStatus.Online)
    }

    /** 再收一条消息作屏障：确认轮询仍在继续。 */
    private suspend fun awaitStillPolling() {
        polls.send(updates(text("2", "二", token = null)))
        withTimeout(2_000) { while (received.none { it.externalMessageId == "2" }) delay(5) }
    }

    @Test
    fun `发送返回 -14 时去掉 context_token 重发一次，成功则送达，链路不受影响`() =
        runBlocking {
            val conn = connection()
            onlineWithToken(conn)
            sendAnswers += 200 to """{"ret":-14,"errmsg":"session timeout"}"""
            assertEquals(DeliveryResult.Accepted, send(conn))
            assertEquals(2, http.calls("/sendmessage").size)
            assertEquals("CT-A", sentToken(0))
            assertEquals("重发不带 context_token", null, sentToken(1))
            assertEquals(DeliveryResult.Accepted, send(conn))
            assertEquals("失效的 context_token 已丢弃", null, sentToken(2))
            awaitStillPolling()
            assertEquals(listOf(ConnectionStatus.Connecting, ConnectionStatus.Online), statuses.toList())
        }

    @Test
    fun `发送重发仍返回 -14 按拒收显示受阻，链路不失败`() =
        runBlocking {
            val conn = connection()
            onlineWithToken(conn)
            sendAnswer = 200 to """{"ret":-14,"errmsg":"x"}"""
            assertEquals(DeliveryResult.Rejected("-14/0 x"), send(conn))
            assertEquals(2, http.calls("/sendmessage").size)
            val blocked = ConnectionStatus.SendFailing("bridge.weixin.sendRejected", "-14/0")
            awaitLast(blocked)
            assertEquals("本来没带 token 就不重发", DeliveryResult.Rejected("-14/0 x"), send(conn))
            assertEquals(3, http.calls("/sendmessage").size)
            awaitStillPolling()
            assertEquals(listOf(ConnectionStatus.Connecting, ConnectionStatus.Online, blocked), statuses.toList())
        }

    @Test
    fun `发送被拒显示受阻，跨重连保留，发送成功恢复`() =
        runBlocking {
            val conn = connection()
            start(conn)
            polls.send(updates())
            awaitLast(ConnectionStatus.Online)
            sendAnswer = 200 to """{"ret":-2,"errmsg":"unknown error"}"""
            assertEquals(DeliveryResult.Rejected("-2/0 unknown error"), send(conn))
            val blocked = ConnectionStatus.SendFailing("bridge.weixin.sendRejected", "-2/0")
            awaitLast(blocked)
            polls.send("!reset")
            polls.send(updates())
            withTimeout(2_000) { while (statuses.count { it == blocked } < 2) delay(5) }
            sendAnswer = 200 to """{"ret":0}"""
            assertEquals(DeliveryResult.Accepted, send(conn))
            awaitLast(ConnectionStatus.Online)
        }

    @Test
    fun `网络异常返回结果不明，不显示受阻`() =
        runBlocking {
            val broken =
                WeixinConnection(
                    RobotId("r"),
                    WeixinApi(WeixinFakeHttp { _, _, _ -> throw IOException("x") }, http),
                    "https://b",
                    "T",
                    scope,
                    now = { clock },
                )
            broken.start(InboundSink { }) { statuses += it }
            assertEquals(DeliveryResult.Unknown, broken.send(OutboundMessage(RobotId("r"), "u", "x")))
            stopAndAwait(broken)
            assertTrue(statuses.toString(), statuses.none { it is ConnectionStatus.SendFailing })
        }

    @Test
    fun `停止后上报已停止，不再发送`() =
        runBlocking {
            val conn = connection()
            start(conn)
            stopAndAwait(conn)
            assertEquals(DeliveryResult.Rejected("offline"), send(conn))
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
}
