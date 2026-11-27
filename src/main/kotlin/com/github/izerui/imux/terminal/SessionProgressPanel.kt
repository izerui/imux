package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.progress.ProgressSnapshot
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextArea

internal fun progressSummary(snapshot: ProgressSnapshot): String {
    val key = when {
        snapshot.endReason == com.github.izerui.imux.progress.ProgressEndReason.FAILED -> "progress.failed"
        snapshot.endReason == com.github.izerui.imux.progress.ProgressEndReason.INTERRUPTED -> "progress.interrupted"
        !snapshot.active -> "progress.ended"
        snapshot.waiting -> "progress.waiting"
        snapshot.progress == null -> "progress.unknown"
        else -> "progress.running"
    }
    return ImuxBundle.message(key)
}

/** 独立局部组件；不会因进度事件重建 MCP/技能分类。 */
internal class SessionProgressPanel : JPanel(BorderLayout(JBUI.scale(6), JBUI.scale(6))) {
    private var snapshot = ProgressSnapshot()
    private val summary = JBLabel().apply { putClientProperty("html.disable", true) }
    private val bar = JProgressBar(0, 100)
    private val steps = JTextArea().apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        font = com.intellij.util.ui.UIUtil.getLabelFont()
        border = JBUI.Borders.emptyTop(6)
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8, 0, 12, 0)
        add(summary, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(bar, BorderLayout.NORTH)
            add(steps)
        })
        isVisible = false
    }

    fun update(value: ProgressSnapshot) {
        if (snapshot == value) return
        snapshot = value
        isVisible = value.active || value.progress != null
        val progress = value.progress
        summary.text = listOfNotNull(progressSummary(value), progress?.let {
            "≈ ${it.percent}%"
        }).joinToString(" · ")
        bar.isVisible = value.active
        bar.isIndeterminate = value.active && progress == null
        bar.value = progress?.percent ?: 0
        bar.toolTipText = ImuxBundle.message("progress.estimate")
        steps.text = listOfNotNull(progress?.message, value.reportedAt?.let {
            ImuxBundle.message("session.info.updated") + " " + java.time.Instant.ofEpochMilli(it)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        }).joinToString("\n")
        bar.preferredSize = JBUI.size(1, 3)
        revalidate()
        repaint()
    }
}
