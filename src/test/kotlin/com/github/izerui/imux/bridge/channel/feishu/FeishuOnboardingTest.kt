package com.github.izerui.imux.bridge.channel.feishu

import com.github.izerui.imux.bridge.channel.OnboardingStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class FeishuOnboardingTest {
    private fun onboarding(vararg responses: Pair<Int, String>): Pair<FeishuOnboarding, FakeHttpJson> {
        val queue = ArrayDeque(responses.toList())
        val http = FakeHttpJson { _, _, _ -> queue.removeFirst() }
        return FeishuOnboarding(http) to http
    }

    private val init = 200 to """{"supported_auth_methods":["client_secret"]}"""
    private val begin =
        200 to """{"device_code":"D","verification_uri_complete":"https://open.feishu.cn/page?code=X","interval":5,"expire_in":600}"""

    @Test
    fun `申请二维码：先 init 再 begin，按 PersonalAgent 请求本人 open_id`() =
        runBlocking {
            val (flow, http) = onboarding(init, begin)
            assertEquals(OnboardingStep.ShowQr("https://open.feishu.cn/page?code=X"), flow.start())
            assertEquals("https://accounts.feishu.cn/oauth/v1/app/registration", http.calls[0].url)
            assertEquals("action=init", http.calls[0].body)
            assertEquals(
                "action=begin&archetype=PersonalAgent&auth_method=client_secret&request_user_info=open_id",
                http.calls[1].body
            )
            assertEquals(5_000L, flow.pollIntervalMillis)
        }

    @Test
    fun `等待、放慢、成功：凭据带域名，本人取 open_id`() =
        runBlocking {
            val (flow, http) =
                onboarding(
                    init,
                    begin,
                    400 to """{"error":"authorization_pending"}""",
                    400 to """{"error":"slow_down"}""",
                    200 to """{"client_id":"cli_1","client_secret":"S","user_info":{"open_id":"ou_me","tenant_brand":"feishu"}}""",
                )
            flow.start()
            assertEquals(OnboardingStep.Waiting, flow.poll())
            assertEquals(OnboardingStep.Waiting, flow.poll())
            assertEquals(10_000L, flow.pollIntervalMillis)
            assertEquals(
                OnboardingStep.Succeeded(
                    mapOf("app_id" to "cli_1", "app_secret" to "S", "domain" to "feishu"),
                    ownerId = "ou_me",
                    suggestedName = null
                ),
                flow.poll(),
            )
            assertEquals("action=poll&device_code=D&tp=ob_app", http.calls.last().body)
        }

    @Test
    fun `Lark 租户切换域名，同一响应带凭据时照常成功`() =
        runBlocking {
            val (flow, http) =
                onboarding(
                    init,
                    begin,
                    200 to """{"error":"authorization_pending","user_info":{"tenant_brand":"lark"}}""",
                    200 to """{"client_id":"cli_1","client_secret":"S","user_info":{"open_id":"ou_me","tenant_brand":"lark"}}""",
                )
            flow.start()
            assertEquals(OnboardingStep.Waiting, flow.poll())
            val done = flow.poll() as OnboardingStep.Succeeded
            assertEquals("lark", done.credentials["domain"])
            assertEquals("https://accounts.larksuite.com/oauth/v1/app/registration", http.calls.last().url)
        }

    @Test
    fun `拿不到 open_id 时走配对码`() =
        runBlocking {
            val (flow, _) = onboarding(init, begin, 200 to """{"client_id":"cli_1","client_secret":"S"}""")
            flow.start()
            assertEquals(null, (flow.poll() as OnboardingStep.Succeeded).ownerId)
        }

    @Test
    fun `轮询遇到网络异常时继续等待，下一次照常成功`() =
        runBlocking {
            val responses =
                ArrayDeque(listOf(init, begin, 200 to """{"client_id":"cli_1","client_secret":"S","user_info":{"open_id":"ou_me"}}"""))
            var failNext = false
            val http =
                FakeHttpJson { _, _, _ ->
                    if (failNext) {
                        failNext = false
                        throw IOException("timeout")
                    }
                    responses.removeFirst()
                }
            val flow = FeishuOnboarding(http)
            flow.start()
            failNext = true
            assertEquals(OnboardingStep.Waiting, flow.poll())
            assertEquals("ou_me", (flow.poll() as OnboardingStep.Succeeded).ownerId)
        }

    @Test
    fun `拒绝、过期、不支持、没有二维码分别失败`() =
        runBlocking {
            val (denied, _) = onboarding(init, begin, 400 to """{"error":"access_denied"}""")
            denied.start()
            assertEquals(OnboardingStep.Failed("bridge.onboarding.denied"), denied.poll())

            val (expired, _) = onboarding(init, begin, 400 to """{"error":"expired_token"}""")
            expired.start()
            assertEquals(OnboardingStep.Failed("bridge.onboarding.expired"), expired.poll())

            assertEquals(
                OnboardingStep.Failed("bridge.onboarding.unsupported"),
                onboarding(200 to """{"supported_auth_methods":["x"]}""").first.start()
            )
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noQr"), onboarding(init, 200 to "{}").first.start())
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noQr"), onboarding().first.poll())
        }
}
