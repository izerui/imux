package com.github.izerui.imux.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScrollStateTest {

    @Test
    fun `内容未超过视口时视为已在底部`() {
        assertTrue(isViewportAtBottom(visibleTop = 0, visibleHeight = 100, contentHeight = 80))
    }

    @Test
    fun `视口末端到达内容末端时视为已在底部`() {
        assertTrue(isViewportAtBottom(visibleTop = 400, visibleHeight = 100, contentHeight = 500))
    }

    @Test
    fun `允许一个像素的布局取整误差`() {
        assertTrue(isViewportAtBottom(visibleTop = 399, visibleHeight = 100, contentHeight = 500))
    }

    @Test
    fun `离内容末端超过一个像素时不在底部`() {
        assertFalse(isViewportAtBottom(visibleTop = 398, visibleHeight = 100, contentHeight = 500))
    }
}
