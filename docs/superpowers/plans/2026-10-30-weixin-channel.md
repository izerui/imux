# 普通微信渠道 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 imux 手机桥接中新增普通微信渠道（腾讯 iLink）：扫码登录即认主，长轮询收单聊消息，sendmessage 推送回复，与企业微信、飞书、钉钉并列。

**Architecture:** 新建 `bridge/channel/weixin/`，自己实现一套（扫码、HTTP 接口、消息解析、媒体下载解密、长轮询连接），不引用 `wecom/`、`feishu/`、`dingtalk/` 的任何类；只用 `channel/` 根包的框架端口与 `core/` 模型。公共层唯一改动：`JdkHttpJson` 增加可选请求超时参数（长轮询需要）。

**Tech Stack:** Kotlin、kotlinx.coroutines、Gson、JDK `java.net.http`、`javax.crypto`（AES-128-ECB）、JUnit4。

**设计依据：** `docs/superpowers/specs/2026-10-30-weixin-channel-design.md`（下称“设计”）。官方源码参考 `/tmp/weixin-research/package/src/`（`@tencent-weixin/openclaw-weixin` 2.4.10）。

## Global Constraints

- 在 main 上工作，**不提交**（用户自己提交）；不建 worktree。
- 不引入任何新依赖。
- **各渠道各自一套，互不复用**：`channel/weixin/` 的源码与测试不得 import `...channel.wecom.`、`...channel.feishu.`、`...channel.dingtalk.`；钉钉等的写法可以参照阅读，但不能引用。
- 渠道代码不得引用 `AgentType`、`bridge.terminal`、`bridge.transcript`、`TerminalHost`、`SessionMonitor`。
- 不改核心层（`bridge/core/`）、`robot/`、`ui/`。
- 取舍原则（用户 2026-10-30）：有官方或成功案例的照着写，选最简单、能跑通的；不强求与某一家逐字节一致。
- 公共请求头：`AuthorizationType: ilink_bot_token`、`X-WECHAT-UIN`（每次随机 uint32 → 十进制字符串 → base64）、`iLink-App-Id: bot`、`iLink-App-ClientVersion: 132106`；登录后加 `Authorization: Bearer <bot_token>`。`base_info` 只带 `{"channel_version":"2.4.10"}`。
- 默认域名 `https://ilinkai.weixin.qq.com`；CDN `https://novac2c.cdn.weixin.qq.com/c2c`。
- 只处理 `message_type == 1` 且 `group_id` 为空的消息；`senderId = chatId = from_user_id`；扫码认主 `ownerId = ilink_user_id`。
- 发送：`maxTextBytes = 4_000`；限频 `SlidingWindowLimiter(3, 1000)`，最多等 20 秒；HTTP 2xx 且 `ret`、`errcode` 均为 0（或缺省）才算成功；发送 `-14` → 去掉 context_token 重发一次（照 hermes），仍失败按拒收；getupdates `-14` → 报 Retrying 并暂停 1 小时后继续（照官方，最终评审后修正，原计划为“报 Failed 并停止”）；其它拒绝 → `SendFailing("bridge.weixin.sendRejected", "<ret>/<errcode>")`。
- 不补历史：游标只在内存，每次连接从空游标开始，丢弃 `create_time_ms < connectedAt - 60_000` 的消息。
- 状态上报：锁内只入队、锁外由通知协程回调；收发受阻跨重连保留，只在 stop() 或下一次发送成功时清除；只在链路 Online 时显示受阻。
- 日志、状态原因、`toString` 不得包含 bot_token、context_token、消息正文、媒体地址与密钥。
- 文案补齐 10 种语言（`src/main/resources/messages/ImuxBundle*.properties`，UTF-8；法语用 `’`）。
- 测试命令：`./gradlew test --tests '<类全名或通配>'`；不要并发跑多个 gradle；不用固定 delay 断言“没有发生”，用可等待的屏障。
- 风格与周围一致：中文 KDoc/注释，注释只写“为什么”，中文引号用弯引号“”。

## File Structure

| 文件 | 职责 |
|---|---|
| Modify `src/main/kotlin/com/github/izerui/imux/bridge/channel/Transports.kt` | `JdkHttpJson` 增加 `requestTimeout` 参数 |
| Create `.../bridge/channel/weixin/WeixinApi.kt` | `WeixinResult`、请求头、取码、扫码状态、getupdates、sendmessage |
| Create `.../bridge/channel/weixin/WeixinOnboarding.kt` | `WeixinCredentialKeys`、扫码登录 |
| Create `.../bridge/channel/weixin/WeixinEvents.kt` | `WeixinMessage`、`WeixinMediaRef`、消息解析（纯函数） |
| Create `.../bridge/channel/weixin/WeixinMedia.kt` | `WeixinAttachment`：下载地址、AES-128-ECB 解密 |
| Create `.../bridge/channel/weixin/WeixinConnection.kt` | 长轮询、去重、按序交出、context_token、发送、状态 |
| Create `.../bridge/channel/weixin/WeixinChannelFactory.kt` | 工厂 |
| Modify `src/main/kotlin/com/github/izerui/imux/bridge/channel/ChannelFactories.kt` | 登记 |
| Modify `src/main/resources/messages/ImuxBundle*.properties`（10 个） | 3 条新文案 |
| Create `src/test/kotlin/.../bridge/channel/JdkHttpJsonTimeoutTest.kt` | 超时参数 |
| Create `src/test/kotlin/.../bridge/channel/weixin/WeixinFakeHttp.kt` 与 `Weixin*Test.kt` | 测试 |
| Modify `src/test/kotlin/.../bridge/channel/ChannelFactoriesTest.kt`、`ChannelIsolationSourceTest.kt` | 登记与隔离 |

下文 `.../bridge` 指 `com/github/izerui/imux/bridge`（main 在 `src/main/kotlin/`，test 在 `src/test/kotlin/`）。

---

### Task 1: JdkHttpJson 请求超时参数

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/channel/Transports.kt`（`class JdkHttpJson`）
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/JdkHttpJsonTimeoutTest.kt`

**Interfaces:**
- Produces: `JdkHttpJson(client: HttpClient = ..., requestTimeout: Duration = NETWORK_TIMEOUT)`。Task 2 用 `JdkHttpJson(requestTimeout = Duration.ofSeconds(60))` 做长轮询。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.http.HttpTimeoutException
import java.time.Duration

class JdkHttpJsonTimeoutTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    private val base = "http://127.0.0.1:${server.address.port}"

    @After
    fun tearDown() = server.stop(0)

    private fun slow(millis: Long) {
        server.createContext("/slow") { exchange ->
            Thread.sleep(millis)
            val bytes = """{"ok":1}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

    @Test
    fun `请求超时可按实例设置`() =
        runBlocking {
            slow(400)
            val error = runCatching { JdkHttpJson(requestTimeout = Duration.ofMillis(100)).get("$base/slow") }.exceptionOrNull()
            assertTrue("应超时，实际 $error", error is HttpTimeoutException || error?.cause is HttpTimeoutException)
        }

    @Test
    fun `默认超时不变，慢一点的请求照常返回`() =
        runBlocking {
            slow(400)
            assertEquals(1, JdkHttpJson().get("$base/slow").json!!["ok"].asInt)
        }
}
```

（`get(url)` 的单参数形式若不存在，使用 `get("$base/slow", emptyMap())`——先看 `HttpJson` 接口的默认参数。）

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.JdkHttpJsonTimeoutTest'`
Expected: 编译失败，`requestTimeout` 参数不存在。

- [ ] **Step 3: 实现**

把 `class JdkHttpJson` 的构造与 `execute` 改为：

```kotlin
class JdkHttpJson(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(NETWORK_TIMEOUT).build(),
    /** 整次请求的超时；长轮询（服务端挂起数十秒）需要比默认更长。 */
    private val requestTimeout: Duration = NETWORK_TIMEOUT,
) : HttpJson {
```

`execute` 里 `builder.timeout(NETWORK_TIMEOUT)` 改为 `builder.timeout(requestTimeout)`。其余不变。

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.JdkHttpJsonTimeoutTest' --tests 'com.github.izerui.imux.bridge.channel.HttpJsonTest'`
Expected: PASS。

- [ ] **Step 5: 不提交。**

---

### Task 2: WeixinApi（请求头、取码、扫码状态、getupdates、sendmessage）

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/weixin/WeixinApi.kt`
- Create: `src/test/kotlin/com/github/izerui/imux/bridge/channel/weixin/WeixinFakeHttp.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/weixin/WeixinApiTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `JdkHttpJson(requestTimeout = ...)`。
- Produces（Task 3、6、7 使用）：
  - `sealed interface WeixinResult<out T>`：`Ok(value)`、`Error(status: Int, ret: Int?, errcode: Int?, message: String)`（属性 `expired: Boolean`、`code: String`）、`Network(reason: String, timeout: Boolean = false)`
  - `data class WeixinQrCode(qrcode: String, content: String)`
  - `data class WeixinLoginStatus(status: String, redirectHost: String?, botToken: String?, botId: String?, baseUrl: String?, userId: String?)`
  - `data class WeixinUpdates(messages: List<JsonObject>, cursor: String?)`
  - `class WeixinApi(http: HttpJson = JdkHttpJson(), longPollHttp: HttpJson = JdkHttpJson(requestTimeout = LONG_POLL_TIMEOUT), random: Random = SecureRandom())`：`qrCode()`、`qrCodeStatus(host, qrcode)`、`updates(baseUrl, token, cursor)`、`send(baseUrl, token, to, text, contextToken: String?)`、`headers(token: String?)`；companion：`DEFAULT_BASE_URL`、`LONG_POLL_TIMEOUT`、`normalizeBaseUrl(raw: String?)`
  - 测试工具 `WeixinFakeHttp`

- [ ] **Step 1: 写假 HTTP**

```kotlin
package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.channel.HttpJson
import com.github.izerui.imux.bridge.channel.HttpJsonResponse
import com.google.gson.JsonParser
import java.util.concurrent.CopyOnWriteArrayList

/** 微信测试用假 HTTP：按地址返回 JSON，记录方法、地址、正文与请求头。route 抛异常即模拟网络错误或超时。 */
class WeixinFakeHttp(
    private val route: suspend (method: String, url: String, body: String?) -> Pair<Int, String>,
) : HttpJson {
    data class Call(
        val method: String,
        val url: String,
        val body: String?,
        val headers: Map<String, String>,
    )

    val calls = CopyOnWriteArrayList<Call>()

    fun calls(part: String) = calls.filter { part in it.url }

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
        val (status, text) = route(method, url, body)
        val json = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        return HttpJsonResponse(status, json)
    }
}
```

- [ ] **Step 2: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.weixin

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.http.HttpTimeoutException
import java.util.Base64
import java.util.Random

class WeixinApiTest {
    @Volatile
    private var answer: Pair<Int, String> = 200 to """{"ret":0}"""

    private val http = WeixinFakeHttp { _, _, _ -> answer }
    private val api = WeixinApi(http, http, Random(1))

    @Test
    fun `公共请求头与 Bearer`() {
        val plain = api.headers(null)
        assertEquals("ilink_bot_token", plain["AuthorizationType"])
        assertEquals("bot", plain["iLink-App-Id"])
        assertEquals("132106", plain["iLink-App-ClientVersion"])
        assertNull(plain["Authorization"])
        assertFalse("Content-Type 由请求方法自己设置", plain.containsKey("Content-Type"))
        val uin = String(Base64.getDecoder().decode(plain["X-WECHAT-UIN"]))
        assertTrue("uin 是十进制 uint32：$uin", uin.matches(Regex("\\d+")) && uin.toLong() in 0..0xFFFFFFFFL)
        assertEquals("Bearer T", api.headers("T")["Authorization"])
    }

    @Test
    fun `取码用 GET，返回票据与二维码链接`() =
        runBlocking {
            answer = 200 to """{"qrcode":"q1","qrcode_img_content":"https://liteapp.weixin.qq.com/q/abc"}"""
            assertEquals(WeixinResult.Ok(WeixinQrCode("q1", "https://liteapp.weixin.qq.com/q/abc")), api.qrCode())
            val call = http.calls.single()
            assertEquals("GET", call.method)
            assertEquals("https://ilinkai.weixin.qq.com/ilink/bot/get_bot_qrcode?bot_type=3", call.url)
            answer = 200 to """{"qrcode":"q1"}"""
            assertTrue(api.qrCode() is WeixinResult.Network)
        }

    @Test
    fun `扫码状态解析`() =
        runBlocking {
            answer = 200 to """{"status":"confirmed","bot_token":"BT","ilink_bot_id":"b@im.bot","baseurl":"https://x.weixin.qq.com","ilink_user_id":"u@im.wechat"}"""
            val status = (api.qrCodeStatus("https://h", "q 1") as WeixinResult.Ok).value
            assertEquals(WeixinLoginStatus("confirmed", null, "BT", "b@im.bot", "https://x.weixin.qq.com", "u@im.wechat"), status)
            assertEquals("https://h/ilink/bot/get_qrcode_status?qrcode=q+1", http.calls.single().url)
            assertFalse("toString 不含 token", status.toString().contains("BT"))
            answer = 200 to """{"status":"scaned_but_redirect","redirect_host":"sh.weixin.qq.com"}"""
            assertEquals("sh.weixin.qq.com", (api.qrCodeStatus("https://h", "q") as WeixinResult.Ok).value.redirectHost)
        }

    @Test
    fun `getupdates 请求体与结果`() =
        runBlocking {
            answer = 200 to """{"ret":0,"msgs":[{"message_id":1},{"message_id":2}],"get_updates_buf":"c2"}"""
            val updates = (api.updates("https://b", "T", "c1") as WeixinResult.Ok).value
            assertEquals(2, updates.messages.size)
            assertEquals("c2", updates.cursor)
            val call = http.calls.single()
            assertEquals("https://b/ilink/bot/getupdates", call.url)
            assertEquals("Bearer T", call.headers["Authorization"])
            val body = JsonParser.parseString(call.body).asJsonObject
            assertEquals("c1", body["get_updates_buf"].asString)
            assertEquals("2.4.10", body["base_info"].asJsonObject["channel_version"].asString)
            answer = 200 to """{"ret":0,"msgs":[],"get_updates_buf":""}"""
            assertNull("空游标视为没有新游标", (api.updates("https://b", "T", "c2") as WeixinResult.Ok).value.cursor)
        }

    @Test
    fun `sendmessage 请求体`() =
        runBlocking {
            answer = 200 to """{"ret":0}"""
            assertEquals(WeixinResult.Ok(Unit), api.send("https://b", "T", "u@im.wechat", "你好", "CT"))
            val msg = JsonParser.parseString(http.calls.single().body).asJsonObject["msg"].asJsonObject
            assertEquals("", msg["from_user_id"].asString)
            assertEquals("u@im.wechat", msg["to_user_id"].asString)
            assertEquals(2, msg["message_type"].asInt)
            assertEquals(2, msg["message_state"].asInt)
            assertEquals("CT", msg["context_token"].asString)
            assertTrue(msg["client_id"].asString.isNotEmpty())
            val item = msg["item_list"].asJsonArray.single().asJsonObject
            assertEquals(1, item["type"].asInt)
            assertEquals("你好", item["text_item"].asJsonObject["text"].asString)
            api.send("https://b", "T", "u@im.wechat", "再来", null)
            val second = JsonParser.parseString(http.calls.last().body).asJsonObject["msg"].asJsonObject
            assertFalse("没有 context_token 就不带（官方照发）", second.has("context_token"))
        }

    @Test
    fun `ret 与 errcode 判定`() =
        runBlocking {
            answer = 200 to """{"ret":-14,"errmsg":"session timeout"}"""
            val expired = api.send("https://b", "T", "u", "x", null) as WeixinResult.Error
            assertTrue(expired.expired)
            assertEquals("-14/0", expired.code)
            answer = 200 to """{"errcode":-14}"""
            assertTrue((api.send("https://b", "T", "u", "x", null) as WeixinResult.Error).expired)
            answer = 200 to """{"ret":-2,"errmsg":"unknown error"}"""
            val rejected = api.send("https://b", "T", "u", "x", null) as WeixinResult.Error
            assertFalse(rejected.expired)
            assertEquals("-2/0", rejected.code)
            assertEquals("unknown error", rejected.message)
            answer = 500 to "<html>"
            assertEquals(WeixinResult.Network("HTTP 500"), api.send("https://b", "T", "u", "x", null))
            answer = 200 to """{}"""
            assertEquals(WeixinResult.Ok(Unit), api.send("https://b", "T", "u", "x", null))
        }

    @Test
    fun `网络错误与超时`() =
        runBlocking {
            val timeoutApi = WeixinApi(WeixinFakeHttp { _, _, _ -> throw HttpTimeoutException("slow https://secret") }, WeixinFakeHttp { _, _, _ -> throw HttpTimeoutException("slow") })
            assertEquals(WeixinResult.Network("timeout", timeout = true), timeoutApi.updates("https://b", "T", ""))
            val brokenApi = WeixinApi(WeixinFakeHttp { _, _, _ -> throw IOException("reset https://secret") }, WeixinFakeHttp { _, _, _ -> throw IOException("x") })
            val network = brokenApi.send("https://b", "T", "u", "x", null) as WeixinResult.Network
            assertEquals("IOException", network.reason)
            assertFalse(network.timeout)
        }

    @Test
    fun `规范化 baseurl`() {
        assertEquals("https://ilinkai.weixin.qq.com", WeixinApi.normalizeBaseUrl(null))
        assertEquals("https://ilinkai.weixin.qq.com", WeixinApi.normalizeBaseUrl("  "))
        assertEquals("https://x.qq.com", WeixinApi.normalizeBaseUrl("x.qq.com/"))
        assertEquals("http://x.qq.com", WeixinApi.normalizeBaseUrl("http://x.qq.com"))
    }
}
```

- [ ] **Step 3: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.weixin.WeixinApiTest'`
Expected: 编译失败，`WeixinApi` 未定义。

- [ ] **Step 4: 实现**

```kotlin
package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.channel.HttpJson
import com.github.izerui.imux.bridge.channel.HttpJsonResponse
import com.github.izerui.imux.bridge.channel.JdkHttpJson
import com.github.izerui.imux.bridge.json.objects
import com.github.izerui.imux.bridge.json.primitiveText
import com.github.izerui.imux.bridge.json.string
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URLEncoder
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.Random
import java.util.UUID
import java.util.concurrent.CompletionException
import kotlin.coroutines.cancellation.CancellationException

/** 一次微信调用的结果：成功、服务端拒绝、网络或解析失败（结果不明）。 */
sealed interface WeixinResult<out T> {
    data class Ok<out T>(val value: T) : WeixinResult<T>

    /** 服务端拒绝：HTTP 非 2xx（正文为 JSON），或 ret / errcode 非 0。 */
    data class Error(
        val status: Int,
        val ret: Int?,
        val errcode: Int?,
        val message: String,
    ) : WeixinResult<Nothing> {
        /** -14：bot_token 失效，需要重新扫码（官方 session-guard.ts）。 */
        val expired: Boolean
            get() = ret == EXPIRED || errcode == EXPIRED

        /** 界面与原因里显示的错误码，形如 `-2/0`。 */
        val code: String
            get() = "${ret ?: 0}/${errcode ?: 0}"
    }

    /** 网络错误或响应无法解析；原因只含异常类名或 HTTP 状态，不含地址。[timeout] 为请求超时（长轮询里等同于没有新消息）。 */
    data class Network(
        val reason: String,
        val timeout: Boolean = false,
    ) : WeixinResult<Nothing>

    companion object {
        const val EXPIRED = -14
    }
}

/** 取码结果：[qrcode] 是轮询票据，[content] 是要编码成二维码的链接（官方 login-qr.ts:164-167）。 */
data class WeixinQrCode(
    val qrcode: String,
    val content: String,
) {
    override fun toString(): String = "WeixinQrCode(qrcode=***, content=***)"
}

/** 扫码状态；`confirmed` 时带登录凭据。 */
data class WeixinLoginStatus(
    val status: String,
    val redirectHost: String? = null,
    val botToken: String? = null,
    val botId: String? = null,
    val baseUrl: String? = null,
    val userId: String? = null,
) {
    override fun toString(): String =
        "WeixinLoginStatus(status=$status, redirectHost=$redirectHost, botToken=${botToken?.let { "***" }}, botId=$botId, baseUrl=$baseUrl, userId=$userId)"
}

/** 一轮长轮询的结果；[cursor] 为空表示服务端没给新游标（保持原游标）。 */
data class WeixinUpdates(
    val messages: List<JsonObject>,
    val cursor: String?,
)

/**
 * 微信 iLink 的 HTTP 调用（官方 `@tencent-weixin/openclaw-weixin` 2.4.10 的 api.ts）。
 * 取码用 GET 不带 body（DeepTutor、hermes、claude-codex-wechat 均如此并跑通）；
 * 扫码状态与 getupdates 是服务端挂起的长轮询，用更长的请求超时。
 */
class WeixinApi(
    private val http: HttpJson = JdkHttpJson(),
    private val longPollHttp: HttpJson = JdkHttpJson(requestTimeout = LONG_POLL_TIMEOUT),
    private val random: Random = SecureRandom(),
) {
    suspend fun qrCode(): WeixinResult<WeixinQrCode> =
        when (val result = request { http.get("$DEFAULT_BASE_URL/ilink/bot/get_bot_qrcode?bot_type=$BOT_TYPE", headers(null)) }) {
            is WeixinResult.Ok -> {
                val qrcode = result.value.string("qrcode")
                val content = result.value.string("qrcode_img_content")
                if (qrcode.isNullOrEmpty() || content.isNullOrEmpty()) {
                    WeixinResult.Network("no qrcode")
                } else {
                    WeixinResult.Ok(WeixinQrCode(qrcode, content))
                }
            }

            is WeixinResult.Error -> result
            is WeixinResult.Network -> result
        }

    suspend fun qrCodeStatus(
        host: String,
        qrcode: String,
    ): WeixinResult<WeixinLoginStatus> =
        when (val result = request { longPollHttp.get("$host/ilink/bot/get_qrcode_status?qrcode=${encode(qrcode)}", headers(null)) }) {
            is WeixinResult.Ok -> {
                val json = result.value
                WeixinResult.Ok(
                    WeixinLoginStatus(
                        status = json.string("status").orEmpty(),
                        redirectHost = json.string("redirect_host"),
                        botToken = json.string("bot_token"),
                        botId = json.primitiveText("ilink_bot_id"),
                        baseUrl = json.string("baseurl"),
                        userId = json.primitiveText("ilink_user_id"),
                    ),
                )
            }

            is WeixinResult.Error -> result
            is WeixinResult.Network -> result
        }

    suspend fun updates(
        baseUrl: String,
        token: String,
        cursor: String,
    ): WeixinResult<WeixinUpdates> {
        val body =
            JsonObject().apply {
                addProperty("get_updates_buf", cursor)
                add("base_info", baseInfo())
            }
        return when (val result = request { longPollHttp.post("$baseUrl/ilink/bot/getupdates", JSON_TYPE, body.toString(), headers(token)) }) {
            is WeixinResult.Ok ->
                WeixinResult.Ok(WeixinUpdates(result.value.objects("msgs"), result.value.string("get_updates_buf")?.takeIf(String::isNotEmpty)))

            is WeixinResult.Error -> result
            is WeixinResult.Network -> result
        }
    }

    /** 发一段文本；[contextToken] 为空时不带（官方缺失时照发，只记警告）。 */
    suspend fun send(
        baseUrl: String,
        token: String,
        to: String,
        text: String,
        contextToken: String?,
    ): WeixinResult<Unit> {
        val item =
            JsonObject().apply {
                addProperty("type", ITEM_TEXT)
                add("text_item", JsonObject().apply { addProperty("text", text) })
            }
        val msg =
            JsonObject().apply {
                addProperty("from_user_id", "")
                addProperty("to_user_id", to)
                addProperty("client_id", "imux-${UUID.randomUUID()}")
                addProperty("message_type", MESSAGE_TYPE_BOT)
                addProperty("message_state", MESSAGE_STATE_FINISH)
                add("item_list", JsonArray().apply { add(item) })
                contextToken?.let { addProperty("context_token", it) }
            }
        val body =
            JsonObject().apply {
                add("msg", msg)
                add("base_info", baseInfo())
            }
        return when (val result = request { http.post("$baseUrl/ilink/bot/sendmessage", JSON_TYPE, body.toString(), headers(token)) }) {
            is WeixinResult.Ok -> WeixinResult.Ok(Unit)
            is WeixinResult.Error -> result
            is WeixinResult.Network -> result
        }
    }

    /** 公共请求头（官方 api.ts:221-254）；Content-Type 由 POST 自己设置，这里不放，避免重复。 */
    fun headers(token: String?): Map<String, String> =
        buildMap {
            put("AuthorizationType", "ilink_bot_token")
            put("X-WECHAT-UIN", uin())
            put("iLink-App-Id", APP_ID)
            put("iLink-App-ClientVersion", CLIENT_VERSION)
            token?.let { put("Authorization", "Bearer $it") }
        }

    /** 每次请求随机 uint32 → 十进制字符串 → base64（官方 api.ts:222-225）。 */
    private fun uin(): String {
        val value = random.nextInt().toLong() and 0xFFFFFFFFL
        return Base64.getEncoder().encodeToString(value.toString().toByteArray(StandardCharsets.US_ASCII))
    }

    private fun baseInfo() = JsonObject().apply { addProperty("channel_version", CHANNEL_VERSION) }

    private suspend fun request(call: suspend () -> HttpJsonResponse): WeixinResult<JsonObject> {
        val response =
            try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cause = (e as? CompletionException)?.cause ?: e
                // 异常消息可能带完整地址，只留类名
                return if (cause is HttpTimeoutException) {
                    WeixinResult.Network("timeout", timeout = true)
                } else {
                    WeixinResult.Network(cause.javaClass.simpleName.ifEmpty { cause.javaClass.name })
                }
            }
        val json = response.json ?: return WeixinResult.Network("HTTP ${response.status}")
        val ret = json.primitiveText("ret")?.toIntOrNull()
        val errcode = json.primitiveText("errcode")?.toIntOrNull()
        val rejected = response.status !in 200..299 || (ret ?: 0) != 0 || (errcode ?: 0) != 0
        return if (rejected) WeixinResult.Error(response.status, ret, errcode, json.string("errmsg").orEmpty()) else WeixinResult.Ok(json)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"

        /** 服务端长轮询最长挂起约 35 秒，留足余量。 */
        val LONG_POLL_TIMEOUT: Duration = Duration.ofSeconds(60)

        private const val BOT_TYPE = 3
        private const val APP_ID = "bot"

        /** 2.4.10 按 major<<16 | minor<<8 | patch 编码（官方 api.ts:99-107）。 */
        private const val CLIENT_VERSION = "132106"
        private const val CHANNEL_VERSION = "2.4.10"
        private const val JSON_TYPE = "application/json"
        private const val ITEM_TEXT = 1
        private const val MESSAGE_TYPE_BOT = 2
        private const val MESSAGE_STATE_FINISH = 2

        /** 登录返回的 baseurl 可能为空、缺协议头或带结尾斜杠。 */
        fun normalizeBaseUrl(raw: String?): String {
            val value = raw?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty) ?: return DEFAULT_BASE_URL
            return if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
        }

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}
```

- [ ] **Step 5: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.weixin.WeixinApiTest'`
Expected: PASS。

- [ ] **Step 6: 不提交。**

---

### Task 3: WeixinOnboarding（扫码登录）

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/weixin/WeixinOnboarding.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/weixin/WeixinOnboardingTest.kt`

**Interfaces:**
- Consumes: Task 2 `WeixinApi`、`WeixinResult`、`WeixinLoginStatus`、`WeixinFakeHttp`。
- Produces（Task 7 使用）：`object WeixinCredentialKeys { BOT_TOKEN="bot_token"; BASE_URL="base_url"; BOT_ID="bot_id"; USER_ID="user_id" }`；`class WeixinOnboarding(api: WeixinApi = WeixinApi()) : ChannelOnboarding`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.channel.OnboardingStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class WeixinOnboardingTest {
    private var qrCount = 0
    private val statuses = ArrayDeque<String>()

    private val http =
        WeixinFakeHttp { _, url, _ ->
            when {
                "/get_bot_qrcode" in url -> {
                    qrCount++
                    200 to """{"qrcode":"q$qrCount","qrcode_img_content":"https://qr/$qrCount"}"""
                }

                "/get_qrcode_status" in url -> {
                    val next = synchronized(statuses) { statuses.removeFirstOrNull() } ?: """{"status":"wait"}"""
                    if (next == "throw") throw IOException("reset")
                    200 to next
                }

                else -> 404 to "{}"
            }
        }

    private fun onboarding() = WeixinOnboarding(WeixinApi(http, http))

    private fun status(vararg bodies: String) = synchronized(statuses) { statuses.addAll(bodies) }

    @Test
    fun `扫码确认后直接认主，凭据齐全`() =
        runBlocking {
            val onboarding = onboarding()
            assertEquals(OnboardingStep.ShowQr("https://qr/1"), onboarding.start())
            assertEquals(1_000L, onboarding.pollIntervalMillis)
            status(
                """{"status":"wait"}""",
                """{"status":"scaned"}""",
                """{"status":"confirmed","bot_token":"BT","ilink_bot_id":"b@im.bot","baseurl":"x.weixin.qq.com/","ilink_user_id":"u@im.wechat"}""",
            )
            assertEquals(OnboardingStep.Waiting, onboarding.poll())
            assertEquals(OnboardingStep.Waiting, onboarding.poll())
            assertEquals(
                OnboardingStep.Succeeded(
                    mapOf("bot_token" to "BT", "base_url" to "https://x.weixin.qq.com", "bot_id" to "b@im.bot", "user_id" to "u@im.wechat"),
                    ownerId = "u@im.wechat",
                    suggestedName = null,
                ),
                onboarding.poll(),
            )
            assertEquals("https://ilinkai.weixin.qq.com/ilink/bot/get_qrcode_status?qrcode=q1", http.calls("/get_qrcode_status").first().url)
        }

    @Test
    fun `换域名后改轮询新地址`() =
        runBlocking {
            val onboarding = onboarding()
            onboarding.start()
            status("""{"status":"scaned_but_redirect","redirect_host":"sh.weixin.qq.com"}""", """{"status":"wait"}""")
            onboarding.poll()
            onboarding.poll()
            assertEquals("https://sh.weixin.qq.com/ilink/bot/get_qrcode_status?qrcode=q1", http.calls("/get_qrcode_status").last().url)
        }

    @Test
    fun `过期刷新二维码，超过三次失败`() =
        runBlocking {
            val onboarding = onboarding()
            onboarding.start()
            status("""{"status":"expired"}""", """{"status":"expired"}""", """{"status":"expired"}""", """{"status":"expired"}""")
            assertEquals(OnboardingStep.ShowQr("https://qr/2"), onboarding.poll())
            assertEquals(OnboardingStep.ShowQr("https://qr/3"), onboarding.poll())
            assertEquals(OnboardingStep.ShowQr("https://qr/4"), onboarding.poll())
            assertEquals(OnboardingStep.Failed("bridge.onboarding.expired"), onboarding.poll())
        }

    @Test
    fun `手机验证码暂不支持；缺凭据失败；没有本人身份走配对码`() =
        runBlocking {
            val a = onboarding()
            a.start()
            status("""{"status":"need_verifycode"}""")
            assertEquals(OnboardingStep.Failed("bridge.weixin.verifyCode"), a.poll())
            val b = onboarding()
            b.start()
            status("""{"status":"confirmed","bot_token":"BT"}""")
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noCredentials"), b.poll())
            val c = onboarding()
            c.start()
            status("""{"status":"confirmed","bot_token":"BT","ilink_bot_id":"b"}""")
            val succeeded = c.poll() as OnboardingStep.Succeeded
            assertEquals(null, succeeded.ownerId)
            assertEquals("https://ilinkai.weixin.qq.com", succeeded.credentials["base_url"])
        }

    @Test
    fun `网络错误继续等；取码失败报无二维码`() =
        runBlocking {
            val onboarding = onboarding()
            onboarding.start()
            status("throw")
            assertEquals(OnboardingStep.Waiting, onboarding.poll())
            val broken = WeixinOnboarding(WeixinApi(WeixinFakeHttp { _, _, _ -> 200 to "{}" }, http))
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noQr"), broken.start())
        }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.weixin.WeixinOnboardingTest'`
Expected: 编译失败，`WeixinOnboarding` 未定义。

- [ ] **Step 3: 实现**

```kotlin
package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.channel.OnboardingStep

/** 微信机器人凭据在凭据库里的键名。 */
object WeixinCredentialKeys {
    const val BOT_TOKEN = "bot_token"
    const val BASE_URL = "base_url"
    const val BOT_ID = "bot_id"
    const val USER_ID = "user_id"
}

/**
 * 微信扫码登录（官方 login-qr.ts；状态处理取 DeepTutor、hermes 跑通的子集）。
 * 扫码人即本人：ownerId = ilink_user_id，不需要配对码。
 * 手机验证码（need_verifycode）本版不支持，三个参考工程也都没做。
 */
class WeixinOnboarding(
    private val api: WeixinApi = WeixinApi(),
) : ChannelOnboarding {
    /** 扫码状态本身是服务端挂起的长轮询，两次之间只需短暂间隔（官方 1 秒）。 */
    override val pollIntervalMillis: Long = 1_000

    private var qrcode: String? = null
    private var host = WeixinApi.DEFAULT_BASE_URL
    private var refreshes = 0

    override suspend fun start(): OnboardingStep = fetchQr()

    override suspend fun poll(): OnboardingStep {
        val code = qrcode ?: return OnboardingStep.Failed("bridge.onboarding.noQr")
        // 网络错误、超时都按“还在等”处理，总超时由调用方兜底
        val status = (api.qrCodeStatus(host, code) as? WeixinResult.Ok)?.value ?: return OnboardingStep.Waiting
        return when (status.status) {
            "scaned_but_redirect" -> {
                status.redirectHost?.trim()?.takeIf(String::isNotEmpty)?.let { host = WeixinApi.normalizeBaseUrl(it) }
                OnboardingStep.Waiting
            }

            "expired" ->
                if (refreshes < MAX_REFRESHES) {
                    refreshes++
                    fetchQr()
                } else {
                    OnboardingStep.Failed("bridge.onboarding.expired")
                }

            "confirmed" -> confirmed(status)
            "need_verifycode", "verify_code_blocked" -> OnboardingStep.Failed("bridge.weixin.verifyCode")
            else -> OnboardingStep.Waiting
        }
    }

    private suspend fun fetchQr(): OnboardingStep {
        val qr = (api.qrCode() as? WeixinResult.Ok)?.value ?: return OnboardingStep.Failed("bridge.onboarding.noQr")
        qrcode = qr.qrcode
        host = WeixinApi.DEFAULT_BASE_URL
        return OnboardingStep.ShowQr(qr.content)
    }

    private fun confirmed(status: WeixinLoginStatus): OnboardingStep {
        val token = status.botToken?.takeIf(String::isNotEmpty)
        val botId = status.botId?.takeIf(String::isNotEmpty)
        if (token == null || botId == null) return OnboardingStep.Failed("bridge.onboarding.noCredentials")
        val userId = status.userId?.takeIf(String::isNotEmpty)
        val credentials =
            buildMap {
                put(WeixinCredentialKeys.BOT_TOKEN, token)
                put(WeixinCredentialKeys.BASE_URL, WeixinApi.normalizeBaseUrl(status.baseUrl))
                put(WeixinCredentialKeys.BOT_ID, botId)
                userId?.let { put(WeixinCredentialKeys.USER_ID, it) }
            }
        return OnboardingStep.Succeeded(credentials = credentials, ownerId = userId, suggestedName = null)
    }

    private companion object {
        const val MAX_REFRESHES = 3
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.weixin.WeixinOnboardingTest'`
Expected: PASS。

- [ ] **Step 5: 不提交。**

---

### Task 4: WeixinEvents（消息解析）

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/weixin/WeixinEvents.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/weixin/WeixinEventsTest.kt`

**Interfaces:**
- Produces（Task 5、6 使用）：
  - `data class WeixinMediaRef(kind: AttachmentKind, fullUrl: String?, encryptQueryParam: String?, aesKeyHex: String?, fileName: String?)`（`aesKeyHex` 为 32 位小写 hex 的 16 字节密钥，或 null）
  - `data class WeixinMessage(messageId: String?, fromUserId: String, createTimeMillis: Long?, contextToken: String?, text: String?, media: List<WeixinMediaRef>, quote: String?)`
  - `object WeixinEvents { fun message(json: JsonObject): WeixinMessage?; fun hexKey(hex: String): String?; fun base64Key(value: String): String? }`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class WeixinEventsTest {
    private fun msg(
        items: String,
        extra: String = "",
    ): JsonObject =
        JsonParser.parseString(
            """{"message_id":12345678901234567890,"from_user_id":"u@im.wechat","to_user_id":"b@im.bot","create_time_ms":1700000000000,"message_type":1,"context_token":"CT","item_list":[$items]$extra}""",
        ).asJsonObject

    private val key16 = ByteArray(16) { it.toByte() }
    private val keyHex = "000102030405060708090a0b0c0d0e0f"

    @Test
    fun `文本消息与顶层字段`() {
        val m = WeixinEvents.message(msg("""{"type":1,"text_item":{"text":"你好"}}"""))!!
        assertEquals("12345678901234567890", m.messageId)
        assertEquals("u@im.wechat", m.fromUserId)
        assertEquals(1700000000000L, m.createTimeMillis)
        assertEquals("CT", m.contextToken)
        assertEquals("你好", m.text)
        assertEquals(emptyList<WeixinMediaRef>(), m.media)
        assertNull(m.quote)
        assertFalse("toString 不含正文与 context_token", m.toString().contains("你好") || m.toString().contains("CT"))
    }

    @Test
    fun `多个 item 的文字按顺序换行拼接`() {
        val m = WeixinEvents.message(msg("""{"type":1,"text_item":{"text":"一"}},{"type":1,"text_item":{"text":"二"}}"""))!!
        assertEquals("一\n二", m.text)
    }

    @Test
    fun `语音有转写作为文字，没有作为音频附件`() {
        val withText = WeixinEvents.message(msg("""{"type":3,"voice_item":{"text":"跑一下单测","media":{"full_url":"https://cdn/v","aes_key":"x"}}}"""))!!
        assertEquals("跑一下单测", withText.text)
        assertEquals(emptyList<WeixinMediaRef>(), withText.media)
        val aesKey = Base64.getEncoder().encodeToString(key16)
        val noText = WeixinEvents.message(msg("""{"type":3,"voice_item":{"media":{"full_url":"https://cdn/v","aes_key":"$aesKey"}}}"""))!!
        assertNull(noText.text)
        assertEquals(listOf(WeixinMediaRef(AttachmentKind.AUDIO, "https://cdn/v", null, keyHex, null)), noText.media)
    }

    @Test
    fun `图片、文件、视频与三种密钥格式`() {
        val hexInBase64 = Base64.getEncoder().encodeToString(keyHex.toByteArray())
        val image = WeixinEvents.message(msg("""{"type":2,"image_item":{"aeskey":"${keyHex.uppercase()}","media":{"encrypt_query_param":"p+1"}}}"""))!!
        assertEquals(listOf(WeixinMediaRef(AttachmentKind.IMAGE, null, "p+1", keyHex, null)), image.media)
        val plainImage = WeixinEvents.message(msg("""{"type":2,"image_item":{"media":{"full_url":"https://cdn/i"}}}"""))!!
        assertEquals(listOf(WeixinMediaRef(AttachmentKind.IMAGE, "https://cdn/i", null, null, null)), plainImage.media)
        val file = WeixinEvents.message(msg("""{"type":4,"file_item":{"file_name":"报告.pdf","media":{"full_url":"https://cdn/f","aes_key":"$hexInBase64"}}}"""))!!
        assertEquals(listOf(WeixinMediaRef(AttachmentKind.FILE, "https://cdn/f", null, keyHex, "报告.pdf")), file.media)
        val video = WeixinEvents.message(msg("""{"type":5,"video_item":{"media":{"full_url":"https://cdn/m","aes_key":"$hexInBase64"}}}"""))!!
        assertEquals(AttachmentKind.VIDEO, video.media.single().kind)
    }

    @Test
    fun `非图片缺密钥或缺地址时不作为附件`() {
        assertNull(WeixinEvents.message(msg("""{"type":4,"file_item":{"file_name":"a","media":{"full_url":"https://cdn/f"}}}""")))
        assertNull(WeixinEvents.message(msg("""{"type":2,"image_item":{"media":{}}}""")))
    }

    @Test
    fun `引用取被引用的文字，取不到用标题`() {
        val quoted =
            WeixinEvents.message(
                msg("""{"type":1,"text_item":{"text":"这个呢"},"ref_msg":{"title":"摘要","message_item":{"type":1,"text_item":{"text":"原话"}}}}"""),
            )!!
        assertEquals("原话", quoted.quote)
        val titled = WeixinEvents.message(msg("""{"type":1,"text_item":{"text":"这个呢"},"ref_msg":{"title":"摘要"}}"""))!!
        assertEquals("摘要", titled.quote)
    }

    @Test
    fun `只收用户单聊消息；无内容丢弃`() {
        assertNull(WeixinEvents.message(msg("""{"type":1,"text_item":{"text":"x"}}""").apply { addProperty("message_type", 2) }))
        assertNull(WeixinEvents.message(msg("""{"type":1,"text_item":{"text":"x"}}""", ""","group_id":"g1"""")))
        assertNull(WeixinEvents.message(msg("""{"type":1,"text_item":{"text":"  "}}""")))
        assertNull(WeixinEvents.message(msg("""{"type":11}""")))
        val noId = msg("""{"type":1,"text_item":{"text":"x"}}""").apply { remove("message_id"); addProperty("seq", 7) }
        assertEquals("seq:7", WeixinEvents.message(noId)!!.messageId)
    }

    @Test
    fun `密钥解析`() {
        assertEquals(keyHex, WeixinEvents.hexKey(keyHex.uppercase()))
        assertNull(WeixinEvents.hexKey("xyz"))
        assertEquals(keyHex, WeixinEvents.base64Key(Base64.getEncoder().encodeToString(key16)))
        assertEquals(keyHex, WeixinEvents.base64Key(Base64.getEncoder().encodeToString(keyHex.toByteArray())))
        assertNull(WeixinEvents.base64Key(Base64.getEncoder().encodeToString(ByteArray(8))))
        assertNull(WeixinEvents.base64Key("not base64!!"))
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.weixin.WeixinEventsTest'`
Expected: 编译失败，`WeixinEvents` 未定义。

- [ ] **Step 3: 实现**

```kotlin
package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.json.obj
import com.github.izerui.imux.bridge.json.objects
import com.github.izerui.imux.bridge.json.primitiveText
import com.github.izerui.imux.bridge.json.string
import com.google.gson.JsonObject
import java.nio.charset.StandardCharsets
import java.util.Base64

/** 消息里的一个附件：只记下载所需信息，路由器放行后才下载。[aesKeyHex] 为 16 字节密钥的小写 hex；图片可能没有（明文）。 */
data class WeixinMediaRef(
    val kind: AttachmentKind,
    val fullUrl: String?,
    val encryptQueryParam: String?,
    val aesKeyHex: String?,
    val fileName: String?,
) {
    override fun toString(): String = "WeixinMediaRef(kind=$kind, fileName=$fileName)"
}

/** 一条用户单聊消息的解析结果。 */
data class WeixinMessage(
    val messageId: String?,
    val fromUserId: String,
    val createTimeMillis: Long?,
    val contextToken: String?,
    val text: String?,
    val media: List<WeixinMediaRef>,
    val quote: String?,
) {
    override fun toString(): String = "WeixinMessage(messageId=$messageId, fromUserId=$fromUserId, media=${media.size}, quote=${quote != null})"
}

/** 微信消息解析（官方 api/types.ts、media-download.ts、pic-decrypt.ts）。纯函数。 */
object WeixinEvents {
    private const val MESSAGE_TYPE_USER = 1
    private const val ITEM_TEXT = 1
    private const val ITEM_IMAGE = 2
    private const val ITEM_VOICE = 3
    private const val ITEM_FILE = 4
    private const val ITEM_VIDEO = 5
    private val HEX32 = Regex("[0-9a-f]{32}")

    /** 只接受用户发来的单聊消息（message_type == 1 且 group_id 为空）；无文字无附件返回 null。 */
    fun message(json: JsonObject): WeixinMessage? {
        if (json.primitiveText("message_type")?.toIntOrNull() != MESSAGE_TYPE_USER) return null
        if (!json.primitiveText("group_id").isNullOrEmpty()) return null
        val from = json.string("from_user_id")?.takeIf(String::isNotEmpty) ?: return null
        val items = json.objects("item_list")
        val text = items.mapNotNull(::itemText).joinToString("\n").takeIf(String::isNotBlank)
        val media = items.mapNotNull(::mediaRef)
        if (text == null && media.isEmpty()) return null
        return WeixinMessage(
            // message_id 是 uint64，Gson 按原文取字符串，不会丢精度；没有时用 seq 兜底（DeepTutor 做法）
            messageId = json.primitiveText("message_id")?.takeIf(String::isNotEmpty) ?: json.primitiveText("seq")?.let { "seq:$it" },
            fromUserId = from,
            createTimeMillis = json.primitiveText("create_time_ms")?.toLongOrNull(),
            contextToken = json.string("context_token")?.takeIf(String::isNotEmpty),
            text = text,
            media = media,
            quote = items.firstNotNullOfOrNull(::quote),
        )
    }

    /** 16 字节密钥的 hex（图片 image_item.aeskey）。 */
    fun hexKey(hex: String): String? = hex.trim().lowercase().takeIf { it.matches(HEX32) }

    /** media.aes_key：base64 解码后 16 字节即密钥，或 32 个 hex 字符（官方 pic-decrypt.ts:40-52）。 */
    fun base64Key(value: String): String? {
        val decoded = runCatching { Base64.getDecoder().decode(value.trim()) }.getOrNull() ?: return null
        return when (decoded.size) {
            16 -> decoded.joinToString("") { "%02x".format(it) }
            32 -> hexKey(String(decoded, StandardCharsets.US_ASCII))
            else -> null
        }
    }

    private fun type(item: JsonObject): Int? = item.primitiveText("type")?.toIntOrNull()

    private fun itemText(item: JsonObject): String? =
        when (type(item)) {
            ITEM_TEXT -> item.obj("text_item")?.string("text")
            ITEM_VOICE -> item.obj("voice_item")?.string("text")
            else -> null
        }?.takeIf(String::isNotBlank)

    private fun mediaRef(item: JsonObject): WeixinMediaRef? =
        when (type(item)) {
            ITEM_IMAGE ->
                item.obj("image_item")?.let { image ->
                    // 图片优先用 image_item.aeskey；都没有时为明文（官方 media-download.ts:43-63）
                    val key = image.string("aeskey")?.let(::hexKey) ?: image.obj("media")?.string("aes_key")?.let(::base64Key)
                    ref(AttachmentKind.IMAGE, image.obj("media"), key, null, keyRequired = false)
                }

            // 有转写就当文字，不再下载音频
            ITEM_VOICE ->
                item.obj("voice_item")?.takeIf { it.string("text").isNullOrBlank() }?.let { voice ->
                    ref(AttachmentKind.AUDIO, voice.obj("media"), mediaKey(voice), null, keyRequired = true)
                }

            ITEM_FILE ->
                item.obj("file_item")?.let { file ->
                    ref(AttachmentKind.FILE, file.obj("media"), mediaKey(file), file.string("file_name"), keyRequired = true)
                }

            ITEM_VIDEO -> item.obj("video_item")?.let { video -> ref(AttachmentKind.VIDEO, video.obj("media"), mediaKey(video), null, keyRequired = true) }
            else -> null
        }

    private fun mediaKey(holder: JsonObject): String? = holder.obj("media")?.string("aes_key")?.let(::base64Key)

    /** 非图片缺密钥时官方不下载，这里也不作为附件。 */
    private fun ref(
        kind: AttachmentKind,
        media: JsonObject?,
        keyHex: String?,
        fileName: String?,
        keyRequired: Boolean,
    ): WeixinMediaRef? {
        media ?: return null
        val fullUrl = media.string("full_url")?.takeIf(String::isNotEmpty)
        val param = media.string("encrypt_query_param")?.takeIf(String::isNotEmpty)
        if (fullUrl == null && param == null) return null
        if (keyRequired && keyHex == null) return null
        return WeixinMediaRef(kind, fullUrl, param, keyHex, fileName?.takeIf(String::isNotEmpty))
    }

    /** 被引用消息的文字；取不到用摘要标题。只带 svr_id 的新版引用不查（官方需本地缓存）。 */
    private fun quote(item: JsonObject): String? {
        val ref = item.obj("ref_msg") ?: return null
        val quoted = ref.obj("message_item")?.let(::itemText)
        return (quoted ?: ref.string("title"))?.trim()?.takeIf(String::isNotEmpty)
    }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.weixin.WeixinEventsTest'`
Expected: PASS。

- [ ] **Step 5: 不提交。**

---

### Task 5: WeixinMedia（附件下载与解密）

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/weixin/WeixinMedia.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/weixin/WeixinMediaTest.kt`

**Interfaces:**
- Consumes: Task 4 `WeixinMediaRef`；`HttpFileDownload` 与扩展 `download(url, maxBytes)`（`channel/Transports.kt`）。
- Produces（Task 6 使用）：`class WeixinAttachment(ref: WeixinMediaRef, download: HttpFileDownload) : InboundAttachment`；`fun weixinDownloadUrl(ref: WeixinMediaRef): String?`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class WeixinMediaTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val keyHex = "000102030405060708090a0b0c0d0e0f"
    private val key = ByteArray(16) { it.toByte() }
    private val plain = "图片内容 hello".toByteArray()
    private val requests = CopyOnWriteArrayList<Pair<String, Long>>()

    private fun encrypt(data: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(data)
        }

    private fun downloader(bytes: ByteArray) =
        HttpFileDownload { url, maxBytes, _ ->
            requests += url to maxBytes
            if (bytes.size > maxBytes) {
                DownloadResult.TooLarge
            } else {
                val file = temp.newFile().toPath()
                Files.write(file, bytes)
                DownloadResult.Ok(file, "x.bin")
            }
        }

    @Test
    fun `下载地址：full_url 优先，否则拼 CDN 并编码`() {
        assertEquals("https://cdn/f", weixinDownloadUrl(WeixinMediaRef(AttachmentKind.FILE, "https://cdn/f", "p", keyHex, null)))
        assertEquals(
            "https://novac2c.cdn.weixin.qq.com/c2c/download?encrypted_query_param=a%2Bb%2Fc%3D",
            weixinDownloadUrl(WeixinMediaRef(AttachmentKind.FILE, null, "a+b/c=", keyHex, null)),
        )
    }

    @Test
    fun `AES-128-ECB 解密，文件名用消息里的`() =
        runBlocking {
            val attachment = WeixinAttachment(WeixinMediaRef(AttachmentKind.FILE, "https://cdn/f", null, keyHex, "报告.pdf"), downloader(encrypt(plain)))
            assertEquals(AttachmentKind.FILE, attachment.kind)
            val ok = attachment.fetch(100) as FetchResult.Ok
            assertArrayEquals(plain, Files.readAllBytes(ok.file.path))
            assertEquals("报告.pdf", ok.file.suggestedName)
            assertEquals("https://cdn/f" to 116L, requests.single())
        }

    @Test
    fun `图片没有密钥按明文；文件名交给落盘`() =
        runBlocking {
            val ok = WeixinAttachment(WeixinMediaRef(AttachmentKind.IMAGE, "https://cdn/i", null, null, null), downloader(plain)).fetch(100) as FetchResult.Ok
            assertArrayEquals(plain, Files.readAllBytes(ok.file.path))
            assertEquals(null, ok.file.suggestedName)
            assertEquals(100L, requests.single().second)
        }

    @Test
    fun `超限、解密失败与下载失败`() =
        runBlocking {
            val ref = WeixinMediaRef(AttachmentKind.FILE, "https://cdn/f", null, keyHex, null)
            assertEquals(FetchResult.TooLarge, WeixinAttachment(ref, downloader(encrypt(ByteArray(200)))).fetch(100))
            val bad = WeixinAttachment(ref, downloader(ByteArray(15)))
            val failed = bad.fetch(100) as FetchResult.Failed
            assertFalse("原因不含地址", failed.reason.contains("cdn"))
            assertTrue("解密失败的临时文件要删掉", temp.root.listFiles()!!.none { it.length() == 15L })
            assertEquals(
                FetchResult.Failed("HTTP 403"),
                WeixinAttachment(ref, HttpFileDownload { _, _, _ -> DownloadResult.Failed("HTTP 403") }).fetch(100),
            )
        }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.weixin.WeixinMediaTest'`
Expected: 编译失败。

- [ ] **Step 3: 实现**

```kotlin
package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.channel.download
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import com.github.izerui.imux.bridge.core.InboundAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException

private const val CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c"

/** 密文比明文最多多一个 16 字节的 PKCS7 填充块。 */
private const val AES_BLOCK = 16

/** 下载地址：服务端给了 full_url 就用，否则按 encrypt_query_param 拼 CDN 地址（官方 cdn-url.ts）。 */
fun weixinDownloadUrl(ref: WeixinMediaRef): String? =
    ref.fullUrl ?: ref.encryptQueryParam?.let { "$CDN_BASE_URL/download?encrypted_query_param=${URLEncoder.encode(it, StandardCharsets.UTF_8)}" }

/** 微信消息里的一个附件：[fetch] 时才下载（不带认证头），有密钥则 AES-128-ECB 解密为明文（官方 aes-ecb.ts）。 */
class WeixinAttachment(
    private val ref: WeixinMediaRef,
    private val download: HttpFileDownload,
) : InboundAttachment {
    override val kind: AttachmentKind
        get() = ref.kind

    override suspend fun fetch(maxBytes: Long): FetchResult {
        val url = weixinDownloadUrl(ref) ?: return FetchResult.Failed("no url")
        val key = ref.aesKeyHex
        val limit = if (key == null) maxBytes else maxBytes + AES_BLOCK
        return when (val result = download.download(url, limit)) {
            DownloadResult.TooLarge -> FetchResult.TooLarge
            is DownloadResult.Failed -> FetchResult.Failed(result.reason)
            is DownloadResult.Ok -> if (key == null) FetchResult.Ok(FetchedFile(result.path, ref.fileName)) else decrypt(result.path, key, maxBytes)
        }
    }

    private suspend fun decrypt(
        path: Path,
        keyHex: String,
        maxBytes: Long,
    ): FetchResult =
        withContext(Dispatchers.IO) {
            try {
                val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexBytes(keyHex), "AES"))
                val plain = cipher.doFinal(Files.readAllBytes(path))
                if (plain.size > maxBytes) {
                    Files.deleteIfExists(path)
                    return@withContext FetchResult.TooLarge
                }
                Files.write(path, plain)
                FetchResult.Ok(FetchedFile(path, ref.fileName))
            } catch (e: CancellationException) {
                runCatching { Files.deleteIfExists(path) }
                throw e
            } catch (e: Exception) {
                runCatching { Files.deleteIfExists(path) }
                FetchResult.Failed("decrypt ${e.javaClass.simpleName}")
            }
        }

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
```

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.weixin.WeixinMediaTest'`
Expected: PASS。

- [ ] **Step 5: 不提交。**

---

### Task 6: WeixinConnection（长轮询、发送、状态）

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/weixin/WeixinConnection.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/weixin/WeixinConnectionTest.kt`

**Interfaces:**
- Consumes: Task 2 `WeixinApi`、`WeixinResult`；Task 4 `WeixinEvents`、`WeixinMessage`；Task 5 `WeixinAttachment`；`WeixinFakeHttp`。
- Produces（Task 7 使用）：

```kotlin
class WeixinConnection(
    override val robotId: RobotId,
    private val api: WeixinApi,
    private val baseUrl: String,
    private val token: String,
    private val scope: CoroutineScope,
    private val download: HttpFileDownload = JdkHttpFileDownload(),
    private val now: () -> Long = System::currentTimeMillis,
    private val retryDelayMillis: Long = 2_000,
    private val backoffMillis: Long = 30_000,
    private val limiter: SlidingWindowLimiter = SlidingWindowLimiter(3, 1_000, now),
    private val maxRateWaitMillis: Long = 20_000,
) : ChannelConnection
```

规则（设计第 5、7 节）：
- 首次 getupdates 成功报 Online；游标只在内存、非空才替换；丢弃 `create_time_ms < connectedAt - 60_000`；`message_id` 去重（容量 1000）；单协程按序交出；按 `from_user_id` 记最新 context_token（只在内存）。
- 超时（`Network.timeout`）不计失败，立即下一轮；其它失败计数 +1：之前在线则报 Connecting；累计 5 次起报 `Retrying(原因)`（原因变化时再报）；第 3、6、9…次等 `backoffMillis`，其余等 `retryDelayMillis`；成功后清零并报 Online。
- getupdates 或 send 返回 `-14` → 报 `Failed("<ret>/<errcode> <errmsg>")`，停止轮询（取消运行，不再发请求）。
  > **已被最终评审修正取代**：-14 的处理以 Global Constraints 与设计文档第 5、7 节为准（getupdates 暂停后继续；send 去掉 context_token 重发一次）。本任务下文代码与测试中 -14 的部分是原稿，不再适用。
- 发送：限频 → `api.send(..., contextTokens[chatId])`；Ok → Accepted 并清除发送受阻；Network → Unknown；Error 非 -14 → `SendFailing("bridge.weixin.sendRejected", code)` 并 `Rejected("<code> <errmsg>")`。
- 状态：锁内只入队、锁外回调；受阻跨重连保留，只在 Online 时显示；stop 清除。

- [ ] **Step 1: 写失败测试**

```kotlin
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

                "/sendmessage" in url -> sendAnswer
                else -> 404 to "{}"
            }
        }

    @After
    fun tearDown() = scope.cancel()

    private fun connection(limiter: SlidingWindowLimiter = SlidingWindowLimiter(3, 1_000)) =
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
    fun `收消息返回 -14 报失败并停止轮询`() =
        runBlocking {
            start(connection())
            polls.send("""{"ret":-14,"errmsg":"session timeout"}""")
            awaitLast(ConnectionStatus.Failed("-14/0 session timeout"))
            val count = pollCount()
            polls.send(updates())
            polls.send(updates())
            assertEquals("停止后不再请求", count, pollCount())
        }

    @Test
    fun `发送返回 -14 报失败并停止轮询`() =
        runBlocking {
            val conn = connection()
            start(conn)
            polls.send(updates())
            awaitLast(ConnectionStatus.Online)
            sendAnswer = 200 to """{"ret":-14,"errmsg":"x"}"""
            assertTrue(send(conn) is DeliveryResult.Rejected)
            awaitLast(ConnectionStatus.Failed("-14/0 x"))
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
    fun `网络异常返回结果不明，状态不变`() =
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
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.weixin.WeixinConnectionTest'`
Expected: 编译失败。

- [ ] **Step 3: 实现**

```kotlin
package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ConnectionListener
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.channel.JdkHttpFileDownload
import com.github.izerui.imux.bridge.channel.SlidingWindowLimiter
import com.github.izerui.imux.bridge.core.DeliveryResult
import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * 微信长轮询连接（docs/superpowers/specs/2026-10-30-weixin-channel-design.md 第 5、7 节）。
 *
 * 收消息用 getupdates 长轮询（服务端挂起即保活，没有心跳接口），发消息用 sendmessage，两者互不依赖。
 * 不补历史：游标只在内存，每次 start 从空游标开始，丢弃连接前一分钟以前的消息。
 * 单个协程按到达顺序交给 [InboundSink]；按用户记最新 context_token 供发送带回。
 */
class WeixinConnection(
    override val robotId: RobotId,
    private val api: WeixinApi,
    private val baseUrl: String,
    private val token: String,
    private val scope: CoroutineScope,
    private val download: HttpFileDownload = JdkHttpFileDownload(),
    private val now: () -> Long = System::currentTimeMillis,
    private val retryDelayMillis: Long = 2_000,
    private val backoffMillis: Long = 30_000,
    private val limiter: SlidingWindowLimiter = SlidingWindowLimiter(3, 1_000, now),
    private val maxRateWaitMillis: Long = 20_000,
) : ChannelConnection {
    /** 官方按 4000 字符分段；路由器按 UTF-8 字节切，4000 字节保证每段不超过 4000 字符。 */
    override val maxTextBytes: Int = 4_000

    /**
     * 一次 start() 到 stop() 的运行。状态先在 [statusLock] 内排进 [statuses]，再由该运行自己的通知协程按序交给监听器：
     * 绝不在持锁时回调监听器。调用方常在自己的锁里调 stop()，而监听器又会去拿那把锁，持锁回调会死锁。
     */
    private class Run(
        val job: Job,
        val statuses: Channel<ConnectionStatus>,
    )

    private val seen = LinkedHashSet<String>()

    /** 每个用户最新的 context_token，只在内存：缺失时按官方做法照发。 */
    private val contextTokens = ConcurrentHashMap<String, String>()

    @Volatile
    private var run: Run? = null

    /** 下面几项只在 [statusLock] 内读写；状态入队也在锁内，保证监听器收到的顺序与状态变化的顺序一致。 */
    private val statusLock = Any()

    /** 链路的真实状态，只由运行循环、-14 和 stop() 写入。 */
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
        // 只入队不回调，不会阻塞在监听器上
        synchronized(statusLock) {
            linkStatus = ConnectionStatus.Stopped
            sendBlocked = null
            shown = ConnectionStatus.Stopped
            current.statuses.trySend(ConnectionStatus.Stopped)
            current.statuses.close()
        }
    }

    override suspend fun send(message: OutboundMessage): DeliveryResult {
        val current = run ?: return DeliveryResult.Rejected("offline")
        var wait = limiter.tryAcquire(message.chatId)
        if (wait > 0) {
            if (wait > maxRateWaitMillis) return DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED)
            delay(wait)
            wait = limiter.tryAcquire(message.chatId)
            if (wait > 0) return DeliveryResult.Rejected(DeliveryResult.RATE_LIMITED)
        }
        return when (val result = api.send(baseUrl, token, message.chatId, message.text, contextTokens[message.chatId])) {
            is WeixinResult.Ok -> {
                blockSend(current, null)
                DeliveryResult.Accepted
            }

            is WeixinResult.Network -> DeliveryResult.Unknown
            is WeixinResult.Error -> {
                if (result.expired) {
                    expire(current, result)
                } else {
                    // 官方没定义其它错误码；参考工程的经验值互相矛盾，统一如实提示（设计第 7 节）
                    blockSend(current, ConnectionStatus.SendFailing("bridge.weixin.sendRejected", result.code))
                }
                DeliveryResult.Rejected(reason(result))
            }
        }
    }

    /** -14：bot_token 失效，重连也没用，报失败并停止轮询（需要重新扫码）。 */
    private fun expire(
        current: Run,
        error: WeixinResult.Error,
    ) = synchronized(statusLock) {
        reportLinkLocked(current, ConnectionStatus.Failed(reason(error)))
        current.job.cancel()
    }

    private fun reason(error: WeixinResult.Error): String = "${error.code} ${error.message}".trim()

    /**
     * 上报链路状态。发送受阻标记保留（只在 stop() 时清除）：重新在线后如实显示之前记下的受阻。
     * 与 stop() 同锁：被取消的运行在锁内看到自己已不活跃，不会再把状态改回去。
     */
    private fun reportLink(
        current: Run,
        status: ConnectionStatus,
    ) = synchronized(statusLock) { reportLinkLocked(current, status) }

    /** 持 [statusLock] 调用。 */
    private fun reportLinkLocked(
        current: Run,
        status: ConnectionStatus,
    ) {
        if (!current.job.isActive) return
        linkStatus = status
        val display = if (status == ConnectionStatus.Online) sendBlocked ?: ConnectionStatus.Online else status
        shown = display
        current.statuses.trySend(display)
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

    /**
     * 长轮询：成功即在线；超时视为没有新消息，立即下一轮；其它失败 2 秒后重试，每连续 3 次退避 30 秒（官方 monitor.ts），
     * 连续失败 [REPORT_RETRYING_AFTER] 次起如实报“正在重试”。
     */
    private suspend fun runLoop(
        owner: Run,
        sink: InboundSink,
    ) {
        coroutineScope {
            val inbound = Channel<WeixinMessage>(Channel.UNLIMITED)
            launch { for (message in inbound) deliver(message, sink) }
            val connectedAt = now()
            var cursor = ""
            var failures = 0
            var online = false
            var retrying: String? = null
            reportLink(owner, ConnectionStatus.Connecting)
            while (isActive) {
                val failure: String =
                    when (val result = api.updates(baseUrl, token, cursor)) {
                        is WeixinResult.Ok -> {
                            failures = 0
                            retrying = null
                            if (!online) {
                                online = true
                                reportLink(owner, ConnectionStatus.Online)
                            }
                            result.value.cursor?.let { cursor = it }
                            result.value.messages.forEach { accept(it, connectedAt, inbound) }
                            continue
                        }

                        is WeixinResult.Network -> if (result.timeout) continue else result.reason
                        is WeixinResult.Error -> {
                            if (result.expired) {
                                expire(owner, result)
                                break
                            }
                            reason(result)
                        }
                    }
                failures++
                if (online) {
                    online = false
                    reportLink(owner, ConnectionStatus.Connecting)
                }
                if (failures >= REPORT_RETRYING_AFTER && retrying != failure) {
                    retrying = failure
                    reportLink(owner, ConnectionStatus.Retrying(failure))
                }
                delay(if (failures % BACKOFF_EVERY == 0) backoffMillis else retryDelayMillis)
            }
            inbound.close()
        }
    }

    private fun accept(
        raw: com.google.gson.JsonObject,
        connectedAt: Long,
        inbound: Channel<WeixinMessage>,
    ) {
        val message = WeixinEvents.message(raw) ?: return
        // 空游标可能推送积压的历史；断开期间的消息不补（AGENTS.md）。留一分钟余量防时钟偏差把新消息误丢
        val created = message.createTimeMillis
        if (created != null && created < connectedAt - HISTORY_MARGIN_MILLIS) return
        val id = message.messageId
        if (id != null && !markSeen(id)) return
        message.contextToken?.let { contextTokens[message.fromUserId] = it }
        inbound.trySend(message)
    }

    private fun deliver(
        message: WeixinMessage,
        sink: InboundSink,
    ) {
        val attachments = message.media.map { WeixinAttachment(it, download) }
        try {
            sink.accept(
                InboundMessage(
                    robotId,
                    message.messageId ?: "weixin-${UUID.randomUUID()}",
                    message.fromUserId,
                    message.fromUserId,
                    message.text.orEmpty(),
                    attachments,
                    message.quote,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 交出失败不能挡住后面的消息
        }
    }

    private fun markSeen(id: String): Boolean =
        synchronized(seen) {
            if (!seen.add(id)) return false
            while (seen.size > SEEN_CAPACITY) seen.remove(seen.first())
            true
        }

    private companion object {
        const val REPORT_RETRYING_AFTER = 5
        const val BACKOFF_EVERY = 3
        const val SEEN_CAPACITY = 1000
        const val HISTORY_MARGIN_MILLIS = 60_000L
    }
}
```

说明：`when` 分支里 `continue`/`break` 在 Kotlin 循环内的 `when` 表达式中可用（Kotlin 2.2+ 允许在 when 中直接 break/continue）；若当前 Kotlin 版本不支持，改为 `if/else` 结构，语义不变。

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.weixin.*'`（连接测试连跑 3 次）
Expected: 全部 PASS。

- [ ] **Step 5: 不提交。**

---

### Task 7: 工厂、登记、文案、隔离与全量验证

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/weixin/WeixinChannelFactory.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/channel/ChannelFactories.kt`
- Modify: `src/main/resources/messages/ImuxBundle*.properties`（10 个）
- Modify: `src/test/kotlin/com/github/izerui/imux/bridge/channel/ChannelFactoriesTest.kt`
- Modify: `src/test/kotlin/com/github/izerui/imux/bridge/channel/ChannelIsolationSourceTest.kt`（若“渠道子包之间互不引用”已断言 `containsAll(listOf("wecom","feishu","dingtalk"))`，加上 `"weixin"`）

**Interfaces:**
- Consumes: `WeixinOnboarding`、`WeixinCredentialKeys`（Task 3）；`WeixinApi`（Task 2）；`WeixinConnection`（Task 6）。
- Produces: `object WeixinChannelFactory : ChannelFactory`；`ChannelFactories.all()` 末尾加上它。

- [ ] **Step 1: 写失败测试**

在 `ChannelFactoriesTest` 末尾加（补 import `...weixin.WeixinChannelFactory`、`...weixin.WeixinConnection`、`...weixin.WeixinOnboarding`）：

```kotlin
    @Test
    fun `微信已登记，扫码说明用自己的文案`() {
        assertEquals(WeixinChannelFactory, ChannelFactories.forKind(ChannelKind.WEIXIN))
        assertEquals("bridge.add.scan.steps.weixin", WeixinChannelFactory.scanStepsKey)
        assertTrue(WeixinChannelFactory.onboarding() is WeixinOnboarding)
    }

    @Test
    fun `按凭据建立微信连接`() {
        val connection =
            WeixinChannelFactory.connect(
                RobotId("r"),
                mapOf("bot_token" to "BT", "base_url" to "https://x.weixin.qq.com", "bot_id" to "b", "user_id" to "u"),
                CoroutineScope(Dispatchers.Default),
            )
        assertTrue(connection is WeixinConnection)
        assertEquals(RobotId("r"), connection.robotId)
        assertEquals(4_000, connection.maxTextBytes)
    }
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.ChannelFactoriesTest'`
Expected: 编译失败。

- [ ] **Step 3: 工厂与登记**

```kotlin
package com.github.izerui.imux.bridge.channel.weixin

import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ChannelFactory
import com.github.izerui.imux.bridge.channel.ChannelKind
import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CoroutineScope

object WeixinChannelFactory : ChannelFactory {
    override val kind: ChannelKind = ChannelKind.WEIXIN

    /** 扫码人即本人，不需要配对码，说明文字与企业微信不同。 */
    override val scanStepsKey: String = "bridge.add.scan.steps.weixin"

    override fun onboarding(): ChannelOnboarding = WeixinOnboarding()

    override fun connect(
        robotId: RobotId,
        credentials: Map<String, String>,
        scope: CoroutineScope,
    ): ChannelConnection =
        WeixinConnection(
            robotId = robotId,
            api = WeixinApi(),
            baseUrl = WeixinApi.normalizeBaseUrl(credentials[WeixinCredentialKeys.BASE_URL]),
            token = credentials.getValue(WeixinCredentialKeys.BOT_TOKEN),
            scope = scope,
        )
}
```

`ChannelFactories.all()` 改为 `listOf(WecomChannelFactory, FeishuChannelFactory, DingtalkChannelFactory, WeixinChannelFactory)`（补 import）。

- [ ] **Step 4: 文案（10 种语言）**

`bridge.add.scan.steps.weixin` 插在 `bridge.add.scan.steps.dingtalk=` 那一行之后；`bridge.weixin.verifyCode`、`bridge.weixin.sendRejected` 插在 `bridge.dingtalk.noStaffId=` 那一行之后。在仓库根目录运行：

```bash
python3 - <<'EOF'
import pathlib
texts = {
 "": [r"1. Open {0} on your phone and scan\n2. Confirm the login on your phone\n3. Setup finishes automatically, no pairing code needed",
      "WeChat asked for a verification code on your phone, which is not supported yet. Please scan again later.",
      "WeChat rejected the message: the session may have expired or too many messages were sent. Send any message to the bot in WeChat, then try again ({0})"],
 "_zh_CN": [r"1. 打开手机{0}，扫一扫\n2. 在手机上确认登录\n3. 扫码后自动完成，无需配对码",
      "微信要求在手机上输入验证码，本版暂不支持，请稍后重新扫码。",
      "微信拒收：可能是会话过期或发送过多，请在微信给机器人发任意一条消息后再试（{0}）"],
 "_zh_TW": [r"1. 打開手機{0}，掃一掃\n2. 在手機上確認登入\n3. 掃碼後自動完成，無需配對碼",
      "微信要求在手機上輸入驗證碼，本版暫不支援，請稍後重新掃碼。",
      "微信拒收：可能是工作階段過期或傳送過多，請在微信傳任意一則訊息給機器人後再試（{0}）"],
 "_ja": [r"1. スマートフォンの{0}でスキャン\n2. スマートフォンでログインを確認\n3. スキャン後は自動で完了（ペアリングコード不要）",
      "WeChat がスマートフォンでの確認コード入力を求めていますが、現在は未対応です。しばらくしてから再度スキャンしてください。",
      "WeChat に拒否されました。セッションの期限切れまたは送信過多の可能性があります。WeChat でボットに何かメッセージを送ってから再試行してください（{0}）"],
 "_ko": [r"1. 휴대폰에서 {0}을(를) 열고 스캔\n2. 휴대폰에서 로그인 확인\n3. 스캔 후 자동으로 완료되며 페어링 코드가 필요 없습니다",
      "WeChat에서 휴대폰 인증 코드 입력을 요구하지만 아직 지원하지 않습니다. 잠시 후 다시 스캔하세요.",
      "WeChat에서 거부했습니다. 세션이 만료되었거나 너무 많이 보냈을 수 있습니다. WeChat에서 봇에게 아무 메시지나 보낸 뒤 다시 시도하세요 ({0})"],
 "_de": [r"1. {0} auf dem Smartphone öffnen und scannen\n2. Anmeldung auf dem Smartphone bestätigen\n3. Danach automatisch abgeschlossen, kein Kopplungscode nötig",
      "WeChat verlangt einen Bestätigungscode auf dem Smartphone; das wird noch nicht unterstützt. Bitte später erneut scannen.",
      "WeChat hat die Nachricht abgelehnt: Die Sitzung ist evtl. abgelaufen oder es wurde zu viel gesendet. Schreiben Sie dem Bot in WeChat eine beliebige Nachricht und versuchen Sie es erneut ({0})"],
 "_es": [r"1. Abre {0} en el móvil y escanea\n2. Confirma el inicio de sesión en el móvil\n3. Se completa automáticamente, sin código de emparejamiento",
      "WeChat pide un código de verificación en el móvil, que aún no es compatible. Vuelve a escanear más tarde.",
      "WeChat rechazó el mensaje: la sesión pudo caducar o se enviaron demasiados. Envía cualquier mensaje al bot en WeChat y vuelve a intentarlo ({0})"],
 "_fr": [r"1. Ouvrez {0} sur votre téléphone et scannez\n2. Confirmez la connexion sur votre téléphone\n3. La suite est automatique, aucun code d’appairage requis",
      "WeChat demande un code de vérification sur le téléphone, ce qui n’est pas encore pris en charge. Veuillez scanner de nouveau plus tard.",
      "WeChat a refusé le message : la session a peut-être expiré ou trop de messages ont été envoyés. Envoyez un message quelconque au bot dans WeChat, puis réessayez ({0})"],
 "_pt_BR": [r"1. Abra o {0} no celular e escaneie\n2. Confirme o login no celular\n3. Conclui automaticamente, sem código de pareamento",
      "O WeChat pediu um código de verificação no celular, o que ainda não é suportado. Escaneie novamente mais tarde.",
      "O WeChat recusou a mensagem: a sessão pode ter expirado ou foram enviadas mensagens demais. Envie qualquer mensagem ao bot no WeChat e tente novamente ({0})"],
 "_ru": [r"1. Откройте {0} на телефоне и отсканируйте\n2. Подтвердите вход на телефоне\n3. Дальше всё автоматически, код привязки не нужен",
      "WeChat запросил код подтверждения на телефоне — это пока не поддерживается. Отсканируйте снова позже.",
      "WeChat отклонил сообщение: возможно, сеанс истёк или отправлено слишком много. Напишите боту в WeChat любое сообщение и повторите попытку ({0})"],
}
for suffix, (steps, verify, rejected) in texts.items():
    path = pathlib.Path(f"src/main/resources/messages/ImuxBundle{suffix}.properties")
    lines = path.read_text(encoding="utf-8").split("\n")
    assert not any(l.startswith("bridge.weixin.") or l.startswith("bridge.add.scan.steps.weixin=") for l in lines), path
    out = []
    for line in lines:
        out.append(line)
        if line.startswith("bridge.add.scan.steps.dingtalk="):
            out.append("bridge.add.scan.steps.weixin=" + steps)
        if line.startswith("bridge.dingtalk.noStaffId="):
            out.append("bridge.weixin.verifyCode=" + verify)
            out.append("bridge.weixin.sendRejected=" + rejected)
    assert len(out) == len(lines) + 3, path
    path.write_text("\n".join(out), encoding="utf-8")
EOF
```

- [ ] **Step 5: 运行，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.bridge.channel.*' --tests 'com.github.izerui.imux.ImuxBundleTest'`
Expected: 全部 PASS（含隔离测试、文案键一致）。

- [ ] **Step 6: 全量验证**

Run: `./gradlew test`，然后对本计划新建/修改的 Kotlin 文件跑 IDE `lint_files`（无新增错误），再 `./gradlew verifyPlugin`（各目标 IDE Compatible）。

- [ ] **Step 7: 不提交。**
