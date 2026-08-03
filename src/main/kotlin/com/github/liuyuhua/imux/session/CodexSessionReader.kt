package com.github.liuyuhua.imux.session

import com.github.liuyuhua.imux.model.AgentSession
import com.github.liuyuhua.imux.model.AgentType
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.useLines

/**
 * 读取 Codex 的会话库。
 *
 * 布局：<codexHome>/sessions/YYYY/MM/DD/rollout-<时间戳>-<uuid>.jsonl
 *
 * 与 Claude 有两处关键差别：
 * 1. 目录按日期组织而非按 cwd，归属需读首行 session_meta 的 cwd 字段判断
 * 2. 没有标题类记录，标题回退为首条用户消息截断
 */
class CodexSessionReader(private val codexHome: Path) {

    fun read(projectPath: String): List<AgentSession> {
        val root = codexHome.resolve("sessions")
        if (!Files.isDirectory(root)) return emptyList()

        return Files.walk(root).use { stream ->
            stream.toList()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }
                .mapNotNull { readOne(it, projectPath) }
        }
    }

    private fun readOne(file: Path, projectPath: String): AgentSession? = runCatching {
        // 只扫前若干行找首条用户消息，避免为一个标题读完整个大文件
        val lines = file.useLines { it.take(MAX_SCAN_LINES).toList() }
        val meta = lines.firstOrNull() ?: return null
        if (!meta.contains(META_MARKER)) return null

        val cwd = CWD_REGEX.find(meta)?.groupValues?.get(1) ?: return null
        if (cwd != projectPath) return null

        val id = ID_REGEX.find(meta)?.groupValues?.get(1) ?: return null

        AgentSession(
            id = id,
            title = firstUserMessage(lines)?.let(::truncate) ?: "会话 ${id.take(8)}",
            agentType = AgentType.CODEX,
            lastActiveAt = Files.getLastModifiedTime(file).toInstant(),
        )
    }.onFailure { LOG.warn("跳过无法解析的 Codex 会话文件 $file", it) }.getOrNull()

    private fun firstUserMessage(lines: List<String>): String? = lines
        .asSequence()
        .filter { it.contains(USER_ROLE_MARKER) }
        .mapNotNull { TEXT_REGEX.find(it)?.groupValues?.get(1) }
        .firstOrNull()
        ?.replace("\\n", " ")
        ?.replace("\\\"", "\"")

    private fun truncate(text: String): String =
        if (text.length <= TITLE_MAX) text else text.take(TITLE_MAX) + "…"

    private companion object {
        val LOG = logger<CodexSessionReader>()

        const val MAX_SCAN_LINES = 50
        const val TITLE_MAX = 60

        const val META_MARKER = "\"session_meta\""
        const val USER_ROLE_MARKER = "\"role\":\"user\""

        val CWD_REGEX = """"cwd"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
        val ID_REGEX = """"id"\s*:\s*"([^"]+)"""".toRegex()
        val TEXT_REGEX = """"text"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
    }
}
