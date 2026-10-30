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
