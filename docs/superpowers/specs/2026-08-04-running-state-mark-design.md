# 运行中标记 设计

日期：2026-08-04
状态：已确认，待编写实现计划

## 1. 目标

左侧会话列表用两个标记表达会话此刻的处境：

| 标记 | 含义 |
|---|---|
| `▶` 绿 | CLI 正在执行这一轮（转圈干活中） |
| `●` 蓝 | 这一轮跑完了，你还没回去看 |

标记只出现在**本插件开着标签页**的会话上。其余会话（含 IDE 外自行启动的 claude 进程）不带任何标记。

## 2. 与当前实现的差距

`▶` 目前表示的是「开着标签页」，与是否在执行无关（`AgentSessionTree.nodesFor` 的 `tabOpen`）。本设计把它改回「正在执行」，但判定依据换成会话文件与运行态文件，而非终端状态。

`●` 已经在工作（`markUnread` / `clearUnread`），逻辑不变。

### 2.1 为什么不是第三次尝试「进程存活标记」

`2026-08-03-imux-design.md` 的 8.2 记录了两次失败的运行状态标记，根因都是判据落在**终端运行时状态**（`TerminalView.sessionState`）上，无法离线验证。

本设计的判据全部落在文件上：Claude 读 CLI 自己写的运行态 json，Codex 读会话 jsonl 的事件流。两者都可离线构造样本单测。这与失败的两次是不同性质的依据。

## 3. 数据源

判定规则不新增，沿用已验证的两条既有通路。

### 3.1 Claude：运行态文件

`~/.claude/sessions/` 下每个 json 的 `status` 字段，`ClaudeRuntimeIndex` 已在读（文件名不参与判定，会话身份取自记录里的 `sessionId` 字段）。`status != "idle"` 即执行中（`ClaudeRuntimeSession.isBusy`）。

这是 CLI 自己维护的一手状态。参考项目 `claude-codex-wechat` 判断后台会话能否 resume 时（`prepareClaudeSessionForResume`）用的是同一份文件的同一个字段，可佐证这条路子可靠。

### 3.2 Codex：会话文件事件流

`TurnWatcher` 内部已经维护每个被监控会话的 `TurnState`：`task_started` → `WORKING`，`task_complete` / `turn_aborted` → `IDLE`（`TurnSignalParser.codexSignal`）。

这个状态此前只用于产出「刚完成」事件，从未对外暴露。本设计把它开放出来即可，无需新增解析逻辑。

### 3.3 为什么不复刻参考项目的事件驱动方案

`claude-codex-wechat` 自己 spawn CLI 子进程，用结构化输出流拿 turn 边界：Claude 走 `--output-format stream-json`（开始 `type:"system"/subtype:"init"`，结束 `type:"result"`），Codex 走 app-server 的 JSON-RPC（`turn/started` / `turn/completed`）。事件驱动，零延迟。

imux 无法采用：它把 CLI 跑在 IDE 的交互式终端里（PTY），stdout 是给人看的终端画面，不是可解析的 NDJSON。要拿结构化流就得改成 headless，那样标签页里就没有终端了，插件的核心价值不复存在。

因此 imux 只能轮询文件。差距不在判定规则的正确性，而在及时性——见第 5 节。

## 4. 渲染规则

```
running = (claudeBusyIds ∪ codexWorkingIds) ∩ openTabKeys
```

优先级：`running` > `unread` > 无标记。正在跑就显示在跑，即使身上还挂着未读。

与 `openTabKeys` 取交集有两重作用：一是实现「只关心自己正在用的会话」这一产品决策，二是天然排除 IDE 外启动的 claude 进程——它们在运行态文件里可见，但不该出现在列表标记上。

## 5. 及时性

标记的手感取决于轮询频率。当前 `SessionStoreWatcher` 固定 3 秒一轮，最坏情况标记滞后 3 秒。

**变频轮询**：有标签页开着时 1 秒一轮，一个都没开时退回 3 秒。

成本可控，因为快轮询只服务于一个很小的集合：Claude 侧读十余个小 json（本机实测运行态文件 13 个），Codex 侧只读会话文件新追加的字节。会话库全量扫描（60–250ms）仍维持 3 秒节奏，不受影响。

**前提：文件 IO 必须移出 EDT。** 当前 `checkCompletedTurns` 在 `invokeLater` 内读运行态目录并逐个 `ProcessHandle.of` 查进程存活，本就是 EDT 上的隐患；1 秒一轮会把它放大成可感知的卡顿。改为后台线程读取、仅把结果 `invokeLater` 回 EDT 渲染。

## 6. 已知限制

**Codex 打开时若正在跑，标记不会亮。** `TurnWatcher.watch()` 把读取偏移直接设到文件末尾、初始状态设为 `IDLE`，它只认「监控开始之后」的跃迁。点开一个已在执行的 codex 会话，要等本轮结束、下一轮开始才会亮。

这是既有设计的刻意取舍（避免插件启动后历史会话被误判），本设计不改动它。Claude 侧无此问题，运行态文件直接给出当前 status。

**Codex 的及时性取决于 CLI 何时落盘。** `task_started` 写入 jsonl 的时机未实测。若 codex 有写入缓冲，标记会晚于实际开始执行。需实机验证。

## 7. 测试

可单测的部分（纯逻辑，无平台依赖）：

- `TurnWatcher.workingIds()`：构造临时文件，追加 `task_started` / `task_complete` 后断言集合变化
- 运行中集合的合成：给定 claude 运行态快照、codex 工作集、标签页集合，断言三者合成结果

不可自动化的部分（需 GUI，README 已说明平台测试框架未引入）：标记的实际颜色与位置、变频轮询的观感。这些走 `./gradlew runIde` 实机验证。
