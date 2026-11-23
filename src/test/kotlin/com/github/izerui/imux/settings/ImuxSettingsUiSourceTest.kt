package com.github.izerui.imux.settings

import com.github.izerui.imux.SourceCode
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

    @Test
    fun `副驾驶默认提示词跟随界面语言`() {
        assertTrue("应通过抽取函数选择提示词", source.contains("defaultPeerPromptForLanguage"))
        assertTrue("语言切换应调用抽取函数", source.contains("promptAfterLanguageSwitch"))
        assertTrue("语言切换时应联动更新提示词文本框", source.contains("addActionListener"))
    }

    @Test
    fun `恢复默认同时更新提示词文本框`() {
        assertTrue(source.contains("defaultPeerPromptForLanguage(selectedLanguage)"))
    }

    @Test
    fun `语言切换后视口滚回顶部`() {
        val block = source.substringAfter("promptAfterLanguageSwitch").substringBefore("selectedLanguage")
        assertTrue("语言切换 addActionListener 块内缺少 scrollToTop", block.contains("scrollToTop(promptArea)"))
    }

    @Test
    fun `恢复默认后视口滚回顶部`() {
        val block = source.substringAfter("settings.peer.prompt.restore").substringBefore("settings.group.project.window")
        assertTrue("恢复默认按钮回调内缺少 scrollToTop", block.contains("scrollToTop(promptArea)"))
    }

    @Test
    fun `onReset 后视口滚回顶部`() {
        val block = source.substringAfter("onReset").substringBefore("onIsModified")
        assertTrue("onReset 回调内缺少 scrollToTop", block.contains("scrollToTop(promptArea)"))
    }

    /**
     * 长文案不自动换行时，文本框按最长一行撑宽，整个设置页被往右撑出去（实测一句默认回复引导就撑到 889px）。
     * 页上每个多行文本框都必须自动换行，宽度才跟着设置窗口走。
     */
    @Test
    fun `设置页的回复引导与副驾驶提示词两个文本框都自动换行`() {
        val compact = SourceCode.fromText(source).compact(source)
        listOf("replyArea=textArea()", "promptArea=textArea()").forEach { area ->
            val start = compact.indexOf(area)
            assertTrue("找不到文本框：$area", start >= 0)
            val setup = compact.substring(start, compact.indexOf(".align(", start))
            assertTrue("$area 没开自动换行：$setup", setup.contains("lineWrap=true") && setup.contains("wrapStyleWord=true"))
        }
        assertTrue("页上多了一个文本框却没纳入本条守卫", Regex("""=textArea\(\)""").findAll(compact).count() == 2)
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
