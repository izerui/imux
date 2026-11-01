package com.github.izerui.imux.peer.mcp

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.terminal.TerminalHost
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** 按终端发放 `imux_session` 凭证；凭证只认启动它的那个标签页，终端关闭时撤销。 */
@Service(Service.Level.APP)
class PeerMcpService(val scope: CoroutineScope) : Disposable {
    private val protocols = ConcurrentHashMap<String, PeerMcpProtocol>()

    fun register(project: Project, tabId: String, agentType: AgentType): String {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val tools = PeerSessionTools(
            agentType,
            sessionKey = { if (project.isDisposed) null else TerminalHost.getInstance(project).openTabsByTabId()[tabId] },
            coordinator = { if (project.isDisposed) null else SessionMonitor.getInstance(project).peerCoordinator },
            onSwitched = { partner -> notifySwitched(project, tabId, partner) },
        )
        protocols[token] = PeerMcpProtocol(object : PeerToolsBackend {
            override suspend fun status() = withContext(Dispatchers.EDT) { tools.status() }
            override suspend fun enable(agent: String?) = withContext(Dispatchers.EDT) { tools.enable(agent) }
            override suspend fun disable() = withContext(Dispatchers.EDT) { tools.disable() }
        }, scope)
        return token
    }

    fun protocol(token: String): PeerMcpProtocol? = protocols[token]
    fun revoke(token: String) { protocols.remove(token) }

    /** 智能体自己开关了结对，留一条用户看得见的记录。 */
    private fun notifySwitched(project: Project, tabId: String, partner: AgentType?) {
        val title = TerminalHost.getInstance(project).tabTitleOf(tabId) ?: tabId
        val content = if (partner == null) ImuxBundle.message("action.peer.mcp.disabled", title)
        else ImuxBundle.message("action.peer.mcp.enabled", title, partner.displayName)
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(ImuxBundle.message("action.peer.group.text"), content, NotificationType.INFORMATION)
            .notify(project)
    }

    override fun dispose() { protocols.clear() }

    companion object {
        private const val NOTIFICATION_GROUP = "imux.turnCompleted"
        fun getInstance(): PeerMcpService = ApplicationManager.getApplication().getService(PeerMcpService::class.java)
    }
}
