package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.DocumentAdapter
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

internal data class InfoRow(
    val title: String,
    val detail: String = "",
    val status: String = "",
    val failed: Boolean = false,
    val location: String? = null
)

internal data class InfoPage(
    val name: String,
    val rows: List<InfoRow>,
    val searchable: Boolean = true,
    val reported: Boolean = true,
    val emptyKey: String = "session.info.noData"
)

internal class SessionInfoPanel(
    project: Project, parent: Disposable,
    title: String, agent: String, icon: Icon, refresh: () -> Unit, close: () -> Unit,
) : JPanel(BorderLayout()) {
    private val progressPanel = SessionProgressPanel()
    fun updateProgress(snapshot: com.github.izerui.imux.progress.ProgressSnapshot) {
        progressPanel.update(snapshot)
    }

    private val tokenValue = JBLabel()
    private val contextValue = JBLabel()
    private val tabs = JBTabsFactory.createEditorTabs(project, parent).apply {
        presentation.setTabDraggingEnabled(false)
        presentation.setFirstTabOffset(JBUI.scale(14))
    }
    private val time = secondary("")
    private val filters = mutableMapOf<String, String>()
    private val expanded = mutableSetOf<Pair<String, String>>()
    private val readStatus = secondary("")

    fun reading() {
        readStatus.text = msg("session.info.reading")
    }

    fun readFailed() {
        readStatus.text = msg("session.info.readFailed")
    }

    fun missingRuntimeHeight(): Int {
        val viewport =
            (tabs.getTabAt(0).component as JPanel).components.filterIsInstance<JBScrollPane>().single().viewport
        if (viewport.extentSize.height <= 0) return 0
        return (viewport.view.preferredSize.height - viewport.extentSize.height).coerceAtLeast(0)
    }

    init {
        tabs.addListener(object : TabsListener {
            override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                markTab(oldSelection, false)
                markTab(newSelection, true)
            }
        }, parent)
        preferredSize = JBUI.size(430, 475)
        val header = JPanel(VerticalLayout(0))
        val commands = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)).apply {
            add(InplaceButton(IconButton(msg("action.refresh.text"), AllIcons.Actions.Refresh), { refresh() }))
            add(InplaceButton(IconButton(msg("action.peer.close"), AllIcons.Actions.Close), { close() }))
        }
        header.add(JPanel(BorderLayout(JBUI.scale(9), 0)).apply {
            border = JBUI.Borders.empty(16, 20)
            background = UIUtil.getPanelBackground()
            commands.isOpaque = false
            add(JBLabel(icon), BorderLayout.WEST)
            add(JPanel(VerticalLayout(1)).apply { isOpaque = false; add(bold(title)); add(secondary(agent)) })
            add(commands, BorderLayout.EAST)
        })
        header.add(JPanel(java.awt.GridLayout(1, 2, JBUI.scale(14), 0)).apply {
            background = UIUtil.getListBackground()
            border =
                JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 0), JBUI.Borders.empty(9, 20))
            fun metric(key: String, value: JBLabel) = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(secondary(msg(key)), BorderLayout.WEST)
                value.horizontalAlignment = javax.swing.SwingConstants.RIGHT
                value.font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
                add(value)
            }
            add(metric("session.info.total", tokenValue))
            add(metric("session.info.context", contextValue))
        })
        add(header, BorderLayout.NORTH)
        add(tabs.component)
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(7, 16))
            add(readStatus, BorderLayout.CENTER)
            add(time, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
    }

    fun display(tokens: String, context: String, sections: List<InfoPage>) {
        val selected = tabs.selectedInfo?.`object`
        val offsets = tabs.tabs.associate { tab ->
            tab.`object` to (tab.component as? JPanel)?.components?.filterIsInstance<JBScrollPane>()
                ?.firstOrNull()?.verticalScrollBar?.value
        }
        tokenValue.text = tokens
        tokenValue.toolTipText = tokens
        contextValue.toolTipText = context
        contextValue.text = context
        tabs.removeAllTabs()
        sections.forEach { page ->
            val countHint = if (page.reported) "${page.name} · ${page.rows.size}" else page.name
            val content = pageComponent(page)
            val tab = TabInfo(content).setText(page.name).setTooltipText(countHint).setObject(page.name)
            tabs.addTab(tab)
            markTab(tab, tabs.selectedInfo === tab)
            if (page.name == selected) tabs.select(tab, false)
            offsets[page.name]?.let { offset ->
                javax.swing.SwingUtilities.invokeLater {
                    content.components.filterIsInstance<JBScrollPane>().firstOrNull()?.verticalScrollBar?.value = offset
                }
            }
        }
        readStatus.text = ""
        time.text = msg("session.info.updated") + " " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }

    private fun markTab(tab: TabInfo?, selected: Boolean) {
        val label = tab?.let(tabs::getTabLabel) as? JComponent ?: return
        label.border = JBUI.Borders.compound(
            if (selected) JBUI.Borders.customLine(JBUI.CurrentTheme.Link.Foreground.ENABLED, 0, 0, 2, 0)
            else JBUI.Borders.emptyBottom(2),
            JBUI.Borders.empty(7, 8),
        )
    }

    private fun pageComponent(page: InfoPage): JPanel {
        val rows = JPanel(VerticalLayout(0)).apply { border = JBUI.Borders.empty(8, 20) }
        fun filter(query: String) {
            rows.removeAll()
            if (page.name == msg("session.info.runtime")) rows.add(progressPanel)
            page.rows.filter {
                listOf(
                    it.title,
                    it.detail,
                    it.status,
                    it.location.orEmpty()
                ).any { value -> value.contains(query.trim(), true) }
            }
                .forEach { row ->
                    if (page.name == msg("session.info.runtime")) {
                        rows.add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                            border = JBUI.Borders.compound(
                                JBUI.Borders.customLineBottom(JBColor.border()),
                                JBUI.Borders.empty(9, 0)
                            )
                            add(secondary(row.title), BorderLayout.WEST)
                            add(javax.swing.JTextPane().apply {
                                isEditable = false
                                isOpaque = false
                                border = null
                                font = UIUtil.getLabelFont()
                                text = row.detail
                                val alignment = javax.swing.text.SimpleAttributeSet()
                                javax.swing.text.StyleConstants.setAlignment(
                                    alignment,
                                    javax.swing.text.StyleConstants.ALIGN_RIGHT
                                )
                                setParagraphAttributes(alignment, false)
                            })
                        })
                        return@forEach
                    }
                    rows.add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                        border = JBUI.Borders.compound(
                            JBUI.Borders.customLineBottom(JBColor.border()),
                            JBUI.Borders.empty(10, 0)
                        )
                        val nameLine = JPanel(BorderLayout()).apply { isOpaque = false; add(bold(row.title)) }
                        add(JPanel(BorderLayout(0, JBUI.scale(3))).apply {
                            isOpaque = false
                            add(nameLine, BorderLayout.NORTH)
                            if (row.detail.isNotEmpty()) add(javax.swing.JTextArea(row.detail).apply {
                                isEditable = false
                                isFocusable = false
                                lineWrap = true
                                wrapStyleWord = true
                                isOpaque = false
                                border = null
                                font = UIUtil.getLabelFont()
                                foreground = UIUtil.getContextHelpForeground()
                            })
                            if (row.status.isNotEmpty()) nameLine.add(secondary(row.status).apply {
                                icon = if (row.failed) AllIcons.General.Warning else AllIcons.General.GearPlain
                            }, BorderLayout.EAST)
                        })
                        row.location?.let { path ->
                            val entry = page.name to path
                            val detail = javax.swing.JTextArea(path).apply {
                                isEditable = false
                                lineWrap = true
                                wrapStyleWord = false
                                background = UIUtil.getPanelBackground()
                                border = JBUI.Borders.empty(12, 20, 0, 20)
                                font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
                                isVisible = entry in expanded
                            }
                            add(detail, BorderLayout.SOUTH)
                            val expand = javax.swing.JToggleButton(AllIcons.General.ArrowRight).apply {
                                toolTipText = path
                                accessibleContext.accessibleName = row.title
                                border = JBUI.Borders.emptyRight(3)
                                margin = JBUI.emptyInsets()
                                isContentAreaFilled = false
                                isBorderPainted = false
                                selectedIcon = AllIcons.General.ArrowDown
                                isSelected = detail.isVisible
                                addActionListener {
                                    if (isSelected) expanded.add(entry) else expanded.remove(entry)
                                    detail.isVisible = isSelected
                                    rows.revalidate()
                                    rows.repaint()
                                }
                            }
                            nameLine.add(expand, BorderLayout.WEST)
                            val toggle = object : java.awt.event.MouseAdapter() {
                                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                                    if (javax.swing.SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1) expand.doClick()
                                }
                            }

                            // Summary and arrow are one disclosure target, as in the prototype.
                            fun bind(component: java.awt.Component) {
                                if (component === expand || component === detail) return
                                component.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                                component.addMouseListener(toggle)
                                if (component is java.awt.Container) component.components.forEach(::bind)
                            }
                            bind(this)
                            val line = this
                            val normal = background
                            object : com.intellij.ui.hover.HoverListener() {
                                override fun mouseEntered(component: java.awt.Component, x: Int, y: Int) {
                                    line.background = JBUI.CurrentTheme.ActionButton.hoverBackground()
                                }

                                override fun mouseMoved(component: java.awt.Component, x: Int, y: Int) = Unit
                                override fun mouseExited(component: java.awt.Component) {
                                    line.background = normal
                                }
                            }.addTo(line)
                        }
                    })
                }
            if (rows.componentCount == 0) rows.add(secondary(msg(if (query.isEmpty()) page.emptyKey else "session.info.noMatches")).apply {
                border = JBUI.Borders.empty(16)
            })
            rows.revalidate()
            rows.repaint()
        }

        val query = filters[page.name].orEmpty()
        filter(query)
        return JPanel(BorderLayout()).apply {
            if (page.searchable) add(SearchTextField(false).apply {
                border = JBUI.Borders.empty(12, 20, 0, 20)
                textEditor.emptyText.text = msg("session.info.search")
                text = query
                addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) {
                        filters[page.name] = text
                        filter(text)
                    }
                })
            }, BorderLayout.NORTH)
            add(JBScrollPane(rows).apply { border = JBUI.Borders.empty() })
        }
    }

    private fun plain(value: String) =
        JBLabel().apply { putClientProperty("html.disable", true); text = value; toolTipText = value }

    private fun bold(value: String) = plain(value).apply { font = font.deriveFont(Font.BOLD) }
    private fun secondary(value: String) = plain(value).apply { foreground = UIUtil.getContextHelpForeground() }
    private fun msg(key: String) = ImuxBundle.message(key)
}
