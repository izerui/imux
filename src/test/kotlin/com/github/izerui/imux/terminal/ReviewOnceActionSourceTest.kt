package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ReviewOnceActionSourceTest {
    private val action = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/ReviewOnceAction.kt")
    private val editor = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt")

    @Test
    fun `审查一次注册在终端正文菜单的结对编程之后`() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/resources/META-INF/plugin.xml"))
        val actions = document.getElementsByTagName("action")
        val review = (0 until actions.length).map { actions.item(it) as Element }
            .single { it.getAttribute("id") == "imux.ReviewOnce" }
        assertEquals("com.github.izerui.imux.terminal.ReviewOnceAction", review.getAttribute("class"))
        val placement = review.getElementsByTagName("add-to-group").item(0) as Element
        assertEquals("Terminal.ReworkedTerminalContextMenu", placement.getAttribute("group-id"))
        assertEquals("after", placement.getAttribute("anchor"))
        assertEquals("imux.PeerProgramming", placement.getAttribute("relative-to-action"))
    }

    @Test
    fun `右键和提示条共用当前会话的审查弹窗`() {
        val invoke = action.bodyAfter("override fun actionPerformed(event: AnActionEvent)", '{')
        assertTrue("右键应把当前终端文件交给弹窗", invoke.contains("showReviewOnceDialog(project, file)"))
        val menu = editor.bodyAfter("private fun showPeerMenu(anchor: JComponent)", '{')
        assertTrue("图标组的结对编程菜单应使用同一入口", menu.contains("showReviewOnceDialog(project, virtualFile)"))
    }

    @Test
    fun `右键审查入口不受顶部提示隐藏设置影响`() {
        val update = action.bodyAfter("override fun update(event: AnActionEvent)", '{')
        assertTrue("有效会话应保留菜单项", update.contains("event.presentation.isVisible = file != null"))
        assertTrue("已有副驾驶绑定时应禁用单次审查", update.contains("boundTarget(file.sessionKey) == null"))
        assertTrue("菜单不能跟着提示条一起消失", !action.normalized.contains("peerBannerDismissed"))
    }
}
