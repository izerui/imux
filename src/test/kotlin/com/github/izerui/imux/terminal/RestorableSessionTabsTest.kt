package com.github.izerui.imux.terminal

import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorableSessionTabsTest {
    @Test
    fun `末尾打开标签时恢复顺序保持原样`() {
        assertEquals(listOf("a", "b", "c"), restorationOpenOrder(listOf("a", "b", "c"), true, true))
        assertEquals(listOf("a", "b", "c"), restorationOpenOrder(listOf("a", "b", "c"), true, false))
    }

    @Test
    fun `选中标签后插入时逆序恢复`() {
        assertEquals(listOf("c", "b", "a"), restorationOpenOrder(listOf("a", "b", "c"), false, true))
    }

    @Test
    fun `空窗口先打开最左侧标签再逆序恢复剩余标签`() {
        assertEquals(listOf("a", "c", "b"), restorationOpenOrder(listOf("a", "b", "c"), false, false))
        assertEquals(listOf("a"), restorationOpenOrder(listOf("a"), false, false))
        assertEquals(emptyList<String>(), restorationOpenOrder(emptyList<String>(), false, false))
    }

    @Test
    fun `workspace state survives platform xml serialization`() {
        val expected =
            RestorableSessionTabs.State(
                listOf(RestorableSessionTabs.Tab("pi", "session-1", "Restored")),
            )

        val restored =
            XmlSerializer.deserialize(
                XmlSerializer.serialize(expected),
                RestorableSessionTabs.State::class.java,
            )

        assertEquals(expected, restored)
    }

    @Test
    fun `state starts without restorable tabs`() {
        assertEquals(emptyList<RestorableSessionTabs.Tab>(), RestorableSessionTabs().tabs())
    }

    @Test
    fun `replace keeps ordered valid tabs and removes duplicate sessions`() {
        val state = RestorableSessionTabs()
        val first = RestorableSessionTabs.Tab("claude", "session-1", "First")
        val duplicate = RestorableSessionTabs.Tab("claude", "session-1", "Duplicate")
        val second = RestorableSessionTabs.Tab("codex", "session-2", "Second")

        state.replace(
            listOf(
                RestorableSessionTabs.Tab("", "missing-agent", "Invalid"),
                first,
                duplicate,
                RestorableSessionTabs.Tab("pi", "", "Invalid"),
                second,
            ),
        )

        assertEquals(listOf(first, second), state.tabs())
    }

    @Test
    fun `restoration snapshot is immutable and suppresses persistence until finished`() {
        val state = SessionTabRestorationState()
        val original = RestorableSessionTabs.Tab("codex", "session-1", "Original")

        val snapshot = state.capture(listOf(original))

        assertTrue(state.active)
        assertFalse(state.canPersist(projectClosing = false, projectDisposed = false))
        original.title = "Overwritten while scanning"
        assertEquals("Original", snapshot.single().title)

        state.finish()

        assertFalse(state.active)
        assertTrue(state.canPersist(projectClosing = false, projectDisposed = false))
        assertFalse(state.canPersist(projectClosing = true, projectDisposed = false))
        assertFalse(state.canPersist(projectClosing = false, projectDisposed = true))
    }

}
