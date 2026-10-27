package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.robot.ChannelKind
import com.github.izerui.imux.bridge.robot.RobotRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class PairWaitTest {
    private val robot = RobotRecord("r1", "bot", ChannelKind.WECOM)

    @Test
    fun `机器人被删除时结束等待`() = assertEquals(PairWait.GONE, pairWait(null))

    @Test
    fun `未认主继续等待`() = assertEquals(PairWait.WAITING, pairWait(robot))

    @Test
    fun `认主后完成`() = assertEquals(PairWait.PAIRED, pairWait(robot.copy(ownerSenderId = "u", ownerChatId = "u")))
}
