package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.useLines

/**
 * 读取 pi 的会话库。
 *
 * 布局：<piHome>/agent/sessions/<cwd 编码>/<时间戳>_<session-uuid>.jsonl
 *
 * 与 Claude 一样是「一个项目一个目录」，可由项目路径直接算出，
 * 不必像 Codex 那样扫全库再按首行 cwd 归属。
 *
 * 构造器接收 piHome 而非硬编码 ~/.pi，是为了测试能指向临时目录。
 */
class PiSessionReader(private val piHome: Path) {

    /**
     * 目录名编码，与 pi 的 `dist/core/session-manager.js` 保持一致：
     *
     * ```js
     * const safePath = `--${resolvedCwd.replace(/^[/\\]/, "").replace(/[/\\:]/g, "-")}--`;
     * ```
     *
     * 只有路径分隔符与盘符冒号被替换，`.`、`_`、`-` 都原样保留——
     * 这点与 Claude 的编码不同（那边连 `.` 也换），错一个字符就整个目录读不到。
     */
    fun projectDirName(projectPath: String): String =
        buildString(projectPath.length + 4) {
            append("--")
            for (ch in projectPath.removePrefix("/").removePrefix("\\")) {
                append(if (ch == '/' || ch == '\\' || ch == ':') '-' else ch)
            }
            append("--")
        }

    fun read(projectPath: String): List<AgentSession> {
        val dir = piHome.resolve("agent").resolve("sessions").resolve(projectDirName(projectPath))
        if (!Files.isDirectory(dir)) return emptyList()

        return Files.list(dir).use { stream ->
            stream.toList()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }
                .mapNotNull { readOne(it, projectPath) }
        }
    }

    private fun readOne(file: Path, projectPath: String): AgentSession? = runCatching {
        val head = firstLine(file) ?: return null
        if (!head.contains(SESSION_HEAD_MARKER)) return null

        // 编码把 '/' 和 '-' 映射到同一个字符，/Users/demo/a-b 与 /Users/demo/a/b
        // 会落进同一个目录名。首行的 cwd 是原始路径，用它排除撞进来的会话。
        val cwd = JsonLineScanner.stringValue(head, "cwd") ?: return null
        if (cwd != projectPath) return null

        val id = JsonLineScanner.stringValue(head, "id") ?: return null

        AgentSession(
            id = id,
            // 回退链：会话显示名 -> 首条用户消息 -> id 短码
            title = sessionName(file)?.let(::truncate)
                ?: firstUserMessage(file)
                ?: "会话 ${id.take(8)}",
            agentType = AgentType.PI,
            // 与另外两个 reader 同一口径：优先用记录自带的时刻而非 mtime，
            // 理由见 lastTimestampOf 的注释。
            lastActiveAt = lastTimestampOf(file) ?: Files.getLastModifiedTime(file).toInstant(),
            createdAt = creationTimeOf(file),
            filePath = file,
        )
    }.onFailure { LOG.warn("跳过无法解析的 pi 会话文件 $file", it) }.getOrNull()

    private fun firstLine(file: Path): String? = file.useLines { it.firstOrNull() }

    /**
     * 取最后一条 session_info 的 name。
     *
     * 必须是最后一条：`/name` 可以改多次，每次都往文件里追加一条新的 session_info，
     * 取首条就会一直显示改名前的旧标题。
     *
     * 逐行扫描而非 JSON 反序列化，理由同 [ClaudeSessionReader.extractTitle]：
     * 会话文件单行可达数 MB，为一个标题解析整行不划算。
     */
    private fun sessionName(file: Path): String? {
        var name: String? = null
        file.useLines { lines ->
            for (line in lines) {
                if (!line.contains(SESSION_INFO_MARKER)) continue
                val reported = JsonLineScanner.stringValue(line, "name") ?: continue
                name = reported.trim().takeIf { it.isNotEmpty() }
            }
        }
        return name
    }

    /**
     * 没有显示名时的回退：取首条用户消息。
     *
     * pi 不会自动给会话起名——只有手动 `--name` / `/name` 过的才有 session_info，
     * 所以这条回退是常态而非例外。
     */
    private fun firstUserMessage(file: Path): String? = file.useLines { lines ->
        lines.take(MAX_SCAN_LINES)
            .mapNotNull(::userMessageText)
            .map { it.replace('\n', ' ').replace('\t', ' ').trim() }
            .firstOrNull { it.isNotEmpty() }
    }?.let(::truncate)

    private fun userMessageText(line: String): String? {
        if (!line.contains(USER_ROLE_MARKER)) return null
        if (JsonLineScanner.topLevelStringValue(line, "type") != MESSAGE_TYPE) return null
        if (JsonLineScanner.objectStringValue(line, "message", "role") != USER_ROLE) return null
        return JsonLineScanner.objectStringValue(line, "message", "content")
            ?: JsonLineScanner.stringValueInObject(line, "message", "text")
    }

    private fun truncate(text: String): String =
        if (text.length <= TITLE_MAX) text else text.take(TITLE_MAX) + "…"

    private companion object {
        val LOG = logger<PiSessionReader>()

        const val MAX_SCAN_LINES = 50
        const val TITLE_MAX = 60

        const val SESSION_HEAD_MARKER = "\"type\":\"session\""
        const val SESSION_INFO_MARKER = "\"type\":\"session_info\""
        const val USER_ROLE_MARKER = "\"role\":\"user\""
        const val MESSAGE_TYPE = "message"
        const val USER_ROLE = "user"
    }
}
