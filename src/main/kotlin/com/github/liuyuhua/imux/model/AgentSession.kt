package com.github.liuyuhua.imux.model

import java.time.Instant

enum class AgentType { CLAUDE, CODEX }

data class AgentSession(
    val id: String,
    val title: String,
    val agentType: AgentType,
    val lastActiveAt: Instant,
)
