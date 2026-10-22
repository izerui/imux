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
        assertFalse(source.bodyAfter("private fun render()", '{').contains("DirectionStatus.DROPPED"))
        assertTrue(source.normalized.contains("value.dropReason"))
    }
}
