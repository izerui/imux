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
    fun `面板展开时先触发首次生成再读取显示状态`() {
        val refresh = overlay.bodyAfter("private fun refresh()", '{')
        val generate = refresh.indexOf("if (expanded) store.generateIfNeeded(key, virtualFile.tabId)")
        assertTrue("折叠时不自动生成", generate >= 0)
        assertTrue("首次展开直接显示生成进度", refresh.indexOf("val state = store.state(key)") > generate)
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
    fun `编辑器把正文、滚动条和叠加控件接到收起监听，随编辑器切换重绑并在早退前解绑`() {
        val create = editor.bodyAfter("private fun createEditorComponent()", '{')
        for (target in listOf("navigatorComponent", "scrollButton", "peerBanner")) {
            assertTrue("点 $target 也算点在轨迹之外", create.contains("trailOutsideClicks.attach($target)"))
        }
        assertTrue(
            "终端编辑器切换时重绑正文与滚动条",
            editor.normalized.contains("trailOutsideClicks.bindEditor(editor?.let(::terminalClickTargets).orEmpty())"),
        )
        val dispose = editor.bodyAfter("override fun dispose()", '{')
        val unbind = dispose.indexOf("trailOutsideClicks.dispose()")
        assertTrue("拖动重建时终端被沿用，必须在早退前解绑", unbind >= 0 && unbind < dispose.indexOf("CLOSING_TO_REOPEN"))
        assertFalse("卡片本身不能挂收起监听", editor.normalized.contains("trailOutsideClicks.attach(trailOverlay"))
        assertTrue(overlay.normalized.contains("fun collapse() = setExpanded(false)"))
        assertFalse("项目约定不用全局 AWT 监听", editor.normalized.contains("addAWTEventListener"))
    }

    @Test
    fun `面板上的按钮不抢终端焦点`() {
        assertTrue(overlay.normalized.contains("isFocusable = false"))
        assertFalse(overlay.normalized.contains("requestFocus"))
    }
}
