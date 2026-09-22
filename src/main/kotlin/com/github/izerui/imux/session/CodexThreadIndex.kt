package com.github.izerui.imux.session

import com.intellij.openapi.diagnostic.logger
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * 读取 Codex 当前使用的会话数据库，取会话标题。
 *
 * 为什么必须读它：rollout 文件里**没有**标题字段，只能退而取首条用户消息，
 * 而那常常是注入的系统内容（例如 `# AGENTS.md instructions for ...`），毫无意义。
 * 同一个会话在 sqlite 里的标题是「分析工程结构」——差距很大。
 *
 * 这是 Codex 的私有实现细节，表结构变更会导致标题失效。所有数据库均不可读时
 * 不按目录过滤 rollout，让上层回退到用户消息；成功读取的空表仍表示没有会话。
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
    internal enum class Source { DEV, STATE }

    internal data class Catalog(
        val file: Path,
        val source: Source,
        val titles: Map<String, String>,
        val newestCreatedAt: Double,
        val newestActivityAt: Double,
    )

    /**
     * 返回会话索引：sessionId -> 标题。返回 null 表示没有可读取的 DB。
     *
     * null 与空 map 的区别至关重要：
     * - **null**：没有 DB，或所有 DB 均读取失败；调用方不过滤 rollout
     * - **空 map**：成功读取了空库；调用方过滤 rollout
     *
     * 两种库可能同时留在磁盘上。优先比较库内最新活动时间，避免恢复旧会话时
     * 误选残留库；空 dev 库没有行可比较，才参考其文件或 WAL 的修改时间。
     */
    fun load(): Map<String, String>? = currentCatalog()?.titles

    internal fun currentCatalog(): Catalog? {
        val dir = codexSqliteDir(codexHome)
        val devDbFile = sequenceOf(dir.resolve("sqlite/codex-dev.db"), dir.resolve("codex-dev.db"))
            .firstOrNull { Files.isRegularFile(it) }
        val dev = devDbFile?.let(::readDevDb)
        val state = latestVersionedDbIn(dir, "state")
            ?.takeIf { Files.isRegularFile(it) }
            ?.let(::readLegacyDb)
        return when {
            dev == null -> state
            state == null -> dev
            dev.titles.isEmpty() && state.newestActivityAt > databaseModifiedAt(dev.file) -> state
            dev.titles.isEmpty() -> dev
            state.newestActivityAt > dev.newestActivityAt -> state
            state.newestActivityAt == dev.newestActivityAt && state.newestCreatedAt > dev.newestCreatedAt -> state
            else -> dev
        }
    }

    /**
     * dev 库损坏时不据此猜测来源；若另一套库可读，仍可正常显示其中的会话。
     */
    private fun readDevDb(file: Path): Catalog? =
        runCatching {
            val titles = HashMap<String, String>()
            var newest = 0.0
            var newestActivity = 0.0
            readOnlyDataSource(file).connection.use { conn ->
                val columns = columnsOf(conn, "local_thread_catalog")
                val activityColumns = listOf("source_updated_at", "source_recency_at").filter { it in columns }
                conn.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT thread_id, display_title, source_created_at" +
                            activityColumns.joinToString("") { ", $it" } + " FROM local_thread_catalog",
                    ).use { rows ->
                        while (rows.next()) {
                            val id = rows.getString("thread_id") ?: continue
                            titles[id] = rows.getString("display_title")?.trim().orEmpty()
                            val created = rows.getDouble("source_created_at")
                            newest = maxOf(newest, created)
                            newestActivity = maxOf(
                                newestActivity,
                                created,
                                *activityColumns.map { rows.getDouble(it) }.toDoubleArray(),
                            )
                        }
                    }
                }
            }
            Catalog(file, Source.DEV, titles, newest, newestActivity)
        }.getOrElse {
            LOG.warn("读取 Codex codex-dev.db 失败", it)
            null
        }

    /**
     * state 库里的空标题也必须保留 id，否则有 rollout 的新会话在自动标题生成前
     * 无法绑定 pending；展示时由 Reader 回退到用户消息。
     */
    private fun readLegacyDb(file: Path): Catalog? =
        runCatching {
            val titles = HashMap<String, String>()
            var newest = 0.0
            var newestActivity = 0.0
            readOnlyDataSource(file).connection.use { conn ->
                val columns = columnsOf(conn, "threads")
                val hasName = "name" in columns
                val hasCreatedAt = "created_at" in columns
                val activityColumns =
                    listOf("updated_at_ms", "recency_at_ms", "updated_at", "recency_at")
                        .filter { it in columns }
                val query = "SELECT id, title" +
                    (if (hasName) ", name" else "") +
                    (if (hasCreatedAt) ", created_at" else "") +
                    activityColumns.joinToString("") { ", $it" } + " FROM threads"
                conn.createStatement().use { statement ->
                    statement.executeQuery(query).use { rows ->
                        while (rows.next()) {
                            val id = rows.getString("id") ?: continue
                            val title =
                                rows.takeIf { hasName }?.getString("name")?.trim()?.takeIf(String::isNotEmpty)
                                    ?: rows.getString("title")?.trim()
                            titles[id] = title.orEmpty()
                            val created = if (hasCreatedAt) rows.getDouble("created_at") else 0.0
                            newest = maxOf(newest, created)
                            newestActivity = maxOf(
                                newestActivity,
                                created,
                                *activityColumns.map {
                                    val value = rows.getDouble(it)
                                    if (it.endsWith("_ms")) value / 1000.0 else value
                                }.toDoubleArray(),
                            )
                        }
                    }
                }
            }
            Catalog(file, Source.STATE, titles, newest, newestActivity)
        }.getOrElse {
            LOG.warn("读取 Codex ${file.fileName} 失败", it)
            null
        }

    private fun readOnlyDataSource(file: Path): SQLiteDataSource =
        SQLiteDataSource(SQLiteConfig().apply { setReadOnly(true) })
            .apply { url = "jdbc:sqlite:${file.toAbsolutePath()}" }

    private fun columnsOf(connection: Connection, table: String): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { rows ->
                buildSet {
                    while (rows.next()) add(rows.getString("name"))
                }
            }
        }

    private fun databaseModifiedAt(file: Path): Double =
        sequenceOf(file, file.resolveSibling("${file.fileName}-wal"))
            .mapNotNull { runCatching { Files.getLastModifiedTime(it).toMillis() / 1000.0 }.getOrNull() }
            .maxOrNull() ?: 0.0

    private companion object {
        val LOG = logger<CodexThreadIndex>()
    }
}
