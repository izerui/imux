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
}
