package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentSession
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

    /**
     * 按**创建时间**倒序，不用最后活动时间：resume 会写文件、抬高 mtime，
     * 导致「点一下会话它就窜到顶部」，列表位置记不住。
     */
    fun scan(projectPath: String): List<AgentSession> =
        (claudeReader.read(projectPath) + codexReader.read(projectPath))
            .sortedByDescending { it.createdAt }

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
