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
        assertTrue(source.contains("group(ImuxBundle.message(\"settings.group.idea.mcp\"))"))
        assertTrue(source.contains("group(ImuxBundle.message(\"settings.group.project.window\"))"))
        assertTrue("打开方式应使用互斥的单选组", source.contains("buttonsGroup(ImuxBundle.message(\"settings.open.session\"))"))
        assertTrue("关闭确认应使用原生复选框", source.contains("checkBox(ImuxBundle.message(\"settings.confirm.before.closing.session\"))"))
        assertTrue("关闭确认必须绑定持久化设置", source.contains("bindSelected(settings.state::confirmBeforeClosingSession)"))
        assertTrue("MCP 注入开关必须绑定持久化设置", source.contains("bindSelected(settings.state::injectIdeaMcp)"))
        assertTrue("MCP 端口必须限制在合法范围", source.contains("intTextField(1..65535)"))
        assertTrue("MCP 端口修改必须标记为用户值", source.contains("settings::setIdeaMcpPort"))
        assertTrue("MCP 端口不一致时必须给出校验提示", source.contains("settings.idea.mcp.port.mismatch"))
        assertTrue("MCP 设置必须提供连接检测按钮", source.contains("settings.idea.mcp.check"))
        assertTrue("连接检测不能阻塞 EDT", source.contains("executeOnPooledThread"))
        assertTrue("连接检测结果必须回到 EDT 更新", source.contains("ModalityState.any()"))
        assertTrue("单选组必须绑定值，否则 UI DSL 会在运行时拒绝创建页面", source.contains("Boolean::class.javaObjectType"))
    }

    @Test
    fun `settings page keeps important context inline`() {
        assertTrue(source.contains("settings.interface.language.comment"))
        assertTrue(source.contains("settings.confirm.before.closing.session.comment"))
        assertTrue(source.contains("settings.available.agents.comment"))
        assertTrue(source.contains("settings.idea.mcp.inject.comment"))
        assertTrue(source.contains("settings.idea.mcp.port.comment"))
        assertTrue(source.contains("settings.project.new.agent.menu.comment"))
        assertTrue("至少一个智能体的错误应绑定到字段", source.contains("validationOnApply"))
    }
}
