package com.github.izerui.imux.settings

import com.github.izerui.imux.peer.PeerCoordinator
import org.junit.Assert.assertEquals
import org.junit.Test

class PeerPromptLanguageSwitchTest {

    @Test
    fun `英文语言返回英文默认提示词`() {
        assertEquals(PeerCoordinator.DEFAULT_PROMPT_EN, defaultPeerPromptForLanguage(PluginLanguage.ENGLISH))
    }

    @Test
    fun `简体中文语言返回中文默认提示词`() {
        assertEquals(PeerCoordinator.DEFAULT_PROMPT_ZH, defaultPeerPromptForLanguage(PluginLanguage.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `繁体中文语言返回中文默认提示词`() {
        assertEquals(PeerCoordinator.DEFAULT_PROMPT_ZH, defaultPeerPromptForLanguage(PluginLanguage.TRADITIONAL_CHINESE))
    }

    @Test
    fun `其他语言返回英文默认提示词`() {
        val chineseLanguages = setOf(PluginLanguage.SIMPLIFIED_CHINESE, PluginLanguage.TRADITIONAL_CHINESE)
        for (lang in PluginLanguage.entries.filter { it !in chineseLanguages }) {
            assertEquals("$lang should use EN", PeerCoordinator.DEFAULT_PROMPT_EN, defaultPeerPromptForLanguage(lang))
        }
    }
}
