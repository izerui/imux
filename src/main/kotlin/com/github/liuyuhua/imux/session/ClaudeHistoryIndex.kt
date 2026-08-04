package com.github.liuyuhua.imux.session

import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
 * 为什么需要它：并非每个会话都有 `ai-title` 记录，这些会话的标题躺在这里。
 *
 * 为什么用真正的 JSON 解析而不是本包里的 [JsonLineScanner]：这个文件的每行都很小，
 * 而字段类型会变——实测同一文件里 `timestamp` 既有 `123` 也有 `"123"` 两种写法。
 * 手写扫描器只认其中一种就会静默丢掉半数记录，且不报错、极难发现。
 * [JsonLineScanner] 只适用于会话文件那种单行数 MB、无法整行反序列化的场景。
 */
class ClaudeHistoryIndex(private val claudeHome: Path) {

    fun load(projectPath: String): Map<String, ClaudeHistoryEntry> {
        val file = claudeHome.resolve("history.jsonl")
        if (!Files.isRegularFile(file)) return emptyMap()

        val earliest = HashMap<String, Pair<String, Long>>()
        val latest = HashMap<String, Long>()

        runCatching {
            file.useLines { lines ->
                for (line in lines) {
                    val record = parseLine(line) ?: continue
                    if (record.stringOf("project") != projectPath) continue

                    val sessionId = record.stringOf("sessionId")?.takeIf { it.isNotEmpty() } ?: continue
                    val timestamp = record.longOf("timestamp")
                    val display = record.stringOf("display")

                    if (timestamp != null) {
                        latest[sessionId] = maxOf(latest[sessionId] ?: Long.MIN_VALUE, timestamp)
                    }

                    if (display.isNullOrEmpty()) continue

                    // 标题取最早的一条——它才是这次对话的由头。
                    // 时间戳缺失的记录排在最后，但不会因此被丢弃。
                    val rank = timestamp ?: Long.MAX_VALUE
                    val current = earliest[sessionId]
                    if (current == null || rank < current.second) {
                        earliest[sessionId] = display to rank
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

    private fun parseLine(line: String): JsonObject? {
        if (line.isBlank()) return null
        return runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
    }

    /** 取字符串字段；字段缺失、为 null 或不是基本类型时返回 null。 */
    private fun JsonObject.stringOf(key: String): String? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive) return null
        return runCatching { element.asString.trim() }.getOrNull()
    }

    /** 取整数字段，数字与字符串两种写法都接受——实测同一文件里两者并存。 */
    private fun JsonObject.longOf(key: String): Long? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive) return null
        return runCatching { element.asLong }.getOrNull()
            ?: runCatching { element.asString.trim().toLong() }.getOrNull()
    }

    private companion object {
        val LOG = logger<ClaudeHistoryIndex>()
    }
}
