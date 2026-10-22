package com.github.izerui.imux.clarification

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationDialogSourceTest {
    private val source = SourceCode("src/main/kotlin/com/github/izerui/imux/clarification/ClarificationDialog.kt")

    @Test
    fun `输入页不在 EDT 上查会话记录文件`() {
        val input = source.bodyAfter("private fun inputPanel()", '{')
        assertTrue(input.contains("service.checkBackground(sessionKey)"))
        assertTrue("结果回到 EDT 再改复选框", input.contains("invokeLater("))
        assertFalse(source.normalized.contains("service.hasBackground("))
    }

    @Test
    fun `生成结果按钮是否可用由流程判断`() {
        assertTrue(source.bodyAfter("private fun render()", '{').contains("finishButton.isEnabled = flow.canFinish"))
    }

    @Test
    fun `方向列表保留去掉的方向并显示理由`() {
        assertTrue(source.normalized.contains("dropReason"))
    }

    @Test
    fun `单栏布局不使用分栏和右键菜单`() {
        assertFalse("不再使用 JBSplitter 分栏", source.normalized.contains("JBSplitter"))
        assertFalse("不再使用 JBList 列表", source.normalized.contains("JBList"))
        assertFalse("不再使用 PopupHandler 右键菜单", source.normalized.contains("PopupHandler"))
        assertFalse("不再使用 ColoredListCellRenderer", source.normalized.contains("ColoredListCellRenderer"))
        assertFalse("不再使用 CollectionListModel", source.normalized.contains("CollectionListModel"))
    }

    @Test
    fun `中间区域只在状态变化时重建`() {
        val render = source.bodyAfter("private fun render()", '{')
        assertTrue("状态不变时跳过重建", render.contains("if (state == renderedState) return"))
        assertTrue("重建前记住当前状态", render.contains("renderedState = state"))
    }

    @Test
    fun `头部进度使用台账方向的统计`() {
        val renderHeader = source.bodyAfter("private fun renderHeader(", '{')
        assertTrue("使用 progressCounts 统计", renderHeader.contains("progressCounts(ledger.directions)"))
    }

    @Test
    fun `restart 先作废流程再通过服务重新绑定`() {
        assertTrue("对话框有 rebind 方法", source.normalized.contains("internal fun rebind(newFlow: ClarificationFlow)"))
        val rebind = source.bodyAfter("internal fun rebind(newFlow: ClarificationFlow)", '{')
        assertTrue("保存旧需求用于预填", rebind.contains("restartRequirement = flow.ledger.requirement"))
        assertTrue("移除旧监听", rebind.contains("flow.removeListener(listener)"))
        assertTrue("替换流程", rebind.contains("flow = newFlow"))
        assertTrue("注册新监听", rebind.contains("flow.addListener(listener)"))
    }

    @Test
    fun `输入页在 restart 后预填原需求`() {
        val input = source.bodyAfter("private fun inputPanel()", '{')
        assertTrue("优先取 restartRequirement", input.contains("restartRequirement"))
    }
}
