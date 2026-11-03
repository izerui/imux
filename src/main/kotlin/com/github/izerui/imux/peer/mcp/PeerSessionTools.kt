package com.github.izerui.imux.peer.mcp

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.peer.PeerCoordinator
import com.github.izerui.imux.peer.PeerScheduleResult
import com.github.izerui.imux.settings.ImuxSettings
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** 一次工具调用的结果；[failed] 对应 MCP 的 isError。 */
data class PeerToolResult(val value: JsonObject, val failed: Boolean = false)

/**
 * 未指定伙伴时的默认伙伴，MCP 与「审查一次」弹窗共用：设置里配置且已启用的优先；
 * 否则在已启用列表里从当前会话类型往后顺延一个（到末尾回到开头）；当前类型不在列表里时取第一个，
 * 只启用了当前类型时就用它自己。
 */
internal fun defaultPeerPartner(current: AgentType, enabled: List<AgentType>, configured: String?): AgentType? {
    enabled.firstOrNull { it.cli == configured }?.let { return it }
    val index = enabled.indexOf(current)
    return if (index >= 0 && enabled.size > 1) enabled[(index + 1) % enabled.size] else enabled.firstOrNull()
}

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
    /** 预约了单次审查后以审查者调用。 */
    private val onScheduled: (reviewer: AgentType) -> Unit = {},
) {
    fun status(): PeerToolResult {
        val (key, peer) = session() ?: return closed()
        val status = peer.status(key)
        return PeerToolResult(JsonObject().apply {
            addProperty("enabled", status != null)
            addProperty("partner", status?.targetAgentType?.cli)
            addProperty("reviewing", status?.running == true)
            addProperty("scheduled_reviewer", peer.scheduledReview(key)?.cli)
        })
    }

    fun enable(agent: String?): PeerToolResult {
        val (key, peer) = session() ?: return closed()
        val target = partner(agent) ?: return notEnabled()
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

    /**
     * 预约这一轮结束后审查一次。已结对时这一轮本来就会审，不预约，返回 `already_paired`；
     * 正在处理单次审查意见的这一轮不再预约，返回 `skipped_feedback_turn`。
     * [instruction] 是给审查者的额外要求，只用于这一次。
     */
    suspend fun reviewOnce(agent: String?, instruction: String?): PeerToolResult {
        val (key, peer) = session() ?: return closed()
        val target = partner(agent) ?: return notEnabled()
        return when (peer.scheduleReviewOnce(key, target, instruction?.trim()?.takeIf(String::isNotEmpty))) {
            PeerScheduleResult.ALREADY_PAIRED -> PeerToolResult(JsonObject().apply {
                addProperty("outcome", "already_paired")
                addProperty("partner", peer.boundTarget(key)?.cli)
            })
            PeerScheduleResult.FEEDBACK_TURN -> outcome("skipped_feedback_turn")
            PeerScheduleResult.STATE_CHANGED -> outcome("skipped_state_changed")
            PeerScheduleResult.CLOSED -> closed()
            PeerScheduleResult.SCHEDULED -> {
                onScheduled(target)
                PeerToolResult(JsonObject().apply { addProperty("outcome", "scheduled"); addProperty("partner", target.cli) })
            }
        }
    }

    fun disable(): PeerToolResult {
        val (key, peer) = session() ?: return closed()
        // 还没开始的单次审查预约一并撤销
        val cancelled = peer.cancelScheduledReview(key)
        if (peer.boundTarget(key) == null) return outcome(if (cancelled) "disabled" else "already_disabled")
        peer.unbind(key)
        onSwitched(null)
        return outcome("disabled")
    }

    private fun partner(agent: String?): AgentType? {
        val enabled = enabledAgents()
        return if (agent == null) defaultPeerPartner(currentAgent, enabled, configuredPartner()) else enabled.firstOrNull { it.cli == agent }
    }

    private fun notEnabled() = PeerToolResult(JsonObject().apply {
        addProperty("reason", "agent_not_enabled")
        add("available", JsonArray().apply { enabledAgents().forEach { add(it.cli) } })
    }, failed = true)

    private fun session(): Pair<String, PeerCoordinator>? {
        val key = sessionKey() ?: return null
        return key to (coordinator() ?: return null)
    }

    private fun outcome(value: String) = PeerToolResult(JsonObject().apply { addProperty("outcome", value) })

    private fun closed() = PeerToolResult(JsonObject().apply { addProperty("reason", "session_closed") }, failed = true)
}
