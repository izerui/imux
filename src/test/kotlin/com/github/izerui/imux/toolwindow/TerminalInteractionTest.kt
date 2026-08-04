package com.github.izerui.imux.toolwindow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTextField

class TerminalInteractionTest {

    /** 模拟终端的组件树：外层容器套一个真正接收事件的子组件。 */
    private fun terminalTree(): Pair<JPanel, JTextField> {
        val inner = JTextField()
        val outer = JPanel().apply { add(inner) }
        return outer to inner
    }

    private fun mouseEvent(source: java.awt.Component, id: Int) =
        MouseEvent(source, id, 0L, 0, 1, 1, 1, false)

    private fun keyEvent(source: java.awt.Component, id: Int) =
        KeyEvent(source, id, 0L, 0, KeyEvent.VK_A, 'a')

    @Test
    fun `终端内的鼠标按下算作正在看`() {
        val (terminal, inner) = terminalTree()
        assertTrue(isViewingInteraction(mouseEvent(inner, MouseEvent.MOUSE_PRESSED), terminal))
    }

    @Test
    fun `终端内的键盘输入算作正在看`() {
        val (terminal, inner) = terminalTree()
        assertTrue(isViewingInteraction(keyEvent(inner, KeyEvent.KEY_PRESSED), terminal))
    }

    @Test
    fun `鼠标只是移入移出不算`() {
        // 光标扫过终端不代表在看它，只认真正的按下
        val (terminal, inner) = terminalTree()
        assertFalse(isViewingInteraction(mouseEvent(inner, MouseEvent.MOUSE_ENTERED), terminal))
        assertFalse(isViewingInteraction(mouseEvent(inner, MouseEvent.MOUSE_MOVED), terminal))
        assertFalse(isViewingInteraction(mouseEvent(inner, MouseEvent.MOUSE_RELEASED), terminal))
    }

    @Test
    fun `终端之外的交互不算`() {
        val (terminal, _) = terminalTree()
        val elsewhere = JTextField()
        assertFalse(isViewingInteraction(mouseEvent(elsewhere, MouseEvent.MOUSE_PRESSED), terminal))
        assertFalse(isViewingInteraction(keyEvent(elsewhere, KeyEvent.KEY_PRESSED), terminal))
    }

    @Test
    fun `事件源就是终端组件本身时也算`() {
        val (terminal, _) = terminalTree()
        assertTrue(isViewingInteraction(mouseEvent(terminal, MouseEvent.MOUSE_PRESSED), terminal))
    }
}
