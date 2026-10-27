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
    fun `开关关闭时不启动任何订阅与轮询`() {
        assertTrue(startup.contains("if (!BridgeSettings.getInstance().state.enabled) return"))
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
    fun `轮次完成时先读出最后的回复，再推本轮最终回复，不另发完成通知`() {
        val source = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/TerminalBridgeService.kt")
        val completed = source.bodyAfter("override fun turnCompleted(sessionId: String)", '{')
        val poll = completed.indexOf("pollTab(tabId)")
        val flush = completed.indexOf("hub.router.onTurnCompleted(tabId)")
        assertTrue("完成回调要先读出最后的回复", poll >= 0)
        assertTrue("再推本轮最终回复", flush > poll)
        val router = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeRouter.kt")
        assertTrue("完成入口只发待发结果", router.bodyAfter("suspend fun onTurnCompleted(tabId: String)", '{').contains("flushFinal(tabId)"))
        val ports = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/core/BridgePorts.kt").normalized
        assertTrue("文案接口不再有完成通知", !ports.contains("fun completed("))
    }

    @Test
    fun `尾随器按绑定代次与会话缓存，换绑或迁移后重建`() {
        assertTrue(service.contains("ConcurrentHashMap<String, TailEntry>()"))
        assertTrue(service.contains("reuseTail(cached.epoch, cached.sessionId, binding.epoch, binding.target.sessionId)"))
        assertTrue(service.contains("TailEntry(binding.epoch, sessionId, tail)"))
    }

    @Test
    fun `桥接协程与故障隔离`() {
        assertTrue(service.contains("SupervisorJob"))
        assertTrue(hub.contains("SupervisorJob"))
    }

    @Test
    fun `入站消息经单一消费者按到达顺序处理`() {
        assertTrue(hub.contains("Channel<InboundMessage>(Channel.UNLIMITED)"))
        assertTrue(hub.contains("for (message in inbound)"))
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
