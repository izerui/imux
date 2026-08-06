# Cancel Pending Session On Close Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让未绑定真实会话 id 的新会话在关闭标签页时立即从列表移除，同时保持已绑定真实会话的保留行为不变。

**Architecture:** 在 `SessionListModel` 内补齐 pending 的取消生命周期，新增一个只作用于“未绑定 pending”的取消方法。关闭标签页时继续先由 `TerminalHost` 结束终端，再由 `SessionMonitor` 协调调用 model 的取消方法；若当前 key 已经是绑定后的真实会话 id，则取消调用是 no-op，因此不会影响真实会话记录。

**Tech Stack:** Kotlin、IntelliJ Platform 服务与 FileEditor 生命周期、JUnit 4

## Global Constraints

- 不修改真实会话的保留策略。
- 不修改 pending 的绑定规则。
- 不修改 30 分钟 TTL，TTL 仍作为兜底清理机制存在。
- 不引入“已关闭 pending”的额外状态。
- 仅删除“key 匹配且尚未绑定真实会话 id”的 pending。
- 关闭标签页时继续先调用 `TerminalHost.closeSession(sessionKey)`，再调用 `SessionMonitor.cancelPendingSession(sessionKey)`。
- `cancelPending(key: String): Boolean` 在 key 不存在、key 已绑定、或 key 本身是真实会话 id 时必须返回 `false` 且无副作用。
- 仅在实际删除 pending 时通知监听者刷新列表。

---

## File Structure

- Modify: `src/main/kotlin/com/github/izerui/imux/session/SessionListModel.kt`
  - 为 pending 生命周期增加 `cancelPending(key: String): Boolean`。
  - 保持 `pendings` / `bindings` 作为唯一事实来源。
- Modify: `src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt`
  - 增加 `cancelPendingSession(key: String)` 协调入口，供 UI 关闭路径调用。
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt`
  - 在真正关闭标签页时，先结束终端，再请求 monitor 取消未绑定 pending。
- Modify: `src/test/kotlin/com/github/izerui/imux/session/SessionListModelTest.kt`
  - 增加 pending 取消语义与通知行为的单元测试。

### Task 1: 为 SessionListModel 增加 pending 取消语义与单元测试

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/session/SessionListModel.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/session/SessionListModelTest.kt`

**Interfaces:**
- Consumes: `fun registerPending(agentType: AgentType): PendingSession`、`fun boundIdFor(key: String): String?`、`fun entries(agentType: AgentType): List<ListEntry>`、`fun addListener(listener: () -> Unit)`、`fun refresh()`
- Produces: `fun cancelPending(key: String): Boolean`

- [ ] **Step 1: 写失败测试，锁定“取消未绑定 pending 会删除条目并返回 true”**

```kotlin
@Test
fun `取消未绑定 pending 会移除条目并返回 true`() {
    val model = model(FakeClock(base))
    val pending = model.registerPending(AgentType.CLAUDE)

    val removed = model.cancelPending(pending.key)

    assertTrue(removed)
    assertTrue(model.entries(AgentType.CLAUDE).isEmpty())
    assertNull(model.boundIdFor(pending.key))
}
```

- [ ] **Step 2: 运行单测，确认当前实现失败**

Run:
```bash
./gradlew test --tests 'com.github.izerui.imux.session.SessionListModelTest.取消未绑定 pending 会移除条目并返回 true'
```

Expected: FAIL，错误类似 `Unresolved reference: cancelPending`。

- [ ] **Step 3: 再补三个失败测试，覆盖 no-op 与通知行为**

```kotlin
@Test
fun `取消已绑定 pending 不影响真实会话并返回 false`() {
    val clock = FakeClock(base)
    val model = model(clock)
    val pending = model.registerPending(AgentType.CLAUDE)

    scanResult = listOf(session("真实id", AgentType.CLAUDE, base.plusSeconds(10)))
    model.refresh()

    val removed = model.cancelPending(pending.key)

    assertFalse(removed)
    assertEquals(
        listOf("真实id"),
        model.entries(AgentType.CLAUDE).map { (it as ListEntry.Existing).session.id },
    )
}

@Test
fun `取消不存在的 key 返回 false 且无副作用`() {
    val model = model(FakeClock(base))
    model.registerPending(AgentType.CLAUDE)

    val removed = model.cancelPending("missing")

    assertFalse(removed)
    assertEquals(1, model.entries(AgentType.CLAUDE).size)
}

@Test
fun `仅成功取消 pending 时通知监听者`() {
    val model = model(FakeClock(base))
    val pending = model.registerPending(AgentType.CLAUDE)
    var notified = 0
    model.addListener { notified++ }

    assertTrue(model.cancelPending(pending.key))
    assertEquals(1, notified)

    assertFalse(model.cancelPending(pending.key))
    assertEquals(1, notified)
}
```

如果缺少断言导入，补上：

```kotlin
import org.junit.Assert.assertFalse
```

- [ ] **Step 4: 运行整个 `SessionListModelTest`，确认测试因未实现而失败**

Run:
```bash
./gradlew test --tests 'com.github.izerui.imux.session.SessionListModelTest'
```

Expected: FAIL，新增的 `cancelPending(...)` 相关测试失败，现有测试应继续编译到同一失败点。

- [ ] **Step 5: 在 `SessionListModel` 中实现最小逻辑**

在 `SessionListModel` 中新增方法，放在 `boundIdFor` 与 `drainNewBindings` 附近，保持 pending 生命周期相关接口聚在一起：

```kotlin
fun cancelPending(key: String): Boolean {
    if (key in bindings) return false
    val removed = pendings.removeIf { it.key == key }
    if (removed) notifyListeners()
    return removed
}
```

要求：
- 不改 `bindings`。
- 不额外引入新状态。
- 只有实际删除到未绑定 pending 时才通知。

- [ ] **Step 6: 运行 `SessionListModelTest`，确认全部通过**

Run:
```bash
./gradlew test --tests 'com.github.izerui.imux.session.SessionListModelTest'
```

Expected: PASS。

- [ ] **Step 7: 提交 Task 1**

```bash
git add src/main/kotlin/com/github/izerui/imux/session/SessionListModel.kt \
        src/test/kotlin/com/github/izerui/imux/session/SessionListModelTest.kt
git commit -m "test: cover pending session cancellation"
```

### Task 2: 将关闭标签页路径接入 pending 取消协调

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/session/SessionListModelTest.kt`

**Interfaces:**
- Consumes: `fun cancelPending(key: String): Boolean`、`fun closeSession(key: String)`
- Produces: `fun cancelPendingSession(key: String)`，以及关闭路径中对 `SessionMonitor.getInstance(project).cancelPendingSession(virtualFile.sessionKey)` 的调用

- [ ] **Step 1: 先写一个最小集成语义测试，明确“真实会话 id 调用取消是 no-op”**

复用 `SessionListModelTest`，因为核心关闭语义最终仍由 model 保证：

```kotlin
@Test
fun `真实会话 id 调用取消时返回 false 且保留会话`() {
    val clock = FakeClock(base)
    val model = model(clock)

    scanResult = listOf(session("真实id", AgentType.CLAUDE, base.plusSeconds(10)))
    model.refresh()

    val removed = model.cancelPending("真实id")

    assertFalse(removed)
    assertEquals(
        listOf("真实id"),
        model.entries(AgentType.CLAUDE).map { (it as ListEntry.Existing).session.id },
    )
}
```

- [ ] **Step 2: 运行该测试，确认当前 model 语义已覆盖并通过**

Run:
```bash
./gradlew test --tests 'com.github.izerui.imux.session.SessionListModelTest.真实会话 id 调用取消时返回 false 且保留会话'
```

Expected: PASS。

- [ ] **Step 3: 在 `SessionMonitor` 中增加协调入口**

在 `clearUnread` 之后、`start` 之前增加方法：

```kotlin
fun cancelPendingSession(key: String) {
    model.cancelPending(key)
}
```

要求：
- 不在这里额外调 `refresh()`。
- 不手动 `notifyListeners()`，由 model 控制通知。

- [ ] **Step 4: 在 `AgentTerminalFileEditor.dispose()` 的真实关闭路径调用 monitor 协调方法**

把结尾从：

```kotlin
TerminalHost.getInstance(project).closeSession(virtualFile.sessionKey)
```

改成：

```kotlin
TerminalHost.getInstance(project).closeSession(virtualFile.sessionKey)
com.github.izerui.imux.monitor.SessionMonitor.getInstance(project)
    .cancelPendingSession(virtualFile.sessionKey)
```

要求：
- 保持 `CLOSING_TO_REOPEN` 的早退逻辑不变。
- 调用顺序必须先 `closeSession(...)` 再 `cancelPendingSession(...)`。

- [ ] **Step 5: 运行目标测试 + 全量模型测试，确认语义稳定**

Run:
```bash
./gradlew test --tests 'com.github.izerui.imux.session.SessionListModelTest'
```

Expected: PASS。

- [ ] **Step 6: 做一次项目级编译验证，确认生产代码改动无编译错误**

Run:
```bash
./gradlew compileKotlin compileTestKotlin
```

Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: 提交 Task 2**

```bash
git add src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt \
        src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt \
        src/test/kotlin/com/github/izerui/imux/session/SessionListModelTest.kt
git commit -m "fix: remove pending session on close"
```

### Task 3: 最终验证与文档对齐检查

**Files:**
- Modify: `docs/superpowers/plans/2026-08-06-cancel-pending-session-on-close.md`（仅在执行过程中发现命令或文件路径需要修正时）

**Interfaces:**
- Consumes: `cancelPending(key: String): Boolean`、`cancelPendingSession(key: String)`、关闭路径对 `SessionMonitor` 的调用
- Produces: 已验证的实现结论，可供后续 code review 或合并使用

- [ ] **Step 1: 运行聚焦测试，确认新增行为存在**

Run:
```bash
./gradlew test --tests 'com.github.izerui.imux.session.SessionListModelTest.取消未绑定 pending 会移除条目并返回 true' \
               --tests 'com.github.izerui.imux.session.SessionListModelTest.取消已绑定 pending 不影响真实会话并返回 false' \
               --tests 'com.github.izerui.imux.session.SessionListModelTest.真实会话 id 调用取消时返回 false 且保留会话'
```

Expected: PASS。

- [ ] **Step 2: 运行本次相关的完整测试类**

Run:
```bash
./gradlew test --tests 'com.github.izerui.imux.session.SessionListModelTest' \
               --tests 'com.github.izerui.imux.terminal.TabTitleSyncTest'
```

Expected: PASS。

- [ ] **Step 3: 检查工作区变更，确认仅包含计划内文件**

Run:
```bash
git status --short
```

Expected: 仅出现以下文件或其提交后为空：
- `src/main/kotlin/com/github/izerui/imux/session/SessionListModel.kt`
- `src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt`
- `src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt`
- `src/test/kotlin/com/github/izerui/imux/session/SessionListModelTest.kt`

- [ ] **Step 4: 对照 spec 做人工核对**

核对清单：
- 未绑定 pending 关闭时立即移除
- 已绑定真实会话关闭后仍保留记录
- 30 分钟 TTL 未修改
- 未引入“已关闭 pending”新状态
- 关闭顺序仍是先 `closeSession(...)` 再 `cancelPendingSession(...)`

Expected: 以上五项全部满足。

- [ ] **Step 5: 如本任务由人工执行，准备请求代码评审**

```bash
git log --oneline -n 3
```

Expected: 能看到本次两个实现提交，供后续 review 使用。
