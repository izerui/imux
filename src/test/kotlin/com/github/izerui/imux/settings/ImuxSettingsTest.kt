package com.github.izerui.imux.settings

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `查调用链工具默认开启`() {
        assertEquals(true, ImuxSettings().state.injectCodeTools)
    }

    // region pendingLanguage / effectiveLanguage

    @Test
    fun `effectiveLanguage 无预览时返回持久化语言`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)

        assertEquals(null, settings.pendingLanguage)
        assertEquals(PluginLanguage.ENGLISH, settings.effectiveLanguage)
    }

    @Test
    fun `effectiveLanguage 有预览时返回预览语言`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)
        settings.setPreviewLanguage(PluginLanguage.SIMPLIFIED_CHINESE)

        assertEquals(PluginLanguage.SIMPLIFIED_CHINESE, settings.effectiveLanguage)
        assertEquals(PluginLanguage.ENGLISH, settings.language)
    }

    @Test
    fun `clearPreviewLanguage 清除后回到持久化语言`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)
        settings.setPreviewLanguage(PluginLanguage.SIMPLIFIED_CHINESE)

        settings.clearPreviewLanguage()

        assertEquals(null, settings.pendingLanguage)
        assertEquals(PluginLanguage.ENGLISH, settings.effectiveLanguage)
    }

    @Test
    fun `setPreviewLanguage 变化时触发语言监听`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)
        var fired = false
        val disposable = com.intellij.openapi.util.Disposer.newDisposable()
        try {
            settings.addLanguageListener(disposable) { fired = true }
            settings.setPreviewLanguage(PluginLanguage.SIMPLIFIED_CHINESE)
            assertTrue(fired)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(disposable)
        }
    }

    @Test
    fun `setPreviewLanguage 与当前 effectiveLanguage 相同时不触发监听`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)
        settings.setPreviewLanguage(PluginLanguage.ENGLISH)
        var fired = false
        val disposable = com.intellij.openapi.util.Disposer.newDisposable()
        try {
            settings.addLanguageListener(disposable) { fired = true }
            settings.setPreviewLanguage(PluginLanguage.ENGLISH)
            assertFalse(fired)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(disposable)
        }
    }

    @Test
    fun `clearPreviewLanguage 变化时触发语言监听`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)
        settings.setPreviewLanguage(PluginLanguage.SIMPLIFIED_CHINESE)
        var fired = false
        val disposable = com.intellij.openapi.util.Disposer.newDisposable()
        try {
            settings.addLanguageListener(disposable) { fired = true }
            settings.clearPreviewLanguage()
            assertTrue(fired)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(disposable)
        }
    }

    @Test
    fun `clearPreviewLanguage 无变化时不触发监听`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)
        var fired = false
        val disposable = com.intellij.openapi.util.Disposer.newDisposable()
        try {
            settings.addLanguageListener(disposable) { fired = true }
            settings.clearPreviewLanguage()
            assertFalse(fired)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(disposable)
        }
    }

    @Test
    fun `语言预览逻辑：预览中文后 effectiveLanguage 产出中文引导词 清除后恢复英文`() {
        val settings = ImuxSettings()
        settings.setLanguage(PluginLanguage.ENGLISH)

        settings.setPreviewLanguage(PluginLanguage.SIMPLIFIED_CHINESE)
        assertEquals(agentGuidance(chinese = true, ideaMcp = true, codeTools = true), agentGuidanceForLanguage(settings.effectiveLanguage, true, true))

        settings.clearPreviewLanguage()
        assertEquals(agentGuidance(chinese = false, ideaMcp = true, codeTools = true), agentGuidanceForLanguage(settings.effectiveLanguage, true, true))
    }

    // endregion

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
