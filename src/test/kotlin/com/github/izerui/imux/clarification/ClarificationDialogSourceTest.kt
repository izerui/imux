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
    fun `方向列表保留去掉的方向并原样显示理由`() {
        // directionRow 在 DROPPED 时把 dropReason 写入 detailLabel，不跳过也不过滤
        val row = source.bodyAfter("private fun directionRow(", '{')
        assertTrue("DROPPED 方向的标题标灰", row.contains("DirectionStatus.DROPPED"))
        assertTrue("drop 理由原样写入标签", row.contains("dropReason"))
        // renderList 遍历全部 directions，不排除 DROPPED
        val list = source.bodyAfter("private fun renderList(", '{')
        assertFalse("列表不过滤掉 DROPPED 方向", list.contains("DirectionStatus.DROPPED"))
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
        assertTrue("使用 needsCenterRebuild 判断", render.contains("needsCenterRebuild("))
        assertTrue("重建前记住当前状态", render.contains("renderedState = state"))
    }

    @Test
    fun `头部进度使用台账方向的统计`() {
        val renderHeader = source.bodyAfter("private fun renderHeader(", '{')
        assertTrue("使用 progressCounts 统计", renderHeader.contains("progressCounts(ledger.directions)"))
    }

    @Test
    fun `rebind 保存旧需求并切换监听`() {
        assertTrue("对话框有 rebind 方法", source.normalized.contains("internal fun rebind(newFlow: ClarificationFlow)"))
        val rebind = source.bodyAfter("internal fun rebind(newFlow: ClarificationFlow)", '{')
        assertTrue("保存旧需求用于预填", rebind.contains("restartRequirement = flow.ledger.requirement"))
        val removeIdx = rebind.indexOf("flow.removeListener(listener)")
        val assignIdx = rebind.indexOf("flow = newFlow")
        val addIdx = rebind.indexOf("flow.addListener(listener)")
        assertTrue("移除旧监听 → 替换流程 → 注册新监听", removeIdx >= 0 && assignIdx > removeIdx && addIdx > assignIdx)
    }

    @Test
    fun `输入页在 restart 后预填原需求`() {
        val input = source.bodyAfter("private fun inputPanel()", '{')
        assertTrue("优先取 restartRequirement", input.contains("restartRequirement"))
    }

    @Test
    fun `我的理解仅在首批第一题且用 JButton 纠正`() {
        val asking = source.bodyAfter("private fun askingPanel(", '{')
        assertTrue("纠正理解使用 JButton", asking.contains("JButton(ImuxBundle.message(\"clarify.understanding.correct\"))"))
        assertFalse("纠正理解不使用 ActionLink", asking.contains("ActionLink(ImuxBundle.message(\"clarify.understanding.correct\"))"))
        assertTrue("只在 index == 0 时展示理解", asking.contains("asking.index == 0"))
    }

    @Test
    fun `对话框弹窗的父组件是 contentPane`() {
        // Messages.showYesNoDialog / showInputDialog / showInfoMessage 的第一个参数是 contentPane
        assertTrue("restart 确认弹窗用 contentPane", source.bodyAfter("private fun confirmRestart()", '{').contains("contentPane"))
        assertTrue("添加方向弹窗用 contentPane", source.bodyAfter("private fun renderList(", '{').contains("contentPane"))
        assertTrue("查看 Agent 输出弹窗用 contentPane", source.bodyAfter("private fun failedPanel(", '{').contains("contentPane"))
    }
}
