package com.github.izerui.imux.turn

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class CompletionSubtitleTest {

    @Test
    fun `项目名 agent 耗时依次连起来`() {
        assertEquals(
            "imux · Claude Code · 耗时 2 分 13 秒",
            completionSubtitle("imux", AgentType.CLAUDE, Duration.ofSeconds(133)),
        )
    }

    @Test
    fun `agent 未知时跳过它，不留多余间隔号`() {
        // 会话刚落盘、扫描还没纳入时查不到它属于哪个 agent
        assertEquals("imux · 耗时 5 秒", completionSubtitle("imux", null, Duration.ofSeconds(5)))
    }

    @Test
    fun `耗时未知时跳过它`() {
        // 插件启动前就已经在跑的会话，起点无从得知，宁可不显示也不要报个错的数
        assertEquals("imux · Codex", completionSubtitle("imux", AgentType.CODEX, null))
    }

    @Test
    fun `都没有时只剩项目名`() {
        assertEquals("imux", completionSubtitle("imux", null, null))
    }

    @Test
    fun `耗时按量级选用单位`() {
        assertEquals("1 秒", formatDuration(Duration.ofMillis(1400)))
        assertEquals("59 秒", formatDuration(Duration.ofSeconds(59)))
        assertEquals("1 分 0 秒", formatDuration(Duration.ofSeconds(60)))
        assertEquals("2 分 13 秒", formatDuration(Duration.ofSeconds(133)))
        assertEquals("59 分 59 秒", formatDuration(Duration.ofSeconds(3599)))
        assertEquals("1 小时 0 分", formatDuration(Duration.ofSeconds(3600)))
        assertEquals("2 小时 5 分", formatDuration(Duration.ofSeconds(7500)))
    }

    @Test
    fun `不足一秒显示为 0 秒而不是空`() {
        assertEquals("0 秒", formatDuration(Duration.ofMillis(300)))
    }

    @Test
    fun `负的时长按 0 处理`() {
        // 时钟回拨等极端情况下不该显示成「-3 秒」
        assertEquals("0 秒", formatDuration(Duration.ofSeconds(-3)))
    }
}
