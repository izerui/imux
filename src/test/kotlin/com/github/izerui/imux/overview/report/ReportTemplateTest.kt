package com.github.izerui.imux.overview.report

import com.github.izerui.imux.SourceCode
import com.github.izerui.imux.settings.PluginLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内置报告模板的结构：语言表覆盖插件的每种语言、各语言键齐全、数据占位唯一。 */
class ReportTemplateTest {
    private val template = SourceCode("src/main/resources/report/session-report.html").source

    /** 模板里 `"<id>": { ... }` 一个语言块的顶层键（不含嵌套对象里的键）。 */
    private fun keysOf(id: String): Set<String> {
        val start = template.indexOf("\"$id\": {")
        assertTrue("模板缺少语言 $id", start >= 0)
        var depth = 0
        var end = start
        for (i in template.indexOf('{', start) until template.length) {
            when (template[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) { end = i; break }
            }
        }
        val body = template.substring(template.indexOf('{', start) + 1, end)
        // 先去掉字符串（文案里可能有「data:」这样的字样），再去掉嵌套对象，只留顶层「键:」
        val flat = body.replace(Regex(""""(?:[^"\\]|\\.)*""""), "\"\"").replace(Regex("""\{[^{}]*\}"""), "{}")
        return Regex("""(?:^|[,{\s])(\w+):""").findAll(flat).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `模板语言表覆盖插件的每种语言`() {
        for (language in PluginLanguage.entries) {
            assertTrue("模板缺少语言 ${language.id}", template.contains("\"${language.id}\": {"))
        }
    }

    @Test
    fun `每种语言的界面文字键齐全`() {
        val expected = keysOf("en")
        assertTrue("英文键集合不应为空", expected.size > 10)
        for (language in PluginLanguage.entries) {
            assertEquals("${language.id} 的键与英文不一致", expected, keysOf(language.id))
        }
    }

    @Test
    fun `数据占位恰好一处且为空对象`() {
        val placeholder = """<script id="imux-data" type="application/json">{}</script>"""
        assertEquals(1, template.split(placeholder).size - 1)
        assertEquals(1, template.split("id=\"imux-data\"").size - 1)
    }

    @Test
    fun `改动标签取文件名时斜杠和反斜杠都认，Windows 路径不会整条显示`() {
        org.junit.Assert.assertFalse(template.contains(""".split("/")"""))
        assertTrue(template.contains(""".split(/[\\/]/)"""))
    }
}
