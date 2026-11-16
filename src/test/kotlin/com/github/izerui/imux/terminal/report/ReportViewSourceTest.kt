package com.github.izerui.imux.terminal.report

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JCEF 是可选依赖（2026.2 里是可被禁用的内置插件 `com.intellij.modules.jcef`）：引用它的类必须隔离在 JcefReportView 里，
 * 只在确认可用后才加载，否则 JCEF 被禁用时 imux 会在加载别的类时失败。
 */
class ReportViewSourceTest {
    @Test
    fun `JCEF 类只在 JcefReportView 里引用`() {
        val offenders = File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "JcefReportView.kt" }
            .filter { file ->
                val code = SourceCode(file.path).normalized
                code.contains("com.intellij.ui.jcef") || code.contains("org.cef")
            }
            .map { it.path }
            .toList()
        assertEquals(emptyList<String>(), offenders)
        assertTrue(SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/report/JcefReportView.kt").normalized.contains("com.intellij.ui.jcef"))
    }

    @Test
    fun `plugin xml 以可选依赖声明 JCEF`() {
        val xml = SourceCode("src/main/resources/META-INF/plugin.xml").source
        val depends = Regex("""<depends\b[^>]*>\s*com\.intellij\.modules\.jcef\s*</depends>""").findAll(xml).map { it.value }.toList()
        assertEquals(1, depends.size)
        assertTrue(depends.single().contains("""optional="true""""))
        assertTrue(depends.single().contains("""config-file="imux-jcef.xml""""))
        assertTrue(File("src/main/resources/META-INF/imux-jcef.xml").isFile)
    }

    @Test
    fun `导航到报告本身和页内锚点时不拦截，其他地址交给外部打开`() {
        val report = "file:///p/.imux/reports/s1.html"
        assertTrue(isReportUrl(report, report))
        assertTrue(isReportUrl(report, "$report#changes"))
        org.junit.Assert.assertFalse(isReportUrl(report, "https://example.com"))
        org.junit.Assert.assertFalse(isReportUrl(report, "file:///p/.imux/reports/s2.html"))
        org.junit.Assert.assertFalse("还没加载报告时一律交给外部", isReportUrl(null, report))
    }
}
