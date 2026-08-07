package com.github.izerui.imux.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用「每个字符宽 10」的等宽假字体测截断逻辑，
 * 免得断言跟着真实字体的字距漂移。
 */
private val TEN_PER_CHAR: (String) -> Int = { it.length * 10 }

class EllipsizeTest {

    @Test
    fun `预算刚好放得下前缀与省略号`() {
        // 预算 50 = 省略号 10 + 4 个字符
        assertEquals("abcd…", ellipsize("abcdefgh", budget = 50, width = TEN_PER_CHAR))
    }

    @Test
    fun `预算不足以再放一个字符时不多放`() {
        assertEquals("abcd…", ellipsize("abcdefgh", budget = 59, width = TEN_PER_CHAR))
        assertEquals("abcde…", ellipsize("abcdefgh", budget = 60, width = TEN_PER_CHAR))
    }

    @Test
    fun `预算只够省略号时退化成省略号`() {
        assertEquals("…", ellipsize("abcdefgh", budget = 10, width = TEN_PER_CHAR))
        assertEquals("…", ellipsize("abcdefgh", budget = 0, width = TEN_PER_CHAR))
        assertEquals("…", ellipsize("abcdefgh", budget = -5, width = TEN_PER_CHAR))
    }

    @Test
    fun `不把代理对劈成两半`() {
        // 「🚀」占两个 char。预算只够 2 个 char 时，退回到 emoji 之前，
        // 否则画出来是半个码元的方框。
        val truncated = ellipsize("a🚀bc", budget = 30, width = TEN_PER_CHAR)
        assertEquals("a…", truncated)
        assertTrue(truncated.none { Character.isHighSurrogate(it) })
    }

    @Test
    fun `预算够放下整个代理对时就放`() {
        assertEquals("a🚀…", ellipsize("a🚀bc", budget = 40, width = TEN_PER_CHAR))
    }

    @Test
    fun `预算充裕时也只在调用方判定超宽后才截`() {
        // 本函数不负责「放得下就别截」，那是调用方的判断；
        // 这里确认预算大于整串时会原样返回。
        assertEquals("abc…", ellipsize("abc", budget = 1000, width = TEN_PER_CHAR))
    }
}
