package com.github.izerui.imux.terminal

import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorableSessionTabsTest {
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
