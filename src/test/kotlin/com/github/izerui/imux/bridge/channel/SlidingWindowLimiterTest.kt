package com.github.izerui.imux.bridge.channel

import org.junit.Assert.assertEquals
import org.junit.Test

class SlidingWindowLimiterTest {
    @Test
    fun `窗口内超过上限需要等待，窗口滑过后放行`() {
        var now = 0L
        val limiter = SlidingWindowLimiter(limit = 2, windowMillis = 1_000) { now }
        assertEquals(0L, limiter.tryAcquire("u"))
        now = 100
        assertEquals(0L, limiter.tryAcquire("u"))
        now = 200
        assertEquals(800L, limiter.tryAcquire("u"))
        now = 1_000
        assertEquals(0L, limiter.tryAcquire("u"))
    }

    @Test
    fun `不同会话分别计数`() {
        val limiter = SlidingWindowLimiter(limit = 1, windowMillis = 1_000) { 0L }
        assertEquals(0L, limiter.tryAcquire("a"))
        assertEquals(0L, limiter.tryAcquire("b"))
    }
}
