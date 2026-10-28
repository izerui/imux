package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBannerHostSourceTest {
    private val editor = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt")
    private val overlay = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/SessionTrailOverlay.kt").normalized

    @Test
    fun `终端顶部不再有常驻横幅，提示条叠在终端上层不改变终端尺寸`() {
        assertFalse("不能再把横幅放进终端上方的布局", editor.normalized.contains("BorderLayout.NORTH"))
        val create = editor.bodyAfter("private fun createEditorComponent()", '{')
        assertTrue(create.contains("return terminalLayer"))
        assertTrue("提示条压在导航轨道之上", create.contains("setLayer(notices, JLayeredPane.PALETTE_LAYER + 1)"))
        assertTrue("终端仍占满正文", create.contains("terminal.setBounds(0, 0, width, height)"))
    }

    @Test
    fun `其它功能以按钮加进浮动图标组，提示发到手机来源`() {
        assertTrue(editor.normalized.contains("SessionDecorations.tools(project, virtualFile, this, noticeOverlay.sink(NoticeSource.PHONE))"))
        assertTrue(editor.normalized.contains("setLayer(toolStrip.component, JLayeredPane.PALETTE_LAYER)"))
    }

    @Test
    fun `会话轨迹是图标组里的开关按钮，不挂点外面收起`() {
        assertTrue(editor.normalized.contains("listOf(ToolPill(pairTool, pinned = true), ToolPill(talkTool), ToolPill(trailOverlay.tool))"))
        assertTrue(editor.normalized.contains("toolButtons.filter { it.tool !== trailOverlay.tool }.forEach(trailOutsideClicks::attach)"))
        assertTrue("展开时按钮呈按下状态", overlay.contains("selected = virtualFile.trailExpanded"))
        assertTrue(overlay.contains("setExpanded(!virtualFile.trailExpanded)"))
        assertFalse("横幅段已经不存在", overlay.contains("bannerSegment"))
    }

    @Test
    fun `图标组排在提示条下面，轨迹卡片再往下`() {
        val create = editor.bodyAfter("private fun createEditorComponent()", '{')
        assertTrue(create.contains("val stripY = noticeHeight + margin"))
        assertTrue("图标组宽度受终端宽度约束，不能直接用 preferredSize", create.contains("val strip = toolStrip.fit(stripRoom)"))
        assertTrue(create.contains("val stripRoom = (width - rightReserve - margin * 2).coerceAtLeast(0)"))
        assertTrue(create.contains("trailOverlay.layout(width, height, rightReserve, stripY + strip.height)"))
    }

    @Test
    fun `拖选终端文字时藏起图标组，松开后恢复，监听随编辑器切换摘除`() {
        val drag = editor.bodyAfter("object : EditorMouseMotionListener", '{')
        assertTrue(drag.contains("toolStrip?.component?.isVisible = false"))
        val release = editor.bodyAfter("override fun mouseReleased(event: EditorMouseEvent)", '{')
        assertTrue(release.contains("toolStrip?.component?.isVisible = true"))
        assertTrue(editor.bodyAfter("private fun refreshScrollButton()", '{').contains("removeEditorMouseMotionListener(editorDragListener)"))
        assertTrue(editor.bodyAfter("override fun dispose()", '{').contains("removeEditorMouseMotionListener(editorDragListener)"))
    }

    @Test
    fun `第一次看到图标组时用平台的 GotIt 说明一次`() {
        val gotIt = editor.bodyAfter("private fun showToolsGotIt()", '{')
        assertTrue(gotIt.contains("GotItTooltip(TOOLS_GOT_IT_ID,"))
        assertTrue(gotIt.contains(".show(target) { component, _ -> Point(component.width / 2, component.height) }"))
        assertTrue("不用挂在内部 Companion 上的锚点常量", !gotIt.contains("GotItTooltip.BOTTOM_MIDDLE"))
    }
}
