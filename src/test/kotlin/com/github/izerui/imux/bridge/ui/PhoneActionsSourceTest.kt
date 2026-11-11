package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneActionsSourceTest {
    private val actions = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneActions.kt").normalized
    private val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()

    @Test
    fun `连接菜单使用实际会话可用性查询`() {
        val source = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneActions.kt")
        val menu = source.compactArgs(source.bodyAfter("internal fun phoneMenuFor(tabId: String)", '{'))
        assertTrue(menu.contains("phoneMenuModel(manager.robots(),hub.bindings::bindingOf,::bindingSessionAvailable,"))
    }

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
        assertTrue("断开与连接提示按顺序发，并受项目关闭取消", bind.contains("bridge.notifyConnection{hub.router.onRebound(released,binding,"))
        assertTrue("只提交一组连接通知", Regex("bridge\\.notifyConnection").findAll(bind).count() == 1)
        assertTrue("先启动关闭监听再提交通知", bind.contains("valbridge=TerminalBridgeService.getInstance(project)bridge.start()bridge.notifyConnection"))
    }

    @Test
    fun `替换已有连接前需要确认`() {
        assertTrue(actions.contains("bridge.action.replace.message"))
    }

    @Test
    fun `注册了通知分组`() {
        assertTrue(pluginXml.contains("imux.phoneBridge"))
    }
}
