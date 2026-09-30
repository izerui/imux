package com.github.izerui.imux.session

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.useLines
import kotlin.math.abs

/**
 * 读取 Claude Code 的会话库。
 *
 * 布局：<claudeHome>/projects/<cwd 编码>/<session-uuid>.jsonl
 * 编码规则见 [projectDirName]。
 *
 * 构造器接收 claudeHome 而非硬编码 ~/.claude，是为了测试能指向临时目录。
 */
class ClaudeSessionReader(
    private val claudeHome: Path,
    /**
     * 长路径核实 cwd 与计算兜底哈希时用哪个平台的规则。
     *
     * IDE 给的项目路径是 `C:/a/b`，Claude 在 Windows 上记录、并拿去求哈希的是原生的 `C:\a\b`。
     * 短路径两种写法替换后目录名相同，长路径的哈希与 cwd 比较却会因此对不上。
     * 注入而不是就地读 `SystemInfo`，理由同 [CodexSessionReader]：Windows 分支要能在 macOS 上被测到。
     */
    private val isWindows: Boolean = SystemInfo.isWindows,
) {
    private val historyIndex = ClaudeHistoryIndex(claudeHome)

    /** 已在磁盘上核实过的长路径目录名。只缓存命中，未命中下一次还要重新找。 */
    private val longDirCache = ConcurrentHashMap<String, String>()

    /**
     * cwd -> 会话目录名，与 Claude Code 的 sanitizePath 对齐（docs sessions.md）：
     *
     * 1. 所有非 `[a-zA-Z0-9]` 字符都换成 `-`——不只是 `/` 与 `.`，`_`、空格、中文、
     *    Windows 的 `\` 与 `:` 都算。按 UTF-16 码元逐个替换，与 JS 正则的行为一致。
     * 2. 结果超过 200 个字符时截到 200，再追加 `-<全路径哈希>`。
     *
     * 哈希有两种实现：Node 下是 djb2（即 Java 的 `String.hashCode`）取绝对值转 36 进制，
     * Bun 编译的原生安装包则用 `Bun.hash`（wyhash）。后者这里算不出来，所以长路径
     * 优先在磁盘上按前缀找现有目录、用会话记录里的 cwd 核实；找不到才退回 djb2 的算法值。
     */
    fun projectDirName(projectPath: String): String {
        val sanitized =
            buildString(projectPath.length) {
                for (ch in projectPath) append(if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9') ch else '-')
            }
        if (sanitized.length <= MAX_SANITIZED_LENGTH) return sanitized

        longDirCache[projectPath]?.let { return it }
        val prefix = sanitized.take(MAX_SANITIZED_LENGTH) + "-"
        findLongDir(prefix, projectPath)?.let {
            longDirCache[projectPath] = it
            return it
        }
        val nativePath = if (isWindows) projectPath.replace('/', '\\') else projectPath
        return prefix + abs(nativePath.hashCode().toLong()).toString(36)
    }

    /** 在 projects 下找 `前缀-哈希` 形态、且会话记录里 cwd 正是本项目的目录。 */
    private fun findLongDir(
        prefix: String,
        projectPath: String,
    ): String? {
        val projects = claudeHome.resolve("projects")
        if (!Files.isDirectory(projects)) return null
        val candidates =
            Files.list(projects).use { stream ->
                stream.toList().filter {
                    val name = it.fileName.toString()
                    name.startsWith(prefix) && Files.isDirectory(it) &&
                        name.substring(prefix.length).let { h -> h.isNotEmpty() && h.all { c -> c in '0'..'9' || c in 'a'..'z' } }
                }
            }
        return candidates.firstOrNull { recordsCwd(it, projectPath) }?.fileName?.toString()
    }

    /**
     * 目录里任一会话文件的前若干行中出现的 cwd 是否指向 [projectPath]。
     * 分隔符的归一化与 codex 侧共用 [sameCodexCwd]：只在 Windows 上做，POSIX 上逐字节比较。
     */
    private fun recordsCwd(
        dir: Path,
        projectPath: String,
    ): Boolean =
        runCatching {
            Files.list(dir).use { stream ->
                stream.toList().filter { it.fileName.toString().endsWith(".jsonl") }
            }.any { file ->
                file.useLines { lines ->
                    lines.take(CWD_PROBE_LINES)
                        .firstNotNullOfOrNull { if (it.contains(CWD_MARKER)) JsonLineScanner.stringValue(it, "cwd") else null }
                }?.let { sameCodexCwd(it, projectPath, isWindows) } == true
            }
        }.getOrDefault(false)

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
        const val MAX_SANITIZED_LENGTH = 200
        const val CWD_MARKER = "\"cwd\""
        const val CWD_PROBE_LINES = 50
    }
}
