package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.Rectangle

class SessionTrailLayoutTest {
    private val metrics = TrailMetrics(margin = 8, chip = 24, minWidth = 240, minHeight = 140)

    /** 导航轨道宽 22 + 右侧留白 14。 */
    private val rightReserve = 36

    @Test
    fun `折叠时是右上角的小图标，贴着导航轨道左侧`() {
        val bounds = trailBounds(1000, 600, rightReserve, expanded = false, 360, 420, metrics)

        assertEquals(Rectangle(1000 - 36 - 8 - 24, 8, 24, 24), bounds)
    }

    @Test
    fun `展开时按期望尺寸锚定右上角`() {
        val bounds = trailBounds(1000, 600, rightReserve, expanded = true, 360, 420, metrics)

        assertEquals(Rectangle(1000 - 36 - 8 - 360, 8, 360, 420), bounds)
    }

    @Test
    fun `期望尺寸超出正文时收缩到可用区域`() {
        val bounds = trailBounds(500, 300, rightReserve, expanded = true, 900, 900, metrics)

        assertEquals("左侧留 margin", 8, bounds.x)
        assertEquals("右边缘不越过导航轨道", 500 - 36 - 8, bounds.x + bounds.width)
        assertEquals("上下各留 margin", 300 - 16, bounds.height)
    }

    @Test
    fun `面板从提示条和浮动图标组下面开始且不越过底部`() {
        val bounds = trailBounds(1000, 600, rightReserve, expanded = true, 360, 900, metrics, topOffset = 100)

        assertEquals(100 + 8, bounds.y)
        assertEquals(600 - 8, bounds.y + bounds.height)
    }

    @Test
    fun `期望尺寸小于下限时取下限`() {
        val bounds = trailBounds(1000, 600, rightReserve, expanded = true, 10, 10, metrics)

        assertEquals(240, bounds.width)
        assertEquals(140, bounds.height)
    }

    @Test
    fun `正文比下限还小时以可用区域为准且不出现负值`() {
        val narrow = trailBounds(200, 100, rightReserve, expanded = true, 360, 420, metrics)
        assertEquals(200 - 36 - 16, narrow.width)
        assertEquals(100 - 16, narrow.height)

        val zero = trailBounds(0, 0, rightReserve, expanded = true, 360, 420, metrics)
        assertTrue(zero.x >= 0 && zero.y >= 0 && zero.width >= 0 && zero.height >= 0)
        val collapsed = trailBounds(0, 0, rightReserve, expanded = false, 360, 420, metrics)
        assertTrue(collapsed.x >= 0 && collapsed.width >= 0)
    }

    @Test
    fun `向左拖变宽，向下拖变高`() {
        val size = resizedTrailSize(360, 420, dx = -40, dy = 30, resizeWidth = true, resizeHeight = true, 900, 900, metrics)

        assertEquals(Dimension(400, 450), size)
    }

    @Test
    fun `只拖左边缘时高度不变`() {
        val size = resizedTrailSize(360, 420, dx = -40, dy = 30, resizeWidth = true, resizeHeight = false, 900, 900, metrics)

        assertEquals(Dimension(400, 420), size)
    }

    @Test
    fun `拖拽结果夹在上下限之间`() {
        val tooSmall = resizedTrailSize(360, 420, dx = 1000, dy = -1000, resizeWidth = true, resizeHeight = true, 900, 900, metrics)
        assertEquals(Dimension(240, 140), tooSmall)

        val tooLarge = resizedTrailSize(360, 420, dx = -1000, dy = 1000, resizeWidth = true, resizeHeight = true, 500, 600, metrics)
        assertEquals(Dimension(500, 600), tooLarge)
    }

    @Test
    fun `可用区域比下限还小时拖拽不超出可用区域`() {
        val size = resizedTrailSize(360, 420, dx = -10, dy = 10, resizeWidth = true, resizeHeight = true, 200, 100, metrics)

        assertEquals(Dimension(200, 100), size)
    }

    @Test
    fun `展开后收起按钮正好落在折叠图标的位置`() {
        val chipSize = com.intellij.util.ui.JBUI.scale(24)
        for (cardWidth in listOf(240, 360, 600)) {
            val button = javax.swing.JButton()
            val card = javax.swing.JPanel(java.awt.BorderLayout()).apply {
                border = trailCardBorder()
                add(trailCardHeader(javax.swing.JLabel("会话轨迹"), button), java.awt.BorderLayout.NORTH)
                setSize(cardWidth, 300)
            }
            fun layout(c: java.awt.Component) {
                if (c !is java.awt.Container) return
                c.doLayout()
                c.components.forEach { layout(it) }
            }
            layout(card)

            // 卡片与折叠图标都锚定同一个右上角（见 trailBounds），折叠图标占卡片右上 chip×chip
            val slot = javax.swing.SwingUtilities.convertRectangle(button.parent, button.bounds, card)
            assertEquals("@${cardWidth}px", Rectangle(cardWidth - chipSize, 0, chipSize, chipSize), slot)
        }
    }

    // ── 报告页：默认手机比例，放不下时同比缩小 ──

    @Test
    fun `放得下时按原尺寸不放大`() {
        assertEquals(java.awt.Dimension(520, 1127), fitKeepingRatio(520, 1127, 2000, 2000, 320))
    }

    @Test
    fun `放不下时宽高同比缩小`() {
        assertEquals(java.awt.Dimension(369, 800), fitKeepingRatio(520, 1127, 2000, 800, 320))
    }

    @Test
    fun `宽度缩到下限后只缩高度`() {
        assertEquals(java.awt.Dimension(320, 500), fitKeepingRatio(520, 1127, 2000, 500, 320))
    }

    @Test
    fun `可用宽度不足时也同比缩小`() {
        assertEquals(java.awt.Dimension(400, 866), fitKeepingRatio(520, 1127, 400, 2000, 320))
    }

    @Test
    fun `报告弹窗默认是 520 乘 1127 的手机比例`() {
        assertEquals(520, TrailSizeSetting.DEFAULT_WIDTH)
        assertEquals(1127, TrailSizeSetting.DEFAULT_HEIGHT)
    }

    @Test
    fun `有报告的就绪、生成中、失败状态显示报告页，其余不显示`() {
        val node = com.github.izerui.imux.overview.OverviewNode(com.github.izerui.imux.overview.NodeSymbol.MAIN, "一")
        val withReport = com.github.izerui.imux.overview.OverviewResult(
            listOf(node), java.time.Instant.now(), report = com.github.izerui.imux.overview.report.SessionReport(timeline = listOf(node)),
        )
        val empty = com.github.izerui.imux.overview.OverviewResult(emptyList(), java.time.Instant.now())
        assertTrue(showsReport(com.github.izerui.imux.overview.TrailState.Ready(withReport, false)))
        assertTrue(showsReport(com.github.izerui.imux.overview.TrailState.Loading(null, 0, withReport)))
        assertTrue(showsReport(com.github.izerui.imux.overview.TrailState.Failed("x", withReport)))
        assertFalse(showsReport(com.github.izerui.imux.overview.TrailState.Ready(empty, false)))
        assertFalse(showsReport(com.github.izerui.imux.overview.TrailState.Loading(null, 0, null)))
        assertFalse(showsReport(com.github.izerui.imux.overview.TrailState.Failed("x", null)))
        assertFalse(showsReport(com.github.izerui.imux.overview.TrailState.Empty))
        assertFalse(showsReport(null))
    }

    @Test
    fun `拖到恰好是默认尺寸也记作拖过，只有恢复默认才清掉`() {
        val layout = com.github.izerui.imux.SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/SessionTrailLayout.kt")
        val save = layout.bodyAfter("fun save(", '{')
        assertFalse("带默认值存时，等于默认的值会被平台删掉、算成没拖过：$save", save.contains("DEFAULT_"))
        assertTrue(layout.bodyAfter("fun reset(", '{').contains("unsetValue(WIDTH_KEY)"))
        assertTrue(layout.bodyAfter("fun reset(", '{').contains("unsetValue(HEIGHT_KEY)"))
    }
}
