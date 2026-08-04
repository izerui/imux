package com.github.izerui.imux.toolwindow

import java.awt.AWTEvent
import java.awt.Component
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * 这个 AWT 事件是否意味着「用户此刻正在看 [terminal] 这个终端」。
 *
 * 用来消除未读标记：正被查看的会话跑完一轮也会打标记（否则人不在屏幕前就完全无感），
 * 而人一旦回来动它，标记就该灭掉。
 *
 * 只认按下与键入，不认移入移出与抬起：光标扫过终端不代表在看它。
 *
 * 为什么要判断「事件源是否在终端组件树内」而不是直接给终端挂 MouseListener：
 * Swing 的鼠标事件只投递给最深的那个组件，不像 DOM 会往上冒泡，
 * 挂在容器上根本收不到子组件里的点击。
 */
internal fun isViewingInteraction(event: AWTEvent, terminal: Component): Boolean {
    val source = when (event) {
        // 刻意只读事件的来源组件，不读 keyChar/keyCode——
        // 这是个全局监听器，任何时候都不该去看用户敲了什么
        is KeyEvent -> if (event.id == KeyEvent.KEY_PRESSED) event.component else null
        is MouseEvent -> if (event.id == MouseEvent.MOUSE_PRESSED) event.component else null
        else -> null
    } ?: return false

    return source === terminal || SwingUtilities.isDescendingFrom(source, terminal)
}
