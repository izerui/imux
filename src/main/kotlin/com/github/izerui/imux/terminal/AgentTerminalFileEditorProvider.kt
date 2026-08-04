package com.github.izerui.imux.terminal

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class AgentTerminalFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        file is AgentTerminalVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        AgentTerminalFileEditor(project, file as AgentTerminalVirtualFile)

    override fun getEditorTypeId(): String = "imux-terminal"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
