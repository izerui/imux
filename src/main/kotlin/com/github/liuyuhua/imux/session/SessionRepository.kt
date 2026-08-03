package com.github.liuyuhua.imux.session

import com.github.liuyuhua.imux.model.AgentSession
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 把两个 CLI 的会话库合并成一份列表。
 *
 * 无状态：每次 scan 都重新读文件系统。条数截断不在此处，属于 UI 关注点。
 */
class SessionRepository(
    private val claudeReader: ClaudeSessionReader,
    private val codexReader: CodexSessionReader,
) {

    fun scan(projectPath: String): List<AgentSession> =
        (claudeReader.read(projectPath) + codexReader.read(projectPath))
            .sortedByDescending { it.lastActiveAt }

    companion object {
        fun forUserHome(): SessionRepository {
            val home: Path = Paths.get(System.getProperty("user.home"))
            return SessionRepository(
                ClaudeSessionReader(home.resolve(".claude")),
                CodexSessionReader(home.resolve(".codex")),
            )
        }
    }
}
