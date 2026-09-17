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
    fun `IDEA MCP session injection uses the platform default port`() {
        val state = ImuxSettings().state

        assertTrue(state.injectIdeaMcp)
        assertEquals(64342, state.ideaMcpPort)
    }

    @Test
    fun `未自定义端口时采用自动检测值`() {
        val settings = ImuxSettings()

        settings.initializeIdeaMcpPortDefault(64355)

        assertEquals(64355, settings.state.ideaMcpPort)
        assertEquals(false, settings.state.ideaMcpPortCustomized)
    }

    @Test
    fun `自动端口成功初始化后不再变化`() {
        val settings = ImuxSettings()

        settings.initializeIdeaMcpPortDefault(64355)
        settings.initializeIdeaMcpPortDefault(64356)

        assertEquals(64355, settings.state.ideaMcpPort)
    }

    @Test
    fun `自动端口首次读取失败后允许重试`() {
        val settings = ImuxSettings()

        settings.initializeIdeaMcpPortDefault(null)
        settings.initializeIdeaMcpPortDefault(64355)

        assertEquals(64355, settings.state.ideaMcpPort)
    }

    @Test
    fun `用户修改端口后不再被自动检测覆盖`() {
        val settings = ImuxSettings()
        settings.setIdeaMcpPort(64360)

        settings.initializeIdeaMcpPortDefault(64355)

        assertEquals(64360, settings.state.ideaMcpPort)
        assertTrue(settings.state.ideaMcpPortCustomized)
    }

    @Test
    fun `all agents are enabled by default`() {
        assertEquals(AgentType.entries, ImuxSettings().enabledAgentTypes)
    }

    @Test
    fun `applying unchanged detected language keeps automatic mode`() {
        val settings = ImuxSettings()

        applyLanguageSelection(settings, settings.language)

        assertEquals(null, settings.state.languageId)
    }

    @Test
    fun `applying a different language persists explicit selection`() {
        val settings = ImuxSettings()
        val selected = PluginLanguage.entries.first { it != settings.language }

        applyLanguageSelection(settings, selected)

        assertEquals(selected.id, settings.state.languageId)
    }

    @Test
    fun `existing explicit language remains explicit on apply`() {
        val settings = ImuxSettings()
        val selected = settings.language
        settings.state.languageId = selected.id

        applyLanguageSelection(settings, selected)

        assertEquals(selected.id, settings.state.languageId)
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
