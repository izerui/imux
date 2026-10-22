package com.github.izerui.imux.clarification

import com.github.izerui.imux.ImuxBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
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
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.Timer

/**
 * 需求澄清窗口：只负责显示流程状态并把操作转给 [ClarificationService]。
 * 非模态，打开期间仍可看代码、操作终端；关闭不中断在途一轮。
 */
internal class ClarificationDialog(
    private val project: Project,
    private val service: ClarificationService,
    private val flow: ClarificationFlow,
    title: String,
) : DialogWrapper(project, null, true, IdeModalityType.MODELESS) {
    @Volatile var sessionKey: String = service.currentKeyOf(flow).orEmpty()

    private val directionModel = CollectionListModel<Direction>()
    private val directionList = JBList(directionModel).apply { cellRenderer = DirectionRenderer() }
    private val requirementLabel = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty(4)
    }
    private val right = JPanel(BorderLayout())
    private val roundLabel = JBLabel()
    /** 右栏当前显示的状态。只有状态变了才重建右栏，否则正在输入的文字、选中的选项会被清掉。 */
    private var renderedState: ClarificationState? = null
    private var elapsedLabel: JBLabel? = null
    private val finishButton = JButton(ImuxBundle.message("clarify.finish")).apply { addActionListener { service.finish(sessionKey) } }
    private val elapsedTimer = Timer(1_000) { updateElapsed() }
    private val listener: () -> Unit = { ApplicationManager.getApplication().invokeLater({ render() }, ModalityState.any()) }

    init {
        this.title = ImuxBundle.message("clarify.dialog.title", title)
        setCancelButtonText(ImuxBundle.message("clarify.close"))
        installDirectionMenu()
        flow.addListener(listener)
        init()
        render()
        elapsedTimer.start()
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun createCenterPanel(): JComponent {
        val addButton = JButton(ImuxBundle.message("clarify.directions.add"), AllIcons.General.Add).apply {
            addActionListener {
                val text = Messages.showInputDialog(project, ImuxBundle.message("clarify.directions.add.prompt"), ImuxBundle.message("clarify.directions.add"), null)
                if (!text.isNullOrBlank()) service.addDirection(sessionKey, text)
            }
        }
        val left = JPanel(BorderLayout()).apply {
            add(requirementLabel, BorderLayout.NORTH)
            add(JBScrollPane(directionList), BorderLayout.CENTER)
            add(addButton, BorderLayout.SOUTH)
            border = JBUI.Borders.emptyRight(8)
        }
        val splitter = JBSplitter(false, 0.35f).apply {
            firstComponent = left
            secondComponent = JBScrollPane(right).apply { border = JBUI.Borders.empty() }
        }
        val bottom = JPanel(BorderLayout()).apply {
            add(roundLabel, BorderLayout.WEST)
            add(finishButton, BorderLayout.EAST)
            border = JBUI.Borders.emptyTop(8)
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = JBUI.size(900, 560)
            add(splitter, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
    }

    override fun dispose() {
        elapsedTimer.stop()
        flow.removeListener(listener)
        service.dialogClosed(sessionKey)
        super.dispose()
    }

    private fun render() {
        val ledger = flow.ledger
        val state = flow.state
        requirementLabel.text = ledger.requirement
        requirementLabel.isVisible = ledger.requirement.isNotEmpty()
        directionModel.replaceAll(ledger.directions)
        roundLabel.text = if (ledger.history.isEmpty() && state is ClarificationState.Input) "" else ImuxBundle.message("clarify.round", ledger.history.size + 1)
        finishButton.isEnabled = flow.canFinish
        if (state == renderedState) return
        renderedState = state
        elapsedLabel = null
        right.removeAll()
        right.add(
            when (state) {
                ClarificationState.Input -> inputPanel()
                is ClarificationState.Investigating -> investigatingPanel(state)
                is ClarificationState.Asking -> askingPanel(state.question, ledger)
                is ClarificationState.Result -> resultPanel(state.requirement)
                is ClarificationState.Sending -> sendingPanel(state)
                is ClarificationState.Failed -> failedPanel(state)
            },
            BorderLayout.NORTH,
        )
        right.revalidate()
        right.repaint()
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
        val area = JBTextArea(6, 50).apply { lineWrap = true; wrapStyleWord = true }
        // 是否有会话记录要查文件系统，不在 EDT 上做：先隐藏，查到再显示并默认勾选
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

    private fun askingPanel(question: Question, ledger: Ledger): JComponent {
        val panel = column()
        val correction = JBTextArea(3, 50).apply { lineWrap = true; wrapStyleWord = true; isVisible = false }
        ledger.understanding?.takeIf { ledger.history.isEmpty() || ledger.corrections.isNotEmpty() }?.let { understanding ->
            panel.add(JBLabel(ImuxBundle.message("clarify.understanding.title")).apply { font = font.deriveFont(java.awt.Font.BOLD) })
            panel.add(wrapped(understanding))
            panel.add(ActionLink(ImuxBundle.message("clarify.understanding.correct")) { correction.isVisible = !correction.isVisible; panel.revalidate() })
            panel.add(correction)
        }
        panel.add(wrapped(question.text, bold = true))
        if (question.reason.isNotBlank()) panel.add(wrapped(question.reason))
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

        panel.add(buttons(
            button("clarify.question.skip") { service.skip(sessionKey, question.directionId) },
            button("clarify.question.confirm") {
                val picked = radios.indexOfFirst { it.isSelected }
                val answer = when {
                    otherRadio.isSelected && otherField.text.isNotBlank() -> Answer.OwnWords(otherField.text)
                    picked >= 0 -> Answer.Picked(question.options[picked].id)
                    else -> return@button
                }
                service.answer(sessionKey, answer, correction.text.takeIf { correction.isVisible && it.isNotBlank() })
            },
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
                Messages.showInfoMessage(project, raw.take(20_000), ImuxBundle.message("clarify.failed.raw"))
            })
        }
        panel.add(buttons(
            button("clarify.finish") { service.fallback(sessionKey) },
            button("clarify.retry") { service.retry(sessionKey) },
        ))
        return panel
    }

    private fun installDirectionMenu() {
        val group = DefaultActionGroup(
            menuAction("clarify.directions.skip", { it.status == DirectionStatus.OPEN || it.status == DirectionStatus.ACTIVE }) { service.skip(sessionKey, it.id) },
            menuAction("clarify.directions.pin", { it.status == DirectionStatus.OPEN }) { service.pin(sessionKey, it.id) },
            menuAction("clarify.directions.reopen", { it.status == DirectionStatus.DECIDED || it.status == DirectionStatus.DEFAULTED }) { service.reopen(sessionKey, it.id) },
        )
        PopupHandler.installPopupMenu(directionList, group, "ClarifyDirections")
    }

    private fun menuAction(key: String, enabled: (Direction) -> Boolean, perform: (Direction) -> Unit) =
        object : DumbAwareAction(ImuxBundle.message(key)) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = directionList.selectedValue?.let(enabled) == true
            }

            override fun actionPerformed(e: AnActionEvent) {
                directionList.selectedValue?.let(perform)
            }
        }

    private class DirectionRenderer : ColoredListCellRenderer<Direction>() {
        override fun customizeCellRenderer(list: JList<out Direction>, value: Direction, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = when (value.status) {
                DirectionStatus.OPEN -> AllIcons.RunConfigurations.TestNotRan
                DirectionStatus.ACTIVE -> AllIcons.Actions.Execute
                DirectionStatus.DECIDED -> AllIcons.RunConfigurations.TestPassed
                DirectionStatus.DEFAULTED -> AllIcons.RunConfigurations.TestIgnored
                DirectionStatus.DROPPED -> AllIcons.Actions.Cancel
            }
            if (value.status == DirectionStatus.DROPPED) {
                // 去掉的方向不藏起来：灰显并附上模型给的理由，用户能看见它为什么不再问
                append(value.title, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                value.dropReason?.let { append("  $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
                return
            }
            append(value.title)
            value.conclusion?.takeIf { value.status == DirectionStatus.DECIDED || value.status == DirectionStatus.DEFAULTED }?.let {
                append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }
}
