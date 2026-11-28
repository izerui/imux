package com.github.izerui.imux.terminal

import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.JProgressBar

private fun progressFill() =
    ColorUtil.mix(JBUI.CurrentTheme.Banner.SUCCESS_BACKGROUND, JBUI.CurrentTheme.ProgressBar.PASSED, 0.25)

/** The native painter has no track-color option for a full-height banner.
 * Paint only the determinate background; keep native animation and its component lifecycle.
 */
internal fun sessionProgressBar(): JProgressBar = object : JProgressBar(0, 100) {
    override fun getPreferredSize(): Dimension = layout?.preferredLayoutSize(this) ?: super.getPreferredSize()

    override fun paintComponent(g: Graphics) {
        if (isIndeterminate) {
            super.paintComponent(g)
            return
        }
        g.color = JBUI.CurrentTheme.Banner.SUCCESS_BACKGROUND
        g.fillRect(0, 0, width, height)
        g.color = progressFill()
        g.fillRect(0, 0, (width * percentComplete).toInt(), height)
    }
}.apply {
    layout = BorderLayout()
    border = JBUI.Borders.empty()
    isOpaque = false
    val accent = progressFill()
    putClientProperty("ProgressBar.flatEnds", true)
    putClientProperty("ProgressBar.indeterminateStartColor", JBUI.CurrentTheme.Banner.SUCCESS_BACKGROUND)
    putClientProperty("ProgressBar.indeterminateEndColor", accent)
}
