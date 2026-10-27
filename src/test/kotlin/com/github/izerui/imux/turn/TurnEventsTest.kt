package com.github.izerui.imux.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class TurnEventsTest {
    private val failing =
        object : TurnEventsListener {
            override fun turnCompleted(sessionId: String) {
                throw IllegalStateException("boom")
            }

            override fun turnWaiting(
                sessionId: String,
                reason: String?,
            ) {
            }
        }

    @Test
    fun `监听方异常被吞下并上报，不影响调用方`() {
        val failures = mutableListOf<Throwable>()
        deliverTurnEvent({ failing }, { it.turnCompleted("s") }, failures::add)
        assertEquals(1, failures.size)
    }

    @Test
    fun `取消信号照常向上传递`() {
        val cancelling =
            object : TurnEventsListener {
                override fun turnCompleted(sessionId: String) {
                    throw CancellationException("cancel")
                }

                override fun turnWaiting(
                    sessionId: String,
                    reason: String?,
                ) {
                }
            }
        val thrown = runCatching { deliverTurnEvent({ cancelling }, { it.turnCompleted("s") }, {}) }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }
}
