package com.github.liuyuhua.imux.session

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.useLines

/**
 * 单条历史记录中我们关心的部分。
 *
 * @param display 用户当时输入的 prompt 原文，用作会话标题
 * @param lastPromptAtMillis 该会话最后一次 prompt 的时刻
 */
data class ClaudeHistoryEntry(
    val display: String,
    val lastPromptAtMillis: Long,
)

/**
 * 读取 `~/.claude/history.jsonl`——Claude Code 自己维护的 prompt 索引。
 *
 * 为什么需要它：并非每个会话都有 `ai-title` 记录（实测同一项目 6 个会话有 3 个没有，
 * 且与对话轮数无关）。这些会话的标题其实躺在 history.jsonl 里。只读 ai-title
 * 会让它们退化成 id 短码，而用户合理地认为「不可能没有标题」。
 *
 * 每行结构：`{display, pastedContents, timestamp, project, sessionId}`
 * 其中 project 就是 cwd，timestamp 是毫秒字符串。同一会话每个 prompt 一条。
 */
class ClaudeHistoryIndex(private val claudeHome: Path) {

    fun load(projectPath: String): Map<String, ClaudeHistoryEntry> {
        val file = claudeHome.resolve("history.jsonl")
        if (!Files.isRegularFile(file)) return emptyMap()

        // sessionId -> (最早 prompt 的文本, 最早时刻, 最晚时刻)
        val earliest = HashMap<String, Pair<String, Long>>()
        val latest = HashMap<String, Long>()

        runCatching {
            file.useLines { lines ->
                for (line in lines) {
                    if (!line.contains(SESSION_ID_KEY)) continue

                    val project = JsonLineScanner.stringValue(line, "project") ?: continue
                    if (project != projectPath) continue

                    val sessionId = JsonLineScanner.stringValue(line, "sessionId") ?: continue
                    val display = JsonLineScanner.stringValue(line, "display")?.trim()
                    val timestamp = JsonLineScanner.stringValue(line, "timestamp")?.toLongOrNull()
                        ?: continue

                    latest[sessionId] = maxOf(latest[sessionId] ?: Long.MIN_VALUE, timestamp)

                    // 标题取最早的一条：它才是这次对话的由头。空白 prompt 不参与。
                    if (display.isNullOrEmpty()) continue
                    val current = earliest[sessionId]
                    if (current == null || timestamp < current.second) {
                        earliest[sessionId] = display to timestamp
                    }
                }
            }
        }.onFailure { LOG.warn("读取 Claude history.jsonl 失败", it) }

        return earliest.mapValues { (sessionId, value) ->
            ClaudeHistoryEntry(
                display = value.first,
                lastPromptAtMillis = latest[sessionId] ?: value.second,
            )
        }
    }

    private companion object {
        val LOG = logger<ClaudeHistoryIndex>()
        const val SESSION_ID_KEY = "\"sessionId\""
    }
}
