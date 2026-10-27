package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBannerHostSourceTest {
    private val editor = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt").normalized

    @Test
    fun `终端顶部只有一条横幅，装饰段接在原有横幅右端`() {
        assertTrue(editor.contains("SessionDecorations.bannerSegments(project, virtualFile, this@AgentTerminalFileEditor)"))
        assertTrue(editor.contains("peerBanner.add(segment)"))
        assertTrue(editor.contains("add(peerBanner, BorderLayout.NORTH)"))
        assertTrue(!editor.contains("BoxLayout.Y_AXIS"))
    }
}
