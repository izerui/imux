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
    fun `同一会话用运行门避免副驾驶重入`() {
        assertTrue(coordinator.contains("activeRuns.putIfAbsent(sessionKey, run)"))
        assertTrue(coordinator.contains("activeRuns[sessionKey] === run"))
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
    fun `协调器绑定到项目生命周期`() {
        assertTrue(monitor.contains("Disposer.register(this, peerCoordinator)"))
        assertTrue(coordinator.contains("override fun dispose()"))
    }
}
