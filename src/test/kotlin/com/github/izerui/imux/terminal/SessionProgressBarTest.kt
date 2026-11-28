package com.github.izerui.imux.terminal

import com.intellij.util.ui.JBUI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import javax.swing.SwingUtilities

class SessionProgressBarTest {
    @Test
    fun `确定进度按比例填满背景高度并随宽度变化`() {
        SwingUtilities.invokeAndWait {
            val bar = sessionProgressBar()
            bar.value = 40
            fun render(width: Int): java.awt.image.BufferedImage {
                bar.setSize(width, 32)
                val image = java.awt.image.BufferedImage(width, 32, java.awt.image.BufferedImage.TYPE_INT_RGB)
                image.createGraphics().let { g -> try { bar.paint(g) } finally { g.dispose() } }
                return image
            }
            val image = render(200)
            val fill = image.getRGB(20, 16)
            val track = image.getRGB(160, 16)
            assertNotEquals(fill, track)
            assertEquals(fill, image.getRGB(79, 1))
            assertEquals(fill, image.getRGB(79, 30))
            assertEquals(track, image.getRGB(80, 16))
            val resized = render(400)
            assertEquals(fill, resized.getRGB(159, 16))
            assertEquals(track, resized.getRGB(160, 16))
            bar.value = 0
            assertEquals(track, render(200).getRGB(1, 16))
            bar.value = 100
            assertEquals(fill, render(200).getRGB(199, 16))
        }
    }

    @Test
    fun `未知进度动画从轨道色过渡到绿色`() {
        SwingUtilities.invokeAndWait {
            val bar = sessionProgressBar()
            assertEquals(JBUI.CurrentTheme.Banner.SUCCESS_BACKGROUND, bar.getClientProperty("ProgressBar.indeterminateStartColor"))
            assertNotEquals(bar.getClientProperty("ProgressBar.indeterminateStartColor"),
                bar.getClientProperty("ProgressBar.indeterminateEndColor"))
        }
    }
}
