package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.RobotOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PairingTest {
    private val pending = RobotRecord("r", "bot", ChannelKind.WECOM, pairCode = "482713", pairCodeExpiresAtMillis = 1_000)

    private fun msg(text: String) = InboundMessage(RobotId("r"), "m1", "user-1", "user-1", text)

    @Test
    fun `已认主的机器人直接放行`() {
        val owned = pending.copy(ownerSenderId = "u", ownerChatId = "u", pairCode = null)
        assertEquals(PairDecision.Pass, Pairing.decide(owned, msg("hello"), nowMillis = 0))
    }

    @Test
    fun `发来正确配对码即认主，容忍空白`() {
        assertEquals(PairDecision.Paired(RobotOwner("user-1", "user-1")), Pairing.decide(pending, msg(" 482 713\n"), nowMillis = 500))
    }

    @Test
    fun `格式像配对码但不对的计为猜错，普通消息被忽略`() {
        assertEquals(PairDecision.Wrong, Pairing.decide(pending, msg("111111"), nowMillis = 500))
        assertEquals(PairDecision.Wrong, Pairing.decide(pending, msg(" 111 111 "), nowMillis = 500))
        assertEquals(PairDecision.Ignore, Pairing.decide(pending, msg("在吗"), nowMillis = 500))
        assertEquals("位数不对不算猜错", PairDecision.Ignore, Pairing.decide(pending, msg("12345"), nowMillis = 500))
        assertEquals("含非数字不算猜错", PairDecision.Ignore, Pairing.decide(pending, msg("12345a"), nowMillis = 500))
    }

    @Test
    fun `作废后的配对码不再认主`() {
        val book = RobotBook().add(pending).withPairCode("r", null, 0)
        assertEquals(PairDecision.Ignore, Pairing.decide(book.robot("r")!!, msg("482713"), nowMillis = 0))
    }

    @Test
    fun `配对码过期时只对正确配对码提示过期`() {
        assertEquals(PairDecision.Expired, Pairing.decide(pending, msg("482713"), nowMillis = 1_001))
        assertEquals(PairDecision.Ignore, Pairing.decide(pending, msg("在吗"), nowMillis = 1_001))
    }

    @Test
    fun `没有配对码的未认主机器人忽略一切`() {
        assertEquals(PairDecision.Ignore, Pairing.decide(pending.copy(pairCode = null), msg("482713"), nowMillis = 0))
    }

    @Test
    fun `配对码是 6 位数字`() {
        val code = Pairing.newCode(Random(42))
        assertEquals(6, code.length)
        assertTrue(code.all(Char::isDigit))
    }
}
