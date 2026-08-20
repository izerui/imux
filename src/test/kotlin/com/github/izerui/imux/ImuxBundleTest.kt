package com.github.izerui.imux

import com.github.izerui.imux.settings.PluginLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.Properties

class ImuxBundleTest {
    @Test
    fun `resolves each language independently`() {
        assertEquals(
            "Open Session",
            ImuxBundle.message(PluginLanguage.ENGLISH, "action.open.session.text"),
        )
        assertEquals(
            "打开会话",
            ImuxBundle.message(PluginLanguage.SIMPLIFIED_CHINESE, "action.open.session.text"),
        )
        assertEquals(
            "開啟工作階段",
            ImuxBundle.message(PluginLanguage.TRADITIONAL_CHINESE, "action.open.session.text"),
        )
        assertEquals(
            "セッションを開く",
            ImuxBundle.message(PluginLanguage.JAPANESE, "action.open.session.text"),
        )
        assertEquals(
            "세션 열기",
            ImuxBundle.message(PluginLanguage.KOREAN, "action.open.session.text"),
        )
        assertEquals(
            "Sitzung öffnen",
            ImuxBundle.message(PluginLanguage.GERMAN, "action.open.session.text"),
        )
        assertEquals(
            "Ouvrir la session",
            ImuxBundle.message(PluginLanguage.FRENCH, "action.open.session.text"),
        )
        assertEquals(
            "Abrir sesión",
            ImuxBundle.message(PluginLanguage.SPANISH, "action.open.session.text"),
        )
        assertEquals(
            "Abrir sessão",
            ImuxBundle.message(PluginLanguage.PORTUGUESE_BRAZIL, "action.open.session.text"),
        )
        assertEquals(
            "Открыть сессию",
            ImuxBundle.message(PluginLanguage.RUSSIAN, "action.open.session.text"),
        )
    }

    @Test
    fun `every language ships a bundle with the same keys`() {
        val english = properties(bundleFile(PluginLanguage.ENGLISH))
        assertTrue("资源包不能为空", english.isNotEmpty())

        PluginLanguage.entries.filter { it != PluginLanguage.ENGLISH }.forEach { language ->
            val file = bundleFile(language)
            assertTrue("缺少 ${language.id} 资源包：$file", File(file).isFile)
            assertEquals("${language.id} 的消息键与英文不一致", english.keys, properties(file).keys)
        }
    }

    @Test
    fun `translations keep the placeholders of the default bundle`() {
        val english = properties(bundleFile(PluginLanguage.ENGLISH))

        PluginLanguage.entries.filter { it != PluginLanguage.ENGLISH }.forEach { language ->
            val translated = properties(bundleFile(language))
            english.stringPropertyNames().forEach { key ->
                assertEquals(
                    "${language.id} 的 $key 占位符与英文不一致",
                    placeholders(english.getProperty(key)),
                    placeholders(translated.getProperty(key)),
                )
            }
        }
    }

    @Test
    fun `unknown persisted language falls back to English`() {
        assertEquals(PluginLanguage.ENGLISH, PluginLanguage.fromId("future-language"))
        assertEquals(PluginLanguage.ENGLISH, PluginLanguage.fromId(null))
    }

    @Test
    fun `locale detection prefers the exact region then the language`() {
        assertEquals(PluginLanguage.SIMPLIFIED_CHINESE, PluginLanguage.fromLocale(Locale.forLanguageTag("zh-CN")))
        assertEquals(PluginLanguage.TRADITIONAL_CHINESE, PluginLanguage.fromLocale(Locale.forLanguageTag("zh-TW")))
        assertEquals(PluginLanguage.TRADITIONAL_CHINESE, PluginLanguage.fromLocale(Locale.forLanguageTag("zh-HK")))
        assertEquals(PluginLanguage.PORTUGUESE_BRAZIL, PluginLanguage.fromLocale(Locale.forLanguageTag("pt-PT")))
        assertEquals(PluginLanguage.GERMAN, PluginLanguage.fromLocale(Locale.forLanguageTag("de-AT")))
        assertEquals(PluginLanguage.ENGLISH, PluginLanguage.fromLocale(Locale.forLanguageTag("en-US")))
        assertEquals(PluginLanguage.ENGLISH, PluginLanguage.fromLocale(Locale.forLanguageTag("sv-SE")))
        assertEquals(PluginLanguage.ENGLISH, PluginLanguage.fromLocale(null))
    }

    private fun bundleFile(language: PluginLanguage): String {
        val suffix =
            when (language) {
                PluginLanguage.ENGLISH -> ""
                else -> "_" + language.id.replace('-', '_')
            }
        return "src/main/resources/messages/ImuxBundle$suffix.properties"
    }

    private fun placeholders(value: String?): Set<String> = PLACEHOLDER.findAll(value.orEmpty()).map(MatchResult::value).toSet()

    private fun properties(path: String): Properties =
        Properties().apply {
            File(path).reader(Charsets.UTF_8).use(::load)
        }

    private companion object {
        val PLACEHOLDER = Regex("\\{\\d+}")
    }
}
