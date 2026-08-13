# 接入 pi CLI

给 imux 加上第三种 agent：[pi](https://www.npmjs.com/package/@earendil-works/pi-coding-agent)（`@earendil-works/pi-coding-agent`，本机验证版本 0.80.10 / 包内 0.84.1）。接入后 pi 会话与 Claude Code、Codex 并列出现在 AI Agents 面板里，同样支持新建、续聊、运行中状态和轮次完成通知。

## pi 的会话库

以下事实均在本机实测确认，路径编码规则取自 `dist/core/session-manager.js:245`。

**会话文件位置**

```
~/.pi/agent/sessions/<编码后的 cwd>/<时间戳>_<uuid>.jsonl
```

目录名编码：`"--" + cwd.replace(/^[/\\]/, "").replace(/[/\\:]/g, "-") + "--"`。
例：`/Users/liuyuhua/github/KWU` → `--Users-liuyuhua-github-KWU--`。

与 Claude 一样是「一个项目一个目录」，可由项目路径直接算出，无需扫全库（Codex 那种按日期分目录、靠首行 cwd 归属的扫法在这里用不上）。

**文件内容**

JSONL，首行是会话头，其余是事件条目，条目间用 `id` / `parentId` 组成树（pi 支持 `/tree` 分支）。imux 不解析对话内容，只用到以下三类：

```jsonc
{"type":"session","version":3,"id":"019ffa25-...","timestamp":"2026-08-13T08:03:09.173Z","cwd":"/Users/liuyuhua"}
{"type":"session_info","id":"97a95cf0","parentId":null,"timestamp":"...","name":"标题探测"}
{"type":"message","id":"f33de69c","parentId":"8f42c7b2","timestamp":"...","message":{"role":"assistant","content":[...],"stopReason":"stop"}}
```

- `session`：会话 id、创建时间、cwd。
- `session_info`：会话显示名，由 `--name` / `/name` 写入，**可以出现多次**，以最后一条为准。没有名字的会话不会有这个条目。
- `message.stopReason`：取值为 `stop` | `length` | `toolUse` | `error` | `aborted`（见 pi 文档 `session-format.md:88`）。

**启动参数**

```
pi --session-id <uuid>    # 用指定 id 打开会话，不存在则以该 id 创建
pi --session <path|id>    # 打开已有会话（支持路径或 uuid 前缀）
```

`--session-id` 接受任意 uuid 并原样落到文件首行与文件名，已实测。

pi **没有**类似 `~/.claude/sessions/<pid>.json` 的运行态文件。

## 设计

### 1. `PiSessionReader`

新增 `session/PiSessionReader.kt`，与 `ClaudeSessionReader` / `CodexSessionReader` 并列，构造接收 pi home（生产传 `~/.pi`，测试传临时目录）。

读取流程：由项目路径算出目录名 → 列出该目录下的 `*.jsonl` → 每个文件取首行 `session` 条目拿 id 与创建时间，并用其中的 `cwd` 二次校验（防止编码后的目录名碰撞）→ 标题取最后一条 `session_info` 的 `name`，没有则回退到首条 user 消息文本（复用现有回退）→ `lastActiveAt` 取文件 mtime，与另两个 reader 一致。

`SessionRepository.forUserHome()` 增加第三个 reader。`SessionStoreWatcher` 增加 `piHome` 参数，`watchedDirs()` 加入该项目对应的那一个目录。

不需要旁路标题索引（Codex 要读 `state_5.sqlite`、Claude 要读 `history.jsonl`，pi 的标题就在会话文件里）。

### 2. 启动与会话绑定：`--session-id` 预绑定

`AgentType` 增加：

```kotlin
PI("Pi", "Pi", "pi", "Earendil Works")
```

`AgentCommand.launchCommand()` 的 pi 分支，新建与续聊是同一条命令：

```
pi --session-id <uuid>
```

新建时由 imux 生成一个 uuid 传给 pi，pi 按此 id 创建会话；续聊时传已有 id，pi 直接加载。生成用 `UUID.randomUUID()`（v4）即可——pi 自己生成的是 v7，但它对传入的 id 不做版本校验（实测传入 v7 格式的自造 id 与随机 id 均正常创建），且 imux 只把 id 当作不透明标识使用。

这样标签页与会话 id 在启动那一刻即确定，**不需要 `lsof` 或进程扫描反推绑定**：`LiveSessionProbe` 的 pi 分支返回空，`ProcessProbes` 不新增任何函数。相比 Codex 那条路（扫进程表 + `lsof` 找持有的 rollout 文件），少一整套探测逻辑，也没有绑定延迟；pi 由 node 启动、进程名不是 `pi`，走进程匹配本来也更脆。

代价：这是 imux 第一次生成会话 id。文件仍完全由 pi 自己写、自己管，插件不写一个字节，卸载后记录一条不少。README 里「不生成会话 id」这句需要相应修订（见第 7 节）。

环境变量沿用现有 tabId 注入，不加 pi 专属变量。

### 3. 运行中状态与轮次完成

pi 没有运行态文件，因此走 Codex 那条链路——从会话文件增量推断。

`TurnSignalParser` 新增 `piSignal()`：

| 会话文件里读到 | 状态 |
|---|---|
| `message.role == "user"` | WORKING |
| `assistant` + `stopReason == "toolUse"` | WORKING |
| `assistant` + `stopReason` ∈ `stop` / `length` / `error` | IDLE + COMPLETED（发通知） |
| `assistant` + `stopReason == "aborted"` | IDLE + ABORTED（不发通知） |

`TurnWatcher.watch()` 现在是 `if (agentType == CLAUDE) return`，改为语义化判断：**有运行态文件的 agent 不走这里**，pi 与 Codex 都进。`RunningSessions.of()` 的 `codexWorking` 参数改名为泛化命名（按会话文件推断出的运行集合），`SessionMonitor` 相应改为遍历 `AgentType.entries` 收集，而不是硬写 `AgentType.CODEX`。

继承 Codex 的已知盲区：点开一个本来就在跑的会话，要等下一轮信号才亮转圈。与现状一致，不额外处理。

### 4. 图标

品牌 logo 由用户提供（单色方形阶梯造型）。按现有资源约定渲出四份 PNG（工具用本机已有的 `rsvg-convert`）：

| 文件 | 尺寸 | 填充 |
|---|---|---|
| `icons/pi.png` | 16×16 | `#000` |
| `icons/pi@2x.png` | 32×32 | `#000` |
| `icons/pi_dark.png` | 16×16 | `#fff` |
| `icons/pi@2x_dark.png` | 32×32 | `#fff` |

与 `codex.png` / `codex_dark.png` 的双变体模式一致（采样确认现有 codex 资源就是纯黑与纯白）。不采用 `agent.svg` 那种主题染色 SVG——那是通用图标的做法，品牌图标一律走 PNG。

`AgentIcons` 增加 `pi` / `piBusy` 字段与 `forAgent` / `busy` 两处 `when` 分支，写法与 claude / codex 相同。忙碌动画直接复用 `spinning()`：logo 图案占 800 画布的 `165.29 ~ 634.72`（边长 469，对角线 663 < 800），旋转到任何角度都不会被声明区域裁切。

### 5. 顺手泛化

- `AgentSessionTree` 的 `limits` 从硬编码 map 改为 `AgentType.entries.associateWith { PAGE_SIZE }`，避免以后加 agent 再漏。
- `NewSessionPopup` 遍历 `AgentType.entries`，pi 自动出现，无需改动。

### 6. 测试

新增 `PiSessionReaderTest`（仿 `CodexSessionReaderTest`，用 `TemporaryFolder` 手写 jsonl），覆盖：

- 目录名编码正确（含路径里带 `.`、`_`、`-` 的情况）
- 标题取最后一条 `session_info`
- 无 `session_info` 时回退首条 user 消息
- 首行 `cwd` 与项目路径不符时排除该文件

补充断言：

- `TurnSignalParserTest`：pi 的五种 `stopReason` 各一例，外加 user 消息触发 WORKING
- `AgentCommandTest`：pi 新建与续聊都产出 `--session-id <uuid>`；不注入 `CLAUDE_CODE_NATIVE_CURSOR`
- `IconResourceTest`：pi 四份资源与 codex 那组并列断言（尺寸、透明通道、深色变体）

### 7. 文档

README 更新：

- 开头一句、CLI 前提说明、面板分组说明里加上 pi
- 「它读了你机器上的什么」表格加一行 `~/.pi/agent/sessions/<项目目录>/*.jsonl`
- **修订「不生成会话 id」**：改为「不解析对话内容、不往你的会话文件里写任何东西；pi 会话在新建时由 imux 指定一个 id 交给 pi 创建，文件仍由 pi 自己写」

plugin.xml 无需改动（agent 类型完全由枚举与代码分支驱动，没有按 agent 注册的扩展点），仅描述文案里提到 Claude/Codex 的地方可一并更新。

## 暂不处理

**输入法适配。** Codex 为流式输出冲掉中文输入法组合专门写了 `CodexImeCompositionSupport`，pi 是否有同样问题尚未实测。先不挂：`AgentTerminalFileEditor` 里保持只有 Codex 分支启用。真用起来发现问题，再把该支持泛化成按 agent 开关的通用能力。
