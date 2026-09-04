package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMessageNavigatorSourceTest {
    private val editor = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt")
    private val navigator = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/SessionMessageNavigator.kt")

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
    fun `Terminal active buffer 切换不进入消息导航刷新链路`() {
        assertFalse(editor.normalized.contains("messageNavigator.outputModelChanged()"))
        assertFalse(editor.normalized.contains("outputModelChanged = true"))
        assertFalse(navigator.normalized.contains("fun outputModelChanged()"))
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
                navigator.normalized.contains("outputModels.active.value.addListener"),
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
        val apply = body.indexOf("applyAnchors(snapshot.editor, snapshot.outputModel, stableAnchors)")
        val retry = body.indexOf("if (changedDuringLocate) scheduleRefresh()")

        assertFalse("不能再因持续变化丢弃整轮结果", body.contains("modificationStamp"))
        assertTrue("必须先应用可用的绝对锚点", apply >= 0)
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

    /**
     * 刻度是时间线上的点：常态一颗小点，鼠标划过的那颗和当前视口所在的那颗放大到**同一尺寸**。
     *
     * 两者必须一样大：点击跳转后鼠标通常还停在那颗点上，两个尺寸不同的话，鼠标一移开
     * 点就会凭空缩一下。
     */
    @Test
    fun `刻度画成圆点且悬停与当前视口放大到同一尺寸`() {
        val body = navigator.bodyAfter("override fun paintComponent(graphics: Graphics)", '{')
        assertTrue(body.contains("fillOval"))
        assertTrue("悬停要参与强调判定", body.contains("previewAnchor"))
        assertTrue("当前视口也要参与强调判定", body.contains("firstVisibleLine"))
        assertTrue("视口判定必须使用可视行，覆盖软换行", body.contains("xyToVisualPosition"))
        assertTrue("两种强调共用一个半径", body.contains("ACTIVE_MARK_RADIUS"))
        assertFalse("不该再有第二档强调半径", navigator.normalized.contains("HOVER_MARK_RADIUS"))
        assertTrue("常态与强调两档要不同，否则放大看不出来", MARK_RADIUS != ACTIVE_MARK_RADIUS)
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

    @Test
    fun `用户消息使用右侧浮层轨道并支持点击滚动`() {
        assertTrue(navigator.normalized.contains("scrollingModel.scrollTo"))
        assertTrue(editor.normalized.contains("setLayer(navigatorComponent, JLayeredPane.PALETTE_LAYER)"))
    }

    @Test
    fun `同坐标标记通过重复点击循环选择`() {
        val body = navigator.bodyAfter("override fun mouseClicked(event: MouseEvent)", '{')
        assertTrue(body.contains("nearestAnchorGroup"))
        assertTrue(body.contains("nextCollocatedAnchor"))
    }

    /**
     * 预览是一张对话气泡：平台 Balloon 自带指向锚点的尾巴，位置和形状都不用自己算。
     * 尾巴必须开着，否则就退化成一个浮在旁边、看不出属于哪颗点的方框。
     */
    @Test
    fun `悬停预览是带尾巴的气泡且不抢终端焦点`() {
        val compact = navigator.compactArgs(navigator.normalized)
        assertTrue(compact.contains("createBalloonBuilder"))
        assertTrue("尾巴要指向圆点", compact.contains(navigator.compactArgs("setShowCallout(true)")))
        assertTrue("气泡开在圆点左侧", compact.contains("Balloon.Position.atLeft"))
        assertTrue(compact.contains(navigator.compactArgs("setRequestFocus(false)")))
    }

    /** 气泡挪不了位置，换点只能重建；带上淡入淡出就是一次可见的闪，动画必须关掉。 */
    @Test
    fun `切换刻度时不带动画重建气泡`() {
        assertTrue(
            navigator.compactArgs(navigator.normalized).contains(
                navigator.compactArgs("setAnimationCycle(0)"),
            ),
        )
    }

    /** 尾巴要对准圆点，锚点就得是圆点的坐标，不能跟着鼠标走。 */
    @Test
    fun `气泡锚在圆点上而不是鼠标位置`() {
        val body = navigator.bodyAfter("fun previewPointFor(anchor: UserMessageAnchor): RelativePoint?", '{')
        assertTrue(body.contains("anchorY"))
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

    /** 鼠标在同一坐标的刻度上微动会连发 mouseMoved，锚点和屏幕坐标都没变时不能重建。 */
    @Test
    fun `停在同一条刻度上不重建弹窗`() {
        val body = navigator.bodyAfter("private fun showPreview(anchor: UserMessageAnchor)", '{')
        assertTrue(body.contains("!previewNeedsRebuild"))
        assertTrue(body.contains("previewBalloon?.isDisposed == false"))
    }

    /**
     * 命中区就贴着圆点四周留一点余量，横竖都收窄——轨道盖在终端上层，放大命中面积
     * 就是在吃终端的鼠标事件。边沿抖动导致的闪烁不靠放大面积解决，交给延迟隐藏。
     */
    @Test
    fun `命中区贴着圆点四周留出少量余量`() {
        val body = navigator.compactArgs(navigator.bodyAfter("override fun contains(x: Int, y: Int): Boolean", '{'))
        assertTrue("横向要判定", body.contains(navigator.compactArgs("x - width / 2")))
        assertTrue("纵向也要判定，不能整条轨道都能触发", body.contains("nearestAnchorDistance"))
        assertTrue(body.contains("MARK_HIT_SLACK"))
    }

    /**
     * 气泡开着的时候点圆点，第一下会被 hide-on-click-outside 拿去关气泡，轨道根本收不到，
     * 于是要点两下才跳转。关掉这个行为，点击直接落到轨道上，跳转后自己收起。
     */
    @Test
    fun `气泡不吞掉轨道上的点击`() {
        assertTrue(
            navigator.compactArgs(navigator.normalized).contains(
                navigator.compactArgs("setHideOnClickOutside(false)"),
            ),
        )
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
    fun `圆点位置按编辑器可视行而不是逻辑行计算`() {
        val body = navigator.bodyAfter("private fun anchorPoints(): List<AnchorPoint>", '{')

        assertTrue("圆点应按 offset 映射到可视行", body.contains("offsetToVisualPosition(offset).line"))
        assertTrue("总高度应按文档末尾的可视行数计算", body.contains("offsetToVisualPosition(document.textLength).line"))
        assertFalse("不能再用逻辑行号决定圆点位置", body.contains("document.getLineNumber(offset)"))
    }

    @Test
    fun `定位读取平台终端快照并保存绝对起点`() {
        val body = navigator.bodyAfter("private suspend fun refreshAnchors()", '{')

        assertTrue(body.contains("outputModel.takeSnapshot()"))
        assertTrue(body.contains("outputSnapshot.endOffset - NAVIGATION_SCAN_MAX_CHARS.toLong()"))
        assertTrue(body.contains("scanStart.toAbsolute()"))
        assertTrue(body.contains("preferredRanges = preferredRanges"))
        assertTrue(body.contains("highlightingRangesViaReflection"))
        assertTrue(body.contains("outputModels.active.value !== snapshot.outputModel"))
        assertTrue(navigator.normalized.contains("NAVIGATION_SCAN_MAX_CHARS = 256_000"))
        assertTrue(body.contains("outputGeneration.get() != snapshot.outputGeneration"))
        assertTrue(body.contains("awaitingTerminalContent.set(!outputChangedDuringLocate"))
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
}
