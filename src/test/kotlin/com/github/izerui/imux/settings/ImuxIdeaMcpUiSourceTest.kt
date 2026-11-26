package com.github.izerui.imux.settings

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuxIdeaMcpUiSourceTest {
    private val source = SourceCode("src/main/kotlin/com/github/izerui/imux/settings/ImuxIdeaMcpConfigurable.kt")

    @Test
    fun `IDEA MCP 页面包含连接设置和检测操作`() {
        val text = source.normalized

        assertTrue(text.contains("""group(ImuxBundle.message("settings.idea.mcp.connection.group"))"""))
        assertTrue(text.contains("bindSelected(settings.state::injectIdeaMcp)"))
        assertTrue(text.contains("intTextField(1..65535)"))
        assertTrue(text.contains("""button(ImuxBundle.message("settings.idea.mcp.check"))"""))
        assertTrue(text.contains("executeOnPooledThread"))
        assertTrue(text.contains("ModalityState.any()"))
    }

    @Test
    fun `页面有查调用链工具的开关，引导词既无开关也不可编辑`() {
        val text = source.normalized

        assertTrue(text.contains("""group(ImuxBundle.message("settings.code.tools.group"))"""))
        assertTrue(text.contains("bindSelected(settings.state::injectCodeTools)"))
        assertFalse(text.contains("settings.idea.mcp.guidance"))
        assertFalse(text.contains("textArea()"))
    }

    @Test
    fun `IDEA MCP 设置不再混在主设置页`() {
        val main = SourceCode("src/main/kotlin/com/github/izerui/imux/settings/ImuxSettingsConfigurable.kt")

        assertFalse(main.normalized.contains("settings.group.idea.mcp"))
    }

    @Test
    fun `主设置页切语言时设置 previewLanguage 并在 dispose 和 reset 时清除`() {
        val main = SourceCode("src/main/kotlin/com/github/izerui/imux/settings/ImuxSettingsConfigurable.kt")
        val text = main.normalized

        assertTrue(text.contains("setPreviewLanguage"))
        assertTrue(text.contains("clearPreviewLanguage"))
        assertTrue(text.contains("disposeUIResources"))
    }
}
