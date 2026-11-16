package com.github.izerui.imux.overview

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.settings.PluginLanguage
import com.github.izerui.imux.overview.report.reportPrompt
import com.github.izerui.imux.overview.report.SessionReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

class NodeTimeTest {
    private val t1 = Instant.parse("2026-10-21T02:12:30Z")
    private val t2 = Instant.parse("2026-10-21T02:27:00Z")

    // ── 解析 ──

    @Test
    fun `解析带时间的节点`() {
        val nodes = timelineOf(
            """
            [MAIN|2026-10-21T02:12:30Z] 确认需求
            [CHANGE|2026-10-21T02:27:00.123+00:00] 改为右上角面板
            """.trimIndent(),
        )

        assertEquals(listOf("确认需求", "改为右上角面板"), nodes.map { it.text })
        assertEquals(NodeSymbol.CHANGE, nodes[1].symbol)
        assertEquals(t1, nodes[0].time)
        assertEquals(Instant.parse("2026-10-21T02:27:00.123Z"), nodes[1].time)
    }

    @Test
    fun `没有时间或时间写坏了也照常解析节点`() {
        val nodes = timelineOf("[MAIN] 旧格式\n[SIDE|昨天下午] 时间写坏了\n[ERROR|] 空时间")

        assertEquals(listOf("旧格式", "时间写坏了", "空时间"), nodes.map { it.text })
        assertTrue(nodes.all { it.time == null })
        assertEquals(NodeSymbol.SIDE, nodes[1].symbol)
    }

    // ── 校验 ──

    @Test
    fun `晚于本次生成开始的时间不显示`() {
        val startedAt = Instant.parse("2026-10-21T03:00:00Z")
        val nodes = sanitizeNodeTimes(
            listOf(
                OverviewNode(NodeSymbol.MAIN, "a", t1),
                OverviewNode(NodeSymbol.MAIN, "未来", Instant.parse("2026-10-21T05:00:00Z")),
            ),
            startedAt,
        )
        assertEquals(t1, nodes[0].time)
        assertNull(nodes[1].time)
    }

    @Test
    fun `时间顺序倒退的节点不显示时间`() {
        val nodes = sanitizeNodeTimes(
            listOf(
                OverviewNode(NodeSymbol.MAIN, "a", t1),
                OverviewNode(NodeSymbol.MAIN, "b", t2),
                OverviewNode(NodeSymbol.MAIN, "倒退", t1.minusSeconds(60)),
                OverviewNode(NodeSymbol.MAIN, "c", t2.plusSeconds(60)),
            ),
            t2.plusSeconds(3600),
        )
        assertEquals(listOf(t1, t2, null, t2.plusSeconds(60)), nodes.map { it.time })
    }

    // ── 显示 ──

    private fun glyphCenterX(label: javax.swing.JLabel, rect: (java.awt.Component) -> java.awt.Rectangle): Int {
        val r = rect(label)
        val insets = label.insets
        return r.x + insets.left + (r.width - insets.left - insets.right) / 2
    }

    private fun layoutOffscreen(root: javax.swing.JComponent, width: Int, height: Int): javax.swing.JPanel {
        val host = javax.swing.JPanel(java.awt.BorderLayout())
        host.add(root, java.awt.BorderLayout.CENTER)
        host.setSize(width, height)
        fun invalidateAll(c: java.awt.Component) {
            if (c !is java.awt.Container) return
            c.components.forEach { invalidateAll(it) }
            (c.layout as? java.awt.LayoutManager2)?.invalidateLayout(c)
        }
        fun layout(c: java.awt.Component) {
            if (c !is java.awt.Container) return
            c.doLayout()
            c.components.forEach { layout(it) }
        }
        repeat(4) { invalidateAll(host); layout(host) }
        return host
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun allComponents(c: java.awt.Component): List<java.awt.Component> =
        listOf(c) + ((c as? java.awt.Container)?.components?.flatMap { allComponents(it) } ?: emptyList())

    // ── 缓存与提示词 ──

    @Test
    fun `节点时间随缓存写入磁盘并能读回，旧缓存没有时间`() {
        val dir = Files.createTempDirectory("overview-cache-test").also { it.toFile().deleteOnExit() }
        OverviewCache(dir).put(
            "s1",
            OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "a", t1), OverviewNode(NodeSymbol.SIDE, "b")), Instant.now()),
            fileSize = 4, lineCount = 2,
        )
        val loaded = OverviewCache(dir).get("s1")!!.result.nodes
        assertEquals(listOf(t1, null), loaded.map { it.time })

        Files.writeString(dir.resolve("old.json"), """{"fileSize":10,"generatedAt":0,"nodes":[{"symbol":"MAIN","text":"旧"}]}""")
        assertNull(OverviewCache(dir).get("old")!!.result.nodes.single().time)
    }

    @Test
    fun `提示词要求带上时间，增量时上次报告带着时间`() {
        val file = Path.of("/tmp/s.jsonl")
        val zh = reportPrompt(true, PluginLanguage.SIMPLIFIED_CHINESE, AgentType.CLAUDE, file, null, TrailCursor(10, 0))
        assertTrue(zh.contains("\"time\""))
        assertTrue(zh.contains("原样抄写"))
        val en = reportPrompt(false, PluginLanguage.ENGLISH, AgentType.CODEX, file, null, TrailCursor(10, 0))
        assertTrue(en.contains("copied verbatim from the record"))

        val nodes = listOf(OverviewNode(NodeSymbol.MAIN, "有时间", t1), OverviewNode(NodeSymbol.SIDE, "没时间"))
        val cached = CachedOverview(OverviewResult(nodes, Instant.now(), report = SessionReport(timeline = nodes)), 100, 5)
        val incremental = reportPrompt(true, PluginLanguage.SIMPLIFIED_CHINESE, AgentType.PI, file, cached, TrailCursor(9, 200))
        assertTrue(incremental.contains("有时间"))
        assertTrue("有时间的旧节点带着时间", incremental.contains("\"time\": \"$t1\""))
        assertTrue(incremental.contains("没时间"))
        assertTrue("合并节点保留开始时间", incremental.contains("合并的节点用其中最早的时间"))
    }

    @Test
    fun `增量提示词要求旧节点没有时间就保持没有，不要猜`() {
        val old = listOf(OverviewNode(NodeSymbol.MAIN, "旧"))
        val cached = CachedOverview(OverviewResult(old, Instant.now(), report = SessionReport(timeline = old)), 100, 5)
        val zh = reportPrompt(true, PluginLanguage.SIMPLIFIED_CHINESE, AgentType.CLAUDE, Path.of("/tmp/s.jsonl"), cached, TrailCursor(9, 200))
        assertTrue(zh.contains("没有时间的旧节点保持没有时间，不要猜测或补写"))
        val en = reportPrompt(false, PluginLanguage.ENGLISH, AgentType.CLAUDE, Path.of("/tmp/s.jsonl"), cached, TrailCursor(9, 200))
        assertTrue(en.contains("earlier nodes without a time must stay without a time; do not guess or fill one in"))
    }

    @Test
    fun `上次报告的节点没有时间时增量更新：旧节点继续无时间，新节点带时间，且不触发全量重读`() {
        val file = Files.createTempFile("session", ".jsonl").also { it.toFile().deleteOnExit() }
        Files.writeString(file, "u1\na1\n")
        val cache = OverviewCache(null)
        // 已有增量基线（行号），上次报告里的节点没有时间
        val old = listOf(OverviewNode(NodeSymbol.MAIN, "旧节点"))
        cache.put("s1", OverviewResult(old, Instant.now(), report = SessionReport(timeline = old)), Files.size(file), 2)
        Files.writeString(file, "u1\na1\nu2\na2\n")

        val prompts = mutableListOf<String>()
        val result = runTrailGeneration(
            cache, "s1", file,
            { c, cursor -> reportPrompt(true, PluginLanguage.SIMPLIFIED_CHINESE, AgentType.CLAUDE, file, c, cursor) },
            runAgent = { prompt ->
                prompts += prompt
                reportOutput("[MAIN] 旧节点\n[MAIN|2026-10-21T02:12:30Z] 新节点")
            },
        )

        assertEquals(1, prompts.size)
        assertTrue("仍是增量，只读新增的行", prompts.single().contains("只需读取第 3 行到第 4 行"))
        assertTrue("旧节点原样交给智能体，没有时间", prompts.single().contains("旧节点"))
        assertEquals(listOf(null, t1), result.nodes.map { it.time })
        assertEquals(listOf(null, t1), cache.get("s1")!!.result.nodes.map { it.time })
    }

    @Test
    fun `没有新消息时更新不调用智能体，旧节点也不会因此补上时间`() {
        val file = Files.createTempFile("session", ".jsonl").also { it.toFile().deleteOnExit() }
        Files.writeString(file, "u1\na1\n")
        val cache = OverviewCache(null)
        cache.put("s1", OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "旧节点")), Instant.now()), Files.size(file), 2)

        var calls = 0
        val result = runTrailGeneration(cache, "s1", file, { _, _ -> "p" }, runAgent = { calls++; reportOutput("[MAIN|2026-10-21T02:12:30Z] 旧节点") })

        assertEquals("不为补时间而重读", 0, calls)
        assertNull(result.nodes.single().time)
    }
}
