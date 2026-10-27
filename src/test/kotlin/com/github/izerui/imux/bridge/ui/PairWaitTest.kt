package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.robot.ChannelKind
import com.github.izerui.imux.bridge.robot.RobotRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class PairWaitTest {
    private val now = 1_000_000L
    private val robot = RobotRecord("r1", "bot", ChannelKind.WECOM, pairCode = "123456", pairCodeExpiresAtMillis = now + 60_000)

    @Test
    fun `机器人被删除时结束等待`() = assertEquals(PairWait.GONE, pairWait(null, now, ConnectionStatus.Online))

    @Test
    fun `配对码有效、连接正常或还在连接时继续等待`() {
        assertEquals(PairWait.WAITING, pairWait(robot, now, ConnectionStatus.Online))
        assertEquals(PairWait.WAITING, pairWait(robot, now, ConnectionStatus.Connecting))
        assertEquals(PairWait.WAITING, pairWait(robot, now, null))
    }

    @Test
    fun `认主后完成`() = assertEquals(PairWait.PAIRED, pairWait(robot.copy(ownerSenderId = "u", ownerChatId = "u"), now, ConnectionStatus.Online))

    @Test
    fun `猜错次数过多配对码被作废时不再显示等待`() = assertEquals(PairWait.INVALIDATED, pairWait(robot.copy(pairCode = null), now, ConnectionStatus.Online))

    @Test
    fun `配对码过期时不再显示等待`() = assertEquals(PairWait.EXPIRED, pairWait(robot, now + 60_001, ConnectionStatus.Online))

    @Test
    fun `连接失败、被接管或已停止时收不到配对码`() {
        assertEquals(PairWait.DISCONNECTED, pairWait(robot, now, ConnectionStatus.Failed("40001 invalid secret")))
        assertEquals(PairWait.DISCONNECTED, pairWait(robot, now, ConnectionStatus.Kicked))
        assertEquals(PairWait.DISCONNECTED, pairWait(robot, now, ConnectionStatus.Stopped))
    }
}
