# 飞书渠道接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在手机桥接中新增飞书渠道：扫码创建应用即认主，长连接收消息、HTTP 发 Markdown，与企业微信并列。

**Architecture:** 新建 `bridge/channel/feishu/`，结构与 `channel/wecom/` 一一对应（工厂、扫码、帧编解码、事件解析、HTTP API、连接、附件）。公共层只做小扩展（二进制帧、握手失败信息、带头 HTTP JSON 客户端、下载带头、`SendFailing` 状态、`AUDIO` 附件）。核心层（路由、绑定、会话记录）不改。

**Tech Stack:** Kotlin、kotlinx.coroutines、Gson、JDK `java.net.http`（HttpClient / WebSocket）、JUnit4、Concentus（纯 Java Opus 解码，仅用于语音转文字）。不引入飞书 SDK、不引入 protobuf 库。

**设计依据:** `docs/superpowers/specs/2026-10-28-feishu-channel-design.md`

## Global Constraints

- 只新增一个依赖 `io.github.jaredmdobson:concentus:1.0.2`（用户已同意，用于把飞书 Opus 语音解成 pcm）；除此之外不引入新依赖，protobuf 帧手写编解码。
- `bridge/channel/**` 不得引用 `AgentType`、`bridge.terminal`、`bridge.transcript`、`TerminalHost`、`SessionMonitor`（`ChannelIsolationSourceTest` 自动检查）。
- 企业微信行为不变；企业微信现有测试必须全部继续通过。
- 渠道层不打印 `app_secret`、令牌、消息正文。
- 不自动重发失败的消息；断开不接收、重连不补历史（AGENTS.md）。
- 新文案必须同时加入 10 个语言文件（`ImuxBundleTest` 检查键一致与占位符一致）。
- 直接在 `main` 上修改；**不提交**，由用户自己提交。计划中没有 commit 步骤。
- 代码风格与周围一致：中文 KDoc / 注释、测试方法名用中文反引号、import 顺序为“其他包 → java → javax → kotlin”。
- 单个测试类运行：`./gradlew test --tests '<全限定类名>' -q`；全量：`./gradlew test -q`。

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/Transports.kt` | 改 | 二进制帧、握手失败、`HttpJson`、下载带头 |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/BinaryFrameAssembler.kt` | 新 | 二进制分片拼接 |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/ChannelSpi.kt` | 改 | `SendFailing`、`isWorking`、轮询间隔说明、`scanStepsKey` |
| `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeModels.kt` | 改 | `AttachmentKind.AUDIO` |
| `src/main/kotlin/com/github/izerui/imux/bridge/core/AttachmentNames.kt` | 改 | 识别 `OggS` |
| `src/main/kotlin/com/github/izerui/imux/bridge/ui/RobotRows.kt` | 改 | `SendFailing` 文字 |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuDomain.kt` | 新 | 域名、凭据键 |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuFrameCodec.kt` | 新 | `pbbp2` 帧 |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuEvents.kt` | 新 | 事件解析 |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuApi.kt` | 新 | HTTP：长连接地址、令牌、发送、取消息、错误码 |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuOnboarding.kt` | 新 | 扫码 |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuAttachment.kt` | 新 | 附件下载 |
| `build.gradle.kts` | 改 | 新增 Concentus 依赖 |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/OggOpus.kt` | 新 | Ogg Opus → 16k pcm |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuSpeech.kt` | 新 | 语音转文字 |
| `src/test/resources/feishu/*.opus` | 新 | ffmpeg 生成的测试样本 |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuConnection.kt` | 新 | 连接生命周期 |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuChannelFactory.kt` | 新 | 工厂 |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/ChannelFactories.kt` | 改 | 登记飞书 |
| `src/main/kotlin/com/github/izerui/imux/bridge/ui/AddRobotDialog.kt` | 改 | 扫码说明按渠道取 |
| `src/main/resources/messages/ImuxBundle*.properties`（10 个） | 改 | 新文案 |
| `docs/channel-protocols.md`、`docs/channel-limits.md` | 改 | 补充核实结果 |

---

### Task 1: 传输层扩展（二进制帧、握手失败、HTTP JSON 客户端、下载带头）

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/BinaryFrameAssembler.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/channel/Transports.kt`
- Modify: `src/test/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomMediaTest.kt:64-73,115-122`
- Modify: `src/test/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomConnectionTest.kt:91`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/BinaryFrameAssemblerTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/HttpJsonTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/HttpFileDownloadTest.kt`（追加一个用例）

**Interfaces:**
- Produces:
  - `FrameListener.onBinary(bytes: ByteArray)`（默认空实现）
  - `FrameTransport.sendBinary(bytes: ByteArray)`（suspend，默认抛 `UnsupportedOperationException`）
  - `class WebSocketHandshakeRejected(val status: Int, val headers: Map<String, String>) : IOException`（`headers` 键为小写）
  - `data class HttpJsonResponse(val status: Int, val json: JsonObject?)`
  - `interface HttpJson { suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpJsonResponse; suspend fun post(url: String, contentType: String, body: String, headers: Map<String, String> = emptyMap()): HttpJsonResponse }`
  - `class JdkHttpJson(client: HttpClient = …) : HttpJson`
  - `HttpFileDownload.download(url: String, maxBytes: Long, headers: Map<String, String> = emptyMap()): DownloadResult`
  - `class BinaryFrameAssembler(maxBytes: Int = 4 * 1024 * 1024) { fun accept(part: ByteBuffer, last: Boolean): ByteArray? }`

- [ ] **Step 1: 写二进制拼接的失败测试**

`src/test/kotlin/com/github/izerui/imux/bridge/channel/BinaryFrameAssemblerTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class BinaryFrameAssemblerTest {
    @Test
    fun `分片拼接到 last 才输出`() {
        val assembler = BinaryFrameAssembler()
        assertNull(assembler.accept(ByteBuffer.wrap(byteArrayOf(1, 2)), false))
        assertArrayEquals(byteArrayOf(1, 2, 3), assembler.accept(ByteBuffer.wrap(byteArrayOf(3)), true))
        assertArrayEquals(byteArrayOf(9), assembler.accept(ByteBuffer.wrap(byteArrayOf(9)), true))
    }

    @Test
    fun `超长消息整条丢弃，下一条正常`() {
        val assembler = BinaryFrameAssembler(maxBytes = 4)
        assertNull(assembler.accept(ByteBuffer.wrap(ByteArray(5)), false))
        assertNull(assembler.accept(ByteBuffer.wrap(ByteArray(1)), true))
        assertArrayEquals(byteArrayOf(7), assembler.accept(ByteBuffer.wrap(byteArrayOf(7)), true))
    }
}
```

- [ ] **Step 2: 运行，确认编译失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.BinaryFrameAssemblerTest' -q`
Expected: 编译失败，`Unresolved reference: BinaryFrameAssembler`

- [ ] **Step 3: 实现 `BinaryFrameAssembler`**

`src/main/kotlin/com/github/izerui/imux/bridge/channel/BinaryFrameAssembler.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * java.net.http.WebSocket 的 onBinary 可能把一条消息分成多段，拼到 last=true 才是完整的帧。
 * 超过 [maxBytes] 的消息整条丢弃，避免异常大帧占满内存（与 [TextFrameAssembler] 同一规则）。
 */
class BinaryFrameAssembler(
    private val maxBytes: Int = 4 * 1024 * 1024,
) {
    private val buffer = ByteArrayOutputStream()
    private var overflowed = false

    fun accept(
        part: ByteBuffer,
        last: Boolean,
    ): ByteArray? {
        if (!overflowed) {
            if (buffer.size() + part.remaining() > maxBytes) {
                overflowed = true
                buffer.reset()
            } else {
                val bytes = ByteArray(part.remaining())
                part.get(bytes)
                buffer.write(bytes)
            }
        }
        if (!last) return null
        val complete = if (overflowed) null else buffer.toByteArray()
        buffer.reset()
        overflowed = false
        return complete
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.BinaryFrameAssemblerTest' -q`
Expected: PASS

- [ ] **Step 5: 写 `HttpJson` 与 WebSocket 握手失败的失败测试**

`src/test/kotlin/com/github/izerui/imux/bridge/channel/HttpJsonTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress

class HttpJsonTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    private val base = "http://127.0.0.1:${server.address.port}"

    @After
    fun tearDown() = server.stop(0)

    private fun respond(
        path: String,
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
        onRequest: (method: String, contentType: String?, authorization: String?, body: String) -> Unit = { _, _, _, _ -> },
    ) {
        server.createContext(path) { exchange ->
            onRequest(
                exchange.requestMethod,
                exchange.requestHeaders.getFirst("Content-Type"),
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

    @Test
    fun `POST 带内容类型、请求头与正文，返回状态和 JSON`() =
        runBlocking {
            var seen = listOf<String?>()
            respond("/p", 200, """{"code":0}""") { method, type, auth, body -> seen = listOf(method, type, auth, body) }
            val response = JdkHttpJson().post("$base/p", "application/json; charset=utf-8", """{"a":"中"}""", mapOf("Authorization" to "Bearer T"))
            assertEquals(200, response.status)
            assertEquals(0, response.json!!["code"].asInt)
            assertEquals(listOf("POST", "application/json; charset=utf-8", "Bearer T", """{"a":"中"}"""), seen)
        }

    @Test
    fun `4xx 正文是 JSON 时照常返回，不抛异常`() =
        runBlocking {
            respond("/bad", 400, """{"error":"authorization_pending"}""")
            val response = JdkHttpJson().get("$base/bad")
            assertEquals(400, response.status)
            assertEquals("authorization_pending", response.json!!["error"].asString)
        }

    @Test
    fun `正文不是 JSON 对象时 json 为 null`() =
        runBlocking {
            respond("/html", 502, "<html>")
            val response = JdkHttpJson().get("$base/html")
            assertEquals(502, response.status)
            assertNull(response.json)
        }

    @Test
    fun `WebSocket 握手被拒时带出状态码与响应头`() =
        runBlocking {
            respond("/ws", 403, "", headers = mapOf("Handshake-Status" to "403", "Handshake-Msg" to "forbidden"))
            try {
                JdkWebSocketTransport().open("ws://127.0.0.1:${server.address.port}/ws", object : FrameListener {
                    override fun onText(text: String) = Unit

                    override fun onClosed(reason: String) = Unit
                })
                fail("应当抛出 WebSocketHandshakeRejected")
            } catch (e: WebSocketHandshakeRejected) {
                assertEquals(403, e.status)
                assertEquals("403", e.headers["handshake-status"])
                assertEquals("forbidden", e.headers["handshake-msg"])
            }
        }
}
```

在 `src/test/kotlin/com/github/izerui/imux/bridge/channel/HttpFileDownloadTest.kt` 末尾（类内最后一个用例之后）追加：

```kotlin
    @Test
    fun `下载时带上调用方给的请求头`() =
        runBlocking {
            var authorization: String? = null
            server.createContext("/auth") { exchange ->
                authorization = exchange.requestHeaders.getFirst("Authorization")
                val body = "ok".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            if (!started) {
                server.start()
                started = true
            }
            val result =
                JdkHttpFileDownload(tempDir = temp.root.toPath())
                    .download("http://127.0.0.1:${server.address.port}/auth", 100, mapOf("Authorization" to "Bearer T"))
            assertTrue(result is DownloadResult.Ok)
            assertEquals("Bearer T", authorization)
        }
```

（`HttpFileDownloadTest` 已有 `server`、`started`、`temp` 字段；若已有的用例用别的方式启动服务，照该文件里现成的启动写法改成一致。）

- [ ] **Step 6: 运行，确认编译失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.HttpJsonTest' -q`
Expected: 编译失败，`Unresolved reference: JdkHttpJson` / `WebSocketHandshakeRejected`

- [ ] **Step 7: 修改 `Transports.kt`**

1）把 `FrameListener`、`FrameTransport` 换成：

```kotlin
interface FrameListener {
    fun onText(text: String)

    /** 二进制帧（已拼完分片）。只用文本帧的渠道不必实现。 */
    fun onBinary(bytes: ByteArray) {}

    fun onClosed(reason: String)
}

/** 帧传输。渠道连接只依赖这个端口，测试用假实现。 */
interface FrameTransport {
    /** 握手被服务端拒绝时抛 [WebSocketHandshakeRejected]。 */
    suspend fun open(
        url: String,
        listener: FrameListener,
    )

    suspend fun send(text: String)

    /** 二进制帧；与 [send] 共用同一串行顺序。只用文本帧的渠道不必实现。 */
    suspend fun sendBinary(bytes: ByteArray): Unit = throw UnsupportedOperationException("不支持二进制帧")

    fun close()
}

/** WebSocket 握手被服务端拒绝（非 101）。[headers] 的键统一为小写，每个键只取第一个值。 */
class WebSocketHandshakeRejected(
    val status: Int,
    val headers: Map<String, String>,
) : IOException("WebSocket 握手被拒绝：HTTP $status")

data class HttpJsonResponse(
    val status: Int,
    val json: JsonObject?,
)

/**
 * JSON 接口客户端。4xx/5xx 不抛异常：正文是 JSON 对象时照常返回，便于读取平台的业务错误码
 * （飞书扫码的等待态和业务错误都这样返回）。网络错误照常抛出。
 */
interface HttpJson {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpJsonResponse

    suspend fun post(
        url: String,
        contentType: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpJsonResponse
}
```

2）`JdkWebSocketTransport.open` 改为（新增 `binary` 拼接、`onBinary` 回调、握手失败转换）：

```kotlin
    override suspend fun open(
        url: String,
        listener: FrameListener,
    ) {
        val assembler = TextFrameAssembler()
        val binary = BinaryFrameAssembler()
        socket =
            try {
                client.newWebSocketBuilder()
                    .connectTimeout(NETWORK_TIMEOUT)
                    .buildAsync(
                        URI(url),
                        object : WebSocket.Listener {
                            override fun onText(
                                webSocket: WebSocket,
                                data: CharSequence,
                                last: Boolean,
                            ): CompletionStage<*>? {
                                assembler.accept(data, last)?.let(listener::onText)
                                webSocket.request(1)
                                return null
                            }

                            override fun onBinary(
                                webSocket: WebSocket,
                                data: ByteBuffer,
                                last: Boolean,
                            ): CompletionStage<*>? {
                                binary.accept(data, last)?.let(listener::onBinary)
                                webSocket.request(1)
                                return null
                            }

                            override fun onClose(
                                webSocket: WebSocket,
                                statusCode: Int,
                                reason: String,
                            ): CompletionStage<*>? {
                                listener.onClosed("$statusCode $reason")
                                return null
                            }

                            override fun onError(
                                webSocket: WebSocket,
                                error: Throwable,
                            ) {
                                listener.onClosed(error.message ?: error.javaClass.simpleName)
                            }
                        },
                    ).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cause = (e as? CompletionException)?.cause ?: e
                if (cause is WebSocketHandshakeException) throw handshakeRejected(cause)
                throw e
            }
    }

    override suspend fun sendBinary(bytes: ByteArray) {
        sendLock.withLock {
            val current = socket ?: throw IOException("WebSocket 未连接")
            current.sendBinary(ByteBuffer.wrap(bytes), true).await()
        }
    }

    private fun handshakeRejected(e: WebSocketHandshakeException): WebSocketHandshakeRejected {
        val response = e.response
        val headers =
            response.headers().map().entries
                .mapNotNull { (key, values) -> values.firstOrNull()?.let { key.lowercase() to it } }
                .toMap()
        return WebSocketHandshakeRejected(response.statusCode(), headers)
    }
```

3）在 `JdkHttpJsonGet` 之后新增：

```kotlin
/** 不显式设代理：JDK 走默认 ProxySelector，IDE 中即 IDE 的代理设置。 */
class JdkHttpJson(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(NETWORK_TIMEOUT).build(),
) : HttpJson {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpJsonResponse = execute(HttpRequest.newBuilder(URI(url)).GET(), headers)

    override suspend fun post(
        url: String,
        contentType: String,
        body: String,
        headers: Map<String, String>,
    ): HttpJsonResponse =
        execute(
            HttpRequest.newBuilder(URI(url)).header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body)),
            headers,
        )

    private suspend fun execute(
        builder: HttpRequest.Builder,
        headers: Map<String, String>,
    ): HttpJsonResponse {
        headers.forEach { (name, value) -> builder.header(name, value) }
        val response = client.sendAsync(builder.timeout(NETWORK_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString()).await()
        val json = runCatching { JsonParser.parseString(response.body()) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        return HttpJsonResponse(response.statusCode(), json)
    }
}
```

4）`HttpFileDownload` 与 `JdkHttpFileDownload` 增加请求头：

```kotlin
/** 流式下载到临时文件。超过上限中止并删除临时文件。不得抛出 CancellationException 以外的异常。 */
fun interface HttpFileDownload {
    suspend fun download(
        url: String,
        maxBytes: Long,
        headers: Map<String, String>,
    ): DownloadResult
}
```

`fun interface` 的抽象方法不能带默认参数，因此改为扩展函数提供两参数形式（放在 `HttpFileDownload` 声明之后）：

```kotlin
suspend fun HttpFileDownload.download(
    url: String,
    maxBytes: Long,
): DownloadResult = download(url, maxBytes, emptyMap())
```

`JdkHttpFileDownload`：`override suspend fun download(url: String, maxBytes: Long, headers: Map<String, String>)`，把 `headers` 传给 `fetch`；`fetch` 里构造请求改为：

```kotlin
                val builder = HttpRequest.newBuilder(URI(url)).timeout(timeout).GET()
                headers.forEach { (name, value) -> builder.header(name, value) }
                val request = builder.build()
```

5）补 import：`java.nio.ByteBuffer`、`java.net.http.WebSocketHandshakeException`、`java.util.concurrent.CompletionException`。

6）`WecomMedia.kt` 的调用 `download.download(url, maxBytes + PADDING_ALLOWANCE)` 走两参数的扩展函数：在该文件 import 区加 `import com.github.izerui.imux.bridge.channel.download`（扩展函数在 `channel` 包，`WecomMedia` 在 `channel.wecom` 包，必须显式导入）。

7）测试里实现 `HttpFileDownload` 的三处改为三参数：
- `WecomMediaTest.kt` 的 `FakeDownload.download(url, maxBytes)` 与匿名 `object : HttpFileDownload` 的 `download(url, maxBytes)` 增加参数 `headers: Map<String, String>`。
- `WecomConnectionTest.kt:91`：`download = HttpFileDownload { _, _, _ -> DownloadResult.Failed("test") },`

- [ ] **Step 8: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.*' -q`
Expected: PASS（含企业微信的全部测试）

---

### Task 2: SPI 与核心小改（SendFailing、AUDIO、扫码说明键）

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/channel/ChannelSpi.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeModels.kt:22`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/core/AttachmentNames.kt:69-80`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/ui/RobotRows.kt:74-87`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/ConnectionStatusTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/core/AttachmentNamesTest.kt`（追加）
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/ui/RobotRowsTest.kt`（追加）

**Interfaces:**
- Produces:
  - `ConnectionStatus.SendFailing(messageKey: String, detail: String? = null)`
  - `ConnectionStatus?.isWorking` 对 `SendFailing` 为 true
  - `ChannelFactory.scanStepsKey: String`（默认 `"bridge.add.scan.steps"`）
  - `AttachmentKind.AUDIO`
  - `AttachmentNames.extensionOf` 识别 `OggS` → `"ogg"`

- [ ] **Step 1: 写失败测试**

`src/test/kotlin/com/github/izerui/imux/bridge/channel/ConnectionStatusTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStatusTest {
    @Test
    fun `发送受阻时仍算在工作：收得到消息与配对码，不需要重连`() {
        assertTrue(ConnectionStatus.SendFailing("k").isWorking)
        assertTrue(ConnectionStatus.Online.isWorking)
        assertFalse(ConnectionStatus.Failed("x").isWorking)
    }
}
```

`AttachmentNamesTest.kt` 类内追加：

```kotlin
    @Test
    fun `识别 Ogg 音频并按 audio 命名`() {
        val head = "OggS".toByteArray() + ByteArray(8)
        assertEquals("ogg", AttachmentNames.extensionOf(head))
        assertEquals("audio-090501.ogg", AttachmentNames.fallback(AttachmentKind.AUDIO, java.time.LocalTime.of(9, 5, 1), "ogg"))
    }
```

`RobotRowsTest.kt` 类内追加：

```kotlin
    @Test
    fun `发送受阻算需要注意`() {
        assertEquals(RobotHealth.ATTENTION, robotHealth(ConnectionStatus.SendFailing("k"), paired = true, bridgeEnabled = true))
        assertEquals(true, needsAttention(ConnectionStatus.SendFailing("k", "230013")))
    }
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.ConnectionStatusTest' --tests 'com.github.izerui.imux.bridge.core.AttachmentNamesTest' --tests 'com.github.izerui.imux.bridge.ui.RobotRowsTest' -q`
Expected: 编译失败，`Unresolved reference: SendFailing` / `AUDIO`

- [ ] **Step 3: 实现**

`ChannelSpi.kt`：在 `NoCredentials` 之后加入：

```kotlin
    /**
     * 连接在、收得到消息，但发送被平台拒绝（额度用完、缺权限等）。
     * [messageKey] 是 ImuxBundle 的键，[detail] 填进它的 {0}。下一次发送成功后回到 Online。
     */
    data class SendFailing(
        val messageKey: String,
        val detail: String? = null,
    ) : ConnectionStatus
```

`isWorking` 改为：

```kotlin
/** 连接在工作或正在建立：消息与配对码收得到，不需要重连。发送受阻不影响接收。 */
val ConnectionStatus?.isWorking: Boolean
    get() = this == ConnectionStatus.Online || this == ConnectionStatus.Connecting || this is ConnectionStatus.SendFailing
```

`ChannelOnboarding.pollIntervalMillis` 加 KDoc：

```kotlin
    /** 调用方每次轮询前读取；可随轮询结果变化（如飞书要求放慢）。 */
    val pollIntervalMillis: Long
```

`ChannelFactory` 加：

```kotlin
    /** 添加对话框里扫码说明的文案键（ImuxBundle），参数 {0} 是渠道名。 */
    val scanStepsKey: String
        get() = "bridge.add.scan.steps"
```

`BridgeModels.kt:22`：`enum class AttachmentKind { IMAGE, FILE, VIDEO, AUDIO }`

`AttachmentNames.extensionOf` 的 `when` 在 `mp4` 行之前加：

```kotlin
            head.startsWithAscii("OggS") -> "ogg"
```

`RobotRows.kt` 的 `statusLabel`，在 `NoCredentials` 行之后加：

```kotlin
        status is ConnectionStatus.SendFailing ->
            status.detail?.let { ImuxBundle.message(status.messageKey, it) } ?: ImuxBundle.message(status.messageKey)
```

`robotHealth` 无需改：`SendFailing` 落入 `else -> ATTENTION`。

- [ ] **Step 4: 运行，确认通过**

Run: 同 Step 2
Expected: PASS

---

### Task 3: 飞书帧编解码 `FeishuFrameCodec`

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuFrameCodec.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuFrameCodecTest.kt`

**Interfaces:**
- Produces:
  - `data class FeishuHeader(val key: String, val value: String)`
  - `class FeishuFrame(seqId: Long = 0, logId: Long = 0, service: Int = 0, method: Int = 0, headers: List<FeishuHeader> = emptyList(), payloadEncoding: String? = null, payloadType: String? = null, payload: ByteArray? = null, logIdNew: String? = null)`，方法 `header(key): String?`、`withHeader(key, value): FeishuFrame`、`copy(...)`；常量 `FeishuFrame.METHOD_CONTROL = 0`、`FeishuFrame.METHOD_DATA = 1`
  - `object FeishuFrameCodec { fun encode(frame: FeishuFrame): ByteArray; fun decode(bytes: ByteArray): FeishuFrame }`（无法解析抛 `IllegalArgumentException`）

样本字节由官方 Python SDK `lark-oapi` 1.7.5 的 `pbbp2_pb2.Frame.SerializeToString()` 生成：
- ping：`SeqID=0, LogID=0, service=7, method=0, headers=[type=ping]` → `08001000180720002a0c0a0474797065120470696e67`
- data：`SeqID=300, LogID=5, service=7, method=1, headers=[type=event, message_id=m1, sum=1, seq=0, trace_id=t], payload='{"a":"中"}', LogIDNew="L"` → `08ac021005180720012a0d0a047479706512056576656e742a100a0a6d6573736167655f696412026d312a080a0373756d1201312a080a037365711201302a0d0a0874726163655f6964120174420b7b2261223a22e4b8ad227d4a014c`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.feishu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FeishuFrameCodecTest {
    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val pingHex = "08001000180720002a0c0a0474797065120470696e67"
    private val dataHex =
        "08ac021005180720012a0d0a047479706512056576656e742a100a0a6d6573736167655f696412026d312a080a0373756d1201312a080a03" +
            "7365711201302a0d0a0874726163655f6964120174420b7b2261223a22e4b8ad227d4a014c"

    private val ping = FeishuFrame(service = 7, method = FeishuFrame.METHOD_CONTROL, headers = listOf(FeishuHeader("type", "ping")))

    private val data =
        FeishuFrame(
            seqId = 300,
            logId = 5,
            service = 7,
            method = FeishuFrame.METHOD_DATA,
            headers =
                listOf(
                    FeishuHeader("type", "event"),
                    FeishuHeader("message_id", "m1"),
                    FeishuHeader("sum", "1"),
                    FeishuHeader("seq", "0"),
                    FeishuHeader("trace_id", "t"),
                ),
            payload = """{"a":"中"}""".toByteArray(Charsets.UTF_8),
            logIdNew = "L",
        )

    @Test
    fun `编码与官方 SDK 逐字节一致，值为 0 的必填字段也写出`() {
        assertArrayEquals(hex(pingHex), FeishuFrameCodec.encode(ping))
        assertArrayEquals(hex(dataHex), FeishuFrameCodec.encode(data))
    }

    @Test
    fun `解码官方 SDK 的字节`() {
        assertEquals(ping, FeishuFrameCodec.decode(hex(pingHex)))
        assertEquals(data, FeishuFrameCodec.decode(hex(dataHex)))
        assertEquals("m1", FeishuFrameCodec.decode(hex(dataHex)).header("message_id"))
    }

    @Test
    fun `未知字段跳过`() {
        val withUnknown = hex(pingHex) + byteArrayOf(0x78, 0x01) + byteArrayOf(0x82.toByte(), 0x01, 0x01, 0x41)
        assertEquals(ping, FeishuFrameCodec.decode(withUnknown))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `截断的输入报错`() {
        val bytes = hex(dataHex)
        FeishuFrameCodec.decode(bytes.copyOf(bytes.size - 3))
    }

    @Test
    fun `追加 header 不改原帧`() {
        val acked = data.withHeader("biz_rt", "0")
        assertEquals("0", acked.header("biz_rt"))
        assertEquals(null, data.header("biz_rt"))
    }
}
```

（`0x82 0x01` 是字段 16、wire type 2 的 tag，后跟长度 1 和一个字节，用来覆盖“跳过未知的长度前缀字段”。）

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.feishu.FeishuFrameCodecTest' -q`
Expected: 编译失败，`Unresolved reference: FeishuFrame`

- [ ] **Step 3: 实现**

```kotlin
package com.github.izerui.imux.bridge.channel.feishu

import java.io.ByteArrayOutputStream

data class FeishuHeader(
    val key: String,
    val value: String,
)

/**
 * 飞书长连接帧 `pbbp2.Frame`，字段号与官方 SDK 内嵌的 pbbp2.proto 一致：
 * SeqID=1、LogID=2、service=3、method=4 是 proto2 required，编码时总要写出；
 * headers=5、payload_encoding=6、payload_type=7、payload=8、LogIDNew=9 可选。
 */
class FeishuFrame(
    val seqId: Long = 0,
    val logId: Long = 0,
    val service: Int = 0,
    val method: Int = 0,
    val headers: List<FeishuHeader> = emptyList(),
    val payloadEncoding: String? = null,
    val payloadType: String? = null,
    val payload: ByteArray? = null,
    val logIdNew: String? = null,
) {
    fun header(key: String): String? = headers.firstOrNull { it.key == key }?.value

    fun withHeader(
        key: String,
        value: String,
    ): FeishuFrame = copy(headers = headers + FeishuHeader(key, value))

    fun copy(
        headers: List<FeishuHeader> = this.headers,
        payload: ByteArray? = this.payload,
    ): FeishuFrame = FeishuFrame(seqId, logId, service, method, headers, payloadEncoding, payloadType, payload, logIdNew)

    override fun equals(other: Any?): Boolean =
        other is FeishuFrame &&
            seqId == other.seqId &&
            logId == other.logId &&
            service == other.service &&
            method == other.method &&
            headers == other.headers &&
            payloadEncoding == other.payloadEncoding &&
            payloadType == other.payloadType &&
            (payload?.contentEquals(other.payload) ?: (other.payload == null)) &&
            logIdNew == other.logIdNew

    override fun hashCode(): Int = listOf(seqId, logId, service, method, headers, payloadEncoding, payloadType, payload?.contentHashCode(), logIdNew).hashCode()

    /** 不打印 payload：里面是消息正文。 */
    override fun toString(): String = "FeishuFrame(service=$service, method=$method, headers=$headers, payloadBytes=${payload?.size})"

    companion object {
        const val METHOD_CONTROL = 0
        const val METHOD_DATA = 1
    }
}

/** 只实现 pbbp2 用到的两种编码：varint（wire type 0）与长度前缀（wire type 2）；其余 wire type 解码时跳过。 */
object FeishuFrameCodec {
    fun encode(frame: FeishuFrame): ByteArray {
        val out = ByteArrayOutputStream()
        out.varintField(1, frame.seqId)
        out.varintField(2, frame.logId)
        out.varintField(3, frame.service.toLong())
        out.varintField(4, frame.method.toLong())
        frame.headers.forEach { out.bytesField(5, encodeHeader(it)) }
        frame.payloadEncoding?.let { out.bytesField(6, it.toByteArray(Charsets.UTF_8)) }
        frame.payloadType?.let { out.bytesField(7, it.toByteArray(Charsets.UTF_8)) }
        frame.payload?.let { out.bytesField(8, it) }
        frame.logIdNew?.let { out.bytesField(9, it.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /** 无法解析（截断、长度越界、不支持的 wire type）时抛 [IllegalArgumentException]。 */
    fun decode(bytes: ByteArray): FeishuFrame {
        val reader = Reader(bytes)
        var seqId = 0L
        var logId = 0L
        var service = 0
        var method = 0
        val headers = mutableListOf<FeishuHeader>()
        var payloadEncoding: String? = null
        var payloadType: String? = null
        var payload: ByteArray? = null
        var logIdNew: String? = null
        while (reader.hasMore()) {
            val tag = reader.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            when {
                field == 1 && wire == 0 -> seqId = reader.varint()
                field == 2 && wire == 0 -> logId = reader.varint()
                field == 3 && wire == 0 -> service = reader.varint().toInt()
                field == 4 && wire == 0 -> method = reader.varint().toInt()
                field == 5 && wire == 2 -> headers += decodeHeader(reader.bytes())
                field == 6 && wire == 2 -> payloadEncoding = String(reader.bytes(), Charsets.UTF_8)
                field == 7 && wire == 2 -> payloadType = String(reader.bytes(), Charsets.UTF_8)
                field == 8 && wire == 2 -> payload = reader.bytes()
                field == 9 && wire == 2 -> logIdNew = String(reader.bytes(), Charsets.UTF_8)
                else -> reader.skip(wire)
            }
        }
        return FeishuFrame(seqId, logId, service, method, headers, payloadEncoding, payloadType, payload, logIdNew)
    }

    private fun encodeHeader(header: FeishuHeader): ByteArray {
        val out = ByteArrayOutputStream()
        out.bytesField(1, header.key.toByteArray(Charsets.UTF_8))
        out.bytesField(2, header.value.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun decodeHeader(bytes: ByteArray): FeishuHeader {
        val reader = Reader(bytes)
        var key = ""
        var value = ""
        while (reader.hasMore()) {
            val tag = reader.varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            when {
                field == 1 && wire == 2 -> key = String(reader.bytes(), Charsets.UTF_8)
                field == 2 && wire == 2 -> value = String(reader.bytes(), Charsets.UTF_8)
                else -> reader.skip(wire)
            }
        }
        return FeishuHeader(key, value)
    }

    private fun ByteArrayOutputStream.varint(value: Long) {
        var remaining = value
        while (true) {
            if (remaining and 0x7FL.inv() == 0L) {
                write(remaining.toInt())
                return
            }
            write(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
    }

    private fun ByteArrayOutputStream.varintField(
        field: Int,
        value: Long,
    ) {
        varint((field shl 3).toLong())
        varint(value)
    }

    private fun ByteArrayOutputStream.bytesField(
        field: Int,
        value: ByteArray,
    ) {
        varint(((field shl 3) or 2).toLong())
        varint(value.size.toLong())
        write(value)
    }

    private class Reader(
        private val bytes: ByteArray,
    ) {
        private var position = 0

        fun hasMore(): Boolean = position < bytes.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(position < bytes.size) { "varint 被截断" }
                val byte = bytes[position++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "varint 过长" }
            }
        }

        fun bytes(): ByteArray {
            val length = varint()
            require(length >= 0 && length <= bytes.size - position) { "长度越界" }
            val end = position + length.toInt()
            return bytes.copyOfRange(position, end).also { position = end }
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> advance(8)
                2 -> bytes()
                5 -> advance(4)
                else -> throw IllegalArgumentException("不支持的 wire type：$wire")
            }
        }

        private fun advance(count: Int) {
            require(count <= bytes.size - position) { "字段被截断" }
            position += count
        }
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.feishu.FeishuFrameCodecTest' -q`
Expected: PASS

---

### Task 4: 域名、凭据键与事件解析 `FeishuEvents`

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuDomain.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuEvents.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuEventsTest.kt`

**Interfaces:**
- Consumes: `AttachmentKind`（含 Task 2 的 `AUDIO`）
- Produces:
  - `enum class FeishuDomain(val id: String, val accountsBase: String, val openBase: String) { FEISHU, LARK }`，`FeishuDomain.of(id: String?): FeishuDomain`
  - `object FeishuCredentialKeys { const val APP_ID = "app_id"; const val APP_SECRET = "app_secret"; const val DOMAIN = "domain" }`
  - `data class FeishuMediaRef(val kind: AttachmentKind, val fileKey: String, val resourceType: String, val fileName: String?)`
  - `data class FeishuMessage(val messageId: String, val senderOpenId: String, val text: String?, val media: List<FeishuMediaRef>, val parentId: String?)`
  - `object FeishuEvents { fun parseMessage(payload: String): FeishuMessage?; fun quoteText(messageType: String?, content: String?): String? }`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeishuEventsTest {
    private fun event(
        messageType: String,
        content: JsonObject,
        chatType: String = "p2p",
        senderType: String = "user",
        eventType: String = "im.message.receive_v1",
        parentId: String? = null,
    ): String {
        val message =
            JsonObject().apply {
                addProperty("message_id", "om_1")
                addProperty("chat_type", chatType)
                addProperty("message_type", messageType)
                addProperty("content", content.toString())
                parentId?.let { addProperty("parent_id", it) }
            }
        val sender =
            JsonObject().apply {
                add("sender_id", JsonObject().apply { addProperty("open_id", "ou_me") })
                addProperty("sender_type", senderType)
            }
        return JsonObject().apply {
            addProperty("schema", "2.0")
            add("header", JsonObject().apply { addProperty("event_type", eventType) })
            add("event", JsonObject().apply { add("sender", sender); add("message", message) })
        }.toString()
    }

    private fun obj(vararg pairs: Pair<String, String>) = JsonObject().apply { pairs.forEach { addProperty(it.first, it.second) } }

    @Test
    fun `文本消息`() {
        assertEquals(FeishuMessage("om_1", "ou_me", "跑一下单测", emptyList(), null), FeishuEvents.parseMessage(event("text", obj("text" to "跑一下单测"))))
    }

    @Test
    fun `图片、文件、视频、语音各生成一个附件`() {
        assertEquals(listOf(FeishuMediaRef(AttachmentKind.IMAGE, "img_1", "image", null)), FeishuEvents.parseMessage(event("image", obj("image_key" to "img_1")))!!.media)
        assertEquals(listOf(FeishuMediaRef(AttachmentKind.FILE, "f_1", "file", "报告.pdf")), FeishuEvents.parseMessage(event("file", obj("file_key" to "f_1", "file_name" to "报告.pdf")))!!.media)
        assertEquals(listOf(FeishuMediaRef(AttachmentKind.VIDEO, "v_1", "file", "a.mp4")), FeishuEvents.parseMessage(event("media", obj("file_key" to "v_1", "image_key" to "c", "file_name" to "a.mp4")))!!.media)
        assertEquals(listOf(FeishuMediaRef(AttachmentKind.AUDIO, "a_1", "file", null)), FeishuEvents.parseMessage(event("audio", obj("file_key" to "a_1")))!!.media)
    }

    @Test
    fun `富文本拼接文字并收集图片，兼容语言外壳`() {
        val content =
            com.google.gson.JsonParser.parseString(
                """{"title":"标题","content":[[{"tag":"text","text":"看"},{"tag":"a","text":"链接","href":"h"}],[{"tag":"img","image_key":"img_2"}],[{"tag":"code_block","text":"x=1"}]]}""",
            ).asJsonObject
        val plain = FeishuEvents.parseMessage(event("post", content))!!
        assertEquals("标题\n看链接\nx=1", plain.text)
        assertEquals(listOf(FeishuMediaRef(AttachmentKind.IMAGE, "img_2", "image", null)), plain.media)
        val wrapped = JsonObject().apply { add("zh_cn", content) }
        assertEquals("标题\n看链接\nx=1", FeishuEvents.parseMessage(event("post", wrapped))!!.text)
    }

    @Test
    fun `带出引用的 parent_id`() {
        assertEquals("om_0", FeishuEvents.parseMessage(event("text", obj("text" to "这个呢"), parentId = "om_0"))!!.parentId)
    }

    @Test
    fun `群聊、机器人发送、其他事件、不支持的类型都忽略`() {
        assertNull(FeishuEvents.parseMessage(event("text", obj("text" to "x"), chatType = "group")))
        assertNull(FeishuEvents.parseMessage(event("text", obj("text" to "x"), senderType = "app")))
        assertNull(FeishuEvents.parseMessage(event("text", obj("text" to "x"), eventType = "im.chat.updated_v1")))
        assertNull(FeishuEvents.parseMessage(event("sticker", obj("file_key" to "s"))))
        assertNull(FeishuEvents.parseMessage("not json"))
    }

    @Test
    fun `引用文字只取文本与富文本`() {
        assertEquals("原话", FeishuEvents.quoteText("text", """{"text":"原话"}"""))
        assertNull(FeishuEvents.quoteText("image", """{"image_key":"i"}"""))
        assertNull(FeishuEvents.quoteText("text", null))
    }

    @Test
    fun `域名按 id 取，未知时默认飞书`() {
        assertEquals(FeishuDomain.LARK, FeishuDomain.of("lark"))
        assertEquals(FeishuDomain.FEISHU, FeishuDomain.of(null))
        assertEquals("https://open.larksuite.com", FeishuDomain.LARK.openBase)
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.feishu.FeishuEventsTest' -q`
Expected: 编译失败

- [ ] **Step 3: 实现**

`FeishuDomain.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel.feishu

/** 飞书与 Lark 两套地址；扫码时按返回的 tenant_brand 确定，[id] 写入凭据。 */
enum class FeishuDomain(
    val id: String,
    val accountsBase: String,
    val openBase: String,
) {
    FEISHU("feishu", "https://accounts.feishu.cn", "https://open.feishu.cn"),
    LARK("lark", "https://accounts.larksuite.com", "https://open.larksuite.com"),
    ;

    companion object {
        fun of(id: String?): FeishuDomain = entries.firstOrNull { it.id == id } ?: FEISHU
    }
}

/** 飞书机器人凭据在凭据库里的键名。 */
object FeishuCredentialKeys {
    const val APP_ID = "app_id"
    const val APP_SECRET = "app_secret"
    const val DOMAIN = "domain"
}
```

`FeishuEvents.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.json.obj
import com.github.izerui.imux.bridge.json.objects
import com.github.izerui.imux.bridge.json.string
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 消息里的一个附件引用；[resourceType] 是下载接口的 type 参数（image / file）。 */
data class FeishuMediaRef(
    val kind: AttachmentKind,
    val fileKey: String,
    val resourceType: String,
    val fileName: String?,
)

/** 本人单聊发来的一条消息。[text] 为 null 表示没有文字；[parentId] 是被引用消息的 id。 */
data class FeishuMessage(
    val messageId: String,
    val senderOpenId: String,
    val text: String?,
    val media: List<FeishuMediaRef>,
    val parentId: String?,
)

/** 飞书事件解析（docs/superpowers/specs/2026-10-28-feishu-channel-design.md 第 6 节）。纯函数。 */
object FeishuEvents {
    private const val RECEIVE = "im.message.receive_v1"

    /** 只接受用户在单聊里发来的消息；其他事件、群聊、机器人发送、无法解析或既无文字也无附件时返回 null。 */
    fun parseMessage(payload: String): FeishuMessage? {
        val root = parseObject(payload) ?: return null
        if (root.obj("header")?.string("event_type") != RECEIVE) return null
        val event = root.obj("event") ?: return null
        val sender = event.obj("sender") ?: return null
        if (sender.string("sender_type") != "user") return null
        val openId = sender.obj("sender_id")?.string("open_id")?.takeIf(String::isNotEmpty) ?: return null
        val message = event.obj("message") ?: return null
        if (message.string("chat_type") != "p2p") return null
        val messageId = message.string("message_id")?.takeIf(String::isNotEmpty) ?: return null
        val content = message.string("content")?.let(::parseObject) ?: return null
        val (text, media) = contentOf(message.string("message_type"), content)
        if (text == null && media.isEmpty()) return null
        return FeishuMessage(messageId, openId, text, media, message.string("parent_id")?.takeIf(String::isNotEmpty))
    }

    /** 被引用消息的文字：只取文本与富文本，其余返回 null。 */
    fun quoteText(
        messageType: String?,
        content: String?,
    ): String? {
        if (messageType != "text" && messageType != "post") return null
        return content?.let(::parseObject)?.let { contentOf(messageType, it).first }
    }

    private fun contentOf(
        type: String?,
        content: JsonObject,
    ): Pair<String?, List<FeishuMediaRef>> =
        when (type) {
            "text" -> content.string("text")?.takeIf(String::isNotBlank) to emptyList()
            "post" -> post(content)
            "image" -> null to listOfNotNull(content.string("image_key")?.let { FeishuMediaRef(AttachmentKind.IMAGE, it, "image", null) })
            "file" -> null to listOfNotNull(content.string("file_key")?.let { FeishuMediaRef(AttachmentKind.FILE, it, "file", content.string("file_name")) })
            "media" -> null to listOfNotNull(content.string("file_key")?.let { FeishuMediaRef(AttachmentKind.VIDEO, it, "file", content.string("file_name")) })
            "audio" -> null to listOfNotNull(content.string("file_key")?.let { FeishuMediaRef(AttachmentKind.AUDIO, it, "file", null) })
            else -> null to emptyList()
        }

    /** 标题与每段的文字按顺序拼接，段之间换行；`img` 生成图片附件。 */
    private fun post(content: JsonObject): Pair<String?, List<FeishuMediaRef>> {
        val body = postBody(content) ?: return null to emptyList()
        val lines = mutableListOf<String>()
        body.string("title")?.takeIf(String::isNotBlank)?.let(lines::add)
        val media = mutableListOf<FeishuMediaRef>()
        body.get("content")?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.forEach { paragraph ->
            val line = StringBuilder()
            paragraph.objects().forEach { element ->
                when (element.string("tag")) {
                    "text", "a", "md", "code_block" -> element.string("text")?.let(line::append)
                    "at" -> element.string("user_name")?.let { line.append('@').append(it) }
                    "img" -> element.string("image_key")?.let { media += FeishuMediaRef(AttachmentKind.IMAGE, it, "image", null) }
                }
            }
            if (line.isNotBlank()) lines += line.toString()
        }
        return lines.joinToString("\n").takeIf(String::isNotBlank) to media
    }

    /** 富文本可能外面再包一层 `post` 或语言键（如 `zh_cn`）。 */
    private fun postBody(content: JsonObject): JsonObject? {
        val unwrapped = content.obj("post") ?: content
        if (unwrapped.has("content")) return unwrapped
        return unwrapped.entrySet().firstNotNullOfOrNull { (_, value) -> value.takeIf { it.isJsonObject }?.asJsonObject?.takeIf { it.has("content") } }
    }

    private fun parseObject(text: String): JsonObject? = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.feishu.FeishuEventsTest' -q`
Expected: PASS

---

### Task 5: HTTP 接口 `FeishuApi`

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuApi.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuApiTest.kt`
- Test helper: `src/test/kotlin/com/github/izerui/imux/bridge/channel/feishu/FakeHttpJson.kt`

**Interfaces:**
- Consumes: `HttpJson`、`HttpJsonResponse`、`JdkHttpJson`（Task 1）；`FeishuDomain`、`FeishuEvents.quoteText`（Task 4）
- Produces:
  - `sealed interface FeishuResult<out T> { Ok(value), Error(code: Int, msg: String), Network(reason: String) }`
  - `data class FeishuClientConfig(reconnectCount: Int, reconnectIntervalSeconds: Int, reconnectNonceSeconds: Int, pingIntervalSeconds: Int)`，`FeishuClientConfig.DEFAULT`，`FeishuClientConfig.from(json: JsonObject?, fallback: FeishuClientConfig = DEFAULT)`
  - `data class FeishuEndpoint(val url: String, val config: FeishuClientConfig)`
  - `object FeishuCodes`（错误码集合，见代码）
  - `class FeishuApi(appId, appSecret, domain, http = JdkHttpJson(), now = System::currentTimeMillis)`：`suspend fun endpoint(): FeishuResult<FeishuEndpoint>`、`suspend fun token(): FeishuResult<String>`、`fun invalidateToken()`、`suspend fun send(body: String): FeishuResult<Unit>`、`suspend fun messageText(messageId: String): String?`、`fun resourceUrl(messageId: String, fileKey: String, type: String): String`；`FeishuApi.markdownBody(openId: String, markdown: String): String`、`FeishuApi.MAX_POST_BODY_BYTES`

- [ ] **Step 1: 写测试辅助的假 HTTP**

`FakeHttpJson.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.HttpJson
import com.github.izerui.imux.bridge.channel.HttpJsonResponse
import com.google.gson.JsonParser
import java.util.concurrent.CopyOnWriteArrayList

/** 按请求返回 JSON；记录每次请求的方法、地址、正文与 Authorization 头。 */
class FakeHttpJson(
    private val route: suspend (method: String, url: String, body: String?) -> Pair<Int, String>,
) : HttpJson {
    data class Call(
        val method: String,
        val url: String,
        val body: String?,
        val authorization: String?,
    )

    val calls = CopyOnWriteArrayList<Call>()

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
        calls += Call(method, url, body, headers["Authorization"])
        val (status, text) = route(method, url, body)
        val json = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        return HttpJsonResponse(status, json)
    }
}
```

- [ ] **Step 2: 写失败测试**

`FeishuApiTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel.feishu

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class FeishuApiTest {
    private var clock = 0L
    private val tokens = AtomicInteger()

    private fun api(route: suspend (String, String, String?) -> Pair<Int, String>): Pair<FeishuApi, FakeHttpJson> {
        val http =
            FakeHttpJson { method, url, body ->
                if (url.endsWith("/tenant_access_token/internal")) {
                    200 to """{"code":0,"tenant_access_token":"T${tokens.incrementAndGet()}","expire":7200}"""
                } else {
                    route(method, url, body)
                }
            }
        return FeishuApi("cli_1", "sec", FeishuDomain.FEISHU, http) { clock } to http
    }

    @Test
    fun `取长连接地址与客户端配置`() =
        runBlocking {
            val (api, http) =
                api { _, _, _ -> 200 to """{"code":0,"data":{"URL":"wss://x/ws?device_id=d&service_id=7","ClientConfig":{"PingInterval":90,"ReconnectInterval":60,"ReconnectNonce":10,"ReconnectCount":-1}}}""" }
            val endpoint = (api.endpoint() as FeishuResult.Ok).value
            assertEquals("wss://x/ws?device_id=d&service_id=7", endpoint.url)
            assertEquals(FeishuClientConfig(-1, 60, 10, 90), endpoint.config)
            val call = http.calls.single()
            assertEquals("https://open.feishu.cn/callback/ws/endpoint", call.url)
            assertEquals("cli_1", JsonParser.parseString(call.body).asJsonObject["AppID"].asString)
        }

    @Test
    fun `长连接地址的业务错误与网络错误分开返回`() =
        runBlocking {
            assertEquals(FeishuResult.Error(10015, "wrong app secret"), api { _, _, _ -> 200 to """{"code":10015,"msg":"wrong app secret"}""" }.first.endpoint())
            assertTrue(api { _, _, _ -> 502 to "<html>" }.first.endpoint() is FeishuResult.Network)
        }

    @Test
    fun `令牌缓存到剩余不足 10 分钟才重新获取`() =
        runBlocking {
            val (api, _) = api { _, _, _ -> 200 to """{"code":0}""" }
            assertEquals(FeishuResult.Ok("T1"), api.token())
            clock = (7200 - 601) * 1_000L
            assertEquals(FeishuResult.Ok("T1"), api.token())
            clock = (7200 - 599) * 1_000L
            assertEquals(FeishuResult.Ok("T2"), api.token())
        }

    @Test
    fun `发送带令牌；令牌无效时重取并只重试一次`() =
        runBlocking {
            val sends = AtomicInteger()
            val (api, http) = api { _, _, _ -> if (sends.incrementAndGet() == 1) 400 to """{"code":99991663,"msg":"invalid"}""" else 200 to """{"code":0}""" }
            assertEquals(FeishuResult.Ok(Unit), api.send(FeishuApi.markdownBody("ou_me", "**完成**")))
            val sendCalls = http.calls.filter { it.url.contains("/im/v1/messages") }
            assertEquals(listOf("Bearer T1", "Bearer T2"), sendCalls.map { it.authorization })
            assertEquals("https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=open_id", sendCalls.first().url)

            val always = AtomicInteger()
            val (stubborn, _) = api { _, _, _ -> always.incrementAndGet(); 400 to """{"code":99991663,"msg":"invalid"}""" }
            assertEquals(FeishuResult.Error(99991663, "invalid"), stubborn.send("{}"))
            assertEquals(2, always.get())
        }

    @Test
    fun `Markdown 正文放在富文本的 md 标签里`() {
        val body = JsonParser.parseString(FeishuApi.markdownBody("ou_me", "# 标题\n\"引号\"")).asJsonObject
        assertEquals("ou_me", body["receive_id"].asString)
        assertEquals("post", body["msg_type"].asString)
        val content = JsonParser.parseString(body["content"].asString).asJsonObject
        val md = content["zh_cn"].asJsonObject["content"].asJsonArray[0].asJsonArray[0].asJsonObject
        assertEquals("md", md["tag"].asString)
        assertEquals("# 标题\n\"引号\"", md["text"].asString)
    }

    @Test
    fun `取引用消息的文字，失败时为 null`() =
        runBlocking {
            val (api, http) = api { _, _, _ -> 200 to """{"code":0,"data":{"items":[{"msg_type":"text","body":{"content":"{\"text\":\"原话\"}"}}]}}""" }
            assertEquals("原话", api.messageText("om_0"))
            assertEquals("https://open.feishu.cn/open-apis/im/v1/messages/om_0", http.calls.last().url)
            assertNull(api { _, _, _ -> 200 to """{"code":230002,"msg":"x"}""" }.first.messageText("om_0"))
        }

    @Test
    fun `资源地址按消息、文件键与类型拼接`() {
        val (api, _) = api { _, _, _ -> 200 to "{}" }
        assertEquals("https://open.feishu.cn/open-apis/im/v1/messages/om_1/resources/f_1?type=file", api.resourceUrl("om_1", "f_1", "file"))
    }
}
```

- [ ] **Step 3: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.feishu.FeishuApiTest' -q`
Expected: 编译失败

- [ ] **Step 4: 实现**

```kotlin
package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.HttpJson
import com.github.izerui.imux.bridge.channel.HttpJsonResponse
import com.github.izerui.imux.bridge.channel.JdkHttpJson
import com.github.izerui.imux.bridge.json.obj
import com.github.izerui.imux.bridge.json.objects
import com.github.izerui.imux.bridge.json.primitiveText
import com.github.izerui.imux.bridge.json.string
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.cancellation.CancellationException

/** 一次飞书调用的结果：成功、平台业务错误（code 非 0）、网络或解析失败（结果不明）。 */
sealed interface FeishuResult<out T> {
    data class Ok<out T>(val value: T) : FeishuResult<T>

    data class Error(
        val code: Int,
        val msg: String,
    ) : FeishuResult<Nothing>

    data class Network(val reason: String) : FeishuResult<Nothing>
}

/** 服务端下发的长连接参数，时间单位为秒；[reconnectCount] 为 -1 表示无限重连。 */
data class FeishuClientConfig(
    val reconnectCount: Int,
    val reconnectIntervalSeconds: Int,
    val reconnectNonceSeconds: Int,
    val pingIntervalSeconds: Int,
) {
    companion object {
        /** 与官方 SDK 的本地默认值一致。 */
        val DEFAULT = FeishuClientConfig(reconnectCount = -1, reconnectIntervalSeconds = 120, reconnectNonceSeconds = 30, pingIntervalSeconds = 120)

        fun from(
            json: JsonObject?,
            fallback: FeishuClientConfig = DEFAULT,
        ): FeishuClientConfig =
            FeishuClientConfig(
                reconnectCount = json?.int("ReconnectCount") ?: fallback.reconnectCount,
                reconnectIntervalSeconds = json?.int("ReconnectInterval") ?: fallback.reconnectIntervalSeconds,
                reconnectNonceSeconds = json?.int("ReconnectNonce") ?: fallback.reconnectNonceSeconds,
                pingIntervalSeconds = json?.int("PingInterval") ?: fallback.pingIntervalSeconds,
            )

        private fun JsonObject.int(key: String): Int? = primitiveText(key)?.toIntOrNull()
    }
}

data class FeishuEndpoint(
    val url: String,
    val config: FeishuClientConfig,
)

/** 官方通用错误码与发送消息文档里用到的错误码（见设计第 2 节）。 */
object FeishuCodes {
    val TOKEN_INVALID = setOf(99991661, 99991663, 99991665, 20005, 20013)
    val APP_UNAVAILABLE = setOf(10014, 10015, 11207, 11209, 99991662, 99991673, 20009)
    val RATE_LIMITED = setOf(99991400, 1000004, 1000005)
    const val QUOTA_EXCEEDED = 99991403
    val NO_PERMISSION = setOf(99991672, 99991676, 230006, 230013)

    /** 取长连接地址时可重试的错误：系统繁忙、内部错误；其余视为配置问题，不重连。 */
    val ENDPOINT_RETRYABLE = setOf(1, 1000040343)

    const val HANDSHAKE_FORBIDDEN = 403
    const val HANDSHAKE_AUTH_FAILED = 514
    const val EXCEED_CONN_LIMIT = 1000040350
}

/**
 * 飞书的 HTTP 调用：长连接地址、应用令牌、发消息、取消息。
 * 令牌只在内存，随用随取；剩余不足 10 分钟就重新获取（与官方 SDK 相同），令牌无效时清掉重取并只重试一次。
 */
class FeishuApi(
    private val appId: String,
    private val appSecret: String,
    private val domain: FeishuDomain,
    private val http: HttpJson = JdkHttpJson(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val tokenLock = Mutex()

    @Volatile
    private var token: String? = null

    @Volatile
    private var tokenExpiresAt = 0L

    suspend fun endpoint(): FeishuResult<FeishuEndpoint> {
        val body = JsonObject().apply {
            addProperty("AppID", appId)
            addProperty("AppSecret", appSecret)
        }
        return when (val result = request { http.post("${domain.openBase}/callback/ws/endpoint", JSON_TYPE, body.toString(), mapOf("locale" to "zh")) }) {
            is FeishuResult.Ok -> {
                val data = result.value.obj("data")
                val url = data?.string("URL")
                if (data == null || url.isNullOrEmpty()) {
                    FeishuResult.Network("no url")
                } else {
                    FeishuResult.Ok(FeishuEndpoint(url, FeishuClientConfig.from(data.obj("ClientConfig"))))
                }
            }
            is FeishuResult.Error -> result
            is FeishuResult.Network -> result
        }
    }

    suspend fun token(): FeishuResult<String> =
        tokenLock.withLock {
            val cached = token
            if (cached != null && tokenExpiresAt - now() > REFRESH_MARGIN_MILLIS) return@withLock FeishuResult.Ok(cached)
            val body = JsonObject().apply {
                addProperty("app_id", appId)
                addProperty("app_secret", appSecret)
            }
            when (val result = request { http.post("${domain.openBase}/open-apis/auth/v3/tenant_access_token/internal", JSON_TYPE, body.toString()) }) {
                is FeishuResult.Ok -> {
                    val value = result.value.string("tenant_access_token")
                    if (value.isNullOrEmpty()) {
                        FeishuResult.Network("no token")
                    } else {
                        token = value
                        tokenExpiresAt = now() + (result.value.primitiveText("expire")?.toLongOrNull() ?: 0L) * 1_000
                        FeishuResult.Ok(value)
                    }
                }
                is FeishuResult.Error -> result
                is FeishuResult.Network -> result
            }
        }

    fun invalidateToken() {
        token = null
    }

    suspend fun send(body: String): FeishuResult<Unit> =
        when (val result = authorized { http.post("${domain.openBase}/open-apis/im/v1/messages?receive_id_type=open_id", JSON_TYPE, body, bearer(it)) }) {
            is FeishuResult.Ok -> FeishuResult.Ok(Unit)
            is FeishuResult.Error -> result
            is FeishuResult.Network -> result
        }

    /** 被引用消息的文字；取不到或不是文字类消息时返回 null。 */
    suspend fun messageText(messageId: String): String? {
        val result = authorized { http.get("${domain.openBase}/open-apis/im/v1/messages/${encode(messageId)}", bearer(it)) }
        val item = (result as? FeishuResult.Ok)?.value?.obj("data")?.objects("items")?.firstOrNull() ?: return null
        return FeishuEvents.quoteText(item.string("msg_type"), item.obj("body")?.string("content"))
    }

    fun resourceUrl(
        messageId: String,
        fileKey: String,
        type: String,
    ): String = "${domain.openBase}/open-apis/im/v1/messages/${encode(messageId)}/resources/${encode(fileKey)}?type=${encode(type)}"

    private suspend fun authorized(call: suspend (String) -> HttpJsonResponse): FeishuResult<JsonObject> {
        var retried = false
        while (true) {
            val current =
                when (val result = token()) {
                    is FeishuResult.Ok -> result.value
                    is FeishuResult.Error -> return result
                    is FeishuResult.Network -> return result
                }
            val result = request { call(current) }
            if (result is FeishuResult.Error && result.code in FeishuCodes.TOKEN_INVALID && !retried) {
                retried = true
                invalidateToken()
                continue
            }
            return result
        }
    }

    private suspend fun request(call: suspend () -> HttpJsonResponse): FeishuResult<JsonObject> {
        val response =
            try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return FeishuResult.Network(e.message ?: e.javaClass.simpleName)
            }
        val json = response.json ?: return FeishuResult.Network("HTTP ${response.status}")
        val code = json.primitiveText("code")?.toIntOrNull() ?: return FeishuResult.Network("HTTP ${response.status}")
        return if (code == 0) FeishuResult.Ok(json) else FeishuResult.Error(code, json.string("msg").orEmpty())
    }

    companion object {
        /** 富文本请求体上限。 */
        const val MAX_POST_BODY_BYTES = 30 * 1024

        private const val JSON_TYPE = "application/json; charset=utf-8"
        private const val REFRESH_MARGIN_MILLIS = 10 * 60_000L

        /** 单聊发送的请求体：富文本 post，正文整段放进一个 `md` 标签。 */
        fun markdownBody(
            openId: String,
            markdown: String,
        ): String {
            val md = JsonObject().apply {
                addProperty("tag", "md")
                addProperty("text", markdown)
            }
            val paragraphs = JsonArray().apply { add(JsonArray().apply { add(md) }) }
            val post = JsonObject().apply { add("zh_cn", JsonObject().apply { add("content", paragraphs) }) }
            return JsonObject().apply {
                addProperty("receive_id", openId)
                addProperty("msg_type", "post")
                addProperty("content", post.toString())
            }.toString()
        }

        private fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}
```

- [ ] **Step 5: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.feishu.FeishuApiTest' -q`
Expected: PASS

---

### Task 6: 扫码 `FeishuOnboarding`

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuOnboarding.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuOnboardingTest.kt`

**Interfaces:**
- Consumes: `HttpJson`、`JdkHttpJson`（Task 1）；`FeishuDomain`、`FeishuCredentialKeys`（Task 4）；`FakeHttpJson`（Task 5 测试辅助）
- Produces: `class FeishuOnboarding(http: HttpJson = JdkHttpJson()) : ChannelOnboarding`；失败文案键 `bridge.onboarding.unsupported`、`bridge.onboarding.denied`、`bridge.onboarding.expired`（文案在 Task 10 加入）

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.OnboardingStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FeishuOnboardingTest {
    private fun onboarding(vararg responses: Pair<Int, String>): Pair<FeishuOnboarding, FakeHttpJson> {
        val queue = ArrayDeque(responses.toList())
        val http = FakeHttpJson { _, _, _ -> queue.removeFirst() }
        return FeishuOnboarding(http) to http
    }

    private val init = 200 to """{"supported_auth_methods":["client_secret"]}"""
    private val begin = 200 to """{"device_code":"D","verification_uri_complete":"https://open.feishu.cn/page?code=X","interval":5,"expire_in":600}"""

    @Test
    fun `申请二维码：先 init 再 begin，按 PersonalAgent 请求本人 open_id`() =
        runBlocking {
            val (flow, http) = onboarding(init, begin)
            assertEquals(OnboardingStep.ShowQr("https://open.feishu.cn/page?code=X"), flow.start())
            assertEquals("https://accounts.feishu.cn/oauth/v1/app/registration", http.calls[0].url)
            assertEquals("action=init", http.calls[0].body)
            assertEquals("action=begin&archetype=PersonalAgent&auth_method=client_secret&request_user_info=open_id", http.calls[1].body)
            assertEquals(5_000L, flow.pollIntervalMillis)
        }

    @Test
    fun `等待、放慢、成功：凭据带域名，本人取 open_id`() =
        runBlocking {
            val (flow, http) =
                onboarding(
                    init,
                    begin,
                    400 to """{"error":"authorization_pending"}""",
                    400 to """{"error":"slow_down"}""",
                    200 to """{"client_id":"cli_1","client_secret":"S","user_info":{"open_id":"ou_me","tenant_brand":"feishu"}}""",
                )
            flow.start()
            assertEquals(OnboardingStep.Waiting, flow.poll())
            assertEquals(OnboardingStep.Waiting, flow.poll())
            assertEquals(10_000L, flow.pollIntervalMillis)
            assertEquals(
                OnboardingStep.Succeeded(mapOf("app_id" to "cli_1", "app_secret" to "S", "domain" to "feishu"), ownerId = "ou_me", suggestedName = null),
                flow.poll(),
            )
            assertEquals("action=poll&device_code=D&tp=ob_app", http.calls.last().body)
        }

    @Test
    fun `Lark 租户切换域名，同一响应带凭据时照常成功`() =
        runBlocking {
            val (flow, http) =
                onboarding(
                    init,
                    begin,
                    200 to """{"error":"authorization_pending","user_info":{"tenant_brand":"lark"}}""",
                    200 to """{"client_id":"cli_1","client_secret":"S","user_info":{"open_id":"ou_me","tenant_brand":"lark"}}""",
                )
            flow.start()
            assertEquals(OnboardingStep.Waiting, flow.poll())
            val done = flow.poll() as OnboardingStep.Succeeded
            assertEquals("lark", done.credentials["domain"])
            assertEquals("https://accounts.larksuite.com/oauth/v1/app/registration", http.calls.last().url)
        }

    @Test
    fun `拿不到 open_id 时走配对码`() =
        runBlocking {
            val (flow, _) = onboarding(init, begin, 200 to """{"client_id":"cli_1","client_secret":"S"}""")
            flow.start()
            assertEquals(null, (flow.poll() as OnboardingStep.Succeeded).ownerId)
        }

    @Test
    fun `拒绝、过期、不支持、没有二维码分别失败`() =
        runBlocking {
            val (denied, _) = onboarding(init, begin, 400 to """{"error":"access_denied"}""")
            denied.start()
            assertEquals(OnboardingStep.Failed("bridge.onboarding.denied"), denied.poll())

            val (expired, _) = onboarding(init, begin, 400 to """{"error":"expired_token"}""")
            expired.start()
            assertEquals(OnboardingStep.Failed("bridge.onboarding.expired"), expired.poll())

            assertEquals(OnboardingStep.Failed("bridge.onboarding.unsupported"), onboarding(200 to """{"supported_auth_methods":["x"]}""").first.start())
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noQr"), onboarding(init, 200 to "{}").first.start())
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noQr"), onboarding().first.poll())
        }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.feishu.FeishuOnboardingTest' -q`
Expected: 编译失败

- [ ] **Step 3: 实现**

```kotlin
package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.channel.HttpJson
import com.github.izerui.imux.bridge.channel.JdkHttpJson
import com.github.izerui.imux.bridge.channel.OnboardingStep
import com.github.izerui.imux.bridge.json.obj
import com.github.izerui.imux.bridge.json.primitiveText
import com.github.izerui.imux.bridge.json.string
import com.google.gson.JsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 飞书扫码创建应用：OAuth 设备授权流程（官方 SDK RegisterApp；参数与 Hermes、DeepTutor 一致）。
 * 扫码结果带本人 open_id，直接认主；拿不到时返回 ownerId=null，走配对码。
 * 本版不带 addons，依赖 PersonalAgent 默认模板的权限（设计第 4 节）。
 */
class FeishuOnboarding(
    private val http: HttpJson = JdkHttpJson(),
) : ChannelOnboarding {
    @Volatile
    private var interval: Long = DEFAULT_INTERVAL_MILLIS

    override val pollIntervalMillis: Long
        get() = interval

    private var deviceCode: String? = null
    private var domain = FeishuDomain.FEISHU

    override suspend fun start(): OnboardingStep {
        val methods =
            register(listOf("action" to "init"))
                ?.get("supported_auth_methods")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString }
                .orEmpty()
        if ("client_secret" !in methods) return OnboardingStep.Failed("bridge.onboarding.unsupported")
        val begin =
            register(
                listOf(
                    "action" to "begin",
                    "archetype" to "PersonalAgent",
                    "auth_method" to "client_secret",
                    "request_user_info" to "open_id",
                ),
            )
        val code = begin?.string("device_code")
        val qr = begin?.string("verification_uri_complete")
        if (code.isNullOrEmpty() || qr.isNullOrEmpty()) return OnboardingStep.Failed("bridge.onboarding.noQr")
        deviceCode = code
        interval = (begin?.primitiveText("interval")?.toLongOrNull() ?: DEFAULT_INTERVAL_SECONDS) * 1_000
        return OnboardingStep.ShowQr(qr)
    }

    override suspend fun poll(): OnboardingStep {
        val code = deviceCode ?: return OnboardingStep.Failed("bridge.onboarding.noQr")
        val result = register(listOf("action" to "poll", "device_code" to code, "tp" to "ob_app")) ?: return OnboardingStep.Waiting
        val user = result.obj("user_info")
        if (user?.string("tenant_brand") == FeishuDomain.LARK.id) domain = FeishuDomain.LARK
        val appId = result.primitiveText("client_id")
        val secret = result.string("client_secret")
        if (!appId.isNullOrEmpty() && !secret.isNullOrEmpty()) {
            return OnboardingStep.Succeeded(
                credentials =
                    mapOf(
                        FeishuCredentialKeys.APP_ID to appId,
                        FeishuCredentialKeys.APP_SECRET to secret,
                        FeishuCredentialKeys.DOMAIN to domain.id,
                    ),
                ownerId = user?.string("open_id")?.takeIf(String::isNotEmpty),
                suggestedName = null,
            )
        }
        return when (result.string("error")) {
            "slow_down" -> {
                interval += SLOW_DOWN_STEP_MILLIS
                OnboardingStep.Waiting
            }
            "access_denied" -> OnboardingStep.Failed("bridge.onboarding.denied")
            "expired_token" -> OnboardingStep.Failed("bridge.onboarding.expired")
            else -> OnboardingStep.Waiting
        }
    }

    /** 表单 POST；等待态以 4xx 返回，照常读正文。 */
    private suspend fun register(form: List<Pair<String, String>>): JsonObject? {
        val body = form.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return http.post("${domain.accountsBase}/oauth/v1/app/registration", FORM_TYPE, body).json
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val FORM_TYPE = "application/x-www-form-urlencoded"
        const val DEFAULT_INTERVAL_SECONDS = 5L
        const val DEFAULT_INTERVAL_MILLIS = DEFAULT_INTERVAL_SECONDS * 1_000
        const val SLOW_DOWN_STEP_MILLIS = 5_000L
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.feishu.FeishuOnboardingTest' -q`
Expected: PASS

---

### Task 7: 附件下载 `FeishuAttachment`

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuAttachment.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuAttachmentTest.kt`

**Interfaces:**
- Consumes: `HttpFileDownload`（三参数，Task 1）；`FeishuApi.token/invalidateToken/resourceUrl`（Task 5）；`FeishuMediaRef`（Task 4）
- Produces: `class FeishuAttachment(messageId: String, ref: FeishuMediaRef, api: FeishuApi, download: HttpFileDownload) : InboundAttachment`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class FeishuAttachmentTest {
    private val tokens = AtomicInteger()
    private val api =
        FeishuApi("cli", "sec", FeishuDomain.FEISHU, FakeHttpJson { _, _, _ -> 200 to """{"code":0,"tenant_access_token":"T${tokens.incrementAndGet()}","expire":7200}""" })

    private class Recorder(private val results: ArrayDeque<DownloadResult>) : HttpFileDownload {
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()

        override suspend fun download(
            url: String,
            maxBytes: Long,
            headers: Map<String, String>,
        ): DownloadResult {
            requests += url to headers["Authorization"]
            return results.removeFirst()
        }
    }

    private val path = Path.of("/tmp/x")

    @Test
    fun `带令牌下载，文件名优先用消息里的`() =
        runBlocking {
            val download = Recorder(ArrayDeque(listOf(DownloadResult.Ok(path, "header.pdf"))))
            val ref = FeishuMediaRef(AttachmentKind.FILE, "f_1", "file", "报告.pdf")
            assertEquals(FetchResult.Ok(FetchedFile(path, "报告.pdf")), FeishuAttachment("om_1", ref, api, download).fetch(100))
            assertEquals("https://open.feishu.cn/open-apis/im/v1/messages/om_1/resources/f_1?type=file" to "Bearer T1", download.requests.single())
            assertEquals(AttachmentKind.FILE, FeishuAttachment("om_1", ref, api, download).kind)
        }

    @Test
    fun `消息没给文件名时用响应头的`() =
        runBlocking {
            val download = Recorder(ArrayDeque(listOf(DownloadResult.Ok(path, "a.png"))))
            val ref = FeishuMediaRef(AttachmentKind.IMAGE, "img", "image", null)
            assertEquals(FetchResult.Ok(FetchedFile(path, "a.png")), FeishuAttachment("om_1", ref, api, download).fetch(100))
        }

    @Test
    fun `4xx 时重取令牌只重试一次；超限直接返回`() =
        runBlocking {
            val ref = FeishuMediaRef(AttachmentKind.FILE, "f", "file", null)
            val retry = Recorder(ArrayDeque(listOf(DownloadResult.Failed("HTTP 400"), DownloadResult.Failed("HTTP 400"))))
            assertEquals(FetchResult.Failed("HTTP 400"), FeishuAttachment("om", ref, api, retry).fetch(100))
            assertEquals(listOf("Bearer T1", "Bearer T2"), retry.requests.map { it.second })

            val tooLarge = Recorder(ArrayDeque(listOf(DownloadResult.TooLarge)))
            assertEquals(FetchResult.TooLarge, FeishuAttachment("om", ref, api, tooLarge).fetch(100))
        }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.feishu.FeishuAttachmentTest' -q`
Expected: 编译失败

- [ ] **Step 3: 实现**

```kotlin
package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import com.github.izerui.imux.bridge.core.InboundAttachment

/** 飞书消息里的一个附件：只在 [fetch] 时才带令牌下载；内容不加密。 */
class FeishuAttachment(
    private val messageId: String,
    private val ref: FeishuMediaRef,
    private val api: FeishuApi,
    private val download: HttpFileDownload,
) : InboundAttachment {
    override val kind: AttachmentKind
        get() = ref.kind

    override suspend fun fetch(maxBytes: Long): FetchResult {
        var retried = false
        while (true) {
            val token =
                when (val result = api.token()) {
                    is FeishuResult.Ok -> result.value
                    is FeishuResult.Error -> return FetchResult.Failed("token ${result.code}")
                    is FeishuResult.Network -> return FetchResult.Failed(result.reason)
                }
            val url = api.resourceUrl(messageId, ref.fileKey, ref.resourceType)
            when (val result = download.download(url, maxBytes, mapOf("Authorization" to "Bearer $token"))) {
                DownloadResult.TooLarge -> return FetchResult.TooLarge
                is DownloadResult.Ok -> return FetchResult.Ok(FetchedFile(result.path, ref.fileName ?: result.fileName))
                is DownloadResult.Failed -> {
                    // 令牌恰好失效时平台以 4xx 拒绝：清掉令牌重取一次
                    if (!retried && result.reason in RETRY_ON) {
                        retried = true
                        api.invalidateToken()
                        continue
                    }
                    return FetchResult.Failed(result.reason)
                }
            }
        }
    }

    private companion object {
        val RETRY_ON = setOf("HTTP 400", "HTTP 401")
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.feishu.FeishuAttachmentTest' -q`
Expected: PASS

---

### Task 8: 语音转文字（Ogg Opus 解码 + 飞书语音识别）

**Files:**
- Modify: `build.gradle.kts`（`dependencies` 块）
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/OggOpus.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuSpeech.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuApi.kt`（新增 `recognize`）
- Create: `src/test/resources/feishu/tone-16k-mono.opus`、`src/test/resources/feishu/tone-48k-stereo.opus`（用 ffmpeg 生成）
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/OggOpusTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuSpeechTest.kt`

**Interfaces:**
- Consumes: `HttpFileDownload`（Task 1）；`FeishuMediaRef`（Task 4）；`FeishuApi`、`FeishuResult`、`FakeHttpJson`（Task 5）；`FeishuAttachment`（Task 7）
- Produces:
  - 依赖 `io.github.jaredmdobson:concentus:1.0.2`（纯 Java Opus 编解码，BSD 风格许可证，412 KB，无传递依赖）
  - `object OggOpus { const val SAMPLE_RATE = 16_000; fun decode(bytes: ByteArray, maxSamples: Int): ShortArray }`（失败抛 `IllegalArgumentException`）
  - `FeishuApi.recognize(pcm: ByteArray, fileId: String): FeishuResult<String>`
  - `class FeishuSpeech(api: FeishuApi, download: HttpFileDownload, random: Random = SecureRandom(), chunkSamples: Int = 50 * OggOpus.SAMPLE_RATE) { suspend fun transcribe(messageId: String, ref: FeishuMediaRef): String? }`（任何一步失败返回 null）

依据（设计第 2 节）：飞书语音消息是 Opus（上传文件文档要求音频为 OPUS）；识别接口 `POST /open-apis/speech_to_text/v1/speech/file_recognize` 只收 pcm、引擎 `16k_auto`、适合 60 秒以内，请求体 `{"speech":{"speech":<base64>},"config":{"file_id":<16 位字母数字>,"format":"pcm","engine_type":"16k_auto"}}`，返回 `data.recognition_text`；权限 `speech_to_text:speech`。pcm 按 16 位有符号小端处理（文档未写字节序，真机确认）。

- [ ] **Step 1: 加依赖**

`build.gradle.kts` 的 `dependencies` 块，在 ZXing 之后加：

```kotlin
    // 飞书语音消息是 Ogg Opus，而飞书语音识别只收 16k pcm；JDK 解不了 Opus，用纯 Java 的 Concentus（BSD 风格许可证，无传递依赖）。
    implementation("io.github.jaredmdobson:concentus:1.0.2")
```

- [ ] **Step 2: 生成测试样本**

Run:

```bash
mkdir -p src/test/resources/feishu
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=440:sample_rate=16000:duration=1" -c:a libopus -ac 1 -ar 16000 -b:a 24k src/test/resources/feishu/tone-16k-mono.opus
ffmpeg -hide_banner -loglevel error -y -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=1" -c:a libopus -ac 2 -ar 48000 src/test/resources/feishu/tone-48k-stereo.opus
ls -l src/test/resources/feishu
```

Expected: 两个文件，约 4 KB 与 16 KB。（这两个命令与飞书上传文档给出的转换方式同为 libopus + Ogg 封装。）

- [ ] **Step 3: 写 `OggOpus` 的失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class OggOpusTest {
    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/feishu/$name")!!.readBytes()

    private fun rms(samples: ShortArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    @Test
    fun `单声道 16k 解成 1 秒左右的 16k PCM`() {
        val pcm = OggOpus.decode(resource("tone-16k-mono.opus"), maxSamples = 10 * OggOpus.SAMPLE_RATE)
        assertTrue("采样数 ${pcm.size}", pcm.size in 16_000..16_400)
        assertTrue("不能是静音", rms(pcm) > 1_000)
    }

    @Test
    fun `立体声 48k 由解码器直接混成 16k 单声道`() {
        val pcm = OggOpus.decode(resource("tone-48k-stereo.opus"), maxSamples = 10 * OggOpus.SAMPLE_RATE)
        assertTrue("采样数 ${pcm.size}", pcm.size in 16_000..16_400)
        assertTrue("不能是静音", rms(pcm) > 1_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `超过上限报错`() {
        OggOpus.decode(resource("tone-16k-mono.opus"), maxSamples = 8_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `不是 Ogg 时报错`() {
        OggOpus.decode("not an ogg file at all, definitely".toByteArray(), maxSamples = 16_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `截断时报错`() {
        val bytes = resource("tone-16k-mono.opus")
        OggOpus.decode(bytes.copyOf(bytes.size - 10), maxSamples = 10 * OggOpus.SAMPLE_RATE)
    }
}
```

- [ ] **Step 4: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.OggOpusTest' -q`
Expected: 编译失败，`Unresolved reference: OggOpus`

- [ ] **Step 5: 实现 `OggOpus`**

`src/main/kotlin/com/github/izerui/imux/bridge/channel/OggOpus.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel

import io.github.jaredmdobson.concentus.OpusDecoder
import java.io.ByteArrayOutputStream

/**
 * 把 Ogg 封装的 Opus 语音解码成 16 kHz 单声道 16 位 PCM，供只收 pcm 的语音识别接口使用。
 * 只处理单个逻辑流（语音消息就是这样）；立体声由解码器直接混成单声道。
 * 无法解析、解码失败或超过上限时抛 [IllegalArgumentException]。
 */
object OggOpus {
    const val SAMPLE_RATE = 16_000

    /** Opus 一帧最长 120 毫秒。 */
    private const val MAX_FRAME_SAMPLES = SAMPLE_RATE * 120 / 1_000

    private val CAPTURE = "OggS".toByteArray(Charsets.US_ASCII)

    fun decode(
        bytes: ByteArray,
        maxSamples: Int,
    ): ShortArray {
        val packets = packets(bytes)
        require(packets.size >= 2) { "不是 Ogg Opus" }
        val head = packets[0]
        require(head.size >= 19 && String(head, 0, 8, Charsets.US_ASCII) == "OpusHead") { "缺少 OpusHead" }
        // pre-skip 按 48 kHz 计，是编码器在开头垫的采样，要丢掉
        var skip = ((head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)) * SAMPLE_RATE / 48_000
        val decoder =
            try {
                OpusDecoder(SAMPLE_RATE, 1)
            } catch (e: Exception) {
                throw IllegalArgumentException("无法创建解码器", e)
            }
        val frame = ShortArray(MAX_FRAME_SAMPLES)
        var output = ShortArray(SAMPLE_RATE)
        var size = 0
        // packets[1] 是 OpusTags，跳过
        for (index in 2 until packets.size) {
            val packet = packets[index]
            if (packet.isEmpty()) continue
            val count =
                try {
                    decoder.decode(packet, 0, packet.size, frame, 0, MAX_FRAME_SAMPLES, false)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Opus 解码失败", e)
                }
            val start = minOf(skip, count)
            skip -= start
            val kept = count - start
            require(size + kept <= maxSamples) { "语音过长" }
            if (size + kept > output.size) output = output.copyOf(maxOf(output.size * 2, size + kept))
            frame.copyInto(output, size, start, count)
            size += kept
        }
        return output.copyOf(size)
    }

    /** 按页拆出数据包：段长为 255 表示这个数据包在下一段继续。 */
    private fun packets(bytes: ByteArray): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val current = ByteArrayOutputStream()
        var position = 0
        while (position < bytes.size) {
            require(position + 27 <= bytes.size && bytes.copyOfRange(position, position + 4).contentEquals(CAPTURE)) { "不是 Ogg 页" }
            val segments = bytes[position + 26].toInt() and 0xFF
            var data = position + 27 + segments
            require(data <= bytes.size) { "Ogg 页被截断" }
            for (segment in 0 until segments) {
                val length = bytes[position + 27 + segment].toInt() and 0xFF
                require(data + length <= bytes.size) { "Ogg 页被截断" }
                current.write(bytes, data, length)
                data += length
                if (length < 255) {
                    packets += current.toByteArray()
                    current.reset()
                }
            }
            position = data
        }
        require(current.size() == 0) { "最后一个数据包不完整" }
        return packets
    }
}
```

- [ ] **Step 6: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.OggOpusTest' -q`
Expected: PASS

- [ ] **Step 7: 写 `FeishuSpeech` 的失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

class FeishuSpeechTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val tone = javaClass.getResourceAsStream("/feishu/tone-16k-mono.opus")!!.readBytes()
    private val ref = FeishuMediaRef(AttachmentKind.AUDIO, "a_1", "file", null)
    private val chunks = CopyOnWriteArrayList<Int>()
    private val configs = CopyOnWriteArrayList<com.google.gson.JsonObject>()
    private val downloaded = CopyOnWriteArrayList<Path>()

    private fun http(
        code: Int,
        texts: ArrayDeque<String>,
    ) = FakeHttpJson { _, url, body ->
        when {
            url.endsWith("/tenant_access_token/internal") -> 200 to """{"code":0,"tenant_access_token":"T","expire":7200}"""
            url.endsWith("/speech_to_text/v1/speech/file_recognize") -> {
                val json = JsonParser.parseString(body).asJsonObject
                chunks += Base64.getDecoder().decode(json["speech"].asJsonObject["speech"].asString).size
                configs += json["config"].asJsonObject
                200 to """{"code":$code,"msg":"m","data":{"recognition_text":"${texts.removeFirstOrNull().orEmpty()}"}}"""
            }
            else -> 404 to "{}"
        }
    }

    private fun download(bytes: ByteArray) =
        HttpFileDownload { _, _, _ ->
            val file = temp.newFile().toPath()
            Files.write(file, bytes)
            downloaded += file
            DownloadResult.Ok(file, null)
        }

    private fun speech(
        code: Int = 0,
        texts: List<String> = emptyList(),
        bytes: ByteArray = tone,
        chunkSamples: Int = 50 * 16_000,
    ) = FeishuSpeech(FeishuApi("cli", "sec", FeishuDomain.FEISHU, http(code, ArrayDeque(texts))), download(bytes), chunkSamples = chunkSamples)

    @Test
    fun `解码后按段识别并按顺序拼接`() =
        runBlocking {
            assertEquals("你好世界", speech(texts = listOf("你好", "世界", ""), chunkSamples = 8_000).transcribe("om_1", ref))
            assertEquals(3, chunks.size)
            assertTrue(chunks.all { it <= 8_000 * 2 })
            assertTrue("总字节 ${chunks.sum()}", chunks.sum() in 32_000..32_800)
            configs.forEach {
                assertEquals("pcm", it["format"].asString)
                assertEquals("16k_auto", it["engine_type"].asString)
                assertTrue(Regex("[a-z0-9]{16}").matches(it["file_id"].asString))
            }
            assertFalse("临时文件要删掉", Files.exists(downloaded.single()))
        }

    @Test
    fun `识别接口报错时返回 null`() =
        runBlocking {
            assertNull(speech(code = 99991672).transcribe("om_1", ref))
        }

    @Test
    fun `识别结果为空时返回 null`() =
        runBlocking {
            assertNull(speech(texts = listOf("  ")).transcribe("om_1", ref))
        }

    @Test
    fun `不是 Opus 时返回 null，临时文件也删掉`() =
        runBlocking {
            assertNull(speech(bytes = "garbage".toByteArray()).transcribe("om_1", ref))
            assertFalse(Files.exists(downloaded.single()))
            assertTrue(chunks.isEmpty())
        }
}
```

- [ ] **Step 8: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.feishu.FeishuSpeechTest' -q`
Expected: 编译失败，`Unresolved reference: FeishuSpeech`

- [ ] **Step 9: `FeishuApi` 加 `recognize`**

在 `FeishuApi` 的 `messageText` 之后加：

```kotlin
    /** 识别一段 16k 单声道 16 位小端 pcm；[fileId] 是 16 位字母数字标识。 */
    suspend fun recognize(
        pcm: ByteArray,
        fileId: String,
    ): FeishuResult<String> {
        val body = JsonObject().apply {
            add("speech", JsonObject().apply { addProperty("speech", Base64.getEncoder().encodeToString(pcm)) })
            add(
                "config",
                JsonObject().apply {
                    addProperty("file_id", fileId)
                    addProperty("format", "pcm")
                    addProperty("engine_type", "16k_auto")
                },
            )
        }
        return when (val result = authorized { http.post("${domain.openBase}/open-apis/speech_to_text/v1/speech/file_recognize", JSON_TYPE, body.toString(), bearer(it)) }) {
            is FeishuResult.Ok -> FeishuResult.Ok(result.value.obj("data")?.string("recognition_text").orEmpty())
            is FeishuResult.Error -> result
            is FeishuResult.Network -> result
        }
    }
```

import 加 `java.util.Base64`。

- [ ] **Step 10: 实现 `FeishuSpeech`**

```kotlin
package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.channel.OggOpus
import com.github.izerui.imux.bridge.core.FetchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Random
import kotlin.coroutines.cancellation.CancellationException

/**
 * 飞书语音转文字：下载语音 → 解 Ogg Opus 成 16k pcm → 按段调用飞书语音识别 → 按顺序拼接。
 * 识别接口适合 60 秒以内，所以默认按 50 秒切段。任何一步失败（没权限、额度、解码、识别为空）都返回 null，
 * 调用方退回“作为音频附件转交”，消息照常送达。
 */
class FeishuSpeech(
    private val api: FeishuApi,
    private val download: HttpFileDownload,
    private val random: Random = SecureRandom(),
    private val chunkSamples: Int = 50 * OggOpus.SAMPLE_RATE,
) {
    suspend fun transcribe(
        messageId: String,
        ref: FeishuMediaRef,
    ): String? {
        val fetched = FeishuAttachment(messageId, ref, api, download).fetch(MAX_AUDIO_BYTES)
        val file = (fetched as? FetchResult.Ok)?.file ?: return null
        val pcm =
            try {
                withContext(Dispatchers.IO) { OggOpus.decode(Files.readAllBytes(file.path), MAX_SAMPLES) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { Files.deleteIfExists(file.path) }
            }
        if (pcm.isEmpty()) return null
        val texts = mutableListOf<String>()
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(pcm.size, offset + chunkSamples)
            when (val result = api.recognize(littleEndian(pcm, offset, end), fileId())) {
                is FeishuResult.Ok -> texts += result.value.trim()
                is FeishuResult.Error -> return null
                is FeishuResult.Network -> return null
            }
            offset = end
        }
        return texts.joinToString("").takeIf(String::isNotBlank)
    }

    private fun littleEndian(
        pcm: ShortArray,
        from: Int,
        to: Int,
    ): ByteArray {
        val bytes = ByteArray((to - from) * 2)
        for (index in from until to) {
            val value = pcm[index].toInt()
            val at = (index - from) * 2
            bytes[at] = value.toByte()
            bytes[at + 1] = (value shr 8).toByte()
        }
        return bytes
    }

    private fun fileId(): String = String(CharArray(16) { FILE_ID_CHARS[random.nextInt(FILE_ID_CHARS.length)] })

    private companion object {
        const val FILE_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** 语音消息不会很大；20 MB 足够容纳十分钟以上的 Opus。 */
        const val MAX_AUDIO_BYTES = 20L * 1024 * 1024

        /** 最长转写 10 分钟，再长就退回附件，避免一条语音占用过多识别调用。 */
        const val MAX_SAMPLES = 10 * 60 * OggOpus.SAMPLE_RATE
    }
}
```

- [ ] **Step 11: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.feishu.FeishuSpeechTest' --tests 'com.github.izerui.imux.bridge.channel.OggOpusTest' --tests 'com.github.izerui.imux.bridge.channel.ChannelIsolationSourceTest' -q`
Expected: PASS

---

### Task 9: 连接 `FeishuConnection`

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuConnection.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuConnectionTest.kt`

**Interfaces:**
- Consumes: `FrameTransport`（含 `sendBinary`）、`FrameListener.onBinary`、`WebSocketHandshakeRejected`、`JdkWebSocketTransport`、`HttpFileDownload`、`JdkHttpFileDownload`、`SlidingWindowLimiter`（Task 1 / 现有）；`ConnectionStatus.SendFailing`（Task 2）；`FeishuFrame`、`FeishuHeader`、`FeishuFrameCodec`（Task 3）；`FeishuEvents`、`FeishuMessage`（Task 4）；`FeishuApi`、`FeishuResult`、`FeishuCodes`、`FeishuClientConfig`（Task 5）；`FeishuAttachment`（Task 7）；`FeishuSpeech`（Task 8）
- Produces: `class FeishuConnection(robotId: RobotId, api: FeishuApi, scope: CoroutineScope, transportFactory: () -> FrameTransport = { JdkWebSocketTransport() }, download: HttpFileDownload = JdkHttpFileDownload(), speech: FeishuSpeech = FeishuSpeech(api, download), now: () -> Long = System::currentTimeMillis, random: Random = SecureRandom(), secondMillis: Long = 1_000, limiter: SlidingWindowLimiter = SlidingWindowLimiter(5, 1_000, now), maxRateWaitMillis: Long = 20_000) : ChannelConnection`；`maxTextBytes = 15_000`；SendFailing 文案键 `bridge.feishu.quota`、`bridge.feishu.permission`、`bridge.feishu.app`（文案在 Task 10 加入）

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.FrameListener
import com.github.izerui.imux.bridge.channel.FrameTransport
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.channel.WebSocketHandshakeRejected
import com.github.izerui.imux.bridge.core.DeliveryResult
import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.github.izerui.imux.bridge.core.AttachmentKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
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
    ) = """{"code":$code,"msg":"e","data":{"URL":"wss://x/ws?device_id=d&service_id=7","ClientConfig":{"PingInterval":$pingSeconds,"ReconnectInterval":0,"ReconnectNonce":0,"ReconnectCount":-1}}}"""

    private fun connection(reject: WebSocketHandshakeRejected? = null) =
        FeishuConnection(
            robotId = RobotId("r"),
            api = FeishuApi("cli", "sec", FeishuDomain.FEISHU, http),
            scope = scope,
            transportFactory = { FakeTransport(opens, reject).also { transports += it } },
            download =
                HttpFileDownload { _, _, _ ->
                    val file = temp.newFile().toPath()
                    Files.write(file, javaClass.getResourceAsStream("/feishu/tone-16k-mono.opus")!!.readBytes())
                    DownloadResult.Ok(file, null)
                },
            random = Random(7),
            secondMillis = 20,
        )

    private fun start(conn: FeishuConnection) = conn.start(InboundSink { received += it }) { statuses += it }

    private fun startAndAwaitOnline(conn: FeishuConnection) =
        runBlocking {
            start(conn)
            withTimeout(2_000) { while (ConnectionStatus.Online !in statuses) delay(5) }
        }

    private fun textEvent(
        messageId: String,
        text: String,
        parentId: String? = null,
    ) = """{"schema":"2.0","header":{"event_type":"im.message.receive_v1"},"event":{"sender":{"sender_id":{"open_id":"ou_me"},"sender_type":"user"},"message":{"message_id":"$messageId","chat_type":"p2p","message_type":"text","content":"{\"text\":\"$text\"}"${parentId?.let { ",\"parent_id\":\"$it\"" }.orEmpty()}}}}"""

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
            quoteDelayMillis = 500
            startAndAwaitOnline(connection())
            val transport = transports.first()
            transport.emit(dataFrame("f1", textEvent("om_1", "这个呢", parentId = "om_0")))
            withTimeout(300) { while (acks(transport).isEmpty()) delay(5) }
            assertTrue(received.isEmpty())
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
            start(connection(WebSocketHandshakeRejected(400, mapOf("handshake-status" to "514", "handshake-autherrcode" to "1000040350", "handshake-msg" to "limit"))))
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
            assertEquals(ConnectionStatus.SendFailing("bridge.feishu.quota"), statuses.last())
            sendCode = 0
            assertEquals(DeliveryResult.Accepted, conn.send(OutboundMessage(RobotId("r"), "ou_me", "结论")))
            assertEquals(ConnectionStatus.Online, statuses.last())
        }

    @Test
    fun `缺权限带上错误码；限频不改状态`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            sendCode = 230013
            conn.send(OutboundMessage(RobotId("r"), "ou_me", "x"))
            assertEquals(ConnectionStatus.SendFailing("bridge.feishu.permission", "230013"), statuses.last())
            sendCode = 99991400
            assertEquals(DeliveryResult.Rejected("rate limited"), conn.send(OutboundMessage(RobotId("r"), "ou_me", "x")))
            assertEquals(ConnectionStatus.SendFailing("bridge.feishu.permission", "230013"), statuses.last())
        }

    @Test
    fun `正文转义后超过 30 KB 时不发`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            val before = http.calls.size
            assertEquals(DeliveryResult.Rejected("too large"), conn.send(OutboundMessage(RobotId("r"), "ou_me", "\"".repeat(9_000))))
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
            conn.stop()
            assertEquals(ConnectionStatus.Stopped, statuses.last())
            assertEquals(DeliveryResult.Rejected("offline"), conn.send(OutboundMessage(RobotId("r"), "ou_me", "x")))
        }
}
```

（`"\"".repeat(9_000)` 只有 9 000 字节，但在两层 JSON 里每个引号变成 `\\\"` 共 4 字节，请求体约 36 KB，超过 30 KB。）

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.feishu.FeishuConnectionTest' -q`
Expected: 编译失败

- [ ] **Step 3: 实现**

```kotlin
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Random
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * 飞书长连接（docs/superpowers/specs/2026-10-28-feishu-channel-design.md 第 5 至 7 节）。
 *
 * 收消息走 WebSocket（protobuf 帧），发消息走 HTTP，两者互不依赖：长连接重连期间照样能发。
 * 收到事件先回写确认再处理，确认不等取引用、不等终端；之后由单个协程按到达顺序交给 [InboundSink]。
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

    private val seen = LinkedHashSet<String>()
    private val partials = HashMap<String, Partial>()

    @Volatile
    private var config = FeishuClientConfig.DEFAULT

    @Volatile
    private var session: SessionState? = null

    @Volatile
    private var job: Job? = null

    @Volatile
    private var activeListener: ConnectionListener = ConnectionListener { }

    @Volatile
    private var lastStatus: ConnectionStatus? = null

    override fun start(
        sink: InboundSink,
        listener: ConnectionListener,
    ) {
        if (job != null) return
        activeListener = listener
        job = scope.launch { runLoop(sink) }
    }

    override fun stop() {
        val j = job ?: return
        job = null
        j.cancel()
        session?.transport?.close()
        session = null
        report(ConnectionStatus.Stopped)
    }

    override suspend fun send(message: OutboundMessage): DeliveryResult {
        if (job == null) return DeliveryResult.Rejected("offline")
        var wait = limiter.tryAcquire(message.chatId)
        if (wait > 0) {
            if (wait > maxRateWaitMillis) return DeliveryResult.Rejected("rate limited")
            delay(wait)
            wait = limiter.tryAcquire(message.chatId)
            if (wait > 0) return DeliveryResult.Rejected("rate limited")
        }
        val body = FeishuApi.markdownBody(message.chatId, message.text)
        if (body.toByteArray(StandardCharsets.UTF_8).size > FeishuApi.MAX_POST_BODY_BYTES) return DeliveryResult.Rejected("too large")
        return when (val result = api.send(body)) {
            is FeishuResult.Ok -> {
                if (lastStatus is ConnectionStatus.SendFailing && job != null) report(ConnectionStatus.Online)
                DeliveryResult.Accepted
            }
            is FeishuResult.Network -> DeliveryResult.Unknown
            is FeishuResult.Error -> {
                sendFailing(result.code)?.let { if (job != null) report(it) }
                if (result.code in FeishuCodes.RATE_LIMITED) DeliveryResult.Rejected("rate limited") else DeliveryResult.Rejected("${result.code} ${result.msg}".trim())
            }
        }
    }

    private fun sendFailing(code: Int): ConnectionStatus.SendFailing? =
        when {
            code == FeishuCodes.QUOTA_EXCEEDED -> ConnectionStatus.SendFailing("bridge.feishu.quota")
            code in FeishuCodes.NO_PERMISSION -> ConnectionStatus.SendFailing("bridge.feishu.permission", code.toString())
            code in FeishuCodes.APP_UNAVAILABLE -> ConnectionStatus.SendFailing("bridge.feishu.app", code.toString())
            else -> null
        }

    private fun report(status: ConnectionStatus) {
        lastStatus = status
        activeListener.statusChanged(status)
    }

    /**
     * 重连节奏遵循服务端参数：断线后先随机等待 [0, ReconnectNonce) 秒，之后每次间隔 ReconnectInterval 秒；
     * ReconnectCount ≥ 0 时用完次数即失败。重连本身失败过一次才报“连接失败，正在重试”。
     */
    private suspend fun runLoop(sink: InboundSink) {
        coroutineScope {
            val inbound = Channel<FeishuMessage>(Channel.UNLIMITED)
            launch { for (message in inbound) deliver(message, sink) }
            var reconnects = 0
            var lastFailure: String? = null
            var first = true
            while (isActive) {
                if (!first) {
                    val current = config
                    if (current.reconnectCount >= 0 && reconnects >= current.reconnectCount) {
                        report(ConnectionStatus.Failed(lastFailure ?: "unreachable"))
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
                val failure = lastFailure
                report(if (reconnects >= 2 && failure != null) ConnectionStatus.Retrying(failure) else ConnectionStatus.Connecting)
                when (val end = runSession(inbound) {
                    reconnects = 0
                    lastFailure = null
                }) {
                    is SessionEnd.Fatal -> {
                        if (isActive) report(ConnectionStatus.Failed(end.reason))
                        break
                    }
                    is SessionEnd.Dropped -> lastFailure = end.reason
                }
            }
            inbound.close()
        }
    }

    private suspend fun runSession(
        inbound: SendChannel<FeishuMessage>,
        onOnline: () -> Unit,
    ): SessionEnd {
        val endpoint =
            when (val result = api.endpoint()) {
                is FeishuResult.Ok -> result.value
                is FeishuResult.Network -> return SessionEnd.Dropped(result.reason)
                is FeishuResult.Error -> {
                    val reason = "${result.code} ${result.msg}".trim()
                    return if (result.code in FeishuCodes.ENDPOINT_RETRYABLE) SessionEnd.Dropped(reason) else SessionEnd.Fatal(reason)
                }
            }
        config = endpoint.config
        val serviceId = queryParam(endpoint.url, "service_id")?.toIntOrNull() ?: return SessionEnd.Dropped("no service_id")
        val transport = transportFactory()
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
            coroutineContext.ensureActive()
            session = state
            onOnline()
            report(ConnectionStatus.Online)
            return coroutineScope {
                val heartbeat = launch { heartbeat(state, ended) }
                val end = ended.await()
                heartbeat.cancel()
                end
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SessionEnd.Dropped(e.message ?: e.javaClass.simpleName)
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

    /** 每个心跳周期发一次 ping；超过 [DEAD_AFTER_PINGS] 个周期没收到任何帧，判定连接已死（官方 SDK 没有这条，是我们加的保护）。 */
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
            val ping = FeishuFrame(service = state.serviceId, method = FeishuFrame.METHOD_CONTROL, headers = listOf(FeishuHeader("type", "ping")))
            try {
                state.transport.sendBinary(FeishuFrameCodec.encode(ping))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 发不出去由存活检查兜底
            }
        }
    }

    private fun onFrame(
        state: SessionState,
        bytes: ByteArray,
        inbound: SendChannel<FeishuMessage>,
    ) {
        val frame = runCatching { FeishuFrameCodec.decode(bytes) }.getOrNull() ?: return
        when (frame.method) {
            FeishuFrame.METHOD_CONTROL -> if (frame.header("type") == "pong") pongConfig(frame)?.let { config = it }
            FeishuFrame.METHOD_DATA -> onData(state, frame, inbound)
        }
    }

    private fun pongConfig(frame: FeishuFrame): FeishuClientConfig? {
        val payload = frame.payload?.takeIf { it.isNotEmpty() } ?: return null
        val json = runCatching { JsonParser.parseString(String(payload, StandardCharsets.UTF_8)) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return FeishuClientConfig.from(json, config)
    }

    /** card 类型与官方 SDK 一样不处理、不回写。 */
    private fun onData(
        state: SessionState,
        frame: FeishuFrame,
        inbound: SendChannel<FeishuMessage>,
    ) {
        if (frame.header("type") != "event") return
        val frameId = frame.header("message_id") ?: return
        val sum = frame.header("sum")?.toIntOrNull() ?: 1
        val payload =
            if (sum > 1) {
                combine(frameId, sum, frame.header("seq")?.toIntOrNull() ?: -1, frame.payload ?: ByteArray(0)) ?: return
            } else {
                frame.payload ?: ByteArray(0)
            }
        acknowledge(state, frame)
        val message = FeishuEvents.parseMessage(String(payload, StandardCharsets.UTF_8)) ?: return
        if (markSeen(message.messageId)) inbound.trySend(message)
    }

    private fun acknowledge(
        state: SessionState,
        frame: FeishuFrame,
    ) {
        val ack = frame.withHeader("biz_rt", "0").copy(payload = ACK_PAYLOAD)
        scope.launch {
            try {
                state.transport.sendBinary(FeishuFrameCodec.encode(ack))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 确认没发出去，平台会重投，靠消息 id 去重
            }
        }
    }

    /** 与官方 SDK 一样按 seq 放进缓存，5 秒内拼齐才交出。 */
    private fun combine(
        frameId: String,
        sum: Int,
        seq: Int,
        part: ByteArray,
    ): ByteArray? =
        synchronized(partials) {
            val current = now()
            partials.entries.removeIf { current - it.value.createdAt > COMBINE_TTL_MILLIS }
            if (seq !in 0 until sum) return null
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
        try {
            val quote = message.parentId?.let { api.messageText(it) }
            // 语音先交给飞书识别；识别成功就当作本人发的文字（与企业微信的语音转写一致），失败则作为音频附件转交
            val voice = message.media.firstOrNull { it.kind == AttachmentKind.AUDIO }
            val transcript = voice?.let { speech.transcribe(message.messageId, it) }
            val media = if (transcript != null) message.media.filter { it !== voice } else message.media
            val text = listOfNotNull(message.text, transcript).joinToString("\n")
            val attachments = media.map { FeishuAttachment(message.messageId, it, api, download) }
            sink.accept(InboundMessage(robotId, message.messageId, message.senderOpenId, message.senderOpenId, text, attachments, quote))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 一条消息出错不能让后续消息停摆
        }
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
        val ACK_PAYLOAD = """{"code":200}""".toByteArray(StandardCharsets.UTF_8)
        const val DEAD_AFTER_PINGS = 3
        const val COMBINE_TTL_MILLIS = 5_000L
        const val SEEN_CAPACITY = 1000
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.feishu.FeishuConnectionTest' -q`
Expected: PASS。若某个用例偶发超时，先检查是不是心跳把 `secondMillis=20` 下的 120 秒默认间隔缩成 2.4 秒导致与断言竞争；需要稳定的用例都用 `endpoint(pingSeconds = 1_000)`（20 秒）。

---

### Task 10: 工厂登记、扫码说明与文案

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/feishu/FeishuChannelFactory.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/channel/ChannelFactories.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/ui/AddRobotDialog.kt:269`
- Modify: `src/main/resources/messages/ImuxBundle.properties` 及其余 9 个语言文件
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/ChannelFactoriesTest.kt`

**Interfaces:**
- Consumes: `ChannelFactory.scanStepsKey`（Task 2）；`FeishuOnboarding`（Task 6）；`FeishuConnection`（Task 9）；`FeishuApi`、`FeishuDomain`、`FeishuCredentialKeys`
- Produces: `object FeishuChannelFactory : ChannelFactory`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel

import com.github.izerui.imux.bridge.channel.feishu.FeishuChannelFactory
import com.github.izerui.imux.bridge.channel.feishu.FeishuConnection
import com.github.izerui.imux.bridge.channel.feishu.FeishuOnboarding
import com.github.izerui.imux.bridge.channel.wecom.WecomChannelFactory
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelFactoriesTest {
    @Test
    fun `飞书已登记，扫码说明用自己的文案`() {
        assertEquals(FeishuChannelFactory, ChannelFactories.forKind(ChannelKind.FEISHU))
        assertEquals("bridge.add.scan.steps.feishu", FeishuChannelFactory.scanStepsKey)
        assertEquals("bridge.add.scan.steps", WecomChannelFactory.scanStepsKey)
        assertTrue(FeishuChannelFactory.onboarding() is FeishuOnboarding)
    }

    @Test
    fun `按凭据建立飞书连接`() {
        val connection =
            FeishuChannelFactory.connect(RobotId("r"), mapOf("app_id" to "cli", "app_secret" to "s", "domain" to "lark"), CoroutineScope(Dispatchers.Default))
        assertTrue(connection is FeishuConnection)
        assertEquals(RobotId("r"), connection.robotId)
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.ChannelFactoriesTest' -q`
Expected: 编译失败

- [ ] **Step 3: 实现工厂与登记**

`FeishuChannelFactory.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ChannelFactory
import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CoroutineScope

object FeishuChannelFactory : ChannelFactory {
    override val kind: ChannelKind = ChannelKind.FEISHU

    /** 扫码直接认主，不需要配对码，说明文字与企业微信不同。 */
    override val scanStepsKey: String = "bridge.add.scan.steps.feishu"

    override fun onboarding(): ChannelOnboarding = FeishuOnboarding()

    override fun connect(
        robotId: RobotId,
        credentials: Map<String, String>,
        scope: CoroutineScope,
    ): ChannelConnection =
        FeishuConnection(
            robotId = robotId,
            api =
                FeishuApi(
                    appId = credentials.getValue(FeishuCredentialKeys.APP_ID),
                    appSecret = credentials.getValue(FeishuCredentialKeys.APP_SECRET),
                    domain = FeishuDomain.of(credentials[FeishuCredentialKeys.DOMAIN]),
                ),
            scope = scope,
        )
}
```

`ChannelFactories.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel

import com.github.izerui.imux.bridge.channel.feishu.FeishuChannelFactory
import com.github.izerui.imux.bridge.channel.wecom.WecomChannelFactory

/** 已实现的渠道。新增渠道只需在这里登记一个工厂。 */
object ChannelFactories {
    fun all(): List<ChannelFactory> = listOf(WecomChannelFactory, FeishuChannelFactory)

    fun forKind(kind: ChannelKind): ChannelFactory? = all().firstOrNull { it.kind == kind }
}
```

（原文件里多余的 `import com.github.izerui.imux.bridge.channel.ChannelKind` 同包无需导入，一并去掉。）

`AddRobotDialog.kt:269` 改为：

```kotlin
        qrSteps.text = html(ImuxBundle.message(ChannelFactories.forKind(kind)?.scanStepsKey ?: "bridge.add.scan.steps", channelLabel(kind)))
```

- [ ] **Step 4: 加入 10 种语言的文案**

新建一次性脚本 `/tmp/add_feishu_texts.py`（不放进仓库），在每个语言文件的 `bridge.add.scan.steps=` 行之后插入扫码说明，在 `bridge.onboarding.network=` 行之后插入其余 6 个键：

```python
import pathlib, sys

root = pathlib.Path("src/main/resources/messages")
texts = {
    "": [
        "1. Open {0} on your phone and scan\\n2. Confirm creating the app\\n3. Setup finishes automatically, no pairing code needed",
        "This platform does not support creating a bot by scanning.",
        "Authorization was declined on the phone.",
        "The QR code has expired. Please try again.",
        "This month's Feishu API quota is used up; it resets on the 1st. Messages from your phone still reach the terminal.",
        "The Feishu app lacks permission to send messages, or you are outside its availability ({0})",
        "The Feishu app is unavailable ({0}). Remove it and scan again.",
    ],
    "_zh_CN": [
        "1. 打开手机{0}，扫一扫\\n2. 确认创建应用\\n3. 扫码后自动完成，无需配对码",
        "该平台暂不支持扫码创建机器人。",
        "已在手机上取消授权。",
        "二维码已过期，请重试。",
        "本月飞书 API 调用额度已用完，下月 1 日恢复；手机消息仍会转交给终端。",
        "飞书应用缺少发消息权限，或你不在应用可用范围内（{0}）",
        "飞书应用不可用（{0}），请移除后重新扫码。",
    ],
    "_zh_TW": [
        "1. 打開手機{0}，掃一掃\\n2. 確認建立應用\\n3. 掃碼後自動完成，無需配對碼",
        "該平台暫不支援掃碼建立機器人。",
        "已在手機上取消授權。",
        "二維碼已過期，請重試。",
        "本月飛書 API 呼叫額度已用完，下月 1 日恢復；手機訊息仍會轉交給終端。",
        "飛書應用缺少發訊息權限，或你不在應用可用範圍內（{0}）",
        "飛書應用無法使用（{0}），請移除後重新掃碼。",
    ],
    "_ja": [
        "1. スマートフォンで {0} を開いてスキャン\\n2. アプリの作成を確認\\n3. スキャン後は自動で完了（ペアリングコード不要）",
        "このプラットフォームはスキャンによるボット作成に対応していません。",
        "スマートフォンで承認が取り消されました。",
        "QR コードの有効期限が切れました。もう一度お試しください。",
        "今月の Feishu API 呼び出し枠を使い切りました。翌月 1 日に回復します。スマートフォンからのメッセージは引き続きターミナルに届きます。",
        "Feishu アプリにメッセージ送信の権限がないか、利用範囲外です（{0}）",
        "Feishu アプリを利用できません（{0}）。削除してもう一度スキャンしてください。",
    ],
    "_ko": [
        "1. 휴대전화에서 {0}을(를) 열고 스캔\\n2. 앱 생성 확인\\n3. 스캔 후 자동으로 완료되며 페어링 코드가 필요 없음",
        "이 플랫폼은 스캔으로 봇을 만드는 기능을 지원하지 않습니다.",
        "휴대전화에서 승인이 취소되었습니다.",
        "QR 코드가 만료되었습니다. 다시 시도하세요.",
        "이번 달 Feishu API 호출 한도를 모두 사용했습니다. 다음 달 1일에 복구됩니다. 휴대전화 메시지는 계속 터미널로 전달됩니다.",
        "Feishu 앱에 메시지 전송 권한이 없거나 사용 범위에 포함되지 않았습니다({0})",
        "Feishu 앱을 사용할 수 없습니다({0}). 제거한 뒤 다시 스캔하세요.",
    ],
    "_de": [
        "1. {0} auf dem Telefon öffnen und scannen\\n2. Das Erstellen der App bestätigen\\n3. Der Rest läuft automatisch, kein Kopplungscode nötig",
        "Diese Plattform unterstützt das Erstellen eines Bots per Scan nicht.",
        "Die Autorisierung wurde auf dem Telefon abgelehnt.",
        "Der QR-Code ist abgelaufen. Bitte erneut versuchen.",
        "Das monatliche Feishu-API-Kontingent ist aufgebraucht; es wird am 1. zurückgesetzt. Nachrichten vom Telefon erreichen weiterhin das Terminal.",
        "Der Feishu-App fehlt die Sendeberechtigung, oder du bist nicht im Verfügbarkeitsbereich ({0})",
        "Die Feishu-App ist nicht verfügbar ({0}). Entferne sie und scanne erneut.",
    ],
    "_fr": [
        "1. Ouvrez {0} sur votre téléphone et scannez\\n2. Confirmez la création de l’application\\n3. La suite est automatique, aucun code d’appairage requis",
        "Cette plateforme ne permet pas de créer un bot par scan.",
        "L’autorisation a été refusée sur le téléphone.",
        "Le QR code a expiré. Veuillez réessayer.",
        "Le quota mensuel de l’API Feishu est épuisé ; il sera rétabli le 1er. Les messages du téléphone parviennent toujours au terminal.",
        "L’application Feishu n’a pas l’autorisation d’envoyer des messages, ou vous n’êtes pas dans sa portée ({0})",
        "L’application Feishu est indisponible ({0}). Supprimez-la et scannez à nouveau.",
    ],
    "_es": [
        "1. Abre {0} en el teléfono y escanea\\n2. Confirma la creación de la aplicación\\n3. El resto es automático, sin código de emparejamiento",
        "Esta plataforma no permite crear un bot escaneando.",
        "Se rechazó la autorización en el teléfono.",
        "El código QR caducó. Inténtalo de nuevo.",
        "Se agotó la cuota mensual de la API de Feishu; se restablece el día 1. Los mensajes del teléfono siguen llegando a la terminal.",
        "La aplicación de Feishu no tiene permiso para enviar mensajes o no estás dentro de su ámbito ({0})",
        "La aplicación de Feishu no está disponible ({0}). Elimínala y vuelve a escanear.",
    ],
    "_pt_BR": [
        "1. Abra o {0} no celular e escaneie\\n2. Confirme a criação do aplicativo\\n3. O restante é automático, sem código de pareamento",
        "Esta plataforma não permite criar um bot pela leitura de QR code.",
        "A autorização foi recusada no celular.",
        "O QR code expirou. Tente novamente.",
        "A cota mensal da API do Feishu acabou; ela é renovada no dia 1º. As mensagens do celular continuam chegando ao terminal.",
        "O aplicativo do Feishu não tem permissão para enviar mensagens, ou você está fora do escopo dele ({0})",
        "O aplicativo do Feishu está indisponível ({0}). Remova-o e escaneie novamente.",
    ],
    "_ru": [
        "1. Откройте {0} на телефоне и отсканируйте код\\n2. Подтвердите создание приложения\\n3. Дальше всё произойдёт автоматически, код привязки не нужен",
        "Эта платформа не поддерживает создание бота по QR-коду.",
        "Авторизация отклонена на телефоне.",
        "Срок действия QR-кода истёк. Попробуйте ещё раз.",
        "Месячная квота API Feishu исчерпана; она обновится 1-го числа. Сообщения с телефона по-прежнему доходят до терминала.",
        "У приложения Feishu нет права отправлять сообщения, или вы вне его области доступности ({0})",
        "Приложение Feishu недоступно ({0}). Удалите его и отсканируйте код снова.",
    ],
}
keys = ["bridge.onboarding.unsupported", "bridge.onboarding.denied", "bridge.onboarding.expired",
        "bridge.feishu.quota", "bridge.feishu.permission", "bridge.feishu.app"]
for suffix, values in texts.items():
    path = root / f"ImuxBundle{suffix}.properties"
    lines = path.read_text(encoding="utf-8").split("\n")
    if any(l.startswith("bridge.add.scan.steps.feishu=") for l in lines):
        sys.exit(f"{path} 已有飞书文案，停止")
    out = []
    for line in lines:
        out.append(line)
        if line.startswith("bridge.add.scan.steps="):
            out.append("bridge.add.scan.steps.feishu=" + values[0])
        if line.startswith("bridge.onboarding.network="):
            out.extend(f"{k}={v}" for k, v in zip(keys, values[1:]))
    path.write_text("\n".join(out), encoding="utf-8")
    print("updated", path)
```

Run: `python3 /tmp/add_feishu_texts.py`
Expected: 打印 10 行 `updated …`

注意：`.properties` 中单引号在带 `{0}` 的消息里会被 `MessageFormat` 吞掉。上面带 `{0}` 的法语文案用的是 `’`（U+2019），不是 ASCII `'`，不受影响；如有改写，保持这一点。

- [ ] **Step 5: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.ChannelFactoriesTest' --tests 'com.github.izerui.imux.ImuxBundleTest' --tests 'com.github.izerui.imux.bridge.channel.ChannelIsolationSourceTest' -q`
Expected: PASS

---

### Task 11: 文档更新与收尾验证

**Files:**
- Modify: `docs/channel-protocols.md`（第 0 节对照表、第 2 节）
- Modify: `docs/channel-limits.md`（第 3 节“月度额度”小节）

- [ ] **Step 1: 更新 `docs/channel-protocols.md`**

1）第 0 节表格“官方 Java SDK”一行的飞书列改为：`有，但不引入：长连接与 HTTP 自实现（2026-10-28 决定）`。
2）第 2 节“长连接”下的“选型”一条整条替换为：

```markdown
- 实现：自实现，不引入 SDK。帧格式以官方 Python SDK `lark-oapi` 1.7.5 内嵌的 `pbbp2.proto` 为准，测试样本字节由该 SDK 生成。收到数据帧立即回写确认，处理放到按序队列；官方 SDK 只靠读错误发现断线，我们另加“3 个心跳周期无任何帧即重连”。
```

3）第 2 节“收发”之后追加：

```markdown
**本轮核实（2026-10-28）**
- 发 Markdown：`msg_type=post`，`content={"zh_cn":{"content":[[{"tag":"md","text":..}]]}}`；`md` 支持 CommonMark 0.31 + GFM；富文本请求体 ≤ 30 KB。
- 收消息类型：text、post（接收时 `md` 会被转成其他标签）、image（`image_key`）、file（`file_key`、`file_name`）、media（视频）、audio（`file_key`、`duration`，**无转写**）。
- 下载附件：`GET /open-apis/im/v1/messages/{message_id}/resources/{file_key}?type=image|file`，音频与视频用 `file`；100 MB 以下整包下载，更大需 Range 分片（本版不做）；内容不加密。
- 引用：事件只有 `parent_id`，文字需 `GET /open-apis/im/v1/messages/{message_id}` 获取。
- 语音：消息是 Opus（Ogg 封装）；识别接口 `speech_to_text/v1/speech/file_recognize` 只收 16k pcm、适合 60 秒以内、权限 `speech_to_text:speech`。插件用 Concentus 解码后按 50 秒切段识别，失败时退回音频附件。
- 令牌：`tenant_access_token` 最长 2 小时；剩余不足 30 分钟时再取会得到新令牌。
- 错误码：令牌无效 99991661 / 99991663 / 99991665 / 20005 / 20013；应用不可用 10014 / 10015 / 11207 / 11209 / 99991662 / 99991673 / 20009；限频 99991400 / 1000004 / 1000005；额度 99991403；权限 99991672 / 99991676；不在可用范围 230013；未启用机器人 230006。
```

4）第 6 节“飞书”一条改为：`飞书：扫码返回的 open_id 与消息发送人是否一致；PersonalAgent 默认模板是否已开启长连接、单聊收发与语音识别（speech_to_text:speech）权限；语音文件是否为标准 Ogg Opus；pcm 字节序；语音识别是否计入月度额度。`

- [ ] **Step 2: 更新 `docs/channel-limits.md`**

在第 3 节“月度额度不能忽略”小节的列表末尾追加：

```markdown
- 不计入额度的类别包括“认证及授权”（取 `tenant_access_token`）与“事件订阅”（长连接收消息）；发消息、下载消息资源、获取消息计入。调用失败（4xx/5xx）不占额度。（2026-10-28 核实）
```

- [ ] **Step 3: 全量测试**

Run: `./gradlew test -q`
Expected: 0 失败。记录测试总数，与本计划开始前对比（开始前为 2153 个）。

- [ ] **Step 4: IDE 检查**

对本计划新建与修改的全部 `.kt` 文件运行 IDEA MCP `lint_files`，处理所有 error 与 warning（与现有代码风格一致的 weak warning 可保留，需说明）。对新建文件运行 `reformat_file`。

- [ ] **Step 5: 插件验证**

Run: `./gradlew verifyPlugin`
Expected: `.verify/ides/` 下每个 IDE 都是 Compatible。

- [ ] **Step 6: 汇报**

向用户汇报：测试数与结果、lint 结果、verifyPlugin 结果；明确**尚未真机验证**，并列出设计第 12 节的 4 项真机确认事项。不提交。
