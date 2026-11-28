package com.github.izerui.imux.peer.mcp

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.TerminalLaunchContribution
import com.github.izerui.imux.terminal.NamedLaunchContributor
import com.github.izerui.imux.terminal.TerminalMcpServer
import com.github.izerui.imux.terminal.piPluginMcpScript
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.jetbrains.ide.BuiltInServerManager

class PeerLaunchContributor : NamedLaunchContributor {
    override val toolLabelKey = "terminal.tools.peer"
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
            claudeSettings = if (agentType == AgentType.CLAUDE) progressHookSettings(url) else null,
            guidance = setOf(com.github.izerui.imux.settings.GuidanceCapability.TURN_PROGRESS),
        ) { service.revoke(token) }
    }
}

internal fun progressHookSettings(url: String): String {
    val hooks = com.google.gson.JsonObject()
    for (event in listOf("UserPromptSubmit", "Stop", "StopFailure")) {
        val hook = com.google.gson.JsonObject().apply {
            addProperty("type", "http")
            addProperty("url", "$url?progress-hook")
            addProperty("timeout", 1)
            add("headers", com.google.gson.JsonObject().apply {
                addProperty("Authorization", "Bearer \$IMUX_SESSION_TOKEN")
                addProperty("Accept", "application/json, text/event-stream")
            })
            add("allowedEnvVars", com.google.gson.JsonArray().apply { add("IMUX_SESSION_TOKEN") })
        }
        hooks.add(event, com.google.gson.JsonArray().apply {
            add(com.google.gson.JsonObject().apply {
                add("hooks", com.google.gson.JsonArray().apply { add(hook) })
            })
        })
    }
    return com.google.gson.JsonObject().apply { add("hooks", hooks) }.toString()
}
