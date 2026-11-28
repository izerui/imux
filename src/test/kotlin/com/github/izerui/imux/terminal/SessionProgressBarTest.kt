package com.github.izerui.imux.terminal

import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.JBUI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import javax.swing.SwingUtilities

class SessionProgressBarTest {
    @Test
    fun `进度线在浅色和深色主题下分别使用蓝色`() {
        SwingUtilities.invokeAndWait {
            val wasDark = !com.intellij.ui.JBColor.isBright()
            val bar = sessionProgressBar()
            bar.value = 50
            bar.setSize(200, 32)
            fun renderedColor(dark: Boolean): Int {
                com.intellij.ui.JBColor.setDark(dark)
                val image = java.awt.image.BufferedImage(200, 32, java.awt.image.BufferedImage.TYPE_INT_RGB)
                image.createGraphics().let { g -> try { bar.paint(g) } finally { g.dispose() } }
                return image.getRGB(20, 31) and 0xFFFFFF
            }
            try {
                assertEquals("浅色主题蓝色", 0x3574F0, renderedColor(false))
                assertEquals("深色主题蓝色", 0x548AF7, renderedColor(true))
            } finally {
                com.intellij.ui.JBColor.setDark(wasDark)
            }
        }
    }

    @Test
    fun `确定进度只在底部画细线并随宽度变化`() {
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
            val fill = image.getRGB(20, 31)
            val track = UIUtil.getPanelBackground().rgb
            assertNotEquals(fill, track)
            val lineTop = 32 - JBUI.scale(2)
            for (y in 0 until lineTop) {
                for (x in 0 until image.width) {
                    assertEquals("文字区域背景 ($x, $y)", track, image.getRGB(x, y))
                }
            }
            assertEquals(fill, image.getRGB(79, lineTop))
            assertEquals(fill, image.getRGB(79, 31))
            assertEquals(track, image.getRGB(80, 31))
            val resized = render(400)
            assertEquals(fill, resized.getRGB(159, 31))
            assertEquals(track, resized.getRGB(160, 31))
            bar.value = 0
            assertEquals(track, render(200).getRGB(1, 31))
            bar.value = 100
            assertEquals(fill, render(200).getRGB(199, 31))
        }
    }

    @Test
    fun `未知进度清除旧细线并在再次上报后恢复细线`() {
        SwingUtilities.invokeAndWait {
            val bar = sessionProgressBar()
            bar.value = 100
            bar.setSize(200, 32)
            val image = java.awt.image.BufferedImage(200, 32, java.awt.image.BufferedImage.TYPE_INT_RGB)
            fun paint() {
                image.createGraphics().let { g -> try { bar.paint(g) } finally { g.dispose() } }
            }
            paint()
            val fill = image.getRGB(100, 31)
            val track = UIUtil.getPanelBackground().rgb
            assertNotEquals(track, fill)
            bar.isIndeterminate = true
            try {
                paint()
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        assertEquals("未知进度背景 ($x, $y)", track, image.getRGB(x, y))
                    }
                }
            } finally {
                bar.isIndeterminate = false
            }
            bar.value = 40
            paint()
            assertEquals(fill, image.getRGB(79, 31))
            assertEquals(track, image.getRGB(80, 31))
        }
    }
}
