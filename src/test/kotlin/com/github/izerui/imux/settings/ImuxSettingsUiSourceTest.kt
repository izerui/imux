package com.github.izerui.imux.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImuxSettingsUiSourceTest {
    private val source: String by lazy {
        File("src/main/kotlin/com/github/izerui/imux/settings/ImuxSettingsConfigurable.kt").readText()
    }

    @Test
    fun `settings page uses semantic section headers and native control groups`() {
        assertTrue(source.contains("group(ImuxBundle.message(\"settings.group.sessions\"))"))
        assertTrue(source.contains("group(ImuxBundle.message(\"settings.group.agents\"))"))
        assertTrue(source.contains("group(ImuxBundle.message(\"settings.group.project.window\"))"))
        assertTrue("打开方式应使用互斥的单选组", source.contains("buttonsGroup(ImuxBundle.message(\"settings.open.session\"))"))
        assertTrue("单选组必须绑定值，否则 UI DSL 会在运行时拒绝创建页面", source.contains("Boolean::class.javaObjectType"))
    }

    @Test
    fun `settings page keeps important context inline`() {
        assertTrue(source.contains("settings.interface.language.comment"))
        assertTrue(source.contains("settings.available.agents.comment"))
        assertTrue(source.contains("settings.project.new.agent.menu.comment"))
        assertTrue("至少一个智能体的错误应绑定到字段", source.contains("validationOnApply"))
    }
}
