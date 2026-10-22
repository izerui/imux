package com.github.izerui.imux.clarification

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationDialogSourceTest {
    private val dialog = SourceCode("src/main/kotlin/com/github/izerui/imux/clarification/ClarificationDialog.kt")
    private val view = SourceCode("src/main/kotlin/com/github/izerui/imux/clarification/ClarificationView.kt")

    @Test
    fun `输入页不在 EDT 上查会话记录文件`() {
        val check = dialog.bodyAfter("private fun checkBackground()", '{')
        assertTrue("交给服务在 IO 线程查", check.contains("service.checkBackground(sessionKey)"))
        assertTrue("结果回到 EDT 再改复选框", check.contains("invokeLater("))
        assertFalse(dialog.normalized.contains("service.hasBackground("))
        assertTrue("进入输入页时才查", dialog.bodyAfter("private fun render()", '{').contains("checkBackground()"))
    }

    @Test
    fun `按钮栏由状态与流程的 canFinish 决定`() {
        val render = dialog.bodyAfter("private fun render()", '{')
        assertTrue("每次渲染按 buttonBar 更新按钮", render.contains("updateButtons(buttonBar(state, flow.canFinish, ledger))"))
        val update = dialog.bodyAfter("private fun updateButtons(", '{')
        assertTrue("隐藏而不是禁用", update.contains(".isVisible = button in shown"))
        assertFalse("不靠禁用按钮表达不可用", update.contains("isEnabled"))
        assertTrue("切换默认按钮", update.contains("rootPane?.defaultButton = buttonOf(bar.default)"))
    }

    @Test
    fun `全部按钮放在原生按钮栏，内容区里没有按钮栏`() {
        assertTrue("右侧按钮来自 createActions", dialog.normalized.contains("override fun createActions(): Array<Action>"))
        assertTrue("左侧按钮来自 createLeftSideActions", dialog.normalized.contains("override fun createLeftSideActions(): Array<Action>"))
        assertFalse("视图里不再自己排一行按钮", view.normalized.contains("FlowLayout"))
        assertFalse("视图里不使用手搭的 VerticalFlowLayout", view.normalized.contains("VerticalFlowLayout"))
    }

    @Test
    fun `单栏布局不使用分栏和右键菜单`() {
        for (source in listOf(dialog, view)) {
            assertFalse("不再使用 JBSplitter 分栏", source.normalized.contains("JBSplitter"))
            assertFalse("不再使用 JBList 列表", source.normalized.contains("JBList"))
            assertFalse("不再使用 PopupHandler 右键菜单", source.normalized.contains("PopupHandler"))
        }
    }

    @Test
    fun `内容区用 UI DSL 构建且没有写死颜色`() {
        assertTrue(view.normalized.contains("import com.intellij.ui.dsl.builder.panel"))
        assertTrue("方向清单用折叠分组", view.normalized.contains("collapsibleGroup(listTitle("))
        assertFalse("不写死颜色", view.normalized.contains("Color("))
        assertFalse("不写死颜色", view.normalized.contains("JBColor("))
    }

    @Test
    fun `中间区域只在状态变化时重建`() {
        val render = view.bodyAfter("fun render(", '{')
        assertTrue("使用 needsCenterRebuild 判断", render.contains("needsCenterRebuild(old, state)"))
        assertTrue("不重建时就地更新", render.contains("updateInPlace(state)"))
    }

    @Test
    fun `rebind 保存旧需求并切换监听`() {
        assertTrue("对话框有 rebind 方法", dialog.normalized.contains("internal fun rebind(newFlow: ClarificationFlow)"))
        val rebind = dialog.bodyAfter("internal fun rebind(newFlow: ClarificationFlow)", '{')
        assertTrue("保存旧需求用于预填", rebind.contains("restartRequirement = flow.ledger.requirement"))
        val removeIdx = rebind.indexOf("flow.removeListener(listener)")
        val assignIdx = rebind.indexOf("flow = newFlow")
        val addIdx = rebind.indexOf("flow.addListener(listener)")
        assertTrue("移除旧监听 → 替换流程 → 注册新监听", removeIdx >= 0 && assignIdx > removeIdx && addIdx > assignIdx)
    }

    @Test
    fun `输入页在 restart 后预填原需求`() {
        val render = dialog.bodyAfter("private fun render()", '{')
        assertTrue("优先取 restartRequirement", render.contains("restartRequirement ?: ledger.requirement"))
    }

    @Test
    fun `监听在任意模态下回到 EDT 渲染，关闭时摘掉`() {
        assertTrue(dialog.normalized.contains("invokeLater({ render() }, ModalityState.any())"))
        val dispose = dialog.bodyAfter("override fun dispose()", '{')
        assertTrue("停计时器", dispose.contains("elapsedTimer.stop()"))
        assertTrue("摘监听", dispose.contains("flow.removeListener(listener)"))
        assertTrue("通知服务", dispose.contains("service.dialogClosed(sessionKey)"))
    }

    @Test
    fun `对话框弹窗的父组件是 contentPane`() {
        assertTrue("restart 确认弹窗用 contentPane", dialog.bodyAfter("private fun confirmRestart()", '{').contains("contentPane"))
        assertTrue("添加方向弹窗用 contentPane", dialog.bodyAfter("override fun addDirection()", '{').contains("Messages.showInputDialog(contentPane"))
        assertTrue("查看 Agent 输出弹窗用 contentPane", dialog.normalized.contains("Messages.showInfoMessage(contentPane"))
    }
}
