package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BridgeDecorationsSourceTest {
    private val decorator = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/BridgeSessionDecorator.kt").normalized
    private val hub = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BridgeHub.kt").normalized
    private val tool = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneTool.kt").normalized
    private val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()

    @Test
    fun `开关关闭时不查询中枢`() {
        assertTrue(decorator.contains("BridgeSettings.getInstance().state.enabled &&"))
    }

    @Test
    fun `开关关闭时也创建手机按钮，只在点开菜单时引导去设置开启`() {
        val create = decorator.substringAfter("override fun createTool(")
        assertTrue("装饰器无条件创建手机按钮", create.contains("= PhoneTool(project, file, parent, notices)"))
        assertTrue("创建手机按钮时不看开关", !create.contains("BridgeSettings"))
        val menu = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneTool.kt")
        val perform = menu.bodyAfter("override fun perform(anchor: JComponent)", '{')
        assertTrue(perform.contains("showConnectMenu(segment.bridgeEnabled, anchor)"))
        val connectMenu = menu.bodyAfter("private fun showConnectMenu(", '{')
        val disabledBranch = connectMenu.substringAfter("if (!bridgeEnabled) {").substringBefore("return")
        assertTrue("关闭时菜单只引导去设置", disabledBranch.contains("\"bridge.action.disabled\""))
        assertTrue(disabledBranch.contains("showRobotSettings(project)"))
        assertTrue("开关关着时不走会实例化中枢的菜单构造", !disabledBranch.contains("phoneMenuFor"))
        val state = menu.bodyAfter("private fun segment()", '{')
        assertTrue("开关关着时不查询中枢", state.indexOf("if (!enabled) return") in 0 until state.indexOf("BridgeHub.getInstance()"))
        assertTrue("按钮任何状态都不隐藏", !menu.normalized.contains("isVisible"))
    }

    @Test
    fun `断线发常驻提示并带重连与换绑，连上时提示一次`() {
        val refresh = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneTool.kt").bodyAfter("private fun refresh()", '{')
        assertTrue(refresh.contains("tone = NoticeTone.WARNING"))
        assertTrue(refresh.contains("RobotManager.getInstance().reconnect(current.model.robotId)"))
        assertTrue(refresh.contains("showMenu(current.model, anchor)"))
        assertTrue("连接中的提示不能收起", refresh.contains("closable = false"))
        assertTrue(refresh.contains("phoneJustConnected(noticeState, current)"))
    }

    @Test
    fun `机器人、状态或绑定变化时在 EDT 广播装饰变化`() {
        assertTrue(hub.contains("invokeLater({ SessionDecorations.changed() }, ModalityState.any())"))
    }

    @Test
    fun `手机按钮订阅装饰变化并复用绑定操作`() {
        assertTrue(tool.contains("SessionDecorationsListener.TOPIC"))
        assertTrue(tool.contains("PhoneOperations.bind("))
        assertTrue(tool.contains("PhoneOperations.disconnect("))
    }

    @Test
    fun `注册了装饰实现`() {
        assertTrue(pluginXml.contains("<extensions defaultExtensionNs=\"com.github.izerui.imux\">"))
        assertTrue(pluginXml.contains("com.github.izerui.imux.bridge.ui.BridgeSessionDecorator"))
    }
}
