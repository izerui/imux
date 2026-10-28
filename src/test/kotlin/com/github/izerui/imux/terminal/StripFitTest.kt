package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 与对比页方案 A 相同的收缩顺序：结果完整 → 结果短文案 → 从右往左收其它功能的字 →
 * 结果只留图标 → 收起结果 → 最后收结对伙伴名。
 */
class StripFitTest {
    // 结对（伙伴名 60）、讨论（无字）、轨迹（无字）、手机（机器人名 70）；按钮图标宽 28，间距 4，底板 8
    private val pills =
        listOf(
            PillWidth(28, 60, pinned = true),
            PillWidth(28, null),
            PillWidth(28, null),
            PillWidth(28, 70),
        )
    private val chip = ChipWidths(full = 150, short = 80, icon = 40)

    /** 底板 8 + 4 个按钮之间 3 个间距 12 + 各按钮宽度 + chip。 */
    private fun width(pair: Boolean, phone: Boolean, chipWidth: Int) =
        8 + 12 + (if (pair) 60 else 28) + 28 + 28 + (if (phone) 70 else 28) + chipWidth

    private fun fit(available: Int) = stripFit(available, chrome = 8, gap = 4, pills = pills, chip = chip)

    @Test
    fun `宽度够时全部带字且结果完整`() {
        assertEquals(StripFit(setOf(0, 3), ChipFit.FULL), fit(width(true, true, 150)))
    }

    @Test
    fun `先把结果缩成短文案，伙伴名与机器人名都保留`() {
        assertEquals(StripFit(setOf(0, 3), ChipFit.SHORT), fit(width(true, true, 150) - 1))
        assertEquals(StripFit(setOf(0, 3), ChipFit.SHORT), fit(width(true, true, 80)))
    }

    @Test
    fun `再收其它功能的字，伙伴名仍保留`() {
        assertEquals(StripFit(setOf(0), ChipFit.SHORT), fit(width(true, true, 80) - 1))
        assertEquals(StripFit(setOf(0), ChipFit.SHORT), fit(width(true, false, 80)))
    }

    @Test
    fun `然后结果只留图标，再收起结果，伙伴名最后才收`() {
        assertEquals(StripFit(setOf(0), ChipFit.ICON), fit(width(true, false, 80) - 1))
        assertEquals(StripFit(setOf(0), ChipFit.HIDDEN), fit(width(true, false, 40) - 1))
        assertEquals(StripFit(emptySet(), ChipFit.HIDDEN), fit(width(true, false, 0) - 1))
    }

    @Test
    fun `连图标都放不下时停在最紧的一档，不再删按钮`() {
        assertEquals(StripFit(emptySet(), ChipFit.HIDDEN), fit(10))
    }

    @Test
    fun `没有结果时只按功能文字收缩`() {
        val noChip = { available: Int -> stripFit(available, 8, 4, pills, chip = null) }
        assertEquals(StripFit(setOf(0, 3), ChipFit.HIDDEN), noChip(width(true, true, 0)))
        assertEquals(StripFit(setOf(0), ChipFit.HIDDEN), noChip(width(true, true, 0) - 1))
        assertEquals(StripFit(emptySet(), ChipFit.HIDDEN), noChip(width(true, false, 0) - 1))
    }
}
