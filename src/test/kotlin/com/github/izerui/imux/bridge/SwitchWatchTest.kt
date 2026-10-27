package com.github.izerui.imux.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchWatchTest {
    @Test
    fun `开启代次不变时沿用读取位置`() {
        val watch = SwitchWatch().apply { reset(3) }
        assertFalse(watch.observe(3))
        assertFalse(watch.observe(3))
    }

    @Test
    fun `两次轮询之间关闭又开启，代次变了就要求丢弃，只丢弃一次`() {
        val watch = SwitchWatch().apply { reset(3) }
        assertTrue(watch.observe(4))
        assertFalse(watch.observe(4))
    }
}
