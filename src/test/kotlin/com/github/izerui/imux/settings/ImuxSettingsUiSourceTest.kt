package com.github.izerui.imux.settings

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuxSettingsUiSourceTest {
    private val source =
        SourceCode("src/main/kotlin/com/github/izerui/imux/settings/ImuxSettingsConfigurable.kt").normalized

    @Test
    fun `settings page uses semantic section headers and native control groups`() {
        assertTrue(source.contains("group(ImuxBundle.message(\"settings.group.sessions\"))"))
        assertTrue(source.contains("group(ImuxBundle.message(\"settings.group.agents\"))"))
        assertTrue(source.contains("group(ImuxBundle.message(\"settings.group.project.window\"))"))
        assertTrue(
            "打开方式应使用互斥的单选组",
            source.contains("buttonsGroup(ImuxBundle.message(\"settings.open.session\"))")
        )
        assertTrue(
            "关闭确认应使用原生复选框",
            source.contains("checkBox(ImuxBundle.message(\"settings.confirm.before.closing.session\"))")
        )
        assertTrue(
            "关闭确认必须绑定持久化设置",
            source.contains("bindSelected(settings.state::confirmBeforeClosingSession)")
        )
        assertTrue(
            "单选组必须绑定值，否则 UI DSL 会在运行时拒绝创建页面",
            source.contains("Boolean::class.javaObjectType")
        )
    }

    @Test
    fun `settings page keeps important context inline`() {
        assertTrue(source.contains("settings.interface.language.comment"))
        assertTrue(source.contains("settings.confirm.before.closing.session.comment"))
        assertTrue(source.contains("settings.available.agents.comment"))
        assertTrue(source.contains("settings.project.new.agent.menu.comment"))
        assertTrue("至少一个智能体的错误应绑定到字段", source.contains("validationOnApply"))
    }

    /** 回复引导词、副驾驶提示词都不开放编辑：页上只留开关，不能再出现文本框或「恢复默认」。 */
    @Test
    fun `回复风格只留开关，回复引导词和副驾驶提示词都没有文本框和恢复默认`() {
        assertTrue(source.contains("bindSelected(settings.state::plainReplyEnabled)"))
        assertFalse(source.contains("textArea()"))
        assertFalse(source.contains("settings.reply.plain.prompt"))
        assertFalse(source.contains("settings.reply.plain.restore"))
        assertFalse(source.contains("settings.peer.prompt"))
    }

    /**
     * 挂在控件后面的说明（`Cell.comment`）默认按 70 个字折行（平台 DEFAULT_COMMENT_WIDTH），70 个汉字近 900px，
     * 而且最小宽度缩不下去，窗口窄一点就出横向滚动条。实测主设置页改为按页宽折行后最小宽度从 462 降到 199
     * （测试环境字体）。行级说明（`Row.comment`）默认就按页宽折行，不在此列。
     */
    @Test
    fun `主设置页与 IDEA MCP 页的每一处控件说明都按页面宽度折行`() {
        listOf("ImuxSettingsConfigurable", "ImuxIdeaMcpConfigurable").forEach { name ->
            val text = java.io.File("src/main/kotlin/com/github/izerui/imux/settings/$name.kt").readText()
            val cellComments = Regex("""\.comment\((.*)""").findAll(text).map { it.groupValues[1] }.toList()
            assertTrue("$name 找不到控件说明，这条断言会恒真", cellComments.isNotEmpty())
            cellComments.forEach { call ->
                assertTrue("$name 的控件说明没按页宽折行：$call", call.contains("maxLineLength = MAX_LINE_LENGTH_WORD_WRAP"))
            }
        }
    }
}
