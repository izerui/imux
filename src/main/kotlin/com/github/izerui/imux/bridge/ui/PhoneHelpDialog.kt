package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.settings.PluginLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component.BaselineResizeBehavior
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * 手机上能发什么：停止词、确认怎么回、能直接对会话说的话。
 * 这些都只在手机聊天里生效，IDE 里原本看不到，用户不翻文档就不知道。
 */
internal class PhoneHelpDialog(project: Project) : DialogWrapper(project) {
    init {
        title = ImuxBundle.message("bridge.help.title")
        init()
    }

    // 内容比一屏长，定高加滚动；宽度跟着窗口走，说明随之重新折行
    override fun createCenterPanel(): JComponent =
        JBScrollPane(ViewportWidth(phoneHelpPanel())).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = JBUI.size(600, 480)
        }

    override fun createActions(): Array<Action> = arrayOf(okAction)
}

/**
 * 说明内容。要与 TerminalKeys、HookReplies 的实际行为一致（PhoneHelpTest 逐个语言核对）；
 * 1、2 各语言都认，写死在这里，其余要发的词随语言放进资源包，多个用 | 隔开。
 */
internal fun phoneHelpPanel(language: PluginLanguage = ImuxBundle.currentLanguage()): DialogPanel {
    fun msg(key: String): String = ImuxBundle.message(language, key)

    fun words(key: String): List<String> = msg(key).split('|').map(String::trim).filter(String::isNotEmpty)

    // 每个分组是独立的网格，左列各算各的会参差不齐；统一取最宽一行，说明才对得齐
    val keyColumns = mutableListOf<Chips>()
    val keyColumnWidth by lazy { keyColumns.maxOf { it.naturalWidth } }

    fun Panel.subtitle(key: String, first: Boolean = false) {
        row { label(msg(key)).bold() }.topGap(if (first) TopGap.NONE else TopGap.SMALL)
    }

    fun Panel.keyRow(keys: List<String>, descKey: String) {
        val chips = Chips(keys) { keyColumnWidth }.also(keyColumns::add)
        // 不设 AlignY：同一行按文字基线对齐，标签里的词和说明首行才平齐（标签有底色内边距，顶对齐会错开）
        row {
            cell(chips)
            text(msg(descKey), MAX_LINE_LENGTH_WORD_WRAP).resizableColumn().align(AlignX.FILL)
        }
    }

    return panel {
        row { comment(msg("bridge.help.intro"), MAX_LINE_LENGTH_WORD_WRAP) }

        group(msg("bridge.help.section.task")) {
            keyRow(words("bridge.help.task.text.keys"), "bridge.help.task.text")
            keyRow(words("bridge.help.task.file.keys"), "bridge.help.task.file")
        }

        group(msg("bridge.help.section.stop")) {
            keyRow(words("bridge.help.stop.keys"), "bridge.help.stop")
        }

        group(msg("bridge.help.section.confirm")) {
            subtitle("bridge.help.confirm.permission", first = true)
            keyRow(listOf("1") + words("bridge.help.permission.allow.keys"), "bridge.help.permission.allow")
            keyRow(listOf("2") + words("bridge.help.permission.deny.keys"), "bridge.help.permission.deny")
            keyRow(words("bridge.help.other"), "bridge.help.permission.other")

            subtitle("bridge.help.confirm.question")
            keyRow(words("bridge.help.question.number.keys"), "bridge.help.question.number")
            keyRow(words("bridge.help.other"), "bridge.help.question.other")

            subtitle("bridge.help.confirm.plan")
            keyRow(listOf("1"), "bridge.help.plan.first")
            keyRow(listOf("2"), "bridge.help.plan.second")
            keyRow(words("bridge.help.plan.deny.keys"), "bridge.help.plan.deny")
            keyRow(words("bridge.help.other"), "bridge.help.plan.other")

            row { comment(msg("bridge.help.confirm.note"), MAX_LINE_LENGTH_WORD_WRAP) }.topGap(TopGap.SMALL)
        }

        group(msg("bridge.help.section.ask")) {
            // 整句话做标签，左列放不下，改成上面一句、下面说明
            ASK_ROWS.forEachIndexed { i, id ->
                row { cell(Chips(words("bridge.help.ask.$id.keys"))) }.topGap(if (i == 0) TopGap.NONE else TopGap.SMALL)
                row { comment(msg("bridge.help.ask.$id"), MAX_LINE_LENGTH_WORD_WRAP) }
            }
            row { comment(msg("bridge.help.ask.note"), MAX_LINE_LENGTH_WORD_WRAP) }.topGap(TopGap.SMALL)
        }
    }
}

private val ASK_ROWS = listOf("file", "pair", "review", "status")
private val CHIP_BACKGROUND = JBColor.namedColor("Tag.background", JBColor(0xE4E6EB, 0x43454A))

/** 宽度始终等于可视区域，只在竖直方向滚动。 */
private class ViewportWidth(content: JComponent) : JPanel(BorderLayout()), Scrollable {
    init {
        isOpaque = false
        // 右侧留白，文字不贴着滚动条
        border = JBUI.Borders.emptyRight(12)
        add(content)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width

    override fun getScrollableTracksViewportWidth(): Boolean = true

    override fun getScrollableTracksViewportHeight(): Boolean = false
}

/** 一组词并排成标签；给了 [columnWidth] 就按它占宽，作为对齐的左列。 */
private class Chips(words: List<String>, private val columnWidth: (() -> Int)? = null) :
    JPanel(HorizontalLayout(JBUI.scale(4))) {
    init {
        isOpaque = false
        words.forEach { add(Chip(it)) }
    }

    val naturalWidth: Int get() = super.getPreferredSize().width

    override fun getPreferredSize(): Dimension =
        super.getPreferredSize().let { natural -> columnWidth?.let { Dimension(it(), natural.height) } ?: natural }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getBaseline(width: Int, height: Int): Int {
        val first = getComponent(0)
        doLayout()
        return first.y + first.getBaseline(first.width, first.height)
    }

    override fun getBaselineResizeBehavior(): BaselineResizeBehavior = BaselineResizeBehavior.CONSTANT_ASCENT
}

/** 圆角底色的小标签：一眼看出哪些是要原样发的词。 */
private class Chip(text: String) : JBLabel(text) {
    init {
        border = JBUI.Borders.empty(1, 7)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = CHIP_BACKGROUND
            val arc = JBUI.scale(8)
            g2.fillRoundRect(0, 0, width, height, arc, arc)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
