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
import kotlin.coroutines.cancellation.CancellationException

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
        interval = (begin.primitiveText("interval")?.toLongOrNull() ?: DEFAULT_INTERVAL_SECONDS) * 1_000
        return OnboardingStep.ShowQr(qr)
    }

    override suspend fun poll(): OnboardingStep {
        val code = deviceCode ?: return OnboardingStep.Failed("bridge.onboarding.noQr")
        val result =
            try {
                register(listOf("action" to "poll", "device_code" to code, "tp" to "ob_app"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 轮询期间偶发的网络异常不该结束扫码；下一轮照常再查，总超时由调用方负责
                null
            } ?: return OnboardingStep.Waiting
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
