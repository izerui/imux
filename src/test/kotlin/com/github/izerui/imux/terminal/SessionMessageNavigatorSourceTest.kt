package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMessageNavigatorSourceTest {
    private val editor = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt")
    private val navigator = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/SessionMessageNavigator.kt")
    private val peer = SourceCode("src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt")
    private val monitor = SourceCode("src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt")

    @Test
    fun `副驾驶发送后登记位置并通知导航器刷新`() {
        val send = peer.bodyAfter("private suspend fun injectFeedback(", '{')
        val listener = navigator.bodyAfter("private suspend fun refreshAnchors()", '{')

        assertTrue("副驾驶应从终端模型取得当前位置", send.contains("outputModel.endOffset.toAbsolute()"))
        val accepted = send.indexOf("trySendPeerFeedback(view.createSendTextBuilder(), prompt)")
        val hint = send.indexOf("if (accepted) hint = PeerFeedbackHint(")
        assertTrue("只有终端受理后才登记定位信息", accepted >= 0 && hint > accepted)
        assertTrue("登记后应通知气泡刷新", send.contains("notifyStateChanged(mainSessionKey)"))
        assertTrue("导航器应复用项目副驾驶状态订阅", navigator.normalized.contains("peerCoordinator.addStateListener(this)"))
        assertTrue("只有新增定位信息才触发重扫", navigator.normalized.contains("latest === observedFeedbackHint"))
        assertTrue("等待终端正文异步到达后再补一次定位", navigator.normalized.contains("awaitingTerminalContent.set(true)"))
        assertTrue("普通消息和副驾驶锚点应合并", listener.contains("peerFeedbackAnchors("))
        assertTrue("定位过程中到来的新反馈不能被清掉", listener.contains("feedbackGeneration.get() != snapshot.feedbackGeneration"))
        assertTrue("最终应用合并后的锚点", listener.contains("applyAnchors(snapshot.editor, snapshot.outputModel, allAnchors)"))
    }

    @Test
    fun `副驾驶历史锚点在解绑后保留并随终端关闭清理`() {
        val unbind = peer.bodyAfter("private fun unbindInner(sessionKey: String)", '{')
        val closed = monitor.bodyAfter("fun sessionClosed(key: String)", '{')

        assertFalse("解绑不应删除历史气泡", unbind.contains("feedbackHints.remove(sessionKey)"))
        assertTrue("关终端统一结束审查生命周期", closed.contains("peerCoordinator.sessionClosed(key)"))
        val peerClosed = peer.bodyAfter("fun sessionClosed(sessionKey: String)", '{')
        assertTrue("关终端必须清理定位信息", peerClosed.contains("forgetFeedbackHints(sessionKey)"))
        assertTrue("会话身份切换必须清理旧定位信息", peer.normalized.contains("feedbackHints.remove(from)"))
        assertTrue("会话身份切换必须清理目标残留定位信息", peer.normalized.contains("feedbackHints.remove(to)"))
    }

    @Test
    fun `终端 Editor 切换时重新绑定消息导航并在释放时清理`() {
        assertTrue(
            editor.compactArgs(editor.normalized).contains(
                editor.compactArgs("messageNavigator.bind(editor)"),
            ),
        )
        assertTrue(
            editor.compactArgs(editor.normalized).contains(
                editor.compactArgs("Disposer.dispose(messageNavigator)"),
            ),
        )
    }

    @Test
    fun `Terminal active buffer 切换通过共享 ActiveModelDispatcher 调度`() {
        val collectBody = editor.bodyAfter("outputModels.active.collect", '{')
        assertTrue(
            "collect 应调用 activeModelDispatcher.emission()",
            collectBody.contains("activeModelDispatcher.emission()"),
        )
        assertFalse(
            "collect 不应直接调用 activeOutputModelChanged",
            collectBody.contains("activeOutputModelChanged"),
        )
        val compact = editor.compact(editor.normalized)
        assertTrue(
            "dispatcher 的 schedule 应通过 invokeLater + disposed 守卫",
            compact.contains(
                editor.compact("schedule = { runnable -> ApplicationManager.getApplication().invokeLater { if (!disposed && !project.isDisposed) runnable.run() } }"),
            ),
        )
        assertTrue(
            "dispatcher 的 activeModel 应读取当下 active.value",
            compact.contains(
                editor.compact("activeModel = { virtualFile.terminalView.outputModels.active.value }"),
            ),
        )
        assertTrue(
            "dispatcher 的 onActiveModel 应委托给导航器",
            compact.contains(
                editor.compact("onActiveModel = { messageNavigator.activeOutputModelChanged(it) }"),
            ),
        )
    }

    @Test
    fun `activeOutputModelChanged 守卫并委托给 OutputModelBinding`() {
        val body = navigator.bodyAfter("fun activeOutputModelChanged(model: TerminalOutputModel)", '{')
        assertTrue("释放后应跳过", body.contains("disposed"))
        assertTrue("未绑定 editor 时应跳过", body.contains("editor == null"))
        assertTrue("应委托 OutputModelBinding.activeModelChanged", body.contains("outputModelBinding.activeModelChanged(model)"))
    }

    @Test
    fun `activeModelChanged 包含短路和刷新请求`() {
        val body = navigator.bodyAfter("fun activeModelChanged(model: TerminalOutputModel): Boolean", '{')
        assertTrue("相同实例应短路", body.contains("model === observedModel"))
        assertTrue("应绑定新 model", body.contains("bind(model)"))
        assertTrue("应调用 onRefreshRequested", body.contains("onRefreshRequested()"))
    }

    @Test
    fun `bind 与 activeModelChanged 共用 OutputModelBinding 确保监听器注册路径一致`() {
        val bindBody = navigator.bodyAfter("fun bind(editor: Editor?)", '{')
        assertTrue("bind 应通过 outputModelBinding.bind 注册监听器", bindBody.contains("outputModelBinding.bind("))
        assertFalse("bind 不应直接调用 addListener", bindBody.contains(".addListener("))
    }

    @Test
    fun `OutputModelBinding 的 onRefreshRequested 回调设置 locateRequested 并安排刷新`() {
        assertTrue(
            "OutputModelBinding 构造时应注入 locateRequested + scheduleRefresh",
            navigator.compactArgs(navigator.normalized).contains(
                navigator.compactArgs("OutputModelBinding(outputModelListener) { locateRequested.set(true) scheduleRefresh() }"),
            ),
        )
    }

    @Test
    fun `解绑时通过 OutputModelBinding 清理`() {
        val body = navigator.bodyAfter("private fun unbind()", '{')
        assertTrue(body.contains("outputModelBinding.unbind()"))
    }

    @Test
    fun `终端内容只在导航等待消息落屏时触发一次重定位`() {
        val listener = navigator.bodyAfter("override fun afterContentChanged(event: TerminalContentChangeEvent)", '{')

        assertTrue(listener.contains("awaitingTerminalContent.compareAndSet(true, false)"))
        assertTrue(listener.contains("outputGeneration.incrementAndGet()"))
        assertTrue(listener.contains("locateRequested.set(true)"))
        assertTrue(listener.contains("scheduleRefresh()"))
        assertTrue(
            "必须监听官方 TerminalOutputModel，而不是重新监听 Editor Document",
            navigator.normalized.contains("TerminalOutputModelListener") &&
                    navigator.normalized.contains("model.addListener(disposable, listener)"),
        )
        assertFalse(navigator.normalized.contains("DocumentListener"))
    }

    @Test
    fun `运行态回调经过会话状态去重后再刷新`() {
        assertTrue(
            navigator.compactArgs(navigator.normalized).contains(
                navigator.compactArgs("addListener(this, ::sessionStateChanged)"),
            ),
        )
        val body = navigator.bodyAfter("private fun sessionStateChanged()", '{')
        assertTrue(body.contains("sessionChangeTracker.changed"))
        assertFalse("状态通知只唤醒增量索引，不能直接要求全文定位", body.contains("locateRequested"))
        assertTrue(body.contains("scheduleRefresh()"))
    }

    @Test
    fun `持续输出时刷新请求被合并而不取消排队任务`() {
        val body = navigator.bodyAfter("fun scheduleRefresh()", '{')

        assertTrue("文档变化先登记为待刷新", body.contains("refreshRequested.set(true)"))
        assertTrue("已有任务时复用它", body.contains("refreshJob?.isActive == true"))
        assertFalse("持续输出不能反复取消防抖任务", body.contains("refreshJob?.cancel()"))
        assertTrue("执行期间收到新变化后要继续刷新", body.contains("while (refreshRequested.get())"))
        assertTrue("首轮之后要降低整篇终端扫描频率", body.contains("CONTINUOUS_REFRESH_INTERVAL_MS"))
        assertTrue(navigator.normalized.contains("CONTINUOUS_REFRESH_INTERVAL_MS = 1_000L"))
    }

    @Test
    fun `transcript 语义未变化时跳过终端全文快照`() {
        val body = navigator.bodyAfter("private suspend fun refreshAnchors()", '{')
        val unchangedReturn = body.indexOf("if (!locateRequested.get()) return")
        val terminalSnapshot = body.indexOf("outputModel.takeSnapshot()")

        assertTrue("未变化分支必须存在", unchangedReturn >= 0)
        assertTrue("必须先返回再读取终端全文", terminalSnapshot > unchangedReturn)
        assertTrue(body.contains("transcriptIndex.refresh(snapshot.session)"))
    }

    @Test
    fun `定位期间持续输出时先应用锚点并安排纠正`() {
        val body = navigator.bodyAfter("private suspend fun refreshAnchors()", '{')
        val native = body.indexOf("stableAnchorsForNavigation(")
        val merge = body.indexOf("peerFeedbackAnchors(")
        val apply = body.indexOf("applyAnchors(snapshot.editor, snapshot.outputModel, allAnchors)")
        val retry = body.indexOf("if (changedDuringLocate) scheduleRefresh()")

        assertFalse("不能再因持续变化丢弃整轮结果", body.contains("modificationStamp"))
        assertTrue("必须先应用可用的绝对锚点", apply >= 0)
        assertTrue("应先计算原生锚点再合并副驾驶锚点", native >= 0 && native < merge && merge < apply)
        assertTrue("不再依赖终端文档变化监听触发纠正", retry < 0)
        assertFalse(body.contains("contentGeneration"))
    }

    @Test
    fun `释放导航器后不再补排刷新任务`() {
        val body = navigator.bodyAfter("override fun dispose()", '{')

        assertTrue(body.contains("disposed = true"))
        assertTrue(body.contains("refreshRequested.set(false)"))
    }

    @Test
    fun `锚点刷新同步处理已经打开的预览`() {
        val body = navigator.bodyAfter("private fun applyAnchors(", '{')
        assertTrue(body.contains("refreshOpenPreview()"))
        assertTrue(
            navigator
                .bodyAfter("private fun refreshOpenPreview()", '{')
                .contains("showPreview(refreshed)"),
        )
    }

    @Test
    fun `可视区域与轨道尺寸变化时重新校准预览位置`() {
        assertTrue(
            navigator
                .bodyAfter("fun viewportChanged()", '{')
                .contains("refreshOpenPreview()"),
        )
        val rail = navigator.bodyAfter("private inner class NavigatorRail : JComponent()", '{')
        assertTrue(rail.contains("componentMoved"))
        assertTrue(rail.contains("componentResized"))
    }

    @Test
    fun `编辑器失去选中状态时关闭预览`() {
        assertTrue(navigator.normalized.contains("FileEditorManagerListener.FILE_EDITOR_MANAGER"))
        val body = navigator.bodyAfter("override fun selectionChanged(event: FileEditorManagerEvent)", '{')
        assertTrue(body.contains("event.oldEditor === ownerEditor"))
        assertTrue(body.contains("hidePreview()"))
    }

    /** 划过要变大，就得在悬停目标变化时重绘；只更新弹窗不重绘，点还是原来那么大。 */
    @Test
    fun `悬停出现与消失都会重绘轨道`() {
        assertTrue(
            navigator
                .bodyAfter("private fun showPreview(anchor: UserMessageAnchor)", '{')
                .contains("repaint()"),
        )
        assertTrue(navigator.bodyAfter("private fun hidePreview()", '{').contains("repaint()"))
    }

    /**
     * 卡片带上助手回复是这次改动的全部意义：只列提问的话，用户得先回忆自己当时怎么问。
     * 断言卡片构建函数体里两段都用到了，而不是只渲染提问。
     */
    @Test
    fun `悬停卡片同时展示提问与回复`() {
        val body = navigator.bodyAfter("private fun updatePreviewCard(anchor: UserMessageAnchor)", '{')
        assertTrue(body.contains("anchor.userPreview"))
        assertTrue(body.contains("anchor.replyPreview"))
    }

    /**
     * 命中区是硬边界，鼠标在轨道边沿抖动时 contains 会反复跳变，Swing 跟着不停发
     * exited/entered。立刻关弹窗就会看到来回闪。改成排一次延迟隐藏，抖回来就撤销。
     */
    @Test
    fun `鼠标离开轨道后延迟隐藏而不是立刻关`() {
        // mouseExited 是表达式体，没有花括号，不能用 bodyAfter 定位——它会顺延到下一个
        // 匿名对象体上，而那里面恰好也有 scheduleHide()，断言就会假通过。整行精确比对。
        assertTrue(
            navigator.compactArgs(navigator.normalized).contains(
                navigator.compactArgs("override fun mouseExited(event: MouseEvent) = scheduleHide()"),
            ),
        )
        val body = navigator.bodyAfter("private fun scheduleHide()", '{')
        assertTrue("延迟隐藏要走平台 Alarm，绑在导航器的生命周期上", body.contains("addRequest"))
        assertTrue(body.contains("HIDE_DELAY_MS"))
    }

    @Test
    fun `回到轨道上会撤销待执行的隐藏`() {
        assertTrue(
            navigator
                .bodyAfter("private fun showPreview(anchor: UserMessageAnchor)", '{')
                .contains("cancelScheduledHide()"),
        )
    }

    /**
     * 绘制、命中和点击跳转都拿锚点 offset 去问 Editor，而终端裁剪历史后文档会变短——
     * 旧 offset 越界，`getLineNumber` 直接把 `Wrong offset` 抛到 EDT 弹报错框（实测过）。
     * 三条路径都必须先过 [validOffset]。
     */
    @Test
    fun `绘制与点击都先把绝对锚点换算到当前窗口`() {
        assertTrue(
            "绘制与命中共用的 anchorPoints 要按当前 Terminal 起点换算",
            navigator.bodyAfter("private fun anchorPoints(): List<AnchorPoint>", '{').contains("relativeOffset"),
        )
        assertTrue(
            "点击跳转前也要按当前 Terminal 起点换算",
            navigator.bodyAfter("override fun mouseClicked(event: MouseEvent)", '{').contains("relativeOffset"),
        )
    }

    @Test
    fun `定位读取平台终端快照并保存绝对起点`() {
        val body = navigator.bodyAfter("private suspend fun refreshAnchors()", '{')

        assertTrue(body.contains("outputModel.takeSnapshot()"))
        assertTrue(body.contains("outputSnapshot.endOffset - NAVIGATION_SCAN_MAX_CHARS.toLong()"))
        assertTrue(body.contains("scanStart.toAbsolute()"))
        assertTrue(body.contains("preferredRanges = preferredRanges"))
        assertTrue(
            "终端快照与高亮必须在同一个读锁里取，否则偏移错位",
            body.contains("snapshot.outputModel.takeSnapshot() to highlightSpansViaReflection(snapshot.outputModel)"),
        )
        assertTrue(body.contains("documentStartAbsolute = outputSnapshot.startOffset.toAbsolute()"))
        assertTrue(body.contains("outputModels.active.value !== snapshot.outputModel"))
        assertTrue(navigator.normalized.contains("NAVIGATION_SCAN_MAX_CHARS = 256_000"))
        assertTrue(body.contains("outputGeneration.get() != snapshot.outputGeneration"))
        assertTrue(body.contains("awaitingTerminalContent.set("))
        assertTrue(body.contains("waitForTerminalContent(transcript.exchanges, transcript.changed, latestResolved, outputChangedDuringLocate)"))
        assertTrue(body.contains("stableAnchorsForNavigation"))
        assertTrue(body.contains("if (outputChangedDuringLocate) scheduleRefresh()"))
    }

    /**
     * 点击时鼠标还停在那颗点上，气泡该原样留着。关掉它，就只能等鼠标微动再弹回来——
     * 那正是一次「消失再显示」的闪。收起交给移开轨道时的延迟隐藏。
     */
    @Test
    fun `点击跳转不关闭气泡`() {
        assertFalse(navigator.bodyAfter("override fun mouseClicked(event: MouseEvent)", '{').contains("hidePreview()"))
    }

    @Test
    fun `解绑与释放都会关闭悬停弹窗`() {
        assertTrue(navigator.bodyAfter("private fun unbind()", '{').contains("hidePreview()"))
        assertTrue(navigator.bodyAfter("override fun dispose()", '{').contains("hidePreview()"))
    }

    /**
     * 悬停那根和当前视口里的横线放大到**同一长度**：点击跳转后鼠标通常还停在横线上，
     * 两种强调尺寸不同的话，鼠标一移开横线就会凭空缩一下。
     */
    @Test
    fun `横线悬停与当前视口放大到同一长度`() {
        val body = navigator.bodyAfter("private fun anchorPoints(): List<AnchorPoint>", '{')
        assertTrue("悬停要参与强调判定", body.contains("anchor == previewAnchor"))
        assertTrue("当前视口也要参与强调判定", body.contains("firstVisibleLine"))
        assertTrue("视口判定必须使用可视行，覆盖软换行", body.contains("xyToVisualPosition"))
        assertTrue("两种强调共用一个长度", body.contains("JBUI.scale(ACTIVE_TICK_LENGTH)"))
        assertTrue("常态长度按字数分档", body.contains("tickLength("))
        assertTrue(navigator.normalized.contains("ACTIVE_TICK_LENGTH = 20"))
        assertTrue("强调要比最长的常态横线明显更长", navigator.normalized.contains("LONG_TICK_LENGTH = 14"))
    }

    @Test
    fun `横线按条目序号等距排列而不是按文档位置`() {
        val body = navigator.bodyAfter("private fun anchorPoints(): List<AnchorPoint>", '{')
        assertTrue(body.contains("tickCenters("))
        assertTrue("视口强调仍按 offset 映射到可视行", body.contains("offsetToVisualPosition(offset).line"))
        assertTrue("视觉行缓存短暂不同步时应跳过本帧", body.contains("catch (_: IndexOutOfBoundsException)"))
        assertFalse("不再按文档行数等比例分布", navigator.normalized.contains("markerY("))
    }

    @Test
    fun `用户消息使用右侧浮层横线并支持点击滚动与落点高亮`() {
        assertTrue(navigator.normalized.contains("scrollingModel.scrollTo"))
        assertTrue(editor.normalized.contains("setLayer(navigatorComponent, JLayeredPane.PALETTE_LAYER)"))
        assertTrue(editor.normalized.contains("setLayer(navigatorPreview, JLayeredPane.POPUP_LAYER)"))
        val click = navigator.bodyAfter("override fun mouseClicked(event: MouseEvent)", '{')
        assertTrue("点击后高亮落点", click.contains("flashLanding(currentEditor, anchor, offset, outputStart)"))
        assertFalse("等距排列后不再有同位置循环点击", navigator.normalized.contains("nextCollocatedAnchor"))
    }

    /**
     * 卡片是终端层里的普通组件，换横线只挪位置、换文字：Balloon 换点只能重建，会闪，
     * 而且 hide-on-click-outside 会吞掉第一下点击。
     */
    @Test
    fun `悬停卡片是终端层组件而不是 Balloon`() {
        assertFalse(navigator.normalized.contains("createBalloonBuilder"))
        assertFalse(navigator.normalized.contains("import com.intellij.openapi.ui.popup.Balloon"))
        val body = navigator.bodyAfter("private fun showPreview(anchor: UserMessageAnchor)", '{')
        assertTrue("卡片纵向对齐横线并收进可视区", body.contains("previewCardBounds("))
        assertTrue("停在同一根横线上不重摆卡片", body.contains("!previewNeedsRebuild"))
        assertTrue(body.contains("previewCard.isVisible"))
    }

    /**
     * 整列盖在终端上层，命中区放多大就吃掉终端多少鼠标事件。只认横线本身，
     * 横线之间、左侧空白的点击、拖选、滚轮都要落到终端。
     */
    @Test
    fun `命中区只认横线本身`() {
        assertTrue(
            navigator.compactArgs(navigator.normalized).contains(
                navigator.compactArgs("override fun contains(x: Int, y: Int): Boolean = hitPoint(x, y) != null"),
            ),
        )
        val body = navigator.bodyAfter("private fun hitPoint(x: Int, y: Int): AnchorPoint?", '{')
        assertTrue(body.contains("tickHitIndex("))
        assertTrue(body.contains("TICK_HIT_SLACK"))
        assertTrue(body.contains("TICK_HIT_BAND"))
    }

    @Test
    fun `落点高亮会自动撤掉并在解绑时清理`() {
        val flash = navigator.bodyAfter("private fun flashLanding(currentEditor: Editor, anchor: UserMessageAnchor, start: Int, outputStart: Long)", '{')
        assertTrue(flash.contains("clearLanding()"))
        assertTrue(flash.contains("LANDING_HIGHLIGHT_MS"))
        assertTrue(navigator.bodyAfter("private fun unbind()", '{').contains("clearLanding()"))
    }
}
