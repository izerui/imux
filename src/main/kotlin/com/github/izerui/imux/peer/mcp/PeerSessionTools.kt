package com.github.izerui.imux.peer.mcp

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.peer.PeerCoordinator
import com.github.izerui.imux.settings.ImuxSettings
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** 一次工具调用的结果；[failed] 对应 MCP 的 isError。 */
data class PeerToolResult(val value: JsonObject, val failed: Boolean = false)

/**
 * 未指定伙伴时的默认伙伴：设置里配置且已启用的优先；否则取第一个与当前会话类型不同的已启用智能体；
 * 只启用了当前类型时就用它自己。
 */
internal fun defaultPeerPartner(current: AgentType, enabled: List<AgentType>, configured: String?): AgentType? =
    enabled.firstOrNull { it.cli == configured } ?: enabled.firstOrNull { it != current } ?: enabled.firstOrNull()

/**
 * 一个终端会话通过 MCP 调用的结对开关，只作用于 [sessionKey] 当前指向的会话（会话 key 迁移后自然跟着走）。
 * 与右键菜单走同一个 [PeerCoordinator]，须在 EDT 上调用。
 */
internal class PeerSessionTools(
    private val currentAgent: AgentType,
    private val sessionKey: () -> String?,
    private val coordinator: () -> PeerCoordinator?,
    private val enabledAgents: () -> List<AgentType> = { ImuxSettings.getInstance().enabledAgentTypes },
    private val configuredPartner: () -> String? = { ImuxSettings.getInstance().state.peerDefaultPartner },
    /** 实际开启、换伙伴或关闭后以新伙伴调用，关闭时为 null；状态未变时不调用。 */
    private val onSwitched: (partner: AgentType?) -> Unit = {},
) {
    fun status(): PeerToolResult {
        val (key, peer) = session() ?: return closed()
        val status = peer.status(key)
        return PeerToolResult(JsonObject().apply {
            addProperty("enabled", status != null)
            addProperty("partner", status?.targetAgentType?.cli)
            addProperty("reviewing", status?.running == true)
        })
    }

    fun enable(agent: String?): PeerToolResult {
        val (key, peer) = session() ?: return closed()
        val enabled = enabledAgents()
        val target = if (agent == null) {
            defaultPeerPartner(currentAgent, enabled, configuredPartner())
        } else {
            enabled.firstOrNull { it.cli == agent }
        } ?: return PeerToolResult(JsonObject().apply {
            addProperty("reason", "agent_not_enabled")
            add("available", JsonArray().apply { enabled.forEach { add(it.cli) } })
        }, failed = true)
        val previous = peer.boundTarget(key)
        val keptPairing = previous == target && peer.keepPairing(key)
        val outcome = when (previous) {
            target -> if (keptPairing) "enabled" else "already_enabled"
            null -> "enabled"
            else -> "switched"
        }
        if (previous != target) {
            peer.bind(key, target)
        }
        if (previous != target || keptPairing) {
            onSwitched(target)
        }
        return PeerToolResult(JsonObject().apply { addProperty("outcome", outcome); addProperty("partner", target.cli) })
    }

    fun disable(): PeerToolResult {
        val (key, peer) = session() ?: return closed()
        if (peer.boundTarget(key) == null) return outcome("already_disabled")
        peer.unbind(key)
        onSwitched(null)
        return outcome("disabled")
    }

    private fun session(): Pair<String, PeerCoordinator>? {
        val key = sessionKey() ?: return null
        return key to (coordinator() ?: return null)
    }

    private fun outcome(value: String) = PeerToolResult(JsonObject().apply { addProperty("outcome", value) })

    private fun closed() = PeerToolResult(JsonObject().apply { addProperty("reason", "session_closed") }, failed = true)
}
