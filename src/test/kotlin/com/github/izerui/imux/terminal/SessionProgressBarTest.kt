package com.github.izerui.imux.terminal

import com.intellij.util.ui.JBUI
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.swing.SwingUtilities

class SessionProgressBarTest {
    @Test
    fun `确定进度使用主题强调色`() {
        SwingUtilities.invokeAndWait {
            val bar = sessionProgressBar()
            assertEquals(JBUI.CurrentTheme.Link.Foreground.ENABLED, bar.getClientProperty("ProgressBar.progressPaint"))
        }
    }

    @Test
    fun `未知进度动画从轨道色过渡到强调色`() {
        SwingUtilities.invokeAndWait {
            val bar = sessionProgressBar()
            assertEquals(JBUI.CurrentTheme.ProgressBar.TRACK, bar.getClientProperty("ProgressBar.indeterminateStartColor"))
            assertEquals(JBUI.CurrentTheme.Link.Foreground.ENABLED, bar.getClientProperty("ProgressBar.indeterminateEndColor"))
        }
    }

    @Test
    fun `原生绘制厚度与组件高度一致`() {
        SwingUtilities.invokeAndWait {
            val bar = sessionProgressBar()
            assertEquals(2, bar.getClientProperty("ProgressBar.stripeWidth"))
            assertEquals(JBUI.scale(2), bar.preferredSize.height)
        }
    }
}
