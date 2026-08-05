package com.github.izerui.imux.terminal

import com.github.izerui.imux.icons.AgentIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

class AgentTerminalFileIconProvider : FileIconProvider {

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        val terminalFile = file as? AgentTerminalVirtualFile ?: return null
        return AgentIcons.forAgent(terminalFile.agentType)
    }
}
