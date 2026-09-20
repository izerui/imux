package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentType

data class PeerBinding(
    val targetAgentType: AgentType,
    val task: String = "",
)
