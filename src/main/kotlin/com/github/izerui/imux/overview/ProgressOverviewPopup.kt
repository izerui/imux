package com.github.izerui.imux.overview

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.agent.AgentProgressEvent
import com.github.izerui.imux.agent.AgentProgressKind
import com.github.izerui.imux.agent.OneShotAgentTimeoutException
import com.github.izerui.imux.settings.PluginLanguage
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

internal fun showProgressOverviewPopup(
    project: Project,
    sessionKey: String,
    owner: Component,
) {
    ProgressOverviewDialog(project, sessionKey).show()
}

private class ProgressOverviewDialog(
    private val project: Project,
    private val sessionKey: String,
) : DialogWrapper(project, false) {

    private val contentPanel = JPanel(BorderLayout()).apply {
        preferredSize = JBUI.size(500, 400)
    }
    private val popupScope: CoroutineScope
    private var loadingRow: LoadingRow? = null
    /** 加载态的进度：最近一条智能体动作 + 已用时间，每秒刷新。 */
    private var latestProgress: String? = null
    private var loadingStartedAt = 0L
    private val elapsedTimer = javax.swing.Timer(1_000) { refreshLoadingRow() }

    init {
        title = overviewDialogTitle(
            ImuxBundle.message("action.overview.text"),
            com.github.izerui.imux.monitor.SessionMonitor.getInstance(project).model.sessionOf(sessionKey)?.title,
        )
        isModal = false
        setOKButtonText(ImuxBundle.message("action.overview.retry"))

        val service = ProgressOverviewService.getInstance(project)
        popupScope = service.createChildScope()
        Disposer.register(disposable) {
            elapsedTimer.stop()
            popupScope.cancel("dialog disposed")
        }

        init()

        val cached = service.cachedOverview(sessionKey)
        if (cached != null) {
            showResult(cached.result, stale = cached.stale)
        } else {
            showLoading()
            launchGeneration()
        }
    }

    override fun createCenterPanel(): JComponent = contentPanel

    override fun createActions() = arrayOf(cancelAction.apply {
        putValue(javax.swing.Action.NAME, ImuxBundle.message("action.peer.close"))
    })

    private fun showLoading() {
        contentPanel.removeAll()
        val row = LoadingRow()
        loadingRow = row
        latestProgress = null
        loadingStartedAt = System.currentTimeMillis()
        refreshLoadingRow()
        elapsedTimer.restart()
        contentPanel.add(row.component, BorderLayout.CENTER)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun showProgress(event: AgentProgressEvent) {
        // 工具完成、整体完成这类事件不换文字，保留上一条“正在做什么”
        overviewProgressText(event)?.let { latestProgress = it }
        refreshLoadingRow()
    }

    private fun refreshLoadingRow() {
        val row = loadingRow ?: return
        val seconds = ((System.currentTimeMillis() - loadingStartedAt) / 1_000).coerceAtLeast(0)
        row.update(latestProgress ?: ImuxBundle.message("action.overview.loading"), seconds)
    }

    private fun stopLoading() {
        elapsedTimer.stop()
        loadingRow = null
    }

    private fun showResult(result: OverviewResult, stale: Boolean) {
        stopLoading()
        contentPanel.removeAll()
        contentPanel.add(buildResultPanel(result, stale) {
            showLoading()
            launchGeneration()
        }, BorderLayout.CENTER)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun showError(errorMessage: String) {
        stopLoading()
        contentPanel.removeAll()
        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(60, 40)
            isOpaque = false
        }

        val iconRow = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            isOpaque = false
            add(JBLabel(AllIcons.General.Error))
        }
        center.add(iconRow)
        center.add(Box.createVerticalStrut(JBUI.scale(8)))

        val titleRow = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            isOpaque = false
            add(JBLabel(ImuxBundle.message("action.overview.failed")).apply {
                font = JBUI.Fonts.label(13f).asBold()
            })
        }
        center.add(titleRow)
        center.add(Box.createVerticalStrut(JBUI.scale(4)))

        val hintRow = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            isOpaque = false
            // CLI 的报错可能很长，按弹窗宽度换行显示
            add(JBLabel(wrappedLabelHtml(errorMessage, JBUI.scale(380))).apply {
                foreground = JBColor.GRAY
                font = JBUI.Fonts.smallFont()
            })
        }
        center.add(hintRow)
        center.add(Box.createVerticalStrut(JBUI.scale(12)))

        val retryLink = ActionLink(ImuxBundle.message("action.overview.retry")) {
            showLoading()
            launchGeneration()
        }
        val retryRow = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            isOpaque = false
            add(retryLink)
        }
        center.add(retryRow)

        contentPanel.add(center, BorderLayout.CENTER)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun launchGeneration() {
        popupScope.launch(Dispatchers.IO) {
            try {
                val result = runInterruptible {
                    ProgressOverviewService.getInstance(project).generateOverview(sessionKey) { progress ->
                        ApplicationManager.getApplication().invokeLater({
                            if (!isDisposed) showProgress(progress)
                        }, ModalityState.any())
                    }
                }
                withContext(Dispatchers.EDT) {
                    if (!isDisposed) {
                        showResult(result, stale = result.stale)
                    }
                }
            } catch (_: CancellationException) {
                // scope cancelled — nothing to show
            } catch (e: Exception) {
                if (project.isDisposed) return@launch
                val message = overviewErrorText(e)
                LOG.warn("Progress overview generation failed", e)
                withContext(Dispatchers.EDT) {
                    if (!isDisposed) {
                        showError(message)
                    }
                }
            }
        }
    }
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
internal class LoadingRow {
    val spinner = JLabel(AnimatedIcon.Default())
    val action = JBLabel()
    val elapsed = JBLabel().apply { foreground = JBColor.GRAY }
    val component: JComponent = JPanel(CenteredRowLayout(JBUI.scale(8), JBUI.scale(32))).apply {
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

/**
 * 摘要结果面板。过期、保存失败的横幅和“重新生成”入口先于空结果判断，
 * 空摘要过期后也能让用户重新生成。
 */
internal fun buildResultPanel(
    result: OverviewResult,
    stale: Boolean,
    onRegenerate: () -> Unit,
): JComponent {
    val mainPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(4, 12, 4, 12)
    }
    statusBanner(result, stale, onRegenerate)?.let { mainPanel.add(it, BorderLayout.NORTH) }

    if (result.nodes.isEmpty()) {
        val hint = JBLabel(ImuxBundle.message("action.overview.single.hint")).apply {
            foreground = JBColor.GRAY
            horizontalAlignment = SwingConstants.CENTER
        }
        mainPanel.add(hint, BorderLayout.CENTER)
        return mainPanel
    }

    val isChinese = ImuxBundle.currentLanguage() in setOf(PluginLanguage.SIMPLIFIED_CHINESE, PluginLanguage.TRADITIONAL_CHINESE)
    val isAllSide = result.nodes.all { it.symbol == NodeSymbol.SIDE }

    val timelinePanel = ScrollablePanel().apply {
        border = JBUI.Borders.empty(8, 4, 8, 4)
    }

    var inRollback = false
    for ((index, node) in result.nodes.withIndex()) {
        if (node.symbol == NodeSymbol.ROLLBACK_START) inRollback = true

        val nodePanel = createNodePanel(node, inRollback, isChinese)
        timelinePanel.add(nodePanel)

        if (node.symbol == NodeSymbol.ROLLBACK_END) inRollback = false

        if (index < result.nodes.size - 1) {
            val connector = JBLabel("   │").apply {
                foreground = JBColor(0x999999.toInt(), 0x555555)
                font = JBUI.Fonts.label(12f)
                border = JBUI.Borders.emptyLeft(4)
            }
            timelinePanel.add(connector)
        }
    }

    val scrollPane = JBScrollPane(
        timelinePanel,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
    ).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
    }
    mainPanel.add(scrollPane, BorderLayout.CENTER)

    val mainCount = result.nodes.count { it.symbol == NodeSymbol.MAIN || it.symbol == NodeSymbol.ROLLBACK_START || it.symbol == NodeSymbol.ROLLBACK_END }
    val sideCount = result.nodes.count { it.symbol == NodeSymbol.SIDE }
    val status = ImuxBundle.message(if (stale) "action.overview.status.stale" else "action.overview.status.latest")
    val statsText = if (isAllSide) {
        ImuxBundle.message("action.overview.stats.qa", result.nodes.size, status)
    } else {
        ImuxBundle.message("action.overview.stats", result.nodes.size, mainCount, sideCount, status)
    }
    val footer = JBLabel(statsText).apply {
        foreground = JBColor(0x999999.toInt(), 0x686868)
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.empty(4, 8)
        horizontalAlignment = SwingConstants.CENTER
    }
    val footerPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor(0xDDDDDD.toInt(), 0x3C3F41))
        add(footer, BorderLayout.CENTER)
    }
    mainPanel.add(footerPanel, BorderLayout.SOUTH)

    return mainPanel
}

private fun regenerateLink(onRegenerate: () -> Unit) =
    ActionLink(ImuxBundle.message("action.overview.retry")).apply { addActionListener { onRegenerate() } }

private fun statusBanner(result: OverviewResult, stale: Boolean, onRegenerate: () -> Unit): JComponent? {
    val isAllSide = result.nodes.isNotEmpty() && result.nodes.all { it.symbol == NodeSymbol.SIDE }
    if (result.saveFailed) {
        val bannerPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            isOpaque = true
            background = JBColor(0xFDECEA.toInt(), 0x3C1F1F)
            border = JBUI.Borders.empty(4, 8)
            add(JBLabel(AllIcons.General.Warning))
            add(JBLabel(ImuxBundle.message("action.overview.save.failed")))
            add(regenerateLink(onRegenerate))
        }
        return bannerPanel
    } else if (stale) {
        val bannerPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            isOpaque = true
            background = JBColor(0xFFF8E1.toInt(), 0x3B3820)
            border = JBUI.Borders.empty(4, 8)
            add(JBLabel(AllIcons.General.Warning))
            add(JBLabel(ImuxBundle.message("action.overview.stale")))
            add(regenerateLink(onRegenerate))
        }
        return bannerPanel
    } else if (isAllSide) {
        val bannerPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
            isOpaque = true
            background = JBColor(0xE3F2FD.toInt(), 0x1E3A5F)
            border = JBUI.Borders.empty(4, 8)
            add(JBLabel(AllIcons.General.Information))
            add(JBLabel(ImuxBundle.message("action.overview.qa.hint")))
        }
        return bannerPanel
    }

    return null
}

private const val MAX_TITLE_SESSION_CHARS = 40

internal fun overviewDialogTitle(base: String, sessionTitle: String?): String {
    val name = sessionTitle?.trim()?.replace(Regex("\\s+"), " ")?.takeIf(String::isNotEmpty) ?: return base
    val shown = if (name.length > MAX_TITLE_SESSION_CHARS) name.take(MAX_TITLE_SESSION_CHARS - 1) + "…" else name
    return "$base — $shown"
}

/**
 * 宽度跟随 viewport 的面板。
 */
internal class ScrollablePanel : JPanel(), Scrollable {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    override fun getPreferredScrollableViewportSize(): java.awt.Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = JBUI.scale(20)
    override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = visibleRect.height
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}

internal fun createNodePanel(node: OverviewNode, inRollback: Boolean, isChinese: Boolean): JPanel {
    val symbol = when (node.symbol) {
        NodeSymbol.MAIN -> if (inRollback) "╳" else "●"
        NodeSymbol.SIDE -> "○"
        NodeSymbol.ROLLBACK_START -> "┌─"
        NodeSymbol.ROLLBACK_END -> "└─"
        NodeSymbol.CHANGE -> "↻"
        NodeSymbol.ERROR -> "⚠"
        NodeSymbol.IN_PROGRESS -> "◐"
    }

    val symbolColor = when (node.symbol) {
        NodeSymbol.MAIN -> if (inRollback) JBColor(0xAA5555.toInt(), 0x8B4513) else JBColor(0x333333.toInt(), 0xBBBBBB.toInt())
        NodeSymbol.SIDE -> JBColor(0x999999.toInt(), 0x787878)
        NodeSymbol.ROLLBACK_START, NodeSymbol.ROLLBACK_END -> JBColor(0xAA5555.toInt(), 0x8B4513)
        NodeSymbol.CHANGE -> JBColor(0xCC8800.toInt(), 0xD4A843.toInt())
        NodeSymbol.ERROR -> JBColor(0xCC0000.toInt(), 0xC75450)
        NodeSymbol.IN_PROGRESS -> JBColor(0x336699.toInt(), 0x6897BB.toInt())
    }

    val textColor = when {
        inRollback -> JBColor(0xAAAAAA.toInt(), 0x666666)
        node.symbol == NodeSymbol.SIDE -> JBColor(0x999999.toInt(), 0x787878)
        node.symbol == NodeSymbol.CHANGE -> JBColor(0xCC8800.toInt(), 0xD4A843.toInt())
        node.symbol == NodeSymbol.ERROR -> JBColor(0xCC0000.toInt(), 0xC75450)
        else -> JBColor(0x333333.toInt(), 0xBBBBBB.toInt())
    }

    return JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(1, 4, 1, 4)

        val symbolLabel = JBLabel("   $symbol").apply {
            foreground = symbolColor
            font = JBUI.Fonts.label(12f)
            verticalAlignment = SwingConstants.TOP
            border = JBUI.Borders.emptyRight(4)
        }
        add(symbolLabel, BorderLayout.WEST)

        val textPane = JTextPane().apply {
            text = node.text
            isEditable = false
            isOpaque = false
            foreground = textColor
            font = JBUI.Fonts.label(12f)
            border = JBUI.Borders.empty()
            val attrs = SimpleAttributeSet()
            StyleConstants.setLineSpacing(attrs, 0.2f)
            styledDocument.setParagraphAttributes(0, document.length, attrs, false)
        }
        add(textPane, BorderLayout.CENTER)

        if (node.symbol == NodeSymbol.ROLLBACK_START) {
            val tag = JBLabel(if (isChinese) "已回滚" else "rolled back").apply {
                foreground = JBColor(0xAA5555.toInt(), 0x8B4513)
                font = JBUI.Fonts.smallFont()
                verticalAlignment = SwingConstants.TOP
                border = JBUI.Borders.emptyLeft(8)
            }
            add(tag, BorderLayout.EAST)
        }
    }
}

private val LOG = logger<ProgressOverviewService>()
