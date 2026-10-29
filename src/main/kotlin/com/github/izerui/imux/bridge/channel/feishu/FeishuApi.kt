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
import java.util.Base64
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
        val DEFAULT = FeishuClientConfig(
            reconnectCount = -1,
            reconnectIntervalSeconds = 120,
            reconnectNonceSeconds = 30,
            pingIntervalSeconds = 120
        )

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
) {
    /** 地址带 ticket 等一次性凭据，不能进入日志或异常消息。 */
    override fun toString(): String = "FeishuEndpoint(url=***, config=$config)"
}

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
        return when (val result = request {
            http.post(
                "${domain.openBase}/callback/ws/endpoint",
                JSON_TYPE,
                body.toString(),
                mapOf("locale" to "zh")
            )
        }) {
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
            when (val result = request {
                http.post(
                    "${domain.openBase}/open-apis/auth/v3/tenant_access_token/internal",
                    JSON_TYPE,
                    body.toString()
                )
            }) {
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
        when (val result = authorized {
            http.post(
                "${domain.openBase}/open-apis/im/v1/messages?receive_id_type=open_id",
                JSON_TYPE,
                body,
                bearer(it)
            )
        }) {
            is FeishuResult.Ok -> FeishuResult.Ok(Unit)
            is FeishuResult.Error -> result
            is FeishuResult.Network -> result
        }

    /** 被引用消息的文字；取不到或不是文字类消息时返回 null。 */
    suspend fun messageText(messageId: String): String? {
        val result =
            authorized { http.get("${domain.openBase}/open-apis/im/v1/messages/${encode(messageId)}", bearer(it)) }
        val item = (result as? FeishuResult.Ok)?.value?.obj("data")?.objects("items")?.firstOrNull() ?: return null
        return FeishuEvents.quoteText(item.string("msg_type"), item.obj("body")?.string("content"))
    }

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
        return when (val result = authorized {
            http.post(
                "${domain.openBase}/open-apis/speech_to_text/v1/speech/file_recognize",
                JSON_TYPE,
                body.toString(),
                bearer(it)
            )
        }) {
            is FeishuResult.Ok -> FeishuResult.Ok(result.value.obj("data")?.string("recognition_text").orEmpty())
            is FeishuResult.Error -> result
            is FeishuResult.Network -> result
        }
    }

    fun resourceUrl(
        messageId: String,
        fileKey: String,
        type: String,
    ): String = "${domain.openBase}/open-apis/im/v1/messages/${encode(messageId)}/resources/${encode(fileKey)}?type=${
        encode(type)
    }"

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
