package com.github.izerui.imux.terminal.report

import com.github.izerui.imux.ImuxBundle
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.PluginId
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.GridBagLayout
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** 轨迹弹窗里显示报告页的视图。 */
internal interface ReportView : Disposable {
    val component: JComponent

    /** 加载或重新加载报告文件。 */
    fun show(file: Path)

    /** 页面深浅色跟随 IDE 主题。 */
    fun setDark(dark: Boolean)
}

internal object ReportViews {
    /** 2026.2 里 JCEF 是可被禁用的内置插件；imux 对它是可选依赖。 */
    private val JCEF_PLUGIN = PluginId.getId("com.intellij.modules.jcef")

    /**
     * JCEF 插件已启用且当前环境支持时返回内嵌浏览器视图，否则返回只提示「在系统浏览器中打开」的退路视图。
     * 先确认插件启用才去碰 [JcefReportView]：插件被禁用时 JCEF 的类不在 imux 的类加载器里。
     * [onOpenReport] 是「在系统浏览器中打开报告」：由弹窗先补写报告再打开；[openExternal] 只给页面里点到的其他文件用。
     */
    fun create(onEscape: () -> Unit, onOpenReport: () -> Unit, openExternal: (Path) -> Unit): ReportView {
        val jcefEnabled = PluginManagerCore.isPluginInstalled(JCEF_PLUGIN) && !PluginManagerCore.isDisabled(JCEF_PLUGIN)
        if (jcefEnabled) JcefReportView.createIfSupported(onEscape, openExternal)?.let { return it }
        return FallbackReportView(onOpenReport)
    }
}

/**
 * 无法内嵌浏览器时的退路：一句说明加「在系统浏览器中打开」。报告文件照常生成。
 * 链接不记路径、不自己打开：文件可能已被删掉，交给弹窗先补写再打开（与标题栏按钮同一入口）。
 */
private class FallbackReportView(private val onOpenReport: () -> Unit) : ReportView {
    override val component: JComponent =
        JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(16)
            add(JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JBLabel(ImuxBundle.message("action.overview.report.noBrowser"), SwingConstants.CENTER).apply { alignmentX = 0.5f })
                add(ActionLink(ImuxBundle.message("action.overview.report.openInBrowser")) { onOpenReport() }.apply {
                    alignmentX = 0.5f
                    isFocusable = false
                })
            })
        }

    override fun show(file: Path) = Unit

    override fun setDark(dark: Boolean) = Unit

    override fun dispose() = Unit
}
