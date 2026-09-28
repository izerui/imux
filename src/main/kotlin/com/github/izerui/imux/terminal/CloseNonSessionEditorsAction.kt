package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.toolwindow.closeNonSessionEditors
import com.github.izerui.imux.toolwindow.hasClosableEditors
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.terminal.frontend.view.TerminalView

/**
 * 会话终端右键菜单里的「关闭非会话标签页」入口，行为与工具窗口标题栏按钮一致。
 *
 * 只在当前会话终端正文的右键菜单中出现。
 */
class CloseNonSessionEditorsAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.text = ImuxBundle.message("action.close.other.editors.text")
        event.presentation.description = ImuxBundle.message("action.close.other.editors.description")
        event.presentation.icon = AllIcons.General.CloseSmallHovered
        val project = event.project
        val terminalView = event.getData(TerminalView.DATA_KEY)
        if (project == null || terminalView == null ||
            TerminalHost.getInstance(project).sessionKeyFor(terminalView) == null
        ) {
            event.presentation.isEnabledAndVisible = false
            return
        }
        event.presentation.isVisible = true
        event.presentation.isEnabled = hasClosableEditors(
            FileEditorManager.getInstance(project).openFiles,
        ) { it is AgentTerminalVirtualFile }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val manager = FileEditorManager.getInstance(project)
        closeNonSessionEditors(
            manager.openFiles,
            { it is AgentTerminalVirtualFile },
        ) { manager.closeFile(it) }
    }
}
