package com.github.izerui.imux.model

import java.nio.file.Path
import java.time.Instant

enum class AgentType(val displayName: String, val cli: String, val vendor: String) {
    CLAUDE("Claude Code", "claude", "Anthropic"),
    CODEX("Codex", "codex", "OpenAI"),
}

data class AgentSession(
    val id: String,
    val title: String,
    val agentType: AgentType,
    val lastActiveAt: Instant,
    /** 会话创建时刻，供需要稳定识别会话年龄的场景使用。 */
    val createdAt: Instant,
    /** 该会话在 CLI 会话库中的落盘位置，供轮次监控增量读取。 */
    val filePath: Path,
)
