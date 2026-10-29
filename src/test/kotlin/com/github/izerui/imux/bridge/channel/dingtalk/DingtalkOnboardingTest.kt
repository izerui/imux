package com.github.izerui.imux.bridge.channel.dingtalk

import com.github.izerui.imux.bridge.channel.OnboardingStep
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class DingtalkOnboardingTest {
    @Volatile
    private var initBody = """{"errcode":0,"errmsg":"ok","nonce":"n1","expires_in":300}"""

    @Volatile
    private var beginBody = """{"errcode":0,"errmsg":"ok","device_code":"dc","verification_uri_complete":"https://qr","interval":3,"expires_in":7200}"""

    private val polls = ArrayDeque<String>()

    private val http =
        DingtalkFakeHttp { url, _ ->
            when {
                url == "https://oapi.dingtalk.com/app/registration/init" -> 200 to initBody
                url == "https://oapi.dingtalk.com/app/registration/begin" -> 200 to beginBody
                url == "https://oapi.dingtalk.com/app/registration/poll" -> {
                    val next = synchronized(polls) { polls.removeFirstOrNull() } ?: """{"errcode":0,"status":"WAITING"}"""
                    if (next == "throw") throw IOException("reset")
                    200 to next
                }

                else -> 404 to "{}"
            }
        }

    private fun poll(vararg bodies: String) = synchronized(polls) { polls.addAll(bodies) }

    @Test
    fun `扫码成功：source 固定，不带本人身份`() =
        runBlocking {
            val onboarding = DingtalkOnboarding(http)
            assertEquals(OnboardingStep.ShowQr("https://qr"), onboarding.start())
            assertEquals(3_000L, onboarding.pollIntervalMillis)
            val init = JsonParser.parseString(http.calls("/registration/init").single().body).asJsonObject
            assertEquals("DING_DWS_CLAW", init["source"].asString)
            assertEquals("n1", JsonParser.parseString(http.calls("/registration/begin").single().body).asJsonObject["nonce"].asString)
            poll("""{"errcode":0,"status":"WAITING"}""", """{"errcode":0,"status":"SUCCESS","client_id":"cid","client_secret":"sec"}""")
            assertEquals(OnboardingStep.Waiting, onboarding.poll())
            assertEquals(
                OnboardingStep.Succeeded(mapOf("client_id" to "cid", "client_secret" to "sec"), ownerId = null, suggestedName = null),
                onboarding.poll(),
            )
            assertEquals("dc", JsonParser.parseString(http.calls("/registration/poll").first().body).asJsonObject["device_code"].asString)
        }

    @Test
    fun `过期、失败、缺凭据`() =
        runBlocking {
            val onboarding = DingtalkOnboarding(http)
            onboarding.start()
            poll(
                """{"errcode":0,"status":"EXPIRED"}""",
                """{"errcode":0,"status":"FAIL","fail_reason":"denied"}""",
                """{"errcode":0,"status":"SUCCESS","client_id":"cid"}""",
            )
            assertEquals(OnboardingStep.Failed("bridge.onboarding.expired"), onboarding.poll())
            assertEquals(OnboardingStep.Failed("bridge.dingtalk.onboardingFailed"), onboarding.poll())
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noCredentials"), onboarding.poll())
        }

    @Test
    fun `轮询遇到网络错误或业务错误继续等`() =
        runBlocking {
            val onboarding = DingtalkOnboarding(http)
            onboarding.start()
            poll("throw", """{"errcode":-1,"errmsg":"busy"}""")
            assertEquals(OnboardingStep.Waiting, onboarding.poll())
            assertEquals(OnboardingStep.Waiting, onboarding.poll())
        }

    @Test
    fun `取不到二维码时失败；间隔缺省两秒`() =
        runBlocking {
            initBody = """{"errcode":-1,"errmsg":"bad"}"""
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noQr"), DingtalkOnboarding(http).start())
            initBody = """{"errcode":0,"nonce":"n1"}"""
            beginBody = """{"errcode":0,"device_code":"dc"}"""
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noQr"), DingtalkOnboarding(http).start())
            beginBody = """{"errcode":0,"device_code":"dc","verification_uri_complete":"https://qr"}"""
            val onboarding = DingtalkOnboarding(http)
            onboarding.start()
            assertEquals(2_000L, onboarding.pollIntervalMillis)
        }

    @Test
    fun `服务端轮询间隔为 0 或负数时至少 1 秒`() =
        runBlocking {
            for (interval in listOf("0", "-5")) {
                beginBody = """{"errcode":0,"device_code":"dc","verification_uri_complete":"https://qr","interval":$interval}"""
                val onboarding = DingtalkOnboarding(http)
                onboarding.start()
                assertEquals(1_000L, onboarding.pollIntervalMillis)
            }
        }
}
