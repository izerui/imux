package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProgressNoticeSourceTest {
    @Test
    fun `信息弹窗不订阅或显示本轮进度`() {
        val panel = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/SessionInfoPanel.kt").normalized
        val tool = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/SessionInfoTool.kt").normalized
        assertFalse(panel.contains("SessionProgressPanel"))
        assertFalse(panel.contains("updateProgress"))
        assertFalse(tool.contains("TurnProgressStore"))
    }

    @Test
    fun `本轮进度进入顶部提示区且换会话后重读`() {
        val source = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt")
        val code = source.normalized
        assertTrue(code.contains("noticeOverlay.sink(NoticeSource.PROGRESS)"))
        assertTrue(code.contains("progressNotices.persistent(next)"))
        assertTrue(code.contains("progressStore.listen(this, ::updateProgress)"))
        assertTrue(code.contains("addSessionKeyMigratedListener(this) { _, _ -> updateProgress() }"))
        assertFalse(code.contains("progressBar"))
        assertFalse(code.contains("refreshProgressVisibility"))
    }
}
