package com.github.izerui.imux.clarification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSessionBusyTest {
    @Test
    fun `执行中与等待确认都算忙而空闲不算`() {
        assertTrue(mainSessionBusy(running = setOf("s1"), waiting = emptySet(), sessionKey = "s1"))
        assertTrue(mainSessionBusy(running = emptySet(), waiting = setOf("s1"), sessionKey = "s1"))
        assertFalse(mainSessionBusy(running = setOf("s2"), waiting = setOf("s3"), sessionKey = "s1"))
    }
}
