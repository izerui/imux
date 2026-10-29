package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.overview.LoadingRow
import com.github.izerui.imux.overview.ProgressOverviewService
import com.github.izerui.imux.overview.TrailState
import com.github.izerui.imux.overview.buildErrorPanel
import com.github.izerui.imux.overview.buildFailedWithPrevious
import com.github.izerui.imux.overview.buildHintPanel
import com.github.izerui.imux.overview.buildResultPanel
import com.github.izerui.imux.overview.buildUpdatingPanel
import com.github.izerui.imux.settings.ImuxSettings
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

    val card: JComponent = TrailSurface(showGrip = true).apply {
        layout = BorderLayout()
        // 左侧、底部的留白就是拖拽热区：落在卡片自身而不是子组件上，鼠标事件才会交给卡片
        border = trailCardBorder()
        add(trailCardHeader(titleLabel, collapseButton), BorderLayout.NORTH)
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
        ImuxSettings.getInstance().addLanguageListener(this) {
            renderedState = null
            refresh()
        }
        refresh()
    }

    /**
     * 由编辑器在 `doLayout` 中调用；[rightReserve] 是导航轨道连同右侧留白的宽度，
     * [topOffset] 是顶部提示条与浮动图标组占去的高度。
     */
    fun layout(width: Int, height: Int, rightReserve: Int, topOffset: Int) {
        val metrics = trailMetrics()
        val expanded = virtualFile.trailExpanded
        card.isVisible = expanded
        availableWidth = (width - rightReserve - metrics.margin * 2).coerceAtLeast(0)
        availableHeight = (height - topOffset - metrics.margin * 2).coerceAtLeast(0)
        val size = dragSize
        val desiredWidth = size?.width ?: JBUI.scale(TrailSizeSetting.width())
        val desiredHeight = size?.height ?: desiredCardHeight()
        val bounds = trailBounds(width, height, rightReserve, expanded, desiredWidth, desiredHeight, metrics, topOffset)
        if (expanded) card.bounds = bounds
    }

    /** 有时间线时用设定高度；提示、加载、报错这类短内容按内容收紧，不超过设定高度。 */
    private fun desiredCardHeight(): Int {
        val setting = JBUI.scale(TrailSizeSetting.height())
        val hasTimeline = when (val state = renderedState) {
            is TrailState.Ready -> state.result.nodes.isNotEmpty()
            is TrailState.Failed -> state.previous?.nodes?.isNotEmpty() == true
            is TrailState.Loading -> state.previous?.nodes?.isNotEmpty() == true
            else -> false
        }
        if (hasTimeline) return setting
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
        if (shownExpanded && !expanded) returnFocusIfInside()
        shownExpanded = expanded

        if (expanded) store.generateIfNeeded(key, virtualFile.tabId)
        val state = store.state(key)
        updateNotices(state, expanded)
        if (expanded) render(state) else loadingTimer.stop()
        relayout()
    }

    private fun render(state: TrailState) {
        titleLabel.text = ImuxBundle.message("action.overview.text")
        collapseButton.update()

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
        return when (state) {
            TrailState.Unavailable -> buildHintPanel(ImuxBundle.message("action.overview.pending"), null)
            TrailState.Empty -> buildHintPanel(
                ImuxBundle.message("action.overview.empty"),
                ImuxBundle.message("action.overview.generate") to regenerate,
            )
            is TrailState.Loading -> loadingPanel(state.previous)
            is TrailState.Ready -> buildResultPanel(state.result, state.stale, regenerate)
            is TrailState.Failed -> {
                val previous = state.previous
                if (previous == null) buildErrorPanel(state.message, regenerate)
                else buildFailedWithPrevious(state.message, previous, regenerate)
            }
        }
    }

    private fun loadingPanel(previous: com.github.izerui.imux.overview.OverviewResult?): JComponent {
        // 更新时旧时间线照看，进度放顶部；第一次生成没有旧内容，进度居中
        if (previous != null && previous.nodes.isNotEmpty()) {
            val view = buildUpdatingPanel(previous) { store.cancel(key) }
            loadingRow = view.progress
            return view.component
        }
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
            TrailSizeSetting.save(
                if (resizeWidth) JBUI.unscale(size.width) else TrailSizeSetting.width(),
                if (resizeHeight) JBUI.unscale(size.height) else TrailSizeSetting.height(),
            )
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

/** 卡片标题行：标题在左，「收起」占右上角按钮位，行高等于按钮位。 */
internal fun trailCardHeader(title: JComponent, collapseButton: JComponent): JComponent =
    JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(2)
        add(title.apply { border = JBUI.Borders.emptyLeft(6) }, BorderLayout.CENTER)
        add(trailCornerSlot(collapseButton), BorderLayout.EAST)
    }

/** 卡片左、下边缘的拖拽热区宽度（未缩放）。 */
internal const val TRAIL_RESIZE_ZONE = 6
