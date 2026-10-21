package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.overview.ProgressOverviewService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.terminal.frontend.view.TerminalView

/** 切换当前标签页轨迹面板的展开状态，不触发生成。默认不绑定快捷键，用户可在 Keymap 中自行设置。 */
class ToggleSessionTrailAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.text = ImuxBundle.message("action.overview.toggle.text")
        event.presentation.description = ImuxBundle.message("action.overview.toggle.description")
        event.presentation.isEnabledAndVisible = trailSessionFile(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val file = trailSessionFile(event) ?: return
        val project = event.project ?: return
        file.trailExpanded = !file.trailExpanded
        ProgressOverviewService.getInstance(project).trailStore.notifyChanged(file.sessionKey)
    }
}

private fun trailSessionFile(event: AnActionEvent): AgentTerminalVirtualFile? {
    val project = event.project ?: return null
    event.getData(TerminalView.DATA_KEY)?.let { return TerminalHost.getInstance(project).fileFor(it) }
    // 快捷键在面板卡片上触发时，焦点不在终端里，从当前编辑器取会话
    return event.getData(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE) as? AgentTerminalVirtualFile
}
