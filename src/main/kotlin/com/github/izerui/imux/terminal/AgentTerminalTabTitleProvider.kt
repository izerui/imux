package com.github.izerui.imux.terminal

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class AgentTerminalTabTitleProvider : EditorTabTitleProvider {

    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? =
        (file as? AgentTerminalVirtualFile)?.tabTitle
}
