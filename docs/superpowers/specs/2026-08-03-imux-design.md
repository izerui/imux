# imux 设计

日期：2026-08-03
状态：已确认，待编写实现计划

## 1. 目标

在 IntelliJ IDEA 左侧新增一个 AI Agent 工具窗口，形态对标自带的 Project 视图：

- 左侧栏出现一个新图标，点击展开会话列表
- 列表按 agent 类型分组（Claude Code / Codex），列出属于当前项目的会话
- 点击某条会话，在 editor 区域打开一个 tab，里面是跑着该 CLI 的终端

## 2. 核心定位

**插件是一个壳子。** 它是两个 CLI 自有会话库的视图，外加一个终端宿主。它不生成会话 id、不解析对话内容、不存储任何自己的状态。

这条定位推导出三个直接收益：

- CLI 升级、改交互、加功能，插件不需要跟着改
- 在外部终端裸跑 `claude` 产生的会话，回到 IDEA 列表里同样可见、可 resume；反之亦然
- 插件出故障的最坏情况是列表不刷新，不可能损坏会话数据

## 3. 已验证的外部事实

以下均在目标机器（macOS，IntelliJ IDEA 2026.1）上实测确认。

### 3.1 Claude Code

会话落盘路径：

```
~/.claude/projects/<cwd 路径将 / 替换为 ->/<session-uuid>.jsonl
```

例：`/Users/liuyuhua/github/maas-api` → `~/.claude/projects/-Users-liuyuhua-github-maas-api/`

- 目录名是 cwd 的确定性编码，因此「本项目的全部 claude 会话」等价于列出单个目录，无需读取文件内容
- 文件名即 session UUID
- 文件内含 `ai-title` 类型记录，形如
  `{"type":"ai-title","aiTitle":"咨询中牟县第一高级中学招生情况","sessionId":"426c1be3-..."}`
  取最后一条作为列表显示名
- 恢复命令：`claude --resume <uuid>`（已验证 `-r/--resume` 存在）

### 3.2 Codex

会话落盘路径：

```
~/.codex/sessions/YYYY/MM/DD/rollout-<时间戳>-<uuid>.jsonl
```

- 按日期分目录，不按 cwd。归属项目需读取首行 `session_meta` 中的 `cwd` 字段判断
- 文件名中直接含 UUID
- **无标题类记录**（只有 `session_meta` / `turn_context` / `response_item` / `event_msg`）。显示名回退为首条用户消息截断
- 恢复命令：`codex resume <SESSION_ID>`（接受 UUID 或会话名）

### 3.3 会话 id 是惰性产生的

两个 CLI 都在用户发出第一条消息后才写会话文件。刚启动、尚未对话的会话不存在于会话库中，因而没有 id。

这个性质本身是自洽的：没有内容的会话本来就没有可恢复的东西。但它对「新建会话」的交互有直接影响，见 6.3。

### 3.4 resume 会重放完整对话历史

已用伪终端实测两个 CLI 确认：`claude --resume <id>` 与 `codex resume <id>` 在交互式模式下都会**从第一条消息开始把对话逐条打印到终端**，用户消息与助手回复格式与实时对话一致，工具调用折叠为一行摘要。

这条事实很关键：它意味着「点击会话即可看到对话过程」天然成立，**插件不需要自己解析 jsonl 去构建只读回看视图**。

同时实测到三条需要在实现中留意的外部行为：

- **claude 的信任对话框会挡住历史**。目录若未在 `~/.claude.json` 中标记 `hasTrustDialogAccepted`，resume 后显示的是信任询问而非历史。用户确认一次后恢复正常。属 CLI 行为，非插件缺陷。
- **历史依赖终端输出容量**。对话是被打印进输出区的，不是富文本面板，容量不足会截断长会话的开头。Reworked 引擎按字节限制，键为 `new.terminal.output.capacity.kb`（默认 1024 KB）。
- **codex 有 1–3 秒空窗**，先画 banner，等 MCP server 引导完才清屏重绘历史。

## 4. 已确认的设计决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 会话的本质 | 纯 terminal 托管，一个 pty 跑 CLI | 不解析输出，与 CLI 版本完全解耦 |
| 目标 IDE 版本 | 2026.1 及以上 | 单一大版本，无需写兼容层；也因此可接受 Experimental API 的漂移代价 |
| 重启后行为 | 自动 resume 原会话 | 上下文不丢 |
| 工作目录范围 | 锁定当前 Project 根目录 | 符合 IDEA 心智模型；多仓库开多窗口 |
| session id 来源 | 由 CLI 自行生成，插件事后发现 | 会话身份归 CLI 所有 |
| 列表真相源 | 直接读 CLI 会话库，零持久化 | IDE 内外产生的会话统一可见 |
| 列表呈现 | 分组树，两个可折叠节点 | 与 Project 树形态一致，归属清晰 |
| 终端引擎 | Reworked 2025（2026.1 默认） | 跟随 JetBrains 主线；VT 后端仍是 JediTerm，TUI 兼容性不打折 |

## 5. 架构

四个组件，各自边界清楚。状态刻意集中在 `SessionListModel` 一处，其余三者要么无状态，要么只管进程与视图。

### 5.1 `SessionRepository`

**职责**：把两个 CLI 的会话库读成一个统一的会话列表。

**接口**：输入项目根目录，输出 `List<AgentSession>`，其中

```
AgentSession {
  id: String            // CLI 生成的 session UUID
  title: String         // 显示名
  agentType: CLAUDE | CODEX
  lastActiveAt: Instant
}
```

**依赖**：只依赖文件系统。不依赖 IDEA 平台、不依赖 UI、不启动进程。

**内部逻辑**（全部可单测）：
- cwd → claude 项目目录名的编码
- claude：列目录取文件名为 id，读文件提 `ai-title`
- codex：遍历日期目录，读 `session_meta` 按 cwd 过滤，标题回退为首条用户消息
- 按 `lastActiveAt` 倒序返回全部结果。条数截断是 UI 的事，不在这里做

### 5.2 `SessionListModel`

**职责**：列表的**全部有状态部分**。`SessionRepository` 刻意保持无状态，因此状态集中在这里，只此一处。

它持有：
- 最近一次扫描得到的会话列表
- 尚未绑定到真实 id 的临时条目（见 6.3），每条记 `{agent 类型, 启动时刻 T}`
- 监听两个会话库目录的 `WatchService`（见 6.4）

它负责：
- 触发重新扫描，并把结果与临时条目合并成 UI 要渲染的最终列表
- 执行临时条目 → 真实 id 的绑定判定

**依赖**：`SessionRepository`。不依赖 UI —— UI 订阅它的变更事件。

绑定判定与合并逻辑不涉及 UI 和进程，可单测。

### 5.3 `AgentToolWindow`

**职责**：左侧工具窗口的 UI。

- 通过 `com.intellij.toolWindow` 扩展点注册，`anchor="left"`，自定义图标
- 渲染分组树：Claude Code / Codex 两个根节点
- 每条会话显示标题 + 最后活动时间；正在运行的会话带运行中标识
- 工具栏：`+` 新建（下拉选 agent 类型）、刷新
- 每组默认展示最近 50 条，超出时组末尾提供「显示更多」

**依赖**：`SessionListModel`（订阅列表变更）、`TerminalHost`（打开会话）。不直接接触 `SessionRepository`。

### 5.4 `TerminalHost`

**职责**：管理终端实例的生命周期，并把它们挂载到 editor tab。

- Project 级服务，按 session id 索引持有活着的 `TerminalView`（Reworked 引擎）
- 自定义 `FileEditorProvider` + `LightVirtualFile`，FileEditor 只是 view 的**挂载点**

**所有权规则（关键）**：TerminalView 归 `TerminalHost` 所有，**不归 FileEditor 所有**。关闭 editor tab 只取消挂载，进程继续运行；重新点击列表把同一个 view 挂回去。

这条规则决定了不能复用 JetBrains 的 `TerminalViewFileEditor`——它的 `dispose()` 会 `cancel(terminalView.coroutineScope)`，关一次 tab 会话即中断。我们自建 FileEditor 并让 `dispose()` 留空。

## 6. 关键流程

### 6.1 点击已在运行的会话

切换到该 session id 对应的 editor tab。不重开、不重启进程。

### 6.2 点击未运行的历史会话

新建 TerminalView，命令为：
- Claude：`claude --resume <id>`
- Codex：`codex resume <id>`

工作目录为项目根。注册进 `TerminalHost`，挂载到新 editor tab。

### 6.3 新建会话

这是本设计中唯一粗糙的环节，因为 3.3 所述的惰性 id。

1. 用户点 `+`，选 agent 类型
2. 启动不带 resume 参数的 CLI，记录启动时刻 T 和 agent 类型
3. 树上插入一个临时条目，标记为「新会话」，立即打开 terminal tab
4. Watcher 发现对应会话库中出现新 id 且时间 ≥ T，绑定到该临时条目，标题刷新为真实标题

**失败模式**：若用户未发消息即关闭，绑定永不发生，临时条目在下次刷新时消失。这是正确行为——那本就是空会话。

若绑定错位（例如用户同时在外部终端起了会话），最坏结果是列表里多一个未合并的临时条目，刷新即可恢复。不会损坏任何会话数据。

### 6.4 刷新

`~/.claude` 与 `~/.codex` 位于项目之外，IDEA 自身的 VFS 不监听它们。因此：

- 用 `java.nio.file.WatchService` 监听这两个目录
- ToolWindow 获得焦点时主动扫描一次
- 提供手动刷新按钮

## 7. 测试策略

**单元测试（覆盖主要逻辑）** —— 全部针对 `SessionRepository`，不碰 UI、不碰进程：
- cwd → claude 项目目录名的编码，含路径含特殊字符的情形
- 从 claude jsonl 提取 `ai-title`，含多条时取最后一条、无该记录时的回退
- codex 按 `session_meta.cwd` 过滤，含 cwd 不匹配、首行损坏的情形
- codex 标题回退：首条用户消息截断
- 按 `lastActiveAt` 排序

**单元测试（`SessionListModel`）** —— 同样不碰 UI、不碰进程：
- 临时条目与扫描结果的合并：新 id 时间 ≥ T 且 agent 类型匹配时正确绑定
- 新 id 时间早于 T 时不绑定
- 同类型存在多个临时条目时，绑定到时间上最接近的那个
- 临时条目在长时间未绑定后的清理

**平台测试**（`BasePlatformTestCase`）：
- ToolWindow 正确注册且出现在 left anchor
- FileEditorProvider 能为会话虚拟文件打开 editor

**手工验收**（不做自动化）：
- 新建 claude 会话 → 发一条消息 → 临时条目合并为真实条目
- 关闭 tab → 重新点击 → 终端内容与进程延续
- 重启 IDE → 点击历史会话 → resume 成功，上下文在
- 在外部终端起一个会话 → IDEA 列表刷新后可见并能 resume
- codex 会话的同等路径

## 8. 已知风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| CLI 落盘格式变更 | 列表读不出会话 | 解析失败降级为跳过该条并记日志，不抛异常、不影响已有终端 |
| codex 无标题记录 | 显示名不如 claude 精致 | 接受。回退为首条用户消息截断 |
| 新会话 id 绑定错位 | 列表多一个临时条目 | 刷新即恢复；不影响会话数据 |
| Reworked 终端 API 为 Experimental/Internal | 跨 IDE 大版本签名漂移后，点击会话时抛 `NoSuchMethodError` | 已决定不设 `untilBuild`，接受运行时报错而非安装期拒绝；靠把终端 API 全部关在 `terminal/` 包内限制爆炸半径。优先用 Experimental 的 `TerminalToolWindowTabsManager`/`TerminalView`，避开 Internal 的 `TerminalViewImpl`/`createNewTab` |
| **IDEA 终端对这两个 TUI 的兼容性未经验证** | 渲染错乱则整个方案不成立 | 实现前先做前置验证（计划 Task 0），这是硬闸门 |
| 长会话历史超出终端输出容量 | 滚到顶看不到最早的对话 | Reworked 的键是 `new.terminal.output.capacity.kb`，默认仅 1024 KB，需调大；作为验收项确认。注意 `terminal.buffer.max.lines.count` 是经典引擎的键，对 Reworked 无效 |
| claude 信任对话框挡住历史 | 首次在新目录 resume 看不到对话 | 记录为已知 CLI 行为，用户确认一次即可 |
| **运行中标识只覆盖本 IDE 启动的终端** | 在外部终端里活着的会话，列表显示为「未运行」 | 见下节 |

### 8.1 外部正在运行的会话

`TerminalHost` 只知道自己启动过哪些终端，无法感知在 IDE 之外（系统终端、另一个 IDE 窗口）正在运行的会话。因此：

- 这类会话在列表中不带 `●` 标识，看起来像「已跑完」
- 双击它会执行 `resume`，等于对同一个会话开出第二个并发客户端

这是「零持久化、纯读会话库」这一选型的固有代价：会话的运行状态不落盘，插件无从得知。本设计接受这一限制，不做进程探测。理由是探测手段（扫 `ps`、给会话文件加锁）成本高、跨平台脆弱，而该场景本身不常见。

## 9. 明确不做（YAGNI）

- 不解析对话内容做结构化展示
- 不自研聊天 UI
- 不支持跨项目全局会话列表
- 不做会话重命名（标题归 CLI 所有）
- 不做插件自己的持久化存储
- 不支持 2026.1 以下版本
