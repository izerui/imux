package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BridgeDecorationsSourceTest {
    private val decorator =
        SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/BridgeSessionDecorator.kt").normalized
    private val hub = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BridgeHub.kt").normalized
    private val tool = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneTool.kt").normalized
    private val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()

    @Test
    fun `会话装饰按绑定关系显示`() {
        assertTrue(decorator.contains("= BridgeHub.getInstance().bindings.bindingForTab(tabId) != null"))
    }

    @Test
    fun `手机按钮直接提供机器人连接菜单`() {
        val create = decorator.substringAfter("override fun createTool(")
        assertTrue("装饰器无条件创建手机按钮", create.contains("= PhoneTool(project, file, parent, notices)"))
        assertTrue("创建手机按钮时不看开关", !create.contains("BridgeSettings"))
        val menu = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneTool.kt")
        val perform = menu.bodyAfter("override fun perform(anchor: JComponent)", '{')
        assertTrue(perform.contains("showConnectMenu(anchor)"))
        val connectMenu = menu.bodyAfter("private fun showConnectMenu(", '{')
        assertTrue(connectMenu.contains("phoneMenuFor(file.tabId)"))
        assertTrue(connectMenu.contains("AddRobotDialog(project).show()"))
        assertFalse(connectMenu.contains("bridge.action.disabled"))
        assertTrue("按钮任何状态都不隐藏", !menu.normalized.contains("isVisible"))
    }

    @Test
    fun `断线发常驻提示并带重连与换绑，连上时提示一次`() {
        val source = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneTool.kt")
        val refresh = source.bodyAfter(
            "private fun refresh()",
            '{'
        )
        assertTrue(refresh.contains("tone = NoticeTone.WARNING"))
        assertTrue(refresh.contains("RobotManager.getInstance().reconnect(current.model.robotId)"))
        assertTrue("提示上的换绑直接弹出机器人列表", source.compact(refresh).contains("showRebindMenu(anchor)"))
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
    fun `换绑的机器人平铺在菜单里，不再套二级菜单`() {
        assertFalse(tool.contains("DefaultActionGroup(ImuxBundle.message(\"bridge.banner.rebind\"), true)"))
        val menu = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneTool.kt").bodyAfter(
            "private fun showMenu(",
            '{'
        )
        assertTrue(menu.contains("Separator.create(ImuxBundle.message(\"bridge.banner.rebind\"))"))
        assertTrue(menu.contains("addRobots(group)"))
    }

    @Test
    fun `菜单显示不可选的项：当前连着的机器人打勾、不可选`() {
        val popup = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneTool.kt").bodyAfter(
            "private fun popup(",
            '{'
        )
        val args = popup.lines().filterNot { it.trim().startsWith("//") }.joinToString("").filterNot(Char::isWhitespace)
        assertTrue("不可选的也显示", args.contains("ActionSelectionAid.SPEEDSEARCH,true,"))
        val actions = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneActions.kt").normalized
        assertTrue(actions.contains("if (entry.current) AllIcons.Actions.Checked else null"))
        assertTrue("三处菜单都用同一个画法", !tool.contains("phoneAction(entry.label"))
    }

    @Test
    fun `注册了装饰实现`() {
        assertTrue(pluginXml.contains("<extensions defaultExtensionNs=\"com.github.izerui.imux\">"))
        assertTrue(pluginXml.contains("com.github.izerui.imux.bridge.ui.BridgeSessionDecorator"))
    }
}
