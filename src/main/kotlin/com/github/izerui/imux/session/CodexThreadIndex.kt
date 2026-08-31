package com.github.izerui.imux.session

import com.intellij.openapi.diagnostic.logger
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * 读取 `~/.codex/state_5.sqlite` 的 `threads` 表，取会话标题。
 *
 * 为什么必须读它：rollout 文件里**没有**标题字段，只能退而取首条用户消息，
 * 而那常常是注入的系统内容（例如 `# AGENTS.md instructions for ...`），毫无意义。
 * 同一个会话在 sqlite 里的标题是「分析工程结构」——差距很大。
 *
 * 这是 Codex 的私有实现细节，表结构变更会导致标题失效。因此任何异常都降级为
 * 空表，让上层回退到首条用户消息，绝不影响会话列表本身。
 *
 * **不要改回 [java.sql.DriverManager]**：插件的 jar 不在系统 classpath 上，而
 * DriverManager 靠 ServiceLoader 发现驱动时用的是系统类加载器，于是 sqlite-jdbc
 * 明明打进了包，运行时照样报「No suitable driver found」。实测正式 IDE 日志里刷了
 * 上百条，标题从来没读出来过，全都回退成了首条用户消息——而 codex 的首条消息是
 * 注入的 AGENTS.md，所有会话看起来一模一样。
 *
 * 直接实例化 [SQLiteDataSource] 绕开那套全局注册表，顺带也不再往 JVM 全局
 * DriverManager 里塞驱动，插件卸载时少一处拖住旧 ClassLoader 的引用。
 *
 * 这个差异单测复现不了：Gradle 的测试 JVM 里 DriverManager 一切正常。
 */
class CodexThreadIndex(private val codexHome: Path) {

    /** 返回 sessionId -> 标题。 */
    fun load(): Map<String, String> {
        val file = codexHome.resolve("state_5.sqlite")
        if (!Files.isRegularFile(file)) return emptyMap()

        return runCatching {
            val titles = HashMap<String, String>()
            // 只读打开：codex 可能正在写这个库，我们绝不能干扰它
            readOnlyDataSource(file).connection.use { conn ->
                val hasName =
                    conn.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA table_info(threads)").use { rows ->
                            generateSequence { if (rows.next()) rows.getString("name") else null }
                                .any { it == "name" }
                        }
                    }
                val query = if (hasName) "SELECT id, name, title FROM threads" else "SELECT id, title FROM threads"
                conn.createStatement().use { statement ->
                    statement.executeQuery(query).use { rows ->
                        while (rows.next()) {
                            val id = rows.getString("id") ?: continue
                            val title =
                                rows.takeIf { hasName }?.getString("name")?.trim()?.takeIf(String::isNotEmpty)
                                    ?: rows.getString("title")?.trim()
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

    private fun readOnlyDataSource(file: Path): SQLiteDataSource =
        SQLiteDataSource(SQLiteConfig().apply { setReadOnly(true) })
            .apply { url = "jdbc:sqlite:${file.toAbsolutePath()}" }

    private companion object {
        val LOG = logger<CodexThreadIndex>()
    }
}
