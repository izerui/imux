package com.github.izerui.imux

import com.github.izerui.imux.settings.PluginLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.MessageFormat
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

    /**
     * MessageFormat 把 `'` 当引用字符：带占位符的译文若写单个 `'`，会吞掉后续文本甚至整个 {0}。
     * 法语这类语言极易踩到，所以用真实格式化做往返校验，而不是只比对占位符集合。
     */
    @Test
    fun `parameterized translations survive message formatting`() {
        PluginLanguage.entries.forEach { language ->
            val bundle = properties(bundleFile(language))
            bundle.stringPropertyNames().forEach { key ->
                val value = bundle.getProperty(key)
                val placeholders = placeholders(value)
                if (placeholders.isEmpty()) {
                    // 无参消息不走 MessageFormat，写 '' 会原样显示两个撇号。
                    assertTrue("${language.id} 的 $key 没有占位符，不该转义单引号", !value.contains("''"))
                    return@forEach
                }
                val arguments = Array<Any>(placeholders.size) { "<arg$it>" }
                val formatted = MessageFormat(value, language.locale).format(arguments)
                arguments.forEach { argument ->
                    assertTrue(
                        "${language.id} 的 $key 格式化后丢失 $argument，通常是未转义的单引号：$formatted",
                        formatted.contains(argument.toString()),
                    )
                }
            }
        }
    }

    /** 无参消息保留原文撇号；带参消息格式化后也只能剩一个撇号。 */
    @Test
    fun `apostrophes render as a single character`() {
        assertEquals(
            "Langue de l'interface :",
            ImuxBundle.message(PluginLanguage.FRENCH, "settings.interface.language"),
        )
        assertEquals(
            "« Build » s'exécute en tant qu'agent en arrière-plan. Ouvrez-la lorsqu'elle sera inactive.",
            ImuxBundle.message(PluginLanguage.FRENCH, "notification.busy.content", "Build"),
        )
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
