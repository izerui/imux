package com.github.izerui.imux.terminal

import com.intellij.find.SearchReplaceComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLayeredPane
import java.awt.Component
import java.awt.Container
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The 262 terminal adds/removes its search wrapper inside its own layered pane. TerminalView has no
 * public search listener, so observe Swing events within that component tree instead of using its
 * internal search controller. Watching new subtrees also handles replacement of the output editor.
 */
internal class TerminalOverlayPane(
    private val terminal: Container,
    parentDisposable: Disposable,
    private val isSearchComponent: (Component) -> Boolean = { it is SearchReplaceComponent },
) : JBLayeredPane(), Disposable {
    private val observed = Collections.newSetFromMap(IdentityHashMap<Component, Boolean>())
    private var disposed = false
    private val visibilityListener = object : ComponentAdapter() {
        override fun componentShown(event: ComponentEvent) = refreshVisibility()
        override fun componentHidden(event: ComponentEvent) = refreshVisibility()
    }
    private val treeListener = object : ContainerAdapter() {
        override fun componentAdded(event: ContainerEvent) {
            if (disposed) return
            observe(event.child)
            refreshVisibility()
        }

        override fun componentRemoved(event: ContainerEvent) {
            if (disposed) return
            unobserve(event.child)
            refreshVisibility()
        }
    }

    init {
        isOpaque = false
        Disposer.register(parentDisposable, this)
        observe(terminal)
        refreshVisibility()
    }

    // An empty transparent panel must not steal clicks from the terminal below it.
    override fun contains(x: Int, y: Int): Boolean =
        isVisible && components.any { it.isVisible && it.contains(x - it.x, y - it.y) }

    private fun observe(component: Component) {
        if (!observed.add(component)) return
        component.addComponentListener(visibilityListener)
        if (component is Container) {
            component.addContainerListener(treeListener)
            component.components.forEach(::observe)
        }
    }

    private fun unobserve(component: Component) {
        if (!observed.remove(component)) return
        component.removeComponentListener(visibilityListener)
        if (component is Container) {
            component.removeContainerListener(treeListener)
            component.components.forEach(::unobserve)
        }
    }

    private fun refreshVisibility() {
        if (disposed) return
        isVisible = observed.none { isSearchComponent(it) && visibleWithinTerminal(it) }
    }

    private fun visibleWithinTerminal(component: Component): Boolean {
        var current: Component? = component
        while (current != null && current !== terminal) {
            if (!current.isVisible) return false
            current = current.parent
        }
        // Hiding the tab itself does not end its search session.
        return current === terminal
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        observed.forEach {
            it.removeComponentListener(visibilityListener)
            if (it is Container) it.removeContainerListener(treeListener)
        }
        observed.clear()
    }
}
