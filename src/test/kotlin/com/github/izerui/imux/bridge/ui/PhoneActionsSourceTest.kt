package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneActionsSourceTest {
    private val actions = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneActions.kt").normalized
    private val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()

    @Test
    fun `绑定后通知手机并持久化，换绑先通知旧会话断开`() {
        assertTrue(actions.contains("router.onReleased("))
        assertTrue(actions.contains("router.onBound("))
        assertTrue(actions.contains("RobotManager.getInstance().syncBindings()"))
        assertTrue(actions.contains("TerminalBridgeService.getInstance(project).start()"))
    }

    @Test
    fun `替换已有连接前需要确认`() {
        assertTrue(actions.contains("bridge.action.replace.message"))
    }

    @Test
    fun `注册了右键菜单组与通知分组`() {
        assertTrue(pluginXml.contains("com.github.izerui.imux.bridge.ui.ConnectToPhoneGroup"))
        assertTrue(pluginXml.contains("imux.phoneBridge"))
    }
}
