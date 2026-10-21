package com.github.izerui.imux.overview

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

class IncrementalTrailTest {
    private val nodes = listOf(
        OverviewNode(NodeSymbol.MAIN, "确认需求：右上角轨迹面板"),
        OverviewNode(NodeSymbol.IN_PROGRESS, "实现叠加层"),
    )

    private fun cached(fileSize: Long, lineCount: Long?, result: List<OverviewNode> = nodes) =
        CachedOverview(OverviewResult(result, Instant.now()), fileSize, lineCount)

    private fun tempFile(text: String): Path =
        Files.createTempFile("session", ".jsonl").also {
            it.toFile().deleteOnExit()
            Files.writeString(it, text)
        }

    private fun append(file: Path, text: String) {
        Files.writeString(file, text, StandardOpenOption.APPEND)
    }

    // ── 核心场景：会话一直在聊 ──

    @Test
    fun `第一次生成期间会话继续追加，第二次基于第一次的新时间线从其截止行之后读起`() {
        val file = tempFile("u1\na1\nu2\n")
        val cache = OverviewCache(null)
        val prompts = mutableListOf<String>()
        val build = { c: CachedOverview?, cursor: TrailCursor -> trailPrompt(true, AgentType.CLAUDE, file, c, cursor) }

        // 第一次：完整生成，截止第 3 行；智能体运行期间会话又追加了两行
        val first = runTrailGeneration(cache, "s1", file, build, runAgent = { prompt ->
            prompts += prompt
            append(file, "u3\na3\n")
            "[MAIN] 第一次的时间线"
        })

        assertTrue("第一次是完整生成", prompts[0].contains("本次只总结第 1 行到第 3 行"))
        assertTrue("截止之后又有新内容，结果标为过期", first.stale)
        val saved = cache.get("s1")!!
        assertEquals("即使过期也要保存本次时间线", "第一次的时间线", saved.result.nodes.single().text)
        assertEquals("游标停在本次截止行", 3L, saved.lineCount)
        assertTrue(isStale(saved, file))

        // 第二次：必须拿到第一次的新时间线，并从第 4 行读到第 5 行
        val second = runTrailGeneration(cache, "s1", file, build, runAgent = { prompt ->
            prompts += prompt
            "[MAIN] 第一次的时间线\n[MAIN] 第二次新增"
        })

        val incremental = prompts[1]
        assertTrue("应带上第一次的新时间线", incremental.contains("[MAIN] 第一次的时间线"))
        assertTrue("应从第一次截止行之后读起", incremental.contains("只需读取第 4 行到第 5 行"))
        assertTrue(incremental.contains("不要读取第 4 行之前的内容"))
        assertFalse("第二次期间没有追加，结果是最新的", second.stale)
        assertEquals(5L, cache.get("s1")!!.lineCount)
        assertFalse(isStale(cache.get("s1")!!, file))
    }

    @Test
    fun `首次生成一直遇到追加也能建立基线，之后走增量`() {
        val file = tempFile("u1\na1\n")
        val cache = OverviewCache(null)
        val prompts = mutableListOf<String>()
        val build = { c: CachedOverview?, cursor: TrailCursor -> trailPrompt(true, AgentType.PI, file, c, cursor) }

        repeat(3) { round ->
            runTrailGeneration(cache, "s1", file, build, runAgent = { prompt ->
                prompts += prompt
                append(file, "u${round + 2}\n")
                "[MAIN] 第 ${round + 1} 次"
            })
        }

        assertTrue(prompts[0].contains("本次只总结第 1 行到第 2 行"))
        assertTrue("第二次已有基线", prompts[1].contains("只需读取第 3 行到第 3 行"))
        assertTrue(prompts[1].contains("[MAIN] 第 1 次"))
        assertTrue(prompts[2].contains("只需读取第 4 行到第 4 行"))
        assertTrue(prompts[2].contains("[MAIN] 第 2 次"))
    }

    @Test
    fun `快照时还没写完的半行留到下一次`() {
        val file = tempFile("u1\na1\nhalf")
        val cache = OverviewCache(null)
        val prompts = mutableListOf<String>()
        val build = { c: CachedOverview?, cursor: TrailCursor -> trailPrompt(true, AgentType.CODEX, file, c, cursor) }

        val first = runTrailGeneration(cache, "s1", file, build, runAgent = { prompts += it; "[MAIN] 一" })
        assertTrue(prompts[0].contains("第 1 行到第 2 行"))
        assertTrue("半行不算已总结，结果过期", first.stale)

        append(file, " line\n")
        runTrailGeneration(cache, "s1", file, build, runAgent = { prompts += it; "[MAIN] 一\n[MAIN] 二" })
        assertTrue("补完的那一行从第 3 行读起", prompts[1].contains("只需读取第 3 行到第 3 行"))
    }

    @Test
    fun `半行一直没补完时连续重新生成，第二次不调用智能体，保留旧时间线并仍标过期`() {
        val file = tempFile("u1\na1\nu2\nhalf")
        val cache = OverviewCache(null)
        val build = { c: CachedOverview?, cursor: TrailCursor -> trailPrompt(true, AgentType.CLAUDE, file, c, cursor) }

        var firstCalls = 0
        val first = runTrailGeneration(cache, "s1", file, build, runAgent = { firstCalls++; "[MAIN] 总结到第 3 行" })
        assertEquals(1, firstCalls)
        assertTrue(first.stale)

        var secondCalls = 0
        val second = runTrailGeneration(cache, "s1", file, build, runAgent = { secondCalls++; "[MAIN] 不应出现" })

        assertEquals("截止位置没变，不能再花 token", 0, secondCalls)
        assertEquals("总结到第 3 行", second.nodes.single().text)
        assertTrue("半行还在，仍是过期", second.stale)
        assertEquals("总结到第 3 行", cache.get("s1")!!.result.nodes.single().text)
        assertEquals(3L, cache.get("s1")!!.lineCount)
    }

    @Test
    fun `截止位置没变且文件已是最新时也不调用智能体`() {
        val file = tempFile("u1\na1\n")
        val cache = OverviewCache(null)
        runTrailGeneration(cache, "s1", file, { _, _ -> "p" }, runAgent = { "[MAIN] 一" })

        var calls = 0
        val again = runTrailGeneration(cache, "s1", file, { _, _ -> "p" }, runAgent = { calls++; "[MAIN] 二" })

        assertEquals(0, calls)
        assertEquals("一", again.nodes.single().text)
        assertFalse(again.stale)
    }

    @Test
    fun `截止位置没变但上次保存失败时，只重试写盘不调用智能体`() {
        val file = tempFile("u1\na1\n")
        // 先用普通文件占住存储目录的位置，写盘必然失败
        val storage = Files.createTempFile("overview-not-a-dir", ".txt").also { it.toFile().deleteOnExit() }
        val cache = OverviewCache(storage)
        val first = runTrailGeneration(cache, "s1", file, { _, _ -> "p" }, runAgent = { "[MAIN] 一" })
        assertTrue(first.saveFailed)

        // 目录问题仍在：不调用智能体，仍提示保存失败
        var calls = 0
        val stillFailing = runTrailGeneration(cache, "s1", file, { _, _ -> "p" }, runAgent = { calls++; "[MAIN] 二" })
        assertEquals(0, calls)
        assertTrue(stillFailing.saveFailed)

        // 用户修好目录后再点：写盘成功，失败标记消失，磁盘上能读回
        Files.delete(storage)
        val retried = runTrailGeneration(cache, "s1", file, { _, _ -> "p" }, runAgent = { calls++; "[MAIN] 二" })
        assertEquals(0, calls)
        assertFalse(retried.saveFailed)
        assertEquals("一", retried.nodes.single().text)
        assertEquals("一", OverviewCache(storage).get("s1")!!.result.nodes.single().text)
        assertFalse(cache.get("s1")!!.result.saveFailed)
    }

    @Test
    fun `文件缩短后截止位置倒退，退回完整生成`() {
        val file = tempFile("u1\na1\nu2\na2\n")
        val cache = OverviewCache(null)
        val prompts = mutableListOf<String>()
        val build = { c: CachedOverview?, cursor: TrailCursor -> trailPrompt(true, AgentType.PI, file, c, cursor) }
        runTrailGeneration(cache, "s1", file, build, runAgent = { prompts += it; "[MAIN] 四行" })

        Files.writeString(file, "x1\nx2\n")
        val again = runTrailGeneration(cache, "s1", file, build, runAgent = { prompts += it; "[MAIN] 改写后" })

        assertEquals(2, prompts.size)
        assertTrue("改写后从头完整生成", prompts[1].contains("本次只总结第 1 行到第 2 行"))
        assertEquals("改写后", again.nodes.single().text)
        assertEquals(2L, cache.get("s1")!!.lineCount)
    }

    @Test
    fun `只有半行时不调用智能体，存空结果`() {
        val file = tempFile("half")
        var calls = 0
        val result = runTrailGeneration(OverviewCache(null), "s1", file, { _, _ -> "p" }, runAgent = { calls++; "[MAIN] x" })

        assertEquals(0, calls)
        assertTrue(result.nodes.isEmpty())
        assertTrue(result.stale)
    }

    // ── 何时走增量 ──

    @Test
    fun `有新增完整行时从缓存行数的下一行开始`() {
        assertEquals(41L, incrementalStartLine(cached(1_000, 40), TrailCursor(55, 1_500)))
    }

    @Test
    fun `没有缓存时完整生成`() {
        assertNull(incrementalStartLine(null, TrailCursor(55, 1_500)))
    }

    @Test
    fun `升级前的旧缓存没有行号时完整生成`() {
        assertNull(incrementalStartLine(cached(1_000, null), TrailCursor(55, 1_500)))
    }

    @Test
    fun `旧结果为空时完整生成`() {
        assertNull(incrementalStartLine(cached(1_000, 40, emptyList()), TrailCursor(55, 1_500)))
    }

    @Test
    fun `截止位置没有超过上次时完整生成`() {
        assertNull(incrementalStartLine(cached(1_000, 40), TrailCursor(40, 1_000)))
        assertNull(incrementalStartLine(cached(1_000, 40), TrailCursor(38, 900)))
    }

    @Test
    fun `变大但完整行没有增加说明被改写，完整生成`() {
        assertNull(incrementalStartLine(cached(1_000, 40), TrailCursor(30, 1_500)))
        assertNull(incrementalStartLine(cached(1_000, 40), TrailCursor(40, 1_500)))
    }

    // ── 截止位置 ──

    @Test
    fun `截止到快照之内最后一个完整行`() {
        val file = tempFile("a\nb\nc\npartial")
        assertEquals(TrailCursor(3, 6), trailCursor(file, Files.size(file)))
        assertEquals("快照之后追加的内容不计入", TrailCursor(2, 4), trailCursor(file, 5))
    }

    @Test
    fun `空文件截止在开头`() {
        assertEquals(TrailCursor(0, 0), trailCursor(tempFile(""), 0))
    }

    // ── 缓存 ──

    @Test
    fun `行号随缓存写入磁盘并能读回`() {
        val dir = Files.createTempDirectory("overview-cache-test").also { it.toFile().deleteOnExit() }
        OverviewCache(dir).put("s1", OverviewResult(nodes, Instant.now()), fileSize = 4, lineCount = 2)

        val loaded = OverviewCache(dir).get("s1")!!
        assertEquals(2L, loaded.lineCount)
        assertEquals(4L, loaded.fileSize)
    }

    @Test
    fun `没有 lineCount 字段的旧缓存文件读出行号为 null`() {
        val dir = Files.createTempDirectory("overview-cache-test").also { it.toFile().deleteOnExit() }
        Files.writeString(
            dir.resolve("s1.json"),
            """{"fileSize":10,"generatedAt":0,"nodes":[{"symbol":"MAIN","text":"旧"}]}""",
        )

        val loaded = OverviewCache(dir).get("s1")!!
        assertEquals("旧", loaded.result.nodes.single().text)
        assertNull(loaded.lineCount)
    }

    // ── 提示词 ──

    @Test
    fun `增量提示词带上旧时间线和起止行`() {
        val file = Path.of("/tmp/session.jsonl")
        val prompt = trailPrompt(isChinese = true, AgentType.CLAUDE, file, cached(1_000, 40), TrailCursor(55, 1_500))

        assertTrue(prompt.contains("[MAIN] 确认需求：右上角轨迹面板"))
        assertTrue(prompt.contains("[IN_PROGRESS] 实现叠加层"))
        assertTrue(prompt.contains("只需读取第 41 行到第 55 行"))
        assertTrue("应要求不要读起始行之前的内容", prompt.contains("不要读取第 41 行之前"))
        assertTrue("应要求不要读截止行之后的内容", prompt.contains("第 55 行之后是本次开始后才追加的内容，不要读取"))
        assertTrue("应输出完整时间线", prompt.contains("完整时间线"))
        assertTrue("安全约束保留", prompt.contains("不要修改、创建或删除任何文件"))
        assertTrue(prompt.contains(file.toAbsolutePath().normalize().toString()))
    }

    @Test
    fun `英文增量提示词同样带上旧时间线和起止行`() {
        val prompt = trailPrompt(isChinese = false, AgentType.CODEX, Path.of("/tmp/s.jsonl"), cached(1_000, 40), TrailCursor(55, 1_500))

        assertTrue(prompt.contains("[MAIN] 确认需求：右上角轨迹面板"))
        assertTrue(prompt.contains("Read only lines 41 to 55"))
        assertTrue(prompt.contains("Do not read anything before line 41"))
        assertTrue(prompt.contains("Lines after 55 were appended after this run started"))
        assertTrue(prompt.contains("Do not modify, create or delete any file"))
    }

    @Test
    fun `完整提示词同样截止到快照行`() {
        val zh = overviewPrompt(true, AgentType.PI, Path.of("/tmp/s.jsonl"), 55)
        assertTrue(zh.contains("本次只总结第 1 行到第 55 行"))
        assertTrue(zh.contains("第 55 行之后是本次开始后才追加的内容，不要读取"))

        val en = overviewPrompt(false, AgentType.PI, Path.of("/tmp/s.jsonl"), 55)
        assertTrue(en.contains("Summarize only lines 1 to 55"))
        assertTrue(en.contains("Lines after 55 were appended after this run started"))
    }

    // ── 粒度：长期使用也保持简洁 ──

    @Test
    fun `完整与增量提示词都要求概括措辞并限制总数约 20 个`() {
        val file = Path.of("/tmp/s.jsonl")
        val zhPrompts = listOf(
            overviewPrompt(true, AgentType.CLAUDE, file, 55),
            trailPrompt(true, AgentType.CLAUDE, file, cached(1_000, 40), TrailCursor(55, 1_500)),
        )
        for (prompt in zhPrompts) {
            assertTrue(prompt.contains("不罗列文件名、命令、参数等实现细节"))
            assertTrue(prompt.contains("最多约 20 个"))
            assertTrue("不能再鼓励长会话无限增加", !prompt.contains("很长的会话可以适当增加"))
        }

        val enPrompts = listOf(
            overviewPrompt(false, AgentType.CODEX, file, 55),
            trailPrompt(false, AgentType.CODEX, file, cached(1_000, 40), TrailCursor(55, 1_500)),
        )
        for (prompt in enPrompts) {
            assertTrue(prompt.contains("do not list file names, commands or parameters"))
            assertTrue(prompt.contains("at most about 20 nodes"))
            assertTrue(!prompt.contains("very long sessions may use more"))
        }
    }

    @Test
    fun `增量提示词限制每次新增数量，只记里程碑，并允许超限时合并较早的同类节点`() {
        val zh = trailPrompt(true, AgentType.CLAUDE, Path.of("/tmp/s.jsonl"), cached(1_000, 40), TrailCursor(55, 1_500))
        assertTrue(zh.contains("通常新增 0-3 个节点"))
        assertTrue(zh.contains("只记里程碑级的进展"))
        assertTrue(zh.contains("零碎的追问和小修小补"))
        assertTrue(zh.contains("超过约 20 个节点时，把较早的、相邻的同类节点合并成一个概括节点"))
        assertTrue("关键转折不合并", zh.contains("CHANGE、ERROR 节点和回滚区间不合并"))

        val en = trailPrompt(false, AgentType.PI, Path.of("/tmp/s.jsonl"), cached(1_000, 40), TrailCursor(55, 1_500))
        assertTrue(en.contains("usually add 0-3 nodes"))
        assertTrue(en.contains("milestone-level progress"))
        assertTrue(en.contains("more than about 20 nodes, merge earlier adjacent nodes of the same type"))
        assertTrue(en.contains("never merge CHANGE or ERROR nodes or rollback sections"))
    }

    @Test
    fun `不满足增量条件时用完整提示词`() {
        val file = Path.of("/tmp/session.jsonl")
        val cursor = TrailCursor(55, 1_500)
        val full = overviewPrompt(true, AgentType.PI, file, 55)

        assertEquals(full, trailPrompt(true, AgentType.PI, file, null, cursor))
        assertEquals(full, trailPrompt(true, AgentType.PI, file, cached(1_000, null), cursor))
        assertFalse(trailPrompt(true, AgentType.PI, file, cached(1_000, 40), cursor) == full)
    }
}
