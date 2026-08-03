package com.github.liuyuhua.imux.session

import com.github.liuyuhua.imux.model.AgentSession
import com.github.liuyuhua.imux.model.AgentType
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

    fun projectDirName(projectPath: String): String =
        buildString(projectPath.length) {
            for (ch in projectPath) append(if (ch == '/' || ch == '.') '-' else ch)
        }

    fun read(projectPath: String): List<AgentSession> {
        val dir = claudeHome.resolve("projects").resolve(projectDirName(projectPath))
        if (!Files.isDirectory(dir)) return emptyList()

        return Files.list(dir).use { stream ->
            stream.toList()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }
                .mapNotNull { readOne(it) }
        }
    }

    private fun readOne(file: Path): AgentSession? = runCatching {
        val id = file.fileName.toString().removeSuffix(".jsonl")
        AgentSession(
            id = id,
            title = extractTitle(file) ?: fallbackTitle(id),
            agentType = AgentType.CLAUDE,
            lastActiveAt = Files.getLastModifiedTime(file).toInstant(),
        )
    }.onFailure { LOG.warn("跳过无法解析的 Claude 会话文件 $file", it) }.getOrNull()

    /**
     * 取最后一条 ai-title 记录。
     *
     * 用正则逐行扫描而非 JSON 反序列化：会话文件单行可达数 MB（含完整工具输出），
     * 只为取一个标题就解析整行不划算，而 ai-title 记录本身结构极简、可靠。
     */
    private fun extractTitle(file: Path): String? {
        var title: String? = null
        file.useLines { lines ->
            for (line in lines) {
                if (!line.contains(TITLE_MARKER)) continue
                TITLE_REGEX.find(line)?.groupValues?.get(1)?.let { title = unescapeJson(it) }
            }
        }
        return title
    }

    private fun fallbackTitle(id: String) = "会话 ${id.take(8)}"

    private fun unescapeJson(raw: String) = raw
        .replace("\\\"", "\"")
        .replace("\\n", " ")
        .replace("\\\\", "\\")

    private companion object {
        val LOG = logger<ClaudeSessionReader>()
        const val TITLE_MARKER = "\"ai-title\""
        val TITLE_REGEX = """"aiTitle"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
    }
}
