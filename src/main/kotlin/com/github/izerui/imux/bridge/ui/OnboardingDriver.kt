package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.channel.OnboardingStep
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

sealed interface DriverState {
    data object Starting : DriverState

    data class Qr(val content: String) : DriverState

    data object NeedVerifyCode : DriverState

    data class Done(val step: OnboardingStep.Succeeded) : DriverState {
        /** 委托给已屏蔽凭据的 [OnboardingStep.Succeeded.toString]。 */
        override fun toString(): String = "Done(step=$step)"
    }

    data class Error(
        val messageKey: String,
        val detail: String?,
    ) : DriverState

    data object TimedOut : DriverState
}

/**
 * 驱动一次扫码接入：申请二维码、按渠道间隔轮询、需要时等待验证码、总超时。
 * 与 Swing 无关，对话框只负责把状态画出来。
 */
class OnboardingDriver(
    private val onboarding: ChannelOnboarding,
    private val onState: (DriverState) -> Unit,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = 5 * 60_000L,
) {
    private val verifyCodes = Channel<String>(Channel.CONFLATED)

    fun submitVerifyCode(code: String) {
        verifyCodes.trySend(code)
    }

    suspend fun run() {
        onState(DriverState.Starting)
        val deadline = now() + timeoutMillis
        var step = guard { onboarding.start() } ?: return
        while (true) {
            when (val current = step) {
                is OnboardingStep.ShowQr -> onState(DriverState.Qr(current.content))
                OnboardingStep.Waiting -> Unit
                OnboardingStep.NeedVerifyCode -> {
                    onState(DriverState.NeedVerifyCode)
                    val code = verifyCodes.receive()
                    step = guard { onboarding.submitVerifyCode(code) } ?: return
                    continue
                }
                is OnboardingStep.Succeeded -> {
                    onState(DriverState.Done(current))
                    return
                }
                is OnboardingStep.Failed -> {
                    onState(DriverState.Error(current.messageKey, null))
                    return
                }
            }
            if (now() >= deadline) {
                onState(DriverState.TimedOut)
                return
            }
            sleep(onboarding.pollIntervalMillis)
            step = guard { onboarding.poll() } ?: return
        }
    }

    private suspend fun guard(block: suspend () -> OnboardingStep): OnboardingStep? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onState(DriverState.Error("bridge.onboarding.network", e.message ?: e.javaClass.simpleName))
            null
        }
}
