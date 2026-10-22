package com.github.izerui.imux.clarification

import com.github.izerui.imux.ImuxBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import com.intellij.openapi.ui.Messages
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/**
 * 需求澄清窗口：内容由 [ClarificationView] 构建，全部按钮放在 DialogWrapper 原生的底部按钮栏，
 * 每次状态变化按 [buttonBar] 只显示与当前状态相关的按钮并切换默认按钮。
 * 非模态，打开期间仍可看代码、操作终端；关闭不中断在途一轮。
 */
internal class ClarificationDialog(
    private val project: Project,
    private val service: ClarificationService,
    private var flow: ClarificationFlow,
    title: String,
) : DialogWrapper(project, null, true, IdeModalityType.MODELESS) {
    @Volatile var sessionKey: String = service.currentKeyOf(flow).orEmpty()

    /** [修改] 后带到输入页的旧需求。 */
    private var restartRequirement: String? = null
    private val view = ClarificationView(ViewCallbacks())
    private val elapsedTimer = Timer(1_000) { view.tick(System.currentTimeMillis()) }
    private val listener: () -> Unit = { ApplicationManager.getApplication().invokeLater({ render() }, ModalityState.any()) }

    private val actions: Map<BarButton, BarAction> = BarButton.entries.associateWith { BarAction(it) }
    private val buttonPanels = mutableListOf<JPanel>()
    private var lastBar: ButtonBar? = null

    init {
        this.title = ImuxBundle.message("clarify.dialog.title", title)
        setCancelButtonText(ImuxBundle.message("clarify.close"))
        flow.addListener(listener)
        init()
        window?.minimumSize = JBUI.size(420, 300)
        render()
        elapsedTimer.start()
    }

    // 按钮栏：左右两侧各放全部候选按钮，按状态切换显隐。顺序是 Windows 的顺序（主操作在前），
    // macOS 上 DialogWrapper 按 MAC_ACTION_ORDER 排序，主操作排到最右。
    override fun createActions(): Array<Action> = RIGHT_ORDER.map { if (it == BarButton.CLOSE) cancelAction else actions.getValue(it) }.toTypedArray()

    override fun createLeftSideActions(): Array<Action> = LEFT_ORDER.map { actions.getValue(it) }.toTypedArray()

    override fun createButtonsPanel(buttons: List<JButton>): JPanel = super.createButtonsPanel(buttons).also { buttonPanels += it }

    override fun createCenterPanel(): JComponent = view.component

    override fun getInitialSize(): Dimension = JBUI.size(640, 480)

    override fun getDimensionServiceKey(): String = "imux.clarification.dialog"

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
        render()
    }

    private fun render() {
        val state = flow.state
        val ledger = flow.ledger
        val wasInput = view.isShowingInput()
        view.render(state, ledger, flow.canFinish, restartRequirement ?: ledger.requirement)
        if (state is ClarificationState.Input) {
            restartRequirement = null
            if (!wasInput) checkBackground()
        }
        updateButtons(buttonBar(state, flow.canFinish, ledger))
    }

    /** 会话记录文件是否存在要查文件系统，交给服务在 IO 线程上查，结果回到 EDT 再改复选框。 */
    private fun checkBackground() {
        service.checkBackground(sessionKey) { has ->
            ApplicationManager.getApplication().invokeLater({ view.setBackgroundAvailable(has) }, ModalityState.any())
        }
    }

    private fun updateButtons(bar: ButtonBar) {
        if (bar == lastBar) return
        lastBar = bar
        val confirm = bar.right.firstOrNull { it == BarButton.CONFIRM || it == BarButton.CONFIRM_NEXT }
        if (confirm != null) actions.getValue(BarButton.CONFIRM).putValue(Action.NAME, ImuxBundle.message(confirm.labelKey))
        val shown = (bar.left + bar.right).map { if (it == BarButton.CONFIRM_NEXT) BarButton.CONFIRM else it }.toSet()
        for (button in LEFT_ORDER + RIGHT_ORDER) buttonOf(button)?.isVisible = button in shown
        rootPane?.defaultButton = buttonOf(bar.default)
        buttonPanels.forEach(::syncButtonGaps)
    }

    private fun buttonOf(button: BarButton): JButton? =
        when (button) {
            BarButton.CLOSE -> getButton(cancelAction)
            BarButton.CONFIRM_NEXT -> getButton(actions.getValue(BarButton.CONFIRM))
            else -> getButton(actions.getValue(button))
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

    private fun perform(button: BarButton) {
        when (button) {
            BarButton.CLOSE -> doCancelAction()
            BarButton.START -> view.inputText().takeIf { it.isNotBlank() }?.let { service.start(sessionKey, it, view.includeBackground()) }
            BarButton.CANCEL_INVESTIGATION -> service.cancel(sessionKey)
            BarButton.FINISH, BarButton.FINISH_LEFT -> service.finish(sessionKey)
            BarButton.SKIP_QUESTION -> (flow.state as? ClarificationState.Asking)?.let { service.skip(sessionKey, it.question.directionId) }
            BarButton.CONFIRM, BarButton.CONFIRM_NEXT -> view.currentAnswer()?.let { service.answer(sessionKey, it, view.correctionText()) }
            BarButton.CONTINUE -> service.continueClarifying(sessionKey)
            BarButton.COPY -> CopyPasteManager.getInstance().setContents(StringSelection(view.resultText()))
            BarButton.SEND -> {
                val text = view.resultText()
                if (text.isBlank()) return
                flow.editResult(text)
                service.send(sessionKey, text)
            }
            BarButton.CANCEL_SEND -> service.cancelSend(sessionKey)
            BarButton.FALLBACK -> service.fallback(sessionKey)
            BarButton.RETRY -> service.retry(sessionKey)
        }
    }

    private inner class BarAction(private val button: BarButton) : AbstractAction(ImuxBundle.message(button.labelKey)) {
        init {
            // macOS 上主操作排在最右，「够了，生成结果」排在它左边
            putValue(MAC_ACTION_ORDER, if (button == BarButton.FINISH) MAC_ORDER_SECONDARY else MAC_ORDER_PRIMARY)
        }

        override fun actionPerformed(e: ActionEvent) = perform(button)
    }

    /** 视图里的链接：弹窗一律以本对话框为父组件。 */
    private inner class ViewCallbacks : ClarificationViewCallbacks {
        override fun editRequirement() = confirmRestart()

        override fun skipDirection(id: String) = service.skip(sessionKey, id)

        override fun pinDirection(id: String) = service.pin(sessionKey, id)

        override fun reopenDirection(id: String) = service.reopen(sessionKey, id)

        override fun addDirection() {
            val text = Messages.showInputDialog(contentPane, ImuxBundle.message("clarify.directions.add.prompt"), ImuxBundle.message("clarify.directions.add"), null)
            if (!text.isNullOrBlank()) service.addDirection(sessionKey, text)
        }

        override fun showAgentOutput(raw: String) = Messages.showInfoMessage(contentPane, raw.take(20_000), ImuxBundle.message("clarify.failed.raw"))
    }

    companion object {
        private const val MAC_ORDER_PRIMARY = 100
        private const val MAC_ORDER_SECONDARY = 50

        /** 右侧候选按钮（Windows 顺序，主操作在前，关闭在最后）。 */
        private val RIGHT_ORDER =
            listOf(
                BarButton.START, BarButton.CANCEL_INVESTIGATION, BarButton.CONFIRM, BarButton.SEND,
                BarButton.CANCEL_SEND, BarButton.RETRY, BarButton.FINISH, BarButton.CLOSE,
            )
        private val LEFT_ORDER = listOf(BarButton.FINISH_LEFT, BarButton.SKIP_QUESTION, BarButton.CONTINUE, BarButton.COPY, BarButton.FALLBACK)

        /**
         * 平台按钮面板在相邻按钮之间各放一个固定间隔；按钮隐藏后间隔还在，会留下空隙。
         * 间隔只在前后都有可见按钮时显示。
         */
        private fun syncButtonGaps(panel: JPanel) {
            val children = panel.components
            for ((i, child) in children.withIndex()) {
                if (child !is Box.Filler) continue
                val before = children.take(i).lastOrNull { it is JButton }?.isVisible == true
                val after = children.drop(i + 1).any { it is JButton && it.isVisible }
                child.isVisible = before && after
            }
            panel.revalidate()
            panel.repaint()
        }
    }
}

// ---- 纯函数：提取出来方便单测 ----

/** 底部按钮栏的按钮。[FINISH] 在右侧（提问页），[FINISH_LEFT] 在左侧（调查中）。 */
internal enum class BarButton(val labelKey: String) {
    CLOSE("clarify.close"),
    START("clarify.input.start"),
    CANCEL_INVESTIGATION("clarify.cancel"),
    FINISH("clarify.finish"),
    FINISH_LEFT("clarify.finish"),
    SKIP_QUESTION("clarify.question.skip"),
    CONFIRM("clarify.question.confirm"),
    CONFIRM_NEXT("clarify.question.confirm.next"),
    CONTINUE("clarify.result.back"),
    COPY("clarify.result.copy"),
    SEND("clarify.result.send"),
    CANCEL_SEND("clarify.sending.cancel"),
    FALLBACK("clarify.failed.fallback"),
    RETRY("clarify.retry"),
}

/** 一个状态下的按钮栏：[left]、[right] 按屏幕上从左到右的顺序；[default] 是回车触发的默认按钮。 */
internal data class ButtonBar(val left: List<BarButton>, val right: List<BarButton>, val default: BarButton)

/** 状态 → 按钮栏。只给出与当前状态相关的按钮（隐藏而不是禁用）。 */
internal fun buttonBar(state: ClarificationState, canFinish: Boolean, ledger: Ledger): ButtonBar =
    when (state) {
        ClarificationState.Input -> ButtonBar(emptyList(), listOf(BarButton.CLOSE, BarButton.START), BarButton.START)
        is ClarificationState.Investigating ->
            ButtonBar(
                if (investigationKind(canFinish, ledger) == InvestigationKind.ROUND) listOf(BarButton.FINISH_LEFT) else emptyList(),
                listOf(BarButton.CLOSE, BarButton.CANCEL_INVESTIGATION),
                BarButton.CANCEL_INVESTIGATION,
            )
        is ClarificationState.Asking -> {
            val confirm = if (state.index >= state.batch.lastIndex) BarButton.CONFIRM else BarButton.CONFIRM_NEXT
            ButtonBar(listOf(BarButton.SKIP_QUESTION), if (canFinish) listOf(BarButton.FINISH, confirm) else listOf(confirm), confirm)
        }
        is ClarificationState.Result -> ButtonBar(listOf(BarButton.CONTINUE, BarButton.COPY), listOf(BarButton.CLOSE, BarButton.SEND), BarButton.SEND)
        is ClarificationState.Sending -> ButtonBar(emptyList(), listOf(BarButton.CANCEL_SEND), BarButton.CANCEL_SEND)
        is ClarificationState.Failed -> ButtonBar(listOf(BarButton.FALLBACK), listOf(BarButton.CLOSE, BarButton.RETRY), BarButton.RETRY)
    }

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

/**
 * 判断是否需要重建中间面板。以下情况就地更新而不重建，否则用户正在输入的文字、选中的选项和纠正文本都会丢失：
 * 当前题不变、只是批次缩短（跳过了后面的方向）；同一次调查只是进度文字变了；同一次发送只是等待原因变了。
 */
internal fun needsCenterRebuild(old: ClarificationState?, new: ClarificationState): Boolean {
    if (old == new) return false
    return when {
        old is ClarificationState.Asking && new is ClarificationState.Asking -> old.index != new.index || old.question != new.question
        old is ClarificationState.Investigating && new is ClarificationState.Investigating -> old.startedAt != new.startedAt
        old is ClarificationState.Sending && new is ClarificationState.Sending -> old.requirement != new.requirement
        else -> true
    }
}

/** 需求文本的 tooltip：换行保留，限 2000 字符，HTML 包裹限宽。 */
internal fun requirementTooltip(requirement: String): String? {
    if (requirement.isBlank()) return null
    val text = requirement.take(2000).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
    return "<html><body style='width:400px'>$text</body></html>"
}
