package com.github.izerui.imux.settings

import com.github.izerui.imux.peer.PeerCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `英文默认文本切到中文后变为中文默认`() {
        val result = promptAfterLanguageSwitch(PeerCoordinator.DEFAULT_PROMPT_EN, PluginLanguage.SIMPLIFIED_CHINESE)
        assertEquals(PeerCoordinator.DEFAULT_PROMPT_ZH, result)
    }

    @Test
    fun `中文默认文本切到英文后变为英文默认`() {
        val result = promptAfterLanguageSwitch(PeerCoordinator.DEFAULT_PROMPT_ZH, PluginLanguage.ENGLISH)
        assertEquals(PeerCoordinator.DEFAULT_PROMPT_EN, result)
    }

    @Test
    fun `自定义文本切语言后保持不变`() {
        val custom = "我的自定义提示词"
        assertEquals(custom, promptAfterLanguageSwitch(custom, PluginLanguage.SIMPLIFIED_CHINESE))
        assertEquals(custom, promptAfterLanguageSwitch(custom, PluginLanguage.ENGLISH))
    }

    @Test
    fun `默认提示词不需要持久化`() {
        assertTrue(isDefaultPeerPrompt(PeerCoordinator.DEFAULT_PROMPT_ZH))
        assertTrue(isDefaultPeerPrompt(PeerCoordinator.DEFAULT_PROMPT_EN))
        assertFalse(isDefaultPeerPrompt("自定义内容"))
        assertFalse(isDefaultPeerPrompt(""))
    }
}
