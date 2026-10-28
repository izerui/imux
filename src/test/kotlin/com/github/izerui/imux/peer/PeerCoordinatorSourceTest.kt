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
    fun `提示条与图标组由自有编辑器图层承载`() {
        assertFalse(coordinator.contains("setTopComponent("))
        assertTrue(editor.contains("setLayer(toolStrip.component, JLayeredPane.PALETTE_LAYER)"))
    }

    @Test
    fun `结对选项弹窗不显示重复标题`() {
        assertTrue(
            SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt")
                .compact(editor)
                .contains("createActionGroupPopup(null,group,"),
        )
    }

    @Test
    fun `审查中的提示给完整进度并能取消本轮`() {
        val refreshFun = editor.substringAfter("private fun refreshPeer()").substringBefore("private fun failedText")
        assertTrue(refreshFun.contains("if (progress != null) progressSummary(name, progress)"))
        assertTrue(refreshFun.contains("NoticeAction(ImuxBundle.message(\"action.peer.segment.cancelRun\")) { coordinator.cancelCurrentRun(key) }"))
    }

    @Test
    fun `结对编程按钮开着时显示伙伴名，审查中显示秒数，失败显示黄色`() {
        val pairTool = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/PairTool.kt").bodyAfter("internal fun pairToolState(", '{')
        assertTrue("审查中显示秒数", pairTool.contains("busyIcon, title, runningText, label = message(\"action.peer.tool.running\", listOf(model.elapsedSeconds)), tone = NoticeTone.INFO"))
        assertTrue("失败显示黄色短字", pairTool.contains("message(\"action.peer.tool.failed\", emptyList()), NoticeTone.WARNING"))
        assertTrue("空闲只是图标，但附上一轮结果", pairTool.contains("ToolState(AgentIcons.pair, title, withResult(message(\"action.peer.segment.hint\", emptyList())))"))
        assertTrue("结对中显示伙伴名", pairTool.contains("label = name, tone = NoticeTone.SUCCESS"))
        val refreshFun = editor.substringAfter("private fun refreshPeer()").substringBefore("private fun failedText")
        assertTrue("审查中复用缓存的旋转图标", refreshFun.contains("peerBusyIcon,"))
        assertTrue("旋转图标应缓存为字段", editor.contains("private val peerBusyIcon = AnimatedIcon.Default()"))
        assertFalse("refreshPeer 不应新建 AnimatedIcon", refreshFun.contains("AnimatedIcon.Default()"))
        assertTrue("伙伴名最后才收", editor.contains("ToolPill(pairTool, pinned = true)"))
    }

    @Test
    fun `新的审查结果提示一次，之后留在结果 chip，不以仍在结对为前提`() {
        val refreshFun = editor.substringAfter("private fun refreshPeer()").substringBefore("private fun failedText")
        assertTrue("按完成时间认新结果", refreshFun.contains("it.finishedAtMillis != announcedOutcomeAt"))
        assertTrue(refreshFun.contains("fresh != null -> pairNotices.flash(resultNotice(fresh))"))
        val chip = editor.substringAfter("private fun refreshResultChip()").substringBefore("private fun refreshTalk()")
        assertTrue("结果提示还在顶部时不重复显示", chip.contains("!noticeOverlay.isShowing(NoticeSource.PAIR)"))
        assertTrue(chip.contains(".lastOutcome(virtualFile.sessionKey)"))
        assertFalse("单次审查自动解绑后 status 为 null，chip 不能依赖它", chip.contains(".status("))
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
        assertTrue("暂存反馈应使用 require 括号粘贴模式", stageFun.contains("tryStagePeerFeedback("))
        assertTrue("终端消失时应走通用兜底", stageFun.contains("notifyFeedbackUndelivered"))
        assertTrue("终端未就绪时应走带复制的通知", stageFun.contains("notifyFeedbackNotReady"))
    }
}
