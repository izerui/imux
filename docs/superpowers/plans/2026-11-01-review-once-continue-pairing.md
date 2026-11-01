# 审查一次时同时开启结对编程 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** “审查一次”对话框加勾选框“同时开启结对编程”，勾选后确认时既开启普通结对编程、又立即审查当前这一轮。

**Architecture:** `PeerCoordinator.reviewOnce` 增加 `keepPairing` 参数：为 `true` 时只 `bind` 并立即启动一轮审查，不登记单次标记，之后全部走普通结对路径。审查要求从“按会话的单次登记”改为挂在本轮 `PeerRun` 上，随这一轮结束失效。对话框把勾选结果传进来。

**Tech Stack:** Kotlin、IntelliJ Platform（Swing `DialogWrapper`）、kotlinx.coroutines、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-11-01-review-once-continue-pairing-design.md`

## Global Constraints

- 不勾选时行为与现在完全一致（审查完解绑、取消解绑、新一轮开始解绑、失败重试带要求）。
- 勾选框每次打开对话框默认不勾，不记忆。
- 审查要求只用于这一次审查；结对后续各轮不带。
- 轮次计数沿用现有规则：只有反馈成功自动发送才计一轮。
- 新文案键必须加到全部 10 个语言文件（`ImuxBundleTest` 要求键一致）；本组其他语言沿用英文。
- 工作区可能有其他会话的未提交改动：只 `git add` 本计划列出的文件，禁止 `git stash`、`git add -A`。

---

### Task 1: 协调器支持“审查并继续结对”

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/peer/PeerSessionGuard.kt`（`PeerRun` 加字段）
- Modify: `src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt`（`OneShotInfo`、`reviewOnce`、`runReviewAndInject`）
- Create: `src/test/kotlin/com/github/izerui/imux/peer/PeerReviewOnceKeepPairingTest.kt`

**Interfaces:**
- Produces: `fun reviewOnce(sessionKey: String, targetAgentType: AgentType, userInstruction: String?, keepPairing: Boolean = false)`（Task 2 调用）
- Produces: `PeerRun.instruction: String?`（`@Volatile var`，默认 `null`）

- [ ] **Step 1: 写失败的测试**

创建 `src/test/kotlin/com/github/izerui/imux/peer/PeerReviewOnceKeepPairingTest.kt`：

```kotlin
package com.github.izerui.imux.peer

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.SessionListModel
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 审查一次时勾选“同时开启结对编程”：确认即结对，审查要求只用于这一次，之后按普通结对执行。 */
class PeerReviewOnceKeepPairingTest {
    private val events = ConcurrentLinkedQueue<PeerReviewEvent>()
    private val prompts = ConcurrentLinkedQueue<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val instruction = "重点看并发"

    /** [replies] 依次作为每次审查的输出；用完后重复最后一个。[gate] 非空时每次审查先等它放行。 */
    private fun coordinator(
        replies: List<String>,
        maxRounds: Int = 5,
        autoInject: Boolean = true,
        entered: CountDownLatch? = null,
        gate: CountDownLatch? = null,
    ): PeerCoordinator {
        val queue = ConcurrentLinkedQueue(replies)
        return PeerCoordinator(
            project = testProject(),
            projectPath = "/tmp/test-project",
            model = SessionListModel(scan = { emptyList() }, clock = Instant::now),
            viewOf = { null },
            coroutineScope = scope,
            shell = "/bin/zsh",
            edtDispatcher = Dispatchers.Unconfined,
            peerMaxRounds = { maxRounds },
            peerAutoInject = { autoInject },
            runCli = { _, _, _, prompt, _, _, _, _ ->
                prompts += prompt
                entered?.countDown()
                gate?.await(5, TimeUnit.SECONDS)
                if (queue.size > 1) queue.poll() else queue.peek()
            },
            resolveMcpConfig = { PeerMcpConfig(null, null, null) },
            buildPrompt = { _, _ -> "test prompt" },
            sendAutoFeedback = { _, _ -> true },
            onFeedbackUndelivered = { _, _ -> },
            onOneShotPassed = {},
        ).also { c -> c.addReviewListener(c) { events += it } }
    }

    private fun concluded() = events.count { it !is PeerReviewEvent.Started }

    /** 等到第 [n] 个结局（通过、意见、失败或中止）报告出来，且这一轮已收尾。 */
    private fun awaitConcluded(c: PeerCoordinator, n: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (concluded() >= n && c.status("s1")?.running != true) return
            Thread.sleep(10)
        }
        throw AssertionError("第 $n 个审查结局没有在时限内出现：${events.toList()}")
    }

    private fun <T> using(c: PeerCoordinator, block: (PeerCoordinator) -> T): T =
        try {
            block(c)
        } finally {
            c.dispose()
            scope.cancel()
        }

    @Test
    fun `确认即结对：审查还没结束就已处于结对状态，结束后仍在结对`() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        using(coordinator(listOf("PASS"), entered = entered, gate = gate)) { c ->
            c.reviewOnce("s1", AgentType.CODEX, instruction, keepPairing = true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(AgentType.CODEX, c.boundTarget("s1"))
            gate.countDown()
            awaitConcluded(c, 1)
            assertEquals(AgentType.CODEX, c.boundTarget("s1"))
            assertEquals(1, prompts.size)
        }
    }

    @Test
    fun `主链路：首次带要求，意见发送后主会话完成下一轮自动再审且不带要求`() =
        using(coordinator(listOf("请修复第 3 行", "PASS"))) { c ->
            c.reviewOnce("s1", AgentType.CODEX, instruction, keepPairing = true)
            awaitConcluded(c, 1)
            assertEquals(PeerOutcomeKind.FEEDBACK, (events.last() as PeerReviewEvent.Finished).outcome.kind)
            assertEquals(AgentType.CODEX, c.boundTarget("s1"))

            // 意见注入后主会话开工、做完
            c.onTurnStarted("s1")
            assertEquals(AgentType.CODEX, c.boundTarget("s1"))
            c.onTurnCompleted("s1")
            awaitConcluded(c, 2)

            val sent = prompts.toList()
            assertEquals(2, sent.size)
            assertTrue(sent[0], sent[0].contains(instruction))
            assertFalse(sent[1], sent[1].contains(instruction))
            assertEquals(PeerOutcomeKind.PASSED, (events.last() as PeerReviewEvent.Finished).outcome.kind)
            assertEquals(AgentType.CODEX, c.boundTarget("s1"))
        }

    @Test
    fun `轮次上限为 1：首次意见成功发送后不再追加审查`() =
        using(coordinator(listOf("请修复"), maxRounds = 1)) { c ->
            c.reviewOnce("s1", AgentType.CODEX, instruction, keepPairing = true)
            awaitConcluded(c, 1)
            c.onTurnStarted("s1")
            c.onTurnCompleted("s1")
            assertEquals(1, prompts.size)
            assertEquals(PeerReviewEvent.Stopped("s1", AgentType.CODEX, PeerStopReason.MAX_ROUNDS, maxRounds = 1), events.last())
        }

    @Test
    fun `首次通过后保持结对，不发起额外审查`() =
        using(coordinator(listOf("PASS"))) { c ->
            c.reviewOnce("s1", AgentType.CODEX, instruction, keepPairing = true)
            awaitConcluded(c, 1)
            Thread.sleep(100)
            assertEquals(AgentType.CODEX, c.boundTarget("s1"))
            assertEquals(1, prompts.size)
        }

    @Test
    fun `意见没能自动发送时不计轮次：上限为 1 时下一轮仍会审查`() =
        using(coordinator(listOf("请修复", "PASS"), maxRounds = 1, autoInject = false)) { c ->
            c.reviewOnce("s1", AgentType.CODEX, instruction, keepPairing = true)
            awaitConcluded(c, 1)
            c.onTurnStarted("s1")
            c.onTurnCompleted("s1")
            awaitConcluded(c, 2)
            assertEquals(2, prompts.size)
        }

    @Test
    fun `取消这一轮审查后保持结对`() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        using(coordinator(listOf("PASS"), entered = entered, gate = gate)) { c ->
            c.reviewOnce("s1", AgentType.CODEX, instruction, keepPairing = true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            c.cancelCurrentRun("s1")
            gate.countDown()
            Thread.sleep(100)
            assertEquals(AgentType.CODEX, c.boundTarget("s1"))
        }
    }

    @Test
    fun `审查期间停止结对：立即解绑，之后主会话完成新一轮也不再审查`() {
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        using(coordinator(listOf("PASS"), entered = entered, gate = gate)) { c ->
            c.reviewOnce("s1", AgentType.CODEX, instruction, keepPairing = true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            c.unbind("s1")
            assertNull(c.boundTarget("s1"))
            gate.countDown()
            Thread.sleep(100)
            c.onTurnStarted("s1")
            c.onTurnCompleted("s1")
            Thread.sleep(100)
            assertNull(c.boundTarget("s1"))
            assertEquals(1, prompts.size)
        }
    }

    @Test
    fun `不勾选时仍是单次审查：带要求，审查完解绑`() =
        using(coordinator(listOf("PASS"))) { c ->
            c.reviewOnce("s1", AgentType.CODEX, instruction)
            awaitConcluded(c, 1)
            Thread.sleep(100)
            assertTrue(prompts.single().contains(instruction))
            assertNull(c.boundTarget("s1"))
        }

    private fun testProject(): Project =
        Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
            when (method.name) {
                "getBasePath" -> "/tmp/test-project"
                "isDisposed" -> false
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "TestProject"
                else -> if (method.returnType == java.lang.Boolean.TYPE) false else null
            }
        } as Project
}
```

- [ ] **Step 2: 运行测试，确认因缺少参数而编译失败**

Run: `./gradlew test --tests '*PeerReviewOnceKeepPairingTest*' -q`
Expected: 编译失败，`No parameter with name 'keepPairing' found`。

- [ ] **Step 3: `PeerRun` 加本轮审查要求字段**

`PeerSessionGuard.kt` 中 `class PeerRun` 的 `@Volatile var passed: Boolean = false` 之后加：

```kotlin
    /** 本轮的审查要求（用户在“审查一次”里填的）；只属于这一轮，结对后续各轮为 null。 */
    @Volatile var instruction: String? = null
```

- [ ] **Step 4: 修改 `PeerCoordinator`**

1. `OneShotInfo` 去掉 `instruction`（要求改由 `PeerRun` 携带）：

```kotlin
    private data class OneShotInfo(val generation: Long)
```

2. `reviewOnce` 整体替换为：

```kotlin
    /**
     * 立即审查当前这一轮。[keepPairing] 为 false 是单次审查：跑完自动解绑。
     * 为 true 时等同于先开启普通结对、再手动跑一轮：不登记单次标记，之后的结束、取消、新一轮、迁移都走普通结对路径。
     * [userInstruction] 只挂在这一轮上。
     */
    fun reviewOnce(
        sessionKey: String,
        targetAgentType: AgentType,
        userInstruction: String?,
        keepPairing: Boolean = false,
    ) {
        ApplicationManager.getApplication()?.assertIsDispatchThread()
        if (disposed) return
        if (bindings.containsKey(sessionKey)) return
        bind(sessionKey, targetAgentType)
        val generation = bindings[sessionKey]?.generation ?: return
        if (!keepPairing) oneShotSessions[sessionKey] = OneShotInfo(generation)
        val guard = guards.computeIfAbsent(sessionKey) { PeerSessionGuard() }
        val result = guard.tryStart(generation, { bindings[sessionKey]?.generation }) ?: return
        result.cancelled?.killProcess()
        result.run.instruction = userInstruction
        LOG.info("结对编程：立即审查 sessionKey=$sessionKey agent=${targetAgentType.displayName} keepPairing=$keepPairing")
        announce(
            PeerReviewEvent.Started(
                sessionKey,
                targetAgentType,
                round = (roundCounts[sessionKey]?.get() ?: 0) + 1,
                oneShot = !keepPairing,
            ),
        )
        launchReview(sessionKey, result.run, guard)
    }
```

3. `runReviewAndInject` 中把

```kotlin
        val oneShotInstruction = oneShotSessions[mainSessionKey]?.instruction
```

改为

```kotlin
        val oneShotInstruction = run.instruction
```

（变量名保持不变，下方拼接提示词与 `outcome(...)` 的引用不用改。）

- [ ] **Step 5: 运行新测试与现有结对测试**

Run: `./gradlew test --tests '*PeerReviewOnceKeepPairingTest*' --tests 'com.github.izerui.imux.peer.*' --tests '*OneShotResultTraceTest*' --tests '*TerminalBannerHostSourceTest*' -q`
Expected: 全部 PASS。若 `PeerCoordinatorSourceTest` 有断言钉住 `oneShotSessions[mainSessionKey]?.instruction` 或旧的 `OneShotInfo(generation, userInstruction)` 文本，把断言同步为新文本（`run.instruction`、`OneShotInfo(generation)`），不改其语义。

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/peer/PeerSessionGuard.kt \
        src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt \
        src/test/kotlin/com/github/izerui/imux/peer/PeerReviewOnceKeepPairingTest.kt
git commit -m "结对编程：审查一次支持同时开启结对，审查要求改为只挂在本轮"
```

（若 Step 5 同步了 `PeerCoordinatorSourceTest`，一并 `git add` 该文件。）

---

### Task 2: 对话框勾选框与文案

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt`（`showReviewOnceDialog`、`ReviewOnceDialog`，约 850-910 行）
- Modify: `src/main/resources/messages/ImuxBundle*.properties`（10 个文件）
- Test: `src/test/kotlin/com/github/izerui/imux/terminal/TerminalBannerHostSourceTest.kt`

**Interfaces:**
- Consumes: `PeerCoordinator.reviewOnce(sessionKey, targetAgentType, userInstruction, keepPairing)`（Task 1）
- Produces: 文案键 `action.peer.once.input.keepPairing`

- [ ] **Step 1: 写失败的源码断言**

在 `TerminalBannerHostSourceTest` 类末尾加：

```kotlin
    @Test
    fun `审查一次对话框把“同时开启结对编程”的勾选结果交给协调器`() {
        assertTrue(editor.normalized.contains("coordinator.reviewOnce(file.sessionKey, dialog.selectedAgent, instruction, dialog.keepPairing)"))
        assertTrue(editor.normalized.contains("JBCheckBox(ImuxBundle.message(\"action.peer.once.input.keepPairing\"))"))
    }
```

（`editor` 是该测试类里已有的 `AgentTerminalFileEditor.kt` 源码字段；若名字不同，用类里现有的同一个字段。）

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests '*TerminalBannerHostSourceTest*' -q`
Expected: 新用例 FAIL。

- [ ] **Step 3: 加文案键（10 个文件）**

在每个文件的 `action.peer.once.input.agent=` 那一行下面插入一行：

| 文件 | 插入行 |
|---|---|
| `ImuxBundle.properties`、`_de`、`_es`、`_fr`、`_ja`、`_ko`、`_pt_BR`、`_ru` | `action.peer.once.input.keepPairing=Also turn on pair programming (review every turn automatically)` |
| `ImuxBundle_zh_CN.properties` | `action.peer.once.input.keepPairing=同时开启结对编程（之后每轮自动审查）` |
| `ImuxBundle_zh_TW.properties` | `action.peer.once.input.keepPairing=同時開啟結對編程（之後每輪自動審查）` |

用精确的单行插入（例如 Edit 工具以 `action.peer.once.input.agent=` 整行为锚点），不要整文件重写——这些文件可能有其他会话的未提交改动。

- [ ] **Step 4: 对话框加勾选框并传参**

`ReviewOnceDialog` 中，`instructionField` 声明下面加：

```kotlin
    private val keepPairingBox = JBCheckBox(ImuxBundle.message("action.peer.once.input.keepPairing"))
```

`val selectedAgent` 下面加：

```kotlin
    /** 每次打开默认不勾，不记忆。 */
    val keepPairing: Boolean get() = keepPairingBox.isSelected
```

`createCenterPanel()` 里，在 `panel.add(instructionField, gbc)` 之后、`return panel` 之前加：

```kotlin
        gbc.gridx = 0; gbc.gridy += 1; gbc.gridwidth = 2; gbc.weightx = 1.0
        panel.add(keepPairingBox, gbc)
```

`showReviewOnceDialog` 最后一行改为：

```kotlin
    coordinator.reviewOnce(file.sessionKey, dialog.selectedAgent, instruction, dialog.keepPairing)
```

文件顶部若无 `import com.intellij.ui.components.JBCheckBox` 则补上。

- [ ] **Step 5: 运行测试**

Run: `./gradlew test --tests '*TerminalBannerHostSourceTest*' --tests '*ImuxBundleTest*' --tests '*PeerReviewOnceKeepPairingTest*' -q`
Expected: 全部 PASS。

- [ ] **Step 6: IDE 检查**

对 `AgentTerminalFileEditor.kt`、`PeerCoordinator.kt`、`PeerSessionGuard.kt` 运行 `lint_files`，无新增错误。

- [ ] **Step 7: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt \
        src/main/resources/messages/ImuxBundle.properties \
        src/main/resources/messages/ImuxBundle_de.properties \
        src/main/resources/messages/ImuxBundle_es.properties \
        src/main/resources/messages/ImuxBundle_fr.properties \
        src/main/resources/messages/ImuxBundle_ja.properties \
        src/main/resources/messages/ImuxBundle_ko.properties \
        src/main/resources/messages/ImuxBundle_pt_BR.properties \
        src/main/resources/messages/ImuxBundle_ru.properties \
        src/main/resources/messages/ImuxBundle_zh_CN.properties \
        src/main/resources/messages/ImuxBundle_zh_TW.properties \
        src/test/kotlin/com/github/izerui/imux/terminal/TerminalBannerHostSourceTest.kt
git commit -m "审查一次对话框加“同时开启结对编程”勾选框"
```

提交前先 `git diff --cached` 确认 bundle 文件里只有新增的这一行；若混入其他会话的改动，停下来告知用户，不要提交。
