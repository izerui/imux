package com.github.izerui.imux.turn

import com.github.izerui.imux.model.AgentType
import com.intellij.ide.nls.NlsMessages
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class CompletionSubtitleTest {

    @Test
    fun `项目名 agent 耗时依次连起来`() {
        val duration = NlsMessages.formatDuration(133_000)
        assertEquals(
            "imux · Claude Code · 耗时 $duration",
            completionSubtitle("imux", AgentType.CLAUDE, Duration.ofSeconds(133)),
        )
    }

    @Test
    fun `agent 未知时跳过它，不留多余间隔号`() {
        // 会话刚落盘、扫描还没纳入时查不到它属于哪个 agent
        val duration = NlsMessages.formatDuration(5_000)
        assertEquals("imux · 耗时 $duration", completionSubtitle("imux", null, Duration.ofSeconds(5)))
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
        listOf(1_400L, 59_000L, 60_000L, 133_000L, 3_599_000L, 3_600_000L, 7_500_000L)
            .forEach { millis ->
                assertEquals(
                    NlsMessages.formatDuration(millis),
                    formatDuration(Duration.ofMillis(millis)),
                )
            }
    }

    @Test
    fun `不足一秒显示为 0 秒而不是空`() {
        assertEquals(NlsMessages.formatDuration(300), formatDuration(Duration.ofMillis(300)))
    }

    @Test
    fun `负的时长按 0 处理`() {
        assertEquals(NlsMessages.formatDuration(0), formatDuration(Duration.ofSeconds(-3)))
    }
}
