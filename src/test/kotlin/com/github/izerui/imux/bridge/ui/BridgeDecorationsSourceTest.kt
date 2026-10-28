package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BridgeDecorationsSourceTest {
    private val decorator = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/BridgeSessionDecorator.kt").normalized
    private val hub = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BridgeHub.kt").normalized
    private val banner = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneBanner.kt").normalized
    private val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()

    @Test
    fun `开关关闭时不查询中枢`() {
        assertTrue(decorator.contains("BridgeSettings.getInstance().state.enabled &&"))
    }

    @Test
    fun `开关关闭时也创建手机段，只在点开菜单时引导去设置开启`() {
        val create = decorator.substringAfter("override fun createBannerSegment(")
        assertTrue("装饰器无条件创建手机段", create.contains("= PhoneBanner(project, file, parent)"))
        assertTrue("创建手机段时不看开关", !create.contains("BridgeSettings"))
        val menu = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneBanner.kt")
        val showMenu = menu.bodyAfter("private fun showMenu()", '{')
        assertTrue(showMenu.contains("showConnectMenu(segment.bridgeEnabled)"))
        val connectMenu = menu.bodyAfter("private fun showConnectMenu(bridgeEnabled: Boolean)", '{')
        val disabledBranch = connectMenu.substringAfter("if (!bridgeEnabled) {").substringBefore("return")
        assertTrue("关闭时菜单只引导去设置", disabledBranch.contains("\"bridge.action.disabled\""))
        assertTrue(disabledBranch.contains("showRobotSettings(project)"))
        assertTrue("开关关着时不走会实例化中枢的菜单构造", !disabledBranch.contains("phoneMenuFor"))
        val refresh = menu.bodyAfter("private fun refresh()", '{')
        assertTrue("刷新时不隐藏手机段", !refresh.contains("isVisible"))
    }

    @Test
    fun `机器人、状态或绑定变化时在 EDT 广播装饰变化`() {
        assertTrue(hub.contains("invokeLater({ SessionDecorations.changed() }, ModalityState.any())"))
    }

    @Test
    fun `提示条订阅装饰变化并复用绑定操作`() {
        assertTrue(banner.contains("SessionDecorationsListener.TOPIC"))
        assertTrue(banner.contains("PhoneOperations.bind("))
        assertTrue(banner.contains("PhoneOperations.disconnect("))
    }

    @Test
    fun `注册了装饰实现`() {
        assertTrue(pluginXml.contains("<extensions defaultExtensionNs=\"com.github.izerui.imux\">"))
        assertTrue(pluginXml.contains("com.github.izerui.imux.bridge.ui.BridgeSessionDecorator"))
    }
}
