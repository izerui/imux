# 等待用户选择时标记未读

## 背景

Claude 弹出选项（权限确认、AskUserQuestion 对话框、需要输入）时，对话已经停止，只是在等用户操作。但插件此刻的表现是「运行中」：树里转菊花、标签页品牌图标呼吸、不标未读、不提醒。用户离开一会儿回来，看到的是一个仍在「执行」的会话，实际上它早就卡在那儿等着了。

Claude CLI 2.1.197 的运行态文件（`~/.claude/sessions/<pid>.json`）已经写下了这个状态，是一手信号，无需推断：

```
status ∈ { "busy", "shell", "idle", "waiting" }
waitingFor ∈ { "permission prompt", "dialog open", "input needed",
               "worker request", "sandbox request" }
```

而 `ClaudeRuntimeSession.isBusy` 当前是 `status != "idle" && status != "shell"`，于是 `waiting` 被归入忙碌。这是问题的全部成因。

## 目标

`waiting` 视作「这一轮停下了」：停止运行中动画、标记未读、弹一条说明等待原因的通知。

## 非目标

**不做 Codex 侧。** Codex 会话文件只有 `task_started` / `task_complete` / `turn_aborted`，实测 30 个会话文件的全部事件类型中没有任何审批相关记录，没有可靠信号可用。

**不新增第三种视觉状态。** 「跑完了」和「卡住等你」对用户的下一步动作都是「打开看看」，共用未读标记；区别只体现在通知文案上。

## 设计

### 1. 数据层：`ClaudeRuntimeSession` / `ClaudeRuntimeIndex`

```kotlin
val waitingFor: String?          // 新增，由 ClaudeRuntimeIndex.readOne 读取

val isWaiting: Boolean get() = status == WAITING

// 唯一改动：多排除一个 WAITING
val isBusy: Boolean get() =
    status != null && status != "idle" && status != SHELL && status != WAITING

val isOccupied: Boolean get() = status != null && status != "idle"   // 不变
```

`isOccupied` 保持原样：`waiting` 本来就 `!= "idle"`，「进程占着会话、resume 会被 CLI 拒绝」的判定天然正确。

`RunningSessions.of` 不改。它读 `isBusy`，语义自动跟随——`waiting` 不再算运行中，转菊花与品牌图标呼吸自动停止。

### 2. 判定层：`RuntimeStatusTracker`

返回值由 `List<String>` 改为结构体，两类事件分开：

```kotlin
data class TurnOutcome(
    val completed: List<String>,
    val waiting: List<WaitingSession>,
)

data class WaitingSession(val sessionId: String, val reason: String?)
```

状态机规则：

| 跃迁 | 产出 | `busySince` |
|---|---|---|
| `busy → waiting` | waiting 事件 | 保留 |
| `waiting → busy` | 无 | 保留，不重置起点 |
| `waiting → idle` | completed | 用原起点算耗时，然后清除 |
| `busy → idle` | completed（原有行为） | 原有行为 |
| 首次见到即 `waiting` | 无 | 不记 |

**计时起点必须在 `waiting` 期间保留。** 这是本设计不污染原有行为的关键：判「完成」的依据是 `isBusy` 由真变假，若 `waiting` 顺手清掉起点，一轮 `busy(5s) → waiting → busy(10s) → idle` 报出的耗时会从 15s 缩水成 10s。保留起点等于把两种语义拆开——标记语义认为 `waiting` 是停止，计时语义认为这一轮仍在继续。

**首次见到即 `waiting` 不提醒也不标记**，与现有「首次观察到就是 idle 的会话不提醒」规则对称。插件启动时不该把一批早就卡住的历史会话集体标成未读。

内部新增 `waitingIds: MutableSet<String>` 记录上一拍的 waiting 集合，用于识别「进入」这一跃迁；持续处于 `waiting` 不重复产出事件。首次见到即 `waiting` 的会话也记入该集合，只是不产出事件——它随后转 `busy` 时被移除，再次进入 `waiting` 就是一次真实跃迁，照常提醒。

会话从快照中消失（进程退出）时，`waitingIds` 与现有的 `busySince`、`seen` 一同 `retainAll` 清理，且不产出任何事件——沿用现有的「会话消失不提醒」规则。

### 3. 通知层：`WaitingSubtitle.kt`（新文件）与 `TurnNotifier.notifyWaiting`

与现有 `CompletionSubtitle.kt` 对称的纯函数：

```kotlin
"permission prompt" -> "等待权限确认"
"dialog open"       -> "等待你的选择"
"input needed"      -> "等待输入"
else                -> "等待你的确认"
```

`worker request` 与 `sandbox request` 走兜底：属于子代理与沙箱场景，日常几乎不出现，单列文案不会被读到。未知取值同样落到兜底，CLI 将来新增取值不会显示成空白。

`notifyWaiting` 复用现有气泡结构——同一个 `GROUP_ID`、同一张 `active` 去重表、同样的「打开会话」动作、同样由 `notifyOutsideIde` 在窗口不在前台时补系统通知。两点不同：

- 副标题用上述映射，**不报耗时**（这一轮并未结束，报出的任何数字都是错的）
- **该会话正是当前选中标签页时不弹气泡**（`FileEditorManager.selectedFiles` 判断），未读标记照常打

第二点与「完成」通知的规则刻意相反。现有代码注释写明完成时即使正在查看也要提醒，理由是「tab 选中不等于人在屏幕前」；但 `waiting` 不同——CLI 的选择框就占在屏幕上，再弹一个 IDE 气泡属于重复告知。同时它顺带解决了连环气泡：一轮里连续几次权限确认会产生 `waiting → busy → waiting`，用户正在屏幕前逐个确认，不该每次都被打扰。

### 4. 消费层：`SessionMonitor.checkCompletedTurns`

```kotlin
val outcome = statusTracker.completedSince(snapshot)
val completed = (outcome.completed + watcher.poll()).distinct()
// 原有 completed 循环不动
outcome.waiting.forEach { markUnread(it.sessionId); 条件通知 }
```

未读标记集合、窗口标题星号、树图标、终端输入事件清除未读——全部原样复用，不改一行。

## 影响面

不受影响：

- `busy` / `shell` / `idle` 的判定
- `isOccupied` 与 resume 拦截
- Codex 整条链路（`TurnWatcher`、`TurnSignalParser`）
- 未读标记的消除机制
- 轮次耗时的统计结果

有意变化：`waiting` 期间不再显示运行中动画，改为未读标记加一条说明原因的通知。

## 测试

- `RuntimeStatusTrackerTest`：补全五种跃迁，其中 `busy → waiting → busy → idle` 必须报出**完整**耗时——这条直接锁住「不污染原有行为」
- `WaitingSubtitle`：文案映射，含未知取值兜底
- `ClaudeRuntimeIndex`：解析 `waitingFor`，以及 `isBusy` 排除 `waiting`、`isOccupied` 仍为真

## 改动清单

| 文件 | 性质 |
|---|---|
| `session/ClaudeRuntimeIndex.kt` | 修改 |
| `turn/RuntimeStatusTracker.kt` | 修改 |
| `turn/TurnNotifier.kt` | 修改 |
| `monitor/SessionMonitor.kt` | 修改 |
| `turn/WaitingSubtitle.kt` | 新增 |
