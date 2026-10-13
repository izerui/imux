# CLI 集成审查：Claude Code / Codex / Pi

审查日期：2026-09-30
对照文档：`docs/claude-code/en/`、`docs/codex/pages/`、`docs/pi/repo/coding-agent/`（快照提交 `3caab40`，Pi 上游 commit `1b34779`）
本机实测版本：Claude Code 2.1.274、codex-cli 0.159.0

标注说明：「待验证」为推测，未在本机复现；「风险」指依赖文档未承诺的私有格式，当前可用但可能随 CLI 升级失效。

## 一、集成问题

### 高优先级

| # | CLI | 问题 | 位置 | 文档依据 |
|---|---|---|---|---|
| 1 | Claude | 项目目录名编码只替换 `/` 和 `.`；文档规定所有非字母数字字符替换为 `-`，超过 200 字符截断加哈希。路径含 `_`、空格、中文或 Windows 盘符时会话列表为空，`SessionStoreWatcher` 也监听不到 | `session/ClaudeSessionReader.kt:24` | `claude-code/en/sessions.md:232` "non-alphanumeric characters replaced by `-`" |
| 2 | Codex | 列表混入 `codex exec` 非交互会话，只过滤了 `thread_source=subagent`。本机 imux 目录下实测有 7 条 `source=exec` 会话会被列出；插件自己的 peer 与标题生成也在制造这类会话 | `session/CodexSessionReader.kt:106` | `codex/pages/docs/app-server.md`：thread/list 默认只含 `cli`、`vscode` |
| 3 | Claude / Codex | peer 副驾驶在宿主机上关闭权限/沙箱：`--permission-mode bypassPermissions`、`--dangerously-bypass-approvals-and-sandbox`。文档要求仅在容器/VM 中使用；企业配置 `disableBypassPermissionsMode` 时 Claude peer 直接失败 | `peer/PeerCli.kt:28,35` | `claude-code/en/permission-modes.md:58,604`；`codex-manual` "only use inside an isolated runner" |

修复方向：
1. 按文档规则编码；长路径用会话文件中的 `cwd` 字段反查目录，而不是猜哈希算法。
2. 读 rollout 首行 `payload.source`，只保留 `cli` / `vscode`。
3. peer 只做审阅：Claude 改 `plan` 或 `dontAsk` + `--tools` 只读白名单；Codex 改 `--sandbox read-only` 或 `workspace-write`。

### 中优先级

4. **数据目录写死（三家共性）**
   `~/.claude`、`~/.codex`、`~/.pi` 硬编码，忽略 `CLAUDE_CONFIG_DIR`、`CODEX_HOME` / `CODEX_SQLITE_HOME`、`PI_CODING_AGENT_DIR` / `PI_CODING_AGENT_SESSION_DIR` / `--session-dir`。多账号 alias 用户（文档示例用法）列表与状态全部失效。
   位置：`SessionRepository.kt:26-30`、`SessionMonitor.kt:195,725-730`、`SessionTitleRegenerator.kt:124`、`ProcessProbes.kt:159`、`LspDiagnostics.kt:33-50`。另 `CodexRuntimeIndex.kt:226,302` 解析 `sqlite_home` 只认双引号值，不处理单引号、`~`、相对路径。
   依据：`claude-code/en/env-vars.md:406`、`codex/pages/docs/config-file/environment-variables.md`、`pi/.../docs/sessions.md`、`environment-variables.md`。
   修复：从登录 shell 取变量，按文档优先级解析路径。

5. **非交互进程干扰（待验证）**
   本机 `~/.claude/sessions/` 中有 `entrypoint:"sdk-cli"` 条目，`claude -p` 可能同样登记。peer、标题重生成、SDK 脚本在项目 cwd 下运行时，busy→idle 可能触发「完成」提醒，标题为裸 id。
   修复：按 `entrypoint=="cli"` 过滤，或只认列表中已知的会话 id。

6. **标题写回直接改 CLI 私有存储（风险）**
   - Claude：向活跃 transcript 追加 `custom-title`（`SessionTitleRegenerator.kt:112-120`）。文档：`sessions.md:234` "entry format is internal… can break on any release"。
   - Codex：直接 UPDATE 私有 sqlite（`SessionTitleRegenerator.kt:135,148`），可能被 thread/list 的元数据修复扫描覆盖（待验证）。
   - Pi：把文件最后一行当 parentId 追加 `session_info`（`SessionTitleRegenerator.kt:157-178`），用户 `/tree` 切过分支时可能改动当前 leaf（待验证）。
   三者都可能与运行中的 CLI 并发写。
   修复：Claude 运行中改为向终端注入 `/rename`；Codex 走 `codex app-server` 的 `thread/name/set`；Pi 经扩展调用 `pi.setSessionName`。

7. **Claude `.orphaned-*.jsonl` 被列为会话**
   位置：`ClaudeSessionReader.kt:40`，只按 `.jsonl` 后缀过滤。依据：`claude-directory.md:1539` 该文件 "doesn't appear in the session picker"。点开恢复会失败。
   修复：只收文件名主体为 UUID 的文件。

8. **Claude 新建会话仍靠时间窗事后认领**
   位置：`terminal/AgentCommand.kt:263-279`。依据：`cli-reference.md:125` `--session-id`（须为 UUID）。
   修复：新建时预分配 UUID 传入，与 Pi 一样确定性绑定。

9. **Pi 完成判定过早**
   `TurnSignalParser.kt:108-125` 把 `stopReason=stop` 视为完成；`src/main/js/pi-imux-reporter.js:220-235` 只在 error/length 时上报 `agent_settled`。排队 follow-up 或其他扩展要求续跑时，通知提前发出。peer 侧 `PeerProgress.kt:175` 把 `agent_end` 当完成，同理。
   依据：`extensions.md` "`agent_settled` is final… use it when an integration needs to know Pi will not continue automatically"；`json.md` agent_end 后仍可能 retry。
   修复：每次 `agent_settled` 都上报，并上报 `agent_start`，由上报驱动运行/空闲；文件解析仅兜底。

10. **Pi LSP 探测漏对象写法**
    位置：`lsp/PiLspProbe.kt:33-38` 只认字符串包名。依据：`packages.md` `"packages":[{"source":"npm:…"}]`，以及项目级 `.pi/settings.json`。
    修复：同时解析 `source` 字段与项目级设置。

11. **Codex `-c` 注入覆盖用户同名配置（待验证）**
    位置：`AgentCommand.kt:133`。`developer_instructions` 为字符串，会替换用户 config.toml 中的值；用户已有 stdio 型 `[mcp_servers.idea]` 时合入 `url` 可能导致配置非法。
    修复：注入的服务器改用专用 id（如 `imux_idea`）；引导词与用户已有 instructions 拼接后注入。

### 低优先级 / 风险项

- **状态判定依赖私有格式（风险）**：Claude `~/.claude/sessions/<pid>.json`（`ClaudeRuntimeIndex.kt`）、Codex rollout 事件与 `logs_n` / `local_thread_catalog` 表（`TurnSignalParser.kt:92`）、Pi 改写 TUI 内部 `positionHardwareCursor` 等（`pi-imux-reporter.js:37-97`）。文档均未承诺稳定；Claude 文档称 `claude agents --json` 才是受支持的状态读取方式（`agent-view.md:756`），Codex 文档称 transcript 格式 "isn't a stable interface"（`hooks.md`）。
- **Claude `kind` 取值（待验证）**：`ClaudeRuntimeIndex.kt:24` 判断 `"bg"`，文档取值为 `interactive` / `background`，`blocksResume` 可能永不生效。该文件还可能被读到写一半的内容，解析失败时会话短暂从快照消失。
- **Codex 等待审批时仍显示「运行中」**。
- **Claude 引导词修改对已有会话延迟生效**：系统提示在首个请求时记录，`cli-reference.md:162` "takes effect once the conversation is compacted"。建议在设置页注明。
- **Claude LSP 体检只读用户级配置**（`LspDiagnostics.kt:33-36`），漏掉 project / managed 作用域与 `--plugin-dir`；建议改用 `claude plugin list --json`（`plugins/cli-reference.md:268`）。
- **Claude MCP 服务器名 `idea` 与用户同名服务器冲突时的优先级文档未列**（`mcp.md:591`，待验证）。
- **Pi 小问题**：`PiSessionReport.kt:26-32` 的 `PiStopReason` 缺 `deferred`；`PeerProgress.kt:160` 读取 `tool_execution_end` 不存在的 `args` 字段；MCP 返回的 resource/audio 内容块原样透传给 Pi（`pi-imux-idea-mcp.js:224`），Pi 工具结果只定义 text / image。
- **Codex 小问题**：`PeerProgress.kt:198` 把 `web_search` 的 `query` 当对象读取（待验证）；标题生成未隔离用户配置，用户配置了 `required=true` 的 MCP 服务器失败时 exec 直接退出，可考虑 `--ignore-user-config` 或 `-m` 指定轻量模型。

### 已核对无问题

- Claude：`--resume`、`--mcp-config`（`type:http` + `headers`）、`--tools ""`、`--safe-mode`、`CLAUDE_CODE_NATIVE_CURSOR`、`waitingFor` 取值。
- Codex：`mcp_servers.<id>.url` / `http_headers` 键结构；exec 参数 `--ephemeral`、`--skip-git-repo-check`、`--color`、`--json`、`-C`、`-c`；`--json` 事件名与 `PeerProgress` 解析一致。本次文档移除的 `approval_policy="untrusted"`、弃用的 `--full-auto` 插件均未使用。
- Pi：`--session-id`、`-e/--extension`、`--`、`-p`、`--mode json`、`--no-session`、`-nt/--no-tools`、会话目录编码、`agent_settled` / `message_end` 事件、`PI_HARDWARE_CURSOR`、`registerTool` + `StringEnum`。

## 二、扩展建议（按价值排序）

1. **官方事件驱动状态，替代私有文件解析**（最高价值，一次消化上面多数风险）
   - Claude：`--settings` 按会话注入 hooks（`hooks.md:249`：跨层级合并，不覆盖用户 hooks）。Stop / Notification（`permission_prompt`、`idle_prompt`）/ UserPromptSubmit 用 `type:http` 打到现有 `PiReportEndpoint`；SessionStart（仅 command 类型）的 `source` 区分 startup / resume / clear / compact / fork，可取代 `/clear` 漂移探测与 pending 认领；Stop 带 `last_assistant_message`，完成通知可附回复摘要。需处理 `allowManagedHooksOnly`、`allowedHttpHookUrls`、`disableAllHooks`，被禁时回退文件轮询。落点：`AgentCommand.kt`、`SessionMonitor`。
   - Codex：接入 `codex app-server`（`thread/list` 按 cwd 过滤、`thread/status/changed` 含 `waitingOnApproval`、`thread/name/set`、`thread/archive`），TUI 可经 `codex --remote ws://…` 连接。轻量替代：会话级注入 `notify`（payload 含 `thread-id`、`last-assistant-message`），但必须串联调用用户原有 notify（本机已配置一个）。hooks 的 `PermissionRequest` / `Stop` 也可用，但非托管 hook 需用户审核信任，宜做可选项。落点：`session/`、`turn/`、`TerminalHost`。
   - Pi：reporter 上报 `agent_start` / `agent_settled`，并用 `auto_retry_*`、`compaction_*` 显示「重试中 / 压缩中」。落点：reporter 脚本、`PiSessionReportHandler`。
   - 统一新增「等待审批」状态。

2. **Pi 改用官方 MCP 接入**：`pi.registerMcpServer(name, {url, headers, timeout, exposure})` 替换自写的 `idea_mcp` 路由工具，可删去握手、重试、isError 转换，获得 `/mcp` 管理界面、工具注解与 tool_search。落点：`pi-imux-idea-mcp.js`。

3. **新建会话可选参数**：Claude `--permission-mode` / `--model` / `--effort` / `-n` 预设会话名；Codex `-a` / `-s` / `-p` profile / `-m`、`service_tier="fast"`。落点：`NewSessionPopup`。

4. **会话管理动作**：分叉（Claude `--resume X --fork-session`、Pi `--session-id` + `--fork`、`codex fork`）；Codex `archive` / `unarchive` / `delete`；resume 用 `tui.resume_cwd` 或 `-C` 避免目录选择弹窗；会话交接改用官方 fork / Pi `handoff` 示例。落点：会话树右键菜单、`HandoffSessionActionGroup`。

5. **成本与上下文用量展示**：Claude statusline 输入含 `cost.total_cost_usd`、`context_window.*`（`statusline.md:180`），经 `--settings` 挂包装脚本获取，须串联用户原 statusline；Pi 读 `model_change`、`thinking_level_change`。落点：标签副标题 / 会话树。

6. **peer 副驾驶改造**：Pi 用 RPC 模式常驻（`prompt` / `steer` / `abort` / `get_last_assistant_text`），支持中途补充与打断；Codex 用 `--output-last-message` / `--output-schema` 拿结构化结果；同时收窄权限（见问题 3）。落点：`peer/`。

7. **Code review 入口**：`codex review --uncommitted|--base|--commit` 或 app-server `review/start`。落点：结对编程 / VCS 菜单。

8. **子会话与后台会话展示**：Codex 子代理树（`parentThreadId`，experimental）；Claude 后台 / Remote Control 会话标记，「发送到后台」「在 agent view 打开」入口，`claude agents --json --cwd <项目>` 低频校验 working / blocked / done / failed。落点：会话树、`ClaudeRuntimeIndex`。

9. **LSP 体检一键安装 / 刷新**：基于 `claude plugin list --json --available`、`plugin install --json`。落点：`lsp/`。

10. **Pi 扩展内注册 IDE 命令**：`pi.registerCommand`（如 `/imux-handoff`），`ctx.ui.setStatus` 显示 IDEA MCP 连通状态；会话树可用 RPC `get_tree` 展示分支与 `label`。

## 三、建议处理顺序

1. 小改动：问题 1、2、7、8、10（各几行代码加测试）。
2. 安全与路径：问题 3、4。
3. 结构性改造：扩展建议 1，单独立计划，按 Claude hooks → Pi 事件 → Codex app-server 顺序渐进替换，保留文件轮询兜底。
