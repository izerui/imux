package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * 分页上限按 agent 分别记账，取的时候用的是 getValue——少一个 key 就是运行时异常，
     * 而不是少显示几条。手写 map 每加一个 agent 都得记得补一行，这里让它自己长齐。
     */
    @Test
    fun `每种 agent 都有初始分页上限`() {
        val limits = initialLimits()

        assertEquals(AgentType.entries.toSet(), limits.keys)
        assertTrue("初始上限应为正数", limits.values.all { it > 0 })
    }
}
