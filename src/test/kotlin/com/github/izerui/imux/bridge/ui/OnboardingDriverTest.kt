package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.channel.OnboardingStep
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class OnboardingDriverTest {
    private class Scripted(
        private val start: OnboardingStep,
        polls: List<OnboardingStep>,
        private val afterCode: OnboardingStep = OnboardingStep.Waiting,
    ) : ChannelOnboarding {
        private val queue = ArrayDeque(polls)
        var code: String? = null
        override val pollIntervalMillis = 10L

        override suspend fun start() = start

        override suspend fun poll(): OnboardingStep = queue.removeFirstOrNull() ?: throw IOException("boom")

        override suspend fun submitVerifyCode(code: String): OnboardingStep {
            this.code = code
            return afterCode
        }
    }

    private val states = mutableListOf<DriverState>()

    @Test
    fun `显示二维码，等待后成功`() =
        runBlocking {
            val success = OnboardingStep.Succeeded(mapOf("k" to "v"), null, null)
            OnboardingDriver(Scripted(OnboardingStep.ShowQr("QR"), listOf(OnboardingStep.Waiting, success)), states::add, sleep = {}).run()
            assertEquals(listOf(DriverState.Starting, DriverState.Qr("QR"), DriverState.Done(success)), states)
        }

    @Test
    fun `超时`() =
        runBlocking {
            var clock = 0L
            val driver = OnboardingDriver(Scripted(OnboardingStep.ShowQr("QR"), List(10) { OnboardingStep.Waiting }), states::add, sleep = { clock += it }, now = { clock }, timeoutMillis = 25)
            driver.run()
            assertEquals(DriverState.TimedOut, states.last())
        }

    @Test
    fun `需要验证码时等待输入并提交`() =
        runBlocking {
            val success = OnboardingStep.Succeeded(emptyMap(), "owner", null)
            val onboarding = Scripted(OnboardingStep.ShowQr("QR"), listOf(OnboardingStep.NeedVerifyCode), afterCode = success)
            val driver = OnboardingDriver(onboarding, states::add, sleep = {})
            val job = launch { driver.run() }
            while (DriverState.NeedVerifyCode !in states) yield()
            driver.submitVerifyCode("1234")
            job.join()
            assertEquals("1234", onboarding.code)
            assertEquals(DriverState.Done(success), states.last())
        }

    @Test
    fun `渠道失败与网络异常`() =
        runBlocking {
            OnboardingDriver(Scripted(OnboardingStep.Failed("bridge.onboarding.noQr"), emptyList()), states::add, sleep = {}).run()
            assertEquals(DriverState.Error("bridge.onboarding.noQr", null), states.last())
            states.clear()
            OnboardingDriver(Scripted(OnboardingStep.ShowQr("QR"), emptyList()), states::add, sleep = {}).run()
            assertEquals(DriverState.Error("bridge.onboarding.network", "boom"), states.last())
        }
}
