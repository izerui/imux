package com.github.izerui.imux.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnabledEdgeTest {
    @Test
    fun `从关到开时要求丢弃读取位置`() {
        val edge = EnabledEdge()
        assertFalse(edge.observe(true))
        assertFalse(edge.observe(false))
        assertFalse(edge.observe(false))
        assertTrue(edge.observe(true))
        assertFalse(edge.observe(true))
    }

    @Test
    fun `初始关闭时首次开启也要求丢弃`() {
        val edge = EnabledEdge(initiallyEnabled = false)
        assertTrue(edge.observe(true))
    }

    @Test
    fun `一直关闭不要求丢弃`() {
        val edge = EnabledEdge(initiallyEnabled = false)
        assertFalse(edge.observe(false))
        assertFalse(edge.observe(false))
    }
}
