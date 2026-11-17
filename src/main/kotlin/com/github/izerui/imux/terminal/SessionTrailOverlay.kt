package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.overview.LoadingRow
import com.github.izerui.imux.overview.ProgressOverviewService
import com.github.izerui.imux.overview.TrailState
import com.github.izerui.imux.overview.buildErrorPanel
import com.github.izerui.imux.overview.OverviewResult
import com.github.izerui.imux.overview.report.ReportWrite
import com.github.izerui.imux.overview.report.ShownReport
import com.github.izerui.imux.overview.report.needsReload
import com.github.izerui.imux.overview.buildFailedBanner
import com.github.izerui.imux.overview.buildProgressBanner
import com.github.izerui.imux.overview.buildSaveFailedBanner
import com.github.izerui.imux.overview.buildStaleBanner
import com.github.izerui.imux.overview.buildHintPanel
import com.github.izerui.imux.settings.ImuxSettings
import com.github.izerui.imux.terminal.report.ReportView
import com.github.izerui.imux.terminal.report.ReportViews
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionButtonLook
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 会话轨迹：入口是终端右上角浮动图标组里的一个按钮（[tool]），展开后是图标组下方一张可滚动、可调整大小的卡片。
 * 生成失败时在顶部发一条常驻提示，重新生成完成时发一条一次性提示（[notices]）。
 *
 * 卡片由 [AgentTerminalFileEditor] 放进终端上方的 `PALETTE_LAYER`，只遮住自身范围，
 * 不改变终端尺寸，TUI 不会重新排版。这里只负责显示：状态与生成任务在
 * [com.github.izerui.imux.overview.TrailStateStore]，展开状态在 [AgentTerminalVirtualFile.trailExpanded]，
 * 所以拖动标签页重建编辑器后内容不丢，同一会话的多个编辑器实例看到的也是同一份内容。
 */
internal class SessionTrailOverlay(
    private val project: Project,
    private val virtualFile: AgentTerminalVirtualFile,
    parent: Disposable,
    private val focusTerminal: () -> Unit,
    private val relayout: () -> Unit,
    private val notices: NoticeSink,
) : Disposable {
    private val store get() = ProgressOverviewService.getInstance(project).trailStore
    private val key get() = virtualFile.sessionKey

    private var disposed = false
    private var shownExpanded = virtualFile.trailExpanded
    private var renderedKey: String? = null
    private var renderedState: TrailState? = null
    private var loadingRow: LoadingRow? = null
    private val loadingTimer = Timer(1_000) { updateLoadingRow() }
    private val monitorAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    /** 拖拽中的临时尺寸；松手后写入 [TrailSizeSetting]。 */
    private var dragSize: java.awt.Dimension? = null
    private var availableWidth = 0
    private var availableHeight = 0

    /** 上次刷新时是否在生成，用来判断“刚生成完”。初值 null 表示还没刷新过，打开标签页时不发提示。 */
    private var wasLoading: Boolean? = null

    /**
     * 浮动图标组里的会话轨迹按钮：生成中转圈并显示「生成中」，失败显示黄色短字；过期提示只在面板内显示。
     */
    val tool: SessionTool =
        object : SessionTool {
            override fun state(): ToolState {
                val state = store.state(key)
                val title = ImuxBundle.message("action.overview.text")
                return when {
                    state is TrailState.Unavailable ->
                        ToolState(AgentIcons.trail, title, ImuxBundle.message("action.overview.pending"), enabled = false)
                    state is TrailState.Loading -> {
                        val text = ImuxBundle.message("action.overview.status.loading")
                        ToolState(AnimatedIcon.Default.INSTANCE, title, text, label = text, tone = NoticeTone.INFO, selected = virtualFile.trailExpanded)
                    }
                    state is TrailState.Failed -> {
                        val text = ImuxBundle.message("action.overview.status.failed")
                        ToolState(AgentIcons.trail, title, text, label = text, tone = NoticeTone.WARNING, selected = virtualFile.trailExpanded)
                    }
                    else ->
                        ToolState(AgentIcons.trail, title, ImuxBundle.message("action.overview.toggle.description"), selected = virtualFile.trailExpanded)
                }
            }

            override fun perform(anchor: JComponent) {
                if (store.state(key) !is TrailState.Unavailable) setExpanded(!virtualFile.trailExpanded)
            }
        }

    private val titleLabel = JBLabel().apply { font = JBUI.Fonts.label().asBold() }
    private val collapseAction =
        object : DumbAwareAction(AllIcons.General.HideToolWindow) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(event: AnActionEvent) {
                event.presentation.text = ImuxBundle.message("action.overview.collapse")
            }

            override fun actionPerformed(event: AnActionEvent) = setExpanded(false)
        }

    /**
     * 不用 InplaceButton：它在鼠标刚移入时会忽略点击（TimedDeadzone），移过去马上点就没反应；
     * 点击区域也只有图标那么大。ActionButton 与折叠图标一致，22px 点击区、有悬停反馈。
     */
    private val collapseButton =
        ActionButton(
            collapseAction,
            collapseAction.templatePresentation.clone(),
            CHIP_ACTION_PLACE,
            ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE,
        ).apply {
            setLook(ActionButtonLook.SYSTEM_LOOK)
            isFocusable = false
        }
    private val body = JPanel(BorderLayout()).apply { isOpaque = false }

    /** 显示报告页的视图，第一次需要时创建（见 [reportView]）。 */
    private var reportView: ReportView? = null

    /** 视图上正在显示的报告（文件与内容摘要）：换了文件或内容不同才重新加载（见 [needsReload]）。写入失败时为 null。 */
    private var shownReport: ShownReport? = null

    /** 当前报告页顶部的横幅区：写入失败横幅加在这里。不显示报告页时为 null。 */
    private var reportBanners: JPanel? = null

    /** 点了「在浏览器中打开」，等报告写好就打开。 */
    private var pendingOpen = false

    /** 当前显示着的写入失败横幅；同一时刻只留一条。 */
    private var writeFailedBanner: JComponent? = null

    private val updateAction =
        object : DumbAwareAction(AllIcons.Actions.Refresh) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(event: AnActionEvent) {
                event.presentation.text = ImuxBundle.message("action.overview.report.update")
                event.presentation.isEnabled = store.state(key) !is TrailState.Loading
            }

            override fun actionPerformed(event: AnActionEvent) = store.generate(key, virtualFile.tabId)
        }

    private val openAction =
        object : DumbAwareAction(AllIcons.Actions.MoveTo2) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(event: AnActionEvent) {
                event.presentation.text = ImuxBundle.message("action.overview.report.openInBrowser")
            }

            override fun actionPerformed(event: AnActionEvent) = openReportInBrowser()
        }

    /** 标题栏上的「更新报告」「在系统浏览器中打开」：有报告页时才显示。 */
    private val reportButtons = listOf(updateAction, openAction).map { action ->
        ActionButton(action, action.templatePresentation.clone(), CHIP_ACTION_PLACE, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE).apply {
            setLook(ActionButtonLook.SYSTEM_LOOK)
            isFocusable = false
            isVisible = false
        }
    }

    val card: JComponent = TrailSurface(showGrip = true).apply {
        layout = BorderLayout()
        // 左侧、底部的留白就是拖拽热区：落在卡片自身而不是子组件上，鼠标事件才会交给卡片
        border = trailCardBorder()
        add(trailCardHeader(titleLabel, collapseButton, reportButtons), BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        isVisible = false
        val escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(escape, "imux.collapseTrail")
        actionMap.put("imux.collapseTrail", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = setExpanded(false)
        })
        ResizeHandler().let {
            addMouseListener(it)
            addMouseMotionListener(it)
        }
    }

    init {
        Disposer.register(parent, this)
        val removeListener = store.addListener { changed -> if (changed == key) refresh() }
        Disposer.register(this) { removeListener() }
        TerminalHost.getInstance(project).addSessionKeyMigratedListener(this) { _, to ->
            if (to == key) refresh()
        }
        // 会话有新交互时摘要会过期；输出期间事件很密，合并成每秒最多一次
        SessionMonitor.getInstance(project).addListener(this) {
            if (monitorAlarm.isEmpty) monitorAlarm.addRequest(::refresh, MONITOR_REFRESH_MS)
        }
        // 这个会话的报告写好了：不论是哪个面板请求的，显示着它的面板都更新（同一会话可能在几个分屏里）
        val removeReportListener = ProgressOverviewService.getInstance(project).reportWrites.addListener { sessionKey, write ->
            ApplicationManager.getApplication().invokeLater({ onReportWritten(sessionKey, write) }, ModalityState.any())
        }
        Disposer.register(this) { removeReportListener() }
        // 报告页的深浅色跟随 IDE 主题
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { reportView?.setDark(!JBColor.isBright()) })
        // 切换语言后重新渲染：报告页的界面文字随之切换（正文要等下次更新报告）
        ImuxSettings.getInstance().addLanguageListener(this) {
            renderedState = null
            refresh()
        }
        refresh()
    }

    /**
     * 由编辑器在 `doLayout` 中调用；[rightReserve] 是提问导航横线列连同右侧留白的宽度，
     * [topOffset] 是顶部提示条与浮动图标组占去的高度。
     */
    fun layout(width: Int, height: Int, rightReserve: Int, topOffset: Int) {
        val metrics = trailMetrics()
        val expanded = virtualFile.trailExpanded
        card.isVisible = expanded
        availableWidth = (width - rightReserve - metrics.margin * 2).coerceAtLeast(0)
        availableHeight = (height - topOffset - metrics.margin * 2).coerceAtLeast(0)
        val size = dragSize
        var desiredWidth = size?.width ?: JBUI.scale(TrailSizeSetting.width())
        var desiredHeight = size?.height ?: desiredCardHeight()
        // 报告页没拖过尺寸时按比例自适应；拖拽中、拖过之后都按手动的宽高，只由 trailBounds 截到可用范围
        if (size == null && showsReport(renderedState) && !TrailSizeSetting.isCustomized()) {
            val fitted = fitKeepingRatio(desiredWidth, desiredHeight, availableWidth, availableHeight, JBUI.scale(TrailSizeSetting.MIN_REPORT_WIDTH))
            desiredWidth = fitted.width
            desiredHeight = fitted.height
        }
        val bounds = trailBounds(width, height, rightReserve, expanded, desiredWidth, desiredHeight, metrics, topOffset)
        if (expanded) card.bounds = bounds
    }

    /** 显示报告页时用设定高度；提示、加载、报错这类短内容按内容收紧，不超过设定高度。 */
    private fun desiredCardHeight(): Int {
        val setting = JBUI.scale(TrailSizeSetting.height())
        if (showsReport(renderedState)) return setting
        return card.preferredSize.height.coerceAtLeast(JBUI.scale(TrailSizeSetting.COMPACT_HEIGHT)).coerceAtMost(setting)
    }

    /** 在卡片之外点击时由编辑器调用；已收起时什么都不做。 */
    fun collapse() = setExpanded(false)

    private fun setExpanded(expanded: Boolean) {
        if (virtualFile.trailExpanded == expanded) return
        virtualFile.trailExpanded = expanded
        store.notifyChanged(key)
    }

    private fun refresh() {
        if (disposed || project.isDisposed) return
        val expanded = virtualFile.trailExpanded
        val wasExpanded = shownExpanded
        if (wasExpanded && !expanded) returnFocusIfInside()
        shownExpanded = expanded

        if (expanded) store.generateIfNeeded(key, virtualFile.tabId)
        val state = store.state(key)
        updateNotices(state, expanded)
        if (expanded) render(state) else loadingTimer.stop()
        // 状态没变时 render 不重建内容：收起再展开也要补写一次，报告文件被删了能补回来（内容没变不重写、页面不重载）
        if (shouldRewriteOnExpand(wasExpanded, expanded, state)) currentReport(state)?.let(::showReport)
        relayout()
    }

    private fun render(state: TrailState) {
        titleLabel.text = ImuxBundle.message("action.overview.text")
        collapseButton.update()
        val (updateButton, openButton) = reportButtons
        updateButton.isVisible = showsReport(state)
        // 报告文件写好之后才能在浏览器里打开（见 applyReport）
        openButton.isVisible = showsReport(state) && shownReport != null
        reportButtons.forEach { it.update() }

        val sameLoading = state is TrailState.Loading && renderedState is TrailState.Loading &&
            (renderedState as TrailState.Loading).startedAt == state.startedAt
        if (renderedKey == key && sameLoading) {
            renderedState = state
            updateLoadingRow()
            if (!loadingTimer.isRunning) loadingTimer.start()
            return
        }
        if (renderedKey == key && renderedState == state) return
        renderedKey = key
        renderedState = state

        loadingRow = null
        loadingTimer.stop()
        body.removeAll()
        body.add(contentFor(state), BorderLayout.CENTER)
        if (state is TrailState.Loading) {
            updateLoadingRow()
            loadingTimer.restart()
        }
        body.revalidate()
        body.repaint()
    }

    private fun contentFor(state: TrailState): JComponent {
        val regenerate = { store.generate(key, virtualFile.tabId) }
        if (showsReport(state)) return reportContent(state, regenerate)
        // 不再显示报告：还在后台排队的写入作废；之后写好的通知因为不在报告页，也不会再动按钮和视图
        ProgressOverviewService.getInstance(project).reportWrites.ticket(key)
        reportBanners = null
        writeFailedBanner = null
        shownReport = null
        return when (state) {
            TrailState.Unavailable -> buildHintPanel(ImuxBundle.message("action.overview.pending"), null)
            TrailState.Empty -> buildHintPanel(
                ImuxBundle.message("action.overview.empty"),
                ImuxBundle.message("action.overview.generate") to regenerate,
            )
            is TrailState.Loading -> loadingPanel()
            // 生成过但会话刚开始、总结不出东西；有新进展后可以更新
            is TrailState.Ready -> buildHintPanel(
                ImuxBundle.message("action.overview.single.hint"),
                if (state.stale) ImuxBundle.message("action.overview.report.update") to regenerate else null,
            )
            is TrailState.Failed -> buildErrorPanel(state.message, regenerate)
        }
    }

    /**
     * 报告页：生成中、失败、过期、保存失败时顶部各有一条横幅，下面照常显示（旧）报告。
     * 报告先由服务渲染成 `.imux/reports/` 下的文件，再交给视图显示。
     */
    private fun reportContent(state: TrailState, regenerate: () -> Unit): JComponent {
        val result = when (state) {
            is TrailState.Ready -> state.result
            is TrailState.Loading -> state.previous
            is TrailState.Failed -> state.previous
            else -> null
        } ?: error("showsReport 已确认有报告")
        val banner: JComponent? = when (state) {
            is TrailState.Loading -> buildProgressBanner { store.cancel(key) }.let { (component, row) ->
                loadingRow = row
                component
            }
            is TrailState.Failed -> buildFailedBanner(state.message, regenerate)
            is TrailState.Ready -> when {
                result.saveFailed -> buildSaveFailedBanner(regenerate)
                state.stale -> buildStaleBanner(
                    ImuxBundle.message(if (result.legacy) "action.overview.report.legacy" else "action.overview.report.stale"),
                    regenerate,
                )
                else -> null
            }
            else -> null
        }
        val banners = JPanel().apply {
            isOpaque = false
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            banner?.let(::add)
        }
        reportBanners = banners
        writeFailedBanner = null
        val view = reportView()
        showReport(result)
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(banners, BorderLayout.NORTH)
            add(view.component, BorderLayout.CENTER)
        }
    }

    private fun reportView(): ReportView =
        reportView ?: ReportViews.create(
            onEscape = { setExpanded(false) },
            onOpenReport = ::openReportInBrowser,
            openExternal = { BrowserUtil.browse(it.toUri()) },
        ).also { view ->
            Disposer.register(this, view)
            view.setDark(!JBColor.isBright())
            reportView = view
        }

    /**
     * 请求把 [result] 写成报告文件：在后台线程按会话排队写（读写磁盘不占界面线程），同一会话只写最新那次。
     * 这里不负责显示：写好后服务通知这个会话的所有面板（见 [onReportWritten]），号过期没去写的面板也拿到最新的报告。
     */
    private fun showReport(result: OverviewResult) {
        val sessionKey = key
        val service = ProgressOverviewService.getInstance(project)
        // 按会话领号：同一会话后来的请求（含标签页重建、另一个分屏）一领号，这次就过期了
        val ticket = service.reportWrites.ticket(sessionKey)
        ApplicationManager.getApplication().executeOnPooledThread {
            service.reportWrites.runIfLatest(sessionKey, ticket) { service.writeReport(sessionKey, result) }
        }
    }

    /**
     * 在系统浏览器中打开报告：标题栏按钮与无 JCEF 时的备用链接共用。不直接打开记下的路径（文件可能已被删掉），
     * 先请求写好（没变就不重写），写好的通知到了再打开（见 [applyReport]）。
     */
    private fun openReportInBrowser() {
        currentReport(renderedState)?.let {
            pendingOpen = true
            showReport(it)
        }
    }

    /** 这个会话的报告写好了（[write] 为 null 表示写入失败）：面板此刻显示的正是它的报告页才更新。 */
    private fun onReportWritten(sessionKey: String, write: ReportWrite?) {
        if (disposed || sessionKey != key || !showsReport(renderedState)) return
        applyReport(write)
    }

    /**
     * 写入失败（没有工程目录、磁盘满、没有权限）时顶部加一条横幅，点「更新报告」按面板当前的报告再写一次；「在浏览器中打开」随之隐藏。
     * 内容变了或换了文件才让视图重新加载，同一份报告切换状态时不闪。
     */
    private fun applyReport(write: ReportWrite?) {
        val view = reportView ?: return
        val banners = reportBanners ?: return
        reportButtons[1].isVisible = write != null
        writeFailedBanner?.let(banners::remove)
        writeFailedBanner = null
        if (write == null) {
            shownReport = null
            pendingOpen = false
            val failed = buildFailedBanner(ImuxBundle.message("action.overview.report.writeFailed")) {
                currentReport(renderedState)?.let(::showReport)
            }
            writeFailedBanner = failed
            banners.add(failed)
            banners.revalidate()
            banners.repaint()
            return
        }
        banners.revalidate()
        // 按内容判断：别的请求可能刚把文件改成同样的内容，这次通知的 changed 才是 false，面板上却仍是旧版
        if (needsReload(shownReport, write)) {
            view.show(write.path)
            shownReport = ShownReport(write.path, write.digest)
        }
        if (pendingOpen) {
            pendingOpen = false
            BrowserUtil.browse(write.path.toUri())
        }
    }

    /** 第一次生成、还没有旧报告：进度居中。 */
    private fun loadingPanel(): JComponent {
        val row = LoadingRow()
        loadingRow = row
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(16, 0)
            add(row.component, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.CENTER)).apply {
                    isOpaque = false
                    add(ActionLink(ImuxBundle.message("action.overview.cancel")) { store.cancel(key) }.apply { isFocusable = false })
                },
                BorderLayout.SOUTH,
            )
        }
    }

    private fun updateLoadingRow() {
        val row = loadingRow ?: return
        val state = renderedState as? TrailState.Loading ?: return
        val seconds = ((System.currentTimeMillis() - state.startedAt) / 1_000).coerceAtLeast(0)
        row.update(state.progress ?: ImuxBundle.message("action.overview.loading"), seconds)
    }

    /** 失败是常驻提示（带「重试」）；从生成中变为就绪且卡片收着时，提示一次已更新。 */
    private fun updateNotices(state: TrailState, expanded: Boolean) {
        val loading = state is TrailState.Loading
        notices.persistent(
            (state as? TrailState.Failed)?.let {
                Notice(
                    key = "trail:failed:${it.message}",
                    tone = NoticeTone.WARNING,
                    text = ImuxBundle.message("action.overview.notice.failed", it.message),
                    actions = listOf(NoticeAction(ImuxBundle.message("action.overview.notice.retry")) { store.generate(key, virtualFile.tabId) }),
                )
            },
        )
        if (wasLoading == true && state is TrailState.Ready && !expanded) {
            notices.flash(
                Notice(
                    key = "trail:ready",
                    tone = NoticeTone.SUCCESS,
                    text = ImuxBundle.message("action.overview.notice.ready", state.result.nodes.size),
                    actions = listOf(NoticeAction(ImuxBundle.message("action.overview.notice.show")) { setExpanded(true) }),
                ),
            )
        }
        wasLoading = loading
    }

    /** 收起时若焦点在卡片里（比如刚选中了节点文字），交还终端，免得键盘输入落空。 */
    private fun returnFocusIfInside() {
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return
        if (SwingUtilities.isDescendingFrom(owner, card)) focusTerminal()
    }

    override fun dispose() {
        disposed = true
        loadingTimer.stop()
    }

    /** 卡片锚定右上角：拖左边缘改宽、拖下边缘改高、拖左下角同时改；双击左下角恢复默认大小。 */
    private inner class ResizeHandler : MouseAdapter() {
        private var start: Point? = null
        private var startWidth = 0
        private var startHeight = 0
        private var resizeWidth = false
        private var resizeHeight = false

        private fun zone(event: MouseEvent): Pair<Boolean, Boolean> {
            val edge = JBUI.scale(TRAIL_RESIZE_ZONE)
            return (event.x < edge) to (event.y > card.height - edge)
        }

        override fun mouseMoved(event: MouseEvent) {
            val (left, bottom) = zone(event)
            card.cursor = Cursor.getPredefinedCursor(
                when {
                    left && bottom -> Cursor.SW_RESIZE_CURSOR
                    left -> Cursor.W_RESIZE_CURSOR
                    bottom -> Cursor.S_RESIZE_CURSOR
                    else -> Cursor.DEFAULT_CURSOR
                },
            )
            card.toolTipText = if (left || bottom) ImuxBundle.message("action.overview.resize.hint") else null
        }

        override fun mousePressed(event: MouseEvent) {
            val (left, bottom) = zone(event)
            if (!left && !bottom) return
            start = event.locationOnScreen
            startWidth = card.width
            startHeight = card.height
            resizeWidth = left
            resizeHeight = bottom
        }

        override fun mouseDragged(event: MouseEvent) {
            val origin = start ?: return
            val point = event.locationOnScreen
            dragSize = resizedTrailSize(
                startWidth, startHeight,
                point.x - origin.x, point.y - origin.y,
                resizeWidth, resizeHeight,
                availableWidth, availableHeight,
                trailMetrics(),
            )
            relayout()
        }

        override fun mouseReleased(event: MouseEvent) {
            val size = dragSize ?: return
            start = null
            dragSize = null
            val height = heightToSave(resizeHeight, showsReport(renderedState), JBUI.unscale(size.height), TrailSizeSetting.height())
            TrailSizeSetting.save(JBUI.unscale(size.width), height)
            relayout()
        }

        override fun mouseClicked(event: MouseEvent) {
            val (left, bottom) = zone(event)
            if (event.clickCount == 2 && left && bottom) {
                TrailSizeSetting.reset()
                relayout()
            }
        }

        override fun mouseExited(event: MouseEvent) {
            if (start == null) card.cursor = Cursor.getDefaultCursor()
        }
    }

    private companion object {
        const val CHIP_ACTION_PLACE = "imuxSessionTrail"
        const val MONITOR_REFRESH_MS = 1_000
    }
}

/** 与消息导航预览卡片同一套底色与描边的圆角面板；可在左下角画拖拽手柄。浮动图标组也用它。 */
internal class TrailSurface(private val showGrip: Boolean = false) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(8)
            g.color = BACKGROUND
            g.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g.color = BORDER
            g.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            if (showGrip) {
                val inset = JBUI.scale(3)
                g.color = JBColor.GRAY
                for (step in 1..2) {
                    val d = JBUI.scale(4) * step
                    g.drawLine(inset, height - 1 - inset - d, inset + d, height - 1 - inset)
                }
            }
        } finally {
            g.dispose()
        }
    }

    private companion object {
        val BACKGROUND = JBColor.namedColor("Imux.SessionMessageNavigator.previewBackground", JBColor(0xF7F8FA, 0x2B2D30))
        val BORDER = JBColor.namedColor("Imux.SessionMessageNavigator.previewBorder", JBColor(0xD1D5DB, 0x393B40))
    }
}

/**
 * 右上角 chip×chip 的按钮位：折叠图标和卡片里的「收起」都放在这里。
 * 卡片与折叠图标锚定同一个右上角，所以展开、收起时按钮位置完全不变，鼠标不用挪。
 */
internal fun trailCornerSlot(button: JComponent): JComponent =
    JPanel(BorderLayout()).apply {
        isOpaque = false
        val size = trailMetrics().chip
        preferredSize = java.awt.Dimension(size, size)
        minimumSize = preferredSize
        add(button, BorderLayout.CENTER)
    }

/** 卡片边距：上、右为 0，让「收起」贴住右上角；左、下留出拖拽热区。 */
internal fun trailCardBorder(): javax.swing.border.Border =
    JBUI.Borders.empty(0, TRAIL_RESIZE_ZONE, TRAIL_RESIZE_ZONE, 0)

/** 卡片标题行：标题在左，[extras] 依次排在「收起」左边，「收起」占右上角按钮位，行高等于按钮位。 */
internal fun trailCardHeader(title: JComponent, collapseButton: JComponent, extras: List<JComponent> = emptyList()): JComponent =
    JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(2)
        add(title.apply { border = JBUI.Borders.emptyLeft(6) }, BorderLayout.CENTER)
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                if (extras.isNotEmpty()) {
                    add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                        isOpaque = false
                        extras.forEach(::add)
                    }, BorderLayout.CENTER)
                }
                add(trailCornerSlot(collapseButton), BorderLayout.EAST)
            },
            BorderLayout.EAST,
        )
    }

/** 卡片左、下边缘的拖拽热区宽度（未缩放）。 */
internal const val TRAIL_RESIZE_ZONE = 6

/** 这个状态下内容区显示报告页（生成中、失败时有旧报告也显示旧报告）；其余显示提示、加载、错误面板。 */
internal fun showsReport(state: TrailState?): Boolean =
    when (state) {
        is TrailState.Ready -> state.result.report?.timeline?.isNotEmpty() == true
        is TrailState.Loading -> state.previous?.report?.timeline?.isNotEmpty() == true
        is TrailState.Failed -> state.previous?.report?.timeline?.isNotEmpty() == true
        else -> false
    }

/** 状态里正在显示的报告结果：就绪时是它本身，生成中、失败时是旧报告。 */
internal fun currentReport(state: TrailState?): com.github.izerui.imux.overview.OverviewResult? =
    when (state) {
        is TrailState.Ready -> state.result
        is TrailState.Loading -> state.previous
        is TrailState.Failed -> state.previous
        else -> null
    }

/** 收起后再展开、且显示的是报告页：要重新请求写报告（文件可能已被删）。一直展开着时由状态变化驱动。 */
internal fun shouldRewriteOnExpand(wasExpanded: Boolean, expanded: Boolean, state: TrailState): Boolean =
    !wasExpanded && expanded && showsReport(state)
