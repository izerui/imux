package com.github.izerui.imux.terminal

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * 把终端 view 的组件挂到 editor tab 上，并在标签页关闭时结束会话。
 */
class AgentTerminalFileEditor(
    private val project: Project,
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
        // 拖动标签页、分屏等操作会先销毁再重建编辑器，平台用这个标记区分。
        // 漏判会导致拖一下标签页就把会话杀了。
        if (virtualFile.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == true) return

        TerminalHost.getInstance(project).closeSession(virtualFile.sessionKey)
    }
}
