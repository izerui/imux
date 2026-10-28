package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.channel.HttpJsonGet
import com.github.izerui.imux.bridge.channel.JdkHttpJsonGet
import com.github.izerui.imux.bridge.channel.OnboardingStep
import com.github.izerui.imux.bridge.json.obj
import com.github.izerui.imux.bridge.json.primitiveText
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** 与官方 wecom-cli 一致：1 macOS、2 Windows、3 Linux、0 其他。 */
fun wecomPlat(osName: String = System.getProperty("os.name")): Int {
    val name = osName.lowercase()
    return when {
        name.contains("mac") -> 1
        name.contains("win") -> 2
        name.contains("linux") -> 3
        else -> 0
    }
}

/**
 * 企业微信扫码创建智能机器人（docs/channel-protocols.md 第 1 节）。
 * ai/qc 是管理后台接口而非公开 API，可能变化；失败时明确报错，不提供手填密钥的回退。
 * 不返回扫码人身份，本人由配对码确定。
 */
class WecomOnboarding(
    private val http: HttpJsonGet = JdkHttpJsonGet(),
    private val plat: Int = wecomPlat(),
) : ChannelOnboarding {
    override val pollIntervalMillis: Long = 3_000

    private var scode: String? = null

    override suspend fun start(): OnboardingStep {
        val data = http.get("$BASE/generate?source=imux&plat=$plat").obj("data")
        val code = data?.primitiveText("scode")
        val authUrl = data?.primitiveText("auth_url")
        if (code.isNullOrEmpty() || authUrl.isNullOrEmpty()) return OnboardingStep.Failed("bridge.onboarding.noQr")
        scode = code
        return OnboardingStep.ShowQr(authUrl)
    }

    override suspend fun poll(): OnboardingStep {
        val code = scode ?: return OnboardingStep.Failed("bridge.onboarding.noQr")
        val data = http.get("$BASE/query_result?scode=${URLEncoder.encode(code, StandardCharsets.UTF_8)}").obj("data")
        if (data?.primitiveText("status") != "success") return OnboardingStep.Waiting
        val info = data.obj("bot_info")
        val botId = info?.primitiveText("botid") ?: info?.primitiveText("bot_id")
        val secret = info?.primitiveText("secret")
        if (botId.isNullOrEmpty() || secret.isNullOrEmpty()) return OnboardingStep.Failed("bridge.onboarding.noCredentials")
        return OnboardingStep.Succeeded(mapOf("bot_id" to botId, "secret" to secret), ownerId = null, suggestedName = null)
    }


    private companion object {
        const val BASE = "https://work.weixin.qq.com/ai/qc"
    }
}
