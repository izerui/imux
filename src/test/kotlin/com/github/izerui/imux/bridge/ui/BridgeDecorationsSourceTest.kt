package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BridgeDecorationsSourceTest {
    private val decorator = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/BridgeSessionDecorator.kt").normalized
    private val manager = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/robot/RobotManager.kt").normalized
    private val service = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/TerminalBridgeService.kt").normalized
    private val banner = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneBanner.kt").normalized
    private val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()

    @Test
    fun `开关关闭时不查询中枢`() {
        assertTrue(decorator.contains("BridgeSettings.getInstance().state.enabled &&"))
    }

    @Test
    fun `机器人、状态或绑定变化时在 EDT 广播装饰变化`() {
        assertTrue(manager.contains("SessionDecorations.changed()"))
        assertTrue(manager.contains("ModalityState.any()"))
    }

    @Test
    fun `重新挂接后同步，标记随之刷新`() {
        assertTrue(service.contains("TabChange.Reattach"))
        assertTrue(service.contains("RobotManager.getInstance().syncBindings()"))
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
