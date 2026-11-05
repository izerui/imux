package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Rectangle

class PromptTicksTest {
    @Test
    fun `空列表没有横线`() {
        assertEquals(emptyList<Int>(), tickCenters(count = 0, height = 400, padding = 12, gap = 8))
    }

    @Test
    fun `单条横线垂直居中`() {
        assertEquals(listOf(200), tickCenters(count = 1, height = 400, padding = 12, gap = 8))
    }

    @Test
    fun `放得下时等间距且整列垂直居中`() {
        val centers = tickCenters(count = 3, height = 400, padding = 12, gap = 8)

        assertEquals(listOf(192, 200, 208), centers)
    }

    @Test
    fun `放不下时压缩间距并铺满可用高度`() {
        val centers = tickCenters(count = 21, height = 124, padding = 12, gap = 8)

        assertEquals(12, centers.first())
        assertEquals(112, centers.last())
        assertTrue("压缩后仍按顺序排列", centers.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `长度按提问字数分三档`() {
        assertEquals(8, tickLength(textLength = 4, short = 8, medium = 11, long = 14))
        assertEquals(11, tickLength(textLength = 30, short = 8, medium = 11, long = 14))
        assertEquals(14, tickLength(textLength = 60, short = 8, medium = 11, long = 14))
    }

    private val centers = listOf(100, 108, 116)
    private val lengths = listOf(8, 20, 14)

    private fun hit(x: Int, y: Int) =
        tickHitIndex(x, y, centers, lengths, left = 6, slack = 2, maxBand = 4)

    @Test
    fun `落在横线上命中该横线`() {
        assertEquals(0, hit(x = 10, y = 100))
        assertEquals(1, hit(x = 25, y = 109))
    }

    @Test
    fun `横线右侧空白不命中，鼠标事件交给终端`() {
        assertNull("第一根横线只有 8px，右侧空白不算", hit(x = 20, y = 100))
        assertEquals("同一 x 落在放大的第二根上则命中", 1, hit(x = 20, y = 108))
    }

    @Test
    fun `两根横线正中间之外的纵向空隙按最近横线判定且不越过半个间距`() {
        assertEquals(0, hit(x = 10, y = 103))
        assertEquals(1, hit(x = 10, y = 105))
        assertNull("最后一根横线下方超出半个间距不算", hit(x = 10, y = 121))
    }

    @Test
    fun `列外不命中`() {
        assertNull(hit(x = 1, y = 100))
        assertNull(hit(x = 10, y = 40))
    }

    @Test
    fun `压缩间距下每个位置只命中一根横线`() {
        val dense = tickCenters(count = 50, height = 124, padding = 12, gap = 8)
        val denseLengths = List(dense.size) { 14 }

        val hits = (dense.first()..dense.last()).mapNotNull {
            tickHitIndex(10, it, dense, denseLengths, left = 6, slack = 2, maxBand = 4)
        }

        assertEquals("每个纵向像素都命中某一根", dense.last() - dense.first() + 1, hits.size)
        assertTrue("命中下标随 y 单调不减，相邻横线不重叠", hits.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `卡片纵向对齐横线并收进可视区`() {
        assertEquals(
            Rectangle(40, 160, 300, 80),
            previewCardBounds(tickY = 200, cardWidth = 300, cardHeight = 80, x = 40, containerHeight = 600, margin = 8),
        )
        assertEquals(
            "贴近顶部时往下收",
            Rectangle(40, 8, 300, 80),
            previewCardBounds(tickY = 10, cardWidth = 300, cardHeight = 80, x = 40, containerHeight = 600, margin = 8),
        )
        assertEquals(
            "贴近底部时往上收",
            Rectangle(40, 512, 300, 80),
            previewCardBounds(tickY = 595, cardWidth = 300, cardHeight = 80, x = 40, containerHeight = 600, margin = 8),
        )
    }

    private val tenPerChar: (String) -> Int = { it.length * 10 }

    @Test
    fun `短文本一行放下`() {
        assertEquals(listOf("继续"), clampToLines("继续", maxWidth = 100, maxLines = 2, width = tenPerChar))
    }

    @Test
    fun `按字宽折行`() {
        assertEquals(
            listOf("一二三四五", "六七"),
            clampToLines("一二三四五六七", maxWidth = 50, maxLines = 2, width = tenPerChar),
        )
    }

    @Test
    fun `超出行数时末行以省略号收尾且不超宽`() {
        val lines = clampToLines("一二三四五六七八九十十一", maxWidth = 50, maxLines = 2, width = tenPerChar)

        assertEquals(listOf("一二三四五", "六七八九…"), lines)
        assertTrue(lines.all { tenPerChar(it) <= 50 })
    }

    @Test
    fun `空文本没有行`() {
        assertEquals(emptyList<String>(), clampToLines("", maxWidth = 50, maxLines = 2, width = tenPerChar))
    }
}
