package com.github.izerui.imux.overview

import com.github.izerui.imux.model.AgentType

/**
 * 增量生成的起始行（从 1 开始）；不满足条件返回 null，由调用方完整生成。
 *
 * 上次的报告本身就是前文的汇总，判断新内容与以前的关系有它就够。三种 CLI 的会话文件只追加，
 * 这里只按位置判断是否被改写：截止位置没有超过上次、完整行没有增加都退回完整生成。
 */
internal fun incrementalStartLine(cached: CachedOverview?, cursor: TrailCursor): Long? {
    val lineCount = cached?.lineCount ?: return null
    // 升级前的旧缓存只有时间线、没有报告：没有上次的报告可合并，完整生成
    if (cached.result.nodes.isEmpty() || cached.result.report == null) return null
    if (cursor.bytes <= cached.fileSize || cursor.lines <= lineCount) return null
    return lineCount + 1
}

/** 三种会话记录的格式说明，写进提示词，让智能体知道去哪找对话和工具调用。 */
internal fun recordFormatZh(agentType: AgentType): String =
    when (agentType) {
        AgentType.CLAUDE ->
            "格式：Claude Code 会话记录，JSONL，每行一个 JSON，按时间顺序。`type` 为 `user`/`assistant` 的行是对话，" +
                "正文在 `message.content`（文本、`tool_use` 工具调用、`tool_result` 工具输出）。"
        AgentType.CODEX ->
            "格式：Codex 会话记录（rollout），JSONL，每行一个 JSON，按时间顺序。`type` 为 `response_item` 的行是对话，" +
                "`payload.role` 为 `user`/`assistant` 的是消息，`function_call`/`function_call_output` 是工具调用和输出。"
        AgentType.PI ->
            "格式：Pi 会话记录，JSONL，每行一个 JSON，按时间顺序。对话在 `message` 字段，" +
                "`message.role` 为 `user`/`assistant`/`toolResult`，正文在 `message.content`。"
    }

internal fun recordFormatEn(agentType: AgentType): String =
    when (agentType) {
        AgentType.CLAUDE ->
            "Format: Claude Code session record, JSONL, one JSON object per line in chronological order. Lines whose `type` is " +
                "`user`/`assistant` are the conversation; content is in `message.content` (text, `tool_use` calls, `tool_result` outputs)."
        AgentType.CODEX ->
            "Format: Codex session record (rollout), JSONL, one JSON object per line in chronological order. Lines whose `type` is " +
                "`response_item` are the conversation: `payload.role` `user`/`assistant` are messages, `function_call`/" +
                "`function_call_output` are tool calls and outputs."
        AgentType.PI ->
            "Format: Pi session record, JSONL, one JSON object per line in chronological order. The conversation is in the " +
                "`message` field, `message.role` is `user`/`assistant`/`toolResult`, content is in `message.content`."
    }
