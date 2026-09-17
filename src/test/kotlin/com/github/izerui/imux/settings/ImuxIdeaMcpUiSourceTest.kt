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
    fun `IDEA MCP 页面包含可编辑引导和恢复默认操作`() {
        val text = source.normalized

        assertTrue(text.contains("""group(ImuxBundle.message("settings.idea.mcp.guidance.group"))"""))
        assertTrue(text.contains("bindSelected(settings.state::ideaMcpGuidanceEnabled)"))
        assertTrue(text.contains("textArea()"))
        assertTrue(text.contains("settings::setIdeaMcpGuidance"))
        assertTrue(text.contains("""button(ImuxBundle.message("settings.idea.mcp.guidance.restore"))"""))
        assertTrue(text.contains("guidanceArea?.text = DEFAULT_IDEA_MCP_GUIDANCE"))
    }

    @Test
    fun `IDEA MCP 设置不再混在主设置页`() {
        val main = SourceCode("src/main/kotlin/com/github/izerui/imux/settings/ImuxSettingsConfigurable.kt")

        assertFalse(main.normalized.contains("settings.group.idea.mcp"))
    }
}
