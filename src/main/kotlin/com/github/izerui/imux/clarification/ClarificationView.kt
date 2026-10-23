package com.github.izerui.imux.clarification

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Container
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.ItemEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/** 视图里用到的文案来源；默认按插件语言取，预览测试可指定语言。 */
internal fun interface ViewMessages {
    fun get(key: String, vararg params: Any): String
}

internal val pluginMessages = ViewMessages { key, params -> ImuxBundle.message(key, *params) }

/** 视图转给对话框的事；对话框底部按钮不在视图里。 */
internal interface ClarificationViewCallbacks {
    /** 这一批的草稿；对话框把它存在流程里，窗口关了再打开时接着用。 */
    fun draftsFor(batch: List<Question>): BatchDrafts

    /** 提问页换了标签（另一题或提交页），按钮栏要跟着换。 */
    fun askingPageChanged()

    fun showAgentOutput(raw: String)
}

/** 一题还没提交的回答。 */
internal class DraftAnswer {
    val picked = linkedSetOf<String>()
    var other = false
    var otherText = ""

    /** 填写完整时的回答；什么都没选、或选了"其他"却没写字时为 null（提交时按不回答处理）。 */
    fun toAnswer(question: Question): Answer? {
        val own = otherText.trim().takeIf { other && it.isNotEmpty() }
        val ids = question.options.map { it.id }.filter { it in picked }
        return when {
            !question.multiSelect && own != null -> Answer.OwnWords(own)
            !question.multiSelect -> ids.singleOrNull()?.let { Answer.Picked(listOf(it)) }
            ids.isEmpty() -> own?.let(Answer::OwnWords)
            else -> Answer.Picked(ids, own)
        }
    }
}

/** 一批问题的草稿与当前标签（0..n-1 是各题，n 是提交页）。只在 EDT 上读写。 */
internal class BatchDrafts(val batch: List<Question>) {
    val answers: Map<String, DraftAnswer> = batch.associate { it.id to DraftAnswer() }
    var tab = 0

    fun draftOf(question: Question): DraftAnswer = answers.getValue(question.id)

    /** 已填写的回答，按题目 ID；没填写的不在其中。 */
    fun filled(): Map<String, Answer> =
        batch.mapNotNull { q -> draftOf(q).toAnswer(q)?.let { q.id to it } }.toMap()

    val page: AskingPage
        get() =
            when {
                batch.size == 1 -> AskingPage.SINGLE
                tab >= batch.size -> AskingPage.REVIEW
                tab == batch.lastIndex -> AskingPage.LAST_QUESTION
                else -> AskingPage.QUESTION
            }
}

/** 提问页当前停在哪：只有一题、某一题、最后一题、提交页。决定主按钮。 */
internal enum class AskingPage { SINGLE, QUESTION, LAST_QUESTION, REVIEW }

/**
 * 需求澄清窗口的内容区：只依据（状态、澄清记录）构建与更新组件，不依赖 Project 与服务，
 * 底部按钮由对话框按 [buttonBar] 显示。
 *
 * 不反复渲染：头部（需求与已确认）只在内容变化时重建；中间区域只在 [needsCenterRebuild] 为真时重建，
 * 调查进度、已用秒数、发送原因只改各自的标签；提问页里只有当前题目区域随换标签重建，
 * 回答存在 [BatchDrafts] 里，切回来原样恢复。三处重建各有计数，供测试断言。
 */
internal class ClarificationView(
    private val callbacks: ClarificationViewCallbacks,
    private val messages: ViewMessages = pluginMessages,
) {
    private val header = JPanel(BorderLayout()).apply { isOpaque = false }
    private val body = JPanel(BorderLayout()).apply { isOpaque = false }
    private val content =
        FillViewportPanel().apply {
            // 内边距放在滚动内容上而不是滚动面板上：窗口边缘与内容之间始终留白，滚动条（New UI 下浮在内容上）落在留白里
            border = JBUI.Borders.empty(CONTENT_PADDING_V, CONTENT_PADDING_H)
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

    // ---- 重建计数（测试用） ----
    internal var headerRebuilds = 0
        private set
    internal var centerRebuilds = 0
        private set
    internal var questionRebuilds = 0
        private set

    private var headerKey: Any? = null
    private var renderedState: ClarificationState? = null

    // ---- 各页保持的组件，供对话框读取输入与就地更新 ----
    private var inputArea: JBTextArea? = null
    private var backgroundCheck: JBCheckBox? = null
    private var backgroundRow: Row? = null
    private var backgroundAvailable = false
    private var agentCombo: ComboBox<AgentType>? = null
    private var enabledAgentTypes: List<AgentType> = emptyList()
    private var defaultAgentType: AgentType? = null
    private var progressLabel: JBLabel? = null
    private var elapsedComment: JEditorPane? = null
    private var investigationKind = InvestigationKind.ROUND
    private var investigationStartedAt = 0L
    private var sendingLabel: JBLabel? = null
    private var resultArea: JBTextArea? = null

    // ---- 提问页 ----
    private var drafts: BatchDrafts? = null
    internal var tabs: JBTabbedPane? = null
        private set
    private val questionArea = JPanel(BorderLayout()).apply { isOpaque = false }
    private val unansweredIcon by lazy { EmptyIcon.create(AllIcons.General.GreenCheckmark) }
    internal var options: OptionWidgets? = null
        private set

    /** 当前题目的选项按钮与"其他"。 */
    internal class OptionWidgets(val buttons: List<JToggleButton>, val otherButton: JToggleButton, val otherField: JBTextField)

    init {
        // 数字键选择选项、←/→ 换标签：焦点在内容区任何位置（输入框除外）都可用
        val map = component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        for (n in 1..9) {
            map.put(KeyStroke.getKeyStroke(KeyEvent.VK_0 + n, 0), "imux.choose$n")
            map.put(KeyStroke.getKeyStroke(KeyEvent.VK_NUMPAD0 + n, 0), "imux.choose$n")
            component.actionMap.put("imux.choose$n", keyAction { if (!typingInText()) choose(n - 1) })
        }
        installTabKeys(component)
    }

    fun render(
        state: ClarificationState,
        record: ClarificationRecord,
        inputPrefill: String,
    ) {
        renderHeader(state, record)
        val old = renderedState
        renderedState = state
        if (!needsCenterRebuild(old, state)) {
            updateInPlace(state)
            return
        }
        centerRebuilds++
        clearWidgets()
        val page =
            when (state) {
                ClarificationState.Input -> inputPage(inputPrefill)
                is ClarificationState.Investigating -> investigatingPage(state, investigationKind(record))
                is ClarificationState.Asking -> askingPage(state)
                is ClarificationState.Result -> resultPage(state.requirement)
                is ClarificationState.Sending -> sendingPage(state)
                is ClarificationState.Failed -> failedPage(state)
            }
        body.removeAll()
        body.add(page, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
        if (state is ClarificationState.Asking) focusCurrent()
    }

    /**
     * 当前页在给定宽度下需要的高度（含内边距）。自动换行的文字的首选高度取决于上一次布局给的宽度，
     * 所以按这个宽度布局几遍再取首选高度；只在打开窗口时算一次初始尺寸用，不需要窗口 peer。
     */
    fun preferredContentHeight(width: Int): Int {
        content.setSize(width, content.height.coerceAtLeast(1))
        repeat(3) { layoutTree(content) }
        return content.preferredSize.height
    }

    private fun layoutTree(c: java.awt.Component) {
        if (c is Container) {
            c.doLayout()
            c.components.forEach(::layoutTree)
        }
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

    /** 用户在输入页选的 Agent 类型；没有 combo（只有一种）或不在输入页时为 null。 */
    fun selectedAgentType(): AgentType? = agentCombo?.selectedItem as? AgentType

    /** 设置可用的 Agent 类型和默认选中项；由对话框在创建视图后调用。 */
    fun setAgentTypes(enabled: List<AgentType>, default: AgentType) {
        enabledAgentTypes = enabled
        defaultAgentType = default
    }

    fun resultText(): String = resultArea?.text.orEmpty()

    /** 提问页停在哪；不在提问页时为 null。 */
    fun askingPage(): AskingPage? = drafts?.takeIf { renderedState is ClarificationState.Asking }?.page

    /** 这一批已填写的回答。 */
    fun answers(): Map<String, Answer> = drafts?.takeIf { renderedState is ClarificationState.Asking }?.filled().orEmpty()

    /** 下一题，或从最后一题到提交页。 */
    fun nextTab() {
        selectTab((drafts?.tab ?: return) + 1)
    }

    fun previousTab() {
        selectTab((drafts?.tab ?: return) - 1)
    }

    fun selectTab(index: Int) {
        val strip = tabs ?: return
        if (index in 0 until strip.tabCount) strip.selectedIndex = index
    }

    private fun clearWidgets() {
        inputArea = null
        backgroundCheck = null
        backgroundRow = null
        backgroundAvailable = false
        progressLabel = null
        elapsedComment = null
        sendingLabel = null
        resultArea = null
        drafts = null
        tabs = null
        options = null
        questionArea.removeAll()
    }

    private fun updateInPlace(state: ClarificationState) {
        when (state) {
            is ClarificationState.Investigating -> progressLabel?.let { setProgress(it, state.progress) }
            is ClarificationState.Sending -> sendingLabel?.text = sendingReason(state)
            else -> Unit
        }
    }

    // ---- 头部：目标一行 ----

    private fun renderHeader(state: ClarificationState, record: ClarificationRecord) {
        val shown = state is ClarificationState.Investigating || state is ClarificationState.Asking || state is ClarificationState.Failed
        val key: Any = if (shown) record.requirement else Unit
        if (key == headerKey) return
        headerKey = key
        headerRebuilds++
        header.removeAll()
        if (shown) header.add(headerPanel(record.requirement), BorderLayout.CENTER)
        header.revalidate()
        header.repaint()
    }

    private fun headerPanel(requirement: String): DialogPanel =
        panel {
            row {
                cell(EllipsisLabel(messages.get("clarify.header.requirement", requirement.replace(Regex("\\s+"), " ").trim())))
                    .applyToComponent { toolTipText = requirementTooltip(requirement) }
                    .resizableColumn()
                    .align(AlignX.FILL)
            }
            separator()
        }

    // ---- 各状态的中间区域 ----

    private fun inputPage(prefill: String): DialogPanel {
        val check = JBCheckBox(messages.get("clarify.input.background"), false)
        backgroundCheck = check
        val combo =
            if (enabledAgentTypes.size > 1) {
                ComboBox(enabledAgentTypes.toTypedArray()).apply {
                    renderer = javax.swing.DefaultListCellRenderer().let { base ->
                        javax.swing.ListCellRenderer<Any?> { list, value, index, sel, focus ->
                            base.getListCellRendererComponent(list, value, index, sel, focus).also {
                                if (value is AgentType) (it as? JLabel)?.text = value.displayName
                            }
                        }
                    }
                    selectedItem = defaultAgentType ?: enabledAgentTypes.first()
                }
            } else null
        agentCombo = combo
        return panel {
            row { label(messages.get("clarify.input.title")).bold() }
            row { comment(messages.get("clarify.input.comment"), MAX_LINE_LENGTH_WORD_WRAP).resizableColumn().align(AlignX.FILL) }
            row { inputArea = multiline(prefill, 5).applyToComponent { emptyText.text = messages.get("clarify.input.placeholder") }.component }
                .resizableRow()
                .topGap(TopGap.SMALL)
            if (combo != null) {
                row {
                    label(messages.get("clarify.input.agent.label"))
                    cell(combo)
                }
            }
            backgroundRow = row { cell(check) }.visible(false)
        }
    }

    private fun investigatingPage(state: ClarificationState.Investigating, kind: InvestigationKind): DialogPanel {
        investigationKind = kind
        investigationStartedAt = state.startedAt
        val label = EllipsisLabel("")
        setProgress(label, state.progress)
        progressLabel = label
        return panel {
            row {
                icon(AnimatedIcon.Default.INSTANCE).align(AlignY.TOP).gap(RightGap.SMALL)
                elapsedComment =
                    cell(label)
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .comment(elapsedText(System.currentTimeMillis()), MAX_LINE_LENGTH_WORD_WRAP)
                        .comment
            }
        }
    }

    /** 当前一步只占一行：长路径从中间省略，整行放不下再从末尾省略，完整内容看 tooltip。 */
    private fun setProgress(label: JBLabel, progress: String?) {
        label.text = messages.get("clarify.investigating", shortenPaths(progress.orEmpty())).trim()
        label.toolTipText = progress?.takeIf { it.isNotBlank() }
    }

    private fun elapsedText(nowMillis: Long): String {
        val seconds = ((nowMillis - investigationStartedAt) / 1_000).coerceAtLeast(0)
        val key =
            when (investigationKind) {
                InvestigationKind.FIRST_ROUND -> "clarify.elapsed.first"
                InvestigationKind.ROUND -> "clarify.elapsed.round"
            }
        return messages.get(key, seconds)
    }

    // ---- 提问页：标签条 + 当前题目（或提交页） ----

    private fun askingPage(state: ClarificationState.Asking): JComponent {
        val d = callbacks.draftsFor(state.batch)
        drafts = d
        val page = JPanel(BorderLayout()).apply { isOpaque = false }
        if (state.batch.size > 1) {
            val strip = tabStrip(d)
            tabs = strip
            page.add(strip, BorderLayout.NORTH)
            questionArea.border = JBUI.Borders.emptyTop(6)
        } else {
            d.tab = 0
            questionArea.border = null
        }
        page.add(questionArea, BorderLayout.CENTER)
        rebuildQuestionArea()
        return page
    }

    private fun tabStrip(d: BatchDrafts): JBTabbedPane {
        val strip = TabStrip()
        for (q in d.batch) {
            strip.addTab(truncateCodePoints(q.header, MAX_HEADER_CHARS), unansweredIcon, emptyTab(), headerTooltip(q))
        }
        // 提交页也占同样的图标位，各标签间距一致
        strip.addTab(messages.get("clarify.question.review.tab"), unansweredIcon, emptyTab(), null)
        d.batch.indices.forEach { updateTabMark(strip, d, it) }
        d.tab = d.tab.coerceIn(0, d.batch.size)
        strip.selectedIndex = d.tab
        strip.addChangeListener {
            val index = strip.selectedIndex
            if (index >= 0 && index != d.tab) {
                d.tab = index
                rebuildQuestionArea()
                focusCurrent()
                callbacks.askingPageChanged()
            }
        }
        return strip
    }

    /** 标签条只要一行标签，内容区由下面的题目区域承担。 */
    private fun emptyTab(): JComponent = JPanel().apply {
        isOpaque = false
        preferredSize = Dimension(0, 0)
    }

    /** 答过的题打 ✓；没答的放同样大小的空图标，标签不会因此左右跳动。 */
    private fun updateTabMark(strip: JBTabbedPane, d: BatchDrafts, index: Int) {
        val q = d.batch[index]
        val icon = if (d.draftOf(q).toAnswer(q) != null) AllIcons.General.GreenCheckmark else unansweredIcon
        if (strip.getIconAt(index) == icon) return
        strip.setIconAt(index, icon)
        // JBTabbedPane 用 JLabel 作标签组件显示标题，setIconAt 不会同步到它
        (strip.getTabComponentAt(index) as? JLabel)?.icon = icon
    }

    private fun rebuildQuestionArea() {
        val d = drafts ?: return
        questionRebuilds++
        options = null
        questionArea.removeAll()
        val panel = if (d.tab >= d.batch.size) reviewPanel(d) else questionPanel(d, d.batch[d.tab])
        questionArea.add(panel, BorderLayout.CENTER)
        questionArea.revalidate()
        questionArea.repaint()
    }

    private fun questionPanel(d: BatchDrafts, question: Question): DialogPanel {
        val draft = d.draftOf(question)
        val multi = question.multiSelect
        fun toggle(text: String): JToggleButton = if (multi) JBCheckBox(text) else JBRadioButton(text)
        val buttons =
            question.options.mapIndexed { i, option ->
                val label = truncateCodePoints(option.label, LABEL_CHARS)
                val text = "${i + 1}. " + if (option.recommended) messages.get("clarify.question.recommended", label) else label
                toggle(text).apply {
                    if (label != option.label) toolTipText = option.label
                    isSelected = option.id in draft.picked
                }
            }
        val otherButton = toggle("${question.options.size + 1}. " + messages.get("clarify.question.other")).apply { isSelected = draft.other }
        val otherField = JBTextField(draft.otherText)

        fun changed() {
            tabs?.let { updateTabMark(it, d, d.tab) }
        }
        buttons.forEachIndexed { i, button ->
            val id = question.options[i].id
            button.addItemListener { e ->
                if (e.stateChange == ItemEvent.SELECTED) {
                    if (!multi) {
                        draft.picked.clear()
                        draft.other = false
                    }
                    draft.picked += id
                } else {
                    draft.picked -= id
                }
                changed()
            }
        }
        otherButton.addItemListener { e ->
            draft.other = e.stateChange == ItemEvent.SELECTED
            if (draft.other && !multi) draft.picked.clear()
            // 选了"其他"就是要写字
            if (draft.other && !otherField.hasFocus()) otherField.requestFocusInWindow()
            changed()
        }
        otherField.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    draft.otherText = otherField.text
                    // 在"其他"里打字就是选了它
                    if (otherField.text.isNotEmpty() && !otherButton.isSelected) otherButton.isSelected = true else changed()
                }
            },
        )
        val all = buttons + otherButton
        all.forEachIndexed { i, button -> installOptionKeys(button, all, i, choosesOnEnter = !multi) }
        options = OptionWidgets(buttons, otherButton, otherField)

        val fallback = question.fallback
        return panel {
            row { text(html(question.text), MAX_LINE_LENGTH_WORD_WRAP).bold().resizableColumn().align(AlignX.FILL) }
            // 单选题放进 UI DSL 的按钮组（互斥由它负责），多选题是独立的复选框
            val optionRows: Panel.() -> Unit = {
                question.options.forEachIndexed { i, option ->
                    row { cell(buttons[i]).comment(html(option.description), MAX_LINE_LENGTH_WORD_WRAP) }
                        .topGap(if (i == 0) TopGap.SMALL else TopGap.NONE)
                }
                row {
                    cell(otherButton).gap(RightGap.SMALL)
                    cell(otherField).resizableColumn().align(AlignX.FILL)
                }
            }
            if (multi) optionRows() else buttonsGroup(init = optionRows)
            if (fallback.isNotBlank()) {
                row { comment(html(messages.get("clarify.question.unanswered", fallback)), MAX_LINE_LENGTH_WORD_WRAP).resizableColumn().align(AlignX.FILL) }
                    .topGap(TopGap.SMALL)
            }
        }
    }

    /** 提交页：逐题列出回答，点标签回到那一题修改。 */
    private fun reviewPanel(d: BatchDrafts): DialogPanel =
        panel {
            row { label(messages.get("clarify.review.title")).bold() }
            row { comment(messages.get("clarify.review.comment"), MAX_LINE_LENGTH_WORD_WRAP).resizableColumn().align(AlignX.FILL) }
            d.batch.forEachIndexed { i, q ->
                val answer = d.draftOf(q).toAnswer(q)
                row {
                    link(truncateCodePoints(q.header, MAX_HEADER_CHARS)) { selectTab(i) }
                        .align(AlignY.TOP)
                        .gap(RightGap.SMALL)
                        .applyToComponent { toolTipText = headerTooltip(q) }
                    if (answer != null) {
                        text(html(answerSummary(q, answer)), MAX_LINE_LENGTH_WORD_WRAP).resizableColumn().align(AlignX.FILL)
                    } else {
                        comment(html(messages.get("clarify.review.unanswered", q.fallback)), MAX_LINE_LENGTH_WORD_WRAP).resizableColumn().align(AlignX.FILL)
                    }
                }.layout(RowLayout.PARENT_GRID)
            }
        }

    private fun answerSummary(question: Question, answer: Answer): String =
        when (answer) {
            is Answer.Picked ->
                (question.options.filter { it.id in answer.optionIds }.map { it.label } + listOfNotNull(answer.other?.let { messages.get("clarify.review.other", it) }))
                    .joinToString(" · ")
            is Answer.OwnWords -> messages.get("clarify.review.other", answer.text)
            Answer.Skipped -> ""
        }

    /** 焦点落在当前题选中的选项上（没选就是第一个）；提交页落在标签条上，回车即提交。 */
    private fun focusCurrent() {
        val w = options
        if (w == null) {
            tabs?.requestFocusInWindow()
            return
        }
        ((w.buttons + w.otherButton).firstOrNull { it.isSelected } ?: w.buttons.firstOrNull())?.requestFocusInWindow()
    }

    /** 数字键：第 n 个选项（单选选中、多选勾选或取消）；最后一个数字是"其他"，并把焦点放进输入框。 */
    private fun choose(index: Int) {
        val w = options ?: return
        val multi = w.buttons.firstOrNull() is JBCheckBox
        when {
            index < w.buttons.size -> {
                val button = w.buttons[index]
                button.isSelected = !(multi && button.isSelected)
                button.requestFocusInWindow()
            }
            index == w.buttons.size -> {
                w.otherButton.isSelected = true
                w.otherField.requestFocusInWindow()
            }
        }
    }

    /**
     * 选项按钮上的键：←/→ 换标签，↑/↓ 在选项间移动焦点（不改选择）；单选题回车先选中焦点所在的选项，
     * 再按默认按钮。平台单选按钮自带的方向键行为在这里被覆盖。
     */
    private fun installOptionKeys(button: JToggleButton, all: List<JToggleButton>, index: Int, choosesOnEnter: Boolean) {
        installTabKeys(button)
        val map = button.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        map.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "imux.optionUp")
        map.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "imux.optionDown")
        button.actionMap.put("imux.optionUp", keyAction { all.getOrNull(index - 1)?.requestFocusInWindow() })
        button.actionMap.put("imux.optionDown", keyAction { all.getOrNull(index + 1)?.requestFocusInWindow() })
        if (choosesOnEnter) {
            button.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "imux.chooseAndGo")
            button.actionMap.put(
                "imux.chooseAndGo",
                keyAction {
                    button.isSelected = true
                    if (button === options?.otherButton) options?.otherField?.requestFocusInWindow()
                    else SwingUtilities.getRootPane(button)?.defaultButton?.takeIf { it.isShowing }?.doClick()
                },
            )
        }
    }

    private fun installTabKeys(target: JComponent) {
        val map = target.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        map.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "imux.previousTab")
        map.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "imux.nextTab")
        target.actionMap.put("imux.previousTab", keyAction { if (!typingInText()) previousTab() })
        target.actionMap.put("imux.nextTab", keyAction { if (!typingInText()) nextTab() })
    }

    private fun typingInText(): Boolean = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner is JTextComponent

    // ---- 结果、发送、失败 ----

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

    private fun resultPage(requirement: String): DialogPanel =
        panel {
            row { label(messages.get("clarify.result.title")).bold() }
            row { resultArea = multiline(requirement, 8).component }.resizableRow()
        }

    private fun sendingPage(state: ClarificationState.Sending): DialogPanel =
        panel {
            row {
                icon(AnimatedIcon.Default.INSTANCE).gap(RightGap.SMALL)
                sendingLabel = JBLabel(sendingReason(state)).also { cell(it) }
            }
            row { multiline(state.requirement, 8).applyToComponent { isEditable = false } }.resizableRow()
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

/** 调查的种类：决定已用时间后面的说明。 */
internal enum class InvestigationKind { FIRST_ROUND, ROUND }

/** 用户还没提交过回答说明是第一轮。 */
internal fun investigationKind(record: ClarificationRecord): InvestigationKind =
    when {
        record.history.isEmpty() -> InvestigationKind.FIRST_ROUND
        else -> InvestigationKind.ROUND
    }

/** 标签与提交页链接的 tooltip：完整的短标签与问句。 */
private fun headerTooltip(q: Question): String = q.header + " — " + q.text

/** 进度里的长路径从中间省略，保留开头与文件名。 */
internal fun shortenPaths(text: String): String =
    text.split(' ').joinToString(" ") { token ->
        if (token.length > MAX_PATH_CHARS && ('/' in token || '\\' in token)) StringUtil.shortenPathWithEllipsis(token, MAX_PATH_CHARS) else token
    }

private const val MAX_PATH_CHARS = 48

/** 内容区的内边距（未缩放）：与原生对话框按钮栏的左右留白一致。 */
internal const val CONTENT_PADDING_H = 16
internal const val CONTENT_PADDING_V = 10

/** 交给 UI DSL 的文字按 HTML 显示：转义并保留换行。 */
private fun html(text: String): String = StringUtil.escapeXmlEntities(text).replace("\n", "<br>")

private fun keyAction(body: () -> Unit) =
    object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent) = body()
    }

/** 宽度不够时省略显示的单行标签：首选宽度不撑大窗口，完整内容看 tooltip。 */
private class EllipsisLabel(text: String) : JBLabel(text) {
    override fun getPreferredSize(): Dimension = super.getPreferredSize().also { it.width = minimumSize.width }

    override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(MIN_ELLIPSIS_WIDTH), super.getMinimumSize().height)

    companion object {
        private const val MIN_ELLIPSIS_WIDTH = 60
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

    /**
     * 故意给得很小：对话框打开时平台取"pack 尺寸"与初始尺寸的较大者，内容区若按全部内容报首选尺寸，
     * 窗口就会被撑大；初始尺寸由对话框按当前页内容另算（[ClarificationView.preferredContentHeight]）。
     */
    override fun getPreferredScrollableViewportSize(): Dimension = JBUI.size(360, 160)

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = (parent as? JViewport)?.let { it.height > preferredSize.height } == true
}

/**
 * 只用作标签条的 [JBTabbedPane]（各标签的内容都是空的，题目区域在它下面）。标签多或窗口窄时折成多行，不截断；
 * 平台算首选高度时按"内容宽度"（这里是 0）折行，会多出一大片空白，所以首选高度改按当前宽度下标签实际占的高度，
 * 宽度变了再重新布局一次。
 */
private class TabStrip : JBTabbedPane() {
    private var laidOutWidth = -1

    init {
        tabLayoutPolicy = WRAP_TAB_LAYOUT
        addComponentListener(
            object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    if (width != laidOutWidth) revalidate()
                }
            },
        )
    }

    override fun getPreferredSize(): Dimension {
        val natural = super.getPreferredSize()
        if (width <= 0 || tabCount == 0) return natural
        laidOutWidth = width
        val bottom = (0 until tabCount).maxOf { i -> getBoundsAt(i)?.let { it.y + it.height } ?: 0 }
        return Dimension(natural.width, bottom + insets.bottom + JBUI.scale(1))
    }
}

