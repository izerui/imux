package com.github.izerui.imux.bridge

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeWiringSourceTest {
    private val service =
        SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/TerminalBridgeService.kt").normalized
    private val startup =
        SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BridgeStartupActivity.kt").normalized
    private val hub = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BridgeHub.kt").normalized
    private val pluginXml = java.io.File("src/main/resources/META-INF/plugin.xml").readText()

    @Test
    fun `项目打开时自动连接机器人并启动终端观察`() {
        assertTrue(startup.contains("= BridgeLifecycle.onProjectOpened(project)"))
        val lifecycle = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BridgeLifecycle.kt")
        val opened = lifecycle.compact(lifecycle.bodyAfter("fun onProjectOpened(project: Project)", '{'))
        assertTrue(opened.contains("RobotManager.getInstance().startAll()"))
        assertTrue(opened.contains("if(!project.isDisposed)TerminalBridgeService.getInstance(project).start()"))
        assertTrue(!opened.contains("BridgeSettings"))
    }

    @Test
    fun `桥接不再读取旧开关配置`() {
        val readers =
            java.io.File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
                .filter { SourceCode(it.path).normalized.contains("BridgeSettings") }
                .map { it.name }.toList()
        assertTrue("旧 imux-bridge.xml 不得继续控制桥接：$readers", readers.isEmpty())
    }

    @Test
    fun `绑定表一改动就由中枢持久化，各处不再手动同步`() {
        assertTrue(hub.contains("bindings.onChanged = {"))
        assertTrue(hub.contains("store.update { it.withBindings(bindingsToSave(bindings.snapshot())) }"))
        val manual =
            java.io.File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
                .filter { SourceCode(it.path).normalized.contains("syncBindings") }
                .map { it.name }.toList()
        assertTrue("不应再有手动同步：$manual", manual.isEmpty())
    }

    @Test
    fun `只订阅现有事件，不调用原有状态机`() {
        assertTrue(service.contains("host.addSessionsChangedListener(this)"))
        assertTrue(service.contains("host.addSessionKeyMigratedListener(this)"))
        assertTrue(service.contains("TurnEventsListener.TOPIC"))
        assertTrue(!service.contains("turnWatcher"))
        assertTrue(!service.contains("TurnNotifier"))
        assertTrue(!service.contains("clearUnread"))
    }

    @Test
    fun `轮次完成时先同步记下读取边界，再交给轮询器结算，不另发完成通知`() {
        val source = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/TerminalBridgeService.kt")
        val completed = source.bodyAfter("override fun turnCompleted(sessionId: String)", '{')
        val mark = completed.indexOf("val mark = tails.mark(tabId)")
        val launch = completed.indexOf("scope.launch(Dispatchers.IO) { poller.completed(tabId, mark) }")
        assertTrue("信号到达时就同步记下读取边界，不等进协程", mark >= 0)
        assertTrue("再交给轮询器按边界结算（顺序由 TabPollerTest 验证）", launch > mark)
        val router = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeRouter.kt")
        val onCompleted = router.bodyAfter("suspend fun onTurnCompleted(tabId: String, turn: Long,)", '{')
        assertTrue(
            "完成入口只发待发结果",
            onCompleted.contains("val reply = tab(tabId) { takeReply() }") && onCompleted.contains("sendReply(tabId, reply)")
        )
        assertTrue("还没读到回复时先等，不立即判定没有回复", onCompleted.contains("tab(tabId) { awaitReply(now()) }"))
        assertTrue("结算后还有后台任务时补一条提示", onCompleted.contains("sendBackgroundRunning(tabId)"))
        val ports = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/core/BridgePorts.kt").normalized
        assertTrue("文案接口不再有完成通知", !ports.contains("fun completed("))
    }

    @Test
    fun `中枢、机器人管理与项目观察都用隔离作用域：子任务互不牵连，异常只记日志`() {
        for (name in listOf("BridgeHub", "RobotManager", "TerminalBridgeService")) {
            val code = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/$name.kt").normalized
            assertTrue(name, code.contains("private val scope = isolatedScope(parentScope, LOG, "))
        }
        val scopes = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BridgeScopes.kt")
        val body = scopes.compact(scopes.bodyAfter("internal fun isolatedScope(", '('))
        assertTrue(body.contains("SupervisorJob(parent.coroutineContext[Job])+CoroutineExceptionHandler{_,e->log.warn("))
    }

    @Test
    fun `入站消息按机器人分队列处理，附件失败原因进日志`() {
        assertTrue(hub.contains("RobotInboundQueues("))
        assertTrue(hub.contains("inbound.offer(message)"))
        assertTrue(hub.contains("onWarning ="))
    }

    @Test
    fun `注册了桥接启动活动`() {
        assertTrue(pluginXml.contains("com.github.izerui.imux.bridge.BridgeStartupActivity"))
    }

    @Test
    fun `启动时调度一次恢复绑定对账`() {
        assertTrue(service.contains("invokeLater("))
        assertTrue(service.contains("onSessionsChanged()"))
    }
}
