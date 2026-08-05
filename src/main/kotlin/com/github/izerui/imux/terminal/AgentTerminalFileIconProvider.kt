package com.github.izerui.imux.terminal

import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.intellij.icons.AllIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.RowIcon
import javax.swing.Icon

class AgentTerminalFileIconProvider : FileIconProvider {

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        val terminalFile = file as? AgentTerminalVirtualFile ?: return null
        val monitor =
            if (project == null || project.isDisposed) null else SessionMonitor.getInstance(project)
        return terminalTabIcon(
            agentType = terminalFile.agentType,
            running = monitor?.runningIds?.contains(terminalFile.sessionKey) == true,
            unread = monitor?.isUnread(terminalFile.sessionKey) == true,
        )
    }
}

internal fun terminalTabIcon(agentType: AgentType, running: Boolean, unread: Boolean): Icon {
    val brandIcon = AgentIcons.forAgent(agentType)
    val statusIcon: Icon = when {
        running -> AnimatedIcon.Default.INSTANCE
        unread -> AllIcons.General.Modified
        else -> return brandIcon
    }
    return RowIcon(statusIcon, brandIcon)
}
