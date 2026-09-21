package com.github.izerui.imux.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectChangesTest {
    @Test
    fun `干净工作区不触发副驾驶`() {
        val changed = projectHasChanges("/work") { _, _, _ -> false }

        assertFalse(changed!!)
    }

    @Test
    fun `存在变更时允许触发副驾驶`() {
        val changed = projectHasChanges("/work") { _, _, _ -> true }

        assertTrue(changed!!)
    }

    @Test
    fun `无法判断时保留原有触发行为`() {
        val changed = projectHasChanges("/work") { _, _, _ -> null }

        assertNull(changed)
    }

    @Test
    fun `Git 检查只覆盖当前项目目录`() {
        var command = emptyList<String>()

        projectHasChanges("/work/subproject") { received, _, _ ->
            command = received
            false
        }

        assertEquals("git", command.first())
        assertEquals(listOf("--", "."), command.takeLast(2))
        assertTrue(command.contains("/work/subproject"))
    }
}
