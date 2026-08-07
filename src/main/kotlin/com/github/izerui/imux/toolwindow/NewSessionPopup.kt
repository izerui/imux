package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.awt.Component

/**
 * 「新建会话」的 Agent 选择弹窗。
 *
 * 用平台标准的 action group 菜单：单行「图标 + 名称」，行高、内边距、
 * 选中态都跟 IDE 其它工具栏菜单一致，不再自绘卡片。
 */
internal object NewSessionPopup {

    /** [anchor] 为 null 时（例如键盘触发，拿不到按钮组件）交给平台自己找位置。 */
    fun show(anchor: Component?, dataContext: DataContext, onChosen: (AgentType) -> Unit) {
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            null,
            agentActionGroup(onChosen),
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )

        if (anchor != null) {
            popup.showUnderneathOf(anchor)
        } else {
            popup.showInBestPositionFor(dataContext)
        }
    }
}

/** 每个 Agent 一项：标题是显示名，描述用厂商名，走状态栏提示而不占菜单行。 */
internal fun agentActionGroup(onChosen: (AgentType) -> Unit): DefaultActionGroup =
    DefaultActionGroup(
        AgentType.entries.map { agentType ->
            object : DumbAwareAction(
                agentType.displayName,
                agentType.vendor,
                AgentIcons.forAgent(agentType),
            ) {
                override fun actionPerformed(event: AnActionEvent) = onChosen(agentType)
            }
        },
    )
