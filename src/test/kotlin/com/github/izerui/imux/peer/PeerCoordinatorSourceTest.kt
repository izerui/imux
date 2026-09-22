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
        assertTrue(coordinator.contains("guard.tryStart(generation,"))
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
    fun `runReviewAndInject 源码中 resolveMcpConfig 到 buildPeerCliInvocation 到 runCli 的调用链`() {
        val reviewFun = coordinator.substringAfter("private suspend fun runReviewAndInject(").substringBefore("private fun ")
        assertTrue("应调用 resolveMcpConfig", reviewFun.contains("resolveMcpConfig(currentBinding.targetAgentType)"))
        assertTrue("应调用 buildPeerCliInvocation", reviewFun.contains("buildPeerCliInvocation(shell, currentBinding.targetAgentType, projectPath, mcpConfig)"))
        assertTrue("invocation.command 应赋给 command", reviewFun.contains("val command = invocation.command"))
        assertTrue("invocation.environment 应赋给 environment", reviewFun.contains("val environment = invocation.environment"))
        val runCliArgs = reviewFun.substringAfter("runCli(").substringBefore(") {")
        assertTrue("runCli 第二参数应是 command", runCliArgs.contains("currentBinding.targetAgentType, command,"))
        assertTrue("runCli 第五参数应是 environment", runCliArgs.contains("prompt, environment,"))
    }

    @Test
    fun `构造函数接受可注入的 resolveMcpConfig、edtDispatcher、peerMaxRounds 和 peerAutoInject`() {
        assertTrue(coordinator.contains("resolveMcpConfig: (AgentType) -> PeerMcpConfig"))
        assertTrue(coordinator.contains("edtDispatcher: kotlin.coroutines.CoroutineContext?"))
        assertTrue(coordinator.contains("peerMaxRounds: () -> Int"))
        assertTrue(coordinator.contains("peerAutoInject: () -> Boolean"))
    }

    @Test
    fun `取消和解绑通过 guard 清除状态`() {
        assertTrue(coordinator.contains("cancelledRun?.killProcess()"))
        assertTrue(coordinator.contains("cancelAndDetach()"))
    }

    @Test
    fun `会话迁移在 binding 条件分支前清理目标 key 的全部残留`() {
        val migrateFun = coordinator.substringAfter("fun migrateSessionKey(").substringBefore("fun ")
        val bindingBlock = migrateFun.substringBefore("if (binding != null)")
        assertTrue("迁移应无条件清理旧 key 的 peerInjectedSessions", bindingBlock.contains("peerInjectedSessions.remove(from)"))
        assertTrue("迁移应无条件清理目标 key 的 bindings", bindingBlock.contains("bindings.remove(to)"))
        assertTrue("迁移应无条件清理目标 key 的 peerInjectedSessions", bindingBlock.contains("peerInjectedSessions.remove(to)"))
        assertTrue("迁移应无条件清理目标 key 的 roundCounts", bindingBlock.contains("roundCounts.remove(to)"))
        assertTrue("迁移应无条件移除目标 key 的 guard", bindingBlock.contains("guards.remove(to)"))
    }

    @Test
    fun `onTurnCompleted 中轮次状态修改在 tryStart 回调内`() {
        val onTurnFun = coordinator.substringAfter("fun onTurnCompleted(").substringBefore("fun ")
        assertTrue("应检查 peerInjectedSessions.remove", onTurnFun.contains("peerInjectedSessions.remove(sessionKey)"))
        assertTrue("未标记时应重置计数", onTurnFun.contains("roundCounts[sessionKey]?.set(0)"))
        val tryStartBlock = onTurnFun.substringAfter("guard.tryStart(generation,").substringBefore("} ?: return")
        assertTrue(
            "peerInjectedSessions.remove 应在 tryStart 回调内",
            tryStartBlock.contains("peerInjectedSessions.remove(sessionKey)"),
        )
    }

    @Test
    fun `injectFeedback 含 incrementAndGet 而 runReviewAndInject 不含`() {
        val injectFun = coordinator.substringAfter("private fun injectFeedback(").substringBefore("private fun ")
        assertTrue("injectFeedback 应含 incrementAndGet", injectFun.contains("incrementAndGet()"))
        val reviewFun = coordinator.substringAfter("private suspend fun runReviewAndInject(").substringBefore("private fun ")
        assertFalse("runReviewAndInject 不应含 incrementAndGet", reviewFun.contains("incrementAndGet()"))
    }

    @Test
    fun `injectFeedback 中 send 在 incrementAndGet 和 peerInjectedSessions-add 之前`() {
        val injectFun = coordinator.substringAfter("private fun injectFeedback(").substringBefore("private fun ")
        val sendPos = injectFun.indexOf(".send(prompt)")
        val incrementPos = injectFun.indexOf("incrementAndGet()")
        val addPos = injectFun.indexOf("peerInjectedSessions.add(")
        assertTrue("send 应出现", sendPos >= 0)
        assertTrue("incrementAndGet 应出现", incrementPos >= 0)
        assertTrue("peerInjectedSessions.add 应出现", addPos >= 0)
        assertTrue("send 应在 incrementAndGet 之前", sendPos < incrementPos)
        assertTrue("send 应在 peerInjectedSessions.add 之前", sendPos < addPos)
    }

    @Test
    fun `injectFeedback 和 stageFeedback 直接发送反馈无前缀`() {
        val injectFun = coordinator.substringAfter("private fun injectFeedback(").substringBefore("private fun ")
        val stageFun = coordinator.substringAfter("private fun stageFeedback(").substringBefore("private fun ")
        assertFalse("injectFeedback 不应添加前缀", injectFun.contains("feedbackPrefix"))
        assertFalse("stageFeedback 不应添加前缀", stageFun.contains("feedbackPrefix"))
    }

    @Test
    fun `关闭自动发送只把反馈填入输入框`() {
        val reviewFun =
            coordinator.substringAfter("private suspend fun runReviewAndInject(").substringBefore("private fun ")
        val stageFun = coordinator.substringAfter("private fun stageFeedback(").substringBefore("private fun ")

        assertTrue("关闭自动发送应走暂存分支", reviewFun.contains("stageFeedback(mainSessionKey, feedback)"))
        assertTrue("暂存反馈应使用括号粘贴模式", stageFun.contains(".useBracketedPasteMode()"))
        assertTrue("暂存反馈应写入终端输入区", stageFun.contains(".send(prompt)"))
        assertFalse("暂存反馈不应执行发送", stageFun.contains(".shouldExecute()"))
    }
}
