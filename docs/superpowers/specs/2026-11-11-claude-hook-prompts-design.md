# Claude 确认框改走 PermissionRequest hook：手机与电脑同时作答

## 背景

手机桥接改造前按会话记录推送 Claude 的确认框（权限确认、提问、计划确认），手机回复后由插件模拟按键作答（`PermissionPrompts`、`AskedQuestions`、`TabState.waitingSubject`）。有三个缺口：

1. **子代理里的确认推不过来。** 子代理的工具调用写在它自己的记录里，主记录只有一条没返回的 `Agent` 调用；有后台子代理在跑时也确定不了屏幕上停着的是谁的框。`waitingSubject()` 遇到这两种情况都返回 null，手机只收到「请到 IDE 中处理」。
2. **按键作答绕且脆。** 提问只能逐题翻页、最后 Esc 关框再把答案当普通消息发出；权限框拒绝只能 Esc 结束整轮；电脑上光标位置不对时按键会写进输入框。
3. **权限框的「拒绝并说明」会结束这一轮**，再把话作为新消息发出，不是「别这样做，改成……」的原意。

Claude Code 的 `PermissionRequest` hook 能拿到每一次要弹框的调用（含子代理），并能直接返回允许、拒绝、提问的答案。

## 目标与约束

- 手机与电脑**同时**弹出，任意一端先作答即生效，另一端随即作废。
- 电脑上作答后告诉手机结果；手机作答后不重复推送。
- **不改任何配置文件**：不写 `~/.claude/settings.json`、项目 `.claude/settings*.json`，hook 只对 imux 启动的这一次会话生效，与配置文件里的设置合并。
- 子代理（同步、后台、并行）里的确认一样能在手机上作答。
- 旧的按键方案（按会话记录推送 + 模拟按键作答）已删除，见 §6。Codex、Pi 与没能注入 hook 的 Claude 会话停在框上时，手机只提示到 IDE 中处理。

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

以下 22–29 于 2026-11-12 用 tmux + 本地 http hook 补测（Claude Code 2.1.308）：

| # | 场景 | 结果 |
|---|---|---|
| 22 | hook 服务不可达 | 电脑照常弹框、照常执行；每次工具调用后终端多一行 `connect ECONNREFUSED`（PostToolUse hook 连不上）。IDE 退出时终端会话随之结束，实际少见 |
| 23 | 从 bypass 切进计划模式 | 计划框第 1 项是「Yes, and switch to BYPASS PERMISSIONS (no further prompts) for this session」，不是 auto；请求里 `permission_mode` 为 `plan`、`permission_suggestions` 为 null，看不出原模式 |
| 24 | `updatedPermissions` 依次 `setMode auto`、`setMode bypassPermissions` | bypass 会话批准后为 bypass，普通会话为 auto（bypass 在未开启的会话里是空操作），与电脑第 1 项一致 |
| 25 | 电脑已作答后晚到的 allow（含 `updatedInput`、`setMode`） | 被忽略（2026-11-12 对照实验，测试服务只对目标工具回复，答案按真实题目现算）：计划——对照组及时回 `setMode auto→bypassPermissions` 后为 auto；实验组电脑先选 2，晚到的同一回复在本轮已结束时、以及本轮仍在执行 `sleep 30` 时到达，模式都保持 manual。提问——对照组及时回第 2 项，Claude 收到「绿」；实验组电脑先选「红」，13 秒后晚到「绿」，Claude 收到「红」 |
| 26 | PostToolUse 的 `tool_input` 与 PermissionRequest 不同 | `ExitPlanMode` 的为空对象；`AskUserQuestion` 的多出 `answers`、`annotations`；Bash、Write 的相同 |
| 27 | 电脑上提问选「Chat about this」 | hook 连接不关闭、无 PostToolUse，本轮随即结束（由本轮结束放行主会话请求兜住） |
| 28 | 电脑上计划选 3 写修改意见 | 旧请求连接不关闭、无 PostToolUse；本轮不结束，Claude 改稿后同一代理发来新的 ExitPlanMode 请求 |
| 29 | `allowedHttpHookUrls` 不含 hook 地址 | hook 不发出，电脑照常弹框执行，无报错 |
| 30 | 危险操作确认约 2 分钟无人处理被自动拒绝（bypass 会话，2026-11-12） | hook 连接**不关闭**，也没有 PostToolUse / PostToolUseFailure；Claude 随后结束本轮。主会话请求由本轮结束放行兜住（手机上的确认静默撤回，不提示「已自动拒绝」）；子代理请求靠子代理结束时放行（SubagentStop，见实测 32） |
| 31 | 明确要求子代理前台同步运行（`run_in_background: false`，2.1.308） | 主会话记录里 Agent 结果仍是 `{"status":"async_launched","agentId":…,"isAsync":true}`：当前版本几乎不出现前台的 `completed` 结果，记录侧的「前台子代理跑完」信号很少触发 |
| 32 | 注入 `SubagentStop` http hook（2.1.308） | 子代理结束时到达，请求体带 `agent_id`（与 PermissionRequest 的 `agent_id` 相同），约在它最后一次 PostToolUse 之后 3 秒。子代理的权限框不显示在主界面，主界面按 Esc 不影响它，hook 连接不关闭，会话结束时才关闭 |

## 方案

### 1. 启动注入：`--settings` 内联 JSON

启动 Claude 时在 `--mcp-config` 旁加一个 `--settings '<json>'`，与现有 MCP 注入同路径（`AgentCommand.ideaMcpCliArgument`，同样走 `quoteEmbeddingDoubleQuotes`，Windows 方言一并覆盖）：

```json
{"hooks": {
  "PermissionRequest": [{"matcher": "*", "hooks": [{"type": "http", "url": "http://127.0.0.1:<port>/imux/claude-hook",
    "headers": {"Authorization": "Bearer $IMUX_MOBILE_TOKEN"}, "allowedEnvVars": ["IMUX_MOBILE_TOKEN"], "timeout": 86400}]}],
  "PostToolUse":        [{"matcher": "*", "hooks": [{"type": "http", "url": "…同上", "headers": …, "allowedEnvVars": …, "timeout": 5}]}],
  "PostToolUseFailure": [{"matcher": "*", "hooks": [{"type": "http", "url": "…同上", "headers": …, "allowedEnvVars": …, "timeout": 5}]}],
  "SubagentStop":       [{"matcher": "*", "hooks": [{"type": "http", "url": "…同上", "headers": …, "allowedEnvVars": …, "timeout": 5}]}]
}}
```

- 与配置文件**合并**：`~/.claude/settings.json`、项目 `.claude/settings*.json` 里的 hook 与其它键照常生效（实测 19）。
- 命令行上多个 `--settings` **不合并，后者整体覆盖前者**（实测 17）。imux 的参数拼在 `claude` 之后，alias 展开的参数在前，所以以 imux 注入的为准；用户 alias 里若带 `--settings`，那一份整体失效。这是有意的取舍：alias 带 `--settings` 很少见，会话以 imux 注入为主。备选方案 `--plugin-dir` 可与之共存（实测 18），需要时再换。
- 令牌复用 `MobileLaunchContributor` 已注册的 `IMUX_MOBILE_TOKEN`，经 `allowedEnvVars` 插入请求头，不写进命令行。
- `TerminalLaunchContribution.claudeSettings: String?`：`claudeHookSettings(url, tokenEnvironment)` 生成的 JSON 文本，由 `MobileLaunchContributor` 在 `agentType == CLAUDE` 时提供。多个启动贡献合并时取第一个非空的（`firstNotNullOfOrNull`，不做 JSON 合并）；`launchCommand` 只在 Claude 时拼成一个 `--settings`。Codex、Pi 不加。
- 新建会话与 `--resume` 都加。改造前已开着的标签没有 hook，走旧方案（见 §6）。

### 2. 入口：`/imux/claude-hook`

在 IDE 内置 HTTP 服务上新增 `ClaudeHookHandler`（处理逻辑在可注入 `Channel`、协程作用域与路由函数的 `handleClaudeHook`，用 `EmbeddedChannel` 测）。复用 `LocalMcpHandler.kt` 的 `loopbackAllowed`（本机、端口、Host/Origin）与 `bearerToken`，但不走 MCP 的 Accept / JSON-RPC 约束：本机校验不通过回 403；校验通过但令牌缺失、无效或已撤销（插件热更新后没重启 IDE，旧 Claude 进程每次工具调用都会打过来）回 200 `{}`，不做任何决定，免得终端每次工具调用都冒 hook 错误。按令牌找到 `MobileCaller`（工程路径 + 标签 id），按 `hook_event_name` 分派：

- 请求体：1 MB 以内整份解析；超过 1 MB 时只有 `PostToolUse` / `PostToolUseFailure`（Edit、Write 的 `tool_response` 带整份文件）与 `SubagentStop` 还读，32 MB 以内流式只取 `hook_event_name`、`tool_name`、`agent_id`、`tool_input`、`tool_response.answers`；其余事件（含 `PermissionRequest`）超过 1 MB、或任何事件超过 32 MB，回 200 `{}`。平台内置服务的聚合上限是 180 MB（`ide.netty.max.frame.size.in.mb`），处理函数收到的已是聚合后的 `FullHttpRequest`。

- `PostToolUse` / `PostToolUseFailure`：立即回 `{}`，把 `(agent_id, tool_name, tool_input, tool_response)` 交给 §4 判断是否为电脑作答。
- `SubagentStop`：立即回 `{}`，带 `agent_id` 时交给路由器 `onHookSubagentStopped`（§4「子代理结束」）；缺 `agent_id` 忽略。
- `PermissionRequest`：
  - 标签未绑定机器人、或请求解析不出（如提问缺题目或选项）→ **立即回 `{}`**，只走电脑。机器人暂时离线时照常挂起：电脑端同时弹框，不受影响。
  - 否则登记为一个挂起请求，响应留到作答时再发（Netty 异步回写，与 `LocalMcpHandler` 的 `scope.launch` 一致），并监听 `channel.closeFuture()`。

### 3. 挂起请求与手机推送

每个标签维护一个按到达顺序的挂起队列（`HookPromptQueue`，纯状态，放在 `bridge/core`，由 `BridgeRouter` 在 `lock` 内访问，与 `TabState` 并列；`bridge/core` 不依赖 Gson）：

```
HookRequest(id, toolName, agentId?, agentType?, detail, questions?, input: 顶层字段 → 规范化 JSON)   // kind 由 toolName 推出
HookPromptQueue.Entry(request, respond: (HookDecision) -> Boolean,   // 返回是否真的写出（连接已关闭为 false）
                      pushedEpoch: 已送达的绑定代次, pushing: Push?（正在进行的那次推送，按对象清除）, answers: 已答题目（提问用）)
```

出队的一方负责在锁外调用 `respond`，每个请求只调一次。

- 种类：`AskUserQuestion` → QUESTION，`ExitPlanMode` → PLAN，其余 → PERMISSION。
- **一次只推队首**，与终端一次只显示一个框一致；队首了结后推下一个。并行子代理同时请求时，手机消息末尾附「还有 N 个确认在排队」。
- 推送内容沿用现有渲染：`PermissionPrompts.render`、`AskedQuestions.render`、`texts.plan`、`fitted` 截断。子代理的请求在标题行注明来源（`agent_type`），对应终端的 `from the general-purpose agent`。
- **送达才算推过**：渠道确认接收（`DeliveryResult.Accepted`）后才把队首记为已推给这个绑定代次；推送失败或还在路上（限频重试）的不算。
- 手机消息到达时，若该标签的 hook 队首已送达这台手机，按 §5 译成 hook 响应，**不按任何键**；队首还没送达时手机没看到它，这句不算作答，**也不贴进终端**（电脑上正开着确认框，贴进去的回车可能直接选中默认项）：补推队首（已在路上的不重复推），送达或在路上就回「上面是等你确认的请求，请先回答它」（`hookAnswerFirst`），补推也失败就回 `DialogOpen`。绑定或自动接回（`onBound`、`onReattached`）、收到等待通知（`onWaiting`）时也补推没送达的队首。领到推送（「正在推」标记）到发出之间被取消时清掉标记，免得这个队首再也不补推。取队首与领推送在同一次加锁里；终端未就绪重试期间每次提交前都再检查一次，期间来了确认请求或停到了框上就不再提交，按同样规则回复。

### 4. 了结与通知

一个挂起请求只能了结一次（先到者赢），了结后从队列移除、推下一个。

| 了结来源 | 判定 | 回给 hook | 告诉手机 |
|---|---|---|---|
| 手机作答 | §5（只认已送达这台手机的队首） | 对应 decision；允许的同时记进「手机已允许、待 PostToolUse」小表（按标签，只存代理、工具、参数，最多 32 条，本轮结束清主会话的，子代理结束（以 `SubagentStop` 为主，后台任务结束通知与 `completed` 结果为补充，见下方「子代理结束」一行）清它的，记录重启与解绑全清） | 一句确认（已允许 / 已拒绝 / 已提交答案 / 已批准计划），**不再推电脑作答提示**；回写失败（电脑端已按 Esc 断开）时撤销小表记录，改发「电脑端已拒绝」 |
| 电脑选是 | 收到 `PostToolUse`/`PostToolUseFailure`：先在「手机已允许」小表里找匹配并抵消（那是手机允许过的请求跑完了，什么也不做）；找不到才去队列，`agent_id`、`tool_name` 与队中某请求相同，且 `tool_input` 满足匹配前提：双方都为空→匹配；电脑端为空、请求不为空→仅计划匹配（ExitPlanMode 的 PostToolUse 输入为空对象）；双方都有字段但没有共有字段→不匹配；有共有字段→每个共有字段值相等（提问多出 `answers` 与 `annotations`，实测 26） | `{}`（结束挂着的请求） | 权限：「电脑端已允许」；提问：带上 `tool_response` 里每题答案（沿用 `promptAnsweredLocally` 文案）；计划：`promptHandledLocally`（「选择已在电脑端完成。」） |
| 电脑选否 / Esc | hook 连接被关闭 | 无需回 | 「电脑端已拒绝」 |
| 同代理新计划 / 提问让旧的作废 | 收到 `ExitPlanMode` 或 `AskUserQuestion` 请求，队中有同一 `agent_id`、同一 `tool_name` 的旧请求（实测 28：电脑上计划选 3 后旧请求不关闭，改稿后发来新的）；权限请求不适用，同一代理可能并行请求多个权限 | 旧的回 `{}` | 旧的推给过这台手机时先发「选择已在电脑端完成」，再推新的队首 |
| 子代理结束 | 主信号：`SubagentStop` hook 的 `agent_id`（前台、后台都有，实测 32）。补充：记录里后台任务的 `BackgroundEnded(taskId)`，以及 Agent 工具结果 `status: completed` 带 `agentId` 解成的 `SubagentEnded`（当前版本少见，实测 31）。几条信号重复到达时由先出队的一方 respond，后到的找不到条目不做事。这些 id（`toolUseResult.agentId`、`<task-id>`）与 hook 的 `agent_id` 是同一个值（两边都对应子代理记录 `subagents/agent-<id>.jsonl`） | 该代理的条目 `{}` | 不单独提示 |
| 记录重启、换绑解绑、标签关闭（会话结束）、IDE 退出 | 现有对应事件 | `{}` | 不单独提示 |

本轮结束、中断时只放行主会话（无 `agent_id`）的挂起请求——主会话的确认活不过它所在的一轮，用作电脑端了结漏判（如提问选 Chat about this（实测 27）、允许后 Esc 中断、危险操作自动拒绝（实测 30））的兜底。子代理的请求不在这里放行，只认它结束的正面证据（上表「子代理结束」）：不能用「不在记下的后台任务里」推断前台——记录从文件末尾开始读，绑定之前就已启动的后台子代理、IDE 重启后接回的会话里的后台子代理都不在记下的后台任务里。记录里的结束标记与 IDE 的完成信号（`onTurnCompleted` 结算当前轮时，以及读取边界不可靠的 `flushAfterReply`）都算本轮结束。

要点：

- **任何 decision 都不带 `interrupt`**（实测 5：晚到的 interrupt 会打断下一步）。手机晚于电脑作答时最多是一个无副作用的晚到 decision（实测 6）。
- 电脑选是的通知要等工具**执行完**才到。长命令期间手机仍显示该请求，此时手机再回复会按正常作答回给 hook；这是一个晚到的 decision，不带 `interrupt` 时无副作用（实测 6），不做额外拦截。插件无从得知电脑已选是，手机照常收到回执——两边都答时以先到的为准，手机的回执只代表手机发出的选择（见用户指南）。不用「会话已退出等待」拦截：hook 请求通常先于等待通知到达，用等待状态判断会误拦刚到的请求。
- 同一 `tool_input` 可能在并行调用里重复出现：手机允许的请求跑完后的 PostToolUse 由「手机已允许」小表抵消，不会对上队中同参数的下一个；电脑端允许的取队中最早的一个，影响仅是通知顺序。

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
| 1 / a | `allow` + `updatedInput` 原样 + `updatedPermissions` 依次两条 `setMode: auto`、`setMode: bypassPermissions`（顺序不能反；实测 11、23、24），与电脑上的第 1 项一致：以跳过权限方式启动的会话恢复为 bypass，其余会话停在 auto |
| 2 / b | `allow` + `updatedInput` 原样 + `setMode: default`（实测 12） |
| 好 / 可以 / ok / yes / 允许 等允许词 | 不回应，条目留在队首，提示手机回「1」或「2」（`hookPlanChoose`）。不映射到第 1 项：那会切到跳过权限，权限升级不应由随口一句触发 |
| 不执行 / 拒绝 | `deny` + `message: "The user does not want to proceed with this plan yet."` |
| 其他文字 | `deny` + `message: <原话>`，Claude 按它修改计划（实测 13） |

### 6. 旧按键方案已删除

上线初期 hook 与旧的按键方案（按会话记录推送确认框、手机回复后模拟按键作答，即 `TabState.waitingSubject`、`PermissionPrompts.reply`、`AskedQuestions.answer` 等）并存，未注入 hook 的 Claude 标签走旧方案。现已整体删除，`BridgeRouter` 只剩 hook 一条作答路径：

- `onWaiting`：该标签 hook 队列非空时不发等待提示（确认框已由 hook 推过），只补推还没送达的队首；否则发「<原因>，请到 IDE 中处理」。
- 手机消息：有已送达的 hook 队首就译成决定回给 hook；有队首但没送达时补推并提示先回答它（补推失败回 `DialogOpen`），不贴进终端；没有队首而运行态正停着等人（`waitingActive`）时回 `DialogOpen`，不贴进终端；都不是才照常提交。
- 没能注入 hook 的 Claude 标签（启动贡献准备失败时会弹「手机媒体工具未能接入」警告，或插件更新后未重启 IDE 时已在跑的进程——令牌已失效，hook 入口回 `{}`，不弹警告也不报 hook 错误）：收到 Claude 的等待通知后只提示到 IDE 中处理，等待期间手机的话被拦下，不贴进终端。这与 Codex、Pi 不同：桥接没有接入它们的确认框状态，不能识别或拦截，框打开期间手机的话仍可能按普通消息发进终端（见用户指南）。
- 会话记录解码不再产出工具调用、提问、工具结果事件，终端端口也不再提供按键写入。

### 7. 文案

新增手机提示（10 个语言包）：hook 版权限确认说明（拒绝后 Claude 在同一轮里调整）、手机作答后的确认（已允许 / 已拒绝 / 已提交答案 / 已批准计划 / 已要求修改计划）、电脑端已允许、电脑端已拒绝、子代理来源、排队数。

## 已知限制

- **多题提问的后续题推送失败**：「已送达」按请求记，不按题。第 1 题送达后，后面某题推送失败时，手机的下一句仍按这道题的答案处理。
- **子代理的请求在电脑端已了结却没有任何信号**（如危险操作自动拒绝时连接不关闭、无 PostToolUse，实测 30）：这个请求会占着队首，直到该子代理结束（`SubagentStop` 等）才放行。期间手机的下一句会被当作对它的回答，手机收到的回执与电脑上的实际结果不符；这句不会贴进终端。
- **子代理被中断**：子代理挂起的请求靠 `SubagentStop` 放行。实测 32 中主界面按 Esc 不影响子代理、连接也不关闭；子代理被中断或被停止时 `SubagentStop` 是否到达**未实测**。若不到达，它的请求等记录重启、解绑或会话结束（连接关闭）才放行。

## 测试

- 纯函数：§5 三类翻译（含多选拼接、自定义文字、编号越界）、hook 响应 JSON 结构、`--settings` 片段生成与参数拼接（POSIX 与 Windows 引号各一条断言）。
- `BridgeRouter`：用假 hook 端口覆盖 §4 表中每一行的「回给 hook」与「告诉手机」两列各一条断言；并行两个请求的推送顺序与排队数；有 hook 时不发「请到 IDE 中处理」、无 hook 时照旧。
- `ClaudeHookHandler`（`EmbeddedChannel`）：本机校验失败 403、令牌无效 200 `{}`、未绑定立即回 `{}`、挂起期间连接关闭触发了结、回写后关闭不触发、只回写一次、超过 1 MB 的 `PermissionRequest` 回 `{}`。
- 端到端：按本文实测方法（tmux + 交互式 claude）各跑一遍主会话权限、子代理权限、提问、计划确认，手机端与电脑端先答各一次。

## 实现前需再验证

1. ~~用户 alias 里已有 `--settings` 时是否合并~~：已测，不合并，以 imux 注入的为准（§1）。
2. ~~**IDE 内置服务不可达时**（端口变化、IDE 卡住）http hook 失败，确认 Claude Code 只记一条非阻塞错误、照常弹框。~~已验证（实测 22）。
3. ~~**bypass / auto 模式下的计划确认**选项与 2.1.308 default 模式下不同时，§5 计划翻译的 `setMode` 取值是否需跟随 `permission_suggestions` 或当前 `permission_mode`。~~已验证（实测 23、24）：两者都看不出原模式，回 1 改为依次下发 auto、bypassPermissions（§5）。
4. ~~**电脑已作答后晚到的 `allow` + `updatedInput`**（提问、计划）确认无副作用（只测过晚到的 `deny` 与 `{}`）。~~已验证（实测 25）。
5. ~~**电脑上提问框选「Chat about this」、计划框选 3** 时 hook 连接是否也被关闭，以便正确通知手机。~~已验证（实测 26–28）：都不关闭；提问由本轮结束兜住，计划由同代理新请求作废旧请求（§4）。
6. ~~用户或组织设置了 `allowedHttpHookUrls` / `httpHookAllowedEnvVars` 时 hook 被拦，确认降级为只走电脑、不报错。~~已验证（实测 29）。
