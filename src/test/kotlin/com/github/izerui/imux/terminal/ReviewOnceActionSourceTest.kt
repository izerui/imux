package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewOnceActionSourceTest {
    private val editor = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt")

    @Test
    fun `审查一次从图标组的结对编程菜单进入`() {
        val menu = editor.bodyAfter("private fun showPeerMenu(anchor: JComponent)", '{')
        assertTrue("图标组的结对编程菜单应打开当前会话的审查弹窗", menu.contains("showReviewOnceDialog(project, virtualFile)"))
    }
}
