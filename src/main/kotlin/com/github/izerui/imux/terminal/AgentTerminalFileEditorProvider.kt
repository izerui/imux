package com.github.izerui.imux.terminal

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * 为 [AgentTerminalVirtualFile] 注册唯一的编辑器实现。
 *
 * 虚拟文件本身没有可编辑文档，因此隐藏默认编辑器；终端视图是唯一有意义的内容。
 * 焦点转发与销毁语义由 [AgentTerminalFileEditor] 负责。
 */
class AgentTerminalFileEditorProvider :
    FileEditorProvider,
    DumbAware {
    override fun accept(
        project: Project,
        file: VirtualFile,
    ): Boolean = file is AgentTerminalVirtualFile

    override fun createEditor(
        project: Project,
        file: VirtualFile,
    ): FileEditor = AgentTerminalFileEditor(project, file as AgentTerminalVirtualFile)

    override fun getEditorTypeId(): String = "imux-terminal"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
