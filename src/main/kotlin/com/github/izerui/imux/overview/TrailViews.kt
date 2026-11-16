package com.github.izerui.imux.overview

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.agent.AgentProgressEvent
import com.github.izerui.imux.agent.AgentProgressKind
import com.github.izerui.imux.agent.OneShotAgentTimeoutException
import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * 没有可展示的旧结果时的失败视图：标题固定在上方，「重新生成」固定在底部，
 * 错误信息放在中间的滚动区里按卡片宽度换行、可选中复制。CLI 报错可能带整段堆栈，
 * 不能让它把重试入口挤出卡片。
 */
internal fun buildErrorPanel(message: String, onRetry: () -> Unit): JComponent {
    val header = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(12, 0, 6, 0)
        add(centeredRow(JBLabel(AllIcons.General.Error)))
        add(Box.createVerticalStrut(JBUI.scale(6)))
        add(centeredRow(JBLabel(ImuxBundle.message("action.overview.failed")).apply {
            font = JBUI.Fonts.label(13f).asBold()
        }))
    }
    val detail = javax.swing.JTextArea(message).apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        foreground = JBColor.GRAY
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.empty()
    }
    val scroll = JBScrollPane(
        detail,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
    ).apply {
        border = JBUI.Borders.empty(0, 12)
        isOpaque = false
        viewport.isOpaque = false
    }
    return JPanel(BorderLayout()).apply {
        isOpaque = false
        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(centeredRow(regenerateLink(onRetry)).apply { border = JBUI.Borders.empty(6, 0, 10, 0) }, BorderLayout.SOUTH)
    }
}

/** 有旧结果时的失败横幅：旧时间线照常显示在下面。 */
internal fun buildFailedBanner(message: String, onRetry: () -> Unit): JComponent =
    bannerRow(
        AllIcons.General.Error,
        ImuxBundle.message("action.overview.failed.banner", message),
        FAILED_BANNER_BACKGROUND,
        tooltip = message,
        onRegenerate = onRetry,
    )

/** 未落盘、未生成时的说明文字，可带一个操作链接。 */
internal fun buildHintPanel(text: String, action: Pair<String, () -> Unit>?): JComponent {
    val center = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(16, 12)
        isOpaque = false
    }
    center.add(centeredRow(JBLabel(wrappedLabelHtml(text, JBUI.scale(220))).apply { foreground = JBColor.GRAY }))
    if (action != null) {
        center.add(Box.createVerticalStrut(JBUI.scale(10)))
        center.add(centeredRow(ActionLink(action.first).apply {
            isFocusable = false
            addActionListener { action.second() }
        }))
    }
    return center
}

private fun centeredRow(component: JComponent): JComponent =
    JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
        isOpaque = false
        add(component)
    }

/** 智能体当前在做什么；不需要换文字的事件返回 null。 */
internal fun overviewProgressText(event: AgentProgressEvent): String? = when (event.kind) {
    AgentProgressKind.STARTING -> ImuxBundle.message("action.overview.progress.starting")
    AgentProgressKind.THINKING -> ImuxBundle.message("action.overview.progress.thinking")
    AgentProgressKind.TOOL_STARTED -> toolActionText(event.subject)
    AgentProgressKind.RETRYING -> ImuxBundle.message("action.overview.progress.retrying")
    AgentProgressKind.RESPONDING -> ImuxBundle.message("action.overview.progress.responding")
    AgentProgressKind.TOOL_FINISHED, AgentProgressKind.COMPLETED, AgentProgressKind.FAILED -> null
}

/**
 * 工具动作的显示文字。进度里的 subject 形如 `Read: /a/b/session.jsonl`、`Bash: rg -n ...`，
 * 换成“读取 session.jsonl”“执行命令 rg -n ...”；路径只留文件名，太长的由界面用省略号截断。
 */
internal fun toolActionText(subject: String): String {
    val name = subject.substringBefore(':').trim().ifBlank { "tool" }
    val detail = subject.substringAfter(':', "").trim().let(::shortenPathDetail)
    val key = when (name.lowercase()) {
        "read" -> "action.overview.progress.read"
        "grep", "search", "web search" -> "action.overview.progress.search"
        "glob", "find", "ls" -> "action.overview.progress.find"
        "bash", "shell" -> "action.overview.progress.command"
        else -> return ImuxBundle.message("action.overview.progress.tool", if (detail.isEmpty()) name else "$name $detail")
    }
    return ImuxBundle.message(key, detail).trim()
}

/** 不含空白的单个路径只显示文件名；命令、搜索词等原样保留。 */
private fun shortenPathDetail(detail: String): String {
    if (detail.isEmpty() || detail.any(Char::isWhitespace)) return detail
    val name = detail.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
    return name.ifEmpty { detail }
}

internal fun elapsedText(seconds: Long): String = ImuxBundle.message("action.overview.progress.elapsed", seconds)

/**
 * 加载中的单行提示：转圈 + 当前动作 + 耗时，作为一组紧凑地居中显示。
 *
 * 动作文字放不下时只截断它本身（JLabel 自带省略号，悬停看全文），耗时始终紧跟在后面完整显示。
 * 整组的最小/首选宽度不随文字变长，避免长命令把弹窗撑宽、宽度随动作忽大忽小。
 */
internal class LoadingRow(
    /** 两侧留白（未缩放）：居中的加载页留 32，放进顶部横幅时为 0，把宽度都留给动作文字。 */
    margin: Int = 32,
) {
    val spinner = JLabel(AnimatedIcon.Default())
    val action = JBLabel()
    val elapsed = JBLabel().apply { foreground = JBColor.GRAY }
    val component: JComponent = JPanel(CenteredRowLayout(JBUI.scale(8), JBUI.scale(margin))).apply {
        isOpaque = false
        add(spinner)
        add(action)
        add(elapsed)
    }

    fun update(actionText: String, elapsedSeconds: Long) {
        action.text = actionText
        action.toolTipText = actionText
        elapsed.text = elapsedText(elapsedSeconds)
    }
}

/**
 * 把三个子组件（图标、可截断的文字、固定文字）排成一行，整组水平垂直居中。
 * 空间不够时只压缩中间那个；首选/最小宽度不计中间文字，所以不会撑宽容器。
 */
private class CenteredRowLayout(private val gap: Int, private val margin: Int) : java.awt.LayoutManager {
    override fun addLayoutComponent(name: String?, comp: java.awt.Component) = Unit
    override fun removeLayoutComponent(comp: java.awt.Component) = Unit

    override fun preferredLayoutSize(parent: java.awt.Container): java.awt.Dimension = fixedSize(parent)
    override fun minimumLayoutSize(parent: java.awt.Container): java.awt.Dimension = fixedSize(parent)

    /** 只算图标和耗时：中间文字多长都不影响容器尺寸。 */
    private fun fixedSize(parent: java.awt.Container): java.awt.Dimension {
        val (lead, text, tail) = parent.components
        val height = maxOf(lead.preferredSize.height, text.preferredSize.height, tail.preferredSize.height)
        val insets = parent.insets
        return java.awt.Dimension(
            lead.preferredSize.width + tail.preferredSize.width + gap * 2 + margin * 2 + insets.left + insets.right,
            height + insets.top + insets.bottom,
        )
    }

    override fun layoutContainer(parent: java.awt.Container) {
        val (lead, text, tail) = parent.components
        val insets = parent.insets
        val available = parent.width - insets.left - insets.right - margin * 2
        val fixed = lead.preferredSize.width + tail.preferredSize.width + gap * 2
        val textWidth = text.preferredSize.width.coerceAtMost((available - fixed).coerceAtLeast(0))
        val total = fixed + textWidth
        var x = insets.left + margin + ((available - total) / 2).coerceAtLeast(0)
        val centerY = insets.top + (parent.height - insets.top - insets.bottom) / 2
        for ((comp, width) in listOf(lead to lead.preferredSize.width, text to textWidth, tail to tail.preferredSize.width)) {
            val height = comp.preferredSize.height
            comp.setBounds(x, centerY - height / 2, width, height)
            x += width + gap
        }
    }
}

private fun wrappedLabelHtml(text: String, width: Int): String =
    "<html><div style='text-align:center;width:${width}px'>" +
        com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text) + "</div></html>"

/** 超时、无法解析给出可读提示；其他错误保留原始信息便于排查。 */
internal fun overviewErrorText(error: Throwable): String = when (error) {
    is OneShotAgentTimeoutException -> ImuxBundle.message("action.overview.failed.timeout")
    is OverviewParseException -> ImuxBundle.message("action.overview.failed.parse")
    else -> error.message ?: ImuxBundle.message("action.overview.failed.hint")
}

/** 重新生成报告的入口：失败面板、各条横幅共用。 */
private fun regenerateLink(onRegenerate: () -> Unit) =
    ActionLink(ImuxBundle.message("action.overview.report.update")).apply {
        isFocusable = false
        addActionListener { onRegenerate() }
    }

/**
 * 生成中、已有旧报告：顶部一条进度条（进度文字、已用秒数与「取消」），下面照常显示旧报告，完成后整体换成新报告。
 * 进度条里的 [LoadingRow] 由调用方按进度事件刷新。
 */
internal fun buildProgressBanner(onCancel: () -> Unit): Pair<JComponent, LoadingRow> {
    val progress = LoadingRow(margin = 0)
    val banner =
        JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = true
            background = JBColor(0xE3F2FD.toInt(), 0x1E3A5F)
            border = JBUI.Borders.empty(4, 8)
            add(progress.component, BorderLayout.CENTER)
            add(
                ActionLink(ImuxBundle.message("action.overview.cancel")).apply {
                    isFocusable = false
                    addActionListener { onCancel() }
                },
                BorderLayout.EAST,
            )
        }
    return banner to progress
}

/** 报告过期（会话有新进展，或读自旧格式的缓存）：顶部一条说明与「更新报告」。 */
internal fun buildStaleBanner(text: String, onUpdate: () -> Unit): JComponent =
    bannerRow(AllIcons.General.Warning, text, JBColor(0xFFF8E1.toInt(), 0x3B3820), onRegenerate = onUpdate)

/** 报告已生成但没能写进缓存：下次打开要重新生成，提示一下并给出重试。 */
internal fun buildSaveFailedBanner(onRetry: () -> Unit): JComponent =
    bannerRow(AllIcons.General.Warning, ImuxBundle.message("action.overview.save.failed"), FAILED_BANNER_BACKGROUND, onRegenerate = onRetry)

private val FAILED_BANNER_BACKGROUND = JBColor(0xFDECEA.toInt(), 0x3C1F1F)

/**
 * 单行横幅：图标、文字、「重新生成」依次靠左排开，链接紧跟在文字后面。
 * 放不下时只压缩文字（JLabel 截断成省略号、悬停看全文），链接始终按首选尺寸完整显示。
 *
 * 横幅放在 BorderLayout.NORTH，只按首选的单行高度分配空间；用 FlowLayout 时窄卡片里链接会
 * 被挤到第二行而被裁掉，用户就看不到重试入口了。
 */
private fun bannerRow(
    icon: javax.swing.Icon,
    text: String,
    background: java.awt.Color,
    tooltip: String = text,
    onRegenerate: (() -> Unit)? = null,
): JComponent =
    JPanel(LeadingRowLayout(JBUI.scale(6))).apply {
        isOpaque = true
        this.background = background
        border = JBUI.Borders.empty(4, 8)
        add(JBLabel(icon))
        add(JBLabel(text).apply { toolTipText = tooltip })
        if (onRegenerate != null) add(regenerateLink(onRegenerate))
    }

/**
 * 靠左排成一行、垂直居中：第二个组件（文字）可压缩，其余按首选宽度完整摆放。
 * 空间够时各占首选宽度，右侧留空；不够时只压缩文字，后面的组件仍紧跟在它后面。
 */
private class LeadingRowLayout(private val gap: Int) : java.awt.LayoutManager {
    override fun addLayoutComponent(name: String?, comp: java.awt.Component) = Unit
    override fun removeLayoutComponent(comp: java.awt.Component) = Unit
    override fun minimumLayoutSize(parent: java.awt.Container): java.awt.Dimension = preferredLayoutSize(parent)

    override fun preferredLayoutSize(parent: java.awt.Container): java.awt.Dimension {
        val sizes = parent.components.map { it.preferredSize }
        val insets = parent.insets
        return java.awt.Dimension(
            sizes.sumOf { it.width } + gap * (sizes.size - 1).coerceAtLeast(0) + insets.left + insets.right,
            (sizes.maxOfOrNull { it.height } ?: 0) + insets.top + insets.bottom,
        )
    }

    override fun layoutContainer(parent: java.awt.Container) {
        val components = parent.components
        val insets = parent.insets
        val available = parent.width - insets.left - insets.right
        val widths = components.map { it.preferredSize.width }.toMutableList()
        val total = widths.sum() + gap * (widths.size - 1).coerceAtLeast(0)
        if (total > available && widths.size > 1) {
            widths[1] = (widths[1] - (total - available)).coerceAtLeast(0)
        }
        val centerY = insets.top + (parent.height - insets.top - insets.bottom) / 2
        var x = insets.left
        components.forEachIndexed { index, component ->
            val height = component.preferredSize.height
            component.setBounds(x, centerY - height / 2, widths[index], height)
            x += widths[index] + gap
        }
    }
}
