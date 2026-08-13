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
    /**
     * CLI 是否自己维护运行态文件（claude 的 `~/.claude/sessions/<pid>.json`）。
     *
     * 有的话状态是一手的，打开会话就知道在不在跑；没有的话只能从会话文件的轮次信号推断，
     * 归 [com.github.izerui.imux.turn.TurnWatcher] 监控。两条路同时走会让同一轮完成被报两次。
     */
    val hasRuntimeStatusFile: Boolean = false,
    /**
     * 会话 id 能否在启动前由 imux 定下（pi 的 `--session-id`）。
     *
     * 能的话终端与会话从启动那一刻就绑好了，不必等 CLI 落盘再靠时间窗认领，
     * 也不必事后翻进程表探测漂移，见 [com.github.izerui.imux.session.LiveSessionProbe]。
     */
    val preassignsSessionId: Boolean = false,
) {
    CLAUDE("Claude Code", "Claude", "claude", "Anthropic", hasRuntimeStatusFile = true),
    CODEX("Codex", "Codex", "codex", "OpenAI"),
    PI("Pi", "Pi", "pi", "Earendil Works", preassignsSessionId = true),
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
