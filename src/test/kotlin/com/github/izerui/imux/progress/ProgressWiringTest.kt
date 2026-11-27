package com.github.izerui.imux.progress

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressWiringTest {
    @Test
    fun `进度上报和监控都不读取会话文件`() {
        val monitor = SourceCode("src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt").normalized
        val service = SourceCode("src/main/kotlin/com/github/izerui/imux/peer/mcp/PeerMcpService.kt").normalized

        assertFalse(monitor.contains("progress.refreshInBackground"))
        assertFalse(monitor.contains("progress.observe(session.id, session.agentType, session.filePath"))
        assertFalse(service.contains("synchronizeSource"))
        assertFalse(service.contains("Dispatchers.IO"))
        assertTrue(service.contains("store.report(key, percent, string(args, \"message\"))"))
    }
}
