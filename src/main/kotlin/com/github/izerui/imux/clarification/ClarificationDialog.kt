package com.github.izerui.imux.clarification

import com.github.izerui.imux.ImuxBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/**
 * 需求澄清窗口：单栏布局，只负责显示流程状态并把操作转给 [ClarificationService]。
 * 非模态，打开期间仍可看代码、操作终端；关闭不中断在途一轮。
 */
internal class ClarificationDialog(
    private val project: Project,
    private val service: ClarificationService,
    private var flow: ClarificationFlow,
    title: String,
) : DialogWrapper(project, null, true, IdeModalityType.MODELESS) {
    @Volatile var sessionKey: String = service.currentKeyOf(flow).orEmpty()

    /** 方向清单是否展开，对话框生命期内记住。 */
    private var listExpanded = false
    /** [修改] 后带到输入页的旧需求。 */
    private var restartRequirement: String? = null
    private val requirementLabel = JBLabel().apply {
        border = JBUI.Borders.emptyRight(8)
    }
    private val progressLabel = JBLabel()
    private val toggleLink = ActionLink("") { toggleList() }
    private val headerPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, JBUI.scale(2), true, false))
    private val listPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, JBUI.scale(2), true, false)).apply { isVisible = false }
    private val center = JPanel(BorderLayout())
    /** 中间区域当前显示的状态。只有状态变了才重建，否则正在输入的文字、选中的选项会被清掉。 */
    private var renderedState: ClarificationState? = null
    private var elapsedLabel: JBLabel? = null
    private val finishButton = JButton(ImuxBundle.message("clarify.finish")).apply { addActionListener { service.finish(sessionKey) } }
    private val elapsedTimer = Timer(1_000) { updateElapsed() }
    private val listener: () -> Unit = { ApplicationManager.getApplication().invokeLater({ render() }, ModalityState.any()) }

    /** 提问页保持的可变组件引用，批次缩短时就地更新而不重建。 */
    private var askingHeaderLabel: JBLabel? = null
    private var askingBatchNote: JBLabel? = null
    private var askingConfirmButton: JButton? = null

    init {
        this.title = ImuxBundle.message("clarify.dialog.title", title)
        setCancelButtonText(ImuxBundle.message("clarify.close"))
        flow.addListener(listener)
        init()
        render()
        elapsedTimer.start()
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun createCenterPanel(): JComponent {
        val footer = JPanel(BorderLayout()).apply {
            add(finishButton, BorderLayout.WEST)
            border = JBUI.Borders.emptyTop(8)
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = JBUI.size(700, 520)
            add(headerPanel, BorderLayout.NORTH)
            add(JBScrollPane(center).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }
    }

    override fun dispose() {
        elapsedTimer.stop()
        flow.removeListener(listener)
        service.dialogClosed(sessionKey)
        super.dispose()
    }

    /** 服务重建了流程后让对话框绑到新流程上，回到输入页并预填旧需求。 */
    internal fun rebind(newFlow: ClarificationFlow) {
        restartRequirement = flow.ledger.requirement
        flow.removeListener(listener)
        flow = newFlow
        flow.addListener(listener)
        renderedState = null
        render()
    }

    private fun render() {
        val ledger = flow.ledger
        val state = flow.state
        val isInput = state is ClarificationState.Input
        renderHeader(ledger, isInput)
        renderList(ledger, isInput)
        finishButton.isEnabled = flow.canFinish
        if (!needsCenterRebuild(renderedState, state)) {
            // 批次缩短（跳过后面的方向）：就地更新标题、批注、确认按钮，不重建整个面板
            if (state is ClarificationState.Asking) updateAskingInPlace(state)
            return
        }
        renderedState = state
        elapsedLabel = null
        askingHeaderLabel = null
        askingBatchNote = null
        askingConfirmButton = null
        center.removeAll()
        center.add(
            when (state) {
                ClarificationState.Input -> inputPanel()
                is ClarificationState.Investigating -> investigatingPanel(state)
                is ClarificationState.Asking -> askingPanel(state, ledger)
                is ClarificationState.Result -> resultPanel(state.requirement)
                is ClarificationState.Sending -> sendingPanel(state)
                is ClarificationState.Failed -> failedPanel(state)
            },
            BorderLayout.NORTH,
        )
        center.revalidate()
        center.repaint()
    }

    /** 批次缩短时就地更新提问页的可变部件，不重建面板（保留用户的输入状态）。 */
    private fun updateAskingInPlace(asking: ClarificationState.Asking) {
        renderedState = asking
        askingHeaderLabel?.text = questionHeaderText(asking.index, asking.batch.size)
        askingBatchNote?.isVisible = asking.batch.size > 1
        val isLast = asking.index >= asking.batch.lastIndex
        askingConfirmButton?.text = ImuxBundle.message(if (isLast) "clarify.question.confirm" else "clarify.question.confirm.next")
    }

    private fun renderHeader(ledger: Ledger, isInput: Boolean) {
        headerPanel.removeAll()
        if (isInput) {
            headerPanel.isVisible = false
            listPanel.isVisible = false
            return
        }
        headerPanel.isVisible = true
        // Line 1: requirement + [修改]
        val reqText = ledger.requirement.replace('\n', ' ').replace('\r', ' ')
        requirementLabel.text = ImuxBundle.message("clarify.header.requirement", reqText)
        requirementLabel.toolTipText = requirementTooltip(ledger.requirement)
        val editLink = ActionLink(ImuxBundle.message("clarify.header.edit")) { confirmRestart() }
        val line1 = JPanel(BorderLayout()).apply {
            add(requirementLabel, BorderLayout.CENTER)
            add(editLink, BorderLayout.EAST)
        }
        // Line 2: progress + [展开清单 ▾]/[收起清单 ▴]
        val counts = progressCounts(ledger.directions)
        val progressText = buildString {
            append(ImuxBundle.message("clarify.progress", counts.decided, counts.open, counts.defaulted))
            if (counts.dropped > 0) append(ImuxBundle.message("clarify.progress.dropped", counts.dropped))
        }
        progressLabel.text = progressText
        toggleLink.text = if (listExpanded) ImuxBundle.message("clarify.list.collapse") else ImuxBundle.message("clarify.list.expand")
        val line2 = JPanel(BorderLayout()).apply {
            add(progressLabel, BorderLayout.CENTER)
            add(toggleLink, BorderLayout.EAST)
        }
        headerPanel.add(line1)
        headerPanel.add(line2)
        headerPanel.add(listPanel)
        headerPanel.revalidate()
        headerPanel.repaint()
    }

    private fun renderList(ledger: Ledger, isInput: Boolean) {
        listPanel.removeAll()
        listPanel.isVisible = listExpanded && !isInput
        for (direction in ledger.directions) {
            listPanel.add(directionRow(direction))
        }
        val addLink = ActionLink(ImuxBundle.message("clarify.directions.add")) {
            val text = Messages.showInputDialog(contentPane, ImuxBundle.message("clarify.directions.add.prompt"), ImuxBundle.message("clarify.directions.add"), null)
            if (!text.isNullOrBlank()) service.addDirection(sessionKey, text)
        }
        listPanel.add(addLink)
        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun directionRow(direction: Direction): JPanel {
        val statusText = statusLabel(direction.status)
        val titleLabel = JBLabel(direction.title)
        val detailLabel = JBLabel()
        if (direction.status == DirectionStatus.DROPPED) {
            titleLabel.foreground = UIUtil.getContextHelpForeground()
            direction.dropReason?.let { detailLabel.text = it; detailLabel.foreground = UIUtil.getContextHelpForeground() }
        } else {
            direction.conclusion?.takeIf { direction.status == DirectionStatus.DECIDED || direction.status == DirectionStatus.DEFAULTED }?.let {
                detailLabel.text = it
                detailLabel.foreground = UIUtil.getContextHelpForeground()
            }
        }
        val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        row.add(statusText)
        row.add(titleLabel)
        if (detailLabel.text?.isNotEmpty() == true) row.add(detailLabel)
        val actions = directionRowActions(direction.status)
        if (DirectionAction.SKIP in actions) {
            row.add(ActionLink(ImuxBundle.message("clarify.directions.skip")) { service.skip(sessionKey, direction.id) })
        }
        if (DirectionAction.PIN in actions) {
            row.add(ActionLink(ImuxBundle.message("clarify.directions.pin")) { service.pin(sessionKey, direction.id) })
        }
        if (DirectionAction.REOPEN in actions) {
            row.add(ActionLink(ImuxBundle.message("clarify.directions.reopen")) { service.reopen(sessionKey, direction.id) })
        }
        return row
    }

    private fun statusLabel(status: DirectionStatus): JBLabel {
        val key = when (status) {
            DirectionStatus.OPEN -> "clarify.status.open"
            DirectionStatus.ACTIVE -> "clarify.status.active"
            DirectionStatus.DECIDED -> "clarify.status.decided"
            DirectionStatus.DEFAULTED -> "clarify.status.defaulted"
            DirectionStatus.DROPPED -> "clarify.status.dropped"
        }
        return JBLabel(ImuxBundle.message(key)).apply {
            if (status == DirectionStatus.DROPPED) foreground = UIUtil.getContextHelpForeground()
        }
    }

    private fun toggleList() {
        listExpanded = !listExpanded
        toggleLink.text = if (listExpanded) ImuxBundle.message("clarify.list.collapse") else ImuxBundle.message("clarify.list.expand")
        listPanel.isVisible = listExpanded
        headerPanel.revalidate()
        headerPanel.repaint()
    }

    private fun confirmRestart() {
        val yes = Messages.showYesNoDialog(
            contentPane,
            ImuxBundle.message("clarify.restart.text"),
            ImuxBundle.message("clarify.restart.title"),
            Messages.getQuestionIcon(),
        )
        if (yes == Messages.YES) service.restart(sessionKey)
    }

    private fun updateElapsed() {
        val state = flow.state as? ClarificationState.Investigating ?: return
        elapsedLabel?.text = ImuxBundle.message("clarify.elapsed", ((System.currentTimeMillis() - state.startedAt) / 1_000).coerceAtLeast(0))
    }

    private fun column(): JPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, JBUI.scale(6), true, false))

    private fun wrapped(text: String, bold: Boolean = false): JBTextArea =
        JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            if (bold) font = font.deriveFont(java.awt.Font.BOLD)
        }

    private fun buttons(vararg items: JComponent): JPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { items.forEach(::add) }

    private fun button(key: String, onClick: () -> Unit): JButton = JButton(ImuxBundle.message(key)).apply { addActionListener { onClick() } }

    private fun inputPanel(): JComponent {
        val prefill = restartRequirement ?: flow.ledger.requirement
        restartRequirement = null
        val area = JBTextArea(6, 50).apply { lineWrap = true; wrapStyleWord = true; text = prefill }
        val background = JBCheckBox(ImuxBundle.message("clarify.input.background"), false).apply { isVisible = false }
        service.checkBackground(sessionKey) { has ->
            ApplicationManager.getApplication().invokeLater({
                background.isVisible = has
                background.isSelected = has
            }, ModalityState.any())
        }
        return column().apply {
            add(JBLabel(ImuxBundle.message("clarify.input.label")))
            add(JBScrollPane(area))
            add(background)
            add(buttons(button("clarify.input.start") {
                if (area.text.isNotBlank()) service.start(sessionKey, area.text, background.isVisible && background.isSelected)
            }))
        }
    }

    private fun investigatingPanel(state: ClarificationState.Investigating): JComponent {
        val elapsed = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
        elapsedLabel = elapsed
        updateElapsed()
        return column().apply {
            add(JBLabel(ImuxBundle.message("clarify.investigating", state.progress.orEmpty()), AllIcons.Process.Step_1, JBLabel.LEADING))
            add(elapsed)
            add(buttons(button("clarify.cancel") { service.cancel(sessionKey) }))
        }
    }

    private fun askingPanel(asking: ClarificationState.Asking, ledger: Ledger): JComponent {
        val question = asking.question
        val panel = column()
        val correction = JBTextArea(3, 50).apply { lineWrap = true; wrapStyleWord = true; isVisible = false }
        // "我的理解"：首批第一题（或纠正后）可折叠，默认展开；纠正理解用 JButton
        if (asking.index == 0) {
            val isFirstBatch = ledger.history.isEmpty() || ledger.corrections.isNotEmpty()
            ledger.understanding?.takeIf { isFirstBatch }?.let { understanding ->
                val understandingTitle = JBLabel(ImuxBundle.message("clarify.understanding.title")).apply { font = font.deriveFont(java.awt.Font.BOLD) }
                val understandingBody = wrapped(understanding)
                panel.add(understandingTitle)
                panel.add(understandingBody)
                val correctButton = JButton(ImuxBundle.message("clarify.understanding.correct"))
                correctButton.addActionListener {
                    val nowVisible = !correction.isVisible
                    correction.isVisible = nowVisible
                    understandingBody.isVisible = !nowVisible
                    understandingTitle.isVisible = !nowVisible
                    panel.revalidate()
                }
                panel.add(correctButton)
                panel.add(correction)
            }
        }
        // Question header: "问题 i/n"
        val headerLabel = JBLabel(questionHeaderText(asking.index, asking.batch.size)).apply { font = font.deriveFont(java.awt.Font.BOLD) }
        askingHeaderLabel = headerLabel
        panel.add(headerLabel)
        val batchNote = JBLabel(ImuxBundle.message("clarify.question.batch.note")).apply {
            foreground = UIUtil.getContextHelpForeground()
            isVisible = asking.batch.size > 1
        }
        askingBatchNote = batchNote
        panel.add(batchNote)
        panel.add(wrapped(question.text, bold = true))
        if (question.reason.isNotBlank()) panel.add(wrapped(ImuxBundle.message("clarify.question.why", question.reason)).apply { foreground = UIUtil.getContextHelpForeground() })
        if (question.basis.isNotBlank()) panel.add(wrapped(ImuxBundle.message("clarify.question.basis", question.basis)).apply { foreground = UIUtil.getContextHelpForeground() })

        val group = ButtonGroup()
        val radios = question.options.map { option ->
            val label = if (option.recommended) ImuxBundle.message("clarify.question.recommended", option.label) else option.label
            JBRadioButton(label, option.recommended).also { radio ->
                group.add(radio)
                panel.add(radio)
                panel.add(wrapped(ImuxBundle.message("clarify.question.becomes", option.conclusion)).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.emptyLeft(24)
                })
            }
        }
        val otherRadio = JBRadioButton(ImuxBundle.message("clarify.question.other"))
        val otherField = JBTextField()
        group.add(otherRadio)
        otherField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) { if (otherField.text.isNotEmpty()) otherRadio.isSelected = true }
        })
        panel.add(otherRadio)
        panel.add(otherField)

        val isLast = asking.index >= asking.batch.lastIndex
        val confirmKey = if (isLast) "clarify.question.confirm" else "clarify.question.confirm.next"
        val confirmBtn = button(confirmKey) {
            val picked = radios.indexOfFirst { it.isSelected }
            val answer = when {
                otherRadio.isSelected && otherField.text.isNotBlank() -> Answer.OwnWords(otherField.text)
                picked >= 0 -> Answer.Picked(question.options[picked].id)
                else -> return@button
            }
            service.answer(sessionKey, answer, correction.text.takeIf { correction.isVisible && it.isNotBlank() })
        }
        askingConfirmButton = confirmBtn
        panel.add(buttons(
            button("clarify.question.skip") { service.skip(sessionKey, question.directionId) },
            confirmBtn,
        ))
        return panel
    }

    private fun resultPanel(requirement: String): JComponent {
        val area = JBTextArea(requirement, 14, 50).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        return column().apply {
            add(JBLabel(ImuxBundle.message("clarify.result.title")).apply { font = font.deriveFont(java.awt.Font.BOLD) })
            add(JBScrollPane(area))
            add(buttons(
                button("clarify.result.back") { service.continueClarifying(sessionKey) },
                button("clarify.result.copy") { CopyPasteManager.getInstance().setContents(StringSelection(area.text)) },
                button("clarify.result.send") {
                    if (area.text.isBlank()) return@button
                    flow.editResult(area.text)
                    service.send(sessionKey, area.text)
                },
            ))
        }
    }

    private fun sendingPanel(state: ClarificationState.Sending): JComponent =
        column().apply {
            add(JBLabel(ImuxBundle.message("clarify.result.title")).apply { font = font.deriveFont(java.awt.Font.BOLD) })
            add(JBScrollPane(JBTextArea(state.requirement, 14, 50).apply { lineWrap = true; wrapStyleWord = true; isEditable = false }))
            add(JBLabel(ImuxBundle.message(if (state.waitingForTurn) "clarify.sending.turn" else "clarify.sending"), AllIcons.Process.Step_1, JBLabel.LEADING))
            add(buttons(button("clarify.sending.cancel") { service.cancelSend(sessionKey) }))
        }

    private fun failedPanel(state: ClarificationState.Failed): JComponent {
        val message = when (state.reason) {
            FailureReason.CANCELLED -> ImuxBundle.message("clarify.failed.cancelled")
            FailureReason.TIMEOUT -> ImuxBundle.message("clarify.failed.timeout", state.detail)
            FailureReason.PROTOCOL -> ImuxBundle.message("clarify.failed.protocol")
            FailureReason.AGENT_ERROR -> ImuxBundle.message("clarify.failed.error", state.detail)
        }
        val panel = column()
        panel.add(JBLabel(message, AllIcons.General.Warning, JBLabel.LEADING))
        state.raw?.let { raw ->
            panel.add(ActionLink(ImuxBundle.message("clarify.failed.raw")) {
                Messages.showInfoMessage(contentPane, raw.take(20_000), ImuxBundle.message("clarify.failed.raw"))
            })
        }
        panel.add(buttons(
            button("clarify.finish") { service.fallback(sessionKey) },
            button("clarify.retry") { service.retry(sessionKey) },
        ))
        return panel
    }
}

// ---- 纯函数：提取出来方便单测 ----

internal data class ProgressCounts(val decided: Int, val open: Int, val defaulted: Int, val dropped: Int)

/** 统计方向列表中各状态的数量。OPEN 与 ACTIVE 合并为"待定"（pending），因为正在问的方向也还没定。 */
internal fun progressCounts(directions: List<Direction>): ProgressCounts {
    var decided = 0; var open = 0; var defaulted = 0; var dropped = 0
    for (d in directions) when (d.status) {
        DirectionStatus.DECIDED -> decided++
        DirectionStatus.OPEN, DirectionStatus.ACTIVE -> open++
        DirectionStatus.DEFAULTED -> defaulted++
        DirectionStatus.DROPPED -> dropped++
    }
    return ProgressCounts(decided, open, defaulted, dropped)
}

internal enum class DirectionAction { SKIP, PIN, REOPEN }

/** 每个状态下的方向行可以做哪些操作。 */
internal fun directionRowActions(status: DirectionStatus): Set<DirectionAction> = when (status) {
    DirectionStatus.OPEN -> setOf(DirectionAction.SKIP, DirectionAction.PIN)
    DirectionStatus.ACTIVE -> setOf(DirectionAction.SKIP)
    DirectionStatus.DECIDED -> setOf(DirectionAction.REOPEN)
    DirectionStatus.DEFAULTED -> setOf(DirectionAction.REOPEN)
    DirectionStatus.DROPPED -> setOf(DirectionAction.REOPEN)
}

/** "问题 i/n" 的标题文本。0-based 的 index 和总数。 */
internal fun questionHeaderText(index: Int, total: Int): String =
    ImuxBundle.message("clarify.question.header", index + 1, total)

/**
 * 判断是否需要重建中间面板。当前题与前一题相同、只是批次缩短（跳过了后面的方向）时不重建，
 * 否则用户正在输入的文字、选中的选项和纠正文本都会丢失。
 */
internal fun needsCenterRebuild(old: ClarificationState?, new: ClarificationState): Boolean {
    if (old == new) return false
    if (old is ClarificationState.Asking && new is ClarificationState.Asking) {
        return old.index != new.index || old.question != new.question
    }
    return true
}

/** 需求文本的 tooltip：换行保留，限 2000 字符，HTML 包裹限宽。 */
internal fun requirementTooltip(requirement: String): String? {
    if (requirement.isBlank()) return null
    val text = requirement.take(2000).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
    return "<html><body style='width:400px'>$text</body></html>"
}
