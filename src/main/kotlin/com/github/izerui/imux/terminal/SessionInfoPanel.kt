package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
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

private const val CONTENT_INSET = 20

private data class SearchFocusState(
    val page: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

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

internal fun updateInfoTabContent(
    tab: TabInfo,
    previous: InfoPage?,
    page: InfoPage,
    create: (InfoPage) -> JPanel,
): Boolean {
    if (previous == page) return false
    // JBTabs owns this root. Replacing it can leave the previous component attached.
    val root = tab.component as JPanel
    val replacement = create(page)
    val layout = replacement.layout as BorderLayout
    val children = replacement.components.map { it to layout.getConstraints(it) }
    root.removeAll()
    root.layout = BorderLayout(layout.hgap, layout.vgap)
    children.forEach { (child, constraint) -> root.add(child, constraint) }
    root.revalidate()
    root.repaint()
    return true
}

internal class SessionInfoPanel(
    project: Project, parent: Disposable,
    title: String, agent: String, icon: Icon, refresh: () -> Unit, close: () -> Unit,
) : JPanel(BorderLayout()) {
    private val progressPanel = SessionProgressPanel()
    private val titleValue = bold(title)

    fun updateProgress(snapshot: com.github.izerui.imux.progress.ProgressSnapshot) {
        progressPanel.update(snapshot)
    }

    fun updateTitle(value: String) {
        titleValue.text = value
        titleValue.toolTipText = value
    }

    private val tokenValue = SelectableInfoText()
    private val contextValue = SelectableInfoText()
    private val tabs = JBTabsFactory.createEditorTabs(project, parent).apply {
        presentation.setTabDraggingEnabled(false)
        presentation.setFirstTabOffset(0)
    }
    private val time = secondary("")
    private val filters = mutableMapOf<String, String>()
    private val searchFields = mutableMapOf<String, SearchTextField>()
    private val readStatus = secondary("")
    private var displayedSections: List<InfoPage>? = null
    private var displayRevision = 0L

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
            border = JBUI.Borders.empty(16, CONTENT_INSET)
            background = UIUtil.getPanelBackground()
            commands.isOpaque = false
            add(JBLabel(icon), BorderLayout.WEST)
            add(JPanel(VerticalLayout(1)).apply { isOpaque = false; add(titleValue); add(secondary(agent)) })
            add(commands, BorderLayout.EAST)
        })
        header.add(JPanel(java.awt.GridLayout(1, 2, JBUI.scale(14), 0)).apply {
            background = UIUtil.getListBackground()
            border = JBUI.Borders.compound(
                JBUI.Borders.empty(0, CONTENT_INSET),
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 0),
                JBUI.Borders.empty(9, 0),
            )
            fun metric(key: String, value: SelectableInfoText) = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
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
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, CONTENT_INSET)
            add(tabs.component)
        })
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.empty(0, CONTENT_INSET),
                JBUI.Borders.customLineTop(JBColor.border()),
                JBUI.Borders.empty(7, 0),
            )
            add(readStatus, BorderLayout.CENTER)
            add(time, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
    }

    fun display(tokens: String, context: String, sections: List<InfoPage>) {
        tokenValue.text = tokens
        tokenValue.toolTipText = tokens
        contextValue.toolTipText = context
        contextValue.text = context
        readStatus.text = ""
        time.text = msg("session.info.updated") + " " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        if (displayedSections == sections) return
        val previousPages = displayedSections.orEmpty().associateBy { it.name }
        displayedSections = sections
        val revision = ++displayRevision
        val selected = tabs.selectedInfo?.`object`
        val focusedSearch = focusedSearchState()
        val offsets = tabs.tabs.associate { tab ->
            tab.`object` to (tab.component as? JPanel)?.components?.filterIsInstance<JBScrollPane>()
                ?.firstOrNull()?.verticalScrollBar?.value
        }
        val existingTabs = tabs.tabs.associateBy { it.`object` }
        val pageNames = sections.map { it.name }.toSet()
        existingTabs.filterKeys { it !in pageNames }.forEach { (name, tab) ->
            tabs.removeTab(tab)
            searchFields.remove(name)
        }
        val pendingOffsets = mutableListOf<Pair<JPanel, Int>>()
        val changedPages = mutableSetOf<String>()
        sections.forEachIndexed { index, page ->
            val existing = existingTabs[page.name]
            if (existing != null && previousPages[page.name] == page) return@forEachIndexed
            changedPages += page.name
            searchFields.remove(page.name)
            val countHint = if (page.reported) "${page.name} · ${page.rows.size}" else page.name
            val tab = if (existing == null) {
                TabInfo(pageComponent(page)).setText(page.name).setObject(page.name).also {
                    tabs.addTab(it, index)
                }
            } else {
                updateInfoTabContent(existing, previousPages[page.name], page, ::pageComponent)
                existing
            }
            tab.setTooltipText(countHint)
            val content = tab.component as JPanel
            markTab(tab, tabs.selectedInfo === tab)
            if (page.name == selected) tabs.select(tab, false)
            offsets[page.name]?.let { offset ->
                pendingOffsets += content to offset
            }
        }
        javax.swing.SwingUtilities.invokeLater {
            if (revision != displayRevision) return@invokeLater
            pendingOffsets.forEach { (content, offset) ->
                val scroll = content.components.filterIsInstance<JBScrollPane>().firstOrNull() ?: return@forEach
                if (scroll.verticalScrollBar.value == 0) scroll.verticalScrollBar.value = offset
            }
            if (focusedSearch?.page in changedPages) restoreSearchState(focusedSearch)
        }
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
        val rows = JPanel(VerticalLayout(0)).apply { border = JBUI.Borders.empty(8, 0) }
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
                        val content = JPanel(BorderLayout(0, JBUI.scale(5))).apply {
                            isOpaque = false
                        }
                        val nameLine = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                            isOpaque = false
                            add(bold(row.title))
                            if (row.status.isNotEmpty()) add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                                isOpaque = false
                                add(JBLabel(if (row.failed) AllIcons.General.Warning else AllIcons.General.GearPlain), BorderLayout.WEST)
                                add(secondary(row.status))
                            }, BorderLayout.EAST)
                        }
                        val heading = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                            isOpaque = false
                            add(nameLine, BorderLayout.NORTH)
                            row.location?.takeIf { it.isNotBlank() }?.let { path ->
                                add(JBTextArea(path).apply {
                                    isEditable = false
                                    lineWrap = true
                                    wrapStyleWord = false
                                    isOpaque = false
                                    border = null
                                    font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D * 0.9f)
                                    foreground = UIUtil.getContextHelpForeground()
                                    toolTipText = path
                                }, BorderLayout.CENTER)
                            }
                        }
                        content.add(heading, BorderLayout.NORTH)
                        if (row.detail.isNotEmpty()) {
                            content.add(JBTextArea(row.detail).apply {
                                isEditable = false
                                isFocusable = true
                                lineWrap = true
                                wrapStyleWord = true
                                isOpaque = false
                                border = null
                                font = UIUtil.getLabelFont()
                                foreground = UIUtil.getContextHelpForeground()
                            }, BorderLayout.CENTER)
                        }
                        add(content)
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
                border = JBUI.Borders.empty(12, 0, 0, 0)
                textEditor.emptyText.text = msg("session.info.search")
                text = query
                addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) {
                        filters[page.name] = text
                        filter(text)
                    }
                })
                searchFields[page.name] = this
            }, BorderLayout.NORTH)
            add(JBScrollPane(rows).apply { border = JBUI.Borders.empty() })
        }
    }

    private fun focusedSearchState(): SearchFocusState? {
        val (page, field) = searchFields.entries.firstOrNull { it.value.textEditor.isFocusOwner } ?: return null
        return SearchFocusState(page, field.textEditor.selectionStart, field.textEditor.selectionEnd)
    }

    private fun restoreSearchState(state: SearchFocusState?) {
        val field = state?.let { searchFields[it.page] } ?: return
        val editor = field.textEditor
        val length = editor.document.length
        editor.select(state.selectionStart.coerceAtMost(length), state.selectionEnd.coerceAtMost(length))
        editor.requestFocusInWindow()
    }

    private fun plain(value: String) =
        SelectableInfoText(value)

    private fun bold(value: String) = plain(value).apply { font = font.deriveFont(Font.BOLD) }
    private fun secondary(value: String) = plain(value).apply { foreground = UIUtil.getContextHelpForeground() }
    private fun msg(key: String) = ImuxBundle.message(key)
}
