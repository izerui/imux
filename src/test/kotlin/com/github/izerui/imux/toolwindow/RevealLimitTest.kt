package com.github.izerui.imux.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Test

class RevealLimitTest {

    @Test
    fun `目标已在当前分页内则不动分页`() {
        assertEquals(50, limitCovering(index = 0, current = 50, pageSize = 50))
        assertEquals(50, limitCovering(index = 49, current = 50, pageSize = 50))
    }

    @Test
    fun `目标刚好越界时提升一页`() {
        assertEquals(100, limitCovering(index = 50, current = 50, pageSize = 50))
    }

    @Test
    fun `目标远在后方时一次提升到覆盖它`() {
        assertEquals(150, limitCovering(index = 120, current = 50, pageSize = 50))
        assertEquals(200, limitCovering(index = 199, current = 50, pageSize = 50))
    }

    @Test
    fun `已手动加载更多时不回退分页`() {
        assertEquals(200, limitCovering(index = 10, current = 200, pageSize = 50))
    }
}
