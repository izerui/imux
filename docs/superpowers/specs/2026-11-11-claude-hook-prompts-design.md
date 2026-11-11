# Claude 确认框改走 PermissionRequest hook：手机与电脑同时作答

## 背景

手机桥接现在按会话记录推送 Claude 的确认框（权限确认、提问、计划确认），手机回复后由插件模拟按键作答（`PermissionPrompts`、`AskedQuestions`、`TabState.waitingSubject`）。有三个缺口：

1. **子代理里的确认推不过来。** 子代理的工具调用写在它自己的记录里，主记录只有一条没返回的 `Agent` 调用；有后台子代理在跑时也确定不了屏幕上停着的是谁的框。`waitingSubject()` 遇到这两种情况都返回 null，手机只收到「请到 IDE 中处理」。
2. **按键作答绕且脆。** 提问只能逐题翻页、最后 Esc 关框再把答案当普通消息发出；权限框拒绝只能 Esc 结束整轮；电脑上光标位置不对时按键会写进输入框。
3. **权限框的「拒绝并说明」会结束这一轮**，再把话作为新消息发出，不是「别这样做，改成……」的原意。

Claude Code 的 `PermissionRequest` hook 能拿到每一次要弹框的调用（含子代理），并能直接返回允许、拒绝、提问的答案。

## 目标与约束

- 手机与电脑**同时**弹出，任意一端先作答即生效，另一端随即作废。
- 电脑上作答后告诉手机结果；手机作答后不重复推送。
- **不改任何配置文件**：不写 `~/.claude/settings.json`、项目 `.claude/settings*.json`，hook 只对 imux 启动的这一次会话生效，与配置文件里的设置合并。
- 子代理（同步、后台、并行）里的确认一样能在手机上作答。
- Codex、Pi 和改造前已启动的 Claude 会话保持现有按键方案。

## 实测结论（Claude Code 2.1.308，2026-11-11）

在临时目录用 tmux 起交互式 `claude --settings <json>` 实测，覆盖 command 与 http 两种 hook：

| # | 场景 | 结果 |
|---|---|---|
| 1 | hook 挂起时电脑端是否弹框 | **同时弹**。hook 开始 1 秒内终端已显示完整确认框 |
| 2 | 电脑先选「Yes」 | 立即执行；hook 请求**不会被断开**，挂到自己超时 |
| 3 | 电脑先选「No」或按 Esc | 立即中断；http hook 的**连接随即被关闭** |
| 4 | hook 先返回 `allow` | 终端框自动关闭，显示 `Allowed by PermissionRequest hook`，照常执行 |
| 5 | 电脑已允许后 hook 晚到 `deny` + `interrupt: true` | **会中断 Claude 正在跑的下一步**（有副作用） |
| 6 | 电脑已允许后 hook 晚到 `deny`（无 interrupt）或 `{}` | 无副作用 |
| 7 | 子代理（前台、后台）内的调用 | hook 收到真实 `tool_input`，带 `agent_id`、`agent_type` |
| 8 | `AskUserQuestion` | 两端同时弹；hook 返回 `allow` + `updatedInput: {questions 原样, answers: {题目: 答案}}`，答案可为任意文字，终端框关闭，Claude 收到的与电脑作答一致 |
| 9 | 多选题答案格式 | 电脑作答写入记录为 `"苹果, 橙子, 还有葡萄"`（选中项与自定义文字以 `", "` 连接），hook 按同样格式返回即可 |
| 10 | 电脑上答了一题未提交，hook 返回全部答案 | hook 的答案整体生效，电脑上未提交的选择被覆盖 |
| 11 | `ExitPlanMode` 选 1（auto） | `allow` + `updatedInput`（原样）+ `updatedPermissions: [{type: setMode, mode: auto, destination: session}]`：计划获批，进入 auto 模式 |
| 12 | `ExitPlanMode` 选 2（手动） | 同上，`mode: default`：计划获批，后续写文件照常弹权限框 |
| 13 | `ExitPlanMode` 选 3（要改什么） | `deny` + `message`：Claude 把 message 当修改意见，改完重新提交，仍在计划模式 |
| 14 | 对应关系 | `PermissionRequest` 无 `tool_use_id`；`PostToolUse` 带 `tool_use_id`、`agent_id`、`tool_input`，电脑允许后在**工具执行完**时到达 |
| 15 | `--dangerously-skip-permissions` 下 | 危险操作确认（如 `Dangerous rm operation…`）和 `AskUserQuestion` 都照常触发 hook，`permission_mode` 为 `bypassPermissions` |
| 16 | 危险操作确认 | 终端提示约 2 分钟后 Claude Code 自动拒绝（无人值守保护），与 hook 无关 |
| 17 | 两个 `--settings` | **不合并**，后一个整体覆盖前一个 |
| 18 | `--settings` + 两个 `--plugin-dir` | 三处 hook 全部触发；用户 `--settings` 的 `env` 保留；插件 http hook 的 `allowedEnvVars` 生效；交互启动无额外提示 |
| 19 | 项目 `.claude/settings.json` + `--settings` | 两处 hook 都触发，配置文件里的 `env` 保留：与配置文件是合并的 |
| 20 | http hook 请求头 | 含 `Content-Type: application/json`、`Host: 127.0.0.1:<port>`，无 `Origin`（2026-11-11 实测），入口校验可通过 |
| 21 | 内置服务空闲超时 | IDEA 2026.2 的 `intellij.platform.builtInServer.impl.jar` 未使用 `IdleStateHandler` / `ReadTimeoutHandler`，长时间挂起的请求不会被服务端按空闲断开（静态检查，端到端待确认） |

## 方案

### 1. 启动注入：`--settings` 内联 JSON

启动 Claude 时在 `--mcp-config` 旁加一个 `--settings '<json>'`，与现有 MCP 注入同路径（`AgentCommand.ideaMcpCliArgument`，同样走 `quoteEmbeddingDoubleQuotes`，Windows 方言一并覆盖）：

```json
{"hooks": {
  "PermissionRequest": [{"matcher": "*", "hooks": [{"type": "http", "url": "http://127.0.0.1:<port>/imux/claude-hook",
    "headers": {"Authorization": "Bearer $IMUX_MOBILE_TOKEN"}, "allowedEnvVars": ["IMUX_MOBILE_TOKEN"], "timeout": 86400}]}],
  "PostToolUse":        [{"matcher": "*", "hooks": [{"type": "http", "url": "…同上", "headers": …, "allowedEnvVars": …, "timeout": 5}]}],
  "PostToolUseFailure": [{"matcher": "*", "hooks": [{"type": "http", "url": "…同上", "headers": …, "allowedEnvVars": …, "timeout": 5}]}]
}}
```

- 与配置文件**合并**：`~/.claude/settings.json`、项目 `.claude/settings*.json` 里的 hook 与其它键照常生效（实测 19）。
- 命令行上多个 `--settings` **不合并，后者整体覆盖前者**（实测 17）。imux 的参数拼在 `claude` 之后，alias 展开的参数在前，所以以 imux 注入的为准；用户 alias 里若带 `--settings`，那一份整体失效。这是有意的取舍：alias 带 `--settings` 很少见，会话以 imux 注入为主。备选方案 `--plugin-dir` 可与之共存（实测 18），需要时再换。
- 令牌复用 `MobileLaunchContributor` 已注册的 `IMUX_MOBILE_TOKEN`，经 `allowedEnvVars` 插入请求头，不写进命令行。
- `TerminalLaunchContribution` 增加一个 Claude 设置片段字段（如 `claudeSettings: JsonObject?`），由 `MobileLaunchContributor` 在 `agentType == CLAUDE` 时提供；`launchCommand` 拼接时合并成一个 `--settings`。Codex、Pi 不加。
- 新建会话与 `--resume` 都加。改造前已开着的标签没有 hook，走旧方案（见 §6）。

### 2. 入口：`/imux/claude-hook`

在 IDE 内置 HTTP 服务上新增 `ClaudeHookHandler`。复用 `LocalMcpHandler` 的本机、端口、Origin、Bearer 校验（抽出公共部分），但不走 MCP 的 Accept / JSON-RPC 约束。按令牌找到 `MobileCaller`（工程路径 + 标签 id），按 `hook_event_name` 分派：

- `PostToolUse` / `PostToolUseFailure`：立即回 `{}`，把 `(agent_id, tool_name, tool_input, tool_response)` 交给 §4 判断是否为电脑作答。
- `PermissionRequest`：
  - 标签未绑定机器人、或请求解析不出（如提问缺题目或选项）→ **立即回 `{}`**，只走电脑。机器人暂时离线时照常挂起：电脑端同时弹框，不受影响。
  - 否则登记为一个挂起请求，响应留到作答时再发（Netty 异步回写，与 `LocalMcpHandler` 的 `scope.launch` 一致），并监听 `channel.closeFuture()`。

### 3. 挂起请求与手机推送

每个标签维护一个按到达顺序的挂起队列（新类 `HookPrompts`，纯状态，放在 `bridge/core`，由 `BridgeRouter` 在 `lock` 内访问，与 `TabState` 并列）：

```
HookPrompt(id, kind: PERMISSION | QUESTION | PLAN, agentId?, agentType?, toolName, toolInput,
           answers: 已答题目（提问用）, respond: (JsonObject) -> Unit)
```

- 种类：`AskUserQuestion` → QUESTION，`ExitPlanMode` → PLAN，其余 → PERMISSION。
- **一次只推队首**，与终端一次只显示一个框一致；队首了结后推下一个。并行子代理同时请求时，手机消息末尾附「还有 N 个确认在排队」。
- 推送内容沿用现有渲染：`PermissionPrompts.render`、`AskedQuestions.render`、`texts.plan`、`fitted` 截断。子代理的请求在标题行注明来源（`agent_type`），对应终端的 `from the general-purpose agent`。
- 手机消息到达时，若该标签有 hook 队首，按 §5 译成 hook 响应，**不按任何键**。

### 4. 了结与通知

一个挂起请求只能了结一次（先到者赢），了结后从队列移除、推下一个。

| 了结来源 | 判定 | 回给 hook | 告诉手机 |
|---|---|---|---|
| 手机作答 | §5 | 对应 decision | 一句确认（已允许 / 已拒绝 / 已提交答案 / 已批准计划），**不再推电脑作答提示** |
| 电脑选是 | 收到 `PostToolUse`/`PostToolUseFailure`，`agent_id`+`tool_name`+`tool_input` 与队中某请求相同 | `{}`（结束挂着的请求） | 权限：「电脑端已允许」；提问：带上 `tool_response` 里每题答案（沿用 `promptAnsweredLocally` 文案）；计划：「电脑端已批准计划」 |
| 电脑选否 / Esc | hook 连接被关闭 | 无需回 | 「电脑端已拒绝」 |
| 记录重启、换绑解绑、远程控制关闭、标签关闭、IDE 退出 | 现有对应事件 | `{}` | 不单独提示 |

本轮结束、被中断**不**清队列：后台子代理可能在主会话这一轮结束后才请求权限；电脑上 Esc 中断时连接本就会被关闭（实测 3）。

要点：

- **任何 decision 都不带 `interrupt`**（实测 5：晚到的 interrupt 会打断下一步）。手机晚于电脑作答时最多是一个无副作用的晚到 decision（实测 6）。
- 电脑选是的通知要等工具**执行完**才到。长命令期间手机仍显示该请求，此时手机再回复会按正常作答回给 hook；这是一个晚到的 decision，不带 `interrupt` 时无副作用（实测 6），不做额外拦截。不用「会话已退出等待」拦截：hook 请求通常先于等待通知到达，用等待状态判断会误拦刚到的请求。
- 同一 `tool_input` 可能在并行调用里重复出现；匹配取队中最早的一个，影响仅是通知顺序。

### 5. 手机回复的翻译

**权限确认**（沿用 `PermissionPrompts` 的词表）

| 手机回复 | hook 返回 |
|---|---|
| 允许类（1 / 允许 / 好 / yes …） | `{"behavior": "allow"}`，不带 `updatedPermissions`（只允许这一次，与现在一致） |
| 拒绝类（2 / 拒绝 / 不 / no …）或其他编号 | `{"behavior": "deny", "message": "The user denied this tool call from their phone."}` |
| 其他文字 | `{"behavior": "deny", "message": "The user denied this tool call from their phone and said: <原话>"}` |

给 Claude 的 `message` 用固定英文，不随界面语言变化；手机用户的原话原样附上。

行为变化：拒绝不再结束整轮，Claude 收到拒绝原因后在同一轮里调整做法，与终端里「No, and tell Claude what to do differently」一致。

**提问**

- 仍一题一题推；手机答一题记一题，电脑端**不再翻页**。全部答完后一次性返回 `allow` + `updatedInput: {...原 tool_input, answers: {题目原文: 答案}}`。
- 单选：编号 / 字母 → 选项 `label` 原文；多选：多个编号 → 各 `label` 以 `", "` 连接（实测 9）；其他文字 → 原样作为该题答案（自定义输入）。
- 电脑上答了一半又由手机提交时，以手机为准（实测 10），不另作提醒。

**计划确认**

| 手机回复 | hook 返回 |
|---|---|
| 1 / a | `allow` + `updatedInput` 原样 + `setMode: auto`（实测 11） |
| 2 / b | `allow` + `updatedInput` 原样 + `setMode: default`（实测 12） |
| 不执行 / 拒绝 | `deny` + `message: "The user does not want to proceed with this plan yet."` |
| 其他文字 | `deny` + `message: <原话>`，Claude 按它修改计划（实测 13） |

### 6. 与现有按键方案并存

- `MobileCaller` 记一个 `hooks: Boolean`（启动时注入了 hook 才为真），经端口暴露给 `BridgeRouter`。
- hook 生效的标签：`waitingSubject()` 的推送、`onWaiting` 的「请到 IDE 中处理」在有 hook 挂起请求时不再发；`DialogOpen` 拦截改为「有 hook 队首则作答，否则照旧」。等待原因不是确认框（如空闲提醒）时仍按现有逻辑提示。
- 未注入 hook 的 Claude 标签、Codex、Pi：完全走现有代码，不改。

### 7. 文案

新增手机提示（10 个语言包）：hook 版权限确认说明（拒绝后 Claude 在同一轮里调整）、手机作答后的确认（已允许 / 已拒绝 / 已提交答案 / 已批准计划 / 已要求修改计划）、电脑端已允许、电脑端已拒绝、子代理来源、排队数。

## 测试

- 纯函数：§5 三类翻译（含多选拼接、自定义文字、编号越界）、hook 响应 JSON 结构、`--settings` 片段生成与参数拼接（POSIX 与 Windows 引号各一条断言）。
- `BridgeRouter`：用假 hook 端口覆盖 §4 表中每一行的「回给 hook」与「告诉手机」两列各一条断言；并行两个请求的推送顺序与排队数；有 hook 时不发「请到 IDE 中处理」、无 hook 时照旧。
- `ClaudeHookHandler`：令牌无效 403、未绑定立即回 `{}`、连接关闭触发了结。
- 端到端：按本文实测方法（tmux + 交互式 claude）各跑一遍主会话权限、子代理权限、提问、计划确认，手机端与电脑端先答各一次。

## 实现前需再验证

1. ~~用户 alias 里已有 `--settings` 时是否合并~~：已测，不合并，以 imux 注入的为准（§1）。
2. **IDE 内置服务不可达时**（端口变化、IDE 卡住）http hook 失败，确认 Claude Code 只记一条非阻塞错误、照常弹框。
3. **bypass / auto 模式下的计划确认**选项与 2.1.308 default 模式下不同时，§5 计划翻译的 `setMode` 取值是否需跟随 `permission_suggestions` 或当前 `permission_mode`。
4. **电脑已作答后晚到的 `allow` + `updatedInput`**（提问、计划）确认无副作用（只测过晚到的 `deny` 与 `{}`）。
5. **电脑上提问框选「Chat about this」、计划框选 3** 时 hook 连接是否也被关闭，以便正确通知手机。
6. 用户或组织设置了 `allowedHttpHookUrls` / `httpHookAllowedEnvVars` 时 hook 被拦，确认降级为只走电脑、不报错。
