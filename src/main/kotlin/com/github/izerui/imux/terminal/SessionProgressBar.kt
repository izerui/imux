package com.github.izerui.imux.terminal

import com.intellij.util.ui.JBUI
import javax.swing.JProgressBar

/** Configure the native painter as well as the layout; clipping its default stripe loses detail. */
internal fun sessionProgressBar() = JProgressBar(0, 100).apply {
    val accent = JBUI.CurrentTheme.Link.Foreground.ENABLED
    putClientProperty("ProgressBar.stripeWidth", 2)
    putClientProperty("ProgressBar.progressPaint", accent)
    putClientProperty("ProgressBar.indeterminateStartColor", JBUI.CurrentTheme.ProgressBar.TRACK)
    putClientProperty("ProgressBar.indeterminateEndColor", accent)
    preferredSize = JBUI.size(1, 2)
}
