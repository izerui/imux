package com.github.izerui.imux.session

import com.intellij.util.EnvironmentUtil
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 三个 CLI 各自的数据目录。
 *
 * 三个 CLI 都允许用环境变量把数据目录挪走，多账号时常见的写法是
 * `alias claude-work='CLAUDE_CONFIG_DIR=~/.claude-work claude'`。imux 若写死
 * `~/.claude`，读到的就是另一个账号的会话，或者什么都读不到。
 *
 * 与各 CLI 自己的规则对齐：
 * - Claude：`CLAUDE_CONFIG_DIR`，默认 `~/.claude`
 * - Codex：`CODEX_HOME`，默认 `~/.codex`；SQLite 库另有 `CODEX_SQLITE_HOME`，
 *   但 `config.toml` 的 `sqlite_home` 优先级更高，见 [codexSqliteDir]
 * - pi：`PI_CODING_AGENT_DIR` 覆盖的是 `~/.pi/agent` 而非 `~/.pi`；
 *   `PI_CODING_AGENT_SESSION_DIR` 直接指定会话目录，此时会话**平铺**在该目录下，
 *   不再按 cwd 分子目录（pi 的 `SessionManager.create(cwd, sessionDir)`）
 *
 * 只认绝对路径（允许 `~` 开头）。CLI 会把相对路径解析到自己启动时的 cwd，
 * imux 无从得知那个 cwd，与其猜错目录不如退回默认值。
 *
 * 只能看到 IDE 进程自己的环境变量：只写在 alias 里、临时生效的变量读不到。
 * 从 Dock 启动时 IDE 会加载一次登录 shell 的环境，`export` 在 profile/rc 里的都能读到。
 */
data class AgentHomes(
    val claude: Path,
    val codex: Path,
    /** `CODEX_SQLITE_HOME`；null 表示未设置，按 [codexSqliteDir] 的规则退回。 */
    val codexSqlite: Path?,
    /** pi 的 agent 目录，默认 `~/.pi/agent`。 */
    val piAgent: Path,
    /** `PI_CODING_AGENT_SESSION_DIR`；null 表示用默认的 `<piAgent>/sessions/<cwd 编码>`。 */
    val piSessions: Path?,
) {
    companion object {
        /** 不看环境变量的默认布局。 */
        fun defaults(userHome: Path): AgentHomes =
            AgentHomes(
                claude = userHome.resolve(".claude"),
                codex = userHome.resolve(".codex"),
                codexSqlite = null,
                piAgent = userHome.resolve(".pi").resolve("agent"),
                piSessions = null,
            )

        fun resolve(
            userHome: Path,
            env: (String) -> String?,
        ): AgentHomes {
            fun dir(name: String): Path? = envPath(env(name), userHome)
            val defaults = defaults(userHome)
            return AgentHomes(
                claude = dir("CLAUDE_CONFIG_DIR") ?: defaults.claude,
                codex = dir("CODEX_HOME") ?: defaults.codex,
                codexSqlite = dir("CODEX_SQLITE_HOME"),
                piAgent = dir("PI_CODING_AGENT_DIR") ?: defaults.piAgent,
                piSessions = dir("PI_CODING_AGENT_SESSION_DIR"),
            )
        }

        fun current(): AgentHomes = resolve(Paths.get(System.getProperty("user.home")), EnvironmentUtil::getValue)
    }
}

/** 环境变量值转成目录；空值、相对路径、非法路径一律视为未设置。 */
internal fun envPath(
    raw: String?,
    userHome: Path,
): Path? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        val path =
            when {
                value == "~" -> userHome
                value.startsWith("~/") || value.startsWith("~\\") -> userHome.resolve(value.substring(2))
                else -> Path.of(value)
            }
        path.takeIf { it.isAbsolute }?.normalize()
    }.getOrNull()
}
