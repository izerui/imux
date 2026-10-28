package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.model.AgentType

internal fun bindingsToSave(snapshot: List<Binding>): List<SavedBinding> =
    snapshot.mapNotNull { binding ->
        val sessionId = binding.target.sessionId ?: return@mapNotNull null
        SavedBinding(binding.robotId.value, binding.target.projectPath, binding.target.agentType.cli, sessionId)
    }

internal fun restoredTarget(saved: SavedBinding): SessionTarget? {
    val agentType = AgentType.entries.firstOrNull { it.cli == saved.agentCli } ?: return null
    return SessionTarget(saved.projectPath, RESTORED_TAB_PREFIX + saved.sessionId, agentType, saved.sessionId)
}

private const val RESTORED_TAB_PREFIX = "restored:"
