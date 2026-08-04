# 会话完成提醒 设计

日期：2026-08-04
状态：已确认，待编写实现计划

## 1. 目标

当一个 claude/codex 会话的对话轮次跑完（agent 给出最终回复、等待用户输入）时，在左侧列表标记该会话并弹出 IDE 通知，提醒用户回来查看。

典型场景：交代一个耗时任务给 agent，切去干别的，跑完了希望被叫回来。

## 2. 为什么不复用之前的运行状态标记

此前实现过「进程是否存活」的三态标记，两次修复均告失败，已整体移除（见 `2026-08-03-imux-design.md` 的 8.2）。失败根因是判断依据落在**终端运行时状态**上：先是只看「插件建过 view 没有」，后改用 `TerminalView.sessionState` 仍不对，且始终无法离线验证。

本设计的判断依据完全落在**会话文件**上，不碰终端状态。核心逻辑是「给定新追加的若干行，输出状态跃迁」的纯函数，可完整单测——这是与上次最实质的区别。

## 3. 已验证的信号

以下结论基于本机真实数据统计，不是单样本推断。

### 3.1 Claude（样本：71 个会话、8535 条 assistant 记录）

`stop_reason` 的实际分布：

| 取值 | 次数 |
|---|---|
| `tool_use` | 7989 |
| `end_turn` | 504 |
| `stop_sequence` | 37 |
| `null` | 5 |

**判定规则采用黑名单而非白名单**：`stop_reason == "tool_use"` 视为进行中，**其余一律视为已完成**。

理由：若按白名单只认 `end_turn`，则以 `stop_sequence` 收尾的会话（71 个中有 7 个）永远不会触发提醒。白名单会漏，黑名单才稳。

另外，`user` 类型记录（工具结果回填）视为进行中。统计显示 71 个会话中有 23 个以 `user` 记录收尾，属于「中途放弃」的会话；它们会停在进行中态而永不触发提醒——这是漏报而非误报，可接受。

### 3.2 Codex（样本：150 个会话）

`event_msg` 的 `payload.type` 中与轮次相关的：

| 取值 | 次数 | 含义 |
|---|---|---|
| `task_started` | 1252 | 轮次开始 |
| `task_complete` | 1217 | 轮次正常完成 |
| `turn_aborted` | 31 | 轮次被中断（用户按 Esc） |

150 个会话的末尾任务事件：`task_complete` 147、`turn_aborted` 2、`task_started` 1。

规则：
- `task_started` → 进行中
- `task_complete` → 已完成，**触发提醒**
- `turn_aborted` → 已完成（回到空闲），**不触发提醒**——是用户自己中断的，不需要叫他回来看

## 4. 已确认的设计决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 监控范围 | 仅经 imux 新建或点开过的会话 | 语义干净，不会因外部活动乱亮 |
| 提醒方式 | 列表标记 + IDE 通知 | 写代码时也能注意到，真正做到「叫你回来看」 |
| 触发时机 | 仅「进行中 → 已完成」的跃迁 | 历史会话本就处于已完成态，无跃迁则不响，天然避免全列表标满 |
| 正在查看时 | 不标记、不通知 | 你正看着，不需要提醒 |
| 消除时机 | 该会话的标签页被选中时 | |
| 读取方式 | 按字节偏移增量读取 | claude 单行可达数 MB，全量重读不可接受 |

## 5. 架构

### 5.1 `TurnSignalParser`

**职责**：把新追加的若干行解析成状态跃迁。**纯函数，无 IO、无平台依赖。**

```
enum TurnState { WORKING, IDLE }

sealed interface TurnEvent {
  COMPLETED   // 跃迁到空闲，且应当提醒
  ABORTED     // 跃迁到空闲，但不提醒
  NONE        // 无跃迁
}

fun parse(agentType: AgentType, previous: TurnState, appendedLines: List<String>): Pair<TurnState, TurnEvent>
```

这是本设计的核心，全部规则集中于此，全部可单测。

### 5.2 `TurnWatcher`

**职责**：持有被监控会话的读取偏移与当前状态，驱动 `TurnSignalParser`，产出「哪些会话刚完成」。

- 开始监控某会话时，**偏移直接设为文件当前末尾**，状态设为 `IDLE`。历史内容不参与判定。
- 每轮轮询：若文件变长，读取新增字节，交给 parser；若文件变短（被截断或重建），重置偏移到末尾并回到 `IDLE`。
- 依赖：文件系统 + `TurnSignalParser`。不依赖平台 UI。

复用现有的 `SessionStoreWatcher` 轮询节奏（3 秒），不新起线程。

### 5.3 与既有组件的接线

- `TerminalHost` 打开会话时（`openNew` / `openResume`）通知 `TurnWatcher` 开始监控该会话
- `TurnWatcher` 报出完成事件后：
  - 若该会话的标签页当前被选中 → 忽略
  - 否则标记为未读，并发 IDE 通知（`NotificationGroup`，可点击跳转）
- 标签页被选中时清除该会话的未读标记
- 未读标记在树上以**加粗**呈现（`ColoredTreeCellRenderer`），不再使用符号前缀

### 5.4 数据模型变更

`AgentSession` 增加 `filePath: Path` 字段——两个 reader 本来就持有该路径，`TurnWatcher` 需要它来定位文件。

## 6. 测试策略

**单元测试（`TurnSignalParser`，覆盖核心逻辑）**：
- Claude：`tool_use` → 进行中；`end_turn` / `stop_sequence` / `null` → 完成
- Claude：`user` 记录 → 进行中
- Claude：进行中 → 完成的跃迁产出 `COMPLETED`；已在空闲态再来一条完成信号，不重复产出
- Codex：`task_started` → 进行中；`task_complete` → `COMPLETED`；`turn_aborted` → `ABORTED`
- 无关行（`token_count`、`ai-title` 等）不改变状态
- 损坏行被跳过且不改变状态

**单元测试（`TurnWatcher`）**：
- 开始监控时不因历史内容误报
- 文件增长时只读新增部分
- 文件变短时重置而非崩溃

**手工验收**（无法自动化）：
- 交代一个耗时任务 → 切到别的标签页 → 完成时收到通知，列表条目加粗
- 点开该会话 → 加粗消失
- 正看着该会话时完成 → 不通知不标记
- codex 中途按 Esc → 不通知

## 7. 已知限制

- 以 `user` 记录收尾的会话停在进行中态，不会提醒（漏报，不误报）
- 仅覆盖经 imux 打开过的会话；外部终端里跑的不提醒（与监控范围决策一致）
- 依赖两个 CLI 的落盘格式，格式变更会导致提醒失效。解析失败一律降级为「无跃迁」，不抛异常、不影响列表
