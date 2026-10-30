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
import java.net.http.HttpConnectTimeoutException
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
        /** -14：会话过期（官方 session-guard.ts 暂停 1 小时后继续，不要求重新扫码）。 */
        val expired: Boolean
            get() = ret == EXPIRED || errcode == EXPIRED

        /** 界面与原因里显示的错误码，形如 `-2/0`；HTTP 非 2xx 且没有 ret、errcode 时为 `HTTP 502`，否则 `0/0` 让人看不出原因。 */
        val code: String
            get() = if (ret == null && errcode == null && status !in 200..299) "HTTP $status" else "${ret ?: 0}/${errcode ?: 0}"
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
) {
    /** 原始消息含正文与 context_token，不能进日志。 */
    override fun toString(): String = "WeixinUpdates(messages=${messages.size}, cursor=${cursor?.let { "***" }})"
}

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
                // 异常消息可能带完整地址，只留类名。连接超时是连不上，不是长轮询挂满，要计入失败
                return if (cause is HttpTimeoutException && cause !is HttpConnectTimeoutException) {
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
