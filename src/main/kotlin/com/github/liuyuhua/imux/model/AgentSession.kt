package com.github.liuyuhua.imux.model

import java.nio.file.Path
import java.time.Instant

enum class AgentType { CLAUDE, CODEX }

data class AgentSession(
    val id: String,
    val title: String,
    val agentType: AgentType,
    val lastActiveAt: Instant,
    /** 该会话在 CLI 会话库中的落盘位置，供轮次监控增量读取。 */
    val filePath: Path,
)
