package com.github.izerui.imux.session

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
class ClaudeSessionReader(private val claudeHome: Path) {

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
            stream.toList()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }
                .mapNotNull { readOne(it, history) }
        }
    }

    private fun readOne(file: Path, history: Map<String, ClaudeHistoryEntry>): AgentSession? = runCatching {
        val id = file.fileName.toString().removeSuffix(".jsonl")
        val historyEntry = history[id]

        AgentSession(
            id = id,
            // 回退链：CLI 生成的标题 -> history.jsonl 里的首条 prompt
            //         -> 文件里的首条用户消息 -> id 短码
            title = extractTitle(file)
                ?: historyEntry?.display?.let(::truncate)
                ?: firstUserMessage(file)
                ?: fallbackTitle(id),
            agentType = AgentType.CLAUDE,
            // 「最后一次说话」比文件 mtime 贴切：resume 会写文件却没有新对话
            lastActiveAt = historyEntry?.lastPromptAtMillis?.let(java.time.Instant::ofEpochMilli)
                ?: Files.getLastModifiedTime(file).toInstant(),
            createdAt = creationTimeOf(file),
            filePath = file,
        )
    }.onFailure { LOG.warn("跳过无法解析的 Claude 会话文件 $file", it) }.getOrNull()

    /**
     * 取最后一条 ai-title 记录。
     *
     * 逐行扫描而非 JSON 反序列化：会话文件单行可达数 MB（含完整工具输出），
     * 只为取一个标题就解析整行不划算，而 ai-title 记录本身结构极简、可靠。
     */
    private fun extractTitle(file: Path): String? {
        var title: String? = null
        file.useLines { lines ->
            for (line in lines) {
                if (!line.contains(TITLE_MARKER)) continue
                JsonLineScanner.stringValue(line, "aiTitle")?.let { title = it }
            }
        }
        return title
    }

    private fun fallbackTitle(id: String) = "会话 ${id.take(8)}"

    /**
     * 没有 ai-title 时的回退：取首条真实用户消息。
     *
     * 实测并非每个会话都有 ai-title（同一项目下 6 个会话有 3 个没有，且与轮数无关），
     * 退到 id 短码毫无信息量。codex 侧本就是这么回退的，两边保持一致。
     *
     * 跳过工具结果回填，以及 <ide_opened_file> 之类的尖括号包裹的系统注入内容。
     */
    private fun firstUserMessage(file: Path): String? = file.useLines { lines ->
        lines.take(MAX_SCAN_LINES)
            .filter { it.contains(USER_RECORD) && !it.contains(TOOL_RESULT) }
            .mapNotNull { JsonLineScanner.stringValue(it, "content") }
            .map { it.replace('\n', ' ').trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("<") }
    }?.let(::truncate)

    private fun truncate(text: String): String =
        if (text.length <= TITLE_MAX) text else text.take(TITLE_MAX) + "…"


    private companion object {
        val LOG = logger<ClaudeSessionReader>()
        const val TITLE_MARKER = "\"ai-title\""
        const val USER_RECORD = "\"type\":\"user\""
        const val TOOL_RESULT = "tool_result"
        const val MAX_SCAN_LINES = 50
        const val TITLE_MAX = 60
    }
}
