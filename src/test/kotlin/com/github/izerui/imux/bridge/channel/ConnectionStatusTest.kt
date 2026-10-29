package com.github.izerui.imux.bridge.channel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStatusTest {
    @Test
    fun `发送受阻时仍算在工作：收得到消息与配对码，不需要重连`() {
        assertTrue(ConnectionStatus.SendFailing("k").isWorking)
        assertTrue(ConnectionStatus.Online.isWorking)
        assertFalse(ConnectionStatus.Failed("x").isWorking)
    }
}
