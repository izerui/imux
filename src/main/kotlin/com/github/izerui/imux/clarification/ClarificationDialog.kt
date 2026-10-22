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
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/**
 * 需求澄清窗口：内容由 [ClarificationView] 构建，全部按钮放在 DialogWrapper 原生的底部按钮栏，
 * 按 [buttonBar] 只显示与当前状态相关的按钮并切换默认按钮。
 * 非模态，打开期间仍可看代码、操作终端；关闭不中断在途一轮。
 *
 * 流程的通知经 [RenderCoalescer] 合并：任意多次通知最多排一次 EDT 渲染。
 */
internal class ClarificationDialog(
    project: Project,
    private val service: ClarificationService,
    private val flow: ClarificationFlow,
    title: String,
) : DialogWrapper(project, null, true, IdeModalityType.MODELESS) {
    @Volatile var sessionKey: String = service.currentKeyOf(flow).orEmpty()

    private val view = ClarificationView(ViewCallbacks())
    private val elapsedTimer = Timer(1_000) { view.tick(System.currentTimeMillis()) }
    private val coalescer = RenderCoalescer({ ApplicationManager.getApplication().invokeLater(it, ModalityState.any()) }) { render() }
    private val listener: () -> Unit = coalescer::request

    private val actions: Map<BarButton, BarAction> = (LEFT_ORDER + RIGHT_ORDER - BarButton.CLOSE).associateWith { BarAction(it) }
    private val buttonPanels = mutableListOf<JPanel>()
    private var lastBar: ButtonBar? = null
    private var shown: ClarificationState = ClarificationState.Input
    private var shownCanFinish = false
    private var shownLedger: Ledger = flow.ledger

    init {
        this.title = ImuxBundle.message("clarify.dialog.title", title)
        setCancelButtonText(ImuxBundle.message("clarify.close"))
        flow.addListener(listener)
        init()
        window?.minimumSize = JBUI.size(420, 320)
        render()
        elapsedTimer.start()
    }

    // 按钮栏：左右两侧各放全部候选按钮，按状态切换显隐。顺序是 Windows 的顺序（主操作在前），
    // macOS 上 DialogWrapper 按 MAC_ACTION_ORDER 排序，关闭排在主操作左边。
    override fun createActions(): Array<Action> = RIGHT_ORDER.map { if (it == BarButton.CLOSE) cancelAction else actions.getValue(it) }.toTypedArray()

    override fun createLeftSideActions(): Array<Action> = LEFT_ORDER.map { actions.getValue(it) }.toTypedArray()

    override fun createButtonsPanel(buttons: List<JButton>): JPanel = super.createButtonsPanel(buttons).also { buttonPanels += it }

    override fun createCenterPanel(): JComponent = view.component

    /**
     * 打开时算一次的初始尺寸（之后不随状态变化改窗口大小，免得窗口跳来跳去）：宽 560，
     * 高 = 当前页在这个宽度下的内容高度 + 窗口其余部分（标题栏、按钮栏、边框），限制在 360～520 之间——
     * 短问题不开出一个高高的空窗口，长内容在窗口里滚动。平台在 pack 之后才取这个尺寸，此时窗口其余部分量得出来；
     * 平台取 pack 尺寸与它的较大者，内容区的首选视口尺寸因此故意给得很小（见 [ClarificationView]）。
     */
    override fun getInitialSize(): Dimension {
        val width = JBUI.scale(INITIAL_WIDTH)
        val win = window
        val viewport = view.component
        val restOfWindow =
            if (win != null && win.height > 0 && viewport.height > 0) win.height - viewport.height
            else JBUI.scale(REST_OF_WINDOW_ESTIMATE)
        val horizontalChrome = if (win != null && win.width > 0 && viewport.width > 0) win.width - viewport.width else 0
        val content = view.preferredContentHeight((width - horizontalChrome).coerceAtLeast(JBUI.scale(200)))
        return Dimension(width, initialHeight(content, restOfWindow))
    }

    override fun dispose() {
        elapsedTimer.stop()
        flow.removeListener(listener)
        service.dialogClosed(sessionKey)
        super.dispose()
    }

    private fun render() {
        shown = flow.state
        shownLedger = flow.ledger
        shownCanFinish = flow.canFinish
        val wasInput = view.isShowingInput()
        view.render(shown, shownLedger, shownCanFinish, shownLedger.requirement)
        if (shown is ClarificationState.Input && !wasInput) checkBackground()
        updateButtons()
    }

    /** 会话记录文件是否存在要查文件系统，交给服务在 IO 线程上查，结果回到 EDT 再改复选框。 */
    private fun checkBackground() {
        service.checkBackground(sessionKey) { has ->
            ApplicationManager.getApplication().invokeLater({ view.setBackgroundAvailable(has) }, ModalityState.any())
        }
    }

    /** 按钮栏只在可见按钮或默认按钮变了时才动。 */
    private fun updateButtons() {
        val bar = buttonBar(shown, shownCanFinish, shownLedger, view.askingPage())
        if (bar == lastBar) return
        lastBar = bar
        val visible = (bar.left + bar.right).toSet()
        for (button in LEFT_ORDER + RIGHT_ORDER) buttonOf(button)?.isVisible = button in visible
        // 调查中、发送中没有默认按钮：提交时多按一下回车不能把刚开始的调查（或发送）取消掉
        rootPane?.defaultButton = bar.default?.let(::buttonOf)
        buttonPanels.forEach(::syncButtonGaps)
    }

    private fun buttonOf(button: BarButton): JButton? = getButton(if (button == BarButton.CLOSE) cancelAction else actions.getValue(button))

    private fun perform(button: BarButton) {
        when (button) {
            BarButton.CLOSE -> doCancelAction()
            BarButton.START -> view.inputText().takeIf { it.isNotBlank() }?.let { service.start(sessionKey, it, view.includeBackground()) }
            BarButton.CANCEL_INVESTIGATION -> service.cancel(sessionKey)
            BarButton.FINISH -> service.finish(sessionKey, view.answers())
            BarButton.NEXT, BarButton.REVIEW -> view.nextTab()
            BarButton.SUBMIT -> service.submit(sessionKey, view.answers())
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
            putValue(MAC_ACTION_ORDER, MAC_ORDER_PRIMARY)
        }

        override fun actionPerformed(e: ActionEvent) = perform(button)
    }

    private inner class ViewCallbacks : ClarificationViewCallbacks {
        override fun draftsFor(batch: List<Question>): BatchDrafts =
            flow.drafts?.takeIf { it.batch == batch } ?: BatchDrafts(batch).also { flow.drafts = it }

        override fun askingPageChanged() = updateButtons()

        // 弹窗以本对话框为父组件
        override fun showAgentOutput(raw: String) = Messages.showInfoMessage(contentPane, raw.take(20_000), ImuxBundle.message("clarify.failed.raw"))
    }

    companion object {
        private const val MAC_ORDER_PRIMARY = 100
        internal const val INITIAL_WIDTH = 560

        /** 量不到窗口其余部分时（没有 pack）按标题栏 + 按钮栏的常见高度估计。 */
        private const val REST_OF_WINDOW_ESTIMATE = 90

        /** 右侧候选按钮（Windows 顺序，主操作在前，关闭在最后）。 */
        private val RIGHT_ORDER =
            listOf(
                BarButton.START, BarButton.CANCEL_INVESTIGATION, BarButton.NEXT, BarButton.REVIEW, BarButton.SUBMIT,
                BarButton.SEND, BarButton.CANCEL_SEND, BarButton.RETRY, BarButton.CLOSE,
            )
        private val LEFT_ORDER = listOf(BarButton.FINISH, BarButton.CONTINUE, BarButton.COPY, BarButton.FALLBACK)

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

/**
 * 把流程的通知合并成至多一次待执行的渲染：已经排了一次还没执行时，再来的通知不再排。
 * 渲染开始前先清掉标记，渲染期间到达的通知会再排一次，不会漏掉最后的状态。
 */
internal class RenderCoalescer(private val schedule: (Runnable) -> Unit, private val render: () -> Unit) {
    private val pending = AtomicBoolean(false)

    fun request() {
        if (pending.compareAndSet(false, true)) {
            schedule {
                pending.set(false)
                render()
            }
        }
    }
}

// ---- 纯函数：提取出来方便单测 ----

internal const val MIN_INITIAL_HEIGHT = 360
internal const val MAX_INITIAL_HEIGHT = 520

/** 初始高度：内容高度加窗口其余部分，限制在 [MIN_INITIAL_HEIGHT]～[MAX_INITIAL_HEIGHT]（缩放后）。 */
internal fun initialHeight(contentHeight: Int, restOfWindow: Int): Int =
    (contentHeight + restOfWindow).coerceIn(JBUI.scale(MIN_INITIAL_HEIGHT), JBUI.scale(MAX_INITIAL_HEIGHT))

/** 底部按钮栏的按钮。 */
internal enum class BarButton(val labelKey: String) {
    CLOSE("clarify.close"),
    START("clarify.input.start"),
    CANCEL_INVESTIGATION("clarify.cancel"),
    FINISH("clarify.finish"),
    NEXT("clarify.question.next"),
    REVIEW("clarify.question.review"),
    SUBMIT("clarify.question.submit"),
    CONTINUE("clarify.result.back"),
    COPY("clarify.result.copy"),
    SEND("clarify.result.send"),
    CANCEL_SEND("clarify.sending.cancel"),
    FALLBACK("clarify.failed.fallback"),
    RETRY("clarify.retry"),
}

/**
 * 一个状态下的按钮栏：[left]、[right] 按屏幕上从左到右的顺序；[default] 是回车触发的默认按钮，
 * 只有取消类操作的状态（调查中、发送中）没有默认按钮。
 */
internal data class ButtonBar(val left: List<BarButton>, val right: List<BarButton>, val default: BarButton?)

/** 状态 → 按钮栏。只给出与当前状态相关的按钮（隐藏而不是禁用）；提问页的主按钮取决于停在哪一页。 */
internal fun buttonBar(state: ClarificationState, canFinish: Boolean, ledger: Ledger, page: AskingPage?): ButtonBar =
    when (state) {
        ClarificationState.Input -> ButtonBar(emptyList(), listOf(BarButton.CLOSE, BarButton.START), BarButton.START)
        is ClarificationState.Investigating ->
            ButtonBar(
                if (investigationKind(canFinish, ledger) == InvestigationKind.ROUND) listOf(BarButton.FINISH) else emptyList(),
                listOf(BarButton.CLOSE, BarButton.CANCEL_INVESTIGATION),
                null,
            )
        is ClarificationState.Asking -> {
            val primary =
                when (page) {
                    AskingPage.QUESTION -> BarButton.NEXT
                    AskingPage.LAST_QUESTION -> BarButton.REVIEW
                    AskingPage.SINGLE, AskingPage.REVIEW, null -> BarButton.SUBMIT
                }
            ButtonBar(if (canFinish) listOf(BarButton.FINISH) else emptyList(), listOf(BarButton.CLOSE, primary), primary)
        }
        is ClarificationState.Result -> ButtonBar(listOf(BarButton.CONTINUE, BarButton.COPY), listOf(BarButton.CLOSE, BarButton.SEND), BarButton.SEND)
        is ClarificationState.Sending -> ButtonBar(emptyList(), listOf(BarButton.CANCEL_SEND), null)
        is ClarificationState.Failed -> ButtonBar(listOf(BarButton.FALLBACK), listOf(BarButton.CLOSE, BarButton.RETRY), BarButton.RETRY)
    }

/**
 * 判断是否需要重建中间面板。以下情况就地更新而不重建：同一次调查只是进度变了；同一次发送只是等待原因变了；
 * 同一批问题（回答在草稿里，换标签只重建题目区域）。
 */
internal fun needsCenterRebuild(old: ClarificationState?, new: ClarificationState): Boolean {
    if (old == new) return false
    return when {
        old is ClarificationState.Asking && new is ClarificationState.Asking -> old.batch != new.batch
        old is ClarificationState.Investigating && new is ClarificationState.Investigating -> old.run != new.run || old.startedAt != new.startedAt
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
