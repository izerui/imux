package com.github.izerui.imux.settings

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuxSettingsTest {
    @Test
    fun `session close confirmation is enabled by default`() {
        assertTrue(ImuxSettings().state.confirmBeforeClosingSession)
    }

    @Test
    fun `project new menu is shown by default`() {
        assertTrue(ImuxSettings().state.showProjectNewAgentMenu)
    }

    @Test
    fun `all agents are enabled by default`() {
        assertEquals(AgentType.entries, ImuxSettings().enabledAgentTypes)
    }

    @Test
    fun `enabled agents can be changed without changing their order`() {
        val settings = ImuxSettings()

        settings.setEnabledAgentTypes(setOf(AgentType.PI, AgentType.CLAUDE))

        assertEquals(listOf(AgentType.CLAUDE, AgentType.PI), settings.enabledAgentTypes)
    }

    @Test
    fun `at least one agent must stay enabled`() {
        val settings = ImuxSettings()

        assertThrows(IllegalArgumentException::class.java) {
            settings.setEnabledAgentTypes(emptySet())
        }
        assertEquals(AgentType.entries, settings.enabledAgentTypes)
    }
}
