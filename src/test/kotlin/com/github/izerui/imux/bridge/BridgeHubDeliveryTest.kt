package com.github.izerui.imux.bridge

import com.github.izerui.imux.SourceCode
import com.github.izerui.imux.bridge.core.DeliveryResult
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeHubDeliveryTest {
    private val message = OutboundMessage(RobotId("r1"), "chat-1", "secret body text")

    @Test
    fun `送达成功不记日志`() {
        assertNull(deliveryLog(message, DeliveryResult.Accepted))
    }

    @Test
    fun `被拒记警告，不含正文`() {
        val log = deliveryLog(message, DeliveryResult.Rejected("rate limited"))!!
        assertTrue(log.warn)
        assertTrue(log.text.contains("r1"))
        assertTrue(log.text.contains("rate limited"))
        assertFalse(log.text.contains("secret body text"))
    }

    @Test
    fun `结果不明记信息，不含正文`() {
        val log = deliveryLog(message, DeliveryResult.Unknown)!!
        assertEquals(false, log.warn)
        assertTrue(log.text.contains("r1"))
        assertFalse(log.text.contains("secret body text"))
    }

    @Test
    fun `中枢把送达结果接到日志`() {
        val hub = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BridgeHub.kt")
        assertTrue(hub.compact(hub.normalized).contains("onDelivery=::logDelivery"))
    }
}
