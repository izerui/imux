package com.github.izerui.imux.session

import com.intellij.openapi.diagnostic.logger
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * 由 pid 查出 codex 此刻正在写的 rollout 文件路径。
 *
 * **为什么可以这么查**：codex 的 `logs_&lt;n&gt;.sqlite` 里 `logs.process_uuid` 的字面格式是
 * `pid:&lt;PID&gt;:&lt;uuid&gt;`，同一行带 `thread_id`；`state_&lt;n&gt;.sqlite` 的
 * `threads.rollout_path` 由 thread_id 给出路径。本机实证过这条链的产出与 `lsof -p`
 * 报的**逐字相同**。
 *
 * **这条路只给 Windows 用。** macOS 与 Linux 各自有正在工作的观测面（`lsof` /
 * `/proc/&lt;pid&gt;/fd`），换掉它们是拿能用的换没验证过的。Windows 上读不到别的进程打开的
 * 文件句柄（要 Sysinternals 的 `handle.exe`，不自带且要管理员权限），这里是唯一的路。
 *
 * **不要改回 [java.sql.DriverManager]**：插件的 jar 不在系统 classpath 上，而
 * DriverManager 靠 ServiceLoader 发现驱动时用的是系统类加载器，于是 sqlite-jdbc
 * 明明打进了包，运行时照样报「No suitable driver found」。`CodexThreadIndex` 为此在
 * 正式 IDE 日志里刷过上百条。**这个差异单测复现不了**——Gradle 的测试 JVM 里
 * DriverManager 一切正常。
 *
 * 这是 Codex 的私有实现细节，表结构变更会让查询失败。因此任何异常一律降级为 null，
 * 上层据此跳过本轮认领——`LiveSessionProbe` 的铁律是「认不出就跳过，不能猜」。
 */
internal class CodexRuntimeIndex(private val codexHome: Path) {

    /** 该进程此刻在写的 rollout 路径；查不到返回 null。 */
    fun rolloutPathOf(pid: Long): String? {
        val thread = latestThreadOf(pid) ?: return null
        return rolloutOf(thread)
    }

    private fun latestThreadOf(pid: Long): String? {
        val db = versionedDbIn(logDir(), "logs") ?: return null
        // 前缀整段匹配：`pid:419:` 不能命中 `pid:4197:`，反之亦然。
        // 走 idx_logs_ts 倒序，命中即停——本机 1.1GB 库上典型 7ms、最坏 200ms。
        return querySingle(
            db,
            "SELECT thread_id FROM logs WHERE process_uuid LIKE ? AND thread_id IS NOT NULL " +
                "ORDER BY ts DESC LIMIT 1",
            "pid:$pid:%",
        )
    }

    private fun rolloutOf(threadId: String): String? {
        val db = versionedDbIn(codexHome, "state") ?: return null
        return querySingle(db, "SELECT rollout_path FROM threads WHERE id = ? LIMIT 1", threadId)
    }

    /**
     * `log_dir` 是 codex 认识的配置键（实测 `codex -c log_dir=42` 报
     * `expected path string`），所以日志目录可以被用户改到别处。读不到就用默认。
     */
    private fun logDir(): Path {
        val configured =
            runCatching {
                val file = codexHome.resolve("config.toml")
                if (Files.isRegularFile(file)) Files.readString(file) else null
            }.getOrNull()
        return codexLogDirFrom(configured)
            ?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: codexHome
    }

    private fun querySingle(
        db: Path,
        sql: String,
        argument: String,
    ): String? =
        runCatching {
            // 只读打开：codex 可能正在写这些库，我们绝不能干扰它
            readOnlyDataSource(db).connection.use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, argument)
                    stmt.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                }
            }
        }.getOrElse {
            LOG.debug("查询 ${db.fileName} 失败，本轮不认领", it)
            null
        }

    private fun readOnlyDataSource(file: Path): SQLiteDataSource {
        val config = SQLiteConfig()
        config.setReadOnly(true)
        return SQLiteDataSource(config).apply { url = "jdbc:sqlite:${file.toAbsolutePath()}" }
    }

    private fun versionedDbIn(
        dir: Path,
        stem: String,
    ): Path? =
        runCatching {
            Files.list(dir).use { entries ->
                latestVersionedDb(entries.toList().map { it.fileName.toString() }, stem)
            }
        }.getOrNull()?.let(dir::resolve)

    private companion object {
        val LOG = logger<CodexRuntimeIndex>()
    }
}

/**
 * 从一批文件名里挑出版本号最大的那个 `&lt;stem&gt;_&lt;n&gt;.sqlite`。
 *
 * codex 的库名带 schema 版本（本机现有 `logs_2` / `state_5` / `goals_1` /
 * `queue_1` / `memories_1` / `thread_history_1`），**升级会换名**。写死名字会在下一次
 * schema 变更时静默失效，而症状与「功能没做」不可区分。
 *
 * 版本号按**数值**比而不是字典序：`logs_10` 比 `logs_9` 新，但字典序相反。
 *
 * 后缀必须是 `.sqlite` 整段：每个库旁边都跟着 `-wal` 与 `-shm`，选错会打开一个
 * 不是数据库的文件。
 */
internal fun latestVersionedDb(
    names: List<String>,
    stem: String,
): String? =
    names
        .mapNotNull { name ->
            val version =
                name
                    .removeSuffix(".sqlite")
                    .takeIf { it != name }
                    ?.removePrefix("${stem}_")
                    ?.takeIf { it != name.removeSuffix(".sqlite") }
                    ?.toIntOrNull()
            version?.let { it to name }
        }.maxByOrNull { it.first }
        ?.second

/**
 * 从 `config.toml` 取顶层的 `log_dir`；没有则返回 null。
 *
 * **只认顶层键**：`[某段]` 之后出现的同名键是那个段落的属性，不是日志目录。
 * 与 `lsp/TomlSectionScanner.kt` 一样，这不是通用 TOML 解析器，只回答一个问题。
 */
internal fun codexLogDirFrom(configToml: String?): String? {
    if (configToml.isNullOrBlank()) return null
    configToml.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.startsWith("[")) return null
        if (!line.startsWith("log_dir")) return@forEach
        val value = line.substringAfter('=', "").trim()
        return LOG_DIR_VALUE.find(value)?.groupValues?.get(1)
    }
    return null
}

private val LOG_DIR_VALUE = Regex("""^"([^"]*)"""")
