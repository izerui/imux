package com.github.izerui.imux.terminal

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionWrapper
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.DynamicActionConfigurationCustomizer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.ui.tabs.JBTabs
import javax.swing.SwingUtilities

/**
 * 会话标签上不提供「Split and Move Right/Down」：会话窗口不支持分屏。
 *
 * 会话文件带 FORBID_TAB_SPLIT（见 [AgentTerminalVirtualFile]），但平台只拿它挡复制式分屏，
 * 移动式分屏照样出现在标签右键菜单里。这里把这两个平台动作换成包一层的版本：
 * 目标是会话标签时隐藏、按快捷键也不执行，其他文件原样交给平台动作。插件卸载时换回原动作。
 */
class SessionSplitMoveBlocker : DynamicActionConfigurationCustomizer {
    override fun registerActions(actionManager: ActionManager) {
        MOVE_ACTION_IDS.forEach { id ->
            val original = actionManager.getAction(id) ?: return@forEach
            if (original !is SessionAware) actionManager.replaceAction(id, SessionAware(original))
        }
    }

    override fun unregisterActions(actionManager: ActionManager) {
        MOVE_ACTION_IDS.forEach { id ->
            (actionManager.getAction(id) as? SessionAware)?.let { actionManager.replaceAction(id, it.delegate) }
        }
    }

    private class SessionAware(original: AnAction) : AnActionWrapper(original) {
        override fun update(e: AnActionEvent) {
            super.update(e)
            if (targetsSession(e)) e.presentation.isEnabledAndVisible = false
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (!targetsSession(e)) super.actionPerformed(e)
        }
    }

    internal companion object {
        // 平台的 MoveTabRightAction / MoveTabDownAction，菜单文字是 Split and Move Right/Down
        val MOVE_ACTION_IDS = setOf("MoveTabRight", "MoveTabDown")

        /**
         * 动作作用的标签是不是会话。右键时是被点的那个标签，不一定是选中的：
         * 从被点的组件找到所在标签栏，getTargetInfo 在右键时给被点的标签、平时给选中的标签。
         * 拿不到标签栏（如从 Find Action 调起）时退回当前选中的文件。
         */
        fun targetsSession(e: AnActionEvent): Boolean {
            val project = e.project ?: return false
            val clicked = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)
            val tabs = generateSequence(clicked) { it.parent }.filterIsInstance<JBTabs>().firstOrNull()
                ?: return e.getData(CommonDataKeys.VIRTUAL_FILE) is AgentTerminalVirtualFile
            val target = tabs.targetInfo?.component ?: return false
            return FileEditorManager.getInstance(project).allEditors.any {
                it is AgentTerminalFileEditor && SwingUtilities.isDescendingFrom(it.component, target)
            }
        }
    }
}
