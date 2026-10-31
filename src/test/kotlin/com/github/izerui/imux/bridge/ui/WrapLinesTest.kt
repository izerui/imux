package com.github.izerui.imux.bridge.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WrapLinesTest {
    /** 每个字符宽 10。 */
    private val measure: (String) -> Int = { it.length * 10 }

    @Test
    fun `中文按字折行，原有换行保留`() {
        assertEquals(listOf("一二三四", "五六", "七八"), wrapLines("一二三四五六\n七八", 40, measure))
    }

    @Test
    fun `英文按词折行，行尾不留空格`() {
        assertEquals(listOf("ab cd", "efg"), wrapLines("ab cd efg", 50, measure))
    }

    @Test
    fun `超宽的单词独占一行，不够宽时原样一行`() {
        assertEquals(listOf("abcdefgh", "ij"), wrapLines("abcdefgh ij", 50, measure))
        assertEquals(listOf("短句"), wrapLines("短句", 100, measure))
    }

    @Test
    fun `中英混排`() {
        assertEquals(listOf("用 WeChat", "扫码"), wrapLines("用 WeChat 扫码", 80, measure))
    }
}
