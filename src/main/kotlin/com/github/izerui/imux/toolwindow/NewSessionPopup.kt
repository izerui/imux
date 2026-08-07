package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridLayout
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * 「新建会话」的 Agent 选择弹窗。
 *
 * 不用 `createActionGroupPopup`：平台菜单项是单行文字、行高由菜单规格定死，
 * 两个选项挤成一个小方块，而新建是高频操作，每次都要瞄准。
 * 这里自绘列表，每项两行（显示名 + 厂商名），点击面积大得多。
 */
internal object NewSessionPopup {

    /** [anchor] 为 null 时（例如键盘触发，拿不到按钮组件）交给平台自己找位置。 */
    fun show(anchor: Component?, dataContext: DataContext, onChosen: (AgentType) -> Unit) {
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(AgentType.entries.toList())
            .setTitle("选择 Agent")
            .setRenderer(AgentRenderer())
            .setItemChosenCallback(onChosen)
            .setRequestFocus(true)
            .createPopup()

        if (anchor != null) {
            popup.showUnderneathOf(anchor)
        } else {
            popup.showInBestPositionFor(dataContext)
        }
    }
}

/**
 * 每项渲染成一张卡片：左侧品牌标志，右侧上行显示名、下行厂商名。
 *
 * 图标保持 16px 不放大——资源只有 16/32 两档，放大到卡片高度会糊。
 * 「大」体现在行高与内边距上，不靠图标撑。
 */
private class AgentRenderer : ListCellRenderer<AgentType> {

    override fun getListCellRendererComponent(
        list: JList<out AgentType>,
        value: AgentType,
        index: Int,
        selected: Boolean,
        focused: Boolean,
    ): Component {
        val foreground = UIUtil.getListForeground(selected, true)

        val name = JBLabel(value.displayName).apply {
            this.foreground = foreground
        }
        val vendor = JBLabel(value.vendor).apply {
            font = JBUI.Fonts.smallFont()
            // 选中态下灰色在高亮底上读不清，跟着主文字走；
            // 未选中时用与会话列表「多久以前」同一个灰，两处观感一致
            this.foreground =
                if (selected) foreground else SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
        }

        val text = JPanel(GridLayout(2, 1, 0, JBUI.scale(2))).apply {
            isOpaque = false
            add(name)
            add(vendor)
        }

        return JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            isOpaque = true
            background = UIUtil.getListBackground(selected, true)
            border = JBUI.Borders.empty(8, 12)
            add(JBLabel(AgentIcons.forAgent(value)), BorderLayout.WEST)
            add(text, BorderLayout.CENTER)
        }
    }
}
