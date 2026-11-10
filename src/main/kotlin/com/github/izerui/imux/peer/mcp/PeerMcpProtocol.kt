package com.github.izerui.imux.peer.mcp

import com.github.izerui.imux.mcp.McpJsonRpc
import com.github.izerui.imux.mcp.McpJsonRpc.rpcError
import com.github.izerui.imux.mcp.McpJsonRpc.rpcResult
import com.github.izerui.imux.mcp.McpJsonRpc.toolResult
import com.github.izerui.imux.mcp.McpRpcReply
import com.github.izerui.imux.mcp.text
import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

interface PeerToolsBackend {
    suspend fun status(): PeerToolResult
    suspend fun enable(agent: String?): PeerToolResult
    suspend fun disable(): PeerToolResult
    suspend fun reviewOnce(agent: String?, instruction: String?): PeerToolResult
}

/** `imux_session` 服务：调用方终端自己这个会话的结对编程开关。 */
class PeerMcpProtocol(private val backend: PeerToolsBackend, private val scope: CoroutineScope) {
    fun receive(root: JsonObject): McpRpcReply = McpJsonRpc.receive(root, "imux_session", ::tools, call = ::call)

    private fun call(id: JsonElement, name: String, args: JsonObject): McpRpcReply {
        fun error(code: Int, text: String) = McpJsonRpc.reply(rpcError(id, code, text))
        if (name !in setOf(STATUS, ENABLE, DISABLE, REVIEW_ONCE)) return error(-32602, "Unknown tool")
        val allowed = when (name) {
            ENABLE -> setOf("agent")
            REVIEW_ONCE -> setOf("agent", "instruction")
            else -> emptySet()
        }
        if (args.keySet().any { it !in allowed }) return error(-32602, "Unsupported argument")
        if (args.has("agent") && args.text("agent") == null) return error(-32602, "agent must be a string")
        if (args.has("instruction") && args.text("instruction") == null) return error(-32602, "instruction must be a string")
        return McpRpcReply(200, scope.async {
            val result = when (name) {
                STATUS -> backend.status()
                ENABLE -> backend.enable(args.text("agent"))
                REVIEW_ONCE -> backend.reviewOnce(args.text("agent"), args.text("instruction"))
                else -> backend.disable()
            }
            rpcResult(id, toolResult(result.value, result.failed))
        })
    }

    companion object {
        private const val STATUS = "get_pair_status"
        private const val ENABLE = "enable_pair_programming"
        private const val DISABLE = "disable_pair_programming"
        private const val REVIEW_ONCE = "review_once"

        fun tools(): JsonArray = JsonArray().apply {
            add(tool(STATUS, "Read whether pair programming is on for this terminal's own session, which partner agent reviews it, whether a review is running, and which agent will review once after this turn (scheduled_reviewer).", readOnly = true))
            add(tool(ENABLE, "Turn on pair programming for this terminal's own session: after each turn a partner agent reviews the changes and sends feedback back into this session. Use only when the user asks for it. Omit agent to use the configured default partner. Switching to another partner cancels a running review. The current turn is reviewed too once it ends.", readOnly = false) {
                agentProperty()
            })
            add(tool(REVIEW_ONCE, "Have a partner agent (the copilot) review this session's work once. Use when the user asks for a copilot or partner review of the task, e.g. \"review it with the copilot\". The review starts when your current turn ends, so call it once, then finish the task and end your turn as usual; the reviewer's feedback comes back into this session as a new message. It does not turn on continuous pair programming; if pair programming is already on, this turn is reviewed anyway and the outcome is already_paired. Do not call it again while addressing the reviewer's feedback: that request has been fulfilled, and such calls return skipped_feedback_turn. Omit agent to use the configured default partner.", readOnly = false) {
                agentProperty()
                add("instruction", JsonObject().apply {
                    addProperty("type", "string")
                    addProperty("description", "Optional extra request for the reviewer this time, e.g. what to focus on. Pass on what the user asked for; do not invent one.")
                })
            })
            add(tool(DISABLE, "Turn off pair programming for this terminal's own session and cancel a running review. Call only when the user explicitly asks to turn it off; never to skip, avoid or end a review of your own work.", readOnly = false))
        }

        private fun JsonObject.agentProperty() = add("agent", JsonObject().apply {
            addProperty("type", "string")
            add("enum", JsonArray().apply { AgentType.entries.forEach { add(it.cli) } })
            addProperty("description", "Partner agent CLI name; must be enabled in imux.")
        })

        private fun tool(name: String, description: String, readOnly: Boolean, properties: JsonObject.() -> Unit = {}) = JsonObject().apply {
            addProperty("name", name)
            addProperty("description", "$description The session is determined by the plugin, not arguments.")
            add("inputSchema", JsonObject().apply {
                addProperty("type", "object"); addProperty("additionalProperties", false)
                add("properties", JsonObject().apply(properties))
            })
            add("annotations", JsonObject().apply {
                addProperty("readOnlyHint", readOnly); addProperty("destructiveHint", false)
                addProperty("idempotentHint", true); addProperty("openWorldHint", false)
            })
        }
    }
}
