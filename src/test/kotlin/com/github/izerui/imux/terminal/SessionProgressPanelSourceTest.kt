package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProgressPanelSourceTest {
    @Test
    fun `进度区显示任务文字但不显示上报时间`() {
        val code = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/SessionProgressPanel.kt").normalized

        assertTrue(code.contains("steps.text = progress?.message.orEmpty()"))
        assertFalse(code.contains("reportedAt"))
        assertFalse(code.contains("session.info.updated"))
    }
}
