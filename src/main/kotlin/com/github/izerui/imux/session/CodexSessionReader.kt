package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
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

    private val threadIndex = CodexThreadIndex(codexHome)

    fun read(projectPath: String): List<AgentSession> {
        val root = codexHome.resolve("sessions")
        if (!Files.isDirectory(root)) return emptyList()

        // rollout 文件里没有标题字段，权威标题在 codex 自己的 sqlite 里
        val titles = threadIndex.load()

        return Files.walk(root).use { stream ->
            stream.toList()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jsonl") }
                .mapNotNull { readOne(it, projectPath, titles) }
        }
    }

    /**
     * 归属判断只需要第一行。
     *
     * codex 的会话库是全局的（本机实测 620 个文件、574MB），而属于某个项目的
     * 通常只有个位数。因此**先只读第一行判断 cwd**，不匹配立即返回；
     * 只有匹配的文件才继续读若干行去取标题。
     *
     * 早期实现是先把前 50 行全读出来再过滤，等于为 600 多个无关文件各白读 49 行，
     * 单次 scan 耗时 300–800ms。
     */
    private fun readOne(
        file: Path,
        projectPath: String,
        titles: Map<String, String>,
    ): AgentSession? = runCatching {
        val meta = firstLine(file) ?: return null
        if (!meta.contains(META_MARKER)) return null

        val cwd = JsonLineScanner.stringValue(meta, "cwd") ?: return null
        if (cwd != projectPath) return null

        val id = JsonLineScanner.stringValue(meta, "id") ?: return null

        AgentSession(
            id = id,
            // 回退链：sqlite 里的标题 -> 首条用户消息 -> id 短码
            title = titles[id]?.let(::truncate)
                ?: firstUserMessage(file)?.let(::truncate)
                ?: "会话 ${id.take(8)}",
            agentType = AgentType.CODEX,
            lastActiveAt = Files.getLastModifiedTime(file).toInstant(),
            createdAt = creationTimeOf(file),
            filePath = file,
        )
    }.onFailure { LOG.warn("跳过无法解析的 Codex 会话文件 $file", it) }.getOrNull()

    private fun firstLine(file: Path): String? = file.useLines { it.firstOrNull() }

    /** 只扫前若干行找首条用户消息，避免为一个标题读完整个大文件。 */
    private fun firstUserMessage(file: Path): String? = file.useLines { lines ->
        lines.take(MAX_SCAN_LINES)
            .filter { it.contains(USER_ROLE_MARKER) }
            .mapNotNull { JsonLineScanner.stringValue(it, "text") }
            .firstOrNull()
    }
        ?.replace('\n', ' ')
        ?.replace('\t', ' ')

    private fun truncate(text: String): String =
        if (text.length <= TITLE_MAX) text else text.take(TITLE_MAX) + "…"

    private companion object {
        val LOG = logger<CodexSessionReader>()

        const val MAX_SCAN_LINES = 50
        const val TITLE_MAX = 60

        const val META_MARKER = "\"session_meta\""
        const val USER_ROLE_MARKER = "\"role\":\"user\""
    }
}
