package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneActionsSourceTest {
    private val actions = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneActions.kt").normalized
    private val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()

    @Test
    fun `界面的连接、断开都经绑定服务，不直接改绑定表`() {
        assertTrue(actions.contains("BindingService.bind(project, robotId, target, title)"))
        assertTrue(actions.contains("BindingService.unbind(robotId)"))
        assertTrue(!actions.contains("bindings.bind(") && !actions.contains("bindings.unbind("))
        val settings = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/RobotsConfigurable.kt").normalized
        assertTrue(settings.contains("BindingService.unbind(") && !settings.contains("bindings.unbind("))
    }

    @Test
    fun `绑定服务：先断开占用者再绑定，断开与连接提示在同一协程里按顺序发，并启动观察`() {
        val service = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BindingService.kt")
        val bind = service.compact(service.bodyAfter("fun bind(project: Project, robotId: RobotId, target: SessionTarget, sessionTitle: String,)", '{'))
        assertTrue(bind.contains("occupant?.let{hub.bindings.unbind(it.robotId)}valresult=hub.bindings.bind(robotId,target)"))
        assertTrue("断开与连接提示在同一个协程里按顺序发，绑定失败也通知占用者", bind.contains("hub.launch{hub.router.onRebound(released,binding,"))
        assertTrue("只启动一个协程", Regex("hub\\.launch").findAll(bind).count() == 1)
        assertTrue(bind.contains("TerminalBridgeService.getInstance(project).start()"))
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
