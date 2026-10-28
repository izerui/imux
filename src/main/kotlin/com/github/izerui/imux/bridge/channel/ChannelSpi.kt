package com.github.izerui.imux.bridge.channel

import com.github.izerui.imux.bridge.core.ChannelAdapter
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CoroutineScope

sealed interface ConnectionStatus {
    data object Connecting : ConnectionStatus

    data object Online : ConnectionStatus

    /** 同一机器人在别处建立了连接。不得自动重连，否则两边无限互踢。 */
    data object Kicked : ConnectionStatus

    data class Failed(val reason: String) : ConnectionStatus

    /** 连续多次连不上，仍在自动重试；带上最近一次的原因，界面如实显示而不是一直“连接中”。 */
    data class Retrying(val reason: String) : ConnectionStatus

    data object Stopped : ConnectionStatus

    /** 本地取不到凭据（钥匙串被清空等），连不上；只能移除后重新添加。 */
    data object NoCredentials : ConnectionStatus
}

/** 连接在工作或正在建立：消息与配对码收得到，不需要重连。 */
val ConnectionStatus?.isWorking: Boolean
    get() = this == ConnectionStatus.Online || this == ConnectionStatus.Connecting

sealed interface OnboardingStep {
    data class ShowQr(val content: String) : OnboardingStep

    data object Waiting : OnboardingStep

    /** [ownerId] 为 null 表示扫码拿不到本人身份，需要配对码认主。 */
    data class Succeeded(
        val credentials: Map<String, String>,
        val ownerId: String?,
        val suggestedName: String?,
    ) : OnboardingStep {
        /** 凭据只显示键名，避免进入日志或异常消息。 */
        override fun toString(): String =
            "Succeeded(credentials=${credentials.keys.joinToString(prefix = "{", postfix = "}") { "$it=***" }}, ownerId=$ownerId, suggestedName=$suggestedName)"
    }

    /** [messageKey] 是 ImuxBundle 的键。 */
    data class Failed(val messageKey: String) : OnboardingStep
}

/** 一次扫码接入。调用方按 [pollIntervalMillis] 反复 [poll]，并负责总超时。 */
interface ChannelOnboarding {
    val pollIntervalMillis: Long

    suspend fun start(): OnboardingStep

    suspend fun poll(): OnboardingStep
}

fun interface ConnectionListener {
    fun statusChanged(status: ConnectionStatus)
}

/**
 * 一条渠道连接。单聊场景下必须把 InboundMessage.chatId 规范为发送时使用的对端 id，
 * 因此本人的 senderId 与 chatId 相同。[start] 不阻塞；断线重连由实现自行负责。
 */
interface ChannelConnection : ChannelAdapter {
    fun start(
        sink: InboundSink,
        listener: ConnectionListener,
    )

    fun stop()
}

interface ChannelFactory {
    val kind: ChannelKind

    fun onboarding(): ChannelOnboarding

    fun connect(
        robotId: RobotId,
        credentials: Map<String, String>,
        scope: CoroutineScope,
    ): ChannelConnection
}
