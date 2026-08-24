package com.github.izerui.imux.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/**
 * 这个类是整个刷新节奏的心脏——运行中标记多久亮、新会话多久出现在列表里，
 * 全由它的节拍决定。它早就为可测性注入了 today / intervalMs / slowEveryTicks，
 * 却一直没有测试。
 *
 * 不测 [SessionStoreWatcher.start]：那要占用平台的调度器。直接驱动 tick 即可，
 * 节奏逻辑本身与线程无关。
 */
class SessionStoreWatcherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val today = LocalDate.of(2026, 8, 6)

    private lateinit var claudeHome: File
    private lateinit var codexHome: File
    private lateinit var piHome: File

    private var changes = 0
    private var ticks = 0

    private fun watcher(
        fastTickWanted: () -> Boolean = { false },
        slowEveryTicks: Int = 3,
        onTick: () -> Unit = { ticks++ },
    ): SessionStoreWatcher {
        claudeHome = File(tmp.root, "claude").apply { mkdirs() }
        codexHome = File(tmp.root, "codex").apply { mkdirs() }
        piHome = File(tmp.root, "pi").apply { mkdirs() }
        return SessionStoreWatcher(
            claudeHome = claudeHome.toPath(),
            codexHome = codexHome.toPath(),
            piHome = piHome.toPath(),
            claudeProjectDirName = "-Users-demo-proj",
            piProjectDirName = "--Users-demo-proj--",
            onChange = { changes++ },
            onTick = onTick,
            fastTickWanted = fastTickWanted,
            today = { today },
            slowEveryTicks = slowEveryTicks,
        )
    }

    private fun claudeDir(): File = File(claudeHome, "projects/-Users-demo-proj").apply { mkdirs() }

    private fun codexDir(day: LocalDate = today): File =
        File(codexHome, "sessions/%04d/%02d/%02d".format(day.year, day.monthValue, day.dayOfMonth))
            .apply { mkdirs() }

    private fun piDir(): File = File(piHome, "agent/sessions/--Users-demo-proj--").apply { mkdirs() }

    // ---- 监听范围 ----

    @Test
    fun `只盯本项目的 claude 与 pi 目录，以及 codex 最近两天`() {
        val w = watcher()

        val dirs = w.watchedDirs().map { it.toString() }

        assertEquals(4, dirs.size)
        assertTrue(dirs[0].endsWith("projects/-Users-demo-proj"))
        assertTrue(dirs[1].endsWith("sessions/2026/08/06"))
        assertTrue(dirs[2].endsWith("sessions/2026/08/05"))
        // pi 与 claude 一样是一个项目一个目录，不必按日期回看
        assertTrue(dirs[3].endsWith("agent/sessions/--Users-demo-proj--"))
    }

    @Test
    fun `pi 会话文件的变化会触发回调`() {
        val w = watcher()
        w.start()
        File(piDir(), "2026-08-13T10-00-00-000Z_p1.jsonl").writeText("{}")

        repeat(3) { w.tick() }

        assertEquals(1, changes)
    }

    /** 跨月要借位到上个月的最后一天，不能变成 08/00。 */
    @Test
    fun `月初回看上个月最后一天`() {
        claudeHome = File(tmp.root, "claude").apply { mkdirs() }
        codexHome = File(tmp.root, "codex").apply { mkdirs() }
        val w =
            SessionStoreWatcher(
                claudeHome = claudeHome.toPath(),
                codexHome = codexHome.toPath(),
                piHome = File(tmp.root, "pi").apply { mkdirs() }.toPath(),
                claudeProjectDirName = "p",
                piProjectDirName = "--p--",
                onChange = {},
                today = { LocalDate.of(2026, 8, 1) },
            )

        assertTrue(w.watchedDirs()[2].toString().endsWith("sessions/2026/07/31"))
    }

    // ---- 指纹 ----

    @Test
    fun `指纹只统计 jsonl 文件`() {
        val w = watcher()
        File(claudeDir(), "a.jsonl").writeText("x")
        val withJsonl = w.signature()

        File(claudeDir(), "b.txt").writeText("y")

        assertEquals(withJsonl, w.signature())
    }

    @Test
    fun `文件内容变化会改变指纹`() {
        val w = watcher()
        val file = File(claudeDir(), "a.jsonl").apply { writeText("x") }
        val before = w.signature()

        file.writeText("xxxxxx")

        assertTrue(before != w.signature())
    }

    @Test
    fun `目录不存在时指纹为空而不抛异常`() {
        assertEquals("", watcher().signature())
    }

    // ---- 节奏 ----

    @Test
    fun `会话库比对只在慢周期做`() {
        val w = watcher(slowEveryTicks = 3)
        File(claudeDir(), "a.jsonl").writeText("x")

        w.tick()
        w.tick()
        assertEquals("前两拍不该做全量比对", 0, changes)

        w.tick()
        assertEquals(1, changes)
    }

    @Test
    fun `指纹没变时不触发刷新`() {
        val w = watcher(slowEveryTicks = 1)
        File(claudeDir(), "a.jsonl").writeText("x")

        w.tick()
        assertEquals(1, changes)

        w.tick()
        assertEquals("内容没动就不该再刷", 1, changes)
    }

    @Test
    fun `有标签页开着时每拍都刷新运行状态`() {
        val w = watcher(fastTickWanted = { true }, slowEveryTicks = 3)

        repeat(3) { w.tick() }

        assertEquals(3, ticks)
    }

    /** 一个标签页都没开时没人看运行中标记，退回慢节奏省电。 */
    @Test
    fun `没有标签页时只在慢周期刷新运行状态`() {
        val w = watcher(fastTickWanted = { false }, slowEveryTicks = 3)

        repeat(3) { w.tick() }

        assertEquals(1, ticks)
    }

    // ---- 容错 ----

    @Test
    fun `onTick 抛异常不影响会话库比对`() {
        val w = watcher(fastTickWanted = { true }, slowEveryTicks = 1, onTick = { error("炸") })
        File(claudeDir(), "a.jsonl").writeText("x")

        w.tick()

        assertEquals("onTick 的异常不该带走 onChange", 1, changes)
    }

    @Test
    fun `fastTickWanted 抛异常时按不需要处理`() {
        val w = watcher(fastTickWanted = { error("炸") }, slowEveryTicks = 3)

        w.tick()

        assertEquals(0, ticks)
    }

    @Test
    fun `codex 会话落在昨天的目录里也能发现`() {
        val w = watcher(slowEveryTicks = 1)
        File(codexDir(today.minusDays(1)), "a.jsonl").writeText("x")

        w.tick()

        assertEquals(1, changes)
    }
}
