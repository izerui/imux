package com.github.izerui.imux.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSegmenterTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size

    @Test
    fun `未超限时原样返回`() {
        assertEquals(listOf("你好\nworld"), TextSegmenter.split("你好\nworld", 64))
    }

    @Test
    fun `按行切分且每段不超过上限并可无损拼回`() {
        val text = (1..40).joinToString("\n") { "第 $it 行：一些内容 some content" }
        val parts = TextSegmenter.split(text, 200)
        assertTrue(parts.size > 1)
        parts.forEach { assertTrue("超限：${bytes(it)}", bytes(it) <= 200) }
        assertEquals(text, parts.joinToString("\n"))
    }

    @Test
    fun `超长单行按码点切分不产生乱码`() {
        val text = "中文字符".repeat(100)
        val parts = TextSegmenter.split(text, 64)
        parts.forEach { assertTrue(bytes(it) <= 64) }
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.none { it.contains('�') })
    }

    @Test
    fun `代码块跨段时补齐闭合并在下一段重开`() {
        val body = (1..30).joinToString("\n") { "val x$it = $it" }
        val text = "说明\n```kotlin\n$body\n```\n结尾"
        val parts = TextSegmenter.split(text, 120)
        assertTrue(parts.size > 1)
        parts.forEach { part ->
            assertTrue(bytes(part) <= 120)
            val fences = part.lines().count { it.trimStart().startsWith("```") }
            assertEquals("代码块未闭合：$part", 0, fences % 2)
        }
        assertTrue(parts[1].startsWith("```kotlin"))
    }
}
