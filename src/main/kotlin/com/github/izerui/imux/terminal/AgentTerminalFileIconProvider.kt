package com.github.izerui.imux.terminal

import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.intellij.icons.AllIcons
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.AnimatedIcon
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

/**
 * 状态图标**取代**品牌图标，而不是并排挂在它左边。
 *
 * 并排的话标签页会在忙碌时变宽、闲下来又缩回去，标题跟着左右跳。而这三种状态本就
 * 互斥且短暂：在跑就是在跑，跑完没看就是有新东西，看过了才轮到品牌图标。
 *
 * 忙碌优先于未读——还在跑，就谈不上「读完了」。
 */
internal fun terminalTabIcon(agentType: AgentType, running: Boolean, unread: Boolean): Icon = when {
    running -> AnimatedIcon.Default.INSTANCE
    unread -> AllIcons.General.Modified
    else -> AgentIcons.forAgent(agentType)
}
