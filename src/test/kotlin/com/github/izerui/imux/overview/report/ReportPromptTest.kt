package com.github.izerui.imux.overview.report

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.overview.CachedOverview
import com.github.izerui.imux.overview.NodeSymbol
import com.github.izerui.imux.overview.OverviewNode
import com.github.izerui.imux.overview.OverviewResult
import com.github.izerui.imux.overview.TrailCursor
import com.github.izerui.imux.settings.PluginLanguage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

class ReportPromptTest {
    private val file = Path.of("/tmp/session.jsonl")
    private val previous = SessionReport(
        goal = "给轨迹面板加报告",
        timeline = listOf(OverviewNode(NodeSymbol.MAIN, "确认需求")),
    )

    private fun cached(report: SessionReport?) =
        CachedOverview(OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "确认需求")), Instant.now(), report = report), 1_000, 100)

    @Test
    fun `完整生成读第一行到截止行并写明输出语言`() {
        val prompt = reportPrompt(true, PluginLanguage.JAPANESE, AgentType.CLAUDE, file, null, TrailCursor(120, 5_000))
        assertTrue(prompt.contains("第 1 行到第 120 行"))
        assertTrue(prompt.contains("日本語"))
        assertTrue("应附示例 JSON", prompt.contains("\"timeline\""))
        assertTrue(prompt.contains(file.toAbsolutePath().normalize().toString()))
    }

    @Test
    fun `增量生成附上上次报告 JSON 并从上次截止的下一行读起`() {
        val prompt = reportPrompt(true, PluginLanguage.SIMPLIFIED_CHINESE, AgentType.CLAUDE, file, cached(previous), TrailCursor(150, 2_000))
        assertTrue(prompt.contains("第 101 行"))
        assertTrue(prompt.contains("第 150 行"))
        assertTrue("应带上上次报告的内容", prompt.contains("给轨迹面板加报告"))
    }

    @Test
    fun `旧缓存没有报告时退回完整生成`() {
        val prompt = reportPrompt(true, PluginLanguage.SIMPLIFIED_CHINESE, AgentType.CLAUDE, file, cached(null), TrailCursor(150, 2_000))
        assertTrue(prompt.contains("第 1 行到第 150 行"))
        assertFalse(prompt.contains("第 101 行"))
    }

    @Test
    fun `英文提示词同样写明输出语言`() {
        val prompt = reportPrompt(false, PluginLanguage.GERMAN, AgentType.CODEX, file, null, TrailCursor(120, 5_000))
        assertTrue(prompt.contains("Deutsch"))
        assertTrue(prompt.contains("lines 1 to 120"))
    }

    @Test
    fun `完整与增量提示词都写明各节条数上限与只输出 JSON`() {
        val prompts = listOf(
            reportPrompt(true, PluginLanguage.SIMPLIFIED_CHINESE, AgentType.PI, file, null, TrailCursor(120, 5_000)),
            reportPrompt(true, PluginLanguage.SIMPLIFIED_CHINESE, AgentType.PI, file, cached(previous), TrailCursor(150, 2_000)),
            reportPrompt(false, PluginLanguage.ENGLISH, AgentType.PI, file, null, TrailCursor(120, 5_000)),
            reportPrompt(false, PluginLanguage.ENGLISH, AgentType.PI, file, cached(previous), TrailCursor(150, 2_000)),
        )
        for (prompt in prompts) {
            for (limit in listOf("$MAX_TIMELINE", "$MAX_DECISIONS", "$MAX_CHANGES", "$MAX_SNIPPET_LINES", "$MAX_CHECKS")) {
                assertTrue("上限 $limit 应写进提示词", prompt.contains(limit))
            }
            assertTrue(prompt.contains("JSON"))
        }
    }
}
