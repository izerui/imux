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
    fun `横幅结对选项弹窗不显示重复标题`() {
        assertTrue(
            SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt")
                .compact(editor)
                .contains("createActionGroupPopup(null,group,"),
        )
    }

    @Test
    fun `横幅使用紧凑操作和较小纵向内边距`() {
        assertTrue(editor.contains("""ActionLink(ImuxBundle.message("action.peer.cancel"))"""))
        assertTrue(editor.contains("""ActionLink(ImuxBundle.message("action.peer.close"))"""))
        assertTrue(editor.contains("border = JBUI.Borders.empty(3, 12, 3, 24)"))
    }

    @Test
    fun `横幅三种状态使用正确的图标`() {
        val refreshFun = editor.substringAfter("fun refreshPeerBanner()").substringBefore("private fun progressSummary")
        val nullBranch = refreshFun.substringAfter("if (status == null)").substringBefore("} else {")
        val boundBranch = refreshFun.substringAfter("} else {").substringBefore("peerBanner.revalidate()")

        assertTrue("未绑定时应显示 ProfileCPU 图标", nullBranch.contains("peerIcon.icon = AllIcons.Actions.ProfileCPU"))
        assertFalse("未绑定分支不应出现旋转图标", nullBranch.contains("peerBusyIcon"))

        assertTrue("运行中应复用缓存的旋转图标", boundBranch.contains("if (status.running) peerBusyIcon"))
        assertTrue("就绪时应显示结对验证图标", boundBranch.contains("AllIcons.CodeWithMe.CwmVerified"))

        assertTrue("旋转图标应缓存为字段", editor.contains("private val peerBusyIcon = AnimatedIcon.Default()"))
        assertFalse("refreshPeerBanner 不应新建 AnimatedIcon", refreshFun.contains("AnimatedIcon.Default()"))
    }

    @Test
    fun `横幅在 PASS 时显示 action-peer-passed 文案`() {
        val editorCompact = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt")
            .compact(editor)
        assertTrue(
            "lastReviewPassed 分支应切到 action.peer.passed 文案",
            editorCompact.contains(
                """elseif(status.lastReviewPassed){ImuxBundle.message("action.peer.passed",status.targetAgentType.displayName)}""",
            ),
        )
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
        val injectFun = coordinator.substringAfter("private suspend fun injectFeedback(").substringBefore("private fun ")
        assertTrue("injectFeedback 应含 incrementAndGet", injectFun.contains("incrementAndGet()"))
        val reviewFun = coordinator.substringAfter("private suspend fun runReviewAndInject(").substringBefore("private fun ")
        assertFalse("runReviewAndInject 不应含 incrementAndGet", reviewFun.contains("incrementAndGet()"))
    }

    @Test
    fun `injectFeedback 中 send 在 incrementAndGet 和 peerInjectedSessions-add 之前`() {
        val injectFun = coordinator.substringAfter("private suspend fun injectFeedback(").substringBefore("private fun ")
        val sendPos = injectFun.indexOf("trySendPeerFeedback(view.createSendTextBuilder(), prompt)")
        val incrementPos = injectFun.indexOf("incrementAndGet()")
        val addPos = injectFun.indexOf("peerInjectedSessions.add(")
        assertTrue("send 应出现", sendPos >= 0)
        assertTrue("incrementAndGet 应出现", incrementPos >= 0)
        assertTrue("peerInjectedSessions.add 应出现", addPos >= 0)
        assertTrue("send 应在 incrementAndGet 之前", sendPos < incrementPos)
        assertTrue("send 应在 peerInjectedSessions.add 之前", sendPos < addPos)
    }

    /**
     * 两条发送路径（生产走 viewOf，测试走 sendAutoFeedback 注入点）必须都在重试循环**体内**。
     * 曾经把循环只套在 sendAutoFeedback 分支上，结果真实路径只尝试一次，
     * 终端那一刻没开括号粘贴就直接丢反馈——而重试测试全都跑在注入点上，照样通过。
     *
     * 断言必须切出循环体再看：只比较「调用写在 for 之后」的话，把循环改成空循环、
     * 两个分支挪到循环后面，这条测试照样绿。
     */
    @Test
    fun `自动反馈要求括号粘贴模式且两条发送路径都在重试循环体内`() {
        val peer = SourceCode("src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt")
        // bodyAfter 在锚点出现不止一次时直接失败，「只应存在一个重试循环」由它一并守住。
        val loopBody = peer.bodyAfter("while (true)", '{')
        assertTrue(
            "终端发送应在重试循环体内",
            loopBody.contains("trySendPeerFeedback(view.createSendTextBuilder(), prompt)"),
        )
        assertTrue("测试注入点也应在同一个重试循环体内", loopBody.contains("sendAutoFeedback.invoke("))

        val injectFun = coordinator.substringAfter("private suspend fun injectFeedback(").substringBefore("private fun ")
        val sendPos = injectFun.indexOf("trySendPeerFeedback(view.createSendTextBuilder(), prompt)")
        val countPos = injectFun.indexOf("incrementAndGet()")
        assertTrue("发送应出现", sendPos >= 0)
        assertTrue("发送结果应在更新计数前确认", countPos > sendPos)
        assertTrue(
            "发送函数应要求括号粘贴模式并如实返回结果",
            peer.compact(coordinator).contains("builder.requireBracketedPasteMode().shouldExecute().trySend(prompt)"),
        )
    }

    /**
     * 等待没有时限，只认逻辑过期：本轮作废就静默退出，终端没了才交给兜底。
     * 轮次计数必须留在循环之外——没送出去的反馈不该占掉用户的一轮审查额度。
     */
    @Test
    fun `终端消失时交给兜底且未送达不登记轮次`() {
        val peer = SourceCode("src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt")
        val loopBody = peer.bodyAfter("while (true)", '{')
        assertTrue("终端消失时应交给兜底", loopBody.contains("onFeedbackUndelivered"))
        assertFalse("未送达时不得登记轮次", loopBody.contains("incrementAndGet()"))
        assertFalse("未送达时不得标记已注入", loopBody.contains("peerInjectedSessions.add("))
        assertFalse("不应再有重试次数上限", coordinator.contains("SEND_READY_ATTEMPTS"))
        assertFalse("兜底不应动剪贴板", coordinator.contains("copyTextToClipboard("))
        assertTrue("兜底应通知用户", coordinator.contains("action.peer.notification.undelivered"))
    }

    @Test
    fun `injectFeedback 和 stageFeedback 直接发送反馈无前缀`() {
        val injectFun = coordinator.substringAfter("private suspend fun injectFeedback(").substringBefore("private fun ")
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
