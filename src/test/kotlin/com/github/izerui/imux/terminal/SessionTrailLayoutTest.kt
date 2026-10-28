package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
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
}
