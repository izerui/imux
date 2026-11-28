package com.github.izerui.imux.terminal

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.JProgressBar

/** Keep the progress line at the banner's bottom without tinting the text background.
 * The native progress painter does not support a content-bearing banner with this layout.
 * Unknown progress stays neutral; the notice's separate spinner indicates activity.
 */
internal fun sessionProgressBar(): JProgressBar = object : JProgressBar(0, 100) {
    override fun getPreferredSize(): Dimension = layout?.preferredLayoutSize(this) ?: super.getPreferredSize()

    override fun paintComponent(g: Graphics) {
        g.color = UIUtil.getPanelBackground()
        g.fillRect(0, 0, width, height)
        if (isIndeterminate) return
        val thickness = JBUI.scale(2).coerceAtMost(height)
        g.color = JBUI.CurrentTheme.ProgressBar.PROGRESS
        g.fillRect(0, height - thickness, (width * percentComplete).toInt(), thickness)
    }
}.apply {
    layout = BorderLayout()
    border = JBUI.Borders.empty()
    isOpaque = false
}
