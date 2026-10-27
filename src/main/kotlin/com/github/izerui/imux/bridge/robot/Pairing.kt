package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.RobotOwner
import java.security.SecureRandom
import java.util.Random

sealed interface PairDecision {
    /** 已认主，交给路由器（路由器再按本人过滤）。 */
    data object Pass : PairDecision

    data class Paired(val owner: RobotOwner) : PairDecision

    /** 发来的是正确配对码，但已过期：提示到 IDE 重新生成。 */
    data object Expired : PairDecision

    /** 不处理也不回复。陌生人的消息不能消耗机器人的发送额度。 */
    data object Ignore : PairDecision
}

/**
 * 扫码拿不到本人身份的渠道用配对码认主：IDE 显示一次性数字，本人发给机器人，
 * 第一个发来正确数字的单聊用户成为本人。这条消息同时满足企业微信
 * "用户先发过消息才能主动推送"的前提。
 */
object Pairing {
    const val CODE_LENGTH = 6
    const val TTL_MILLIS = 10 * 60_000L

    fun newCode(random: Random = SecureRandom()): String = (0 until CODE_LENGTH).joinToString("") { random.nextInt(10).toString() }

    fun decide(
        record: RobotRecord,
        message: InboundMessage,
        nowMillis: Long,
    ): PairDecision {
        if (record.owner() != null) return PairDecision.Pass
        val code = record.pairCode ?: return PairDecision.Ignore
        if (message.text.filterNot(Char::isWhitespace) != code) return PairDecision.Ignore
        if (nowMillis > record.pairCodeExpiresAtMillis) return PairDecision.Expired
        return PairDecision.Paired(RobotOwner(message.senderId, message.chatId))
    }
}
