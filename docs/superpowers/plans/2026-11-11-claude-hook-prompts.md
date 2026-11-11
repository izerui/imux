# Claude 确认框走 PermissionRequest hook 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Claude 会话里的权限确认、提问、计划确认（含子代理）同时推到手机和电脑，任一端先答即生效，另一端作废并互相告知结果。

**Architecture:** 启动 Claude 时用 `--settings` 注入指向 IDE 内置服务的 http hook（`PermissionRequest`、`PostToolUse`、`PostToolUseFailure`）。新入口 `ClaudeHookHandler` 把请求解析成 core 层的 `HookRequest`，交给 `BridgeRouter` 按标签排队推送；手机回复译成 `HookDecision` 回写 hook 响应，不再按键。电脑端作答经 `PostToolUse`（允许）或连接关闭（拒绝）得知。Codex、Pi 与未注入 hook 的 Claude 标签走原有按键路径不变。

**Tech Stack:** Kotlin、IntelliJ Platform 262（内置 Netty HTTP 服务 `HttpRequestHandler`）、Gson、kotlinx.coroutines、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-11-11-claude-hook-prompts-design.md`（实测结论表、§1–§7 是本计划的依据，执行者两份都读）

## Global Constraints

- 只能用 JUnit 4；测试方法用中文反引号命名；名字里有「都 / 各 / 两 / 一律 / 全部」等全称词时，每一支各有独立断言（AGENTS.md）。
- `bridge/core` 不依赖 Gson 与 IDE；JSON 解析与序列化只在 `bridge/mcp`、`bridge/transcript`、`terminal`。
- 回给 hook 的 decision **一律不带 `interrupt`**（实测 5）。
- 给 Claude 的拒绝原因用固定英文：`The user denied this tool call from their phone.`、`The user denied this tool call from their phone and said: <原话>`、`The user does not want to proceed with this plan yet.`
- 多选答案以 `", "` 连接选项 `label` 原文（实测 9）。
- 计划批准：选 1 → `setMode` `auto`；选 2 → `setMode` `default`；`destination` 均为 `session`（实测 11、12）。
- hook 超时：`PermissionRequest` 86400 秒，`PostToolUse`/`PostToolUseFailure` 5 秒。
- 令牌经 `"headers": {"Authorization": "Bearer $IMUX_MOBILE_TOKEN"}` + `"allowedEnvVars": ["IMUX_MOBILE_TOKEN"]` 传递，不写进命令行。
- 新增文案 10 个语言包同步（`src/main/resources/messages/ImuxBundle*.properties`）。
- 不改任何用户配置文件；Codex、Pi 的启动命令一个字不变。

## Review Focus

1. **两端几乎同时作答**：手机的 decision 晚于电脑到达，任何 decision 都不得带 `interrupt`，否则会打断 Claude 下一步 → Task 5 `ClaudeHookJsonTest` 对每种 decision 断言无 `interrupt`。
2. **后台子代理在主会话本轮结束后请求权限**：本轮结束不得清掉挂起请求，否则手机收不到 → Task 4 `本轮结束后后台子代理的权限请求照常推送`。
3. **并行子代理调用了参数完全相同的工具**：电脑端允许其中一个，只了结对应代理的那一个 → Task 5 `matchKey 含代理 id`、Task 4 `参数相同的两个请求只了结最早的一个`。
4. **请求挂起时解绑或换绑**：所有挂起请求立即回 `{}`，之后手机的话按未绑定处理，不会答到新会话 → Task 4 `解绑时挂起的请求都放行给电脑`。
5. **手机带图片回复挂起的确认**：不作答、提示不能带附件，请求仍挂起可再答 → Task 4 `带附件的回复不作答`。

---

### Task 1: 抽出 Claude 工具参数解析

记录解码与 hook 入口要对同一份 `tool_input` 得出同样的「要批准的内容」与题目，先把解析从 `ClaudeTranscriptDecoder` 里抽成共用函数。

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/transcript/ClaudeToolInputs.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/transcript/TranscriptDecoders.kt:155-188`（`toolCalled`、`questionAsked` 改为调用新函数；`MAX_TOOL_DETAIL`、`MAX_PLAN_DETAIL` 搬过去）
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/transcript/ClaudeToolInputsTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object ClaudeToolInputs {
      /** 要批准的内容，规则与截短长度同原 toolCalled：计划 8000，其它 1500。 */
      fun detail(name: String, input: JsonObject): String
      /** 题目与选项；任何一题缺题目或选项返回 null。 */
      fun questions(input: JsonObject): List<AskedQuestion>?
  }
  ```

- [ ] **Step 1: 写失败测试** `ClaudeToolInputsTest`：
  - `命令显示说明和命令两行`：`detail("Bash", {"command":"rm -rf x","description":"删"})` == `"删\nrm -rf x"`
  - `计划取全文且按 8000 截短`：9000 字的 plan → 长度 8000、以 `…` 结尾
  - `其它工具依次取文件路径、网址、查询，否则参数原文`：`{"file_path":"/a"}` → `"/a"`；`{"x":1}` → `"{\"x\":1}"`
  - `缺选项的提问返回 null`：一题有选项、一题 `options: []` → null
  - `多选标记与选项说明保留`：`multiSelect: true`、option 带 description → 对应 `AskedQuestion`
- [ ] **Step 2: 运行确认失败** `./gradlew test --tests 'com.github.izerui.imux.bridge.transcript.ClaudeToolInputsTest'` → 编译失败（类不存在）
- [ ] **Step 3: 实现** 把 `toolCalled` 的 detail 分支和 `questionAsked` 的解析原样搬进 `ClaudeToolInputs`，解码器改为调用。
- [ ] **Step 4: 运行** 上述测试与 `./gradlew test --tests 'com.github.izerui.imux.bridge.transcript.*'` → 全 PASS（既有解码测试不变）
- [ ] **Step 5: 提交** `git commit -m "抽出 Claude 工具参数解析，供记录解码与 hook 共用"`

---

### Task 2: core 层的 hook 请求、决定与回复翻译

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/core/HookPrompts.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/core/AskedQuestions.kt`（加 `hookAnswer`）
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/core/PermissionPrompts.kt`（`render` 加可选来源）
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/core/HookPromptsTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  /** 一次 PermissionRequest。[id] 由入口分配；[matchKey] 用来对上随后的 PostToolUse。 */
  data class HookRequest(
      val id: Long, val toolName: String, val agentType: String?,
      val detail: String, val questions: List<AskedQuestion>?, val matchKey: String,
  ) { val kind: PromptKind }   // AskUserQuestion → QUESTION，ExitPlanMode → PLAN，其余 → PERMISSION

  sealed interface HookDecision {
      data object Pass : HookDecision                       // 回 {}：交给电脑
      data class Allow(val answers: Map<String, String>? = null, val mode: String? = null) : HookDecision
      data class Deny(val message: String) : HookDecision
  }

  object HookReplies {
      fun permission(reply: String): HookDecision
      fun plan(reply: String): HookDecision
  }

  // AskedQuestions
  /** 回给 hook 的这题答案：编号对得上选项给 label（多选以 ", " 连接），否则原话；空白返回 null。 */
  fun hookAnswer(question: AskedQuestion, reply: String): String?

  // PermissionPrompts
  internal fun render(call: ToolCall, agentType: String? = null): String   // 有来源时首行为 "**Bash** · general-purpose"
  ```
  `PromptKind` 需从 `internal` 改为 `public`（`HookRequest.kind` 公开）。

- [ ] **Step 1: 写失败测试** `HookPromptsTest`：
  - `权限：允许类回复只允许这一次`：`permission("好")` == `Allow()`（answers、mode 均为 null）
  - `权限：拒绝类与其他编号都拒绝且说明来自手机`：`permission("拒绝")`、`permission("3")` 各断言 == `Deny("The user denied this tool call from their phone.")`
  - `权限：其他说法拒绝并附上原话`：`permission("改用 git rm")` == `Deny("The user denied this tool call from their phone and said: 改用 git rm")`
  - `计划：1 与 2 各切到对应模式`：`plan("1")` == `Allow(mode = "auto")`；`plan("b")` == `Allow(mode = "default")`
  - `计划：不执行与其他说法各自拒绝`：`plan("不执行")` == `Deny("The user does not want to proceed with this plan yet.")`；`plan("文件名换成 a.txt")` == `Deny("文件名换成 a.txt")`
  - `提问：多选编号以逗号空格连接选项原文`：选项 苹果/香蕉/橙子 多选，`hookAnswer(q, "1 3")` == `"苹果, 橙子"`
  - `提问：单选编号给选项原文，自定义文字原样`：`hookAnswer(q, "2")` == `"绿"`；`hookAnswer(q, "紫色")` == `"紫色"`；`hookAnswer(q, "  ")` == null
  - `种类按工具名区分`：三种 toolName 各断言 `kind`
  - `子代理来源写在工具名后`：`render(ToolCall("Bash","ls"), "general-purpose")` 首行 == `"**Bash** · general-purpose"`
- [ ] **Step 2: 运行确认失败** `./gradlew test --tests 'com.github.izerui.imux.bridge.core.HookPromptsTest'`
- [ ] **Step 3: 实现** 词表复用 `PermissionPrompts` 的 `ALLOW`/`DENY`/`OPTION_LIKE`（改为 `internal`）；`hookAnswer` 复用 `AskedQuestions` 现有的 `picks`/`joinedDigits`，只把连接符换成 `", "`。
- [ ] **Step 4: 运行** 本测试与 `PermissionPromptsTest`、`AskedQuestionsTest` → PASS
- [ ] **Step 5: 提交** `git commit -m "core 增加 hook 请求、决定与手机回复翻译"`

---

### Task 3: 启动命令支持 `--settings`

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/TerminalLaunchContributions.kt`（`TerminalLaunchContribution` 加 `claudeSettings`；`prepare` 合并）
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt`（`launchCommand` 加参数；新函数 `claudeHookSettings`）
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt:754-791`（两处 `launchCommand` 传 `claudeSettings = launchContributions[tabId]?.claudeSettings`）
- Test: `src/test/kotlin/com/github/izerui/imux/terminal/MobileMcpLaunchTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class TerminalLaunchContribution(..., val claudeSettings: String? = null, ...)   // 合并时取第一个非 null
  internal fun claudeHookSettings(url: String, tokenEnvironment: String): String    // spec §1 的 JSON
  internal fun launchCommand(..., claudeSettings: String? = null): List<String>
  ```
  `--settings <值>` 只在 `AgentType.CLAUDE` 时拼，紧跟 MCP 参数之后、`--resume` 与 `--` 之前，值用 `quoteEmbeddingDoubleQuotes`。

- [ ] **Step 1: 写失败测试**（加到 `MobileMcpLaunchTest`）：
  - `hook 设置含三种事件且凭证保持环境引用`：解析 `claudeHookSettings("http://127.0.0.1:63342/imux/claude-hook", "IMUX_MOBILE_TOKEN")`：`PermissionRequest`、`PostToolUse`、`PostToolUseFailure` 各断言 url、`headers.Authorization == "Bearer $IMUX_MOBILE_TOKEN"`、`allowedEnvVars == ["IMUX_MOBILE_TOKEN"]`；timeout 分别为 86400、5、5
  - `Claude 新建与恢复都带 --settings 且在 --resume 之前`：fresh 含 `--settings '`；restored 中 `--settings` 下标 < `--resume` 下标
  - `POSIX 与 PowerShell 都保留 hook 的变量占位符`：两种方言各断言含 `$IMUX_MOBILE_TOKEN`，PowerShell 含 `[char]34`
  - `Codex 与 Pi 各自不带 --settings`：两者各一条断言
  - `带初始 prompt 时 --settings 在分隔符之前`：`initialPrompt = "说 hi"` → `--settings` 下标 < `" -- "` 下标
- [ ] **Step 2: 运行确认失败** `./gradlew test --tests 'com.github.izerui.imux.terminal.MobileMcpLaunchTest'`
- [ ] **Step 3: 实现** 按上面的接口；用 Gson 生成 JSON。
- [ ] **Step 4: 运行** `MobileMcpLaunchTest`、`AgentCommandTest` → PASS
- [ ] **Step 5: 提交** `git commit -m "Claude 启动命令支持注入会话级 --settings"`

---

### Task 4: 路由器排队、推送、作答与了结

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/core/HookPromptQueue.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeRouter.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgePorts.kt`（`BridgeTexts` 新方法）
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/BundleBridgeTexts.kt`
- Modify: `src/main/resources/messages/ImuxBundle*.properties`（10 个）
- Modify: `src/test/kotlin/com/github/izerui/imux/bridge/core/BridgeRouterTest.kt`（`Texts` 假实现补新方法）
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/core/HookPromptRouterTest.kt`（夹具照 `BridgeRouterTest` 前 170 行精简复制；`FakePort` 加记录 `press` 调用的 `pressed` 列表；假文案 `hookPermission(l) = "HOOK_PERMISSION($l)"`，其余新方法返回大写方法名，如 `HOOK_ALLOWED`、`HOOK_QUEUED(1)`）

**Interfaces:**
- Consumes: Task 2 的 `HookRequest`、`HookDecision`、`HookReplies`、`AskedQuestions.hookAnswer`、`PermissionPrompts.render(call, agentType)`
- Produces（`BridgeRouter`）：
  ```kotlin
  /** 标签的 Claude 启动时注入了 hook（true）或会话结束（false，同时放行它挂起的请求）。 */
  fun hookAttached(tabId: String, attached: Boolean)
  /** 未绑定立即 respond(Pass)；否则入队，成为队首时推送。respond 只会被调用一次。 */
  suspend fun onHookRequest(tabId: String, request: HookRequest, respond: (HookDecision) -> Unit)
  /** 电脑端允许后工具已跑完：了结 matchKey 相同的最早请求，respond(Pass)。[answers] 是提问的「题目 → 答案」行。 */
  suspend fun onHookToolDone(tabId: String, matchKey: String, answers: String?)
  /** hook 连接被关闭（电脑端拒绝或 Esc）：了结该请求，不再 respond。 */
  suspend fun onHookClosed(tabId: String, id: Long)
  ```
- Produces（`BridgeTexts` 新方法与 zh_CN 文案）：

  | 方法 | key | zh_CN |
  |---|---|---|
  | `hookPermission(listing)` | `bridge.status.hookPermission` | 需要你批准：\n\n{0}\n\n回「1」或「允许」只允许这一次，回「拒绝」不执行；也可以直接说你的要求，Claude 会放弃这一步、按你的话换个做法。手机上不能永久授权，需要的话请到 IDE 中选择。 |
  | `hookQueued(count)` | `bridge.status.hookQueued` | 还有 {0} 个确认在排队，答完这个接着发。 |
  | `hookAllowed()` | `bridge.reply.hookAllowed` | 已允许。 |
  | `hookDenied()` | `bridge.reply.hookDenied` | 已拒绝，Claude 会换个做法。 |
  | `hookAnswersSubmitted()` | `bridge.reply.hookAnswersSubmitted` | 答案已提交。 |
  | `hookPlanApproved(auto)` | `bridge.reply.hookPlanApproved.auto` / `.manual` | 已批准计划，切到自动模式。 / 已批准计划，改文件时会逐个确认。 |
  | `hookPlanRevising()` | `bridge.reply.hookPlanRevising` | 已转告 Claude。 |
  | `hookAllowedLocally()` | `bridge.status.hookAllowedLocally` | 电脑端已允许。 |
  | `hookDeniedLocally()` | `bridge.status.hookDeniedLocally` | 电脑端已拒绝。 |

**行为（实现依据，测试逐条覆盖）：**
- 队列按标签、按到达顺序；只推队首；推送时队列还有 N>0 个在后面，正文后接 `"\n\n" + hookQueued(N)`。权限用 `fitted(binding, render(...), texts::hookPermission)`；计划用 `fitted(..., texts::plan)`；提问逐题用现有 `texts.question(AskedQuestions.render(...), i+1, total)`。
- `onInbound`：提交锁内先看 hook 队首，有则作答，不走 `shownPrompt` 与按键；带附件 → `PromptRejected`。权限、计划：respond 后出队，回一句确认，推下一个。提问：记下这题，不是最后一题就推下一题；最后一题 respond `Allow(answers = 各题原文 → 答案)`，回 `hookAnswersSubmitted()`。确认与推送都在锁外发（新增 `SubmitPhase.Hook(binding, texts: List<String>)`）。
- `onHookToolDone`：已推过的才告诉手机：权限 `hookAllowedLocally()`；提问 `promptAnsweredLocally(answers)`；计划 `promptAnsweredLocally(null)`。没推过的静默出队。
- `onHookClosed`：已推过的发 `hookDeniedLocally()`，没推过的静默出队。
- 放行全部（逐个 respond(Pass)、不发消息）：`onTranscriptRestarted`、`onRebound` 中被释放的绑定、`onReleased`、`discardPending`、`hookAttached(tabId, false)`。本轮结束、中断**不**放行。
- 注入了 hook 的标签：`onWaiting` 在队列非空时什么也不发；队列为空时只发 `texts.waiting(reason)`，不调 `pushWaitingSubject`；`onSessionEvents` 末尾也不调 `pushWaitingSubject`。未注入的标签行为完全不变。

- [ ] **Step 1: 写失败测试** `HookPromptRouterTest`（`respond` 用列表收集）：
  - `未绑定的标签立即放行`：respond 收到 `[Pass]`，手机无消息
  - `权限请求推送内容与来源，回 1 后允许并确认`：推送为 `HOOK_PERMISSION(...)` 且含 `**Bash** · general-purpose`；手机回 `1` → respond `[Allow()]`，手机收 `HOOK_ALLOWED`；`port.pressed` 为空
  - `其他说法拒绝并把原话交给 Claude`：respond `[Deny("...and said: 改用 git rm")]`，`port.submitted` 为空
  - `两个请求只推队首并标出排队数，答完推下一个`：第一条推送含 `hookQueued(1)`；答完后第二个请求被推送
  - `提问一题一题答，最后一次性提交答案`：两题，回 `2`、`1 3` → 第一次回答后 respond 为空且推第 2 题；第二次后 respond `[Allow(answers = {"颜色？": "绿", "水果？": "苹果, 橙子"})]`
  - `计划回 2 批准并切手动模式`：respond `[Allow(mode = "default")]`，手机收 `hookPlanApproved(false)`
  - `电脑端允许后告诉手机且不重复`：推送后 `onHookToolDone(key, null)` → respond `[Pass]`、手机收 `hookAllowedLocally()`；随后手机回 `1` 按普通消息提交（不再命中 hook）
  - `提问在电脑端答完，手机收到答了什么`：`onHookToolDone(key, "颜色？ → 红")` → 手机收 `ANSWERED_LOCALLY(颜色？ → 红)`
  - `电脑端拒绝后告诉手机，不再回写`：`onHookClosed(id)` → 手机收 `hookDeniedLocally()`，respond 为空
  - `手机作答后电脑端结果到达不再通知`：手机回 `1` 后再 `onHookToolDone(key, null)` → 手机只多出 `hookAllowed()` 一条
  - `参数相同的两个请求只了结最早的一个`：同 matchKey 两个请求，`onHookToolDone` 一次 → 只有第一个 respond `[Pass]`，第二个被推送
  - `本轮结束后后台子代理的权限请求照常推送`：先 `onSessionEvents(TurnEnded)`，再 `onHookRequest` → 被推送；再来一次 `TurnEnded` → respond 仍为空
  - `解绑时挂起的请求都放行给电脑`：两个挂起请求，`registry.unbind` + `onReleased` → 两个 respond 各为 `[Pass]`；之后手机发话得 `UNBOUND`
  - `带附件的回复不作答`：返回 `PROMPT_REJECTED`，respond 为空；随后回 `1` 正常允许
  - `注入 hook 的标签有挂起请求时等待通知不提示`：`hookAttached(true)` + 挂起请求 + `onWaiting("permission prompt")` → 无新消息
  - `注入 hook 的标签不再按记录推权限框`：`hookAttached(true)`，记录 `ToolCalled` + `onWaiting("permission prompt")` → 只收到 `WAIT(permission prompt)`，不含 `PERMISSION(`
  - `未注入 hook 的标签照旧按记录推送`：同上不调 `hookAttached` → 收到 `PERMISSION(`
- [ ] **Step 2: 运行确认失败** `./gradlew test --tests 'com.github.izerui.imux.bridge.core.HookPromptRouterTest'`
- [ ] **Step 3: 实现** `HookPromptQueue`（`add`、`head`、`remove(id)`、`matchLocal(matchKey)` 取最早、`drain()`、`size`；条目持 `request`、`respond`、`pushed`、已答题目）与路由器方法；队列与「已注入」集合放在路由器的 `lock` 下，与 `tabs` 分开存放（`discardPending` 时先 drain 再清）。
- [ ] **Step 4: 补文案** `BridgeTexts`、`BundleBridgeTexts`、10 个语言包；`BridgeRouterTest.Texts` 补假实现。
- [ ] **Step 5: 运行** `./gradlew test --tests 'com.github.izerui.imux.bridge.*'` → 全 PASS
- [ ] **Step 6: 提交** `git commit -m "手机桥接按 hook 排队推送 Claude 确认框，手机与电脑先答者生效"`

---

### Task 5: hook 入口、JSON 与接线

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/mcp/ClaudeHookJson.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/mcp/ClaudeHookHandler.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/mcp/LocalMcpHandler.kt`（抽出本机 + Bearer 校验）
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/mcp/MobileLaunchContributor.kt`
- Modify: `src/main/resources/META-INF/plugin.xml:187`（注册 handler）
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/mcp/ClaudeHookJsonTest.kt`

**Interfaces:**
- Consumes: Task 1 `ClaudeToolInputs`；Task 2 `HookRequest`/`HookDecision`；Task 3 `claudeHookSettings`、`TerminalLaunchContribution.claudeSettings`；Task 4 路由器四个方法
- Produces:
  ```kotlin
  const val CLAUDE_HOOK_PATH = "/imux/claude-hook"
  internal object ClaudeHookJson {
      /** 解析不出（提问缺题目或选项）返回 null，入口直接回 {}。 */
      fun request(body: JsonObject, id: Long): HookRequest?
      /** agent_id + tool_name + 去掉 answers 键、键名递归排序后的 tool_input。 */
      fun matchKey(body: JsonObject): String
      /** Pass → {}；Allow → updatedInput 为原 tool_input（提问加 answers）、mode 非空时加 setMode；Deny → message。 */
      fun response(decision: HookDecision, toolInput: JsonObject): JsonObject
      /** PostToolUse 的 tool_response.answers 排成「题目 → 答案」行，没有则 null。 */
      fun localAnswers(toolResponse: JsonElement?): String?
  }
  /** LocalMcpHandler 抽出：本机、本端口、Origin 合法时返回 Bearer 令牌，否则 null。 */
  internal fun loopbackBearer(request: FullHttpRequest, context: ChannelHandlerContext): String?
  class ClaudeHookHandler : HttpRequestHandler()
  ```

**入口流程：** 令牌 → `MobileMcpService.getInstance().callers.get(token)?.tabId`，无效 403；只收 `application/json` POST，请求体上限 1 MB（计划全文可能较长，MCP 入口的 64 KB 不够）；按 `hook_event_name`：
- `PostToolUse`/`PostToolUseFailure`：先回 `{}`，再 `router.onHookToolDone(tabId, matchKey(body), localAnswers(body["tool_response"]))`。
- `PermissionRequest`：`id` 取自 handler 内 `AtomicLong`；`request(...)` 为 null 回 `{}`；否则登记 `context.channel().closeFuture()` → `router.onHookClosed(tabId, id)`，再 `router.onHookRequest(tabId, req) { respond(200, response(it, toolInput)) }`。异步回写与 `LocalMcpHandler` 一致（`scope.launch`，`channel.isActive` 检查）。
- 其余事件回 `{}`。

`MobileLaunchContributor.prepare`：`agentType == CLAUDE` 时 `claudeSettings = claudeHookSettings("http://127.0.0.1:$port$CLAUDE_HOOK_PATH", "IMUX_MOBILE_TOKEN")`，并 `BridgeHub.getInstance().router.hookAttached(tabId, true)`；release 回调里 `hookAttached(tabId, false)`。

- [ ] **Step 1: 写失败测试** `ClaudeHookJsonTest`：
  - `任何决定的响应都不带 interrupt`：Pass、Allow()、Allow(answers)、Allow(mode)、Deny 五种各断言序列化后不含 `interrupt`
  - `允许提问时原样带回题目并加答案`：`updatedInput.questions` 等于原值，`answers` 等于给定 map
  - `计划批准带 setMode 且只作用于本会话`：`updatedPermissions == [{"type":"setMode","mode":"auto","destination":"session"}]`，`updatedInput` 等于原 tool_input
  - `拒绝只带 behavior 与 message`
  - `matchKey 含代理 id`：同 tool_input、不同 `agent_id` → key 不同
  - `matchKey 忽略 answers 与键顺序`：`{"questions":Q}` 与 `{"answers":A,"questions":Q}`（键顺序打乱）→ key 相同
  - `子代理请求带来源与要批准的内容`：body 含 `agent_type: general-purpose`、Bash command → `agentType`、`detail`、`kind == PERMISSION`
  - `提问缺选项解析为 null`
  - `电脑作答的答案排成行`：`{"answers":{"颜色？":"红","水果？":"苹果, 橙子"}}` → `"颜色？ → 红\n水果？ → 苹果, 橙子"`
- [ ] **Step 2: 运行确认失败** `./gradlew test --tests 'com.github.izerui.imux.bridge.mcp.ClaudeHookJsonTest'`
- [ ] **Step 3: 实现** `ClaudeHookJson`、`loopbackBearer`（`LocalMcpHandler` 改用它，行为不变）、`ClaudeHookHandler`、`MobileLaunchContributor`、`plugin.xml`。
- [ ] **Step 4: 运行** `./gradlew test` → 全 PASS；`./gradlew buildPlugin` 成功
- [ ] **Step 5: 提交** `git commit -m "新增 Claude hook 入口并在启动时注入"`

---

### Task 6: 端到端验证与收尾

用 `./gradlew runIde` 起沙箱 IDE，绑定一个可用的手机机器人（飞书或企业微信任一），在 imux 里开 Claude 标签。

- [ ] **Step 1: 主流程各跑一遍，手机先答、电脑先答各一次**：主会话权限；前台子代理权限；后台子代理权限；两个并行子代理同时请求（排队数）；单选 + 多选 + 自定义文字提问；计划选 1、2、改意见。每项核对：两端同时出现；先答一端生效；另一端收到对应提示；无重复消息。
- [ ] **Step 2: 补测 spec「实现前需再验证」2–6**：
  - 退出 IDE 后已开着的 Claude 会话：hook 请求失败，确认电脑照常弹框、只有一条非阻塞错误
  - `--dangerously-skip-permissions` 与 auto 模式下的计划确认选项；若与 default 模式不同，按实际选项调整 `HookReplies.plan` 并补测试
  - 电脑已作答后晚到的 `Allow` + `updatedInput`（提问、计划）无副作用
  - 电脑上提问选「Chat about this」、计划选 3 时连接是否关闭；不关闭则记入 spec 并确认手机侧在 `PostToolUseFailure` 或本轮结束后不会一直挂着
  - `httpHookAllowedEnvVars` / `allowedHttpHookUrls` 限制下降级为只走电脑
- [ ] **Step 3: 未注入 hook 的回归**：Codex 标签权限框、改造前已开着（未重启）的 Claude 标签，手机侧行为与改造前一致。
- [ ] **Step 4: 文档** 把 Step 2 的结论写回 spec 实测表；`docs/guide.md` 手机桥接一节补「子代理的确认也能在手机上回答」「拒绝后 Claude 在同一轮里换个做法」。
- [ ] **Step 5: 提交** `git commit -m "Claude hook 确认框端到端验证结论与用户指南"`
