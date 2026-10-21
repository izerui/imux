package com.github.izerui.imux.overview

import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.time.Instant
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * 按实际绘制出的像素检查轨迹线对齐。标签区域居中不等于字形对齐：符号是比例字体里的文字，
 * `┌`、`└` 还要用竖笔接上 `│`，只有画出来才看得到。
 */
class TrailGlyphRenderTest {
    private val background = Color(0x2B2D30)
    private val scale = 3

    private fun all(c: java.awt.Component): List<java.awt.Component> =
        listOf(c) + ((c as? java.awt.Container)?.components?.flatMap { all(it) } ?: emptyList())

    /** 一格符号实际画出的笔画：横向范围（放大后的像素）。 */
    private data class Ink(val glyph: String, val minX: Int, val maxX: Int) {
        val center get() = (minX + maxX) / 2.0
    }

    private fun renderInks(withTime: Boolean): List<Ink> {
        val now = Instant.now()
        val types = listOf(
            NodeSymbol.MAIN, NodeSymbol.ERROR, NodeSymbol.ROLLBACK_START, NodeSymbol.MAIN, NodeSymbol.ROLLBACK_END,
            NodeSymbol.SIDE, NodeSymbol.CHANGE, NodeSymbol.IN_PROGRESS,
        )
        val nodes = types.mapIndexed { i, t -> OverviewNode(t, "节点 $i", if (withTime && i % 2 == 0) now.minusSeconds(86_400L * i + 60) else null) }
        val panel = buildResultPanel(OverviewResult(nodes, now), stale = false) {}
        val host = javax.swing.JPanel(java.awt.BorderLayout()).apply { this.background = this@TrailGlyphRenderTest.background; isOpaque = true; add(panel) }
        host.setSize(360, 520)
        fun layout(c: java.awt.Component) { if (c is java.awt.Container) { c.doLayout(); c.components.forEach { layout(it) } } }
        fun invalidate(c: java.awt.Component) { if (c is java.awt.Container) { c.components.forEach { invalidate(it) }; (c.layout as? java.awt.LayoutManager2)?.invalidateLayout(c) } }
        repeat(4) { invalidate(host); layout(host) }

        val image = BufferedImage(host.width * scale, host.height * scale, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { scale(scale.toDouble(), scale.toDouble()); host.paint(this); dispose() }

        val timeline = all(panel).filterIsInstance<ScrollablePanel>().single()
        return timeline.components.map { row ->
            val label = row as? JLabel ?: all(row).filterIsInstance<JLabel>()
                .first { it.toolTipText == null && it.text?.isNotBlank() == true && it.text !in setOf("已回滚", "rolled back") }
            val r = SwingUtilities.convertRectangle(label.parent, label.bounds, host)
            var minX = Int.MAX_VALUE
            var maxX = -1
            for (y in r.y * scale until minOf(r.maxY.toInt() * scale, image.height)) {
                for (x in r.x * scale until minOf(r.maxX.toInt() * scale, image.width)) {
                    if (image.getRGB(x, y) != background.rgb) { minX = minOf(minX, x); maxX = maxOf(maxX, x) }
                }
            }
            check(maxX >= 0) { "${label.text} 没有画出任何笔画" }
            Ink(label.text, minX, maxX)
        }
    }

    @Test
    fun `实际绘制时各符号落在轨迹线上，回滚拐角的竖笔接上竖线`() = SwingUtilities.invokeAndWait {
        for (withTime in listOf(false, true)) {
            val inks = renderInks(withTime)
            val line = inks.filter { it.glyph == "│" }
            check(line.isNotEmpty())
            assertTrue("竖线彼此对齐：$line", line.map { it.minX }.distinct().size == 1)
            val lineLeft = line.first().minX
            val lineCenter = line.first().center
            for (ink in inks) {
                val where = "withTime=$withTime $ink 竖线=[${line.first().minX},${line.first().maxX}]"
                when (ink.glyph) {
                    "┌", "└" -> assertTrue("拐角竖笔与竖线重合：$where", kotlin.math.abs(ink.minX - lineLeft) <= scale)
                    "│" -> Unit
                    else -> assertTrue("符号笔画中心在线上（≤1px）：$where", kotlin.math.abs(ink.center - lineCenter) <= scale)
                }
            }
        }
    }
}
