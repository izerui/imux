package com.github.izerui.imux.model

import java.nio.file.Path
import java.time.Instant

/**
 * [displayName] 是产品全称，用在有横向空间的地方——会话树分组、新建会话弹窗。
 * [shortName] 用在通知副标题：那一行还要挤下耗时或等待原因，
 * 「Code」二字不带任何信息量，让位给真正要读的部分。
 */
enum class AgentType(
    val displayName: String,
    val shortName: String,
    val cli: String,
    val vendor: String,
) {
    CLAUDE("Claude Code", "Claude", "claude", "Anthropic"),
    CODEX("Codex", "Codex", "codex", "OpenAI"),
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
