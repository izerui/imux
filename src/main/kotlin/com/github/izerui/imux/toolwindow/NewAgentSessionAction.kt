package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.terminal.startNewSession
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * 全局「新建 AI 会话」：不用先切到 imux 工具窗口点「+」，在 IDE 任何地方双击 ⌘（macOS）
 * 或双击 Alt（Windows / Linux），就在窗口正中弹出与「+」相同的智能体选择菜单。
 *
 * 快捷键在 plugin.xml 里用平台的 `keyboard-gesture-shortcut` 声明，与双击 Shift 打开
 * Search Everywhere 同一机制：由 IDE 事件队列在按键送达焦点组件之前识别，所以焦点在会话终端里也生效。
 * 用户可在 Keymap 里改。
 */
class NewAgentSessionAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.text = ImuxBundle.message("action.new.agent.session.text")
        event.presentation.description = ImuxBundle.message("action.new.agent.session.description")
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        NewSessionPopup.showCentered(project, event.dataContext) { agentType -> startNewSession(project, agentType) }
    }
}
