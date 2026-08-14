package com.github.izerui.imux.turn

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Test

class WaitingSubtitleTest {
    @Test
    fun `formats permission prompt`() {
        assertEquals("Claude · Waiting for permission", waitingSubtitle(AgentType.CLAUDE, "permission prompt"))
    }

    @Test
    fun `formats selection dialog`() {
        assertEquals("Claude · Waiting for your selection", waitingSubtitle(AgentType.CLAUDE, "dialog open"))
    }

    @Test
    fun `formats input request`() {
        assertEquals("Claude · Waiting for input", waitingSubtitle(AgentType.CLAUDE, "input needed"))
    }

    @Test
    fun `uses fallback for uncommon or unknown reasons`() {
        assertEquals("Claude · Waiting for your confirmation", waitingSubtitle(AgentType.CLAUDE, "worker request"))
        assertEquals("Claude · Waiting for your confirmation", waitingSubtitle(AgentType.CLAUDE, "sandbox request"))
        assertEquals("Claude · Waiting for your confirmation", waitingSubtitle(AgentType.CLAUDE, "future reason"))
        assertEquals("Claude · Waiting for your confirmation", waitingSubtitle(AgentType.CLAUDE, null))
    }

    @Test
    fun `omits unknown agent without an extra separator`() {
        assertEquals("Waiting for permission", waitingSubtitle(null, "permission prompt"))
    }
}
