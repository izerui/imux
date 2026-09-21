package com.github.izerui.imux.peer

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerCoordinatorSourceTest {
    private val coordinator =
        SourceCode("src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt").normalized
    private val monitor =
        SourceCode("src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt").normalized
    private val host =
        SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt").normalized
    private val editor =
        SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt").normalized

    @Test
    fun `并发调度由 PeerSessionGuard 保护`() {
        assertTrue(coordinator.contains("guard.tryStart()"))
        assertTrue(coordinator.contains("guard.onFinished(run)"))
        assertTrue(coordinator.contains("guard.isActive(run)"))
    }

    @Test
    fun `会话迁移同步结对状态`() {
        assertTrue(monitor.contains("peerCoordinator.migrateSessionKey(from, to)"))
        assertTrue(coordinator.contains("fun migrateSessionKey("))
    }

    @Test
    fun `未落盘终端使用 sessionKey 作为身份`() {
        assertTrue(host.contains("internal fun sessionKeyFor(view: TerminalView)"))
        assertTrue(host.contains("?.sessionKey"))
        assertFalse(host.contains("file.sessionId ?: file.sessionKey"))
    }

    @Test
    fun `复制会话身份仍然只使用真实 id`() {
        assertTrue(host.contains("file.sessionId?.let { file.agentType to it }"))
    }

    @Test
    fun `横幅由自有编辑器布局承载`() {
        assertFalse(coordinator.contains("setTopComponent("))
        assertTrue(editor.contains("add(peerBanner, BorderLayout.NORTH)"))
    }

    @Test
    fun `横幅使用紧凑操作和较小纵向内边距`() {
        assertTrue(editor.contains("""ActionLink(ImuxBundle.message("action.peer.cancel"))"""))
        assertTrue(editor.contains("""ActionLink(ImuxBundle.message("action.peer.close"))"""))
        assertTrue(editor.contains("border = JBUI.Borders.empty(3, 12, 3, 24)"))
    }

    @Test
    fun `协调器绑定到项目生命周期`() {
        assertTrue(monitor.contains("Disposer.register(this, peerCoordinator)"))
        assertTrue(coordinator.contains("override fun dispose()"))
    }

    @Test
    fun `取消和解绑通过 guard 清除状态`() {
        assertTrue(coordinator.contains("guards[sessionKey]?.cancel()"))
        assertTrue(coordinator.contains("guards.remove(sessionKey)?.cancel()"))
    }

    @Test
    fun `只有用户主动消息重置轮次计数`() {
        assertTrue(coordinator.contains("peerInjectedSessions.remove(sessionKey)"))
        assertTrue(coordinator.contains("peerInjectedSessions.add(mainSessionKey)"))
    }

    @Test
    fun `轮次计数只在反馈实际注入时递增`() {
        val injectFun = coordinator.substringAfter("private fun injectFeedback(").substringBefore("private fun ")
        assertTrue("injectFeedback 应递增计数", injectFun.contains("incrementAndGet()"))
        val reviewFun =
            coordinator.substringAfter("private suspend fun runReviewAndInject(").substringBefore("private fun ")
        assertFalse("runReviewAndInject 不应递增计数", reviewFun.contains("incrementAndGet()"))
        assertTrue("审阅前只检查已注入轮数", reviewFun.contains(".get()"))
    }
}
