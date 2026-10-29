# 钉钉渠道 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 imux 手机桥接中新增钉钉渠道（扫码建机器人 + 配对码认主 + Stream 收单聊 + batchSend 主动推送），与企业微信、飞书并列。

**Architecture:** 新建 `bridge/channel/dingtalk/`，按飞书的分层各自实现一套（扫码、HTTP 接口、帧与消息解析、连接、附件），不引用 `feishu/`、`wecom/` 的任何类；只使用 `channel/` 根包的框架端口与 `core/` 模型。公共层唯一改动是给 `FrameTransport` 加 WebSocket ping/pong。

**Tech Stack:** Kotlin、kotlinx.coroutines、Gson、JDK `java.net.http`（HttpClient、WebSocket）、JUnit4。

**设计依据：** `docs/superpowers/specs/2026-10-29-dingtalk-channel-design.md`（下称“设计”）。协议事实见 `docs/channel-protocols.md` 第 3 节。

## Global Constraints

- 在 main 上工作，**不提交**（用户自己提交）；不建 worktree。
- 不引入任何新依赖；钉钉 SDK 不引入。
- **各渠道各自一套，互不复用**：`channel/dingtalk/` 下的源码不得 import `com.github.izerui.imux.bridge.channel.feishu.` 或 `...channel.wecom.`，即使逻辑相似也自己写；测试同理（钉钉测试用自己的假 HTTP，不用飞书的 `FakeHttpJson`）。
- 渠道代码不得引用 `AgentType`、`bridge.terminal`、`bridge.transcript`、`TerminalHost`、`SessionMonitor`（`ChannelIsolationSourceTest`）。
- 不改核心层（`bridge/core/`）、`robot/`、`ui/`。
- 扫码 `source` 固定为 `DING_DWS_CLAW`。
- 只处理单聊（`conversationType == "1"`）；`senderId = chatId = senderStaffId`。
- 发送：`batchSend` + `msgKey = "sampleMarkdown"`，`maxTextBytes = 6_000`，`msgParam` 超过 15000 字节直接 `Rejected("too large")`；限频 `SlidingWindowLimiter(5, 1000)`，最多等 20 秒。
- 令牌只在内存；剩余不足 10 分钟重取；令牌失效只重试一次。
- 日志不打印 `client_secret`、令牌、`ticket`、消息正文、`downloadCode`（本计划不加日志）。
- 文案补齐 10 种语言（`src/main/resources/messages/ImuxBundle*.properties`，UTF-8；法语用 `’` 不用 `'`）。
- 测试命令：`./gradlew test --tests '<类全名>'`；收尾跑全量 `./gradlew test`、IDE `lint_files`、`./gradlew verifyPlugin`。
- 代码风格与周围代码一致：中文 KDoc/注释，注释只写“为什么”。

## File Structure

| 文件 | 职责 |
|---|---|
| Modify `src/main/kotlin/com/github/izerui/imux/bridge/channel/Transports.kt` | `FrameTransport.sendPing`、`FrameListener.onPong`；`JdkWebSocketTransport` 实现 |
| Modify `src/main/kotlin/com/github/izerui/imux/bridge/channel/ChannelSpi.kt` | `SendFailing` 注释改为“收发受阻” |
| Modify `src/main/kotlin/com/github/izerui/imux/bridge/channel/ChannelFactories.kt` | 登记 `DingtalkChannelFactory` |
| Create `.../bridge/channel/dingtalk/DingtalkEvents.kt` | Stream 帧、ACK/pong、机器人消息解析（纯函数） |
| Create `.../bridge/channel/dingtalk/DingtalkApi.kt` | `DingtalkResult`、`DingtalkCodes`、令牌缓存、建连、batchSend、附件下载地址 |
| Create `.../bridge/channel/dingtalk/DingtalkOnboarding.kt` | `DingtalkCredentialKeys`、扫码 |
| Create `.../bridge/channel/dingtalk/DingtalkAttachment.kt` | 附件按需下载 |
| Create `.../bridge/channel/dingtalk/DingtalkConnection.kt` | 连接生命周期、收发、状态 |
| Create `.../bridge/channel/dingtalk/DingtalkChannelFactory.kt` | 工厂 |
| Modify `src/main/resources/messages/ImuxBundle*.properties`（10 个） | 7 条新文案 |
| Create `src/test/kotlin/com/github/izerui/imux/bridge/channel/WebSocketPingTest.kt` | ping/pong |
| Create `src/test/kotlin/.../bridge/channel/dingtalk/DingtalkFakeHttp.kt` | 钉钉测试用假 HTTP |
| Create `src/test/kotlin/.../bridge/channel/dingtalk/Dingtalk*Test.kt` | 各单元测试 |
| Modify `src/test/kotlin/.../bridge/channel/ChannelIsolationSourceTest.kt` | 渠道子包互不引用 |
| Modify `src/test/kotlin/.../bridge/channel/ChannelFactoriesTest.kt` | 钉钉登记 |

下文 `.../bridge` 指 `com/github/izerui/imux/bridge`（main 在 `src/main/kotlin/` 下，test 在 `src/test/kotlin/` 下）。

---

### Task 1: WebSocket ping/pong

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/channel/Transports.kt`（`FrameListener`、`FrameTransport`、`JdkWebSocketTransport`）
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/WebSocketPingTest.kt`

**Interfaces:**
- Produces: `FrameTransport.sendPing(): Unit`（suspend，默认抛 `UnsupportedOperationException`）；`FrameListener.onPong()`（默认空实现）。Task 6 用它们做存活检查。

原因（写进 KDoc）：钉钉服务端应用层 ping 的频率未公布，官方 Java SDK 靠 WebSocket ping 判断存活。企业微信、飞书不调用，行为不变。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class WebSocketPingTest {
    @Test
    fun `没实现 ping 的传输调用时报不支持`() {
        val transport =
            object : FrameTransport {
                override suspend fun open(
                    url: String,
                    listener: FrameListener,
                ) = Unit

                override suspend fun send(text: String) = Unit

                override fun close() = Unit
            }
        assertThrows(UnsupportedOperationException::class.java) { runBlocking { transport.sendPing() } }
    }

    @Test
    fun `JDK 传输发出 ping，收到 pong 时回调`() =
        runBlocking {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val opcode = CompletableFuture<Int>()
                thread(isDaemon = true) {
                    runCatching {
                        server.accept().use { socket ->
                            val input = DataInputStream(socket.getInputStream())
                            val output = socket.getOutputStream()
                            val key =
                                readRequest(input).lines()
                                    .first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                                    .substringAfter(':').trim()
                            val accept =
                                Base64.getEncoder().encodeToString(
                                    MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)),
                                )
                            output.write(
                                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
                                    .toByteArray(Charsets.US_ASCII),
                            )
                            output.flush()
                            // 客户端帧一定带掩码；ping 的负载很短，长度在一个字节内
                            val code = input.readUnsignedByte() and 0x0F
                            val length = input.readUnsignedByte() and 0x7F
                            val mask = ByteArray(4).also(input::readFully)
                            val payload = ByteArray(length).also(input::readFully)
                            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                            opcode.complete(code)
                            output.write(byteArrayOf(0x8A.toByte(), payload.size.toByte()) + payload)
                            output.flush()
                            input.read()
                        }
                    }.onFailure { opcode.completeExceptionally(it) }
                }
                val pong = CompletableDeferred<Unit>()
                val transport = JdkWebSocketTransport()
                transport.open(
                    "ws://127.0.0.1:${server.localPort}/",
                    object : FrameListener {
                        override fun onText(text: String) = Unit

                        override fun onPong() {
                            pong.complete(Unit)
                        }

                        override fun onClosed(reason: String) = Unit
                    },
                )
                transport.sendPing()
                assertEquals(0x9, opcode.get(5, TimeUnit.SECONDS))
                withTimeout(5_000) { pong.await() }
                transport.close()
            }
        }

    private fun readRequest(input: DataInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (!bytes.toString(Charsets.US_ASCII).endsWith("\r\n\r\n")) bytes.write(input.readUnsignedByte())
        return bytes.toString(Charsets.US_ASCII)
    }

    private companion object {
        const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.WebSocketPingTest'`
Expected: 编译失败，`sendPing` / `onPong` 未定义。

- [ ] **Step 3: 实现**

在 `FrameListener` 的 `onBinary` 之后加：

```kotlin
    /** WebSocket pong（回应 [FrameTransport.sendPing]）。不发 ping 的渠道不必实现。 */
    fun onPong() {}
```

在 `FrameTransport` 的 `sendBinary` 之后加：

```kotlin
    /**
     * WebSocket 级 ping，与 [send] 共用同一串行顺序。服务端应用层心跳频率不明时（钉钉）用它判断存活；
     * 不需要的渠道不必实现。
     */
    suspend fun sendPing(): Unit = throw UnsupportedOperationException("不支持 ping")
```

在 `JdkWebSocketTransport.open` 的匿名 `WebSocket.Listener` 里、`onBinary` 之后加（JDK 默认实现会 `request(1)`，覆盖后必须自己调）：

```kotlin
                            override fun onPong(
                                webSocket: WebSocket,
                                message: ByteBuffer,
                            ): CompletionStage<*>? {
                                listener.onPong()
                                webSocket.request(1)
                                return null
                            }
```

在 `JdkWebSocketTransport` 的 `sendBinary` 之后加：

```kotlin
    override suspend fun sendPing() {
        sendLock.withLock {
            val current = socket ?: throw IOException("WebSocket 未连接")
            current.sendPing(ByteBuffer.allocate(0)).await()
        }
    }
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.WebSocketPingTest' --tests 'com.github.izerui.imux.bridge.channel.feishu.*' --tests 'com.github.izerui.imux.bridge.channel.wecom.*'`
Expected: 全部 PASS（企业微信、飞书不受影响）。

- [ ] **Step 5: 不提交**（main 上由用户提交）。

---

### Task 2: DingtalkEvents（帧与消息解析）

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/dingtalk/DingtalkEvents.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/dingtalk/DingtalkEventsTest.kt`

**Interfaces:**
- Produces（Task 3、5、6 使用）：
  - `data class DingtalkFrame(val type: String, val topic: String?, val messageId: String?, val data: String?)`
  - `data class DingtalkMediaRef(val kind: AttachmentKind, val downloadCode: String, val fileName: String?)`
  - `sealed interface DingtalkInbound`：`Message(msgId: String, staffId: String, text: String?, media: List<DingtalkMediaRef>, quote: String?)`、`MissingStaffId(msgId: String)`、`QuotaExceeded(errorCode: String)`
  - `object DingtalkEvents`：常量 `TYPE_SYSTEM`、`TYPE_CALLBACK`、`TOPIC_PING`、`TOPIC_DISCONNECT`、`TOPIC_BOT_MESSAGE`；`frame(text: String): DingtalkFrame?`；`ack(messageId: String): String`；`pong(messageId: String, data: String?): String`；`message(data: String): DingtalkInbound?`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DingtalkEventsTest {
    private fun data(
        msgtype: String,
        body: JsonObject.() -> Unit,
        staffId: String? = "u1",
        conversationType: String = "1",
    ) = JsonObject().apply {
        addProperty("conversationType", conversationType)
        addProperty("msgId", "m1")
        staffId?.let { addProperty("senderStaffId", it) }
        addProperty("senderId", "enc")
        addProperty("robotCode", "cid")
        addProperty("msgtype", msgtype)
        body()
    }.toString()

    private fun content(vararg pairs: Pair<String, Any>): JsonObject.() -> Unit = {
        add("content", JsonObject().apply { pairs.forEach { (k, v) -> if (v is Boolean) addProperty(k, v) else addProperty(k, v.toString()) } })
    }

    private fun textBody(
        text: String,
        replied: JsonObject? = null,
    ): JsonObject.() -> Unit = {
        add(
            "text",
            JsonObject().apply {
                addProperty("content", text)
                replied?.let {
                    addProperty("isReplyMsg", true)
                    add("repliedMsg", it)
                }
            },
        )
    }

    @Test
    fun `解析推送帧`() {
        val frame =
            DingtalkEvents.frame(
                """{"specVersion":"1.0","type":"CALLBACK","headers":{"topic":"/v1.0/im/bot/messages/get","messageId":"h1","contentType":"application/json"},"data":"{\"a\":1}"}""",
            )
        assertEquals(DingtalkFrame("CALLBACK", "/v1.0/im/bot/messages/get", "h1", """{"a":1}"""), frame)
        assertNull(DingtalkEvents.frame("not json"))
        assertNull(DingtalkEvents.frame("""{"headers":{}}"""))
    }

    @Test
    fun `ACK 与 pong 的格式`() {
        val ack = JsonParser.parseString(DingtalkEvents.ack("h1")).asJsonObject
        assertEquals(200, ack["code"].asInt)
        assertEquals("h1", ack["headers"].asJsonObject["messageId"].asString)
        assertEquals("application/json", ack["headers"].asJsonObject["contentType"].asString)
        assertEquals("OK", ack["message"].asString)
        assertEquals("""{"response": null}""", ack["data"].asString)
        val pong = JsonParser.parseString(DingtalkEvents.pong("p1", """{"opaque":"x"}""")).asJsonObject
        assertEquals("p1", pong["headers"].asJsonObject["messageId"].asString)
        assertEquals("""{"opaque":"x"}""", pong["data"].asString)
    }

    @Test
    fun `文本消息`() {
        assertEquals(
            DingtalkInbound.Message("m1", "u1", "你好", emptyList(), null),
            DingtalkEvents.message(data("text", textBody("你好"))),
        )
    }

    @Test
    fun `富文本拼接文字，图片作为附件`() {
        val body: JsonObject.() -> Unit = {
            val items =
                com.google.gson.JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", "第一段") })
                    add(JsonObject().apply { addProperty("type", "picture"); addProperty("downloadCode", "p1") })
                    add(JsonObject().apply { addProperty("text", "第二段") })
                }
            add("content", JsonObject().apply { add("richText", items) })
        }
        assertEquals(
            DingtalkInbound.Message("m1", "u1", "第一段第二段", listOf(DingtalkMediaRef(AttachmentKind.IMAGE, "p1", null)), null),
            DingtalkEvents.message(data("richText", body)),
        )
    }

    @Test
    fun `图片、视频、文件作为附件`() {
        assertEquals(
            listOf(DingtalkMediaRef(AttachmentKind.IMAGE, "d1", null)),
            (DingtalkEvents.message(data("picture", content("downloadCode" to "d1"))) as DingtalkInbound.Message).media,
        )
        assertEquals(
            listOf(DingtalkMediaRef(AttachmentKind.IMAGE, "pd", null)),
            (DingtalkEvents.message(data("picture", content("pictureDownloadCode" to "pd"))) as DingtalkInbound.Message).media,
        )
        assertEquals(
            listOf(DingtalkMediaRef(AttachmentKind.VIDEO, "v1", null)),
            (DingtalkEvents.message(data("video", content("downloadCode" to "v1", "duration" to "3"))) as DingtalkInbound.Message).media,
        )
        assertEquals(
            listOf(DingtalkMediaRef(AttachmentKind.FILE, "f1", "报告.pdf")),
            (DingtalkEvents.message(data("file", content("downloadCode" to "f1", "fileName" to "报告.pdf"))) as DingtalkInbound.Message).media,
        )
    }

    @Test
    fun `语音有转写时作为文字，没有时作为音频附件`() {
        assertEquals(
            DingtalkInbound.Message("m1", "u1", "跑一下单测", emptyList(), null),
            DingtalkEvents.message(data("audio", content("downloadCode" to "a1", "recognition" to "跑一下单测"))),
        )
        assertEquals(
            DingtalkInbound.Message("m1", "u1", null, listOf(DingtalkMediaRef(AttachmentKind.AUDIO, "a1", null)), null),
            DingtalkEvents.message(data("audio", content("downloadCode" to "a1", "recognition" to " "))),
        )
    }

    @Test
    fun `引用文字：content 为对象或字符串都取得到，引用图片时为空`() {
        val asObject = JsonObject().apply { addProperty("msgType", "text"); add("content", JsonObject().apply { addProperty("text", "原话") }) }
        assertEquals("原话", (DingtalkEvents.message(data("text", textBody("这个呢", asObject))) as DingtalkInbound.Message).quote)
        val asString = JsonObject().apply { addProperty("msgType", "text"); addProperty("content", """{"text":"原话"}""") }
        assertEquals("原话", (DingtalkEvents.message(data("text", textBody("这个呢", asString))) as DingtalkInbound.Message).quote)
        val picture = JsonObject().apply { addProperty("msgType", "picture"); add("content", JsonObject().apply { addProperty("downloadCode", "x") }) }
        assertNull((DingtalkEvents.message(data("text", textBody("这个呢", picture))) as DingtalkInbound.Message).quote)
    }

    @Test
    fun `引用富文本时拼接其中文字`() {
        val replied =
            JsonObject().apply {
                addProperty("msgType", "richText")
                add(
                    "content",
                    JsonObject().apply {
                        add(
                            "richText",
                            com.google.gson.JsonArray().apply {
                                add(JsonObject().apply { addProperty("text", "甲") })
                                add(JsonObject().apply { addProperty("text", "乙") })
                            },
                        )
                    },
                )
            }
        assertEquals("甲乙", (DingtalkEvents.message(data("text", textBody("看这个", replied))) as DingtalkInbound.Message).quote)
    }

    @Test
    fun `群聊、不支持的类型、空文字都忽略`() {
        assertNull(DingtalkEvents.message(data("text", textBody("你好"), conversationType = "2")))
        assertNull(DingtalkEvents.message(data("unknownMsgType", content("unknownMsgType" to "暂不支持"))))
        assertNull(DingtalkEvents.message(data("text", textBody("  "))))
        assertNull(DingtalkEvents.message("not json"))
    }

    @Test
    fun `没有 staffId 时单独标出`() {
        assertEquals(DingtalkInbound.MissingStaffId("m1"), DingtalkEvents.message(data("text", textBody("你好"), staffId = null)))
        assertEquals(DingtalkInbound.MissingStaffId("m1"), DingtalkEvents.message(data("text", textBody("你好"), staffId = "")))
    }

    @Test
    fun `超额消息只剩错误码`() {
        assertEquals(
            DingtalkInbound.QuotaExceeded("20001"),
            DingtalkEvents.message("""{"conversationType":"1","errorCode":20001,"errorMessage":"受调用量超量影响"}"""),
        )
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.dingtalk.DingtalkEventsTest'`
Expected: 编译失败，`DingtalkEvents` 未定义。

- [ ] **Step 3: 实现**

```kotlin
package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.json.flag
import com.github.izerui.imux.bridge.json.obj
import com.github.izerui.imux.bridge.json.objects
import com.github.izerui.imux.bridge.json.primitiveText
import com.github.izerui.imux.bridge.json.string
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Stream 推送帧。[data] 是 JSON 字符串，原样保留（系统 ping 要原样回传）。 */
data class DingtalkFrame(
    val type: String,
    val topic: String?,
    val messageId: String?,
    val data: String?,
)

/** 消息里的一个附件：只记下载码，路由器放行后才换地址下载。 */
data class DingtalkMediaRef(
    val kind: AttachmentKind,
    val downloadCode: String,
    val fileName: String?,
)

/** 一条机器人消息的解析结果。 */
sealed interface DingtalkInbound {
    data class Message(
        val msgId: String,
        val staffId: String,
        val text: String?,
        val media: List<DingtalkMediaRef>,
        val quote: String?,
    ) : DingtalkInbound

    /** 没有 senderStaffId：机器人可能未发布，既无法回复也无法配对。 */
    data class MissingStaffId(val msgId: String) : DingtalkInbound

    /** Webhook&Stream 用量超额：消息只剩错误码，没有内容。 */
    data class QuotaExceeded(val errorCode: String) : DingtalkInbound
}

/**
 * 钉钉 Stream 协议的帧与机器人消息（官方《Stream模式协议接入说明》《消息发送与接收类型》；
 * 引用字段只见于官方连接器源码）。纯函数。
 */
object DingtalkEvents {
    const val TYPE_SYSTEM = "SYSTEM"
    const val TYPE_CALLBACK = "CALLBACK"
    const val TOPIC_PING = "ping"
    const val TOPIC_DISCONNECT = "disconnect"
    const val TOPIC_BOT_MESSAGE = "/v1.0/im/bot/messages/get"

    private const val SINGLE_CHAT = "1"

    /** 机器人消息的回应内容平台不看，按官方示例固定。 */
    private const val ACK_DATA = """{"response": null}"""

    fun frame(text: String): DingtalkFrame? {
        val json = parseObject(text) ?: return null
        val type = json.string("type") ?: return null
        val headers = json.obj("headers")
        return DingtalkFrame(type, headers?.string("topic"), headers?.string("messageId"), json.string("data"))
    }

    fun ack(messageId: String): String = reply(messageId, ACK_DATA)

    /** 系统 ping 的回应：data 原样回传。 */
    fun pong(
        messageId: String,
        data: String?,
    ): String = reply(messageId, data.orEmpty())

    fun message(data: String): DingtalkInbound? {
        val json = parseObject(data) ?: return null
        json.primitiveText("errorCode")?.let { return DingtalkInbound.QuotaExceeded(it) }
        if (json.primitiveText("conversationType") != SINGLE_CHAT) return null
        val msgId = json.string("msgId")?.takeIf(String::isNotEmpty) ?: return null
        val staffId = json.string("senderStaffId")?.takeIf(String::isNotEmpty) ?: return DingtalkInbound.MissingStaffId(msgId)
        val content = json.objectOrJson("content")
        var text: String? = null
        var media: List<DingtalkMediaRef> = emptyList()
        var quoteContainer: JsonObject? = null
        when (json.string("msgtype")) {
            "text" -> {
                val body = json.obj("text")
                text = body?.string("content")
                quoteContainer = body
            }

            "richText" -> {
                text = richText(content)
                media = richImages(content)
                quoteContainer = content
            }

            "picture" -> media = listOfNotNull(content?.mediaRef(AttachmentKind.IMAGE, null))
            "video" -> media = listOfNotNull(content?.mediaRef(AttachmentKind.VIDEO, null))
            "file" -> media = listOfNotNull(content?.let { it.mediaRef(AttachmentKind.FILE, it.string("fileName")) })
            "audio" -> {
                // 平台给了转写就当作文字（与企业微信一致），没有才作为音频转交
                val recognition = content?.string("recognition")?.takeIf(String::isNotBlank)
                if (recognition != null) {
                    text = recognition
                } else {
                    media = listOfNotNull(content?.mediaRef(AttachmentKind.AUDIO, null))
                }
            }

            else -> return null
        }
        val body = text?.takeIf(String::isNotBlank)
        if (body == null && media.isEmpty()) return null
        return DingtalkInbound.Message(msgId, staffId, body, media, quote(quoteContainer))
    }

    /** 被引用消息的文字；引用的不是文字时为 null。 */
    private fun quote(container: JsonObject?): String? {
        if (container == null || !container.flag("isReplyMsg")) return null
        val replied = container.obj("repliedMsg") ?: return null
        val content = replied.objectOrJson("content")
        val text =
            when (replied.string("msgType") ?: "text") {
                "text" -> content?.string("text") ?: replied.string("text")
                "richText" -> richText(content)
                else -> null
            }
        return text?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun richText(content: JsonObject?): String? =
        content?.objects("richText").orEmpty().mapNotNull { it.string("text") }.joinToString("").takeIf(String::isNotBlank)

    private fun richImages(content: JsonObject?): List<DingtalkMediaRef> =
        content?.objects("richText").orEmpty()
            .filter { (it.string("type") ?: "picture") == "picture" }
            .mapNotNull { it.mediaRef(AttachmentKind.IMAGE, null) }

    private fun JsonObject.mediaRef(
        kind: AttachmentKind,
        fileName: String?,
    ): DingtalkMediaRef? {
        val code = (string("downloadCode") ?: string("pictureDownloadCode"))?.takeIf(String::isNotEmpty) ?: return null
        return DingtalkMediaRef(kind, code, fileName?.takeIf(String::isNotEmpty))
    }

    /** 字段可能是对象，也可能是对象的 JSON 字符串。 */
    private fun JsonObject.objectOrJson(key: String): JsonObject? = obj(key) ?: string(key)?.let(::parseObject)

    private fun parseObject(text: String): JsonObject? =
        runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

    private fun reply(
        messageId: String,
        data: String,
    ): String =
        JsonObject().apply {
            addProperty("code", 200)
            add(
                "headers",
                JsonObject().apply {
                    addProperty("messageId", messageId)
                    addProperty("contentType", "application/json")
                },
            )
            addProperty("message", "OK")
            addProperty("data", data)
        }.toString()
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.dingtalk.DingtalkEventsTest'`
Expected: PASS。

- [ ] **Step 5: 不提交。**

---

### Task 3: DingtalkApi（HTTP 接口与令牌）

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/dingtalk/DingtalkApi.kt`
- Create: `src/test/kotlin/com/github/izerui/imux/bridge/channel/dingtalk/DingtalkFakeHttp.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/dingtalk/DingtalkApiTest.kt`

**Interfaces:**
- Consumes: `DingtalkEvents.TOPIC_BOT_MESSAGE`（Task 2）。
- Produces（Task 4、5、6 使用）：
  - `sealed interface DingtalkResult<out T>`：`Ok(value)`、`Error(status: Int, code: String, message: String)`、`Network(reason: String)`
  - `data class DingtalkEndpoint(endpoint: String, ticket: String) { fun url(): String }`
  - `data class DingtalkSendOutcome(invalidStaffIds: List<String>, flowControlledStaffIds: List<String>)`
  - `object DingtalkCodes`：`TOKEN_INVALID`、`CREDENTIALS_INVALID`、`QUOTA_EXCEEDED`、`NO_PERMISSION`、`isRateLimited(code)`、`isTokenInvalid(error)`、`isCredentialError(error)`
  - `class DingtalkApi(clientId, clientSecret, http: HttpJson = JdkHttpJson(), now: () -> Long = System::currentTimeMillis)`：`openConnection()`、`token()`、`invalidateToken()`、`send(staffId, msgParam)`、`downloadUrl(downloadCode)`；companion：`MAX_MSG_PARAM_BYTES = 15_000`、`MSG_KEY`、`TOKEN_HEADER`、`markdownParam(title, text)`、`title(text)`
  - 测试工具 `DingtalkFakeHttp`（Task 4、5、6 的测试使用）

- [ ] **Step 1: 写假 HTTP**

```kotlin
package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.channel.HttpJson
import com.github.izerui.imux.bridge.channel.HttpJsonResponse
import com.google.gson.JsonParser
import java.util.concurrent.CopyOnWriteArrayList

/** 钉钉测试用的假 HTTP：按地址返回 JSON，记录每次请求的方法、地址、正文与请求头。route 抛异常即模拟网络错误。 */
class DingtalkFakeHttp(
    private val route: suspend (url: String, body: String?) -> Pair<Int, String>,
) : HttpJson {
    data class Call(
        val method: String,
        val url: String,
        val body: String?,
        val headers: Map<String, String>,
    )

    val calls = CopyOnWriteArrayList<Call>()

    fun calls(suffix: String) = calls.filter { it.url.endsWith(suffix) }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpJsonResponse = answer("GET", url, null, headers)

    override suspend fun post(
        url: String,
        contentType: String,
        body: String,
        headers: Map<String, String>,
    ): HttpJsonResponse = answer("POST", url, body, headers)

    private suspend fun answer(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): HttpJsonResponse {
        calls += Call(method, url, body, headers)
        val (status, text) = route(url, body)
        val json = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        return HttpJsonResponse(status, json)
    }
}
```

- [ ] **Step 2: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.dingtalk

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class DingtalkApiTest {
    private var clock = 1_000_000L
    private val tokens = AtomicInteger()

    @Volatile
    private var sendAnswer: Pair<Int, String> = 200 to """{"processQueryKey":"q","invalidStaffIdList":[],"flowControlledStaffIdList":[]}"""

    private val http =
        DingtalkFakeHttp { url, _ ->
            when {
                url.endsWith("/v1.0/oauth2/accessToken") -> 200 to """{"accessToken":"T${tokens.incrementAndGet()}","expireIn":7200}"""
                url.endsWith("/v1.0/gateway/connections/open") -> 200 to """{"endpoint":"wss://wss-open-connection.dingtalk.com:443/connect","ticket":"a+b/c"}"""
                url.endsWith("/v1.0/robot/oToMessages/batchSend") -> sendAnswer
                url.endsWith("/v1.0/robot/messageFiles/download") -> 200 to """{"downloadUrl":"https://oss/x"}"""
                else -> 404 to "{}"
            }
        }

    private val api = DingtalkApi("cid", "sec", http) { clock }

    @Test
    fun `令牌缓存，剩余不足十分钟才重取`() =
        runBlocking {
            assertEquals(DingtalkResult.Ok("T1"), api.token())
            clock += 6_000_000
            assertEquals(DingtalkResult.Ok("T1"), api.token())
            clock += 700_000
            assertEquals(DingtalkResult.Ok("T2"), api.token())
            val body = JsonParser.parseString(http.calls("/v1.0/oauth2/accessToken").first().body).asJsonObject
            assertEquals("cid", body["appKey"].asString)
            assertEquals("sec", body["appSecret"].asString)
        }

    @Test
    fun `建连请求与地址拼接`() =
        runBlocking {
            val endpoint = (api.openConnection() as DingtalkResult.Ok).value
            assertEquals("wss://wss-open-connection.dingtalk.com:443/connect?ticket=a%2Bb%2Fc", endpoint.url())
            val call = http.calls("/v1.0/gateway/connections/open").single()
            assertEquals("application/json", call.headers["Accept"])
            val body = JsonParser.parseString(call.body).asJsonObject
            assertEquals("cid", body["clientId"].asString)
            assertEquals("sec", body["clientSecret"].asString)
            val subscription = body["subscriptions"].asJsonArray.single().asJsonObject
            assertEquals("CALLBACK", subscription["type"].asString)
            assertEquals("/v1.0/im/bot/messages/get", subscription["topic"].asString)
        }

    @Test
    fun `发送请求体与两个名单`() =
        runBlocking {
            val param = DingtalkApi.markdownParam("标题", "正文")
            assertEquals(DingtalkResult.Ok(DingtalkSendOutcome(emptyList(), emptyList())), api.send("u1", param))
            val call = http.calls("/v1.0/robot/oToMessages/batchSend").single()
            assertEquals("T1", call.headers[DingtalkApi.TOKEN_HEADER])
            val body = JsonParser.parseString(call.body).asJsonObject
            assertEquals("cid", body["robotCode"].asString)
            assertEquals("u1", body["userIds"].asJsonArray.single().asString)
            assertEquals("sampleMarkdown", body["msgKey"].asString)
            assertEquals(param, body["msgParam"].asString)
            val msgParam = JsonParser.parseString(body["msgParam"].asString).asJsonObject
            assertEquals("标题", msgParam["title"].asString)
            assertEquals("正文", msgParam["text"].asString)
            sendAnswer = 200 to """{"invalidStaffIdList":["u1"],"flowControlledStaffIdList":["u2"]}"""
            assertEquals(DingtalkResult.Ok(DingtalkSendOutcome(listOf("u1"), listOf("u2"))), api.send("u1", param))
        }

    @Test
    fun `令牌失效时重取并只重试一次`() =
        runBlocking {
            sendAnswer = 401 to """{"code":"InvalidAuthentication","message":"x"}"""
            val result = api.send("u1", "{}")
            assertEquals(DingtalkResult.Error(401, "InvalidAuthentication", "x"), result)
            assertEquals(2, http.calls("/v1.0/oauth2/accessToken").size)
            assertEquals(2, http.calls("/v1.0/robot/oToMessages/batchSend").size)
        }

    @Test
    fun `业务错误与网络错误`() =
        runBlocking {
            sendAnswer = 403 to """{"code":"Forbidden.AccessDenied.ApiCountLimitForOrg","message":"quota"}"""
            assertEquals(DingtalkResult.Error(403, "Forbidden.AccessDenied.ApiCountLimitForOrg", "quota"), api.send("u1", "{}"))
            sendAnswer = 502 to "<html>bad gateway</html>"
            assertEquals(DingtalkResult.Network("HTTP 502"), api.send("u1", "{}"))
            val broken = DingtalkApi("cid", "sec", DingtalkFakeHttp { _, _ -> throw IOException("reset") })
            assertEquals(DingtalkResult.Network("reset"), broken.openConnection())
        }

    @Test
    fun `换附件下载地址`() =
        runBlocking {
            assertEquals(DingtalkResult.Ok("https://oss/x"), api.downloadUrl("d1"))
            val call = http.calls("/v1.0/robot/messageFiles/download").single()
            assertEquals("T1", call.headers[DingtalkApi.TOKEN_HEADER])
            val body = JsonParser.parseString(call.body).asJsonObject
            assertEquals("d1", body["downloadCode"].asString)
            assertEquals("cid", body["robotCode"].asString)
        }

    @Test
    fun `错误码分类`() {
        assertTrue(DingtalkCodes.isRateLimited("Forbidden.AccessDenied.QpsLimitForAppkeyAndApi"))
        assertTrue(DingtalkCodes.isRateLimited("Forbidden.AccessDenied.QpsLimitForApi"))
        assertTrue(DingtalkCodes.isRateLimited("send.too.fast"))
        assertFalse(DingtalkCodes.isRateLimited(DingtalkCodes.QUOTA_EXCEEDED))
        assertTrue(DingtalkCodes.isCredentialError(DingtalkResult.Error(400, "invalidClientIdOrSecret", "")))
        assertTrue(DingtalkCodes.isCredentialError(DingtalkResult.Error(401, "x", "")))
        assertFalse(DingtalkCodes.isCredentialError(DingtalkResult.Error(403, DingtalkCodes.QUOTA_EXCEEDED, "")))
        assertTrue(DingtalkCodes.isTokenInvalid(DingtalkResult.Error(400, "token.notExisted", "")))
        assertTrue(DingtalkCodes.isTokenInvalid(DingtalkResult.Error(401, "x", "")))
    }

    @Test
    fun `标题取第一行有字的内容，去掉标记，最多二十字`() {
        assertEquals("结论", DingtalkApi.title("## 结论\n正文"))
        assertEquals("fun main() = Unit", DingtalkApi.title("```kotlin\nfun main() = Unit\n```"))
        assertEquals("要点一", DingtalkApi.title("\n- **要点一**"))
        assertEquals("一二三四五六七八九十一二三四五六七八九十", DingtalkApi.title("一二三四五六七八九十一二三四五六七八九十多出来的"))
        assertEquals("imux", DingtalkApi.title("  \n"))
    }
}
```

- [ ] **Step 3: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.dingtalk.DingtalkApiTest'`
Expected: 编译失败，`DingtalkApi` 未定义。

- [ ] **Step 4: 实现**

```kotlin
package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.channel.HttpJson
import com.github.izerui.imux.bridge.channel.HttpJsonResponse
import com.github.izerui.imux.bridge.channel.JdkHttpJson
import com.github.izerui.imux.bridge.json.primitiveText
import com.github.izerui.imux.bridge.json.string
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.cancellation.CancellationException

/** 一次钉钉调用的结果：成功、平台业务错误、网络或解析失败（结果不明）。 */
sealed interface DingtalkResult<out T> {
    data class Ok<out T>(val value: T) : DingtalkResult<T>

    /** 新版接口以非 2xx 返回 `{"code","message"}`，code 是字符串。 */
    data class Error(
        val status: Int,
        val code: String,
        val message: String,
    ) : DingtalkResult<Nothing>

    data class Network(val reason: String) : DingtalkResult<Nothing>
}

data class DingtalkEndpoint(
    val endpoint: String,
    val ticket: String,
) {
    /** endpoint 已含 /connect 路径，直接拼 ticket。 */
    fun url(): String = "$endpoint?ticket=${URLEncoder.encode(ticket, StandardCharsets.UTF_8)}"
}

/** batchSend 的 HTTP 200 不等于送达：两个名单里的用户没有收到。 */
data class DingtalkSendOutcome(
    val invalidStaffIds: List<String>,
    val flowControlledStaffIds: List<String>,
)

/** 官方文档里用到的错误码（设计第 2 节）。 */
object DingtalkCodes {
    /** batchSend 文档里的令牌错误；HTTP 401 也按令牌失效处理。 */
    val TOKEN_INVALID = setOf("invalidParameter.token.invalid", "token.notExisted")
    val CREDENTIALS_INVALID = setOf("invalidClientIdOrSecret")
    const val QUOTA_EXCEEDED = "Forbidden.AccessDenied.ApiCountLimitForOrg"
    val NO_PERMISSION =
        setOf(
            "chatbotId.notAllow.sendOTO",
            "robot.oto.notExist",
            "bot.forbidden.sendMessage",
            "staffId.notExisted",
            "invalidParameter.userId.empty",
        )
    private val SEND_TOO_FAST = setOf("send.too.fast", "send.byToken.tooFast")

    fun isRateLimited(code: String): Boolean =
        code in SEND_TOO_FAST || code.endsWith("QpsLimitForAppkeyAndApi") || code.endsWith("QpsLimitForApi")

    fun isTokenInvalid(error: DingtalkResult.Error): Boolean = error.status == 401 || error.code in TOKEN_INVALID

    /** 凭据被拒：明确的凭据错误码或 HTTP 401。403 不算：额度、限流等拒绝也可能以 403 返回。 */
    fun isCredentialError(error: DingtalkResult.Error): Boolean = error.status == 401 || error.code in CREDENTIALS_INVALID
}

/**
 * 钉钉的 HTTP 调用：建立 Stream 连接、应用令牌、单聊批量发送、附件下载地址。
 * 令牌只在内存，随用随取；剩余不足 10 分钟就重新获取，令牌失效时清掉重取并只重试一次。
 */
class DingtalkApi(
    private val clientId: String,
    private val clientSecret: String,
    private val http: HttpJson = JdkHttpJson(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val tokenLock = Mutex()

    @Volatile
    private var token: String? = null

    @Volatile
    private var tokenExpiresAt = 0L

    suspend fun openConnection(): DingtalkResult<DingtalkEndpoint> {
        val subscription =
            JsonObject().apply {
                addProperty("type", "CALLBACK")
                addProperty("topic", DingtalkEvents.TOPIC_BOT_MESSAGE)
            }
        val body =
            JsonObject().apply {
                addProperty("clientId", clientId)
                addProperty("clientSecret", clientSecret)
                add("subscriptions", JsonArray().apply { add(subscription) })
                addProperty("ua", USER_AGENT)
            }
        // 不带 Accept 时可能返回 XML（官方 Node SDK 注释）
        return when (val result = request { http.post(CONNECTION_URL, JSON_TYPE, body.toString(), ACCEPT_JSON) }) {
            is DingtalkResult.Ok -> {
                val endpoint = result.value.string("endpoint")
                val ticket = result.value.string("ticket")
                if (endpoint.isNullOrEmpty() || ticket.isNullOrEmpty()) {
                    DingtalkResult.Network("no endpoint")
                } else {
                    DingtalkResult.Ok(DingtalkEndpoint(endpoint, ticket))
                }
            }

            is DingtalkResult.Error -> result
            is DingtalkResult.Network -> result
        }
    }

    suspend fun token(): DingtalkResult<String> =
        tokenLock.withLock {
            val cached = token
            if (cached != null && tokenExpiresAt - now() > REFRESH_MARGIN_MILLIS) return@withLock DingtalkResult.Ok(cached)
            val body =
                JsonObject().apply {
                    addProperty("appKey", clientId)
                    addProperty("appSecret", clientSecret)
                }
            when (val result = request { http.post(TOKEN_URL, JSON_TYPE, body.toString(), ACCEPT_JSON) }) {
                is DingtalkResult.Ok -> {
                    val value = result.value.string("accessToken")
                    if (value.isNullOrEmpty()) {
                        DingtalkResult.Network("no token")
                    } else {
                        token = value
                        tokenExpiresAt = now() + (result.value.primitiveText("expireIn")?.toLongOrNull() ?: 0L) * 1_000
                        DingtalkResult.Ok(value)
                    }
                }

                is DingtalkResult.Error -> result
                is DingtalkResult.Network -> result
            }
        }

    fun invalidateToken() {
        token = null
    }

    /** 给一个人发一条 sampleMarkdown；[msgParam] 由 [markdownParam] 生成。 */
    suspend fun send(
        staffId: String,
        msgParam: String,
    ): DingtalkResult<DingtalkSendOutcome> {
        val body =
            JsonObject().apply {
                addProperty("robotCode", clientId)
                add("userIds", JsonArray().apply { add(staffId) })
                addProperty("msgKey", MSG_KEY)
                addProperty("msgParam", msgParam)
            }
        return when (val result = authorized { http.post(SEND_URL, JSON_TYPE, body.toString(), tokenHeader(it)) }) {
            is DingtalkResult.Ok ->
                DingtalkResult.Ok(
                    DingtalkSendOutcome(result.value.strings("invalidStaffIdList"), result.value.strings("flowControlledStaffIdList")),
                )

            is DingtalkResult.Error -> result
            is DingtalkResult.Network -> result
        }
    }

    /** 用消息里的下载码换临时下载地址（OSS 签名地址，不需要令牌）。 */
    suspend fun downloadUrl(downloadCode: String): DingtalkResult<String> {
        val body =
            JsonObject().apply {
                addProperty("downloadCode", downloadCode)
                addProperty("robotCode", clientId)
            }
        return when (val result = authorized { http.post(DOWNLOAD_URL, JSON_TYPE, body.toString(), tokenHeader(it)) }) {
            is DingtalkResult.Ok ->
                result.value.string("downloadUrl")?.takeIf(String::isNotEmpty)?.let { DingtalkResult.Ok(it) }
                    ?: DingtalkResult.Network("no downloadUrl")

            is DingtalkResult.Error -> result
            is DingtalkResult.Network -> result
        }
    }

    private suspend fun authorized(call: suspend (String) -> HttpJsonResponse): DingtalkResult<JsonObject> {
        var retried = false
        while (true) {
            val current =
                when (val result = token()) {
                    is DingtalkResult.Ok -> result.value
                    is DingtalkResult.Error -> return result
                    is DingtalkResult.Network -> return result
                }
            val result = request { call(current) }
            if (result is DingtalkResult.Error && DingtalkCodes.isTokenInvalid(result) && !retried) {
                retried = true
                invalidateToken()
                continue
            }
            return result
        }
    }

    private suspend fun request(call: suspend () -> HttpJsonResponse): DingtalkResult<JsonObject> {
        val response =
            try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return DingtalkResult.Network(e.message ?: e.javaClass.simpleName)
            }
        val json = response.json ?: return DingtalkResult.Network("HTTP ${response.status}")
        if (response.status in 200..299) return DingtalkResult.Ok(json)
        val code = json.primitiveText("code") ?: return DingtalkResult.Network("HTTP ${response.status}")
        return DingtalkResult.Error(response.status, code, json.string("message").orEmpty())
    }

    companion object {
        /** 单聊上限未公布，参照群发 msgParam 的 15000 字节。 */
        const val MAX_MSG_PARAM_BYTES = 15_000
        const val MSG_KEY = "sampleMarkdown"
        const val TOKEN_HEADER = "x-acs-dingtalk-access-token"

        private const val OPEN_BASE = "https://api.dingtalk.com"
        private const val TOKEN_URL = "$OPEN_BASE/v1.0/oauth2/accessToken"
        private const val CONNECTION_URL = "$OPEN_BASE/v1.0/gateway/connections/open"
        private const val SEND_URL = "$OPEN_BASE/v1.0/robot/oToMessages/batchSend"
        private const val DOWNLOAD_URL = "$OPEN_BASE/v1.0/robot/messageFiles/download"
        private const val JSON_TYPE = "application/json; charset=utf-8"
        private const val USER_AGENT = "imux"
        private const val REFRESH_MARGIN_MILLIS = 10 * 60_000L
        private const val TITLE_MAX_CODE_POINTS = 20
        private const val DEFAULT_TITLE = "imux"
        private val ACCEPT_JSON = mapOf("Accept" to "application/json")
        private val MARKUP_PREFIX = Regex("""^(#{1,6}\s+|>\s*|[-*+]\s+|\d+\.\s+)""")

        fun markdownParam(
            title: String,
            text: String,
        ): String =
            JsonObject().apply {
                addProperty("title", title)
                addProperty("text", text)
            }.toString()

        /** 会话列表与通知里显示的标题：正文第一行有字的内容，去掉 Markdown 标记，最多 20 字。 */
        fun title(text: String): String {
            val line =
                text.lineSequence()
                    .map(String::trim)
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("```") }
                    ?.replace(MARKUP_PREFIX, "")
                    ?.replace("*", "")
                    ?.replace("`", "")
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: return DEFAULT_TITLE
            if (line.codePointCount(0, line.length) <= TITLE_MAX_CODE_POINTS) return line
            return line.substring(0, line.offsetByCodePoints(0, TITLE_MAX_CODE_POINTS))
        }

        private fun tokenHeader(token: String) = mapOf(TOKEN_HEADER to token)

        private fun JsonObject.strings(key: String): List<String> =
            get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString }.orEmpty()
    }
}
```

- [ ] **Step 5: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.dingtalk.DingtalkApiTest'`
Expected: PASS。

- [ ] **Step 6: 不提交。**

---

### Task 4: DingtalkOnboarding（扫码）

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/dingtalk/DingtalkOnboarding.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/dingtalk/DingtalkOnboardingTest.kt`

**Interfaces:**
- Consumes: `DingtalkFakeHttp`（Task 3，测试用）。
- Produces（Task 7 使用）：`object DingtalkCredentialKeys { CLIENT_ID = "client_id"; CLIENT_SECRET = "client_secret" }`；`class DingtalkOnboarding(http: HttpJson = JdkHttpJson()) : ChannelOnboarding`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.channel.OnboardingStep
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class DingtalkOnboardingTest {
    @Volatile
    private var initBody = """{"errcode":0,"errmsg":"ok","nonce":"n1","expires_in":300}"""

    @Volatile
    private var beginBody = """{"errcode":0,"errmsg":"ok","device_code":"dc","verification_uri_complete":"https://qr","interval":3,"expires_in":7200}"""

    private val polls = ArrayDeque<String>()

    private val http =
        DingtalkFakeHttp { url, _ ->
            when {
                url == "https://oapi.dingtalk.com/app/registration/init" -> 200 to initBody
                url == "https://oapi.dingtalk.com/app/registration/begin" -> 200 to beginBody
                url == "https://oapi.dingtalk.com/app/registration/poll" -> {
                    val next = synchronized(polls) { polls.removeFirstOrNull() } ?: """{"errcode":0,"status":"WAITING"}"""
                    if (next == "throw") throw IOException("reset")
                    200 to next
                }

                else -> 404 to "{}"
            }
        }

    private fun poll(vararg bodies: String) = synchronized(polls) { polls.addAll(bodies) }

    @Test
    fun `扫码成功：source 固定，不带本人身份`() =
        runBlocking {
            val onboarding = DingtalkOnboarding(http)
            assertEquals(OnboardingStep.ShowQr("https://qr"), onboarding.start())
            assertEquals(3_000L, onboarding.pollIntervalMillis)
            val init = JsonParser.parseString(http.calls("/registration/init").single().body).asJsonObject
            assertEquals("DING_DWS_CLAW", init["source"].asString)
            assertEquals("n1", JsonParser.parseString(http.calls("/registration/begin").single().body).asJsonObject["nonce"].asString)
            poll("""{"errcode":0,"status":"WAITING"}""", """{"errcode":0,"status":"SUCCESS","client_id":"cid","client_secret":"sec"}""")
            assertEquals(OnboardingStep.Waiting, onboarding.poll())
            assertEquals(
                OnboardingStep.Succeeded(mapOf("client_id" to "cid", "client_secret" to "sec"), ownerId = null, suggestedName = null),
                onboarding.poll(),
            )
            assertEquals("dc", JsonParser.parseString(http.calls("/registration/poll").first().body).asJsonObject["device_code"].asString)
        }

    @Test
    fun `过期、失败、缺凭据`() =
        runBlocking {
            val onboarding = DingtalkOnboarding(http)
            onboarding.start()
            poll(
                """{"errcode":0,"status":"EXPIRED"}""",
                """{"errcode":0,"status":"FAIL","fail_reason":"denied"}""",
                """{"errcode":0,"status":"SUCCESS","client_id":"cid"}""",
            )
            assertEquals(OnboardingStep.Failed("bridge.onboarding.expired"), onboarding.poll())
            assertEquals(OnboardingStep.Failed("bridge.dingtalk.onboardingFailed"), onboarding.poll())
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noCredentials"), onboarding.poll())
        }

    @Test
    fun `轮询遇到网络错误或业务错误继续等`() =
        runBlocking {
            val onboarding = DingtalkOnboarding(http)
            onboarding.start()
            poll("throw", """{"errcode":-1,"errmsg":"busy"}""")
            assertEquals(OnboardingStep.Waiting, onboarding.poll())
            assertEquals(OnboardingStep.Waiting, onboarding.poll())
        }

    @Test
    fun `取不到二维码时失败；间隔缺省两秒`() =
        runBlocking {
            initBody = """{"errcode":-1,"errmsg":"bad"}"""
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noQr"), DingtalkOnboarding(http).start())
            initBody = """{"errcode":0,"nonce":"n1"}"""
            beginBody = """{"errcode":0,"device_code":"dc"}"""
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noQr"), DingtalkOnboarding(http).start())
            beginBody = """{"errcode":0,"device_code":"dc","verification_uri_complete":"https://qr"}"""
            val onboarding = DingtalkOnboarding(http)
            onboarding.start()
            assertEquals(2_000L, onboarding.pollIntervalMillis)
        }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.dingtalk.DingtalkOnboardingTest'`
Expected: 编译失败，`DingtalkOnboarding` 未定义。

- [ ] **Step 3: 实现**

```kotlin
package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.channel.HttpJson
import com.github.izerui.imux.bridge.channel.JdkHttpJson
import com.github.izerui.imux.bridge.channel.OnboardingStep
import com.github.izerui.imux.bridge.json.primitiveText
import com.github.izerui.imux.bridge.json.string
import com.google.gson.JsonObject
import kotlin.coroutines.cancellation.CancellationException

/** 钉钉机器人凭据在凭据库里的键名。 */
object DingtalkCredentialKeys {
    const val CLIENT_ID = "client_id"
    const val CLIENT_SECRET = "client_secret"
}

/**
 * 钉钉扫码创建或选择机器人：官方连接器 device-auth 的注册流程（非公开接口）。
 * 结果不带本人身份，ownerId 一律为 null，走配对码认主。
 * source 沿用连接器的 DING_DWS_CLAW：自定义值会被服务端从二维码链接里去掉（用户 2026-10-29 决定）。
 */
class DingtalkOnboarding(
    private val http: HttpJson = JdkHttpJson(),
) : ChannelOnboarding {
    @Volatile
    private var interval: Long = DEFAULT_INTERVAL_SECONDS * 1_000

    override val pollIntervalMillis: Long
        get() = interval

    private var deviceCode: String? = null

    override suspend fun start(): OnboardingStep {
        val nonce =
            post("init", JsonObject().apply { addProperty("source", SOURCE) })?.string("nonce")?.takeIf(String::isNotEmpty)
                ?: return OnboardingStep.Failed("bridge.onboarding.noQr")
        val begin =
            post("begin", JsonObject().apply { addProperty("nonce", nonce) })
                ?: return OnboardingStep.Failed("bridge.onboarding.noQr")
        val code = begin.string("device_code")
        val qr = begin.string("verification_uri_complete")
        if (code.isNullOrEmpty() || qr.isNullOrEmpty()) return OnboardingStep.Failed("bridge.onboarding.noQr")
        deviceCode = code
        interval = (begin.primitiveText("interval")?.toLongOrNull() ?: DEFAULT_INTERVAL_SECONDS) * 1_000
        return OnboardingStep.ShowQr(qr)
    }

    override suspend fun poll(): OnboardingStep {
        val code = deviceCode ?: return OnboardingStep.Failed("bridge.onboarding.noQr")
        // 网络错误、业务错误都继续等，总超时由调用方兜底
        val result =
            try {
                post("poll", JsonObject().apply { addProperty("device_code", code) })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return OnboardingStep.Waiting
        return when (result.string("status")) {
            "SUCCESS" -> {
                val clientId = result.primitiveText("client_id")
                val secret = result.string("client_secret")
                if (clientId.isNullOrEmpty() || secret.isNullOrEmpty()) {
                    OnboardingStep.Failed("bridge.onboarding.noCredentials")
                } else {
                    OnboardingStep.Succeeded(
                        credentials = mapOf(DingtalkCredentialKeys.CLIENT_ID to clientId, DingtalkCredentialKeys.CLIENT_SECRET to secret),
                        ownerId = null,
                        suggestedName = null,
                    )
                }
            }

            "EXPIRED" -> OnboardingStep.Failed("bridge.onboarding.expired")
            "FAIL" -> OnboardingStep.Failed("bridge.dingtalk.onboardingFailed")
            else -> OnboardingStep.Waiting
        }
    }

    /** errcode 为 0 时返回正文，业务失败返回 null；网络异常照常抛出。 */
    private suspend fun post(
        action: String,
        body: JsonObject,
    ): JsonObject? {
        val json = http.post("$BASE/$action", JSON_TYPE, body.toString()).json ?: return null
        return json.takeIf { it.primitiveText("errcode") == "0" }
    }

    private companion object {
        const val BASE = "https://oapi.dingtalk.com/app/registration"
        const val SOURCE = "DING_DWS_CLAW"
        const val JSON_TYPE = "application/json; charset=utf-8"
        const val DEFAULT_INTERVAL_SECONDS = 2L
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.dingtalk.DingtalkOnboardingTest'`
Expected: PASS。

- [ ] **Step 5: 不提交。**

---

### Task 5: DingtalkAttachment（附件下载）

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/dingtalk/DingtalkAttachment.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/dingtalk/DingtalkAttachmentTest.kt`

**Interfaces:**
- Consumes: `DingtalkMediaRef`（Task 2）；`DingtalkApi.downloadUrl`、`DingtalkResult`（Task 3）；`HttpFileDownload` 与扩展 `download(url, maxBytes)`（`channel/Transports.kt`）。
- Produces（Task 6 使用）：`class DingtalkAttachment(ref: DingtalkMediaRef, api: DingtalkApi, download: HttpFileDownload) : InboundAttachment`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

class DingtalkAttachmentTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Volatile
    private var downloadAnswer: Pair<Int, String> = 200 to """{"downloadUrl":"https://oss/x?Expires=1"}"""

    private val http =
        DingtalkFakeHttp { url, _ ->
            when {
                url.endsWith("/v1.0/oauth2/accessToken") -> 200 to """{"accessToken":"T","expireIn":7200}"""
                url.endsWith("/v1.0/robot/messageFiles/download") -> downloadAnswer
                else -> 404 to "{}"
            }
        }
    private val api = DingtalkApi("cid", "sec", http)
    private val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()

    private fun downloader(result: () -> DownloadResult) =
        HttpFileDownload { url, _, headers ->
            requests += url to headers
            result()
        }

    @Test
    fun `先换地址再下载，文件名用消息里的`() =
        runBlocking {
            val file = temp.newFile().toPath()
            val attachment =
                DingtalkAttachment(
                    DingtalkMediaRef(AttachmentKind.FILE, "d1", "报告.pdf"),
                    api,
                    downloader { DownloadResult.Ok(file, "x.file") },
                )
            assertEquals(AttachmentKind.FILE, attachment.kind)
            assertEquals(FetchResult.Ok(FetchedFile(file, "报告.pdf")), attachment.fetch(100))
            assertEquals(listOf("https://oss/x?Expires=1" to emptyMap<String, String>()), requests.toList())
        }

    @Test
    fun `消息里没有文件名时不用下载地址给的名字`() =
        runBlocking {
            val file = temp.newFile().toPath()
            val attachment = DingtalkAttachment(DingtalkMediaRef(AttachmentKind.IMAGE, "d1", null), api, downloader { DownloadResult.Ok(file, "x.file") })
            assertEquals(FetchResult.Ok(FetchedFile(file, null)), attachment.fetch(100))
        }

    @Test
    fun `换地址失败、超限、下载失败`() =
        runBlocking {
            downloadAnswer = 400 to """{"code":"invalidParameter.robotCode.downloadCode","message":"expired"}"""
            val failed = DingtalkAttachment(DingtalkMediaRef(AttachmentKind.IMAGE, "d1", null), api, downloader { DownloadResult.TooLarge })
            assertEquals(FetchResult.Failed("invalidParameter.robotCode.downloadCode"), failed.fetch(100))
            assertEquals(0, requests.size)
            downloadAnswer = 200 to """{"downloadUrl":"https://oss/x"}"""
            assertEquals(FetchResult.TooLarge, failed.fetch(100))
            val broken = DingtalkAttachment(DingtalkMediaRef(AttachmentKind.IMAGE, "d1", null), api, downloader { DownloadResult.Failed("HTTP 403") })
            assertEquals(FetchResult.Failed("HTTP 403"), broken.fetch(100))
        }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.dingtalk.DingtalkAttachmentTest'`
Expected: 编译失败，`DingtalkAttachment` 未定义。

- [ ] **Step 3: 实现**

```kotlin
package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.channel.download
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import com.github.izerui.imux.bridge.core.InboundAttachment

/**
 * 钉钉消息里的一个附件：[fetch] 时才用下载码换临时地址再下载（地址是 OSS 签名地址，不带令牌）。
 * 下载地址给出的文件名可能是 `.file`，不采用；消息里没有文件名时交给附件落盘按文件头命名。
 */
class DingtalkAttachment(
    private val ref: DingtalkMediaRef,
    private val api: DingtalkApi,
    private val download: HttpFileDownload,
) : InboundAttachment {
    override val kind: AttachmentKind
        get() = ref.kind

    override suspend fun fetch(maxBytes: Long): FetchResult {
        val url =
            when (val result = api.downloadUrl(ref.downloadCode)) {
                is DingtalkResult.Ok -> result.value
                is DingtalkResult.Error -> return FetchResult.Failed(result.code)
                is DingtalkResult.Network -> return FetchResult.Failed(result.reason)
            }
        return when (val result = download.download(url, maxBytes)) {
            DownloadResult.TooLarge -> FetchResult.TooLarge
            is DownloadResult.Ok -> FetchResult.Ok(FetchedFile(result.path, ref.fileName))
            is DownloadResult.Failed -> FetchResult.Failed(result.reason)
        }
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.dingtalk.DingtalkAttachmentTest'`
Expected: PASS。

- [ ] **Step 5: 不提交。**

---

### Task 6: DingtalkConnection（连接生命周期、收发、状态）

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/dingtalk/DingtalkConnection.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/dingtalk/DingtalkConnectionTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `sendPing`/`onPong`；Task 2 的 `DingtalkEvents`、`DingtalkInbound`；Task 3 的 `DingtalkApi`、`DingtalkResult`、`DingtalkCodes`；Task 5 的 `DingtalkAttachment`。
- Produces（Task 7 使用）：

```kotlin
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
) : ChannelConnection
```

状态规则（设计第 5～7 节）：
- 长连接状态（Connecting / Online / Retrying / Failed / Stopped）由运行循环和 `stop()` 写入，每次变化都清掉收发受阻标记。
- 长连接在线时显示 `sendBlocked ?: receiveBlocked ?: Online`，发类优先；只在显示内容变化时上报。长连接不在线时，收发受阻都不改显示。
- 状态在 `statusLock` 内入队，由每次运行自己的通知协程在锁外按序交给监听器（调用方常在自己的锁里调 `stop()`，持锁回调会死锁）。
- 连续 5 次失败后上报 `Retrying(原因)`，之前报 `Connecting`；凭据被拒连续 5 次上报 `Failed`；`disconnect` 立即重建、不算失败；退避 2、4、8…秒，最多 60 秒。

- [ ] **Step 1: 写失败测试**

```kotlin
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DingtalkConnectionTest {
    private class FakeTransport(
        private val opens: AtomicInteger,
        private val autoPong: Boolean,
    ) : FrameTransport {
        val sent = CopyOnWriteArrayList<String>()
        val pings = AtomicInteger()

        @Volatile
        var url: String? = null

        @Volatile
        var listener: FrameListener? = null

        override suspend fun open(
            url: String,
            listener: FrameListener,
        ) {
            opens.incrementAndGet()
            this.url = url
            this.listener = listener
        }

        override suspend fun send(text: String) {
            sent += text
        }

        override suspend fun sendPing() {
            pings.incrementAndGet()
            if (autoPong) listener?.onPong()
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

    @Volatile
    private var openAnswer: Pair<Int, String> = 200 to """{"endpoint":"wss://x/connect","ticket":"t1"}"""

    @Volatile
    private var sendAnswer: Pair<Int, String> = 200 to """{"processQueryKey":"q","invalidStaffIdList":[],"flowControlledStaffIdList":[]}"""

    private val http =
        DingtalkFakeHttp { url, _ ->
            when {
                url.endsWith("/v1.0/gateway/connections/open") -> openAnswer
                url.endsWith("/v1.0/oauth2/accessToken") -> 200 to """{"accessToken":"T","expireIn":7200}"""
                url.endsWith("/v1.0/robot/oToMessages/batchSend") -> sendAnswer
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
    ) = DingtalkConnection(
        robotId = RobotId("r"),
        api = DingtalkApi("cid", "sec", http),
        scope = scope,
        transportFactory = { FakeTransport(opens, autoPong).also { transports += it } },
        download = HttpFileDownload { _, _, _ -> DownloadResult.Failed("unused") },
        pingIntervalMillis = pingIntervalMillis,
        deadAfterMillis = deadAfterMillis,
        backoffBaseMillis = 1,
        backoffMaxMillis = 5,
        limiter = limiter,
    )

    private fun start(conn: DingtalkConnection) = conn.start(InboundSink { received += it }) { statuses += it }

    private fun startAndAwaitOnline(conn: DingtalkConnection) =
        runBlocking {
            start(conn)
            withTimeout(2_000) { while (ConnectionStatus.Online !in statuses) delay(5) }
        }

    private suspend fun awaitLast(status: ConnectionStatus) = withTimeout(2_000) { while (statuses.lastOrNull() != status) delay(5) }

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
            startAndAwaitOnline(connection(autoPong = false, pingIntervalMillis = 20, deadAfterMillis = 60))
            withTimeout(2_000) { while (opens.get() < 2) delay(5) }
            assertTrue(transports.first().pings.get() >= 1)
        }

    @Test
    fun `收到 pong 就算活着`() =
        runBlocking {
            startAndAwaitOnline(connection(autoPong = true, pingIntervalMillis = 20, deadAfterMillis = 60))
            delay(300)
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
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.dingtalk.DingtalkConnectionTest'`
Expected: 编译失败，`DingtalkConnection` 未定义。

- [ ] **Step 3: 实现**

```kotlin
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

        /** 凭据被拒；连续多次才认定失败，避免一次误判就停掉。 */
        data class AuthFailed(val reason: String) : SessionEnd

        data class Dropped(val reason: String) : SessionEnd
    }

    private class SessionState(
        val transport: FrameTransport,
        startedAt: Long,
    ) {
        @Volatile
        var lastFrameAt: Long = startedAt

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

    /** 发送受阻（额度、权限、凭据）：下一次发送成功清除。 */
    private var sendBlocked: ConnectionStatus.SendFailing? = null

    /** 接收受阻（收消息额度用完、没有 staffId）：下一条正常消息清除。 */
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
     * 上报长连接状态并清掉收发受阻标记。与 stop() 同锁：stop() 先取消再在锁内报“已停止”，
     * 之后被取消的运行在锁内看到自己已不活跃，不会再把状态改回去。
     */
    private fun reportLink(
        current: Run,
        status: ConnectionStatus,
    ) = synchronized(statusLock) {
        if (!current.job.isActive) return@synchronized
        linkStatus = status
        sendBlocked = null
        receiveBlocked = null
        shown = status
        current.statuses.trySend(status)
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
     * 首次立即连接；失败后按 2、4、8…秒退避，最多 60 秒。连续失败 [REPORT_RETRYING_AFTER] 次后如实报“正在重试”；
     * 凭据连续被拒 [MAX_AUTH_FAILURES] 次报失败并停止；disconnect 立即重建，不算失败。
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
            var lastFailure: String? = null
            var immediate = true
            while (isActive) {
                if (!immediate) delay(backoff(failures))
                immediate = false
                val failure = lastFailure
                reportLink(
                    owner,
                    if (failures >= REPORT_RETRYING_AFTER && failure != null) ConnectionStatus.Retrying(failure) else ConnectionStatus.Connecting,
                )
                when (
                    val end =
                        runSession(owner, inbound) {
                            failures = 0
                            authFailures = 0
                            lastFailure = null
                        }
                ) {
                    SessionEnd.Disconnect -> immediate = true
                    is SessionEnd.AuthFailed -> {
                        failures++
                        authFailures++
                        lastFailure = end.reason
                        if (authFailures >= MAX_AUTH_FAILURES) {
                            reportLink(owner, ConnectionStatus.Failed(end.reason))
                            break
                        }
                    }

                    is SessionEnd.Dropped -> {
                        failures++
                        authFailures = 0
                        lastFailure = end.reason
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
        onOnline: () -> Unit,
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
        val state = SessionState(transport, now())
        val ended = CompletableDeferred<SessionEnd>()
        val frameListener =
            object : FrameListener {
                override fun onText(text: String) {
                    state.lastFrameAt = now()
                    onFrame(state, text, inbound, ended)
                }

                override fun onPong() {
                    state.lastFrameAt = now()
                }

                override fun onClosed(reason: String) {
                    ended.complete(SessionEnd.Dropped(reason))
                }
            }
        try {
            transport.open(endpoint.url(), frameListener)
            // 已被 stop() 取消的旧运行不能覆盖新运行的会话
            currentCoroutineContext().ensureActive()
            session = state
            onOnline()
            reportLink(owner, ConnectionStatus.Online)
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
            if (session === state) session = null
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
                state.transport.sendPing()
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
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.dingtalk.*'`
Expected: 全部 PASS。若某条时序测试偶发失败，按 Global Constraints 修实现而不是放宽断言；放宽前在报告里说明原因。

- [ ] **Step 5: 不提交。**

---

### Task 7: 工厂、登记、文案、隔离规则

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/dingtalk/DingtalkChannelFactory.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/channel/ChannelFactories.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/channel/ChannelSpi.kt`（`SendFailing` 的 KDoc）
- Modify: `src/main/resources/messages/ImuxBundle*.properties`（10 个文件）
- Modify: `src/test/kotlin/com/github/izerui/imux/bridge/channel/ChannelFactoriesTest.kt`
- Modify: `src/test/kotlin/com/github/izerui/imux/bridge/channel/ChannelIsolationSourceTest.kt`

**Interfaces:**
- Consumes: `DingtalkOnboarding`、`DingtalkCredentialKeys`（Task 4）；`DingtalkApi`（Task 3）；`DingtalkConnection`（Task 6）。
- Produces: `object DingtalkChannelFactory : ChannelFactory`；`ChannelFactories.all()` 返回 `listOf(WecomChannelFactory, FeishuChannelFactory, DingtalkChannelFactory)`。

- [ ] **Step 1: 写失败测试**

在 `ChannelFactoriesTest` 末尾加两个测试（补 import `com.github.izerui.imux.bridge.channel.dingtalk.DingtalkChannelFactory`、`...dingtalk.DingtalkConnection`、`...dingtalk.DingtalkOnboarding`）：

```kotlin
    @Test
    fun `钉钉已登记，扫码说明用自己的文案`() {
        assertEquals(DingtalkChannelFactory, ChannelFactories.forKind(ChannelKind.DINGTALK))
        assertEquals("bridge.add.scan.steps.dingtalk", DingtalkChannelFactory.scanStepsKey)
        assertTrue(DingtalkChannelFactory.onboarding() is DingtalkOnboarding)
    }

    @Test
    fun `按凭据建立钉钉连接`() {
        val connection =
            DingtalkChannelFactory.connect(
                RobotId("r"),
                mapOf("client_id" to "cid", "client_secret" to "s"),
                CoroutineScope(Dispatchers.Default),
            )
        assertTrue(connection is DingtalkConnection)
        assertEquals(RobotId("r"), connection.robotId)
        assertEquals(6_000, connection.maxTextBytes)
    }
```

在 `ChannelIsolationSourceTest` 末尾加：

```kotlin
    @Test
    fun `渠道子包之间互不引用`() {
        val root = File("src/main/kotlin/com/github/izerui/imux/bridge/channel")
        val channels = root.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }
        assertTrue("没有找到渠道子包：$channels", channels.containsAll(listOf("wecom", "feishu", "dingtalk")))
        channels.forEach { own ->
            File(root, own).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val code = SourceCode(file.path).normalized
                (channels - own).forEach { other ->
                    assertTrue("${file.name} 引用了渠道 $other", !code.contains("com.github.izerui.imux.bridge.channel.$other."))
                }
            }
        }
    }
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.ChannelFactoriesTest' --tests 'com.github.izerui.imux.bridge.channel.ChannelIsolationSourceTest'`
Expected: 编译失败，`DingtalkChannelFactory` 未定义。

- [ ] **Step 3: 工厂与登记**

`DingtalkChannelFactory.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ChannelFactory
import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CoroutineScope

object DingtalkChannelFactory : ChannelFactory {
    override val kind: ChannelKind = ChannelKind.DINGTALK

    /** 扫码后要配对码认主，且可能要求组织管理员，说明文字与企业微信不同。 */
    override val scanStepsKey: String = "bridge.add.scan.steps.dingtalk"

    override fun onboarding(): ChannelOnboarding = DingtalkOnboarding()

    override fun connect(
        robotId: RobotId,
        credentials: Map<String, String>,
        scope: CoroutineScope,
    ): ChannelConnection =
        DingtalkConnection(
            robotId = robotId,
            api =
                DingtalkApi(
                    clientId = credentials.getValue(DingtalkCredentialKeys.CLIENT_ID),
                    clientSecret = credentials.getValue(DingtalkCredentialKeys.CLIENT_SECRET),
                ),
            scope = scope,
        )
}
```

`ChannelFactories.kt`：加 import `com.github.izerui.imux.bridge.channel.dingtalk.DingtalkChannelFactory`，并改为

```kotlin
    fun all(): List<ChannelFactory> = listOf(WecomChannelFactory, FeishuChannelFactory, DingtalkChannelFactory)
```

`ChannelSpi.kt` 中 `SendFailing` 的 KDoc 改为：

```kotlin
    /**
     * 连接在，但收发受阻（额度用完、缺权限等）：飞书、钉钉的发送被平台拒绝，或钉钉收消息额度用完。
     * [messageKey] 是 ImuxBundle 的键，[detail] 填进它的 {0}。受阻原因消除后回到 Online。
     */
```

- [ ] **Step 4: 文案（10 种语言）**

在每个 bundle 文件里，把 `bridge.add.scan.steps.dingtalk` 插在 `bridge.add.scan.steps.feishu=` 那一行之后，其余 6 个键插在 `bridge.feishu.app=` 那一行之后（顺序如下）。用下面的脚本一次写入（在仓库根目录运行，文件为 UTF-8）：

```bash
python3 - <<'EOF'
import pathlib
texts = {
 "": {
  "bridge.add.scan.steps.dingtalk": r"1. Open {0} on your phone and scan\n2. Create or choose a bot as prompted (an organization admin may be required)\n3. Come back here to pair",
  "bridge.dingtalk.onboardingFailed": "DingTalk authorization failed. Please try again.",
  "bridge.dingtalk.quota": "This month's DingTalk API quota is used up; messages from your phone still reach the terminal, but replies cannot be sent.",
  "bridge.dingtalk.receiveQuota": "This month's DingTalk message quota is used up; messages from your phone can no longer be received.",
  "bridge.dingtalk.permission": "The DingTalk bot lacks permission to send messages or is not enabled ({0})",
  "bridge.dingtalk.app": "The DingTalk app is unavailable ({0}). Remove it and scan again.",
  "bridge.dingtalk.noStaffId": "DingTalk did not provide the sender's userId; the bot may not be published, so replies are not possible.",
 },
 "_zh_CN": {
  "bridge.add.scan.steps.dingtalk": r"1. 打开手机{0}，扫一扫\n2. 按提示创建或选择机器人（可能需要组织管理员）\n3. 回到这里继续配对",
  "bridge.dingtalk.onboardingFailed": "钉钉扫码授权失败，请重试。",
  "bridge.dingtalk.quota": "本月钉钉 API 调用额度已用完；手机消息仍会转交给终端，但回复发不出去。",
  "bridge.dingtalk.receiveQuota": "本月钉钉消息接收额度已用完，手机发来的消息收不到。",
  "bridge.dingtalk.permission": "钉钉机器人缺少发消息权限或未启用（{0}）",
  "bridge.dingtalk.app": "钉钉应用不可用（{0}），请移除后重新扫码。",
  "bridge.dingtalk.noStaffId": "钉钉没有给出发送人 userId，机器人可能未发布，无法回复。",
 },
 "_zh_TW": {
  "bridge.add.scan.steps.dingtalk": r"1. 打開手機{0}，掃一掃\n2. 依提示建立或選擇機器人（可能需要組織管理員）\n3. 回到這裡繼續配對",
  "bridge.dingtalk.onboardingFailed": "釘釘掃碼授權失敗，請重試。",
  "bridge.dingtalk.quota": "本月釘釘 API 呼叫額度已用完；手機訊息仍會轉交給終端，但回覆發不出去。",
  "bridge.dingtalk.receiveQuota": "本月釘釘訊息接收額度已用完，手機傳來的訊息收不到。",
  "bridge.dingtalk.permission": "釘釘機器人缺少發訊息權限或未啟用（{0}）",
  "bridge.dingtalk.app": "釘釘應用程式無法使用（{0}），請移除後重新掃碼。",
  "bridge.dingtalk.noStaffId": "釘釘沒有提供傳送者 userId，機器人可能未發布，無法回覆。",
 },
 "_ja": {
  "bridge.add.scan.steps.dingtalk": r"1. スマートフォンの{0}でスキャン\n2. 案内に従ってボットを作成または選択（組織の管理者が必要な場合があります）\n3. ここに戻ってペアリングを続行",
  "bridge.dingtalk.onboardingFailed": "DingTalk の認可に失敗しました。もう一度お試しください。",
  "bridge.dingtalk.quota": "今月の DingTalk API 呼び出し枠を使い切りました。スマートフォンのメッセージはターミナルに届きますが、返信は送信できません。",
  "bridge.dingtalk.receiveQuota": "今月の DingTalk メッセージ受信枠を使い切りました。スマートフォンからのメッセージを受信できません。",
  "bridge.dingtalk.permission": "DingTalk ボットにメッセージ送信の権限がないか、有効になっていません（{0}）",
  "bridge.dingtalk.app": "DingTalk アプリを利用できません（{0}）。削除して再度スキャンしてください。",
  "bridge.dingtalk.noStaffId": "DingTalk が送信者の userId を返しませんでした。ボットが公開されていない可能性があり、返信できません。",
 },
 "_ko": {
  "bridge.add.scan.steps.dingtalk": r"1. 휴대폰에서 {0}을(를) 열고 스캔\n2. 안내에 따라 봇을 만들거나 선택 (조직 관리자가 필요할 수 있음)\n3. 여기로 돌아와 페어링 계속",
  "bridge.dingtalk.onboardingFailed": "DingTalk 인증에 실패했습니다. 다시 시도하세요.",
  "bridge.dingtalk.quota": "이번 달 DingTalk API 호출 한도를 모두 사용했습니다. 휴대폰 메시지는 터미널로 전달되지만 답장은 보낼 수 없습니다.",
  "bridge.dingtalk.receiveQuota": "이번 달 DingTalk 메시지 수신 한도를 모두 사용했습니다. 휴대폰에서 보낸 메시지를 받을 수 없습니다.",
  "bridge.dingtalk.permission": "DingTalk 봇에 메시지 전송 권한이 없거나 활성화되지 않았습니다 ({0})",
  "bridge.dingtalk.app": "DingTalk 앱을 사용할 수 없습니다 ({0}). 제거한 뒤 다시 스캔하세요.",
  "bridge.dingtalk.noStaffId": "DingTalk가 보낸 사람의 userId를 제공하지 않았습니다. 봇이 게시되지 않았을 수 있어 답장할 수 없습니다.",
 },
 "_de": {
  "bridge.add.scan.steps.dingtalk": r"1. {0} auf dem Smartphone öffnen und scannen\n2. Wie angezeigt einen Bot erstellen oder auswählen (ggf. ist ein Organisations-Admin nötig)\n3. Hierher zurückkehren und koppeln",
  "bridge.dingtalk.onboardingFailed": "DingTalk-Autorisierung fehlgeschlagen. Bitte erneut versuchen.",
  "bridge.dingtalk.quota": "Das DingTalk-API-Kontingent für diesen Monat ist aufgebraucht; Nachrichten vom Smartphone erreichen das Terminal weiterhin, Antworten können aber nicht gesendet werden.",
  "bridge.dingtalk.receiveQuota": "Das DingTalk-Nachrichtenkontingent für diesen Monat ist aufgebraucht; Nachrichten vom Smartphone können nicht mehr empfangen werden.",
  "bridge.dingtalk.permission": "Dem DingTalk-Bot fehlt die Berechtigung zum Senden, oder er ist nicht aktiviert ({0})",
  "bridge.dingtalk.app": "Die DingTalk-App ist nicht verfügbar ({0}). Entfernen und erneut scannen.",
  "bridge.dingtalk.noStaffId": "DingTalk hat die userId des Absenders nicht geliefert; der Bot ist möglicherweise nicht veröffentlicht, Antworten sind nicht möglich.",
 },
 "_es": {
  "bridge.add.scan.steps.dingtalk": r"1. Abre {0} en el móvil y escanea\n2. Crea o elige un bot según las indicaciones (puede requerir un administrador de la organización)\n3. Vuelve aquí para emparejar",
  "bridge.dingtalk.onboardingFailed": "Falló la autorización de DingTalk. Inténtalo de nuevo.",
  "bridge.dingtalk.quota": "Se agotó la cuota mensual de la API de DingTalk; los mensajes del móvil siguen llegando a la terminal, pero no se pueden enviar respuestas.",
  "bridge.dingtalk.receiveQuota": "Se agotó la cuota mensual de mensajes de DingTalk; ya no se pueden recibir mensajes del móvil.",
  "bridge.dingtalk.permission": "El bot de DingTalk no tiene permiso para enviar mensajes o no está habilitado ({0})",
  "bridge.dingtalk.app": "La app de DingTalk no está disponible ({0}). Elimínala y vuelve a escanear.",
  "bridge.dingtalk.noStaffId": "DingTalk no proporcionó el userId del remitente; puede que el bot no esté publicado, así que no es posible responder.",
 },
 "_fr": {
  "bridge.add.scan.steps.dingtalk": r"1. Ouvrez {0} sur votre téléphone et scannez\n2. Créez ou choisissez un bot selon les indications (un administrateur de l’organisation peut être requis)\n3. Revenez ici pour l’appairage",
  "bridge.dingtalk.onboardingFailed": "L’autorisation DingTalk a échoué. Veuillez réessayer.",
  "bridge.dingtalk.quota": "Le quota mensuel de l’API DingTalk est épuisé ; les messages du téléphone parviennent toujours au terminal, mais les réponses ne peuvent pas être envoyées.",
  "bridge.dingtalk.receiveQuota": "Le quota mensuel de messages DingTalk est épuisé ; les messages du téléphone ne peuvent plus être reçus.",
  "bridge.dingtalk.permission": "Le bot DingTalk n’a pas l’autorisation d’envoyer des messages ou n’est pas activé ({0})",
  "bridge.dingtalk.app": "L’application DingTalk est indisponible ({0}). Supprimez-la et scannez à nouveau.",
  "bridge.dingtalk.noStaffId": "DingTalk n’a pas fourni l’userId de l’expéditeur ; le bot n’est peut-être pas publié, il est donc impossible de répondre.",
 },
 "_pt_BR": {
  "bridge.add.scan.steps.dingtalk": r"1. Abra o {0} no celular e escaneie\n2. Crie ou escolha um bot conforme as instruções (pode exigir um administrador da organização)\n3. Volte aqui para parear",
  "bridge.dingtalk.onboardingFailed": "Falha na autorização do DingTalk. Tente novamente.",
  "bridge.dingtalk.quota": "A cota mensal da API do DingTalk se esgotou; as mensagens do celular ainda chegam ao terminal, mas as respostas não podem ser enviadas.",
  "bridge.dingtalk.receiveQuota": "A cota mensal de mensagens do DingTalk se esgotou; as mensagens do celular não podem mais ser recebidas.",
  "bridge.dingtalk.permission": "O bot do DingTalk não tem permissão para enviar mensagens ou não está ativado ({0})",
  "bridge.dingtalk.app": "O app do DingTalk está indisponível ({0}). Remova-o e escaneie novamente.",
  "bridge.dingtalk.noStaffId": "O DingTalk não informou o userId do remetente; o bot pode não estar publicado, então não é possível responder.",
 },
 "_ru": {
  "bridge.add.scan.steps.dingtalk": r"1. Откройте {0} на телефоне и отсканируйте\n2. Создайте или выберите бота по подсказкам (может потребоваться администратор организации)\n3. Вернитесь сюда для привязки",
  "bridge.dingtalk.onboardingFailed": "Не удалось авторизоваться в DingTalk. Повторите попытку.",
  "bridge.dingtalk.quota": "Месячная квота API DingTalk исчерпана; сообщения с телефона по-прежнему доходят до терминала, но ответы отправить нельзя.",
  "bridge.dingtalk.receiveQuota": "Месячная квота сообщений DingTalk исчерпана; сообщения с телефона больше не принимаются.",
  "bridge.dingtalk.permission": "У бота DingTalk нет права отправлять сообщения, или он не включён ({0})",
  "bridge.dingtalk.app": "Приложение DingTalk недоступно ({0}). Удалите его и отсканируйте снова.",
  "bridge.dingtalk.noStaffId": "DingTalk не передал userId отправителя; возможно, бот не опубликован, поэтому ответить нельзя.",
 },
}
order = ["bridge.dingtalk.onboardingFailed", "bridge.dingtalk.quota", "bridge.dingtalk.receiveQuota",
         "bridge.dingtalk.permission", "bridge.dingtalk.app", "bridge.dingtalk.noStaffId"]
for suffix, values in texts.items():
    path = pathlib.Path(f"src/main/resources/messages/ImuxBundle{suffix}.properties")
    lines = path.read_text(encoding="utf-8").split("\n")
    assert not any(l.startswith("bridge.dingtalk.") or l.startswith("bridge.add.scan.steps.dingtalk=") for l in lines), path
    out = []
    for line in lines:
        out.append(line)
        if line.startswith("bridge.add.scan.steps.feishu="):
            out.append("bridge.add.scan.steps.dingtalk=" + values["bridge.add.scan.steps.dingtalk"])
        if line.startswith("bridge.feishu.app="):
            out.extend(f"{k}={values[k]}" for k in order)
    assert len(out) == len(lines) + 7, path
    path.write_text("\n".join(out), encoding="utf-8")
EOF
```

- [ ] **Step 5: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.*' --tests 'com.github.izerui.imux.ImuxBundleTest'`
Expected: 全部 PASS（含新的“渠道子包之间互不引用”、文案各语言键一致）。

- [ ] **Step 6: 全量验证**

Run: `./gradlew test`
Expected: 全部 PASS。

再对本计划所有新建、修改的 Kotlin 文件跑 IDE `lint_files`（无新增错误），然后：

Run: `./gradlew verifyPlugin`
Expected: 各目标 IDE 均为 Compatible。

- [ ] **Step 7: 不提交。**
