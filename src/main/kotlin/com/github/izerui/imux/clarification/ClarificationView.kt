package com.github.izerui.imux.clarification

import com.github.izerui.imux.ImuxBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.plus
import com.intellij.ui.dsl.builder.rows
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

/** 视图里用到的文案来源；默认按插件语言取，预览测试可指定语言。 */
internal fun interface ViewMessages {
    fun get(key: String, vararg params: Any): String
}

internal val pluginMessages = ViewMessages { key, params -> ImuxBundle.message(key, *params) }

/** 视图内的链接、内联按钮转给调用方；对话框底部按钮不在视图里。 */
internal interface ClarificationViewCallbacks {
    fun editRequirement()

    fun skipDirection(id: String)

    fun pinDirection(id: String)

    fun reopenDirection(id: String)

    fun addDirection()

    fun showAgentOutput(raw: String)
}

/**
 * 需求澄清窗口的内容区：只依据（状态、台账）构建与更新组件，不依赖 Project 与服务，
 * 底部按钮由对话框按 [buttonBar] 显示。
 *
 * 头部（需求 + 方向清单）只在需求或方向变化时重建；中间区域只在 [needsCenterRebuild] 为真时重建，
 * 其余变化（批次缩短、调查进度、发送原因、已用秒数）就地更新，用户正在输入的内容不丢。
 */
internal class ClarificationView(
    private val callbacks: ClarificationViewCallbacks,
    private val messages: ViewMessages = pluginMessages,
) {
    private val header = JPanel(BorderLayout()).apply { isOpaque = false }
    private val body = JPanel(BorderLayout()).apply { isOpaque = false }
    private val content =
        FillViewportPanel().apply {
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }

    /** 整个内容区放在滚动面板里：窗口缩小时内容滚动，底部按钮栏始终可见。 */
    val component: JBScrollPane =
        JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
            viewportBorder = null
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

    /** 方向清单是否展开，视图生命期内记住。 */
    private var listExpanded = false
    private var headerKey: Any? = null
    private var renderedState: ClarificationState? = null

    // ---- 各页保持的组件，供对话框读取输入与就地更新 ----
    private var inputArea: JBTextArea? = null
    private var backgroundCheck: JBCheckBox? = null
    private var backgroundRow: Row? = null
    private var backgroundAvailable = false
    private var progressText: JEditorPane? = null
    private var elapsedComment: JEditorPane? = null
    private var investigationKind = InvestigationKind.ROUND
    private var investigationStartedAt = 0L
    private var sendingLabel: JBLabel? = null
    private var resultArea: JBTextArea? = null
    internal var asking: AskingWidgets? = null
        private set

    /** 提问页的可变部件；批次缩短时就地更新，这些实例保持不变。 */
    internal class AskingWidgets(
        val header: JEditorPane,
        val radios: List<JBRadioButton>,
        val otherRadio: JBRadioButton,
        val otherField: JBTextField,
        val correctionArea: JBTextArea?,
        val correctionRow: Row?,
    ) {
        var correctionVisible = false
    }

    fun render(
        state: ClarificationState,
        ledger: Ledger,
        canFinish: Boolean,
        inputPrefill: String,
    ) {
        val isInput = state is ClarificationState.Input
        renderHeader(ledger, isInput)
        val old = renderedState
        renderedState = state
        if (!needsCenterRebuild(old, state)) {
            updateInPlace(state)
            return
        }
        clearWidgets()
        val page =
            when (state) {
                ClarificationState.Input -> inputPage(inputPrefill)
                is ClarificationState.Investigating -> investigatingPage(state, investigationKind(canFinish, ledger))
                is ClarificationState.Asking -> askingPage(state, ledger)
                is ClarificationState.Result -> resultPage(state.requirement)
                is ClarificationState.Sending -> sendingPage(state)
                is ClarificationState.Failed -> failedPage(state)
            }
        body.removeAll()
        body.add(page, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
    }

    /** 展开或收起方向清单（与点击折叠标题相同），下次渲染头部时生效。 */
    internal fun setListExpanded(expanded: Boolean) {
        listExpanded = expanded
        headerKey = null
    }

    fun isShowingInput(): Boolean = renderedState is ClarificationState.Input

    /** 已用秒数：由对话框的计时器每秒调用，只改这一行文字。 */
    fun tick(nowMillis: Long) {
        elapsedComment?.text = elapsedText(nowMillis)
    }

    fun setBackgroundAvailable(available: Boolean) {
        backgroundAvailable = available
        backgroundRow?.visible(available)
        backgroundCheck?.isSelected = available
    }

    fun inputText(): String = inputArea?.text.orEmpty()

    fun includeBackground(): Boolean = backgroundAvailable && backgroundCheck?.isSelected == true

    fun resultText(): String = resultArea?.text.orEmpty()

    /** 当前选中的回答；什么都没选（或"其他想法"没写字）时返回 null。 */
    fun currentAnswer(): Answer? {
        val widgets = asking ?: return null
        val question = (renderedState as? ClarificationState.Asking)?.question ?: return null
        if (widgets.otherRadio.isSelected) return widgets.otherField.text.takeIf { it.isNotBlank() }?.let { Answer.OwnWords(it) }
        val picked = widgets.radios.indexOfFirst { it.isSelected }
        return if (picked >= 0) Answer.Picked(question.options[picked].id) else null
    }

    fun correctionText(): String? {
        val widgets = asking ?: return null
        return widgets.correctionArea?.text?.takeIf { widgets.correctionVisible && it.isNotBlank() }
    }

    private fun clearWidgets() {
        inputArea = null
        backgroundCheck = null
        backgroundRow = null
        backgroundAvailable = false
        progressText = null
        elapsedComment = null
        sendingLabel = null
        resultArea = null
        asking = null
    }

    private fun updateInPlace(state: ClarificationState) {
        when (state) {
            is ClarificationState.Asking -> asking?.header?.text = questionHeaderLine(state.index, state.batch.size, messages)
            is ClarificationState.Investigating -> progressText?.text = progressLine(state.progress)
            is ClarificationState.Sending -> sendingLabel?.text = sendingReason(state)
            else -> Unit
        }
    }

    // ---- 头部：需求一行 + 方向清单 ----

    private fun renderHeader(ledger: Ledger, isInput: Boolean) {
        val key: Any = if (isInput) Unit else ledger.requirement to ledger.directions
        if (key == headerKey) return
        headerKey = key
        header.removeAll()
        if (!isInput) header.add(headerPanel(ledger), BorderLayout.CENTER)
        header.revalidate()
        header.repaint()
    }

    private fun headerPanel(ledger: Ledger): DialogPanel =
        panel {
            row {
                cell(EllipsisLabel(messages.get("clarify.header.requirement", ledger.requirement.replace(Regex("\\s+"), " ").trim())))
                    .applyToComponent { toolTipText = requirementTooltip(ledger.requirement) }
                    .resizableColumn()
                    .align(AlignX.FILL)
                link(messages.get("clarify.header.edit")) { callbacks.editRequirement() }
            }
            val list =
                collapsibleGroup(listTitle(ledger.directions, messages)) {
                    for (direction in ledger.directions) directionRow(direction)
                    row {
                        link(messages.get("clarify.directions.add")) { callbacks.addDirection() }
                            .applyToComponent { icon = AllIcons.General.Add }
                    }
                }
            list.expanded = listExpanded
            list.addExpandedListener { listExpanded = it }
            list.bottomGap(BottomGap.SMALL)
        }

    private fun Panel.directionRow(direction: Direction) {
        val dropped = direction.status == DirectionStatus.DROPPED
        val detail =
            when (direction.status) {
                DirectionStatus.DROPPED -> direction.dropReason
                DirectionStatus.DECIDED, DirectionStatus.DEFAULTED -> direction.conclusion
                else -> null
            }
        row {
            label(statusWord(direction.status))
                .widthGroup(STATUS_WIDTH_GROUP)
                .align(AlignY.TOP)
                .applyToComponent { if (dropped) foreground = UIUtil.getContextHelpForeground() }
            val title =
                text(html(direction.title), MAX_LINE_LENGTH_WORD_WRAP)
                    .resizableColumn()
                    .align(AlignX.FILL)
                    .applyToComponent { if (dropped) foreground = UIUtil.getContextHelpForeground() }
            if (!detail.isNullOrBlank()) title.comment(html(detail), MAX_LINE_LENGTH_WORD_WRAP)
            val actions = directionRowActions(direction.status)
            if (DirectionAction.SKIP in actions) {
                link(messages.get("clarify.directions.skip")) { callbacks.skipDirection(direction.id) }
                    .align(AlignY.TOP)
                    .applyToComponent { toolTipText = messages.get("clarify.directions.skip.tooltip") }
            }
            if (DirectionAction.PIN in actions) link(messages.get("clarify.directions.pin")) { callbacks.pinDirection(direction.id) }.align(AlignY.TOP)
            if (DirectionAction.REOPEN in actions) link(messages.get("clarify.directions.reopen")) { callbacks.reopenDirection(direction.id) }.align(AlignY.TOP)
        }
    }

    private fun statusWord(status: DirectionStatus): String =
        messages.get(
            when (status) {
                DirectionStatus.OPEN -> "clarify.status.open"
                DirectionStatus.ACTIVE -> "clarify.status.active"
                DirectionStatus.DECIDED -> "clarify.status.decided"
                DirectionStatus.DEFAULTED -> "clarify.status.defaulted"
                DirectionStatus.DROPPED -> "clarify.status.dropped"
            },
        )

    // ---- 各状态的中间区域 ----

    private fun inputPage(prefill: String): DialogPanel {
        val check = JBCheckBox(messages.get("clarify.input.background"), false)
        backgroundCheck = check
        return panel {
            row { label(messages.get("clarify.input.title")).bold() }
            row { comment(messages.get("clarify.input.comment"), MAX_LINE_LENGTH_WORD_WRAP).resizableColumn().align(AlignX.FILL) }
            row { inputArea = multiline(prefill, 5).applyToComponent { emptyText.text = messages.get("clarify.input.placeholder") }.component }
                .resizableRow()
                .topGap(TopGap.SMALL)
            backgroundRow = row { cell(check) }.visible(false)
        }
    }

    private fun investigatingPage(state: ClarificationState.Investigating, kind: InvestigationKind): DialogPanel {
        investigationKind = kind
        investigationStartedAt = state.startedAt
        return panel {
            row {
                icon(AnimatedIcon.Default.INSTANCE).align(AlignY.TOP).gap(RightGap.SMALL)
                val progress =
                    text(progressLine(state.progress), MAX_LINE_LENGTH_WORD_WRAP)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment(elapsedText(System.currentTimeMillis()), MAX_LINE_LENGTH_WORD_WRAP)
                progressText = progress.component
                elapsedComment = progress.comment
            }
        }
    }

    private fun progressLine(progress: String?): String {
        val key = if (investigationKind == InvestigationKind.RESULT) "clarify.investigating.result" else "clarify.investigating"
        return html(messages.get(key, progress.orEmpty()).trim())
    }

    private fun elapsedText(nowMillis: Long): String {
        val seconds = ((nowMillis - investigationStartedAt) / 1_000).coerceAtLeast(0)
        val key =
            when (investigationKind) {
                InvestigationKind.FIRST_ROUND -> "clarify.elapsed.first"
                InvestigationKind.ROUND -> "clarify.elapsed.round"
                InvestigationKind.RESULT -> "clarify.elapsed.result"
            }
        return messages.get(key, seconds)
    }

    private fun askingPage(state: ClarificationState.Asking, ledger: Ledger): DialogPanel {
        val question = state.question
        val understanding = ledger.understanding?.takeIf { state.index == 0 && (ledger.history.isEmpty() || ledger.corrections.isNotEmpty()) }
        var correction: JBTextArea? = null
        var correctionRow: Row? = null
        lateinit var headerComment: JEditorPane
        val radios = mutableListOf<JBRadioButton>()
        lateinit var otherRadio: JBRadioButton
        val otherField = JBTextField()
        val page =
            panel {
                if (understanding != null) {
                    collapsibleGroup(messages.get("clarify.understanding.title")) {
                        row { text(html(understanding), MAX_LINE_LENGTH_WORD_WRAP).resizableColumn().align(AlignX.FILL) }
                        row {
                            button(messages.get("clarify.understanding.correct")) {
                                val widgets = asking ?: return@button
                                widgets.correctionVisible = !widgets.correctionVisible
                                widgets.correctionRow?.visible(widgets.correctionVisible)
                                if (widgets.correctionVisible) widgets.correctionArea?.requestFocusInWindow()
                            }
                        }
                        correctionRow =
                            row {
                                correction =
                                    multiline("", 3)
                                        .applyToComponent { emptyText.text = messages.get("clarify.understanding.correct.placeholder") }
                                        .component
                            }.visible(false)
                    }.apply { expanded = true }
                }
                row { headerComment = comment(questionHeaderLine(state.index, state.batch.size, messages)).component }
                row { text(html(question.text), MAX_LINE_LENGTH_WORD_WRAP).bold().resizableColumn().align(AlignX.FILL) }
                whyLine(question)?.let { why ->
                    row { comment(why, MAX_LINE_LENGTH_WORD_WRAP).resizableColumn().align(AlignX.FILL) }
                }
                buttonsGroup {
                    question.options.forEachIndexed { i, option ->
                        val label = if (option.recommended) messages.get("clarify.question.recommended", option.label) else option.label
                        row {
                            radios +=
                                cell(WrappingRadioButton(label))
                                    .comment(html(messages.get("clarify.question.becomes", option.conclusion)), MAX_LINE_LENGTH_WORD_WRAP)
                                    .resizableColumn()
                                    .align(AlignX.FILL)
                                    .applyToComponent { isSelected = option.recommended }
                                    .component
                        }.topGap(if (i == 0) TopGap.SMALL else TopGap.NONE)
                    }
                    row {
                        otherRadio = cell(JBRadioButton(messages.get("clarify.question.other"))).component
                        cell(otherField).resizableColumn().align(AlignX.FILL)
                    }
                }
            }
        otherField.document.addDocumentListener(
            object : com.intellij.ui.DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (otherField.text.isNotEmpty()) otherRadio.isSelected = true
                }
            },
        )
        asking = AskingWidgets(headerComment, radios, otherRadio, otherField, correction, correctionRow)
        return page
    }

    /** UI DSL 自带的多行输入框（带内边距、放在滚动面板里），按宽度换行，填满所在的行。 */
    private fun Row.multiline(text: String, rows: Int) =
        textArea()
            .rows(rows)
            .align(Align.FILL)
            .applyToComponent {
                lineWrap = true
                wrapStyleWord = true
                this.text = text
                caretPosition = 0
            }

    private fun whyLine(question: Question): String? {
        val lines =
            listOfNotNull(
                question.reason.takeIf { it.isNotBlank() }?.let { messages.get("clarify.question.why", it) },
                question.basis.takeIf { it.isNotBlank() }?.let { messages.get("clarify.question.basis", it) },
            )
        return if (lines.isEmpty()) null else lines.joinToString("<br>") { html(it) }
    }

    private fun resultPage(requirement: String): DialogPanel {
        return panel {
            row { label(messages.get("clarify.result.title")).bold() }
            row { resultArea = multiline(requirement, 8).component }.resizableRow()
            row { comment(messages.get("clarify.result.comment"), MAX_LINE_LENGTH_WORD_WRAP).resizableColumn().align(AlignX.FILL) }
        }
    }

    private fun sendingPage(state: ClarificationState.Sending): DialogPanel {
        return panel {
            row {
                icon(AnimatedIcon.Default.INSTANCE).gap(RightGap.SMALL)
                sendingLabel = JBLabel(sendingReason(state)).also { cell(it) }
            }
            row { multiline(state.requirement, 8).applyToComponent { isEditable = false } }.resizableRow()
        }
    }

    private fun sendingReason(state: ClarificationState.Sending): String =
        messages.get(if (state.waitingForTurn) "clarify.sending.turn" else "clarify.sending")

    private fun failedPage(state: ClarificationState.Failed): DialogPanel {
        val message =
            when (state.reason) {
                FailureReason.CANCELLED -> messages.get("clarify.failed.cancelled")
                FailureReason.TIMEOUT -> messages.get("clarify.failed.timeout", state.detail)
                FailureReason.PROTOCOL -> messages.get("clarify.failed.protocol")
                FailureReason.AGENT_ERROR -> messages.get("clarify.failed.error", state.detail)
            }
        return panel {
            row {
                icon(AllIcons.General.Warning).align(AlignY.TOP).gap(RightGap.SMALL)
                text(html(message), MAX_LINE_LENGTH_WORD_WRAP)
                    .resizableColumn()
                    .align(AlignX.FILL)
                    .comment(messages.get("clarify.failed.hint"), MAX_LINE_LENGTH_WORD_WRAP)
            }.layout(RowLayout.PARENT_GRID)
            state.raw?.let { raw ->
                // 第一列留空，链接与上面的文字对齐
                row {
                    cell()
                    link(messages.get("clarify.failed.raw")) { callbacks.showAgentOutput(raw) }
                }.layout(RowLayout.PARENT_GRID)
            }
        }
    }
}

private const val STATUS_WIDTH_GROUP = "directionStatusWord"

/** 调查的种类：决定进度文案，以及「够了，生成结果」是否出现。 */
internal enum class InvestigationKind { FIRST_ROUND, ROUND, RESULT }

/** 不可生成结果说明正在生成结果；还没有任何方向与问答说明是第一轮。 */
internal fun investigationKind(canFinish: Boolean, ledger: Ledger): InvestigationKind =
    when {
        !canFinish -> InvestigationKind.RESULT
        ledger.directions.isEmpty() && ledger.history.isEmpty() -> InvestigationKind.FIRST_ROUND
        else -> InvestigationKind.ROUND
    }

/** "问题 i/n"，一批多于一题时接上"这一批不用再查代码"的说明。index 从 0 起。 */
internal fun questionHeaderLine(index: Int, total: Int, messages: ViewMessages = pluginMessages): String {
    val head = messages.get("clarify.question.header", index + 1, total)
    return if (total > 1) head + " · " + messages.get("clarify.question.batch.note") else head
}

/** 方向清单的折叠标题："方向清单（已定 N · 待定 N · 跳过 N[ · 已去掉 N]）"。 */
internal fun listTitle(directions: List<Direction>, messages: ViewMessages = pluginMessages): String {
    val counts = progressCounts(directions)
    val progress =
        buildString {
            append(messages.get("clarify.progress", counts.decided, counts.open, counts.defaulted))
            // 属性文件会去掉值开头的空格，分隔符在这里补上
            if (counts.dropped > 0) append(" · ").append(messages.get("clarify.progress.dropped", counts.dropped))
        }
    return messages.get("clarify.list.title", progress)
}

/** 交给 UI DSL 的文字按 HTML 显示：转义并保留换行。 */
private fun html(text: String): String = StringUtil.escapeXmlEntities(text).replace("\n", "<br>")

/** 宽度不够时省略显示的单行标签：首选宽度不撑大窗口，完整内容看 tooltip。 */
private class EllipsisLabel(text: String) : JBLabel(text) {
    override fun getPreferredSize(): Dimension = super.getPreferredSize().also { it.width = minimumSize.width }

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(MIN_ELLIPSIS_WIDTH), super.getMinimumSize().height)

    companion object {
        private const val MIN_ELLIPSIS_WIDTH = 60
    }
}

/**
 * 选项文字较长时按可用宽度换行的单选按钮。JRadioButton 本身不换行，这里用 HTML 文本，
 * 首选高度按当前宽度计算；首选宽度不超过所在列，避免把内容撑出窗口。
 */
internal class WrappingRadioButton(private val plain: String) : JBRadioButton() {
    init {
        text = "<html>" + html(plain) + "</html>"
    }

    override fun getPreferredSize(): Dimension {
        val natural = super.getPreferredSize()
        val view = getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) as? javax.swing.text.View ?: return natural
        if (width <= 0) return natural
        val chrome = natural.width - view.getPreferredSpan(javax.swing.text.View.X_AXIS).toInt()
        val available = (width - chrome).coerceAtLeast(1)
        if (available >= view.getPreferredSpan(javax.swing.text.View.X_AXIS)) return natural
        view.setSize(available.toFloat(), 0f)
        val textHeight = view.getPreferredSpan(javax.swing.text.View.Y_AXIS).toInt()
        val insets = insets
        return Dimension(width, maxOf(natural.height, textHeight + insets.top + insets.bottom))
    }

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(MIN_RADIO_WIDTH), super.getMinimumSize().height)

    companion object {
        private const val MIN_RADIO_WIDTH = 80
    }
}

/**
 * 滚动面板里的内容：宽度始终跟随视口（文字按窗口宽度换行）；视口比内容高时高度也跟随，
 * 让多行输入框随窗口拉伸；比内容矮时按首选高度出现滚动条。
 */
private class FillViewportPanel : JPanel(BorderLayout()), Scrollable {
    init {
        isOpaque = false
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = (parent as? JViewport)?.let { it.height > preferredSize.height } == true
}
