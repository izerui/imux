package com.github.izerui.imux.terminal

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JPanel

/** 终端顶部横幅各段共用的颜色：段间分隔线与状态圆点。 */
object BannerColors {
    val ONLINE = JBColor(0x369650, 0x5FB865)
    val PROBLEM = JBColor(0xC27D04, 0xE0B153)
    val DIVIDER = JBColor(0xA8BFE8, 0x35538F)
}

/** 横幅里段与段之间的细竖线。 */
class BannerDivider : JPanel() {
    init {
        isOpaque = true
        background = BannerColors.DIVIDER
    }

    override fun getPreferredSize() = Dimension(JBUI.scale(1), JBUI.scale(14))

    override fun getMinimumSize() = preferredSize

    override fun getMaximumSize() = preferredSize
}

/** 段内状态圆点：绿色表示在进行，黄色表示出了问题。 */
class StatusDotIcon(var color: JBColor = BannerColors.ONLINE) : Icon {
    override fun paintIcon(
        c: Component?,
        g: Graphics,
        x: Int,
        y: Int,
    ) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            val size = JBUI.scale(7)
            g2.fillOval(x + (iconWidth - size) / 2, y + (iconHeight - size) / 2, size, size)
        } finally {
            g2.dispose()
        }
    }

    override fun getIconWidth() = JBUI.scale(12)

    override fun getIconHeight() = JBUI.scale(16)
}
