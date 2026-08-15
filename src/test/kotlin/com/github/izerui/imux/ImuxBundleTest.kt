package com.github.izerui.imux

import com.github.izerui.imux.settings.PluginLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

class ImuxBundleTest {
    @Test
    fun `resolves English and simplified Chinese independently`() {
        assertEquals(
            "Open Session",
            ImuxBundle.message(PluginLanguage.ENGLISH, "action.open.session.text"),
        )
        assertEquals(
            "打开会话",
            ImuxBundle.message(PluginLanguage.SIMPLIFIED_CHINESE, "action.open.session.text"),
        )
    }

    @Test
    fun `translation contains every default message key`() {
        val english = properties("src/main/resources/messages/ImuxBundle.properties")
        val chinese = properties("src/main/resources/messages/ImuxBundle_zh_CN.properties")

        assertEquals(english.keys, chinese.keys)
        assertTrue("资源包不能为空", english.isNotEmpty())
    }

    @Test
    fun `unknown persisted language falls back to English`() {
        assertEquals(PluginLanguage.ENGLISH, PluginLanguage.fromId("future-language"))
        assertEquals(PluginLanguage.ENGLISH, PluginLanguage.fromId(null))
    }

    private fun properties(path: String): Properties =
        Properties().apply {
            File(path).reader(Charsets.UTF_8).use(::load)
        }
}
