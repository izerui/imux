package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBannerHostSourceTest {
    private val editor = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt").normalized

    @Test
    fun `终端顶部同时容纳原有横幅与装饰横幅`() {
        assertTrue(editor.contains("SessionDecorations.banners(project, virtualFile, this@AgentTerminalFileEditor)"))
        assertTrue(editor.contains("add(peerBanner)"))
        assertTrue(editor.contains("BorderLayout.NORTH"))
    }
}
