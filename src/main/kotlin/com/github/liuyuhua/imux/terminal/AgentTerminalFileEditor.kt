package com.github.liuyuhua.imux.terminal

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * 把终端 view 的组件挂到 editor tab 上。
 *
 * 关键：dispose 时**不**取消 view 的 CoroutineScope —— view 归 TerminalHost 所有。
 * 关闭标签页只是取消挂载，进程继续运行，再次点击会话可重新挂回。
 *
 * 这正是不复用 JetBrains 的 TerminalViewFileEditor 的原因：
 * 它的 dispose() 会执行 cancel(terminalView.coroutineScope)，关一次标签页会话即中断。
 */
class AgentTerminalFileEditor(
    private val virtualFile: AgentTerminalVirtualFile,
) : UserDataHolderBase(), FileEditor {

    override fun getComponent(): JComponent = virtualFile.terminalView.component

    override fun getPreferredFocusedComponent(): JComponent =
        virtualFile.terminalView.preferredFocusableComponent

    override fun getName(): String = virtualFile.name

    override fun getFile(): VirtualFile = virtualFile

    override fun setState(state: FileEditorState) = Unit

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        // 刻意留空：view 的 CoroutineScope 由 TerminalHost 负责取消。
        // 切勿在此调用 cancel(virtualFile.terminalView.coroutineScope)，
        // 那样会复刻 TerminalViewFileEditor 的行为，关标签页即杀进程。
    }
}
