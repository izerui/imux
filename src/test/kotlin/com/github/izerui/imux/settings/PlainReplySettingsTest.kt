package com.github.izerui.imux.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainReplySettingsTest {
    @Test
    fun `回复引导默认开启并跟随插件语言给出默认文案`() {
        val settings = ImuxSettings()

        assertEquals(true, settings.state.plainReplyEnabled)
        assertNull(settings.state.plainReplyGuidanceOverride)

        settings.setLanguage(PluginLanguage.SIMPLIFIED_CHINESE)
        assertEquals(DEFAULT_PLAIN_REPLY_GUIDANCE_ZH, settings.plainReplyGuidance)
        settings.setLanguage(PluginLanguage.ENGLISH)
        assertEquals(DEFAULT_PLAIN_REPLY_GUIDANCE_EN, settings.plainReplyGuidance)
    }

    /**
     * 默认文案只有一句，但各分句都有实测依据（见常量的 KDoc）：
     * 少用代码名（Codex 不写这句就满屏类名）、先讲本体再打比方且比方不能顶替原意（只说「打个比方」时
     * 回复有时只剩比方）、坏消息照实说。中英文各自都要在。
     */
    @Test
    fun `中英文默认文案都要求少用代码名、先讲本体再打比方并照实说出没做完、失败与有风险的事`() {
        listOf("直白", "类名", "先把事情本身说清楚", "比方不能代替原意", "没做完", "失败", "有风险").forEach {
            assertTrue("中文缺少：$it", DEFAULT_PLAIN_REPLY_GUIDANCE_ZH.contains(it))
        }
        listOf("plainly", "class or function names", "actual point first", "never in place of it", "unfinished", "failed", "risky").forEach {
            assertTrue("英文缺少：$it", DEFAULT_PLAIN_REPLY_GUIDANCE_EN.contains(it))
        }
    }

    /** 一句话是有意为之：列成清单会让模型照格式填空。防止以后又被加长成规章。 */
    @Test
    fun `默认文案保持一句话的篇幅`() {
        assertTrue(DEFAULT_PLAIN_REPLY_GUIDANCE_ZH.length < 120)
        assertTrue(DEFAULT_PLAIN_REPLY_GUIDANCE_EN.length < 300)
        assertTrue(!DEFAULT_PLAIN_REPLY_GUIDANCE_ZH.contains('\n') && !DEFAULT_PLAIN_REPLY_GUIDANCE_EN.contains('\n'))
    }

    @Test
    fun `自定义文案落盘，改回任一语言的默认值则清除自定义`() {
        val settings = ImuxSettings()

        settings.setPlainReplyGuidance("custom")
        assertEquals("custom", settings.plainReplyGuidance)

        settings.setPlainReplyGuidance(DEFAULT_PLAIN_REPLY_GUIDANCE_ZH)
        assertNull(settings.state.plainReplyGuidanceOverride)

        settings.setPlainReplyGuidance("custom")
        settings.setPlainReplyGuidance(DEFAULT_PLAIN_REPLY_GUIDANCE_EN)
        assertNull(settings.state.plainReplyGuidanceOverride)
    }

    @Test
    fun `切换语言时默认文案随之切换而自定义文案保留`() {
        assertEquals(DEFAULT_PLAIN_REPLY_GUIDANCE_ZH, plainReplyGuidanceAfterLanguageSwitch(DEFAULT_PLAIN_REPLY_GUIDANCE_EN, PluginLanguage.TRADITIONAL_CHINESE))
        assertEquals(DEFAULT_PLAIN_REPLY_GUIDANCE_EN, plainReplyGuidanceAfterLanguageSwitch(DEFAULT_PLAIN_REPLY_GUIDANCE_ZH, PluginLanguage.JAPANESE))
        assertEquals("mine", plainReplyGuidanceAfterLanguageSwitch("mine", PluginLanguage.ENGLISH))
    }
}
