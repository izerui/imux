package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectOpenedTabTest {

    private val selected = mutableListOf<String>()
    private var opened = 0

    private fun activate(windows: List<String>, openIn: Set<String>) =
        selectOpenedTab(windows, { it in openIn }, { selected += it }, { opened++ })

    /** 主窗口一个组、独立窗口一个组：标签被拖到独立窗口后，从主窗口激活要选中独立窗口里的那个组。 */
    @Test
    fun `标签在独立窗口的组里开着时在那个组选中，不重新打开`() {
        activate(listOf("主窗口", "独立窗口"), openIn = setOf("独立窗口"))

        assertEquals(listOf("独立窗口"), selected)
        assertEquals(0, opened)
    }

    @Test
    fun `标签在主窗口的组里开着时在那个组选中，不重新打开`() {
        activate(listOf("主窗口", "独立窗口"), openIn = setOf("主窗口"))

        assertEquals(listOf("主窗口"), selected)
        assertEquals(0, opened)
    }

    @Test
    fun `哪个组都没开着时才打开`() {
        activate(listOf("主窗口", "独立窗口"), openIn = emptySet())

        assertEquals(emptyList<String>(), selected)
        assertEquals(1, opened)
    }
}
