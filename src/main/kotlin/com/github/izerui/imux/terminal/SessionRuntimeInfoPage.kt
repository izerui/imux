package com.github.izerui.imux.terminal

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.ScrollPaneConstants
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

internal class SessionRuntimeInfoPage(
    initial: List<InfoRow>,
) : JPanel(BorderLayout()) {
    private val rows = SessionInfoRows().apply { border = JBUI.Borders.empty(8, 0) }
    private var fields = emptyList<Pair<String, JTextPane>>()

    init {
        add(JBScrollPane(rows).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        })
        update(initial)
    }

    fun update(items: List<InfoRow>) {
        // Keep unchanged text components attached so native selection and focus survive updates.
        if (fields.map { it.first } != items.map { it.title }) {
            rows.removeAll()
            fields = items.map { row ->
                val value = JTextPane().apply {
                    isEditable = false
                    isOpaque = false
                    border = null
                    font = UIUtil.getLabelFont()
                    text = row.detail
                    val alignment = SimpleAttributeSet()
                    StyleConstants.setAlignment(alignment, StyleConstants.ALIGN_RIGHT)
                    setParagraphAttributes(alignment, false)
                }
                rows.add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                    border = JBUI.Borders.compound(
                        JBUI.Borders.customLineBottom(JBColor.border()),
                        JBUI.Borders.empty(9, 0),
                    )
                    add(SelectableInfoText(row.title).apply {
                        foreground = UIUtil.getContextHelpForeground()
                    }, BorderLayout.WEST)
                    add(value)
                })
                row.title to value
            }
        }
        fields.zip(items).forEach { (field, row) ->
            if (field.second.text != row.detail) field.second.text = row.detail
        }
        rows.revalidate()
        rows.repaint()
    }
}
