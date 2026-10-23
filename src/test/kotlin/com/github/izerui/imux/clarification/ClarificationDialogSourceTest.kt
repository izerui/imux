package com.github.izerui.imux.clarification

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationDialogSourceTest {
    private val dialog = SourceCode("src/main/kotlin/com/github/izerui/imux/clarification/ClarificationDialog.kt")
    private val view = SourceCode("src/main/kotlin/com/github/izerui/imux/clarification/ClarificationView.kt")

    @Test
    fun `输入页不再提供附带最近对话的选项`() {
        assertFalse("不再检查背景记录", dialog.normalized.contains("checkBackground"))
        assertFalse("不再显示背景复选框", view.normalized.contains("clarify.input.background"))
    }

    @Test
    fun `流程通知经合并后才渲染`() {
        assertTrue("监听只是请求一次渲染", dialog.normalized.contains("private val listener: () -> Unit = coalescer::request"))
        assertTrue("合并后的渲染在任意模态下回到 EDT", dialog.normalized.contains("RenderCoalescer({ ApplicationManager.getApplication().invokeLater(it, ModalityState.any()) }) { render() }"))
    }

    @Test
    fun `按钮栏只在变化时更新，隐藏而不是禁用，并切换默认按钮`() {
        val update = dialog.bodyAfter("private fun updateButtons()", '{')
        assertTrue("按状态与提问页所在页决定", update.contains("buttonBar(shown, view.askingPage())"))
        assertTrue("没变就不动", update.contains("if (bar == lastBar) return"))
        assertTrue("隐藏而不是禁用", update.contains(".isVisible = button in visible"))
        assertFalse("不靠禁用按钮表达不可用", update.contains("isEnabled"))
        assertTrue("切换默认按钮，没有默认按钮时清空", update.contains("rootPane?.defaultButton = bar.default?.let(::buttonOf)"))
        assertTrue("换标签时更新按钮栏", dialog.normalized.contains("override fun askingPageChanged() = updateButtons()"))
    }

    @Test
    fun `全部按钮放在原生按钮栏，内容区里没有按钮栏`() {
        assertTrue("右侧按钮来自 createActions", dialog.normalized.contains("override fun createActions(): Array<Action>"))
        assertTrue("左侧按钮来自 createLeftSideActions", dialog.normalized.contains("override fun createLeftSideActions(): Array<Action>"))
        assertFalse("视图里不自己排一行按钮", view.normalized.contains("FlowLayout"))
    }

    @Test
    fun `删掉的功能在视图与对话框里都不再出现`() {
        for ((name, source) in listOf("ClarificationDialog" to dialog, "ClarificationView" to view)) {
            for (gone in listOf("collapsibleGroup", "WrappingRadioButton", "pinDirection", "reopenDirection", "addDirection", "skipDirection", "editRequirement", "rebind(", "correction", "understanding.title", "FINISH", "canFinish", "clarify.finish")) {
                assertFalse("$name：不再有 $gone", source.normalized.contains(gone))
            }
        }
    }

    @Test
    fun `标签条用平台的 JBTabbedPane，内容区用 UI DSL 且没有写死颜色`() {
        assertTrue(view.normalized.contains("JBTabbedPane()"))
        assertTrue(view.normalized.contains("import com.intellij.ui.dsl.builder.panel"))
        assertTrue("已答标记用官方图标", view.normalized.contains("AllIcons.General.GreenCheckmark"))
        assertFalse("不写死颜色", view.normalized.contains("Color("))
        assertFalse("不写死颜色", view.normalized.contains("JBColor("))
    }

    @Test
    fun `中间区域只在需要时重建，否则就地更新`() {
        val render = view.bodyAfter("fun render(", '{')
        assertTrue("使用 needsCenterRebuild 判断", render.contains("needsCenterRebuild(old, state)"))
        assertTrue("不重建时就地更新", render.contains("updateInPlace(state)"))
    }

    @Test
    fun `关闭时停计时器、摘监听、通知服务`() {
        val dispose = dialog.bodyAfter("override fun dispose()", '{')
        assertTrue("停计时器", dispose.contains("elapsedTimer.stop()"))
        assertTrue("摘监听", dispose.contains("flow.removeListener(listener)"))
        assertTrue("通知服务", dispose.contains("service.dialogClosed(sessionKey)"))
    }

    @Test
    fun `草稿存在流程上，重建提问页时接着用`() {
        assertTrue(dialog.normalized.contains("flow.drafts?.takeIf { it.batch == batch } ?: BatchDrafts(batch).also { flow.drafts = it }"))
    }

    @Test
    fun `答好了继续带上视图里已填写的回答`() {
        val perform = dialog.bodyAfter("private fun perform(", '{')
        assertTrue(perform.contains("BarButton.SUBMIT -> service.submit(sessionKey, view.answers())"))
        assertFalse("没有够了，生成结果", perform.contains("FINISH"))
    }

    @Test
    fun `关闭前只在会丢掉回答或结果时确认一次，弹窗以对话框为父组件`() {
        val cancel = dialog.bodyAfter("override fun doCancelAction()", '{')
        assertTrue("按状态、记录、已填写回答与保留方案判断", cancel.contains("closeNeedsConfirmation(flow.state, flow.record, view.answers().isNotEmpty(), flow.hasRetainedPlan())"))
        assertTrue("以对话框为父组件", cancel.contains("Messages.showYesNoDialog("))
        assertTrue("以对话框为父组件", cancel.contains("contentPane,"))
        assertTrue("不确认就不关", cancel.contains("if (choice != Messages.YES) return"))
        assertTrue("确认后照常关闭", cancel.contains("super.doCancelAction()"))
    }

    @Test
    fun `查看 Agent 输出的弹窗以对话框为父组件`() {
        assertTrue(dialog.normalized.contains("Messages.showInfoMessage(contentPane"))
    }
}
