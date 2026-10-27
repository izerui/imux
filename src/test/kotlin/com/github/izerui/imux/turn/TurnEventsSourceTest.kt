package com.github.izerui.imux.turn

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnEventsSourceTest {
    private val monitor =
        SourceCode("src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt").normalized

    @Test
    fun `完成广播紧跟在原有完成通知之后`() {
        val notify = monitor.indexOf("TurnNotifier.notifyCompleted(")
        val publish = monitor.indexOf("publishTurnEvent(project) { it.turnCompleted(sessionId) }")
        assertTrue(notify >= 0 && publish > notify)
    }

    @Test
    fun `等待广播在气泡过滤之前`() {
        val publish = monitor.indexOf("publishTurnEvent(project) { it.turnWaiting(waiting.sessionId, waiting.reason) }")
        val filter = monitor.indexOf("if (!waitingNotificationWanted(waiting.sessionId, selected)) return@forEach")
        assertTrue(publish >= 0 && filter > publish)
    }

    @Test
    fun `SessionMonitor 只新增两处广播`() {
        assertEquals(2, Regex("publishTurnEvent\\(").findAll(monitor).count())
    }
}
