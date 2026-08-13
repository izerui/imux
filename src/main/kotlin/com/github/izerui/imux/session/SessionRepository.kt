package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentSession
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 把各个 CLI 的会话库合并成一份列表。
 *
 * 无状态：每次 scan 都重新读文件系统。条数截断不在此处，属于 UI 关注点。
 */
class SessionRepository(
    private val claudeReader: ClaudeSessionReader,
    private val codexReader: CodexSessionReader,
    private val piReader: PiSessionReader,
) {

    /** 按界面展示的最后活动时间倒序，保证列表顺序与「多久以前」一致。 */
    fun scan(projectPath: String): List<AgentSession> =
        (claudeReader.read(projectPath) + codexReader.read(projectPath) + piReader.read(projectPath))
            .sortedByDescending { it.lastActiveAt }

    companion object {
        fun forUserHome(): SessionRepository {
            val home: Path = Paths.get(System.getProperty("user.home"))
            return SessionRepository(
                ClaudeSessionReader(home.resolve(".claude")),
                CodexSessionReader(home.resolve(".codex")),
                PiSessionReader(home.resolve(".pi")),
            )
        }
    }
}
