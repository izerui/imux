package com.github.izerui.imux.progress

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressWiringTest {
    @Test
    fun `进度上报直接保存且不增加独立会话文件刷新`() {
        val monitor = SourceCode("src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt").normalized
        val service = SourceCode("src/main/kotlin/com/github/izerui/imux/peer/mcp/PeerMcpService.kt").normalized

        assertFalse(monitor.contains("progress.refreshInBackground"))
        assertFalse(monitor.contains("progress.observe(session.id, session.agentType, session.filePath"))
        assertFalse(service.contains("synchronizeSource"))
        assertFalse(service.contains("Dispatchers.IO"))
        assertTrue(service.contains("store.report(key, percent, string(args, \"message\"))"))
    }

    @Test
    fun `已有监控把完整轮次边界和采样时间传给进度`() {
        val monitor = SourceCode("src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt").normalized
        assertTrue(monitor.contains("val sampledAt = System.currentTimeMillis() val snapshot = runtimeIndex.load(projectPath)"))
        assertTrue(monitor.contains("transitions = watcher.progressTransitions(sessionId), sampledAt = sampledAt,"))
        assertTrue(monitor.contains("runningIds = running"))
        assertTrue(monitor.contains("sessionRunning = sessionId in running,"))
        val store = SourceCode("src/main/kotlin/com/github/izerui/imux/progress/TurnProgressStore.kt").normalized
        assertTrue(store.contains("record.observe(active, waiting, endReason, transitions, sampledAt, sessionRunning)"))
        assertTrue(monitor.contains("if (sessionId !in currentProgressSessions) return@forEach"))
        assertTrue(monitor.contains("PiReportType.UI_PROMPT_START, PiReportType.UI_PROMPT_END"))
        assertTrue(monitor.contains("report.type == PiReportType.UI_PROMPT_START,"))
    }
}
