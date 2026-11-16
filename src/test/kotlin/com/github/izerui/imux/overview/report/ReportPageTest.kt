package com.github.izerui.imux.overview.report

import com.github.izerui.imux.overview.NodeSymbol
import com.github.izerui.imux.overview.OverviewNode
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class ReportPageTest {
    private val template = String(javaClass.getResourceAsStream(REPORT_TEMPLATE)!!.readAllBytes(), Charsets.UTF_8)
    private val session = ReportSession("手机停止", "Claude Code", "imux", Instant.parse("2026-11-16T03:20:00Z"))
    private val report = SessionReport(
        goal = "目标",
        summary = "</script><img src=x onerror=alert(1)>&",
        timeline = listOf(OverviewNode(NodeSymbol.MAIN, "一")),
        facts = ReportFacts(135, listOf("src/A.kt")),
    )

    private fun count(text: String, part: String) = text.split(part).size - 1

    @Test
    fun `正文里的 script 结束标签和 HTML 被转义成普通文字`() {
        val page = renderReportPage(template, pageDataJson(report, "zh-CN", session))
        assertEquals("只有模板自己的 script 结束标签", count(template, "</script>"), count(page, "</script>"))
        assertFalse(page.contains("<img src=x"))
        assertTrue(page.contains("\\u003c/script\\u003e"))
        assertTrue(page.contains("\\u0026"))
    }

    @Test
    fun `占位缺失或重复时报错`() {
        val placeholder = """<script id="imux-data" type="application/json">{}</script>"""
        for (broken in listOf(template.replace(placeholder, ""), template.replace(placeholder, placeholder + placeholder))) {
            try {
                renderReportPage(broken, "{}")
                org.junit.Assert.fail("应报错")
            } catch (_: IllegalStateException) {
            }
        }
    }

    @Test
    fun `页面数据带上插件字段且时长为空时不写 stats`() {
        val data = JsonParser.parseString(pageDataJson(report, "zh-CN", session)).asJsonObject
        assertEquals(1, data.get("version").asInt)
        assertEquals("zh-CN", data.get("lang").asString)
        assertEquals("手机停止", data.getAsJsonObject("session").get("title").asString)
        assertEquals("src/A.kt", data.getAsJsonArray("files")[0].asString)
        assertEquals(135, data.getAsJsonObject("stats").get("durationMinutes").asInt)
        assertEquals("目标", data.get("goal").asString)

        val noDuration = JsonParser.parseString(pageDataJson(report.copy(facts = ReportFacts(null, emptyList())), "en", session)).asJsonObject
        assertFalse(noDuration.has("stats"))
    }

    @Test
    fun `写入报告文件后再写相同内容不改动文件`() {
        val root = Files.createTempDirectory("project")
        val first = writeReportFile(root, "s1", "<html>1</html>")
        assertTrue(first.changed)
        val before = Files.getLastModifiedTime(first.path)
        Thread.sleep(20)

        val second = writeReportFile(root, "s1", "<html>1</html>")

        assertFalse(second.changed)
        assertEquals(before, Files.getLastModifiedTime(second.path))
        assertTrue(writeReportFile(root, "s1", "<html>2</html>").changed)
        assertEquals("*\n", Files.readString(root.resolve(".imux/.gitignore")))
    }

    @Test
    fun `报告文件名按安全会话键生成`() {
        assertEquals(Path.of("/p/.imux/reports/a_b_c.html"), reportFile(Path.of("/p"), "a/b:c"))
    }
}
