package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.channel.OnboardingStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class OnboardingDriverTest {
    private class Scripted(
        private val start: OnboardingStep,
        polls: List<OnboardingStep>,
    ) : ChannelOnboarding {
        private val queue = ArrayDeque(polls)
        override val pollIntervalMillis = 10L

        override suspend fun start() = start

        override suspend fun poll(): OnboardingStep = queue.removeFirstOrNull() ?: throw IOException("boom")

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
    fun `渠道失败与网络异常`() =
        runBlocking {
            OnboardingDriver(Scripted(OnboardingStep.Failed("bridge.onboarding.noQr"), emptyList()), states::add, sleep = {}).run()
            assertEquals(DriverState.Error("bridge.onboarding.noQr", null), states.last())
            states.clear()
            OnboardingDriver(Scripted(OnboardingStep.ShowQr("QR"), emptyList()), states::add, sleep = {}).run()
            assertEquals(DriverState.Error("bridge.onboarding.network", "boom"), states.last())
        }
}
