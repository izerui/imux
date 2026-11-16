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
    fun `只有用户点开的外部地址才交给系统浏览器`() {
        val report = "file:///p/.imux/reports/s1.html"
        assertTrue("用户点了外部链接", shouldOpenExternally(report, "https://example.com", userGesture = true))
        assertTrue("用户点了别的本地文件", shouldOpenExternally(report, "file:///p/.imux/reports/s2.html", userGesture = true))
        org.junit.Assert.assertFalse("报告本身", shouldOpenExternally(report, report, userGesture = true))
        org.junit.Assert.assertFalse("页内锚点", shouldOpenExternally(report, "$report#changes", userGesture = true))
        org.junit.Assert.assertFalse("不是用户点的（初始化、重定向）", shouldOpenExternally(report, "https://example.com", userGesture = false))
        org.junit.Assert.assertFalse("空白页", shouldOpenExternally(report, "about:blank", userGesture = true))
        org.junit.Assert.assertFalse("data 地址", shouldOpenExternally(report, "data:text/html,x", userGesture = true))
        org.junit.Assert.assertFalse("JCEF 内部页面", shouldOpenExternally(report, "file:///jbcefbrowser/error.html", userGesture = true))
        org.junit.Assert.assertFalse("还没加载报告时的初始导航", shouldOpenExternally(null, "about:blank", userGesture = false))
    }
}
