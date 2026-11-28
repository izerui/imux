package com.github.izerui.imux.terminal

import com.intellij.openapi.util.Disposer
import org.junit.Assert.*
import org.junit.Test
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Marker panels replace the native search widget; additions, removals and visibility events are real Swing events. */
class TerminalOverlayPaneTest {
    private class Fixture {
        val owner = Disposer.newDisposable()
        val terminal = JPanel(null)
        val output = JLayeredPane().apply { setBounds(0, 0, 800, 400) }
        val search = JPanel().apply { putClientProperty("test.search", true) }
        val wrapper = JPanel(null).apply {
            setBounds(500, 0, 300, 32)
            search.setBounds(0, 0, 300, 32)
            add(search)
        }
        val overlays: TerminalOverlayPane
        val buttons = JPanel().apply { setBounds(600, 36, 150, 28) }
        val host = JLayeredPane()

        init {
            terminal.setBounds(0, 0, 800, 400)
            terminal.add(output)
            overlays = TerminalOverlayPane(terminal, owner, ::isSearch).apply {
                setBounds(0, 0, 800, 400)
                add(buttons)
            }
            host.setSize(800, 400)
            host.add(terminal)
            host.setLayer(terminal, JLayeredPane.DEFAULT_LAYER)
            host.add(overlays)
            host.setLayer(overlays, JLayeredPane.PALETTE_LAYER)
        }

        fun open() {
            output.add(wrapper)
            output.setLayer(wrapper, JLayeredPane.POPUP_LAYER)
        }
        fun close() = output.remove(wrapper)
        fun dispose() = Disposer.dispose(owner)
    }

    companion object {
        private fun isSearch(component: Component): Boolean =
            (component as? JComponent)?.getClientProperty("test.search") == true

        private fun edt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
    }

    @Test
    fun `搜索框加入终端时隐藏浮层并把鼠标交给搜索框，移除后恢复`() = edt {
        val f = Fixture()
        try {
            assertTrue(f.overlays.isVisible)
            f.open()
            assertFalse("不依赖窗口重排就应该隐藏", f.overlays.isVisible)
            assertSame(f.search, SwingUtilities.getDeepestComponentAt(f.host, 510, 10))
            f.close()
            assertTrue(f.overlays.isVisible)
            assertSame(f.buttons, SwingUtilities.getDeepestComponentAt(f.host, 610, 40))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `透明浮层空白区域不拦截终端鼠标，隐藏的按钮不拦截鼠标`() = edt {
        val f = Fixture()
        try {
            assertFalse(f.overlays.contains(20, 200))
            assertTrue(f.overlays.contains(610, 40))
            f.buttons.isVisible = false
            assertFalse(f.overlays.contains(610, 40))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `搜索结束不覆盖拖选期间隐藏按钮的状态`() = edt {
        val f = Fixture()
        try {
            f.buttons.isVisible = false
            f.open()
            f.close()
            f.host.doLayout()
            assertTrue(f.overlays.isVisible)
            assertFalse("搜索结束不是鼠标松开", f.buttons.isVisible)
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `搜索期间松开拖选和新增提示都不能重新显示浮层`() = edt {
        val f = Fixture()
        try {
            f.open()
            f.buttons.isVisible = false
            f.buttons.isVisible = true
            assertFalse("鼠标松开不能盖住搜索框", f.overlays.isVisible)
            val notice = JPanel()
            f.overlays.add(notice)
            notice.isVisible = true
            f.host.doLayout()
            assertFalse("新提示不能盖住搜索框", f.overlays.isVisible)
            f.close()
            assertTrue(f.overlays.isVisible)
            assertTrue(f.buttons.isVisible)
            assertTrue(notice.isVisible)
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `已经存在的搜索框会在创建浮层时立即生效`() = edt {
        val owner = Disposer.newDisposable()
        try {
            val terminal = JPanel()
            terminal.add(JPanel().apply { putClientProperty("test.search", true) })
            assertFalse(TerminalOverlayPane(terminal, owner, ::isSearch).isVisible)
        } finally {
            Disposer.dispose(owner)
        }
    }

    @Test
    fun `搜索框在后加入的嵌套面板里出现也能触发，移除父面板即恢复`() = edt {
        val f = Fixture()
        try {
            val nested = JPanel()
            f.output.add(nested)
            nested.add(f.wrapper)
            assertFalse(f.overlays.isVisible)
            f.output.remove(nested)
            assertTrue(f.overlays.isVisible)
            nested.remove(f.wrapper)
            nested.add(f.wrapper)
            assertTrue("已脱离终端的旧组件不能继续影响浮层", f.overlays.isVisible)
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `搜索框父面板隐藏后恢复浮层，再显示时隐藏浮层`() {
        lateinit var f: Fixture
        edt { f = Fixture(); f.open(); f.wrapper.isVisible = false }
        try {
            edt { assertTrue(f.overlays.isVisible); f.wrapper.isVisible = true }
            edt { assertFalse(f.overlays.isVisible) }
        } finally {
            edt { f.dispose() }
        }
    }

    @Test
    fun `隐藏的旧搜索框不掩盖另一个可见搜索框`() = edt {
        val f = Fixture()
        try {
            f.search.isVisible = false
            f.open()
            assertTrue(f.overlays.isVisible)
            val active = JPanel().apply { putClientProperty("test.search", true) }
            f.output.add(active)
            assertFalse(f.overlays.isVisible)
            f.output.remove(active)
            assertTrue(f.overlays.isVisible)
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `标签页暂时不可见不等于搜索已关闭`() {
        lateinit var f: Fixture
        edt { f = Fixture(); f.open(); f.terminal.isVisible = false }
        try {
            edt {
                assertFalse(f.overlays.isVisible)
                f.terminal.isVisible = true
            }
            edt {
                assertFalse("重新显示标签时搜索仍在，浮层不能盖回来", f.overlays.isVisible)
                f.close()
                assertTrue(f.overlays.isVisible)
            }
        } finally {
            edt { f.dispose() }
        }
    }

    @Test
    fun `关闭编辑器后摘除终端子树监听且不再响应旧组件事件`() = edt {
        val f = Fixture()
        f.open()
        f.dispose()
        assertEquals(0, f.terminal.containerListeners.size)
        assertEquals(0, f.output.containerListeners.size)
        assertEquals(0, f.wrapper.containerListeners.size)
        assertEquals(0, f.search.componentListeners.size)
        f.close()
        assertFalse("已销毁观察器不再改状态", f.overlays.isVisible)
    }
}
