package com.github.izerui.imux.terminal

import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.Scrollable

/** Text wraps to the viewport instead of making the viewport scroll horizontally. */
internal class SessionInfoRows : JPanel(VerticalLayout(0)), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
    override fun getScrollableUnitIncrement(rect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(rect: Rectangle, orientation: Int, direction: Int) = rect.height
}
