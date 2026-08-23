# Windows 上 codex 改读运行态 sqlite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Windows 上 codex 的「此刻在跑哪个会话」改成读 codex 自己的运行态 sqlite，删掉整套 hook 注入机制。

**Architecture:** codex 的 `logs_<n>.sqlite` 里 `logs.process_uuid` 的字面格式是 `pid:<PID>:<uuid>`，同一行带 `thread_id`；`state_<n>.sqlite` 的 `threads.rollout_path` 由 thread_id 给出 rollout 路径。两步查询即可由 pid 得到 rollout 路径，与 macOS 上 `lsof` 报的**逐字相同**，因此 `LiveSessionProbe` 下游一行不用改。

**Tech Stack:** Kotlin, IntelliJ Platform 262, JUnit 4, sqlite-jdbc（已在依赖里）

## 背景：为什么推翻前一个方案

前一版设计断言「codex 没有运行态文件（`state_5.sqlite` 的 `threads` 表无 pid 字段）」，据此在 Windows 上给 codex 注入 `SessionStart` hook 上报。**那个断言是错的**——当初只查了 `threads` 表与 `.codex-global-state.json`，没查 `logs_<n>.sqlite` 的列。

本机实证（活的 codex，pid 4197）：

```
logs_2.sqlite   → SELECT thread_id WHERE process_uuid LIKE 'pid:4197:%'
                  ORDER BY ts DESC LIMIT 1  →  01a01a29-ceb9-75e2-94d5-850c7240693f
state_5.sqlite  → SELECT rollout_path FROM threads WHERE id = 上面那个
                  →  /Users/…/sessions/2026/08/19/rollout-2026-08-19T21-15-42-01a01a29-….jsonl
lsof -p 4197    →  /Users/…/sessions/2026/08/19/rollout-2026-08-19T21-15-42-01a01a29-….jsonl
```

**两条路完全一致。** 查询代价实测：典型 7ms（走 `idx_logs_ts` 倒序立即命中），最坏 200ms（不存在的 pid 需倒扫完 1.1GB）。漂移探测跑在池线程上，可接受。

换掉之后 Windows 上 codex 少掉：`-c` 注入、随包分发的 `.ps1`、HTTP 上报端点扩展、**用户首次被信任提示挡一次**、以及无法验证的 PowerShell 5.1 UTF-8 编码风险。

## Global Constraints

- **macOS / Linux 行为逐字节不变。** 仓库所有者原话：「都支持，但是不要产生 bug，导致其他原有支持的平台有问题」。本计划**只动 Windows 分支**，`ps eww` / `lsof` / `/proc` 三条路一个字节都不改。
- **不修改任何 CLI 的配置文件。** 本计划全程只读。
- **不得用 `java.sql.DriverManager`。** `CodexThreadIndex.kt:19-29` 的 KDoc 记着这个坑：插件 jar 不在系统 classpath 上，`DriverManager` 靠 ServiceLoader 发现驱动时用系统类加载器，于是 sqlite-jdbc 明明打进了包，运行时照样报 `No suitable driver found`——**而这个差异单测复现不了**（Gradle 测试 JVM 里 `DriverManager` 一切正常）。必须直接实例化 `SQLiteDataSource`。
- **只读打开。** codex 可能正在写这些库，绝不能干扰它。
- **认不出就跳过，不能猜**（`LiveSessionProbe` 的铁律）。任何失败一律返回 null / 空，不得编造。
- 平台判断一律参数注入，纯函数体内不得读 `SystemInfo`。
- 测试只能 JUnit 4，项目**未**引入平台 test-framework。测试方法用中文反引号命名。
- 不新增 Gradle 依赖。
- **KDoc 内不得出现 `*/`**，需要时写 `&#42;`。
- **不得在源码里嵌进真的控制字符**（本仓库出过一次真 NUL 写进 Kotlin 源码、编译与测试都没发现）。
- **测试方法名里出现「任何 / 都 / 两 / 一律 / 全部 / 各」这类全称词时，方法体必须对它承诺的每一支各有一条独立断言**（见 `AGENTS.md`，这个仓库为此栽过六次）。
- **禁止 `git add -A` / `git add .` / `git commit -a`**。
- gradle 一律带 `--offline`。
- 基线：851 测试绿。

---

## File Structure

**新建**

| 文件 | 责任 |
| --- | --- |
| `src/main/kotlin/com/github/izerui/imux/session/CodexRuntimeIndex.kt` | pid → rollout 路径；含版本化文件名选取与 `log_dir` 解析两个纯函数 |
| `src/test/kotlin/com/github/izerui/imux/session/CodexRuntimeIndexTest.kt` | |

**修改**

| 文件 | 改什么 |
| --- | --- |
| `session/ProcessProbes.kt` | `readHeldRollouts` 的 Windows 分支从 `emptyList()` 改成查运行态索引 |

**删除**（Task 3）

| 文件 / 位置 | |
| --- | --- |
| `terminal/CodexHookOverride.kt` 与其测试 | 整个 hook 实参构造 |
| `src/main/scripts/codex-imux-reporter.ps1` | 上报脚本 |
| `session/PiReportEndpoint.kt` 的 `CODEX_REPORT_PATH` | |
| `session/PiSessionReportHandler.kt` 的 `handlesCodexReport` 与分派分支 | **pi 那一侧必须逐字节不变** |
| `terminal/AgentCommand.kt` `launchEnvironment` 的 codex 令牌分支 | |
| `monitor/SessionMonitor.kt` `restoreNeedsReportEndpoint` 的 Windows/codex 那一半 | 退回 pi-only |
| `terminal/TerminalHost.kt` 的 `codexHookScriptFor` 与 `codexEndpointOf` | |
| `terminal/AgentCommand.kt` `launchCommand` 的 `codexHookScript` 形参 | |
| `build.gradle.kts` 的 `.ps1` 打包行 | `:test` 的 `inputs` 里 `src/main/scripts` 一并去掉 |

---

### Task 1: `CodexRuntimeIndex` —— 由 pid 查出 rollout 路径

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/session/CodexRuntimeIndex.kt`
- Create: `src/test/kotlin/com/github/izerui/imux/session/CodexRuntimeIndexTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `internal fun latestVersionedDb(names: List<String>, stem: String): String?`
  - `internal fun codexLogDirFrom(configToml: String?): String?`
  - `internal class CodexRuntimeIndex(codexHome: Path)` 带 `fun rolloutPathOf(pid: Long): String?`

- [ ] **Step 1: 先写失败的测试**

创建 `src/test/kotlin/com/github/izerui/imux/session/CodexRuntimeIndexTest.kt`：

```kotlin
package com.github.izerui.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager

class CodexRuntimeIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // 用同一个驱动造结构一致的库，比塞二进制夹具可读得多。
    // 生产代码**不得**这么用 DriverManager，理由见 CodexRuntimeIndex 的 KDoc。
    private fun createLogsDb(name: String, vararg rows: Triple<String, String?, Long>) {
        val file = File(tmp.root, name)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate("CREATE TABLE logs (ts INTEGER, thread_id TEXT, process_uuid TEXT)")
            }
            conn.prepareStatement("INSERT INTO logs (process_uuid, thread_id, ts) VALUES (?, ?, ?)")
                .use { stmt ->
                    rows.forEach { (uuid, thread, ts) ->
                        stmt.setString(1, uuid)
                        stmt.setString(2, thread)
                        stmt.setLong(3, ts)
                        stmt.executeUpdate()
                    }
                }
        }
    }

    private fun createStateDb(name: String, vararg rows: Pair<String, String>) {
        val file = File(tmp.root, name)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use {
                it.executeUpdate("CREATE TABLE threads (id TEXT PRIMARY KEY, rollout_path TEXT NOT NULL)")
            }
            conn.prepareStatement("INSERT INTO threads (id, rollout_path) VALUES (?, ?)").use { stmt ->
                rows.forEach { (id, path) ->
                    stmt.setString(1, id)
                    stmt.setString(2, path)
                    stmt.executeUpdate()
                }
            }
        }
    }

    private fun index() = CodexRuntimeIndex(tmp.root.toPath())

    // ---- 纯函数：版本化文件名 ----

    @Test
    fun `版本化文件名取版本号最大的那个`() {
        // codex 的库名带 schema 版本（logs_2 / state_5 / goals_1），升级会换名。
        // 写死名字会在下一次 schema 变更时静默失效——症状与「功能没做」不可区分。
        assertEquals(
            "logs_10.sqlite",
            latestVersionedDb(listOf("logs_2.sqlite", "logs_10.sqlite", "logs_9.sqlite"), "logs"),
        )
    }

    @Test
    fun `版本号按数值比而不是按字典序`() {
        assertEquals("logs_10.sqlite", latestVersionedDb(listOf("logs_9.sqlite", "logs_10.sqlite"), "logs"))
    }

    @Test
    fun `只认自己那个前缀`() {
        assertNull(latestVersionedDb(listOf("state_5.sqlite", "goals_1.sqlite"), "logs"))
        assertEquals("state_5.sqlite", latestVersionedDb(listOf("state_5.sqlite", "logs_2.sqlite"), "state"))
    }

    @Test
    fun `wal 与 shm 旁文件不算数据库`() {
        // 目录里每个库都跟着 -wal 和 -shm，选错会打开一个不是数据库的文件
        assertEquals(
            "logs_2.sqlite",
            latestVersionedDb(listOf("logs_2.sqlite", "logs_2.sqlite-wal", "logs_2.sqlite-shm"), "logs"),
        )
    }

    @Test
    fun `没有匹配时返回 null`() {
        assertNull(latestVersionedDb(emptyList(), "logs"))
        assertNull(latestVersionedDb(listOf("logs.sqlite", "logs_x.sqlite"), "logs"))
    }

    // ---- 纯函数：log_dir ----

    @Test
    fun `顶层的 log_dir 会被取出来`() {
        assertEquals("/tmp/codexlogs", codexLogDirFrom("model = \"gpt-5\"\nlog_dir = \"/tmp/codexlogs\"\n"))
    }

    @Test
    fun `段落里的同名键不算顶层`() {
        // log_dir 只有作为顶层键才是日志目录；[某段] 之后的同名键是别的东西
        assertNull(codexLogDirFrom("[tui]\nlog_dir = \"/tmp/x\"\n"))
    }

    @Test
    fun `没有 log_dir 或读不到配置时返回 null`() {
        assertNull(codexLogDirFrom("model = \"gpt-5\"\n"))
        assertNull(codexLogDirFrom(null))
        assertNull(codexLogDirFrom(""))
    }

    @Test
    fun `行内注释不会被当成路径的一部分`() {
        assertEquals("/tmp/a", codexLogDirFrom("log_dir = \"/tmp/a\"  # 注释\n"))
    }

    // ---- 端到端 ----

    @Test
    fun `由 pid 查出 rollout 路径`() {
        val thread = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        val rollout = "/Users/me/.codex/sessions/2026/08/19/rollout-2026-08-19T21-15-42-$thread.jsonl"
        createLogsDb("logs_2.sqlite", Triple("pid:4197:9b0b-uuid", thread, 100L))
        createStateDb("state_5.sqlite", thread to rollout)

        assertEquals(rollout, index().rolloutPathOf(4197))
    }

    @Test
    fun `同一个 pid 有多行时取时间戳最新的那一行`() {
        // 用户敲 /new 之后同一个进程会写出新 thread 的日志行；旧行仍在
        val old = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        val new = "01a02cda-8081-7113-b026-607a21c0da98"
        createLogsDb(
            "logs_2.sqlite",
            Triple("pid:4197:u", old, 100L),
            Triple("pid:4197:u", new, 200L),
        )
        createStateDb("state_5.sqlite", old to "/old.jsonl", new to "/new.jsonl")

        assertEquals("/new.jsonl", index().rolloutPathOf(4197))
    }

    @Test
    fun `pid 前缀必须整段匹配`() {
        // pid:419 不能命中 pid:4197；pid:41970 也不能
        val thread = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        createLogsDb(
            "logs_2.sqlite",
            Triple("pid:41970:u", thread, 100L),
            Triple("pid:419:u", thread, 100L),
        )
        createStateDb("state_5.sqlite", thread to "/x.jsonl")

        assertNull(index().rolloutPathOf(4197))
    }

    @Test
    fun `thread_id 为空的行被跳过`() {
        // codex 有大量与会话无关的日志行，thread_id 为 NULL
        val thread = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        createLogsDb(
            "logs_2.sqlite",
            Triple("pid:4197:u", thread, 100L),
            Triple("pid:4197:u", null, 300L),
        )
        createStateDb("state_5.sqlite", thread to "/x.jsonl")

        assertEquals("/x.jsonl", index().rolloutPathOf(4197))
    }

    @Test
    fun `查不到时返回 null 而不是猜`() {
        val thread = "01a01a29-ceb9-75e2-94d5-850c7240693f"
        createLogsDb("logs_2.sqlite", Triple("pid:4197:u", thread, 100L))
        createStateDb("state_5.sqlite", thread to "/x.jsonl")

        // 没有这个 pid
        assertNull(index().rolloutPathOf(9999))
    }

    @Test
    fun `logs 库缺失时返回 null`() {
        createStateDb("state_5.sqlite", "t" to "/x.jsonl")
        assertNull(index().rolloutPathOf(4197))
    }

    @Test
    fun `state 库缺失时返回 null`() {
        createLogsDb("logs_2.sqlite", Triple("pid:4197:u", "t", 100L))
        assertNull(index().rolloutPathOf(4197))
    }

    @Test
    fun `表结构变了也只是返回 null`() {
        // codex 的私有实现细节，schema 变更不得影响会话列表本身
        val file = File(tmp.root, "logs_2.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { it.executeUpdate("CREATE TABLE logs (nothing TEXT)") }
        }
        createStateDb("state_5.sqlite", "t" to "/x.jsonl")

        assertNull(index().rolloutPathOf(4197))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew test --offline --tests '*CodexRuntimeIndexTest*'
```

预期：编译失败，`Unresolved reference: CodexRuntimeIndex`。

- [ ] **Step 3: 写实现**

创建 `src/main/kotlin/com/github/izerui/imux/session/CodexRuntimeIndex.kt`：

```kotlin
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
```

- [ ] **Step 4: 跑测试确认通过**

```
./gradlew test --offline --tests '*CodexRuntimeIndexTest*'
```

**核对 `> Task :test` 那一行不是 `UP-TO-DATE` 也不是 `FROM-CACHE`。**

- [ ] **Step 5: 变异验证**

1. 把 `latestVersionedDb` 的 `maxByOrNull { it.first }` 改成 `maxByOrNull { it.second }`（按名字比）
   → 确认 `版本号按数值比而不是按字典序` FAIL → 还原
2. 把查询里的 `"pid:$pid:%"` 改成 `"pid:$pid%"`（少一个冒号）
   → 确认 `pid 前缀必须整段匹配` FAIL → 还原
3. 把 `ORDER BY ts DESC` 改成 `ORDER BY ts ASC`
   → 确认 `同一个 pid 有多行时取时间戳最新的那一行` FAIL → 还原

- [ ] **Step 6: 跑全量并提交**

```
./gradlew test --offline
```

```bash
git add src/main/kotlin/com/github/izerui/imux/session/CodexRuntimeIndex.kt \
        src/test/kotlin/com/github/izerui/imux/session/CodexRuntimeIndexTest.kt
git commit -m "新增 codex 运行态索引，由 pid 查出正在写的 rollout 路径

前一版设计断言「codex 没有运行态文件」，据此在 Windows 上注入 SessionStart hook
上报会话。那个断言是错的——当初只查了 state 库的 threads 表，没查 logs 库的列。

logs 库的 process_uuid 字面格式就是 pid:<PID>:<uuid>，同一行带 thread_id；
state 库的 threads.rollout_path 由 thread_id 给出路径。本机实证这条链的产出与
lsof -p 报的逐字相同。

库名带 schema 版本号且会随升级换名，因此按数值取最大版本而不是写死文件名。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: 接进 `readHeldRollouts` 的 Windows 分支

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/session/ProcessProbes.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/session/ProcessProbesTest.kt`

**Interfaces:**
- Consumes: `CodexRuntimeIndex(codexHome).rolloutPathOf(pid)`
- Produces: `readHeldRollouts` 增加 `rolloutOfPid: (Long) -> String?` 注入参数

- [ ] **Step 1: 先写失败的测试**

在 `ProcessProbesTest.kt` 追加：

```kotlin
    @Test
    fun `Windows 分支从运行态索引取 rollout 而不是碰 lsof 或 proc`() {
        var ranCommand = false
        val asked = mutableListOf<Long>()

        val held =
            readHeldRollouts(
                4197,
                isLinux = false,
                isWindows = true,
                procRoot = tmp.root.toPath(),
                runCommand = { ranCommand = true; null },
                rolloutOfPid = { pid -> asked += pid; "/x/rollout-a.jsonl" },
            )

        assertEquals(listOf("/x/rollout-a.jsonl"), held)
        assertEquals(listOf(4197L), asked)
        assertFalse("Windows 上不该起 lsof", ranCommand)
    }

    @Test
    fun `Windows 上运行态索引查不到时返回空而不是猜`() {
        val held =
            readHeldRollouts(
                4197,
                isLinux = false,
                isWindows = true,
                procRoot = tmp.root.toPath(),
                runCommand = { null },
                rolloutOfPid = { null },
            )

        assertEquals(emptyList<String>(), held)
    }

    @Test
    fun `非 Windows 一律不碰运行态索引`() {
        // macOS 走 lsof、Linux 走 proc，两支都不该去查 codex 的 sqlite——
        // 那是 Windows 独有的退路，在别处用等于换掉正在工作的观测面
        var touched = false
        val probe: (Long) -> String? = { touched = true; "/x.jsonl" }

        readHeldRollouts(1, isLinux = true, isWindows = false, procRoot = tmp.root.toPath(), rolloutOfPid = probe)
        assertFalse("Linux 分支不该查运行态索引", touched)

        readHeldRollouts(
            1,
            isLinux = false,
            isWindows = false,
            procRoot = tmp.root.toPath(),
            runCommand = { null },
            rolloutOfPid = probe,
        )
        assertFalse("macOS 分支不该查运行态索引", touched)
    }
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew test --offline --tests '*ProcessProbesTest*'
```

预期：编译失败，`readHeldRollouts` 不接受 `rolloutOfPid`。

- [ ] **Step 3: 改实现**

`session/ProcessProbes.kt`：

```kotlin
internal fun readHeldRollouts(
    pid: Long,
    isLinux: Boolean = SystemInfo.isLinux,
    isWindows: Boolean = SystemInfo.isWindows,
    procRoot: Path = PROC_ROOT,
    runCommand: (List<String>) -> String? = ::runCommandForOutput,
    rolloutOfPid: (Long) -> String? = ::codexRolloutOfPid,
): List<String> =
    when {
        isWindows -> listOfNotNull(rolloutOfPid(pid))

        isLinux -> readHeldRolloutsFromProc(pid, procRoot)

        else -> rolloutPathsFromLsof(runCommand(listOf("lsof", "-p", pid.toString())) ?: return emptyList())
    }

/**
 * Windows 上的生产入口：读 codex 自己的运行态 sqlite。
 *
 * 参数化（而不是在 [readHeldRollouts] 里直接 new）只为让分派本身可测——
 * 「分派选错分支」是这一层最难发现的错，症状与「没有漂移」不可区分。
 */
private fun codexRolloutOfPid(pid: Long): String? =
    CodexRuntimeIndex(Path.of(System.getProperty("user.home"), ".codex")).rolloutPathOf(pid)
```

同时把 `readHeldRollouts` 的 KDoc 里「Windows：**这条观测面根本不存在**……codex 那侧改由
codex 自己的 SessionStart hook 上报」整段改写：Windows 读不到句柄，但 codex 把
pid → thread 写进了自己的运行态 sqlite，因此改读它，见 `CodexRuntimeIndex`。

- [ ] **Step 4: 跑全量并变异验证**

```
./gradlew test --offline
```

把 `isWindows -> listOfNotNull(rolloutOfPid(pid))` 与 `isLinux ->` 两支对调
→ 确认 `Windows 分支从运行态索引取 rollout…` 与 `非 Windows 一律不碰运行态索引` **同时** FAIL
→ 还原。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/session/ProcessProbes.kt \
        src/test/kotlin/com/github/izerui/imux/session/ProcessProbesTest.kt
git commit -m "Windows 上 codex 改读运行态 sqlite 认会话

Windows 读不到别的进程打开的文件句柄，原先据此判定这条观测面不存在、
改用 hook 上报。实际上 codex 把 pid 到 thread 的映射写进了自己的 logs 库，
读它即可，三平台都不需要额外机制。

macOS 的 lsof 与 Linux 的 proc 两支一个字节不改——那是正在工作的路径。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 删掉整套 hook 机制

**Files:**
- Delete: `src/main/kotlin/com/github/izerui/imux/terminal/CodexHookOverride.kt`
- Delete: `src/test/kotlin/com/github/izerui/imux/terminal/CodexHookOverrideTest.kt`
- Delete: `src/main/scripts/codex-imux-reporter.ps1`
- Modify: `terminal/AgentCommand.kt`、`terminal/TerminalHost.kt`、`session/PiReportEndpoint.kt`、
  `session/PiSessionReportHandler.kt`、`monitor/SessionMonitor.kt`、`build.gradle.kts`
- Modify: 对应测试文件
- Modify: `docs/superpowers/specs/2026-08-22-imux-cross-platform-design.md`

- [ ] **Step 1: 逐处删除**

删除清单见本计划开头的「File Structure」表。**每删一处，先确认它没有别的调用点。**

三条必须守住的：

1. **pi 的上报路径逐字节不变。** `handlesPiReport`、`piReportTokenMatches`、
   `parsePiReport`、`PI_REPORT_PATH` 一个字符都不改，它们的既有用例全部保留。
   用 `git show 566de0a:src/main/kotlin/com/github/izerui/imux/session/PiSessionReportHandler.kt`
   对比确认
2. **Task 9 的 pid 文件机制要留着。** Windows 上 codex 靠它认「属于哪个标签」
   （`tabPidFileFor` 只看 `SystemInfo.isWindows`，不看 agent 类型）。**只删 hook，不删 pid 文件。**
3. **`restoreNeedsReportEndpoint` 退回 pi-only**，但它的纯函数形态与用例保留
   （删掉 Windows/codex 那一半的分支与对应用例，并在报告里说明理由）

- [ ] **Step 2: 跑全量**

```
./gradlew clean test --offline
```

- [ ] **Step 3: 打包核实**

```
./gradlew buildPlugin --offline
unzip -l build/distributions/*.zip | grep -E "ps1|js"
```

预期：`pi-imux-reporter.js` 仍在，`codex-imux-reporter.ps1` **不再存在**。

- [ ] **Step 4: 更新设计文档**

`docs/superpowers/specs/2026-08-22-imux-cross-platform-design.md`：

- 「组件五」里 Windows/codex 那一格从「`-c hooks.SessionStart` 注入」改成「读运行态 sqlite」
- 「已验证」栏里关于 hook 的三条实证**保留但标注为已不再使用**（它们是真的，只是这条路
  被换掉了；删掉会让读者以为从没验证过）
- 「推断，未证实」栏：删掉 PowerShell 5.1 UTF-8 上报那条（那条路没了），
  新增「Windows 上 codex 的运行态 sqlite 是否在同样路径、同样 schema」
- 真机验证清单：把「Windows / 中文路径下的 codex 标签能否跟随」降级
  （UTF-8 上报那条路没了），新增「Windows 上 codex 敲 `/new` 后标签是否跟上」
- **交付状态一节的每一条事实都要重新核对**——这一节的全部价值就是诚实

- [ ] **Step 5: 提交**

```bash
git add -u
git add docs/superpowers/specs/2026-08-22-imux-cross-platform-design.md
git commit -m "删掉 codex 的 hook 上报机制，改由运行态 sqlite 取代

hook 那条路要注入 -c、随包分发 .ps1、扩 HTTP 端点、让用户首次被信任提示挡一次，
还带着一处本仓库无法验证的 PowerShell 5.1 编码风险。读 sqlite 一样都不需要。

pi 的上报路径逐字节不变；Task 9 的 pid 文件机制保留——Windows 上 codex 仍靠它
认「属于哪个标签」，被换掉的只是「此刻在跑哪个会话」那一半。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

**注意**：`git add -u` 只暂存已跟踪文件的改动与删除，不会带进仓库所有者的未跟踪文件。
仍然**禁止** `git add -A` / `git add .`。

---

## 真机验证清单（新增/变更）

- **Windows**：codex 标签里敲 `/new`，标签是否跟上（现在依赖运行态 sqlite 而非 hook）
- **Windows**：`%USERPROFILE%\.codex\` 下是否有 `logs_<n>.sqlite` 与 `state_<n>.sqlite`，
  且 `process_uuid` 是否同样是 `pid:<PID>:<uuid>` 格式
- **不再需要验证**：codex 的信任提示、`.ps1` 的 UTF-8 编码、PowerShell 执行策略对 hook 的影响
