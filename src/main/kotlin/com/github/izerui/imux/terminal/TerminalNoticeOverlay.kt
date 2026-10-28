package com.github.izerui.imux.terminal

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Graphics
import java.awt.MouseInfo
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 终端顶部叠放的提示条。放在终端上方的图层里，只盖住终端顶部几行，不改变终端尺寸，TUI 不会重新排版。
 *
 * 规则在 [NoticeStack]；这里负责画出来、按真实时间倒数：标签页看不见时不倒数，鼠标停在任意一条上时全部暂停。
 * `onChanged` 在提示增减后调用，编辑器据此重新布局并刷新结果 chip。
 *
 * 各来源在编辑器构造期间就会发第一批提示（例如重建编辑器时轨迹已经是失败状态），那时编辑器的字段还没初始化完，
 * 回调进去会空指针。所以 `start()` 之前只记进 [NoticeStack]，不建行也不回调；编辑器初始化完成后调用 `start()` 再画。
 */
/** 一行提示的画法。生产用平台的编辑器通知条（[platformNoticeView]）；测试里平台配色服务起不来，换成普通组件。 */
internal interface NoticeView {
    val component: JComponent

    fun setText(text: String)

    fun setIcon(icon: Icon)

    fun addAction(text: String, run: (JComponent) -> Unit)

    fun setCloseAction(run: () -> Unit)
}

internal fun platformNoticeView(tone: NoticeTone): NoticeView =
    object : NoticeView {
        private val panel =
            EditorNotificationPanel(
                when (tone) {
                    NoticeTone.WARNING -> EditorNotificationPanel.Status.Warning
                    NoticeTone.INFO -> EditorNotificationPanel.Status.Info
                    NoticeTone.SUCCESS -> EditorNotificationPanel.Status.Success
                },
            )
        override val component: JComponent get() = panel

        override fun setText(text: String) {
            if (panel.text != text) panel.text(text)
        }

        override fun setIcon(icon: Icon) {
            panel.icon(icon)
        }

        override fun addAction(text: String, run: (JComponent) -> Unit) {
            lateinit var label: HyperlinkLabel
            label = panel.createActionLabel(text) { run(label) }
        }

        override fun setCloseAction(run: () -> Unit) {
            panel.setCloseAction(run)
        }
    }

internal class TerminalNoticeOverlay(
    parent: Disposable,
    private val onChanged: () -> Unit,
    private val viewFactory: (NoticeTone) -> NoticeView = ::platformNoticeView,
) : Disposable {
    private val stack = NoticeStack()
    private var rows: List<NoticeRow> = emptyList()
    private var lastTick = 0L
    private var started = false
    private val timer = Timer(TICK_MILLIS) { tick() }

    val component: JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            isVisible = false
        }

    init {
        Disposer.register(parent, this)
        // 倒数只在看得见时进行：看不见时停掉定时器，重新显示时接着倒数
        component.addHierarchyListener { event ->
            val showingChanged = event.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L
            if (showingChanged && component.isShowing && started && stack.hasCountdown() && !timer.isRunning) {
                lastTick = System.currentTimeMillis()
                timer.start()
            }
        }
    }

    fun sink(source: NoticeSource): NoticeSink =
        object : NoticeSink {
            override fun persistent(notice: Notice?) {
                stack.setPersistent(source, notice)
                render()
            }

            override fun flash(notice: Notice) {
                stack.flash(source, notice)
                render()
            }
        }

    fun isShowing(source: NoticeSource): Boolean = stack.visible().any { it.source == source }

    /** 编辑器初始化完成后调用一次，画出此前积下的提示。 */
    fun start() {
        if (started) return
        started = true
        render()
    }

    private fun render() {
        if (!started) return
        val shown = stack.visible()
        val sameRows = rows.size == shown.size && rows.zip(shown).all { (row, notice) -> row.canShow(notice) }
        if (sameRows) {
            rows.zip(shown).forEach { (row, notice) -> row.update(notice) }
        } else {
            val reusable = rows.associateBy { it.id }
            rows = shown.map { notice -> reusable[notice.id]?.takeIf { it.canShow(notice) } ?: NoticeRow(notice) }
            rows.zip(shown).forEach { (row, notice) -> row.update(notice) }
            component.removeAll()
            rows.forEach(component::add)
            component.isVisible = rows.isNotEmpty()
            component.revalidate()
            onChanged()
        }
        component.repaint()
        if (stack.hasCountdown()) {
            if (!timer.isRunning) {
                lastTick = System.currentTimeMillis()
                timer.start()
            }
        } else {
            timer.stop()
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastTick
        lastTick = now
        if (!component.isShowing) {
            timer.stop()
            return
        }
        if (isHovered()) return
        stack.tick(elapsed)
        render()
    }

    private fun isHovered(): Boolean {
        val point = MouseInfo.getPointerInfo()?.location ?: return false
        SwingUtilities.convertPointFromScreen(point, component)
        return component.contains(point)
    }

    override fun dispose() {
        timer.stop()
    }

    /** 一行提示：平台的编辑器通知条（颜色、图标、链接、× 都是原生的），一次性提示底部画倒数线。 */
    private inner class NoticeRow(first: ShownNotice) : JPanel(BorderLayout()) {
        val id = first.id
        private val source = first.source
        private val actionTexts = first.notice.actions.map { it.text }
        private val closable = first.notice.closable
        private var remaining = 1f
        private var flash = first.flash
        private val view =
            viewFactory(first.notice.tone).apply {
                first.notice.actions.forEach { action -> addAction(action.text, action.run) }
                if (first.notice.closable) {
                    setCloseAction {
                        stack.dismiss(source)
                        render()
                    }
                }
            }

        init {
            isOpaque = false
            add(view.component, BorderLayout.CENTER)
        }

        fun canShow(notice: ShownNotice): Boolean =
            notice.id == id && notice.notice.actions.map { it.text } == actionTexts && notice.notice.closable == closable

        fun update(notice: ShownNotice) {
            view.setText(notice.notice.text)
            view.setIcon(notice.notice.icon ?: defaultIcon(notice.notice.tone))
            flash = notice.flash
            remaining = notice.remainingFraction
        }

        override fun paintChildren(g: Graphics) {
            super.paintChildren(g)
            if (!flash) return
            g.color = BannerColors.ONLINE
            val thickness = JBUI.scale(2)
            g.fillRect(0, height - thickness, (width * remaining).toInt(), thickness)
        }
    }

    private companion object {
        const val TICK_MILLIS = 200

        fun defaultIcon(tone: NoticeTone): Icon =
            when (tone) {
                NoticeTone.WARNING -> AllIcons.General.Warning
                NoticeTone.INFO -> AllIcons.General.Information
                NoticeTone.SUCCESS -> AllIcons.Status.Success
            }
    }
}

/** 结果 chip 在图标组里放多少：完整文案、短文案、只留图标、放不下就藏起来。 */
internal enum class ChipFit { FULL, SHORT, ICON, HIDDEN }

/** 一个按钮只放图标与带字时的宽度；[labelled] 为 null 表示此刻没有字。[pinned] 的字最后才收（结对伙伴名）。 */
internal data class PillWidth(val icon: Int, val labelled: Int?, val pinned: Boolean = false)

/** 结果 chip 各种放法的宽度，都已含 chip 与按钮之间的间距和分隔线。 */
internal data class ChipWidths(val full: Int, val short: Int, val icon: Int)

internal data class StripFit(val labelled: Set<Int>, val chip: ChipFit)

/**
 * 图标组按实际宽度逐级收缩，用户最在意的结对伙伴名最后才收：
 * 结果完整 → 结果短文案 → 从右往左收其它功能的字 → 结果只留图标 → 收起结果 → 最后收伙伴名。
 * 按钮本身（图标）始终保留；全收完仍放不下时返回最紧的那一档，由调用方让它盖住一点导航轨道。
 *
 * [chrome] 是底板左右内边距，[gap] 是按钮之间的间距。
 */
internal fun stripFit(
    available: Int,
    chrome: Int,
    gap: Int,
    pills: List<PillWidth>,
    chip: ChipWidths?,
): StripFit {
    fun width(fit: StripFit): Int {
        val buttons = pills.withIndex().sumOf { (i, pill) -> if (i in fit.labelled) pill.labelled ?: pill.icon else pill.icon }
        val chipWidth =
            when (fit.chip) {
                ChipFit.FULL -> chip?.full ?: 0
                ChipFit.SHORT -> chip?.short ?: 0
                ChipFit.ICON -> chip?.icon ?: 0
                ChipFit.HIDDEN -> 0
            }
        return chrome + buttons + gap * (pills.size - 1).coerceAtLeast(0) + chipWidth
    }
    val withText = pills.indices.filter { pills[it].labelled != null }.toSet()
    val shown = if (chip != null) ChipFit.FULL else ChipFit.HIDDEN
    val short = if (chip != null) ChipFit.SHORT else ChipFit.HIDDEN
    val steps = mutableListOf(StripFit(withText, shown), StripFit(withText, short))
    var current = withText
    for (i in pills.indices.reversed()) {
        if (i in current && !pills[i].pinned) {
            current = current - i
            steps += StripFit(current, short)
        }
    }
    if (chip != null) steps += StripFit(current, ChipFit.ICON)
    steps += StripFit(current, ChipFit.HIDDEN)
    steps += StripFit(emptySet(), ChipFit.HIDDEN)
    return steps.firstOrNull { width(it) <= available } ?: steps.last()
}

/**
 * 浮动图标组里的一个按钮：平时只是图标；功能开着或有事时是「图标＋短字」并带浅色底，颜色与顶部提示条同源。
 * 悬停显示 [ToolHoverTip]（内容随状态实时刷新）；点击交给 [SessionTool.perform]。
 */
internal class ToolPill(
    val tool: SessionTool,
    val pinned: Boolean = false,
    tipWindow: TipWindow? = null,
) : JComponent() {
    var state: ToolState = tool.state()
        private set
    private var showLabel = true
    private var hovered = false
    private var pressed = false
    private val tip = if (tipWindow != null) ToolHoverTip(this, tipWindow) else ToolHoverTip(this)

    init {
        isOpaque = false
        font = JBUI.Fonts.label()
        tip.setContent(state.title, state.description)
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseEntered(event: MouseEvent) {
                    hovered = true
                    tip.hoverStarted()
                    repaint()
                }

                override fun mouseExited(event: MouseEvent) {
                    hovered = false
                    pressed = false
                    tip.hoverEnded()
                    repaint()
                }

                override fun mousePressed(event: MouseEvent) {
                    tip.hide()
                    if (!SwingUtilities.isLeftMouseButton(event) || !state.enabled) return
                    pressed = true
                    repaint()
                }

                override fun mouseReleased(event: MouseEvent) {
                    val click = pressed && contains(event.point)
                    pressed = false
                    repaint()
                    if (click) tool.perform(this@ToolPill)
                }
            },
        )
    }

    /** 按功能的最新状态刷新；宽度变了返回 true，由图标组决定要不要重新布局。 */
    fun update(): Boolean {
        val next = tool.state()
        if (next == state) return false
        val before = preferredSize.width
        state = next
        // 正在看的说明原地刷新，不关闭（审查进度每秒都在变）
        tip.setContent(next.title, next.description)
        repaint()
        return preferredSize.width != before
    }

    fun showLabel(show: Boolean) {
        if (showLabel == show) return
        showLabel = show
        revalidate()
        repaint()
    }

    /** 只放图标时的宽度。 */
    fun iconWidth(): Int = JBUI.scale(SIZE)

    /** 带字时的宽度；此刻没有字时为 null。 */
    fun labelledWidth(): Int? {
        val label = state.label ?: return null
        return JBUI.scale(PAD_LEFT + PAD_RIGHT + GAP) + state.icon.iconWidth + getFontMetrics(font).stringWidth(label)
    }

    private val labelVisible get() = showLabel && state.label != null

    override fun getPreferredSize(): java.awt.Dimension =
        java.awt.Dimension(if (labelVisible) labelledWidth()!! else iconWidth(), JBUI.scale(SIZE))

    override fun getMinimumSize() = preferredSize

    override fun getMaximumSize() = preferredSize

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as java.awt.Graphics2D
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(ARC)
            val (fill, line) = colors()
            if (fill != null) {
                g.color = fill
                g.fillRoundRect(0, 0, width, height, arc, arc)
            }
            if (line != null) {
                g.color = line
                g.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }
            val icon = if (state.enabled) state.icon else com.intellij.openapi.util.IconLoader.getDisabledIcon(state.icon)
            val iconY = (height - icon.iconHeight) / 2
            if (labelVisible) {
                val x = JBUI.scale(PAD_LEFT)
                icon.paintIcon(this, g, x, iconY)
                g.font = font
                g.color = if (state.enabled) UIUtil.getLabelForeground() else UIUtil.getLabelDisabledForeground()
                val metrics = g.fontMetrics
                val textY = (height - metrics.height) / 2 + metrics.ascent
                g.drawString(state.label!!, x + icon.iconWidth + JBUI.scale(GAP), textY)
            } else {
                icon.paintIcon(this, g, (width - icon.iconWidth) / 2, iconY)
            }
        } finally {
            g.dispose()
        }
    }

    /** 有状态时用提示条同源的底色与描边；没有状态时按普通工具栏按钮的悬停、按下、选中处理。 */
    private fun colors(): Pair<java.awt.Color?, java.awt.Color?> {
        val tone = state.tone
        if (tone != null) {
            val (fill, line) =
                when (tone) {
                    NoticeTone.SUCCESS -> JBUI.CurrentTheme.Banner.SUCCESS_BACKGROUND to JBUI.CurrentTheme.Banner.SUCCESS_BORDER_COLOR
                    NoticeTone.INFO -> JBUI.CurrentTheme.Banner.INFO_BACKGROUND to JBUI.CurrentTheme.Banner.INFO_BORDER_COLOR
                    NoticeTone.WARNING -> JBUI.CurrentTheme.Banner.WARNING_BACKGROUND to JBUI.CurrentTheme.Banner.WARNING_BORDER_COLOR
                }
            return (if (pressed || hovered) line else fill) to line
        }
        return when {
            !state.enabled -> null to null
            pressed || state.selected -> JBUI.CurrentTheme.ActionButton.pressedBackground() to JBUI.CurrentTheme.ActionButton.pressedBorder()
            hovered -> JBUI.CurrentTheme.ActionButton.hoverBackground() to JBUI.CurrentTheme.ActionButton.hoverBorder()
            else -> null to null
        }
    }

    override fun removeNotify() {
        tip.hide()
        super.removeNotify()
    }

    internal val hoverTip get() = tip

    private companion object {
        const val SIZE = 28
        const val PAD_LEFT = 8
        const val PAD_RIGHT = 10
        const val GAP = 6
        const val ARC = 12
    }
}

/**
 * 终端右上角的浮动图标组：左边是可选的上一轮审查结果 chip，右边是一排功能按钮。
 * 底色与会话轨迹卡片相同；宽度不够时由 [fit] 按 [stripFit] 逐级收字，按钮本身始终保住。
 */
internal class TerminalToolStrip(
    val chip: ResultChip,
    private val pills: List<ToolPill>,
) {
    private val separator =
        JPanel().apply {
            isOpaque = true
            background = JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
            preferredSize = JBUI.size(1, 16)
            minimumSize = preferredSize
            maximumSize = preferredSize
            alignmentY = JComponent.CENTER_ALIGNMENT
        }
    private val chipGap = javax.swing.Box.createRigidArea(JBUI.size(GAP, 0))
    private var separatorGap: java.awt.Component? = null

    val component: JComponent =
        TrailSurface().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(PADDING)
            add(chip)
            add(chipGap)
            add(separator)
            add(javax.swing.Box.createRigidArea(JBUI.size(GAP, 0)).also { separatorGap = it })
            pills.forEachIndexed { i, pill ->
                if (i > 0) add(javax.swing.Box.createRigidArea(JBUI.size(GAP, 0)))
                pill.alignmentY = JComponent.CENTER_ALIGNMENT
                add(pill)
            }
        }

    /** 按 [available] 决定每个按钮带不带字、chip 怎么放，返回整组尺寸。 */
    fun fit(available: Int): java.awt.Dimension {
        val gap = JBUI.scale(GAP)
        val chipExtra = gap + separator.preferredSize.width + gap
        val chipWidths =
            if (!chip.wanted) null
            else ChipWidths(
                chip.widthFor(ChipFit.FULL) + chipExtra,
                chip.widthFor(ChipFit.SHORT) + chipExtra,
                chip.widthFor(ChipFit.ICON) + chipExtra,
            )
        val insets = component.insets
        val fit =
            stripFit(
                available,
                insets.left + insets.right,
                gap,
                pills.map { PillWidth(it.iconWidth(), it.labelledWidth(), it.pinned) },
                chipWidths,
            )
        pills.forEachIndexed { i, pill -> pill.showLabel(i in fit.labelled) }
        chip.show(fit.chip)
        chipGap.isVisible = chip.isVisible
        separator.isVisible = chip.isVisible
        separatorGap?.isVisible = chip.isVisible
        return component.preferredSize
    }

    /** 只在有按钮宽度变化时重新布局；审查进行时每秒都会调用。 */
    fun refresh() {
        val resized = pills.map { it.update() }.any { it }
        if (resized) {
            component.revalidate()
            component.repaint()
        }
    }

    private companion object {
        const val GAP = 4
        const val PADDING = 4
    }
}

/**
 * 上一轮审查结果：图标加一句短话，点开结对编程菜单。
 * 编辑器只给出完整、简短两种文案并决定要不要显示（[wanted]）；实际放哪一种由图标组按宽度决定，完整内容在提示里。
 */
internal class ResultChip(private val onClick: (JComponent) -> Unit) : JBLabel() {
    var wanted = false
        private set
    var fullText = ""
        private set
    var shortText = ""
        private set

    init {
        border = JBUI.Borders.empty(0, 6, 0, 8)
        iconTextGap = JBUI.scale(6)
        foreground = UIUtil.getContextHelpForeground()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        isVisible = false
        alignmentY = CENTER_ALIGNMENT
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) = onClick(this@ResultChip)

                override fun mouseEntered(event: MouseEvent) {
                    foreground = UIUtil.getLabelForeground()
                }

                override fun mouseExited(event: MouseEvent) {
                    foreground = UIUtil.getContextHelpForeground()
                }
            },
        )
    }

    fun setResult(
        wanted: Boolean,
        full: String,
        short: String,
    ) {
        this.wanted = wanted
        fullText = full
        shortText = short
    }

    fun widthFor(fit: ChipFit): Int {
        val text =
            when (fit) {
                ChipFit.FULL -> fullText
                ChipFit.SHORT -> shortText
                ChipFit.ICON, ChipFit.HIDDEN -> ""
            }
        val insets = insets
        val iconWidth = icon?.iconWidth ?: 0
        val textWidth = if (text.isEmpty()) 0 else getFontMetrics(font).stringWidth(text) + iconTextGap
        return insets.left + insets.right + iconWidth + textWidth
    }

    fun show(fit: ChipFit) {
        isVisible = fit != ChipFit.HIDDEN
        val next =
            when (fit) {
                ChipFit.FULL -> fullText
                ChipFit.SHORT -> shortText
                ChipFit.ICON, ChipFit.HIDDEN -> ""
            }
        if (text != next) text = next
    }

    override fun getPreferredSize(): java.awt.Dimension = super.getPreferredSize().apply { height = JBUI.scale(28) }

    override fun getMaximumSize(): java.awt.Dimension = preferredSize
}
