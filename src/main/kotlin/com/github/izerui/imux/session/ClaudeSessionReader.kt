package com.github.izerui.imux.session

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.useLines

/**
 * 读取 Claude Code 的会话库。
 *
 * 布局：<claudeHome>/projects/<cwd 编码>/<session-uuid>.jsonl
 * 编码规则：cwd 中的 '/' 与 '.' 均替换为 '-'。
 *
 * 构造器接收 claudeHome 而非硬编码 ~/.claude，是为了测试能指向临时目录。
 */
class ClaudeSessionReader(
    private val claudeHome: Path,
) {
    private val historyIndex = ClaudeHistoryIndex(claudeHome)

    fun projectDirName(projectPath: String): String =
        buildString(projectPath.length) {
            for (ch in projectPath) append(if (ch == '/' || ch == '.') '-' else ch)
        }

    fun read(projectPath: String): List<AgentSession> {
        val dir = claudeHome.resolve("projects").resolve(projectDirName(projectPath))
        if (!Files.isDirectory(dir)) return emptyList()

        // history.jsonl 是 Claude 自己维护的 prompt 索引，一次读入按会话 id 查表。
        // 并非每个会话都有 ai-title 记录，缺的那些标题就在这里。
        val history = historyIndex.load(projectPath)

        return Files.list(dir).use { stream ->
            stream
                .toList()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }
                .mapNotNull { readOne(it, history) }
        }
    }

    private fun readOne(
        file: Path,
        history: Map<String, ClaudeHistoryEntry>,
    ): AgentSession? =
        runCatching {
            val id = file.fileName.toString().removeSuffix(".jsonl")
            val historyEntry = history[id]

            AgentSession(
                id = id,
                // 回退链：CLI 生成的标题 -> 文件里的最后一条用户消息
                //         -> history.jsonl 里的首条 prompt -> id 短码
                title =
                    extractTitle(file)
                        ?: lastUserMessage(file)
                        ?: historyEntry?.display?.let(::truncate)
                        ?: fallbackTitle(id),
                agentType = AgentType.CLAUDE,
                // 优先用会话文件里最后一条记录自带的时刻——它才是真正的「最后一次说话」。
                // 不能靠 mtime：resume 会往尾部追加 mode / permission-mode 两条无时间戳的记录，
                // 文件是变了，对话却没有，会话因此假装成「刚刚」。
                // history.jsonl 作为次选：它只收录交互式 CLI 里敲的 prompt，
                // 相当一部分会话（如带 ai-title 的那些）在里面一条记录都没有，兜不住。
                lastActiveAt =
                    lastTimestampOf(file)
                        ?: historyEntry?.lastPromptAtMillis?.let(java.time.Instant::ofEpochMilli)
                        ?: Files.getLastModifiedTime(file).toInstant(),
                createdAt = creationTimeOf(file),
                filePath = file,
            )
        }.onFailure { LOG.warn("跳过无法解析的 Claude 会话文件 $file", it) }.getOrNull()

    /**
     * 用户通过 `/rename` 或 SDK 生成的 custom-title 优先于自动 ai-title。
     *
     * 两者都可能出现多次，分别取最后一条；custom-title 只要存在就一直是权威标题，
     * 后续自动生成的 ai-title 不能把用户明确设置的名字盖回去。
     */
    private fun extractTitle(file: Path): String? {
        var generated: String? = null
        var custom: String? = null
        file.useLines { lines ->
            for (line in lines) {
                when {
                    line.contains(CUSTOM_TITLE_MARKER) ->
                        JsonLineScanner.stringValue(line, "customTitle")?.let { custom = it }

                    line.contains(AI_TITLE_MARKER) ->
                        JsonLineScanner.stringValue(line, "aiTitle")?.let { generated = it }
                }
            }
        }
        return custom ?: generated
    }

    private fun fallbackTitle(id: String) = ImuxBundle.message("session.default", id.take(8))

    /**
     * 没有 ai-title 时的回退：取最后一条真实用户消息，与 `claude -r` 保持一致。
     *
     * 跳过工具结果回填，以及 <ide_opened_file> 之类的尖括号包裹的系统注入内容。
     */
    private fun lastUserMessage(file: Path): String? =
        scanTail(file) { lines ->
            lines.asReversed()
                .filter { it.contains(USER_RECORD) && !it.contains(TOOL_RESULT) }
                .mapNotNull { userText(it) }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("<") && it != INTERRUPTED }
        }?.let(::truncate)

    /**
     * 从一行用户记录中提取文本。
     *
     * content 有两种形态：纯字符串 `"content":"你好"` 和内容块数组
     * `"content":[{"type":"text","text":"你好"}]`。后者在多模态消息中很常见，
     * 只取第一个 text 块足够做标题。
     */
    private fun userText(line: String): String? =
        (JsonLineScanner.stringValue(line, "content") ?: JsonLineScanner.stringValue(line, "text"))
            ?.replace('\n', ' ')?.trim()

    private fun truncate(text: String): String = if (text.length <= TITLE_MAX) text else text.take(TITLE_MAX) + "…"

    private companion object {
        val LOG = logger<ClaudeSessionReader>()
        const val AI_TITLE_MARKER = "\"ai-title\""
        const val CUSTOM_TITLE_MARKER = "\"custom-title\""
        const val USER_RECORD = "\"type\":\"user\""
        const val TOOL_RESULT = "tool_result"
        const val INTERRUPTED = "[Request interrupted by user]"
        const val TITLE_MAX = 60
    }
}
