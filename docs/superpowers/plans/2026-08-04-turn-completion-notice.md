# 会话完成提醒 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 当 claude/codex 会话的对话轮次跑完时，在列表中加粗该会话并弹出 IDE 通知。

**Architecture:** 判定完全基于会话文件，不碰终端状态。`TurnSignalParser` 是纯函数，把新追加的行解析成状态跃迁；`TurnWatcher` 持有偏移与状态、增量读取文件；工具窗口负责通知与标记渲染。

**Tech Stack:** Kotlin 2.3.21，IntelliJ Platform 2026.1，JUnit 4。

## Global Constraints

- **判定依据只用会话文件，禁止依赖 `TerminalView.sessionState` 或任何终端运行时状态。** 上一个标记功能正是死在这上面。
- **Claude 用黑名单判定**：`stop_reason == "tool_use"` 才是进行中，其余（`end_turn`/`stop_sequence`/null）一律算完成。白名单会漏掉以 `stop_sequence` 收尾的会话（实测 71 个中有 7 个）。
- **Codex 三个信号都要处理**：`task_started`（进行中）、`task_complete`（完成并提醒）、`turn_aborted`（回到空闲但不提醒）。
- **只在「进行中 → 空闲」的跃迁时提醒**，避免历史会话全被标满。
- 解析失败一律降级为「无跃迁」，不抛异常、不影响列表。
- 根包名 `com.github.liuyuhua.imux`，新代码放 `turn` 子包。

---

## 文件结构

```
src/main/kotlin/com/github/liuyuhua/imux/
  turn/TurnSignalParser.kt     纯函数：新增行 -> 状态跃迁（本功能的核心，全部规则集中于此）
  turn/TurnWatcher.kt          偏移与状态的持有者，增量读文件，产出完成事件
  turn/TurnNotifier.kt         发 IDE 通知
  model/AgentSession.kt        增加 filePath 字段
  session/ClaudeSessionReader.kt / CodexSessionReader.kt   填充 filePath
  toolwindow/AgentSessionTree.kt      未读加粗渲染
  toolwindow/AgentToolWindowFactory.kt 接线

src/test/kotlin/com/github/liuyuhua/imux/
  turn/TurnSignalParserTest.kt
  turn/TurnWatcherTest.kt
```

拆分依据：解析规则（纯逻辑）与文件读取（有状态、有 IO）分开，前者能被完整单测——这是本设计相对上一个失败功能的关键改进。通知单独成文件，因为它是唯一依赖平台通知 API 的部分。

---

## Task 1: 信号解析（纯函数）

**Files:**
- Create: `src/main/kotlin/com/github/liuyuhua/imux/turn/TurnSignalParser.kt`
- Test: `src/test/kotlin/com/github/liuyuhua/imux/turn/TurnSignalParserTest.kt`

**Interfaces:**
- Consumes: `AgentType`（既有）、`JsonLineScanner`（既有，internal）
- Produces:
  - `enum class TurnState { WORKING, IDLE }`
  - `enum class TurnEvent { COMPLETED, ABORTED, NONE }`
  - `data class TurnParseResult(val state: TurnState, val event: TurnEvent)`
  - `object TurnSignalParser { fun parse(agentType: AgentType, previous: TurnState, lines: List<String>): TurnParseResult }`

- [ ] **Step 1: 写失败测试**

`src/test/kotlin/com/github/liuyuhua/imux/turn/TurnSignalParserTest.kt`：

```kotlin
package com.github.liuyuhua.imux.turn

import com.github.liuyuhua.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnSignalParserTest {

    private fun claude(previous: TurnState, vararg lines: String) =
        TurnSignalParser.parse(AgentType.CLAUDE, previous, lines.toList())

    private fun codex(previous: TurnState, vararg lines: String) =
        TurnSignalParser.parse(AgentType.CODEX, previous, lines.toList())

    private fun assistant(stopReason: String?) =
        """{"type":"assistant","message":{"role":"assistant","stop_reason":${
            if (stopReason == null) "null" else "\"$stopReason\""
        },"content":[{"type":"text","text":"回复"}]}}"""

    private val userLine = """{"type":"user","message":{"content":[{"type":"tool_result"}]}}"""

    // ---- Claude ----

    @Test
    fun `claude 调用工具视为进行中`() {
        assertEquals(TurnState.WORKING, claude(TurnState.IDLE, assistant("tool_use")).state)
    }

    @Test
    fun `claude end_turn 视为完成`() {
        val r = claude(TurnState.WORKING, assistant("end_turn"))
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.COMPLETED, r.event)
    }

    /** 关键：不能只认 end_turn。实测 71 个会话中有 7 个以 stop_sequence 收尾。 */
    @Test
    fun `claude stop_sequence 同样视为完成`() {
        val r = claude(TurnState.WORKING, assistant("stop_sequence"))
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.COMPLETED, r.event)
    }

    @Test
    fun `claude stop_reason 为 null 时视为完成`() {
        val r = claude(TurnState.WORKING, assistant(null))
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.COMPLETED, r.event)
    }

    @Test
    fun `claude 工具结果回填视为进行中`() {
        assertEquals(TurnState.WORKING, claude(TurnState.IDLE, userLine).state)
    }

    @Test
    fun `claude 完整一轮只产出一次完成事件`() {
        val r = claude(
            TurnState.IDLE,
            userLine,
            assistant("tool_use"),
            userLine,
            assistant("end_turn"),
        )
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.COMPLETED, r.event)
    }

    @Test
    fun `claude 已处于空闲时再来完成信号不产出事件`() {
        val r = claude(TurnState.IDLE, assistant("end_turn"))
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.NONE, r.event)
    }

    // ---- Codex ----

    @Test
    fun `codex task_started 视为进行中`() {
        val line = """{"type":"event_msg","payload":{"type":"task_started"}}"""
        assertEquals(TurnState.WORKING, codex(TurnState.IDLE, line).state)
    }

    @Test
    fun `codex task_complete 产出完成事件`() {
        val started = """{"type":"event_msg","payload":{"type":"task_started"}}"""
        val done = """{"type":"event_msg","payload":{"type":"task_complete"}}"""
        val r = codex(TurnState.IDLE, started, done)
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.COMPLETED, r.event)
    }

    /** 用户自己按 Esc 中断的，回到空闲但不该叫他回来看。 */
    @Test
    fun `codex turn_aborted 回到空闲但不提醒`() {
        val started = """{"type":"event_msg","payload":{"type":"task_started"}}"""
        val aborted = """{"type":"event_msg","payload":{"type":"turn_aborted"}}"""
        val r = codex(TurnState.IDLE, started, aborted)
        assertEquals(TurnState.IDLE, r.state)
        assertEquals(TurnEvent.ABORTED, r.event)
    }

    // ---- 通用 ----

    @Test
    fun `无关行不改变状态`() {
        val noise = """{"type":"event_msg","payload":{"type":"token_count","total":123}}"""
        val r = codex(TurnState.WORKING, noise)
        assertEquals(TurnState.WORKING, r.state)
        assertEquals(TurnEvent.NONE, r.event)
    }

    @Test
    fun `损坏行被跳过且不改变状态`() {
        val r = claude(TurnState.WORKING, "这不是 json", "{未闭合")
        assertEquals(TurnState.WORKING, r.state)
        assertEquals(TurnEvent.NONE, r.event)
    }

    @Test
    fun `空输入不改变状态`() {
        val r = claude(TurnState.WORKING)
        assertEquals(TurnState.WORKING, r.state)
        assertEquals(TurnEvent.NONE, r.event)
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
./gradlew test --tests '*TurnSignalParserTest*' -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890
```

预期：编译失败，`Unresolved reference: TurnSignalParser`。

- [ ] **Step 3: 实现**

`src/main/kotlin/com/github/liuyuhua/imux/turn/TurnSignalParser.kt`：

```kotlin
package com.github.liuyuhua.imux.turn

import com.github.liuyuhua.imux.model.AgentType
import com.github.liuyuhua.imux.session.JsonLineScanner

enum class TurnState { WORKING, IDLE }

enum class TurnEvent {
    /** 跃迁到空闲，应当提醒 */
    COMPLETED,

    /** 跃迁到空闲，但不提醒（用户自己中断的） */
    ABORTED,

    /** 无跃迁 */
    NONE,
}

data class TurnParseResult(val state: TurnState, val event: TurnEvent)

/**
 * 把会话文件新追加的行解析成轮次状态跃迁。
 *
 * 纯函数，无 IO、无平台依赖——本功能的全部判定规则集中于此，可完整单测。
 * 这是与上一个失败的运行状态标记最实质的区别：那个依赖终端运行时状态，无法离线验证。
 */
object TurnSignalParser {

    fun parse(agentType: AgentType, previous: TurnState, lines: List<String>): TurnParseResult {
        var state = previous
        var event = TurnEvent.NONE

        for (line in lines) {
            val next = when (agentType) {
                AgentType.CLAUDE -> claudeSignal(line)
                AgentType.CODEX -> codexSignal(line)
            } ?: continue

            // 只有「进行中 -> 空闲」才算一次跃迁
            if (state == TurnState.WORKING && next.state == TurnState.IDLE) {
                event = next.eventOnIdle
            }
            state = next.state
        }

        return TurnParseResult(state, event)
    }

    private class Signal(val state: TurnState, val eventOnIdle: TurnEvent)

    /**
     * Claude 用黑名单判定：只有 tool_use 算进行中，其余一律算完成。
     *
     * 实测 8535 条 assistant 记录中 stop_reason 有四种取值：
     * tool_use / end_turn / stop_sequence / null。若只把 end_turn 当完成，
     * 以 stop_sequence 收尾的会话（71 个中有 7 个）永远不会提醒。
     */
    private fun claudeSignal(line: String): Signal? = when {
        line.contains(STOP_REASON_KEY) -> {
            val reason = runCatching { JsonLineScanner.stringValue(line, "stop_reason") }.getOrNull()
            if (reason == TOOL_USE) {
                Signal(TurnState.WORKING, TurnEvent.NONE)
            } else {
                Signal(TurnState.IDLE, TurnEvent.COMPLETED)
            }
        }

        // 工具结果回填，说明这一轮还在跑
        line.contains(USER_RECORD) -> Signal(TurnState.WORKING, TurnEvent.NONE)

        else -> null
    }

    private fun codexSignal(line: String): Signal? = when {
        line.contains(TASK_STARTED) -> Signal(TurnState.WORKING, TurnEvent.NONE)
        line.contains(TASK_COMPLETE) -> Signal(TurnState.IDLE, TurnEvent.COMPLETED)
        line.contains(TURN_ABORTED) -> Signal(TurnState.IDLE, TurnEvent.ABORTED)
        else -> null
    }

    private const val STOP_REASON_KEY = "\"stop_reason\""
    private const val TOOL_USE = "tool_use"
    private const val USER_RECORD = "\"type\":\"user\""

    private const val TASK_STARTED = "\"task_started\""
    private const val TASK_COMPLETE = "\"task_complete\""
    private const val TURN_ABORTED = "\"turn_aborted\""
}
```

注意 `JsonLineScanner` 目前是 `internal object`，与本包同模块可见，无需改动。

- [ ] **Step 4: 运行测试，确认通过**

```bash
./gradlew test --tests '*TurnSignalParserTest*' -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890
```

预期：13 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: 轮次完成信号解析"
```

---

## Task 2: 会话带上文件路径

**Files:**
- Modify: `src/main/kotlin/com/github/liuyuhua/imux/model/AgentSession.kt`
- Modify: `src/main/kotlin/com/github/liuyuhua/imux/session/ClaudeSessionReader.kt`
- Modify: `src/main/kotlin/com/github/liuyuhua/imux/session/CodexSessionReader.kt`
- Test: 既有的 `ClaudeSessionReaderTest` / `CodexSessionReaderTest` 增加断言

**Interfaces:**
- Produces: `AgentSession` 增加 `val filePath: Path`

`TurnWatcher` 需要定位会话文件。两个 reader 本来就持有该路径，顺手带出来即可。

- [ ] **Step 1: 写失败测试**

在 `ClaudeSessionReaderTest` 中追加：

```kotlin
    @Test
    fun `会话带上自身文件路径`() {
        val file = File(projectDir(), "aaaa-9999.jsonl")
        file.writeText("""{"type":"ai-title","aiTitle":"标题","sessionId":"aaaa-9999"}""")

        assertEquals(file.toPath(), reader().read("/Users/demo/proj")[0].filePath)
    }
```

在 `CodexSessionReaderTest` 中追加：

```kotlin
    @Test
    fun `会话带上自身文件路径`() {
        writeRollout("uuid-path", "/Users/demo/proj")

        val expected = File(tmp.root, "sessions/2026/08/03/rollout-2026-08-03T11-31-27-uuid-path.jsonl")
        assertEquals(expected.toPath(), reader().read("/Users/demo/proj")[0].filePath)
    }
```

两个测试文件都需要补 `import java.nio.file.Path` 不必要，但需确保已 `import java.io.File`（既有）。

- [ ] **Step 2: 运行测试，确认失败**

```bash
./gradlew test --tests '*SessionReaderTest*' -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890
```

预期：编译失败，`AgentSession` 无 `filePath`。

- [ ] **Step 3: 修改模型**

`src/main/kotlin/com/github/liuyuhua/imux/model/AgentSession.kt`：

```kotlin
package com.github.liuyuhua.imux.model

import java.nio.file.Path
import java.time.Instant

enum class AgentType { CLAUDE, CODEX }

data class AgentSession(
    val id: String,
    val title: String,
    val agentType: AgentType,
    val lastActiveAt: Instant,
    /** 该会话在 CLI 会话库中的落盘位置，供轮次监控增量读取。 */
    val filePath: Path,
)
```

- [ ] **Step 4: 两个 reader 填充该字段**

`ClaudeSessionReader.readOne` 的构造改为：

```kotlin
        AgentSession(
            id = id,
            title = extractTitle(file) ?: fallbackTitle(id),
            agentType = AgentType.CLAUDE,
            lastActiveAt = Files.getLastModifiedTime(file).toInstant(),
            filePath = file,
        )
```

`CodexSessionReader.readOne` 的构造改为：

```kotlin
        AgentSession(
            id = id,
            title = firstUserMessage(file)?.let(::truncate) ?: "会话 ${id.take(8)}",
            agentType = AgentType.CODEX,
            lastActiveAt = Files.getLastModifiedTime(file).toInstant(),
            filePath = file,
        )
```

- [ ] **Step 5: 修复因新增字段而编译失败的测试**

`SessionListModelTest` 中的 `session(...)` 辅助函数需补该参数：

```kotlin
    private fun session(id: String, type: AgentType, at: Instant) =
        AgentSession(id, "标题-$id", type, at, java.nio.file.Paths.get("/tmp/$id.jsonl"))
```

- [ ] **Step 6: 运行全部测试，确认通过**

```bash
./gradlew test -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890
```

预期：全部 PASS。

- [ ] **Step 7: 提交**

```bash
git add -A && git commit -m "feat: 会话模型带上文件路径"
```

---

## Task 3: 增量监控

**Files:**
- Create: `src/main/kotlin/com/github/liuyuhua/imux/turn/TurnWatcher.kt`
- Test: `src/test/kotlin/com/github/liuyuhua/imux/turn/TurnWatcherTest.kt`

**Interfaces:**
- Consumes: `TurnSignalParser`、`TurnState`、`TurnEvent`、`AgentType`
- Produces: `class TurnWatcher`，含
  - `fun watch(sessionId: String, agentType: AgentType, file: Path)`
  - `fun unwatch(sessionId: String)`
  - `fun poll(): List<String>` —— 返回本轮刚完成、需要提醒的 sessionId

- [ ] **Step 1: 写失败测试**

`src/test/kotlin/com/github/liuyuhua/imux/turn/TurnWatcherTest.kt`：

```kotlin
package com.github.liuyuhua.imux.turn

import com.github.liuyuhua.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TurnWatcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val started = """{"type":"event_msg","payload":{"type":"task_started"}}"""
    private val done = """{"type":"event_msg","payload":{"type":"task_complete"}}"""
    private val aborted = """{"type":"event_msg","payload":{"type":"turn_aborted"}}"""

    private fun newFile(name: String, content: String = ""): File =
        File(tmp.root, name).apply { writeText(content) }

    private fun File.append(line: String) = appendText("$line\n")

    @Test
    fun `开始监控时不因历史内容误报`() {
        val file = newFile("a.jsonl", "$started\n$done\n")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        assertTrue("历史内容不该触发提醒", watcher.poll().isEmpty())
    }

    @Test
    fun `新追加的完成信号触发提醒`() {
        val file = newFile("b.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        file.append(done)

        assertEquals(listOf("s1"), watcher.poll())
    }

    @Test
    fun `同一次完成只提醒一次`() {
        val file = newFile("c.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        file.append(done)
        watcher.poll()

        assertTrue(watcher.poll().isEmpty())
    }

    @Test
    fun `中断不触发提醒`() {
        val file = newFile("d.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.append(started)
        file.append(aborted)

        assertTrue(watcher.poll().isEmpty())
    }

    @Test
    fun `取消监控后不再提醒`() {
        val file = newFile("e.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())
        watcher.unwatch("s1")

        file.append(started)
        file.append(done)

        assertTrue(watcher.poll().isEmpty())
    }

    @Test
    fun `文件变短时重置而不崩溃`() {
        val file = newFile("f.jsonl", "$started\n$done\n$started\n$done\n")
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, file.toPath())

        file.writeText("")

        assertTrue(watcher.poll().isEmpty())

        file.append(started)
        file.append(done)
        assertEquals(listOf("s1"), watcher.poll())
    }

    @Test
    fun `文件不存在时不崩溃`() {
        val watcher = TurnWatcher()
        watcher.watch("s1", AgentType.CODEX, File(tmp.root, "不存在.jsonl").toPath())

        assertTrue(watcher.poll().isEmpty())
    }

    @Test
    fun `多个会话各自独立`() {
        val a = newFile("g.jsonl")
        val b = newFile("h.jsonl")
        val watcher = TurnWatcher()
        watcher.watch("sa", AgentType.CODEX, a.toPath())
        watcher.watch("sb", AgentType.CODEX, b.toPath())

        a.append(started)
        a.append(done)

        assertEquals(listOf("sa"), watcher.poll())
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
./gradlew test --tests '*TurnWatcherTest*' -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890
```

预期：编译失败，`Unresolved reference: TurnWatcher`。

- [ ] **Step 3: 实现**

`src/main/kotlin/com/github/liuyuhua/imux/turn/TurnWatcher.kt`：

```kotlin
package com.github.liuyuhua.imux.turn

import com.github.liuyuhua.imux.model.AgentType
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * 监控若干会话文件的轮次状态，产出「刚完成、需要提醒」的会话。
 *
 * 增量读取：每个会话记住已读到的字节偏移，每轮只读新追加的部分。
 * claude 的单行可达数 MB，全量重读不可接受。
 *
 * 开始监控时偏移直接设到文件末尾——只关心「开始监控之后」的跃迁，
 * 历史内容不参与判定，这也是历史会话不会被误报的原因。
 */
class TurnWatcher {

    private class Entry(
        val agentType: AgentType,
        val file: Path,
        var offset: Long,
        var state: TurnState,
    )

    private val entries = LinkedHashMap<String, Entry>()

    fun watch(sessionId: String, agentType: AgentType, file: Path) {
        if (entries.containsKey(sessionId)) return
        entries[sessionId] = Entry(
            agentType = agentType,
            file = file,
            offset = currentSize(file),
            state = TurnState.IDLE,
        )
    }

    fun unwatch(sessionId: String) {
        entries.remove(sessionId)
    }

    /** 返回本轮刚完成、需要提醒的 sessionId。 */
    fun poll(): List<String> {
        val completed = mutableListOf<String>()
        for ((sessionId, entry) in entries) {
            if (advance(entry) == TurnEvent.COMPLETED) completed += sessionId
        }
        return completed
    }

    private fun advance(entry: Entry): TurnEvent = runCatching {
        val size = currentSize(entry.file)

        // 文件被截断或重建：重置到末尾，回到空闲，不产出事件
        if (size < entry.offset) {
            entry.offset = size
            entry.state = TurnState.IDLE
            return TurnEvent.NONE
        }
        if (size == entry.offset) return TurnEvent.NONE

        val appended = readRange(entry.file, entry.offset, size)
        entry.offset = size

        val result = TurnSignalParser.parse(entry.agentType, entry.state, appended.lines())
        entry.state = result.state
        result.event
    }.getOrElse {
        LOG.warn("轮次监控读取失败：${entry.file}", it)
        TurnEvent.NONE
    }

    private fun currentSize(file: Path): Long =
        if (Files.isRegularFile(file)) Files.size(file) else 0L

    private fun readRange(file: Path, from: Long, to: Long): String =
        Files.newByteChannel(file).use { channel ->
            channel.position(from)
            val buffer = java.nio.ByteBuffer.allocate((to - from).toInt())
            while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
            String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
        }

    private companion object {
        val LOG = logger<TurnWatcher>()
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
./gradlew test --tests '*TurnWatcherTest*' -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890
```

预期：8 个测试全部 PASS。

若「文件不存在时不崩溃」失败，检查 `currentSize` 是否对不存在的路径返回 0 而非抛异常。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: 会话轮次的增量监控"
```

---

## Task 4: 接线、通知与列表标记

**Files:**
- Create: `src/main/kotlin/com/github/liuyuhua/imux/turn/TurnNotifier.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/main/kotlin/com/github/liuyuhua/imux/terminal/TerminalHost.kt`
- Modify: `src/main/kotlin/com/github/liuyuhua/imux/terminal/AgentTerminalVirtualFile.kt`（`sessionKey` 改为 `var`）
- Modify: `src/main/kotlin/com/github/liuyuhua/imux/session/SessionListModel.kt`（新增 `sessionOf`）
- Modify: `src/main/kotlin/com/github/liuyuhua/imux/toolwindow/AgentSessionTree.kt`
- Modify: `src/main/kotlin/com/github/liuyuhua/imux/toolwindow/AgentToolWindowFactory.kt`

**Interfaces:**
- Consumes: `TurnWatcher`（Task 3）
- Produces: 用户可见的提醒与标记

**本任务不写自动化测试**——通知与 Swing 渲染需要真实 IDE。验收方式是 `runIde` 下的人工核对，步骤已具体列出。

- [ ] **Step 1: 注册通知组**

在 `plugin.xml` 的 `<extensions defaultExtensionNs="com.intellij">` 内追加：

```xml
        <notificationGroup id="imux.turnCompleted" displayType="BALLOON"/>
```

不带 `key` 属性——那需要 message bundle，本插件没有。

- [ ] **Step 2: 写通知器**

`src/main/kotlin/com/github/liuyuhua/imux/turn/TurnNotifier.kt`：

```kotlin
package com.github.liuyuhua.imux.turn

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/** 会话轮次完成时弹出的 IDE 通知，点击可直接跳到该会话。 */
object TurnNotifier {

    private const val GROUP_ID = "imux.turnCompleted"

    fun notifyCompleted(project: Project, title: String, onOpen: () -> Unit) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification("会话已完成", title, NotificationType.INFORMATION)
            .addAction(object : AnAction("打开会话") {
                override fun actionPerformed(event: AnActionEvent) = onOpen()
            })
            .notify(project)
    }
}
```

- [ ] **Step 3: 终端宿主在打开会话时纳入监控**

在 `TerminalHost` 中新增字段与方法：

```kotlin
    private val turnWatcher = TurnWatcher()

    fun turnWatcher(): TurnWatcher = turnWatcher

    /** 打开会话时纳入轮次监控。文件路径来自扫描结果。 */
    fun startWatchingTurn(sessionId: String, agentType: AgentType, file: java.nio.file.Path) {
        turnWatcher.watch(sessionId, agentType, file)
    }
```

并在 `openResume` 中不直接调用——调用方（树）持有 `AgentSession`，那里才拿得到 `filePath`。

- [ ] **Step 4: 树在打开会话时登记监控，并渲染未读标记**

在 `AgentSessionTree` 中新增未读集合与自定义渲染：

```kotlin
    private val unread = mutableSetOf<String>()

    fun markUnread(sessionId: String) {
        unread += sessionId
        reload()
    }

    fun clearUnread(sessionId: String) {
        if (unread.remove(sessionId)) reload()
    }
```

树的构造处加上渲染器，未读条目加粗：

```kotlin
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean,
            ) {
                val data = (value as? DefaultMutableTreeNode)?.userObject
                val isUnread = data is NodeData.Session && data.id in unread
                append(
                    data?.toString() ?: "",
                    if (isUnread) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    else SimpleTextAttributes.REGULAR_ATTRIBUTES,
                )
            }
        }
    }
```

需补导入：

```kotlin
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JTree
```

`handleActivate` 的 Session 分支改为同时登记监控并清除未读：

```kotlin
            is NodeData.Session -> {
                val host = TerminalHost.getInstance(project)
                host.openResume(data.agentType, data.id, data.title)
                model.sessionOf(data.id)?.let { host.startWatchingTurn(data.id, data.agentType, it.filePath) }
                clearUnread(data.id)
            }
```

为此 `SessionListModel` 需要新增按 id 取会话的方法：

```kotlin
    fun sessionOf(id: String): AgentSession? = sessions.firstOrNull { it.id == id }
```

- [ ] **Step 5: 工具窗口轮询监控结果并发通知**

在 `AgentToolWindowFactory.doCreateContent` 中，`startWatching` 的 `onChange` 回调里追加轮次检查。改造 `startWatching` 的调用为：

```kotlin
        startWatching(toolWindow, projectPath) {
            refresh()
            checkCompletedTurns(project, model, sessionTree)
        }
```

并新增：

```kotlin
    /**
     * 检查有无刚完成的会话。正被查看的不提醒——你已经在看了。
     */
    private fun checkCompletedTurns(
        project: Project,
        model: SessionListModel,
        sessionTree: AgentSessionTree,
    ) {
        val host = TerminalHost.getInstance(project)
        val completed = host.turnWatcher().poll()
        if (completed.isEmpty()) return

        ApplicationManager.getApplication().invokeLater {
            completed.forEach { sessionId ->
                if (host.isTabSelected(sessionId)) return@forEach
                val title = model.sessionOf(sessionId)?.title ?: sessionId.take(8)
                sessionTree.markUnread(sessionId)
                TurnNotifier.notifyCompleted(project, title) {
                    model.sessionOf(sessionId)?.let {
                        host.openResume(it.agentType, it.id, it.title)
                    }
                    sessionTree.clearUnread(sessionId)
                }
            }
        }
    }
```

`TerminalHost` 需新增：

```kotlin
    /** 该会话的标签页是否正被选中。用于抑制「你正看着」时的提醒。 */
    fun isTabSelected(sessionId: String): Boolean {
        val file = files[sessionId] ?: return false
        return FileEditorManager.getInstance(project).selectedEditor?.file == file
    }
```

- [ ] **Step 6: 新建会话也纳入监控**

新建会话在绑定到真实 id 前没有文件路径，因此在 `AgentSessionTree.applyNewBindings` 的绑定循环中追加登记：

```kotlin
        bindings.forEach { (pendingKey, sessionId) ->
            host.rebindKey(pendingKey, sessionId, titles[sessionId] ?: "会话 ${sessionId.take(8)}")
            model.sessionOf(sessionId)?.let { host.startWatchingTurn(sessionId, it.agentType, it.filePath) }
        }
```

- [ ] **Step 7: 标签页被选中时清除未读**

只在点击列表时清除是不够的：你从别的编辑器标签页切回该会话时，加粗也应消失。
在 `doCreateContent` 中订阅编辑器选中变化：

```kotlin
        project.messageBus.connect(toolWindow.disposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile as? AgentTerminalVirtualFile ?: return
                    sessionTree.clearUnread(file.sessionKey)
                }
            },
        )
```

需补导入：

```kotlin
import com.github.liuyuhua.imux.terminal.AgentTerminalVirtualFile
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
```

**同时必须修一个既有缺陷**：`AgentTerminalVirtualFile.sessionKey` 目前是 `val`，
而 `rebindKey` 迁移终端时并未更新它。于是新建会话绑定后，虚拟文件里存的仍是旧的
pending key，上面这段代码就会拿错 key、清不掉未读。

把字段改为 `var`：

```kotlin
class AgentTerminalVirtualFile(
    name: String,
    val terminalView: TerminalView,
    var sessionKey: String,
) : LightVirtualFile(name, AgentTerminalFileType, "") {
```

并在 `TerminalHost.rebindKey` 的迁移块里同步更新：

```kotlin
        files.remove(oldKey)?.let { file ->
            files[newKey] = file
            file.sessionKey = newKey
            renameTab(file, newTitle)
        }
```

- [ ] **Step 8: 编译并跑全部测试**

```bash
./gradlew test buildPlugin -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890
```

预期：BUILD SUCCESSFUL，既有测试全部通过。

- [ ] **Step 9: 人工验收**

```bash
./gradlew runIde
```

逐项确认：

1. 打开一个会话，交代一个需要跑几十秒的任务，**切到别的编辑器标签页**
2. 任务完成时右下角弹出「会话已完成」通知，列表中该条目变为**加粗**
3. 点击通知里的「打开会话」，跳到该会话，加粗消失
4. 再打开一个会话，**保持看着它**，交代任务；完成时**不应**弹通知、不应加粗
5. codex 会话中途按 Esc 中断，**不应**弹通知
6. 历史会话在插件启动后不应莫名其妙地被标记

第 4 项和第 6 项是本设计最关键的两条验收：前者验证「正看着不打扰」，后者验证「历史内容不误报」。

- [ ] **Step 10: 提交**

```bash
git add -A && git commit -m "feat: 会话完成时通知并在列表标记未读"
```

---

## 完成标准

1. 交代耗时任务后切走，完成时收到通知
2. 列表中未读会话加粗，打开后恢复
3. 正在查看的会话完成时不打扰
4. codex 中断不提醒
5. 插件启动后历史会话不被误标
