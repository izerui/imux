package com.github.izerui.imux.terminal

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.impl.EditorWindow

/**
 * 让标签右键的「Split and Move Right/Down」把会话当成移动，而不是关闭。
 *
 * 会话文件带 FORBID_TAB_SPLIT（见 [AgentTerminalVirtualFile]），但平台只拿它挡复制式分屏；
 * 移动式分屏照样可用，执行时先 closeFile 再到新分屏重开。这次关闭不带 CLOSING_TO_REOPEN，
 * 编辑器 dispose 会当成真关闭而结束 CLI，新分屏里只剩一个已终止的会话。
 * 补上这个标记后就和拖到边缘走同一条路：编辑器早退，终端由新编辑器沿用。
 */
class AgentSplitAndMoveGuard : AnActionListener {
    // 执行完标签已移走，再从事件取会拿到别的文件；不清掉标记，之后真关闭会被当成重开而留下 CLI
    private var moving: AgentTerminalVirtualFile? = null

    override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
        if (ActionManager.getInstance().getId(action) !in MOVE_ACTION_IDS) return
        // 与平台动作取同一个文件：右键哪个标签就是哪个，不一定是当前选中的
        val file = event.getData(EditorWindow.DATA_KEY)?.getContextFile() as? AgentTerminalVirtualFile ?: return
        file.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, true)
        moving = file
    }

    override fun afterActionPerformed(action: AnAction, event: AnActionEvent, result: AnActionResult) {
        moving?.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, null)
        moving = null
    }

    internal companion object {
        // 平台的 MoveTabRightAction / MoveTabDownAction，菜单文字是 Split and Move Right/Down
        val MOVE_ACTION_IDS = setOf("MoveTabRight", "MoveTabDown")
    }
}
