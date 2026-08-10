package com.github.izerui.imux.terminal

import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
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
 * 状态**修饰**品牌图标，而不是取代它：忙碌时品牌图标原地转圈，未读时左边一格亮起标记。
 *
 * 取代的话标签页一忙起来就只剩一个平台菊花，既认不出是 Claude 还是 Codex 的会话，
 * 也认不出它和旁边普通编辑器标签的区别。四种组合的图标一样宽，标题不随状态左右跳。
 *
 * 忙碌与未读可以同时成立：跑起来之前留下的新结果还没看，两个标记就都该在。
 */
internal fun terminalTabIcon(agentType: AgentType, running: Boolean, unread: Boolean): Icon =
    AgentIcons.forTab(agentType, running, unread)
