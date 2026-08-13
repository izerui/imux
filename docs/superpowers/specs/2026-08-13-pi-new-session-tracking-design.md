# pi 会话切换的标签页跟随

让 pi 会话里的 `/new`、`/resume`、`/fork`、`/clone` 也能把终端标签页迁到新会话上——Claude 的 `/clear` 与 Codex 的 `/new` 已经支持，pi 是接入时留下的唯一功能缺口。

## 为什么不能照搬 Claude 或 Codex 的做法

两条现有路径各自的依赖，以及 pi 的实测结果：

| 需要知道 | Claude 怎么做 | Codex 怎么做 | pi |
|---|---|---|---|
| 这个进程在跑哪个会话 | 读 `~/.claude/sessions/<pid>.json` 运行态文件 | `lsof` 看它长期持有的 rollout 文件 | **都不行**：没有运行态文件；`lsof` 里一个 jsonl 都没有（open-append-close 写法） |
| 这个进程属于哪个标签页 | `ps eww` 读进程环境里的 `IMUX_TAB` | 同左 | **不行**：见下 |

pi 设置了 `process.title = 'pi'`。macOS 上这会覆写 argv/environ 内存区，`ps` 从此读不到该进程的任何环境变量。同一个 node 二进制实测对比：

| 进程 | `ps -o comm` | `ps eww` 输出 |
|---|---|---|
| `node -e "..."` | node | 5952 字节，能读到自定义变量 |
| `node -e "process.title='pi'; ..."` | pi | **67 字节，一个变量都没有** |

第二行是两条现有路径共同的最后一步，因此**即使前一步能过也认不回标签页**。

补充实测：pi 的 `/new` 在用户发出第一条消息之前不落盘，所以"等新文件出现再判断"也没有可用的时间窗。

结论：pi 对外不暴露"此刻在跑哪个会话"，OS 层没有任何观测面可用。要做对，只能让 pi 自己说。

## 方案：扩展上报 + HTTP 回传

pi 提供官方扩展机制（`-e <路径>` 加载，实测免信任确认，交互模式下正常加载并出现在启动信息的 `[Extensions]` 列表里）。扩展跑在 pi 进程内，`process.env` 对它可读——外部读不到只是 `ps` 的限制。

实测确认 `session_start` 事件满足全部需要：

```json
{ "reason": "new", "tabId": "imux-exttest",
  "sessionFile":        ".../2026-08-13T09-45-00-045Z_019ffa82-....jsonl",
  "previousSessionFile": ".../2026-08-13T09-44-52-765Z_0199bbbb-...-0003.jsonl" }
```

`/new`、`/resume`、`/fork`、`/clone` 走同一个事件，切换瞬间即报（比 Codex 那条还快，不必等落盘）。

### 数据流

```
pi 进程                                  IDE 进程
  │                                        │
  │ session_start                          │
  │ (startup / new / resume / fork)        │
  ├─ 扩展读 IMUX_TAB + IMUX_PORT + IMUX_TOKEN
  │                                        │
  └── POST /imux/pi-session ───────────────▶ PiSessionReportHandler
      {token, tabId, sessionFile}           │ 校验 token、仅收 loopback
      不 await，失败即弃                     │ 从文件名解析出会话 id
                                            ├─ 按 tabId 找到 project 与终端
                                            └─ sessionId 变了 → 复用现有迁移链路
```

**整条链零磁盘写入**，状态只存在于 IDE 内存，与 `SessionListModel` 同性质。扩展脚本是安装时就在 `imux/scripts/` 下的只读文件，不是运行时生成的。

已验证打包可行：`prepareSandbox` 里 `from(...) { into("${project.name}/scripts") }`，产出的插件 zip 中 `imux/scripts/` 与 `imux/lib/` 平级。运行时用 `PluginManagerCore.getPlugin(id)?.pluginPath` 定位。

## 组件

### 1. 扩展脚本 `scripts/pi-imux-reporter.js`

订阅 `session_start`，取 `ctx.sessionManager.getSessionFile()`，**原样 POST 出去**——从路径解析会话 id 这一步放在 IDE 端，那里能单测；JS 这边不做任何解析，因为它不进测试链路。三条硬约束：

- **不 await**：`fetch(...).catch(() => {})`。阻塞 `session_start` 会让用户敲 `/new` 时卡顿。
- **全程 try/catch 吞掉**：扩展的任何故障都不能影响用户的 pi 会话。
- **1 秒 AbortSignal 超时**：IDE 已关闭、端口变化都不能拖住 pi。

`IMUX_TAB` / `IMUX_PORT` / `IMUX_TOKEN` 任一缺失即直接返回，不做任何事。

保持到"读一遍就能确认无误"的规模，不为它引入 JS 测试链路。

### 2. `PiSessionReportHandler : HttpRequestHandler`

注册到 `com.intellij.httpRequestHandler`。

- **token 校验**：IDE 启动时生成随机串存在应用级 service 的内存里，随 pi 进程环境变量下发，请求必须带对。防的是本机其他进程伪造上报把标签页迁到别的会话上——内置 HTTP 服务是全机器可访问的，实测 `GET /api/about/` 无需任何凭据即返回 200。
- **仅收 loopback**：非本机地址直接拒。
- **定位 project**：tabId 是全局唯一的 `imux-<uuid>`，遍历打开的项目问各自的 `TerminalHost`。

收到报告后复用现有迁移链路——与 `LiveSessionProbe` 探测到 drift 之后走的是同一段代码，不新造一套。

### 3. 启动参数

`launchCommand` 给 pi 追加 `-e <pluginPath>/scripts/pi-imux-reporter.js`；`launchEnvironment` 追加 `IMUX_PORT`（`BuiltInServerManager` 的端口）与 `IMUX_TOKEN`。

## 降级

以下任一情况都**退回今天的行为**（新会话出现在列表里，双击可用，标签页不自动跟随），不弹错、不影响会话启动：

- 脚本文件找不到（安装不完整）
- 内置 HTTP 服务未就绪
- pi 版本变更导致扩展 API 不兼容

这三条要显式处理，不能依赖"应该不会发生"。缺脚本时直接不加 `-e` 参数，避免 pi 因加载失败而启动异常。

## 测试

纯函数照旧单测：上报体解析、token 校验、tabId → project 查找、`launchCommand` / `launchEnvironment` 的 pi 分支。

扩展脚本的实际行为靠一次手工端到端验证覆盖：在 pi 标签页里敲 `/new`，确认标题跟着变、列表里新会话标为"已打开"、完成提醒落到新会话上。

## 性能

- **对 pi**：扩展只在 `session_start` 触发，不是每帧或每条消息；一次触发即一个发往 localhost 的 fire-and-forget POST。加载成本相对 pi 本身要加载的数十个 skill 可忽略。
- **对 IDE**：被动接收，平时零开销。相比 Codex 那条路（定期扫全进程表 + 对每个 pid 跑 `lsof`，后者碰到网络盘会卡住，代码里专门设了 3 秒超时），这条明显更轻。

## 与既有实现的关系

pi 新建会话仍走 `--session-id` 预绑定，不变；扩展上报的首个 `startup` 事件会报出同一个 id，幂等。`LiveSessionProbe` 仍然跳过 pi（`preassignsSessionId`），进程探测那条路对 pi 依旧不适用——本设计不改变这一点，只是另外开了一条 pi 主动上报的通道。

README 需相应更新：删掉"pi 里敲 `/new` 之后标签页不会自动跟过去"这条限制，并如实说明 imux 会给 pi 加载一个自带扩展、以及它上报什么。这一点不能省——性质上它与 Claude、Codex 的纯观察不同，用户有权知道。
