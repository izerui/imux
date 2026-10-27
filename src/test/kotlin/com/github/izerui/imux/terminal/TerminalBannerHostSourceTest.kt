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

    @Test
    fun `会话轨迹是横幅上的一段，右上角不再有折叠图标`() {
        assertTrue(editor.contains("peerBanner.add(trailOverlay.bannerSegment)"))
        assertTrue(!editor.contains("trailOverlay.chip"))
        assertTrue("轨迹段不挂点外面收起", !editor.contains("trailOutsideClicks.attach(trailOverlay.bannerSegment)"))
        val overlay = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/SessionTrailOverlay.kt").normalized
        assertTrue(overlay.contains("setExpanded(!virtualFile.trailExpanded)"))
    }
}
