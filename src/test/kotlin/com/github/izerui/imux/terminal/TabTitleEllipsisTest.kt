package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话标题由 CLI 生成，长短完全不受控：一句话摘要动辄二三十字，标签页会被撑得
 * 占满整条标签栏，把别的会话挤出可视区。显示时截断，内部标题保持原样——窗口标题、
 * 会话列表还要用完整的那份。
 */
class TabTitleEllipsisTest {

    @Test
    fun `短标题原样显示`() {
        assertEquals("分析工程结构", ellipsizeTabTitle("分析工程结构", max = 12))
    }

    @Test
    fun `刚好到上限也不截`() {
        assertEquals("123456789012", ellipsizeTabTitle("123456789012", max = 12))
    }

    @Test
    fun `超长标题截断并补省略号`() {
        assertEquals("12345678901…", ellipsizeTabTitle("1234567890123456", max = 12))
    }

    /** 省略号前面拖着个空格很难看，截完顺手收掉。 */
    @Test
    fun `截断处的空格不保留`() {
        assertEquals("分析工程结构…", ellipsizeTabTitle("分析工程结构 并给出改造建议", max = 8))
    }

    /** emoji 是代理对，按 char 截会切出半个字符，显示成乱码方块。 */
    @Test
    fun `不切开代理对`() {
        assertEquals("🚀🚀🚀…", ellipsizeTabTitle("🚀🚀🚀🚀🚀", max = 4))
    }
}
