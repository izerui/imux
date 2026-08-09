package com.github.izerui.imux.frame

import org.junit.Assert.assertEquals
import org.junit.Test

class UnreadTitlePrefixTest {

    /**
     * 星号而不是圆点：会话列表与标签角标用的 `AllIcons.General.Modified` 是三条
     * 间隔 60° 的线构成的六辐星号（`general/modified.svg`，#6E6E6E / #AFB1B3）。
     * 窗口标题只能放纯文本，放不了那个 Icon，只能挑个形状最接近的字符。
     */
    @Test
    fun `未读前缀与平台 Modified 图标同形`() {
        assertEquals("✳ ", UnreadFrameTitleBuilder.UNREAD_PREFIX)
    }

    @Test
    fun `有未读时项目名前加标记`() {
        assertEquals("✳ imux", UnreadFrameTitleBuilder.decorate("imux", unread = true))
    }

    @Test
    fun `无未读时原样返回`() {
        assertEquals("imux", UnreadFrameTitleBuilder.decorate("imux", unread = false))
    }

    /**
     * 前缀必须加在项目名**之前**：macOS 合并窗口后标签宽度有限，
     * 标题末尾会被省略号截掉，只有开头位置能保证可见。
     */
    @Test
    fun `前缀位于开头而不是结尾`() {
        val decorated = UnreadFrameTitleBuilder.decorate("很长很长的项目名", unread = true)

        assertEquals(
            UnreadFrameTitleBuilder.UNREAD_PREFIX,
            decorated.take(UnreadFrameTitleBuilder.UNREAD_PREFIX.length),
        )
    }

    /** 重复装饰不该叠加星号——平台可能在同一状态下多次重算标题。 */
    @Test
    fun `已带前缀的标题不再重复添加`() {
        val once = UnreadFrameTitleBuilder.decorate("imux", unread = true)

        assertEquals(once, UnreadFrameTitleBuilder.decorate(once, unread = true))
    }
}
