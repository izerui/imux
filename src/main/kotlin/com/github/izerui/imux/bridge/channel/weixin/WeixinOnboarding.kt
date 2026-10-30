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
        // 网络错误、超时都按"还在等"处理，总超时由调用方兜底
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
