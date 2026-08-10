# Codex 等待用户操作的信号调研

> 状态：**已调研，暂不实施**。记录以备将来需要时直接开工，不必重查。

## 起因

Claude 侧已经实现「等待用户选择时标记未读」（见 `2026-08-10-waiting-unread-design.md`），依据是运行态文件里的 `status:"waiting"`。随之而来的问题是 Codex 能不能照做。

结论：**技术上可行，但对当前使用方式没有价值**，故不做。理由见最后一节。

## 三条路的调研结论

### 路 A：从会话文件推断——不可行

Codex 的 rollout 文件里没有任何审批相关记录。**675 个会话文件全量 grep，零命中**。审批请求（`ExecApprovalRequest` / `ApplyPatchApprovalRequest` / `PermissionsApprovalRequest` / `McpElicitationApprovalRequest`）都是进程内的 Op/Event 枚举，只在 TUI 与 core 之间传递，不落盘。

同时确认：Codex 没有 Claude 那种 `~/.claude/sessions/<pid>.json` 运行态文件；`state_5.sqlite` 的 `threads` 表 30 个字段全是元数据，`approval_mode` 是配置项而非实时状态，没有 `status` 字段。

### 路 B：app-server + `--remote`——可行，信号最全

TUI 支持连到 app-server：

```
--remote <ADDR>    ws://host:port | wss://host:port | unix:// | unix://PATH
```

协议里有完全等价的信号（由 `codex app-server generate-json-schema --experimental` 生成，70 个服务端通知）：

```
thread/status/changed → ThreadStatus
    ThreadStatus.active.activeFlags: ThreadActiveFlag
    ThreadActiveFlag = ["waitingOnApproval", "waitingOnUserInput"]
```

外加 `turn/started`、`turn/completed`、`item/*`、`serverRequest/resolved` 一整套，比现在从 rollout 刮 `task_started` / `task_complete` 精确得多。

代价：插件要管理 daemon 生命周期（启动、探活、版本匹配），终端命令要改成 `codex --remote unix://...`，且 `app-server` 与 `remote-control` 都标着 experimental。

**未验证**：app-server 是否向所有连接的 client 广播 `thread/status/changed`。若通知只发给创建该 thread 的 client（即 TUI 自己），旁路监控就不成立。`codex app-server proxy` 的存在暗示支持多 client，但这只是暗示。

### 路 C：hooks——可行，且不必换架构（推荐）

Codex 的 hook 事件：

```
PreToolUse  PermissionRequest  PostToolUse  PreCompact  PostCompact
SessionStart  SessionEnd  UserPromptSubmit  SubagentStart  SubagentStop  Stop
```

`PermissionRequest` 正是审批框弹出的那一刻。这条路不起 daemon、不改终端命令，形状与 Claude 侧完全一致：hook 写状态文件，插件读文件。

## 实测证据

隔离环境（`CODEX_HOME=/tmp/cxhome`）下用 `-a untrusted -s read-only` 启动 TUI，让它执行 `rm -rf /tmp/cxhome/nonexist`，审批框弹出后**先不按**，此时 hook 已经触发：

```
SessionStart
PreToolUse        | Bash
PreToolUse        | Bash
PermissionRequest | Bash   ← 框还挂在屏幕上，信号已经出来了
PostToolUse       | Bash   ← 批准后
Stop                       ← 轮次结束
```

`PermissionRequest` 的完整输入（stdin JSON）：

```json
{
  "session_id": "019feb26-a682-7701-9dbb-a23a562ee32b",
  "turn_id": "019feb26-cf02-7073-8ffc-89691098c80c",
  "transcript_path": "/.../rollout-2026-08-10T18-10-06-019feb26-....jsonl",
  "cwd": "/Users/liuyuhua",
  "hook_event_name": "PermissionRequest",
  "model": "gpt-5.6",
  "permission_mode": "default",
  "tool_name": "Bash",
  "tool_input": { "command": "rm -rf /tmp/cxhome/nonexist" }
}
```

字段比 Claude 侧还全：`session_id` 用于索引会话、`cwd` 用于按项目过滤，另有 Claude 没有的 `turn_id` 与 `tool_input`——后者能让文案具体到「等待确认执行 `rm -rf ...`」。

## 将来实施时的方案

### 信号源

hook 脚本按事件维护一个状态目录：

- `PermissionRequest` → 写入 `<session_id>.json`（含 `cwd`、`tool_name`、`tool_input`）
- **其他任何事件**（`PreToolUse` / `PostToolUse` / `Stop` / `UserPromptSubmit` / `SessionEnd`）→ 删除该文件

不必精确配对「结束事件是哪个」：只要有后续事件发生，就说明已经不在等了。这条规则对事件语义的依赖最小，最抗上游变动。已实测批准走 `PostToolUse`，拒绝同样会走后续事件。

### 接入

Claude 侧现有的 `TurnOutcome`、`WaitingSession`、`notifyWaiting`、`waitingSubtitle`、未读标记与图标**全部复用，一行不改**。Codex 侧只需新增一个读状态目录的索引类，形状对标 `ClaudeRuntimeIndex`，产出 `WaitingSession` 汇入 `SessionMonitor` 现有的 `outcome.waiting`。

### 配置注入：用 `-c`，不写全局配置文件

**已实测可行。** 插件本就自己拼命令行启动 codex，追加两个参数即可，作用域仅限该次调用：

```
codex -c 'features.codex_hooks=true' \
      -c 'hooks.PermissionRequest=[{matcher=".*",hooks=[{type="command",command="<脚本路径>"}]}]'
```

这样不碰 `~/.codex/config.toml`，不影响用户在插件之外跑的 codex。

## 已知的坑

1. **hook trust 是硬门槛。** 不带 `--dangerously-bypass-hook-trust` 时 hook **不会执行**（已实测：同样的 `-c` 配置，带则触发、不带则静默跳过）。而把一个 `--dangerously-` 开头的参数塞进用户的终端命令行并不合适。实施前必须先搞清楚有没有非 dangerous 的信任建立路径（TUI 内首次确认？`trusted_hash` 如何生成与持久化？）。
2. **`[features] codex_hooks = true` 是 experimental 开关**，协议与行为可能变。
3. **配置项会被覆盖。** 实测在 `config.toml` 写 `approval_policy = "untrusted"` 无效，实际生效的是 `bypassPermissions`；命令行 `-a untrusted` 才压得住。排查时不要只看配置文件。
4. `codex exec` 是非交互模式，审批被直接跳过，**验证 `PermissionRequest` 必须用真 TUI**。

## 为什么现在不做

当前 `~/.codex/config.toml` 是全权限：

```toml
approval_policy = "never"
sandbox_mode   = "danger-full-access"
```

**这种模式下 codex 不弹权限框，`PermissionRequest` 永远不触发。** 上面的验证之所以能跑通，是因为在隔离环境里强制加了 `-a untrusted`。

而全权限下 codex 停下来等人的场景，基本就是「模型问完问题、轮次结束」——此时 `task_complete` 已写入会话文件，现有机制已经会标未读。剩下的 MCP elicitation、计划模式确认属于另一类信号，与 `PermissionRequest` 无关，要做得另行调研。

结论：在改用需要审批的策略之前，这套方案没有实际收益，不值得背上 hook 注入与 experimental 开关的包袱。

## 重启条件

出现下列任一情况时，回到本文档直接开工：

- codex 改用 `untrusted` / `on-request` 审批策略
- 确实遇到「codex 停着等你、插件却一直显示执行中」的具体场景（需先确认屏幕上是什么，未必是权限框）
- Codex 官方开始写运行态文件，届时路 A 复活，成本远低于本方案
