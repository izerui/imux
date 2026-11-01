package com.github.izerui.imux.peer.mcp

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.TerminalLaunchContribution
import com.github.izerui.imux.terminal.TerminalLaunchContributor
import com.github.izerui.imux.terminal.TerminalMcpServer
import com.github.izerui.imux.terminal.piPluginMcpScript
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.jetbrains.ide.BuiltInServerManager

class PeerLaunchContributor : TerminalLaunchContributor {
    override suspend fun prepare(project: Project, tabId: String, agentType: AgentType): TerminalLaunchContribution {
        val port = runInterruptible(Dispatchers.IO) { BuiltInServerManager.getInstance().waitForStart().port }
        check(port > 0)
        val script = if (agentType == AgentType.PI) checkNotNull(piPluginMcpScript()) { "session MCP extension unavailable" } else null
        val service = PeerMcpService.getInstance()
        val token = service.register(project, tabId, agentType)
        val url = "http://127.0.0.1:$port$SESSION_MCP_PATH"
        return TerminalLaunchContribution(
            listOf(TerminalMcpServer("imux_session", url, "IMUX_SESSION_TOKEN")),
            mapOf("IMUX_SESSION_URL" to url, "IMUX_SESSION_TOKEN" to token),
            listOfNotNull(script),
        ) { service.revoke(token) }
    }
}
