package com.github.izerui.imux.terminal

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Font
import java.awt.Point
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/** 悬停说明的弹窗本身；生产用平台弹窗（[platformTipWindow]），测试里换成计数的假实现。 */
internal interface TipWindow {
    val isVisible: Boolean

    fun show(content: JComponent, owner: JComponent)

    /** 内容变了：按新内容调整大小，弹窗不关闭、不重建。 */
    fun repack()

    fun close()
}

internal fun platformTipWindow(): TipWindow =
    object : TipWindow {
        private var popup: JBPopup? = null

        override val isVisible get() = popup?.isVisible == true

        override fun show(content: JComponent, owner: JComponent) {
            val created =
                JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(content, null)
                    .setRequestFocus(false)
                    .setFocusable(false)
                    .setResizable(false)
                    .setMovable(false)
                    .setCancelOnClickOutside(true)
                    .setCancelKeyEnabled(true)
                    .createPopup()
            popup = created
            created.show(RelativePoint(owner, Point(0, owner.height + JBUI.scale(4))))
        }

        override fun repack() {
            popup?.takeIf { it.isVisible }?.pack(true, true)
        }

        override fun close() {
            popup?.cancel()
            popup = null
        }
    }

/**
 * 浮动图标组按钮的悬停说明：标题加一段说明，外观取平台提示气泡的主题色与边距。
 *
 * 不用平台的 HelpTooltip：它的说明是安装时定死的字符串，内容一变只能卸载重装，正在看的气泡会被关掉；
 * 能更新内容的入口（createTipPanel、initPopupBuilder）又都是内部 API。审查进行时说明里的工具调用和秒数每秒都在变，
 * 所以这里自己持有弹窗：显示期间只改两个标签的文字并调整大小，弹窗实例保持不变，鼠标不动也一直看得到实时进度。
 */
internal class ToolHoverTip(
    private val owner: JComponent,
    private val window: TipWindow = platformTipWindow(),
) {
    private val titleLabel =
        JBLabel().apply {
            font = JBUI.CurrentTheme.Tooltip.font().deriveFont(Font.BOLD)
            foreground = JBUI.CurrentTheme.Tooltip.foreground()
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
    private val descriptionLabel =
        JBLabel().apply {
            font = JBUI.CurrentTheme.Tooltip.font()
            foreground = JBUI.CurrentTheme.Tooltip.foreground()
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
    private val content: JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = JBUI.CurrentTheme.Tooltip.background()
            border = JBUI.Borders.empty(JBUI.CurrentTheme.HelpTooltip.defaultTextBorderInsets())
            add(titleLabel)
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))
            add(descriptionLabel)
        }
    private var title = ""
    private var description = ""
    private val showTimer = Timer(INITIAL_DELAY_MS) { showNow() }.apply { isRepeats = false }

    val isShowing get() = window.isVisible

    /** 状态变化时调用；正在显示时原地刷新。 */
    fun setContent(title: String, description: String) {
        if (title == this.title && description == this.description) return
        this.title = title
        this.description = description
        titleLabel.text = title
        titleLabel.isVisible = title.isNotEmpty()
        descriptionLabel.text = wrapped(description)
        content.revalidate()
        if (window.isVisible) window.repack()
    }

    fun hoverStarted() {
        if (!window.isVisible) showTimer.restart()
    }

    fun hoverEnded() = hide()

    fun hide() {
        showTimer.stop()
        if (window.isVisible) window.close()
    }

    internal fun showNow() {
        if (window.isVisible || (title.isEmpty() && description.isEmpty())) return
        window.show(content, owner)
    }

    /** 短说明一行放下；长的（审查进度、失败原因）按最大宽度折行。 */
    private fun wrapped(text: String): String {
        val maxWidth = JBUI.scale(MAX_WIDTH)
        val escaped = StringUtil.escapeXmlEntities(text)
        return if (descriptionLabel.getFontMetrics(descriptionLabel.font).stringWidth(text) <= maxWidth) {
            "<html>$escaped</html>"
        } else {
            "<html><div style='width:${maxWidth}px'>$escaped</div></html>"
        }
    }

    private companion object {
        const val INITIAL_DELAY_MS = 600
        const val MAX_WIDTH = 320
    }
}
