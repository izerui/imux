package com.github.izerui.imux.bridge.mcp

import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.TerminalLaunchContribution
import com.github.izerui.imux.terminal.TerminalLaunchContributor
import com.github.izerui.imux.terminal.TerminalMcpServer
import com.github.izerui.imux.terminal.claudeHookSettings
import com.github.izerui.imux.terminal.piPluginMcpScript
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.jetbrains.ide.BuiltInServerManager

class MobileLaunchContributor : TerminalLaunchContributor {
    override suspend fun prepare(project: Project, tabId: String, agentType: AgentType): TerminalLaunchContribution {
        val port = runInterruptible(Dispatchers.IO) { BuiltInServerManager.getInstance().waitForStart().port }
        check(port > 0)
        val script = if (agentType == AgentType.PI) checkNotNull(piPluginMcpScript()) { "mobile MCP extension unavailable" } else null
        val service = MobileMcpService.getInstance()
        val token = service.register(project.basePath ?: System.getProperty("user.home"), tabId)
        val url = "http://127.0.0.1:$port$MOBILE_MCP_PATH"
        // Claude 经 --settings 注入 PermissionRequest 等 hook，令牌同样从环境变量带进请求头
        val hooked = agentType == AgentType.CLAUDE
        val router = BridgeHub.getInstance().router
        if (hooked) router.hookAttached(tabId, true)
        return TerminalLaunchContribution(
            listOf(TerminalMcpServer("imux_mobile", url, "IMUX_MOBILE_TOKEN")),
            mapOf("IMUX_MOBILE_URL" to url, "IMUX_MOBILE_TOKEN" to token),
            listOfNotNull(script),
            if (hooked) claudeHookSettings("http://127.0.0.1:$port$CLAUDE_HOOK_PATH", "IMUX_MOBILE_TOKEN") else null,
        ) {
            if (hooked) router.hookAttached(tabId, false)
            service.revoke(token)
        }
    }
}
