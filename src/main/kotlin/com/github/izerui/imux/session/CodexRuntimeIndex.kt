package com.github.izerui.imux.session

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
internal class CodexRuntimeIndex(
    private val codexHome: Path,
    /**
     * 该 pid 的进程启动时刻；进程已退出或权限不足时返回 null。
     *
     * **必须可注入**：真机上这个值取决于「此刻本机恰好有没有这个 pid」，
     * 不注入就只能在开发者那台机器上偶然测到一条路径，而「陈旧运行被挡掉」与
     * 「压根没做这个判断」在真实环境里给出完全相同的结果（都是能用），
     * 没有任何断言分得开两者。与 [readTabId] 的 `isLinux` 是同一种注入形状。
     */
    private val processStartOf: (Long) -> Instant? = ::processStartInstantOf,
    /** 单条查询的时间上界，毫秒。见 [withQueryDeadline]。 */
    private val queryTimeoutMs: Long = QUERY_TIMEOUT_MS,
) {

    /** 该进程此刻在写的 rollout 路径；查不到返回 null。 */
    fun rolloutPathOf(pid: Long): String? {
        val dir = sqliteDir()
        val thread = latestThreadOf(pid, dir) ?: return null
        return rolloutOf(thread, dir)
    }

    /**
     * 两步走，缺一不可。
     *
     * `process_uuid` 的字面格式是 `pid:&lt;PID&gt;:&lt;uuid&gt;`，**尾部那个 uuid 正是
     * 「哪一次运行」的判别符**。只按 `pid:&lt;PID&gt;:%` 匹配等于把它丢掉：同一个 pid 的
     * 两次不同 codex 运行在那样一条查询眼里无法区分。
     *
     * Windows 的 pid 池小、按 4 递增，复用远比 POSIX 频繁。于是有这条真实路径：
     * 新 codex 拿到一个被复用的 pid → 探测发生在它写出第一条带 `thread_id` 的日志行
     * **之前** → 查询返回**上一次运行**的 thread → `threads.rollout_path` 老老实实给出
     * 一条真路径 → `threadIdOfRollout` 老老实实解析出合法 UUID → `driftOf` 老老实实
     * 产出一条 KeyDrift。整条链没有任何一环有机会说「这不对」，用户看到的是终端被迁到
     * 一个跟他毫无关系、早已结束的旧会话上。
     *
     * 第一步（[isStaleRun]）挡掉「这个 pid 在日志里的最新一行早于当前进程的启动时刻」，
     * 也就是新进程还一行日志都没写出来的情形。
     * 第二步用完整的 `process_uuid` 做**等值**过滤，保证取到的 thread 行与那条最新行
     * 来自同一次运行——这一步管的是「新进程写了日志、但还没写带 thread 的那一行」。
     * 两个子情形各由一步负责，合起来才完整。
     */
    private fun latestThreadOf(pid: Long, dir: Path): String? {
        val db = versionedDbIn(dir, "logs") ?: return null
        // 前缀整段匹配：`pid:419:` 不能命中 `pid:4197:`，反之亦然。
        // LIKE 的 `%` 与 `_` 仍是元字符，此处安全仅因为 pid 是 Long——
        // 将来如果有人把 pid 改成 String，必须转义或换用 GLOB。
        //
        // 这里**故意不带** `thread_id IS NOT NULL`：要问的是「这个 pid 最近一次出现在
        // 日志里属于哪次运行」，而不是「最近一条带 thread 的行」。带上条件就会跳回上一次
        // 运行的行，把判别符又丢一次。
        val run = queryRow(
            db,
            "SELECT process_uuid, ts FROM logs WHERE process_uuid LIKE ? " +
                "ORDER BY ts DESC, ts_nanos DESC, id DESC LIMIT 1",
            "pid:$pid:%",
        ) { rows -> rows.getString(1)?.let { uuid -> uuid to rows.getLong(2) } } ?: return null
        val (processUuid, seconds) = run
        if (isStaleRun(pid, seconds)) return null
        return queryRow(
            db,
            "SELECT thread_id FROM logs WHERE process_uuid = ? AND thread_id IS NOT NULL " +
                "ORDER BY ts DESC, ts_nanos DESC, id DESC LIMIT 1",
            processUuid,
        ) { rows -> rows.getString(1) }
    }

    /**
     * 这条日志行是不是属于**上一次**运行。
     *
     * `logs.ts` 是 unix 秒（`datetime(ts,'unixepoch')` 给出正确日期）。日志行早于进程
     * 启动时刻，就只可能是同一个 pid 的前一任写下的。
     *
     * **取不到启动时刻时不做这个判断**：进程已退出、权限不足都会走到这里，
     * 少一道保险好过把能用的变成不能用的。
     *
     * 留 [CLOCK_SLACK_SECONDS] 秒宽容：codex 的 ts 与 OS 报的启动时刻来自同一台机器，
     * 但精度与取整方式不同，卡死会误杀刚起来那一瞬间写下的行。
     */
    private fun isStaleRun(pid: Long, seconds: Long): Boolean {
        val start = runCatching { processStartOf(pid) }.getOrNull() ?: return false
        return seconds < start.epochSecond - CLOCK_SLACK_SECONDS
    }

    private fun rolloutOf(threadId: String, dir: Path): String? {
        val db = versionedDbIn(dir, "state") ?: return null
        val sql = "SELECT rollout_path FROM threads WHERE id = ? LIMIT 1"
        return queryRow(db, sql, threadId) { rows -> rows.getString(1) }
    }

    /**
     * `sqlite_home` 是 codex 管六个 sqlite 库存放目录的配置键（`codex doctor` 输出证实
     * `log DB` 与 `state DB` 均跟随此键变化，而 `log_dir` 管的是 `codex-tui.log`
     * 文本日志——两者无关）。读不到就用 `codexHome` 默认值。
     */
    private fun sqliteDir(): Path {
        val configured =
            runCatching {
                val file = codexHome.resolve("config.toml")
                if (Files.isRegularFile(file)) Files.readString(file) else null
            }.getOrNull()
        return codexSqliteHomeFrom(configured)
            ?.let { runCatching { Path.of(it) }.getOrNull() }
            ?: codexHome
    }

    private fun <T> queryRow(
        db: Path,
        sql: String,
        argument: String,
        read: (ResultSet) -> T?,
    ): T? =
        runCatching {
            // 只读打开：codex 可能正在写这些库，我们绝不能干扰它
            readOnlyDataSource(db).connection.use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, argument)
                    withQueryDeadline(stmt, queryTimeoutMs) {
                        stmt.executeQuery().use { rows -> if (rows.next()) read(rows) else null }
                    }
                }
            }
        }.getOrElse {
            if (hasWarned.compareAndSet(false, true)) {
                LOG.warn("查询 ${db.fileName} 失败，本轮不认领", it)
            } else {
                LOG.debug("查询 ${db.fileName} 失败，本轮不认领", it)
            }
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
        val hasWarned = AtomicBoolean(false)
    }
}

/**
 * 生产用的启动时刻来源。参数化的理由见 [CodexRuntimeIndex] 的构造参数。
 *
 * `ProcessHandle.of` 对已退出的 pid 返回空，`startInstant()` 在权限不足时也返回空——
 * 两者都落到 null，由调用方决定「取不到就不判断」。
 */
private fun processStartInstantOf(pid: Long): Instant? =
    runCatching {
        ProcessHandle.of(pid).flatMap { it.info().startInstant() }.orElse(null)
    }.getOrNull()

/**
 * 给一条查询套上时间上界，超时按查不到处理。
 *
 * **为什么不用 `Statement.setQueryTimeout`**：本机在 sqlite-jdbc 3.49.1.0 上实测，
 * 它**不限制查询时长**——`setQueryTimeout(3)` 之后一条跑 45.8 秒的递归 CTE 照样跑完并
 * 正常返回。读回 `PRAGMA busy_timeout` 可见它落到的是 busy timeout（等锁的上界，
 * 该驱动默认本就是 3000ms），与「这条查询最多跑多久」是两回事。
 *
 * 真正管用的是 `Statement.cancel()`：驱动把它接到 `sqlite3_interrupt`，实测由另一个
 * 线程在 1 秒时调用，查询在 1005ms 抛 `SQLITE_INTERRUPT` 中止。因此这里用一个看门狗
 * 定时器去 cancel，异常由外层的 runCatching 吞掉，降级为「本轮不认领」。
 *
 * 3 秒与隔壁 `runCommandForOutput` 的 `COMMAND_TIMEOUT_MS` 取齐，理由也一样：
 * 调用方在轮询链路上，宁可这一轮探测不出来。
 *
 * 看门狗排不上（IDE 正在关闭，线程池已拒收）时**不套界直接跑**——少一道保险
 * 好过把能用的变成不能用的。cancel 只作用于本 [Statement] 自己的连接，
 * 每条查询各开一个连接，不会误伤别的查询。
 */
internal fun <T> withQueryDeadline(
    statement: Statement,
    timeoutMs: Long,
    body: () -> T,
): T {
    val watchdog =
        runCatching {
            AppExecutorUtil.getAppScheduledExecutorService().schedule(
                Runnable { runCatching { statement.cancel() } },
                timeoutMs,
                TimeUnit.MILLISECONDS,
            )
        }.getOrNull()
    return try {
        body()
    } finally {
        watchdog?.cancel(false)
    }
}

/** 单条 sqlite 查询的时间上界，与 `ProcessProbes` 的 `COMMAND_TIMEOUT_MS` 取齐。 */
private const val QUERY_TIMEOUT_MS = 3_000L

/** 允许日志时间戳比进程启动时刻早这么多秒，见 `CodexRuntimeIndex.isStaleRun`。 */
private const val CLOCK_SLACK_SECONDS = 5L

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
 * 从 `config.toml` 取顶层的 `sqlite_home`；没有则返回 null。
 *
 * `codex doctor` 的输出证明 `sqlite_home` 管六个 sqlite 库的存放目录，
 * `log_dir` 管的是 `codex-tui.log` 文本日志——两者无关。
 *
 * **只认顶层键**：`[某段]` 之后出现的同名键是那个段落的属性，不是库目录。
 * 与 `lsp/TomlSectionScanner.kt` 一样，这不是通用 TOML 解析器，只回答一个问题。
 */
internal fun codexSqliteHomeFrom(configToml: String?): String? {
    if (configToml.isNullOrBlank()) return null
    configToml.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.startsWith("[")) return null
        if (!line.startsWith("sqlite_home")) return@forEach
        val value = line.substringAfter('=', "").trim()
        return TOML_QUOTED_VALUE.find(value)?.groupValues?.get(1)
    }
    return null
}

private val TOML_QUOTED_VALUE = Regex("""^"([^"]*)"""")
