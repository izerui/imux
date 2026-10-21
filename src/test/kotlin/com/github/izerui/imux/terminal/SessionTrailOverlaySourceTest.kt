package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTrailOverlaySourceTest {
    private val editor = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt")
    private val overlay = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/SessionTrailOverlay.kt")

    @Test
    fun `轨迹面板叠在终端上层，不进入终端布局`() {
        assertTrue(editor.normalized.contains("setLayer(trailOverlay.chip, JLayeredPane.PALETTE_LAYER)"))
        assertTrue(editor.normalized.contains("setLayer(trailOverlay.card, JLayeredPane.PALETTE_LAYER)"))
        assertTrue("终端仍占满正文", editor.normalized.contains("terminal.setBounds(0, 0, width, height)"))
        assertTrue(
            "面板让出导航轨道",
            editor.normalized.contains("trailOverlay.layout(width, height, navigatorWidth + JBUI.scale(NAVIGATOR_RIGHT_INSET))"),
        )
    }

    @Test
    fun `只有标签页真正关闭才取消轨迹生成`() {
        val dispose = editor.bodyAfter("override fun dispose()", '{')
        val reopen = dispose.indexOf("CLOSING_TO_REOPEN")
        val release = dispose.indexOf(".releaseOwner(virtualFile.tabId)")
        assertTrue("拖动标签页、分屏重建时不能取消", reopen >= 0 && release > reopen)
        assertFalse(
            "按会话 key 清理会漏掉 /clear、/new 迁移前的任务，必须按稳定的标签页身份清理",
            dispose.contains(".forget(sessionKey)"),
        )
    }

    @Test
    fun `面板以标签页身份发起生成`() {
        assertTrue(overlay.normalized.contains("store.generate(key, virtualFile.tabId)"))
    }

    @Test
    fun `面板跟随会话 key 迁移，且状态与任务不存在面板里`() {
        assertTrue(overlay.normalized.contains("addSessionKeyMigratedListener(this)"))
        assertTrue("每次读取当前 key", overlay.normalized.contains("private val key get() = virtualFile.sessionKey"))
        assertFalse("面板不能自己起协程任务", overlay.normalized.contains(".launch("))
    }

    @Test
    fun `收起按钮没有点击死区且点击区域足够大`() {
        assertFalse(
            "InplaceButton 在鼠标刚移入时会吞掉点击（TimedDeadzone），收起按钮显得不灵敏",
            overlay.normalized.contains("InplaceButton("),
        )
        assertTrue(overlay.normalized.contains("ActionButton( collapseAction,"))
    }

    @Test
    fun `面板上的按钮不抢终端焦点`() {
        assertTrue(overlay.normalized.contains("isFocusable = false"))
        assertFalse(overlay.normalized.contains("requestFocus"))
    }
}
