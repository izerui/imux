package com.github.liuyuhua.imux.session

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * 读取 `~/.codex/state_5.sqlite` 的 `threads` 表，取会话标题。
 *
 * 为什么必须读它：rollout 文件里**没有**标题字段，只能退而取首条用户消息，
 * 而那常常是注入的系统内容（例如 `# AGENTS.md instructions for ...`），毫无意义。
 * 同一个会话在 sqlite 里的标题是「分析工程结构」——差距很大。
 *
 * 这是 Codex 的私有实现细节，表结构变更会导致标题失效。因此任何异常都降级为
 * 空表，让上层回退到首条用户消息，绝不影响会话列表本身。
 */
class CodexThreadIndex(private val codexHome: Path) {

    /** 返回 sessionId -> 标题。 */
    fun load(): Map<String, String> {
        val file = codexHome.resolve("state_5.sqlite")
        if (!Files.isRegularFile(file)) return emptyMap()

        return runCatching {
            val titles = HashMap<String, String>()
            // 只读打开：codex 可能正在写这个库，我们绝不能干扰它
            DriverManager.getConnection("jdbc:sqlite:file:${file.toAbsolutePath()}?mode=ro").use { conn ->
                conn.createStatement().use { statement ->
                    statement.executeQuery("SELECT id, title FROM threads").use { rows ->
                        while (rows.next()) {
                            val id = rows.getString("id") ?: continue
                            val title = rows.getString("title")?.trim()
                            if (!title.isNullOrEmpty()) titles[id] = title
                        }
                    }
                }
            }
            titles as Map<String, String>
        }.getOrElse {
            LOG.warn("读取 Codex state_5.sqlite 失败，标题回退到首条用户消息", it)
            emptyMap()
        }
    }

    private companion object {
        val LOG = logger<CodexThreadIndex>()
    }
}
