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
        // 服务端给 0 或负数时不能忙等
        interval = (begin.primitiveText("interval")?.toLongOrNull() ?: DEFAULT_INTERVAL_SECONDS).coerceAtLeast(1) * 1_000
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
