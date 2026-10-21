package com.github.izerui.imux.overview

import com.github.izerui.imux.model.AgentType
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
        val nodes = parseOverviewOutput(
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
        val nodes = parseOverviewOutput("[MAIN] 旧格式\n[SIDE|昨天下午] 时间写坏了\n[ERROR|] 空时间")

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

    @Test
    fun `按本地时区与距今远近显示`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-10-21T06:00:00Z")
        assertEquals("当天只显示时分", "10:12", nodeTimeText(t1, now, zone))
        assertEquals("当年显示月日", "10-20 18:00", nodeTimeText(Instant.parse("2026-10-20T10:00:00Z"), now, zone))
        assertEquals("跨年只显示日期", "2025-12-31", nodeTimeText(Instant.parse("2025-12-31T00:00:00Z"), now, zone))
    }

    @Test
    fun `时间在轨迹线左边单独成列，各行圆点与竖线对齐`() = onEdt {
        val now = Instant.now()
        val result = OverviewResult(
            listOf(
                OverviewNode(NodeSymbol.MAIN, "跨天的节点", now.minusSeconds(3 * 86_400)),
                OverviewNode(NodeSymbol.SIDE, "没有时间的节点"),
                OverviewNode(NodeSymbol.CHANGE, "当天的节点", now.minusSeconds(60)),
            ),
            now,
        )
        val panel = buildResultPanel(result, stale = false) {}
        val host = layoutOffscreen(panel, 360, 400)
        fun rect(c: java.awt.Component) = javax.swing.SwingUtilities.convertRectangle(c.parent, c.bounds, host)
        val labels = allComponents(panel).filterIsInstance<javax.swing.JLabel>()
        val symbols = labels.filter { it.text?.trim() in setOf("●", "○", "↻") }
        val connectors = labels.filter { it.text?.trim() == "│" }
        val times = labels.filter { it.toolTipText?.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")) == true }

        assertEquals(3, symbols.size); assertEquals(2, connectors.size); assertEquals(2, times.size)
        val lineX = rect(symbols.first()).x
        assertTrue("圆点在同一列：${symbols.map { rect(it) }}", symbols.all { rect(it).x == lineX })
        val lineCenter = rect(symbols.first()).centerX.toInt() - 0
        assertTrue("竖线与圆点同列：${connectors.map { rect(it) }} vs ${symbols.map { rect(it) }}", connectors.all { glyphCenterX(it, ::rect) == glyphCenterX(symbols.first(), ::rect) })
        assertTrue("时间都在轨迹线左边：${times.map { rect(it) }} vs $lineX", times.all { rect(it).maxX <= lineX })
        assertTrue("时间右对齐、紧贴轨迹线", times.all { it.horizontalAlignment == javax.swing.SwingConstants.RIGHT } && times.map { rect(it).maxX }.distinct().size == 1)
        assertTrue("长短不同的时间都完整显示", times.all { it.width >= it.preferredSize.width })
    }

    @Test
    fun `240px 卡片里跨年时间与无时间回滚节点混排，回滚节点正文仍完整可见`() = onEdt {
        for (isChinese in listOf(true, false)) {
            val now = Instant.now()
            val longText = "尝试把轨迹放进独立窗口并跟随标签页移动，最终因焦点和分屏归属问题放弃"
            val result = OverviewResult(
                listOf(
                    OverviewNode(NodeSymbol.MAIN, "确认需求", now.minusSeconds(400L * 86_400)),
                    OverviewNode(NodeSymbol.ROLLBACK_START, longText),
                    OverviewNode(NodeSymbol.ROLLBACK_END, "回到叠加层方案"),
                ),
                now,
            )
            val panel = buildResultPanel(result, stale = false) {}
            // 结果面板里的节点用 createNodePanel 的 isChinese 标签；直接在节点层面再测一遍中英文标签
            val host = layoutOffscreen(panel, 240, 600)
            val node = createNodePanel(result.nodes[1], inRollback = true, isChinese = isChinese, timeColumnWidthFor(result.nodes))
            // 结果面板左右内边距 12、时间线左右内边距 4：240px 卡片里节点行实际宽 240 - 32
            val nodeHost = layoutOffscreen(node, 240 - com.intellij.util.ui.JBUI.scale(32), 600)

            for ((root, h) in listOf(panel to host, node to nodeHost)) {
                val pane = allComponents(root).filterIsInstance<javax.swing.JTextPane>().single { it.text == longText }
                val where = "zh=$isChinese pane=${pane.bounds} pref=${pane.preferredSize}"
                assertTrue("回滚节点正文要留出可读宽度（约一行 6 个汉字以上）：$where", pane.width >= com.intellij.util.ui.JBUI.scale(80))
                assertTrue("正文没被截掉高度：$where", pane.height >= pane.preferredSize.height)
                val last = pane.modelToView2D(pane.document.length - 1)
                assertTrue("末尾文字在正文区域内：$where last=$last", last != null && last.maxY <= pane.height && last.maxX <= pane.width)

                val tag = allComponents(root).filterIsInstance<javax.swing.JLabel>().single { it.text in setOf("已回滚", "rolled back") }
                val tagRect = javax.swing.SwingUtilities.convertRectangle(tag.parent, tag.bounds, h)
                assertTrue("回滚标签完整显示：$where tag=$tagRect", tag.width >= tag.preferredSize.width && tagRect.x >= 0 && tagRect.maxX <= h.width)
            }

            // 父容器裁切：正文末尾和标签必须完整落在所属节点行内，下一条连接线在标签下方
            val timeline = allComponents(panel).filterIsInstance<ScrollablePanel>().single()
            val pane = allComponents(panel).filterIsInstance<javax.swing.JTextPane>().single { it.text == longText }
            val tag = allComponents(panel).filterIsInstance<javax.swing.JLabel>().single { it.text in setOf("已回滚", "rolled back") }
            val rows = timeline.components.toList()
            val row = rows.single { javax.swing.SwingUtilities.isDescendingFrom(pane, it) }
            val rowArea = java.awt.Rectangle(0, 0, row.width, row.height)
            // 光标位置宽度为 0，Rectangle.contains 永远不认；取最后一个字前后两个光标位置的并集
            val lastChar = pane.modelToView2D(pane.document.length - 1).bounds.union(pane.modelToView2D(pane.document.length).bounds)
            check(lastChar.width > 0) { "最后一个字的范围应有宽度：$lastChar" }
            val lastInRow = javax.swing.SwingUtilities.convertRectangle(pane, lastChar, row)
            val tagInRow = javax.swing.SwingUtilities.convertRectangle(tag.parent, tag.bounds, row)
            val where = "row=${row.bounds} last=$lastInRow tag=$tagInRow"
            assertTrue("正文末尾完整落在节点行内：$where", rowArea.contains(lastInRow))
            assertTrue("回滚标签完整落在节点行内：$where", rowArea.contains(tagInRow))
            val next = rows[rows.indexOf(row) + 1]
            assertTrue("下一条是连接线", (next as? javax.swing.JLabel)?.text?.trim() == "│")
            val tagInTimeline = javax.swing.SwingUtilities.convertRectangle(tag.parent, tag.bounds, timeline)
            assertTrue("连接线在标签下方：next=${next.bounds} tag=$tagInTimeline", next.y >= tagInTimeline.maxY)
        }
    }

    @Test
    fun `宽卡片里回滚标签仍在正文右边`() = onEdt {
        val node = createNodePanel(OverviewNode(NodeSymbol.ROLLBACK_START, "尝试独立窗口方案"), inRollback = true, isChinese = true, timeColumnWidth = 0)
        val host = layoutOffscreen(node, 600, 200)
        val pane = allComponents(node).filterIsInstance<javax.swing.JTextPane>().single()
        val tag = allComponents(node).filterIsInstance<javax.swing.JLabel>().single { it.text == "已回滚" }
        fun rect(c: java.awt.Component) = javax.swing.SwingUtilities.convertRectangle(c.parent, c.bounds, host)
        assertTrue("同一行：pane=${rect(pane)} tag=${rect(tag)}", rect(tag).y == rect(pane).y && rect(tag).x >= rect(pane).maxX)
    }

    /** 符号在其标签里的绘制中心：标签居中对齐时就是去掉内边距后的区域中心。 */
    private fun glyphCenterX(label: javax.swing.JLabel, rect: (java.awt.Component) -> java.awt.Rectangle): Int {
        val r = rect(label)
        val insets = label.insets
        return r.x + insets.left + (r.width - insets.left - insets.right) / 2
    }

    @Test
    fun `所有类型的符号与连接竖线居中在同一列`() = onEdt {
        for (withTime in listOf(false, true)) {
            val now = Instant.now()
            val types = listOf(
                NodeSymbol.MAIN, NodeSymbol.ERROR, NodeSymbol.ROLLBACK_START, NodeSymbol.MAIN, NodeSymbol.ROLLBACK_END,
                NodeSymbol.SIDE, NodeSymbol.CHANGE, NodeSymbol.IN_PROGRESS,
            )
            val nodes = types.mapIndexed { i, t -> OverviewNode(t, "节点 $i", if (withTime && i == 0) now.minusSeconds(60) else null) }
            val panel = buildResultPanel(OverviewResult(nodes, now), stale = false) {}
            val host = layoutOffscreen(panel, 360, 800)
            fun rect(c: java.awt.Component) = javax.swing.SwingUtilities.convertRectangle(c.parent, c.bounds, host)
            val timeline = allComponents(panel).filterIsInstance<ScrollablePanel>().single()
            val connectors = timeline.components.filterIsInstance<javax.swing.JLabel>()
            val symbols = timeline.components.filterIsInstance<javax.swing.JPanel>().map { row ->
                allComponents(row).filterIsInstance<javax.swing.JLabel>().first { it.toolTipText == null && it.text?.isNotBlank() == true && it.text !in setOf("已回滚", "rolled back") }
            }
            assertEquals(types.size, symbols.size)
            assertEquals(types.size - 1, connectors.size)
            val all = symbols + connectors
            val centers = all.map { glyphCenterX(it, ::rect) }
            assertTrue("withTime=$withTime 符号与竖线都居中：${all.map { it.text to it.horizontalAlignment }}", all.all { it.horizontalAlignment == javax.swing.SwingConstants.CENTER })
            assertEquals("withTime=$withTime 同一列中心：${all.map { it.text }} -> $centers", 1, centers.distinct().size)
            assertEquals("符号文字不带前导空格：${symbols.map { it.text }}", symbols.map { it.text.trim() }, symbols.map { it.text })
        }
    }

    @Test
    fun `一个节点都没有时间时不占时间列`() = onEdt {
        val result = OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "a"), OverviewNode(NodeSymbol.MAIN, "b")), Instant.now())
        val panel = buildResultPanel(result, stale = false) {}
        val host = layoutOffscreen(panel, 360, 400)
        val dot = allComponents(panel).filterIsInstance<javax.swing.JLabel>().first { it.text?.trim() == "●" }
        val x = javax.swing.SwingUtilities.convertRectangle(dot.parent, dot.bounds, host).x
        assertTrue("圆点仍贴在左侧：x=$x", x <= com.intellij.util.ui.JBUI.scale(30))
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
    fun `提示词要求带上时间，增量时旧时间线带着时间`() {
        val file = Path.of("/tmp/s.jsonl")
        val zh = overviewPrompt(true, AgentType.CLAUDE, file, 10)
        assertTrue(zh.contains("[TYPE|时间] 一句话描述"))
        assertTrue(zh.contains("原样抄写"))
        val en = overviewPrompt(false, AgentType.CODEX, file, 10)
        assertTrue(en.contains("[TYPE|time] One-sentence description"))

        val cached = CachedOverview(
            OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "有时间", t1), OverviewNode(NodeSymbol.SIDE, "没时间")), Instant.now()),
            100, 5,
        )
        val incremental = trailPrompt(true, AgentType.PI, file, cached, TrailCursor(9, 200))
        assertTrue(incremental.contains("[MAIN|2026-10-21T02:12:30Z] 有时间"))
        assertTrue(incremental.contains("[SIDE] 没时间"))
        assertTrue("合并节点保留开始时间", incremental.contains("合并后的节点用其中最早的时间"))
    }

    @Test
    fun `增量提示词要求旧节点没有时间就保持没有，不要猜`() {
        val cached = CachedOverview(OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "旧")), Instant.now()), 100, 5)
        val zh = trailPrompt(true, AgentType.CLAUDE, Path.of("/tmp/s.jsonl"), cached, TrailCursor(9, 200))
        assertTrue(zh.contains("旧时间线里没有时间的节点保持没有时间，不要猜测或补写"))
        val en = trailPrompt(false, AgentType.CLAUDE, Path.of("/tmp/s.jsonl"), cached, TrailCursor(9, 200))
        assertTrue(en.contains("Earlier nodes without a time must stay without a time; do not guess or fill one in"))
    }

    @Test
    fun `旧缓存没有时间时增量更新：旧节点继续无时间，新节点带时间，且不触发全量重读`() {
        val file = Files.createTempFile("session", ".jsonl").also { it.toFile().deleteOnExit() }
        Files.writeString(file, "u1\na1\n")
        val cache = OverviewCache(null)
        // 升级前的缓存：有行号（本轮已有增量基线），节点没有时间
        cache.put("s1", OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "旧节点")), Instant.now()), Files.size(file), 2)
        Files.writeString(file, "u1\na1\nu2\na2\n")

        val prompts = mutableListOf<String>()
        val result = runTrailGeneration(
            cache, "s1", file,
            { c, cursor -> trailPrompt(true, AgentType.CLAUDE, file, c, cursor) },
            runAgent = { prompt ->
                prompts += prompt
                "[MAIN] 旧节点\n[MAIN|2026-10-21T02:12:30Z] 新节点"
            },
        )

        assertEquals(1, prompts.size)
        assertTrue("仍是增量，只读新增的行", prompts.single().contains("只需读取第 3 行到第 4 行"))
        assertTrue("旧节点原样交给智能体，没有时间", prompts.single().contains("[MAIN] 旧节点"))
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
        val result = runTrailGeneration(cache, "s1", file, { _, _ -> "p" }, runAgent = { calls++; "[MAIN|2026-10-21T02:12:30Z] 旧节点" })

        assertEquals("不为补时间而重读", 0, calls)
        assertNull(result.nodes.single().time)
    }
}
