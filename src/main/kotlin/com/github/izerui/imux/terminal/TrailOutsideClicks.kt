package com.github.izerui.imux.terminal

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/**
 * 在会话标签页里、轨迹卡片之外按下鼠标时收起面板。
 *
 * 只在标签页自己的组件上挂局部监听（项目约定不用全局 AWT 监听）。Swing 鼠标事件只投给
 * 最深的组件，所以连子组件一起挂。监听只观察、不消费事件，被点的组件照常工作。
 *
 * - [attach]：标签页里固定不变的组件（导航轨道、回到底部按钮、提示条）。
 * - [bindEditor]：当前终端编辑器的正文与滚动条。TUI 切换 alternate buffer 时编辑器会换，
 *   由编辑器在切换时重新绑定，旧的随之解绑。
 */
internal class TrailOutsideClicks(private val onOutsideClick: () -> Unit) {
    private val listener =
        object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = onOutsideClick()
        }
    private val fixed = mutableSetOf<Component>()
    private var editorTargets = emptySet<Component>()

    fun attach(component: Component) {
        attachTree(component, fixed)
    }

    fun bindEditor(targets: List<Component>) {
        editorTargets.forEach(::detach)
        val next = mutableSetOf<Component>()
        targets.forEach { attachTree(it, next) }
        editorTargets = next
    }

    fun dispose() {
        (fixed + editorTargets).forEach(::detach)
        fixed.clear()
        editorTargets = emptySet()
    }

    private fun attachTree(component: Component, into: MutableSet<Component>) {
        if (component.mouseListeners.none { it === listener }) component.addMouseListener(listener)
        into += component
        (component as? Container)?.components?.forEach { attachTree(it, into) }
    }

    private fun detach(component: Component) {
        component.removeMouseListener(listener)
    }
}

/** 终端编辑器里不属于轨迹卡片的可点区域：正文与两个方向的滚动条。 */
internal fun terminalClickTargets(editor: Editor): List<Component> {
    val scrollPane = (editor as? EditorEx)?.scrollPane
    return listOfNotNull(editor.contentComponent, scrollPane?.verticalScrollBar, scrollPane?.horizontalScrollBar)
}
