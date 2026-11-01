# 结对编程开关作为会话 MCP 工具

## 需求

把“开启结对编程 / 关闭结对编程”作为 MCP 工具提供给每个智能体终端会话。用户在任务要求里写“开启结对编程”“用 codex 结对”“关闭结对编程”，会话即调用工具切换**自己这个会话**的结对状态，效果与终端右键菜单“结对编程”中的操作一致。

## 方案

新增独立的会话级 MCP 服务 `imux_session`，与手机媒体的 `imux_mobile` 并列，复用同一套按终端注入机制（`TerminalLaunchContributor`）。结对是会话能力，与手机机器人无关，不放进 `imux_mobile`。

依赖：建立在手机媒体 MCP 的按终端注入基础设施之上（`bridge/mcp/`、`TerminalLaunchContributions.kt`、`src/main/js/pi-imux-mobile-mcp.js`），该部分须先落地。

## 工具

| 工具 | 参数 | 行为 |
|---|---|---|
| `get_pair_status` | 无 | 返回 `enabled`、`partner`（cli 名或 null）、`reviewing` |
| `enable_pair_programming` | `agent`（可选，字符串） | 开启或换伙伴，返回结果状态 |
| `disable_pair_programming` | 无 | 关闭结对 |

`enable_pair_programming`：

- `agent` 取值为已启用智能体的 cli 名（`claude` / `codex` / `pi`，以 `ImuxSettings.enabledAgentTypes` 为准）。未知或未启用的值返回工具错误（`isError: true`，`reason: "agent_not_enabled"`），列出可用值。
- 不传 `agent` 时取默认伙伴：设置项“默认结对伙伴”（新增，未配置为 null）若已配置且已启用则用它；否则取已启用列表中第一个与当前会话类型不同的智能体；都没有（只启用了当前类型）时用当前类型。
- 已与同一伙伴结对：不调 `bind`，返回 `outcome: "already_enabled"`。与其他伙伴结对：调 `bind` 换伙伴（沿用现有换绑语义：进行中的审查作废），返回 `outcome: "switched"`。未结对：`bind`，返回 `outcome: "enabled"`。

`disable_pair_programming`：已结对则 `unbind`，返回 `outcome: "disabled"`；未结对返回 `outcome: "already_disabled"`。工具描述写明：仅在用户明确要求时调用，不得为跳过或回避审查而关闭。

三个工具在会话已关闭（令牌失效或标签页不在）时返回 `isError: true`，`reason: "session_closed"`。

返回体同时放 `structuredContent` 与文本 `content`，与 `imux_mobile` 一致。annotations：`get_pair_status` 只读幂等；开关工具 `readOnlyHint=false`、`destructiveHint=false`、`idempotentHint=true`、`openWorldHint=false`。

## 当前会话的确定

- 终端启动时 `PeerLaunchContributor.prepare(project, tabId, agentType)` 生成随机令牌，登记 `(project, tabId, agentType)`，终端关闭时撤销（`TerminalLaunchContribution.release`）。
- 调用时用令牌查到登记项，再用 `TerminalHost.getInstance(project).openTabsByTabId()[tabId]` 取当前 `sessionKey`（pending key 也可；会话 key 迁移后自然取到新 key）。
- 在 EDT 上调用 `SessionMonitor.getInstance(project).peerCoordinator` 的 `bind` / `unbind` / `status`，等待结果后响应。
- 工具没有会话参数，智能体无法操作其他会话。

## 组件

- `bridge/mcp/McpJsonRpc.kt`（从 `MobileMcpProtocol` 抽出）：JSON-RPC 校验、`initialize` / `ping` / `tools/list` 分发、`rpcError` / `rpcResult` / `toolResult`。`MobileMcpProtocol` 改为使用它，行为与测试不变。
- `bridge/mcp/McpHttpGate.kt`（从 `MobileMcpHandler` 抽出）：回环地址、Host/Origin、Bearer、方法、Content-Type、Accept、协议版本、大小校验。两个 handler 共用。
- `peer/mcp/PeerMcpProtocol.kt`：三个工具的定义与参数校验，依赖 `PeerToolsBackend` 接口（`status()`、`enable(agent: String?)`、`disable()`）。
- `peer/mcp/PeerMcpService.kt`（APP 级 service）：令牌登记与撤销，实现 backend（tabId → sessionKey → coordinator，切到 EDT）。
- `peer/mcp/PeerMcpHandler.kt`：路径 `/imux/session-mcp`。
- `peer/mcp/PeerLaunchContributor.kt`：注入 `TerminalMcpServer("imux_session", url, "IMUX_SESSION_TOKEN")` 与环境变量 `IMUX_SESSION_URL` / `IMUX_SESSION_TOKEN`；Pi 加载扩展脚本。plugin.xml 注册 contributor 与 handler。
- Pi：`pi-imux-mobile-mcp.js` 泛化为按环境变量注册多个服务的 `pi-imux-mcp.js`（`imux_mobile` 与 `imux_session` 各自读自己的 URL/令牌，缺哪个跳过哪个），两个 contributor 共用这一个脚本，Pi 只加载一次（`piExtensions` 去重）。

## 默认结对伙伴设置

`ImuxSettings.State.peerDefaultPartner: String?`（存 cli 名），在现有结对编程设置区加一个下拉框：“自动（与当前会话不同的第一个）”+ 已启用的智能体。文案新增键加到全部 10 个语言文件（`ImuxBundleTest` 要求键一致）。

## 留痕

通过 MCP 成功开启、换伙伴或关闭时，弹 IDE 通知（imux 现有通知组），内容“会话「标签标题」通过 MCP 开启了结对编程（伙伴：X）/ 关闭了结对编程”。`already_*` 不弹。文案进 10 个语言文件。

## 生效时机

开启后与右键菜单开启完全一致：当前这一轮结束时 `onTurnCompleted` 触发审查，即发出指令的这一轮会被审查。关闭立即生效，进行中的审查被取消。

## 测试

- `PeerMcpProtocolTest`：`tools/list` 内容；参数校验（多余参数、`agent` 非字符串、未知 agent）；各 outcome 映射；`session_closed`。
- `PeerMcpServiceTest`：默认伙伴选择规则（设置优先、跳过当前类型、仅一个类型时回落）；tabId→sessionKey 映射与 key 迁移；令牌撤销后返回 `session_closed`；同伙伴幂等不调 `bind`。
- 抽取公共代码后 `bridge/mcp` 现有测试全部通过。
- Pi 脚本：仅设 session 环境变量时只注册 `imux_session`；两者都设时注册两个。
- 启动注入：Claude / Codex 命令行配置中出现 `imux_session`。
