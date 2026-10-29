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

    /** ticket 是连接凭据，不进日志或异常消息。 */
    override fun toString(): String = "DingtalkEndpoint(endpoint=$endpoint, ticket=***)"
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
