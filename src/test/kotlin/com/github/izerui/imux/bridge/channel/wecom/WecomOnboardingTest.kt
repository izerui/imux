package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.channel.HttpJsonGet
import com.github.izerui.imux.bridge.channel.OnboardingStep
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WecomOnboardingTest {
    private val urls = mutableListOf<String>()

    private fun http(vararg responses: String): HttpJsonGet {
        val queue = ArrayDeque(responses.toList())
        return HttpJsonGet { url ->
            urls += url
            JsonParser.parseString(queue.removeFirst()).asJsonObject
        }
    }

    @Test
    fun `申请二维码时带 imux 来源与平台`() =
        runBlocking {
            val onboarding = WecomOnboarding(http("""{"data":{"scode":"S1","auth_url":"https://work.weixin.qq.com/ai/qc/c?s=S1"}}"""), plat = 1)
            assertEquals(OnboardingStep.ShowQr("https://work.weixin.qq.com/ai/qc/c?s=S1"), onboarding.start())
            assertEquals("https://work.weixin.qq.com/ai/qc/generate?source=imux&plat=1", urls.single())
        }

    @Test
    fun `扫码前等待，成功后返回凭据且没有本人`() =
        runBlocking {
            val onboarding =
                WecomOnboarding(
                    http(
                        """{"data":{"scode":"S1","auth_url":"A"}}""",
                        """{"data":{"status":"init"}}""",
                        """{"data":{"status":"success","bot_info":{"botid":"B","secret":"X"}}}""",
                    ),
                    plat = 2,
                )
            onboarding.start()
            assertEquals(OnboardingStep.Waiting, onboarding.poll())
            assertEquals(OnboardingStep.Succeeded(mapOf("bot_id" to "B", "secret" to "X"), ownerId = null, suggestedName = null), onboarding.poll())
            assertTrue(urls.last().endsWith("/ai/qc/query_result?scode=S1"))
        }

    @Test
    fun `兼容 bot_id 字段名`() =
        runBlocking {
            val onboarding = WecomOnboarding(http("""{"data":{"scode":"S","auth_url":"A"}}""", """{"data":{"status":"success","bot_info":{"bot_id":"B","secret":"X"}}}"""), plat = 3)
            onboarding.start()
            assertEquals(mapOf("bot_id" to "B", "secret" to "X"), (onboarding.poll() as OnboardingStep.Succeeded).credentials)
        }

    @Test
    fun `成功但缺凭据、没有二维码、未申请就轮询都返回失败`() =
        runBlocking {
            val missing = WecomOnboarding(http("""{"data":{"scode":"S","auth_url":"A"}}""", """{"data":{"status":"success","bot_info":{"botid":"B"}}}"""), plat = 1)
            missing.start()
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noCredentials"), missing.poll())
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noQr"), WecomOnboarding(http("""{"data":{}}"""), plat = 1).start())
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noQr"), WecomOnboarding(http(), plat = 1).poll())
        }

    @Test
    fun `轮询遇网络异常返回 Waiting 而非失败`() =
        runBlocking {
            val onboarding =
                WecomOnboarding(
                    http = object : HttpJsonGet {
                        private var callCount = 0

                        override suspend fun get(url: String): com.google.gson.JsonObject {
                            callCount++
                            return when (callCount) {
                                // start() 正常
                                1 -> JsonParser.parseString("""{"data":{"scode":"S1","auth_url":"A"}}""").asJsonObject
                                // 第一次 poll() 抛网络异常
                                2 -> throw IOException("network error")
                                // 第二次 poll() 正常返回成功
                                else -> JsonParser.parseString("""{"data":{"status":"success","bot_info":{"botid":"B","secret":"X"}}}""").asJsonObject
                            }
                        }
                    },
                    plat = 1,
                )
            onboarding.start()
            // 网络异常应返回 Waiting，不应冒泡
            assertEquals(OnboardingStep.Waiting, onboarding.poll())
            // 下次正常轮询应成功
            assertEquals(
                OnboardingStep.Succeeded(mapOf("bot_id" to "B", "secret" to "X"), ownerId = null, suggestedName = null),
                onboarding.poll(),
            )
        }

    @Test
    fun `平台编码`() {
        assertEquals(1, wecomPlat("Mac OS X"))
        assertEquals(2, wecomPlat("Windows 11"))
        assertEquals(3, wecomPlat("Linux"))
        assertEquals(0, wecomPlat("FreeBSD"))
    }
}
