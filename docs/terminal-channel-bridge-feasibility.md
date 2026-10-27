# 终端会话通讯桥接：设计与源码可行性核实

核实日期：2026-10-25。状态：设计与接口核实完成；尚未实现桥接，尚未真实账号联调。

接入范围补充：用户进一步明确，只接受“插件显示二维码 -> 手机扫码确认 -> 自动连接”的渠道，不提供手动创建应用、复制 Token/Secret、配置回调或另行部署桥接服务的接入流程。下面的凭据管理指扫码结果的内部存储；第 14 节筛选表优先于前文较宽的渠道能力讨论。

最终选定的核实范围为企业微信、飞书、钉钉、普通微信、QQ，共五个渠道。QQ 的后续手机页面核实结果见第 15 节：支持在手机扫码流程内创建机器人，但首次需取名并点击创建，不应表述为只有一次确认。

## 1. 用户需求与结论

在 imux 已有终端会话上增加外部通讯输入和文本通知出口：

```text
IDEA 原终端键盘 --------------------------+
                                        v
企业微信 / 飞书 -> 通讯适配器 -> 会话端口 -> 同一个 CLI 进程 -> 原终端照常显示
                                        |
                           只读原生会话记录 / 已有状态事件
                                        |
                                统一消息 -> 通讯适配器
```

结论：这一架构可行。现有终端输入、会话定位、生命周期监听、结构化记录读取以及参考工程的通讯实现均有代码依据。完整功能需要新增桥接模块，不能把已有接口和模拟测试通过等同于真实端到端已通过。

本设计的硬边界：

1. 只绑定 imux 的终端会话。副驾驶、先聊再做、一次性审查、轨迹生成等后台任务不接入，不修改其流程。它们若将内容注入主会话，桥接只观察主会话实际记录，不追踪后台任务。
2. 不新建 AI 进程，不调用另一套 `resume`，不更换原终端、渲染器、启动参数或 MCP 配置。
3. 企业微信与飞书只承担通讯。渠道代码不依赖 `AgentType`，不负责提示词、模型、工具执行或 AI 会话管理。
4. 远程输入按到达顺序提交原终端，不等待 Agent 空闲，不自行决定 AI 任务排队或插入，不抢占本地键盘。
5. 传输串行化、网络重试、消息去重是通讯职责，不是 AI 任务调度。
6. 输出读取是旁路只读行为，不消费、改写或拦截原终端输出。
7. 功能默认关闭；未配置、未绑定时，不建立通讯连接、不读取额外会话内容。
8. 保持“关闭标签页结束 CLI”“恢复标签页可懒启动”“拖动标签页不结束会话”等现有行为。

## 2. 证据等级和检查范围

本文区分：

- **代码已确认**：本仓库或参考仓库存在实际调用链。
- **API 已确认**：本机 SDK 签名或官方源码存在相应能力。
- **测试已通过**：本轮执行了已有测试，但测试范围通常是纯逻辑、模拟 SDK 或合成记录。
- **待实现/待联调**：设计合理，但不能据此宣称已经稳定可用。

检查覆盖与此次需求直接相关的完整路径：会话定位、终端提交、三个 Agent 的记录形态、轮次状态、ID 迁移、关闭/恢复、MCP 注入、通讯注册和收发、身份路由、附件、重试、凭据以及插件生命周期。未审计两个参考工程全部业务功能，也未把学习引擎或桥接项目的 Agent 执行器纳入设计。

本地检查基线：

| 项目 | HEAD |
| --- | --- |
| imux | `836bb49f29dc9a49ec9a1d1e93f11cfab9dd0822` |
| DeepTutor | `993003f4d3eadcc27c3ea2f0a26cfff3db801543` |
| claude-codex-wechat | `8abe279ffcc74d465da24a7aebc1dee7d8330879` |

参考工程只读检查和测试；没有读取机器人凭据、接入生产机器人、发送消息或启动真实 AI 任务。

## 3. imux 已具备的接口

下表路径相对仓库根目录，符号为本轮查到的真实符号，不是拟新增 API。

| 需求 | 实际接口/位置 | 判断与使用方式 |
| --- | --- | --- |
| 找到当前终端 | `terminal/TerminalHost.kt: terminalViewOf(sessionKey)` | 可复用；每次发送现查，不长期缓存可能过期的 view |
| 识别终端身份 | `sessionIdentityFor`、`openTabsByTabId` | 区分真实会话 ID 和终端 `tabId`；新建 pending 不等于可恢复的会话 ID |
| 发送文本并提交 | `TerminalView.createSendTextBuilder()`，`TerminalSendTextBuilder.shouldExecute()/trySend()` | 本机 `javap` 已确认；现有 `trySendPeerFeedback` 使用 `requireBracketedPasteMode().shouldExecute().trySend(prompt)` |
| 终端存活状态 | `TerminalView.sessionState`、`coroutineScope` | 可观察关闭；不得因远程消息调用 `openResume/openNew` 来“修复”离线 |
| 标签开关监听 | `TerminalHost.addSessionsChangedListener(parentDisposable, listener)` | 可订阅，回调后重新计算绑定端点 |
| 会话 ID 迁移 | `TerminalHost.addSessionKeyMigratedListener` | `/clear`、`/new` 和 pending 认领成功后已有事件；桥接另挂监听即可 |
| 消息文件定位 | `AgentSession.filePath`、`SessionMonitor.model` | 可定位真实会话记录；不自行猜目录编码或按最新文件认领 |
| 列表/运行态变化 | `SessionMonitor.addListener` | 可作为合并刷新触发器；它不是完整的消息事件流 |
| 原生增量文件读取 | `session/NavigationTranscriptIndex.kt` | 可参考半行、UTF-8、替换/截短、分支处理；不能直接拿其裁剪后的结果转发 |
| 轮次完成判定 | `SessionMonitor.checkCompletedTurns`、`TurnWatcher`、`RuntimeStatusTracker` | 原流程已消费状态；通讯不能独立再调用一次 `poll/completedSince` |
| Pi 最终状态 | `pi-imux-reporter.js`、`PiSessionReportHandler`、`onPiSessionReported` | 已有 session_start/user_message/agent_settled 报告；不是完整文本或工具结果推送 |
| MCP | `terminal/AgentCommand.kt`、`IdeaMcpSupport.kt`、`pi-imux-idea-mcp.js` | 原会话继续沿用原配置；通讯层不介入 |

关键源码入口：

- [TerminalHost](../src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt)
- [现有输入组合](../src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt)
- [SessionMonitor](../src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt)
- [增量导航索引](../src/main/kotlin/com/github/izerui/imux/session/NavigationTranscriptIndex.kt)
- [会话迁移](../src/main/kotlin/com/github/izerui/imux/monitor/SessionDriftApplier.kt)
- [现有 MCP 注入](../src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt)

### 输入接口的准确含义

`trySend` 是终端输入通道的接受结果，不是“Agent 已理解/已执行”的回执。模式未准备好时现有 helper 返回 false；这与 Agent 忙碌是两回事。桥接不得以 `runningIds` 为发送门槛。

采用用户确认的原生终端语义：远程输入等同于往同一终端提交一段内容。已有草稿、TUI 弹窗、原生 slash 命令的解释仍由 CLI 决定。没有证据证明终端接口能将远程输入变成独立于草稿的原子“聊天 RPC”；第一版也不承诺这点，不为此增加控制权切换。

不调用副驾驶协调器完成发送；可独立封装同一组平台调用。副驾驶的业务等待、轮次上限、PASS、提示词和取消机制不进入桥接。

### 轮次事件需要一个小的新增观察点

`TurnWatcher.poll()` 会推进 offset/state；`RuntimeStatusTracker.completedSince()` 也推进状态。新增消费者再次调用，可能使原流程读不到应有的跃迁。

推荐只在 `SessionMonitor` 已经得出结果的位置发布不可变的观察事件，例如 started/completed/waiting/aborted。原先 UI 通知、未读和其它业务照常执行，网络发送异步处理。waiting 事件在“用户正看终端所以不弹气泡”的过滤之前发布，因为远端通知不等于本地气泡。

这是新增旁路观察点，不是替换现有通知系统。异常必须隔离；不得阻止原有处理继续。若第一版只发送回复文本，可先不接状态事件，绝不能为少改一行代码而重复消费状态机。

## 4. 三个 CLI 的结构化输出核实

| Agent | 已确认的结构 | 可提取的信息 | 不能混淆的边界 |
| --- | --- | --- | --- |
| Claude | JSONL 中的 `message.role` 和 `content` 块；`text/tool_use/tool_result` | 用户文本、助手文本、工具名/参数、按工具 ID 关联的结果 | 工具结果可能处于 user 记录中，不能按 role=user 就当真人输入；meta、分支和子代理需区分 |
| Codex | rollout 的 `response_item`；消息、函数/自定义工具调用及结果 | 用户/助手文本、工具调用与结果、已保存的轮次事件 | 不能把 `codex exec --json` 的 item 事件解析器直接套到 TUI rollout；同一文字可能在不同事件表示中重复 |
| Pi | session JSONL 的 `type=message`；assistant 的 `text/toolCall`，role=`toolResult` | 用户/助手文本、工具调用、结果与 stopReason | session 是树，存在 branch_summary/compaction；error/length 可能重试，不能立即宣布整轮完成 |

依据：

- [现有 transcriptMessage](../src/main/kotlin/com/github/izerui/imux/session/SessionTitleRegenerator.kt) 已区分角色/内容，但会压缩空白和截断。
- [现有事件解析器](../src/main/kotlin/com/github/izerui/imux/agent/OneShotAgentEvents.kt) 证实结构化类别可解析；它服务于插件自行启动的一次性/后台调用，不是现有终端的实时订阅口。
- [Pi 会话格式](pi/repo/coding-agent/docs/session-format.md)、[消息类型](pi/repo/coding-agent/docs/message-types.md) 给出角色、块类型、ID 和树结构。
- Codex 官方 `protocol/src/models.rs` 和 `rollout/src/policy.rs` 确认消息/工具记录存在，同时明确有非持久化事件和工具结果持久化裁剪。详见第 13 节来源。

**不能再声称“所有 CLI 事件都完整落盘”。** 官方 Codex 源码将部分 Error、队列、执行等事件标为非持久化；部分持久化命令/MCP 输出有大小限制。平台终端的屏幕缓冲也会裁剪。只读旁路无法恢复上游从未保存或已经裁剪的信息。

本设计的“完整”定义为：从启用绑定起，可读取的主会话公开对话文本按原文转发，不做摘要替代、不因插件长度限制静默截断；附件、上游截断、记录不可读时给出明确状态。它不等于复制所有瞬时菜单、按键、隐藏推理、子代理内部活动或完整 shell 输出。

### 独立的 TranscriptSource

新增通讯专用读取器，不修改导航/标题/轨迹的原有读取规则：

1. 只读已绑定且归属明确的 `filePath`。文件库与路径解析继续由 imux 负责。
2. 以字节读取，保留半行，正确跨块解码 UTF-8；按完整 JSON 记录发布。
3. 保留所有公开文本块的顺序、换行和代码内容；未知类型保留诊断，不冒充工具结果或正文。
4. 保留记录 ID、工具调用 ID、分支信息；没有可靠 ID 时以文件代次和字节区间构造观察 ID。
5. Codex 各事件表示选一个权威输出来源，不能同时转发 `response_item` 和对应镜像事件。
6. 不以文本内容哈希作为唯一去重依据：两次相同的“继续”是两条合法输入。
7. 绑定时记录起始游标，默认不倾倒历史；补历史必须是明确操作。恢复绑定从已保存位置继续。
8. 跟踪文件代次与已读边界，处理截短、替换、压缩、分支跳转；无法确定时提示同步断点，不静默跳到末尾。
9. 仅监听生命周期和已有刷新事件，按绑定文件合并读取请求，不另扫所有项目会话。若需文件观察补充，只注册绑定路径及其生命周期。
10. 读取在 IO 协程；有每批读取预算和背压，历史大文件不能阻塞 EDT 或正常 CLI。

`NavigationTranscriptIndex` 可借鉴的是增量读取方法，不是返回的 `SessionExchange`：它默认只保留 200 轮、每消息 1000 字符、初读尾部 4MB，且只配对首条助手回复；这些选择服务导航，不适合作为通讯数据源。

不能保证逐 token 实时。消息何时落盘由 CLI 决定。第一版提供记录级增量与完成通知；逐字流式只能作为有来源支持的增强能力。

## 5. 两个参考工程如何参考

### DeepTutor

目录：`/Users/liuyuhua/github/DeepTutor`。

| 模块 | 已确认能力 | imux 中的取舍 |
| --- | --- | --- |
| `partners/channels/base.py` | start/stop/send、streaming capability、访问名单 | 参考接口边界；用 Kotlin/IDE 生命周期实现，不搬 Python 运行时 |
| `partners/bus/events.py`、`queue.py` | 入站/出站消息解耦 | 采用统一消息模型；补账号身份、绑定代次和持久化发送状态 |
| `channels/registry.py`、`manager.py` | 渠道注册、实例创建、重试、去重、流式调度 | 采用 adapter 注册方式；不沿用仅以渠道名作为全局实例键 |
| `services/partners/manager.py` | 每个 Partner 独立 bus/channel 实例 | 参考多实例隔离；imux 按账号/机器人隔离，不引入 Partner 学习引擎 |
| `channels/wecom.py` | Bot ID/Secret、WebSocket、心跳重连、文字/语音/图片/文件 | 可参考协议映射；主动通知与错误回执需要按官方协议补齐 |
| `channels/feishu.py` | App ID/Secret、WS 事件、HTTP 发送、卡片、附件、群聊 @ | 可参考消息行为；不复制 Python event-loop/thread 补丁 |
| `services/partners/channel_onboarding.py` | 扫码配置流程 | 按最新需求作为接入前置；其中企业微信使用 `source=hermes` 的流程，授权/适用性需再核实 |

DeepTutor 代码中不能直接继承的具体问题：

- 企业微信 `send()` 依赖 `_chat_frames[chatId]` 的最近入站帧；没有帧时仅记录日志并返回。因此它不足以实现“我只在电脑输入，也通知手机”。
- 企业微信 SDK 未初始化、req-id generator 缺失、回调帧缺失时并不抛失败，不能据此给上层标记投递成功。
- 企业微信只调用一次 `reply_stream(..., finish=True)`，没有覆盖 BaseChannel 的 `send_delta`；不能把它称为完整增量流式实现。
- 每个聊天保存一个最近帧不适合作为所有任务回复的唯一依据；应保留具体入站消息的上下文，并提供独立主动通知路径。
- 当前 `BaseChannel.is_allowed()` 中空名单拒绝所有人，manager 也跳过空名单。但补读完整扫码 `apply()` 链路后确认，企业微信会保留已有名单，或自动设置 `["*"]`；飞书会加入扫码者 open_id。因此扫码路径没有此前报告所述的名单缺失问题。imux 仍须区分“取得机器人凭据”和“确定远程操作者/通知对象”，不能认为扫码结果一定包含企业微信用户身份。
- `mixed` 图文解析字段与当前官方 SDK 的 `msg_item/msgtype` 定义存在差异；附件不能仅靠“DeepTutor 注册了 handler”就认定已完整支持。
- 飞书语音下载后调用 `transcribe_audio()`；BaseChannel 依赖额外转写服务配置。不能把企业微信的自动文字字段推广为飞书同样自带。
- 内存 LRU 和内存 queue 并不等于重启后可靠续传；imux 必须独立定义持久化语义。

这些是参考适用边界，本轮不修改 DeepTutor。

### claude-codex-wechat

目录：`/Users/liuyuhua/github/claude-codex-wechat`。

可参考 `src/channels/types.ts` 的通道接口、入站去重、收发健康状态、附件归一化和发送失败处理。

明确不移植：

- `NativeProviderAdapter`、ClaudeStreamingRunner、CodexInteractiveRunner：它们拥有另一套 AI 进程，违反本设计。
- `MessageRouter` 的 Agent 启停、steer、sessionOpChain、命令切会话和恢复：这里只保留通讯顺序，不引入另一套 AI 调度。
- `providerAutoAttach` 的标题/原生会话元信息归一化与写回：旁路桥接不写 CLI 会话库。
- 微信 iLink 的 token/额度机制：不是企业微信/飞书协议。
- “用户新消息只用于刷新额度、消费后不交 AI”的行为：不符合本次输入直达的初衷。

README/AGENTS 中的部分执行模型已与当前代码不同；本轮以实际 `createDefaultProviders`、runner 和 router 为依据，没有照抄旧说明。

## 6. 企业微信与飞书接口能力

| 能力 | 企业微信 | 飞书 | 设计结论 |
| --- | --- | --- | --- |
| 入站连接 | 智能机器人 WS，Bot ID/Secret | 应用机器人 WS，App ID/Secret | 两个 ChannelAdapter，均由本机主动建立连接 |
| 文本输入 | text.content | message.content 中的 text | 归一化为文本，但保留来源 ID |
| 语音输入 | voice.content 是转写文字 | DeepTutor 下载 audio，再外部转写 | 标记能力差异；无转写能力时不能发送“[audio]”冒充用户指令 |
| 主动通知 | 官方 `sendMessage(chatid, body)` -> `aibot_send_msg` | `im.v1.message.create` | 可以支持电脑端发起任务后的远端通知；需已知并授权的接收目标 |
| 回复原消息 | req_id/stream | message.reply，群可在线程内回复 | 入站上下文是可选回复能力，不是主动通知的必需条件 |
| 流式展示 | replyStream，最终 finish | CardKit 流式更新/普通消息降级 | 可选能力，不能成为统一接口强制要求 |
| 图片/文件 | 下载解密、上传 media | message_resource、上传 image/file | 渠道可传输不代表 TUI 已支持原生附件注入，需单独验证 |
| 回执 | req_id 对应 ack/errcode | HTTP success 与消息 ID | 网络写入成功不等于平台已接收；平台接收不等于用户已读 |
| 断线 | 心跳、重连、认证错误 | SDK 重连与 WS 生命周期 | 通讯故障只影响远端，不停止原 CLI |
| 同账号多连接 | 官方 SDK 处理新连接导致旧连接被断开，并停止自动抢回 | 接入时按一个账号一个管理者设计 | 进程内统一管理；跨 IDE 进程检测本机重复占用；服务器踢线后显示状态，不反复抢连 |

插件是 JVM 工程。DeepTutor 的 Python SDK 不能直接作为 Kotlin 依赖：

- 飞书官方 Java SDK 有 WS `Client.start/close`、事件分派和消息 API，可优先选用。此次核实的是上游源码，不是已完成依赖引入；发布版本、依赖冲突、线程释放、代理与 Marketplace 兼容需在实现时验证。
- 此次检查企业微信官方组织仓库，找到 Node/Python SDK，未找到对应的官方 JVM aibot SDK。插件内方案可使用 JDK WebSocket/HTTP 实现范围受控的协议适配，严格对照官方 SDK 的认证、心跳、ack、重连、踢线与素材协议，补协议测试。
- 不为了复用 Python/Node 代码而默默增加必须常驻的外部桥接服务。若以后选择随插件管理通讯 helper，应另行明确运行时依赖；它也不得启动 AI 进程。本设计默认插件内运行。
- 不预设企业机器人创建数量、消息速率或账号权限为常量；这些不是此次代码审计能证明的租户额度。按真实平台错误显示状态，发布前对目标账号实测。

## 7. 新增模块与插槽

建议新增 `bridge/`，渠道实现放 `bridge/channels/`。下述名称是设计，不宣称当前代码已有：

| 模块 | 职责 | 禁止承担的职责 |
| --- | --- | --- |
| `ChannelAdapter` / factory | connect/disconnect、收发归一化消息、能力声明、健康状态 | Agent 启停、读取 CLI 文件、修改项目 |
| `BridgeAccountService`（应用级） | 账号连接唯一性、凭据、适配器实例、回执和发送箱 | 持有已关闭 Project 的强引用 |
| `TerminalBridgeService`（项目级） | 绑定到终端、输入端口、生命周期、观察源 | 新建/恢复 AI 进程、控制副驾驶 |
| `TerminalSessionPort` | 现查终端、提交文本、返回传输结果 | 根据 AI 忙闲安排任务、模拟完整聊天 RPC |
| `TranscriptSource` + 三个 decoder | 只读结构化记录，输出统一事件 | 改写会话文件、摘要或隐藏截断 |
| `BridgeDeliveryStore` | 游标、去重 ID、绑定代次、待发送内容及回执 | 替代 CLI 原生会话库 |
| 设置与绑定 Action | 扫码接入、连接状态、绑定/解绑、失败查看 | 要求手填凭据、更换现有终端布局、抢键盘焦点 |

统一入站消息至少包括：

```text
accountId, channelKind, externalMessageId,
senderId, destination(chatId + 可选 threadId),
receivedAt, text, attachments, replyContext
```

统一会话事件至少包括：

```text
bindingId, bindingEpoch, projectIdentity, sessionIdentity,
sourceEventId, sourcePosition,
kind(userText / assistantText / toolStarted / toolFinished / status),
text, toolCallId?, originHint?
```

输出接口返回明确的 `Accepted(platformMessageId?) / Rejected / Unknown`。
输入接口返回 `SubmittedToTerminal / NotReady / Offline / StaleBinding / Unknown`。
不能共用一个 Boolean 表示所有投递阶段。

渠道能力声明至少区分 text、主动发送、回复、更新/流式、图片/文件、语音文字化。平台不支持时降级为明确提示，而不是强行假装同构。

工厂注册初期可用静态注册表。若目标是让其它插件贡献新渠道，再新增 IntelliJ extension point；不使用任意脚本动态加载。两者都不要求修改 CLI 侧接口。

## 8. 绑定、输入与输出行为

### 绑定

持久化身份使用项目定位信息、Agent 类型、原生 session ID；运行期再记录 tabId、view 实例代次和 bindingEpoch。项目是定位环境，会话才是操作目标。

机器人身份不足以决定回复目的地，必须一并保存允许发送者和指定聊天。第一版一个机器人绑定一个终端会话和一个明确聊天目标；数据模型可容纳不同渠道，但不自动把同一会话广播给所有接触机器人的用户。

新建未落盘会话可以暂按 tabId 绑定，但不得把 pending key 当作持久化原生 ID。首次成功迁移后更新正式身份。

绑定跟随同一终端上已确认的 `/new`、`/clear` 迁移，通知身份变化。用户手动把机器人改绑到另一个终端时递增 epoch。旧投递不重新解释为新目标。

### 输入

1. 适配器接收消息，提取账户、发送者、目标和平台 ID。
2. 校验绑定和允许用户；先登记入站 ID，避免平台重投导致重复输入。
3. 短的提交串行区保持接收顺序；附件下载异步，但同目标消息不得因下载完成先后悄悄重排。
4. 在 EDT 重新定位当前 view、检查终止状态与 epoch，调用文本提交接口。不检查 AI 的 working 状态。
5. NotReady 只允许有限的传输等待，过程中持续校验实例/绑定；超时提示未送入。已离线不留一条会在用户以后恢复时偷偷执行的指令。
6. Submitted 只显示“已提交到终端”。会话记录中出现相应文本可以作为旁证，但同文本、CLI 改写、草稿合并使精确匹配并不总是成立，不能伪造“执行成功”。
7. 对结果不明确的提交不自动重发。PTY 没有跨崩溃的应用级幂等键，不能保证 exactly-once。

保留普通 slash 文本交给 CLI 的行为，不移植微信桥接项目的 `/new`、`/resume` 解析。停止、审批等若需要专门按钮/控制键能力，应作为独立能力验证；普通聊天文字不等价于 Esc、Ctrl+C 或 TUI 的选择操作。

### 输出

结构化读取器发布事件后，输出策略决定发哪些文本。默认助手公开文字与必要状态；电脑提交的用户文字可以同步，工具详情按设置开启。状态不冒充助手回答，不推送隐藏推理或内部提示。

远端输入已在聊天中出现，不应再作为“电脑输入”重复镜像。无法可靠确认来源时，不猜；保留来源未知状态，默认关闭不确定的用户文本镜像，助手回复不受影响。

正文超过渠道限制时按 UTF-8 字节/渠道要求分段，维护 Markdown 代码块和顺序；超长正文可作为完整文本文件。限制值以对应适配器的验证结果为准，不把 stream 长度限制套到主动消息。

首次绑定默认从当前记录边界开始，避免倾倒历史。输出游标在消息已持久化到发送箱后推进；平台确认后标记发送完成。不会因为网络慢卡住终端、解析线程或原有通知。

重连补发保持原目标和 epoch。换绑后默认暂停旧发送箱并在设置中可见，不把旧内容偷偷投递到新目标；不假称已取消网络中实际已经提交的消息。

## 9. 保持现有功能的改动边界

| 原功能 | 保持方式 |
| --- | --- |
| 终端显示、滚动、分屏、IME、焦点 | 不改组件，不读写 Editor 文档，不拦截本地按键，不改变终端尺寸 |
| CLI 启动和恢复 | 不改 `AgentCommand`、shell、环境、启动标志，不引入 headless/json/RPC 启动替代 |
| MCP | 不修改注入开关、端口或引导词；原本不可用时桥接也不能自动变为可用 |
| 标签关闭 | `closeSession` 照常取消原 view；桥接变离线，不能为了微信保活 CLI |
| 拖动/分屏重建 | 绑定项目服务和 tab 身份，不绑定短命 FileEditor；遵守 CLOSING_TO_REOPEN |
| 恢复的后台标签 | 不因机器人连接而强制初始化原本 initOnShow 的终端 |
| 列表、未读、窗口标题 | 通讯阅读不调用 `clearUnread`，不模拟用户打开标签页 |
| 完成/等待通知 | 新增广播观察点，不再调用原状态机，不替换 TurnNotifier |
| 副驾驶、澄清、审查、轨迹 | 不订阅后台进程，不改各协调器；仅借鉴现有输入调用形态 |
| 删除会话 | 按现有流程结束和删除；桥接解除可执行绑定并保留可解释的离线状态 |
| 原生会话存储 | 全部只读；不写标题、entrypoint、source、历史索引或 recovery 元数据 |
| 功能关闭 | 断开 adapter、取消桥接协程/监听、停止新增读取，现有功能继续工作 |

“不影响”是设计约束和回归验收目标，不是尚未实现时能够无条件证明的结论。允许的既有文件变更应局限于新 Action/设置注册，以及必要的只读状态广播；不借这次机会重构现有终端执行链。

## 10. 生命周期、存储与失败隔离

- 应用服务管理每个机器人连接；项目服务管理绑定终端和文件观察。订阅全部有 parent Disposable。
- EDT 只做身份快照、提交与 UI 更新；网络、文件、附件、持久化在独立 IO 协程。
- 桥接子任务使用隔离的取消/异常边界，某机器人故障不能取消项目或终端的 coroutineScope。
- view 已终止、项目关闭、插件释放后停止新提交；释放 adapter 心跳、重连 timer、HTTP/WS 线程。
- 凭据用平台 `PasswordSafe`；非秘密绑定配置使用禁漫游的应用状态，不写入项目仓库。
- 发送箱/游标是本机桥接状态，需显式位置、保留期限、容量和清理入口；内容不得在 debug 日志中默认完整输出。
- 占用同一机器人时，一个 IDE 内复用连接；同机不同 IDE 进程可用本地账号锁提示占用，远端机器则以平台踢线事件为准。被踢下线不无限争抢。
- 目标成员、群聊、线程必须明确，不靠“最后一个给机器人发消息的人”重设输出目标。
- 只接受已允许用户是输入入口的基本边界，不能因为对方能看到机器人就允许其操作开发机。
- 不承诺未知网络结果下的零重复且零丢失；保存 Unknown 状态并允许核对。明确失败才按适配器策略重试。
- 写满磁盘、发送箱满、无法解析的新记录必须显示同步受限，不让插件内存无限增长，也不阻塞本地编程。
- 本轮设计面向本地 IDE/backend 中可访问 CLI 会话库的环境；JetBrains Remote Development 的 frontend/backend 服务位置和凭据归属需要专门联调，不能默认等同于本机。

## 11. 需要验证的边界清单

以下是实施验收条件，尚不是本轮通过的测试：

| 场景 | 应有结果 |
| --- | --- |
| Claude/Codex/Pi 各自在空闲时收到文本 | 各有独立断言证明进入原进程，不启动新进程 |
| 三个 CLI 各自在执行中收到文本 | 桥接立即尝试提交；不等待完成；排队/插入以 CLI 真实表现为准 |
| 本地正常键入、IME、多行、已有草稿 | 终端原行为不被桥接监听改写；记录原生粘贴结果，不承诺隔离草稿 |
| NotReady、已终止、刚关闭 | 不报告 Agent 已接受；不重新打开会话，不向旧 view 重试 |
| 本地与远程连续输入 | 输入通道不并发交错多条远程消息；本地按键不被控制权机制拦截 |
| 两条完全相同的合法输入 | 不按文本哈希误去重 |
| 平台重投同一消息 ID | 不重复向 CLI 提交 |
| 中文 UTF-8 跨块、半行 JSON | 只在记录完整后发布，正文不乱码 |
| 一条 assistant 含多文本块和工具块 | 文本全部保留并按顺序发送，工具分类不混入普通用户输入 |
| Codex 镜像事件、Claude meta、Pi toolResult | 不重复正文、不误认为真人输入 |
| CLI 重试、压缩、切分支、文件截短/替换 | 正确建立新记录代次；同步缺口可见，不误广播整段旧历史 |
| 程序正常终止但最后记录稍晚落盘 | 有有限收尾读取，不能马上销毁游标丢掉尾部 |
| 长文本、代码块、工具输出上游截断 | 插件不静默截断，明确上游限制；分段/文件内容可重组比对 |
| pending 认领、/new、/clear | 只跟随成功的正式迁移；等待中的投递重新核对绑定代次 |
| 显式换绑、旧请求晚返回 | 不把旧消息发往新会话，已发出的消息不伪称撤销 |
| 关闭标签、拖动、分屏、恢复后台标签 | 保留各自原有关闭/不关闭/懒启动语义 |
| 网络断开、ack 丢失、平台拒绝 | 区分拒绝与未知；不改变 CLI，不将未知自动当成功 |
| 同机器人不同项目窗口、不同 IDE 进程 | 单连接所有者明确，踢线后可见，不无限抢连 |
| 企业微信 voice、飞书 audio | 分别验证已有文字与需转写的分支，无转写时明确提示 |
| 附件下载失败、文件同名、超大文件 | 不覆盖其它任务附件，不把失败占位符作为正常任务输入 |
| 群聊非授权用户、机器人自消息 | 不进入 CLI、不造成自转发循环 |
| 企业微信/飞书分别在“只有电脑输入”时通知 | 独立验证主动发送，不借用最近入站回复假装通知成功 |
| 通讯关闭/认证失败/发送箱满 | 本地终端和原通知继续工作 |
| 原有 MCP 开启/关闭/不可达 | 保持各自原行为；三个 Agent 的 MCP 调用分别实测 |

原功能回归重点是终端输入、迁移、关闭/恢复、原通知和 MCP。后台副驾驶等不成为桥接实现对象，也不增加对应的远程入口。

## 12. 本轮实际验证与剩余工作

### 已执行

1. 本机 Platform 终端 jar 的 `javap`：确认 TerminalView 的 sendText/createSendTextBuilder/sessionState/outputModels，以及 TerminalSendTextBuilder 的 shouldExecute/requireBracketedPasteMode/trySend。
2. 阅读本地 JetBrains Embedded Terminal、凭据存储和生命周期资料。
3. 查验 DeepTutor 企业微信/飞书通道、通道基类、消息总线、manager、onboarding 和相关测试。
4. 查验微信桥接项目的渠道类型、适配器、当前默认 provider、独立进程调用与 router，明确可参考和不可移植部分。
5. 获取官方企业微信 Node SDK、飞书 Java SDK、Codex protocol/rollout 源码核对接口，见下一节。
6. imux：10 个测试类、206 项通过，失败/错误/跳过均为 0。
7. DeepTutor：5 个测试文件、49 项通过。
8. claude-codex-wechat：2 个测试文件、36 项通过。

合计 291 项已有测试通过，**没有桥接新实现的测试**。本轮没有修改业务代码；这些结果不是未来桥接代码的回归证明，也不是三种 CLI 的真实终端/机器人联调证明。

执行记录：

```sh
# imux；先检查了 IDE 运行配置，没有匹配本次测试组合的配置，使用原生命令
./gradlew test \
  --tests '*PeerCoordinatorDispatchTest' \
  --tests '*NavigationTranscriptIndexTest' \
  --tests '*SessionTitleRegeneratorTest' \
  --tests '*SessionKeyMigrationTest' \
  --tests '*SessionDriftApplierTest' \
  --tests '*TurnWatcherTest' \
  --tests '*TurnSignalParserTest' \
  --tests '*IdeaMcpSupportTest' \
  --tests '*PiReporterBehaviorTest' \
  --tests '*PiIdeaMcpBehaviorTest'

# DeepTutor
.venv/bin/python -m pytest -q \
  tests/services/partners/test_wecom_channel.py \
  tests/services/partners/test_feishu_reply_delivery.py \
  tests/services/partners/test_feishu_final_delivery.py \
  tests/services/partners/test_feishu_stream_resilience.py \
  tests/services/partners/test_channel_manager.py

# claude-codex-wechat
pnpm exec vitest run tests/weixinDirectAdapter.test.ts tests/commandParser.test.ts
```

PeerCoordinatorDispatchTest 用于核对复用的终端输入调用组合，不表示接入副驾驶。其测试用代理模拟 builder，不能证明三个真实 TUI 的所有输入状态。

未执行：真实账号收发、真实 CLI 任务、跨平台终端操作、附件注入、断线/进程崩溃演练、机器人额度验证、桥接依赖打包。没有业务代码改动，本轮未运行新功能的 IDE lint/build 或 plugin verifier；实际引入平台 API 与 SDK 后须按 AGENTS 执行。

IDE MCP 尝试读取本机安装 jar 的反编译源码被拒绝，原因是该路径不在 IDE 项目/库/SDK roots 内；因此平台实现细节未据此验证。接口签名已通过 `javap` 和项目现有调用核对，不将签名存在冒充实现级证明。

### 实施顺序

1. 新建会话端口、结构化只读观察源和假渠道；验证输出分类、运行中输入、原终端不受影响。
2. 先验证企业微信扫码闭环，再接文字与已有语音转写，验证主动通知和输入直达；第一条真实链路必须检查进程未新增、MCP 仍可调用。不把手动填写凭据作为扫码失败后的交付替代。
3. 实现绑定持久化、回执、错误与恢复、生命周期及设置。
4. 接飞书适配器，以同一套端口/事件合同通过契约测试，证明渠道层没有 Agent 分支。
5. 逐项扩展附件、流式展示、其它渠道；停止与审批在有确切控制接口后独立验收。

## 13. 可复核的来源索引

本仓库：

- `src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt`：定位、监听、迁移、终止、懒启动。
- `src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt`：dispose 与 CLOSING_TO_REOPEN。
- `src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt`、`IdeaMcpSupport.kt`：MCP 保持条件。
- `src/main/kotlin/com/github/izerui/imux/session/NavigationTranscriptIndex.kt`、`SessionTitleRegenerator.kt`：现有读取能力与摘要限制。
- `src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt`、`SessionDriftApplier.kt`：观察点与迁移。
- `src/main/kotlin/com/github/izerui/imux/turn/TurnWatcher.kt`、`TurnSignalParser.kt`：轮次判定与消费性。
- `src/main/kotlin/com/github/izerui/imux/agent/OneShotAgentEvents.kt`、`OneShotAgentCommand.kt`：后台结构化事件不能冒充 TUI 订阅。
- `src/main/js/pi-imux-reporter.js`：现有 Pi 报告范围。
- `.reference/jetbrains/intellij-sdk-docs/topics/reference_guide/embedded_terminal.md`：输入和只读输出模型。
- `.reference/jetbrains/intellij-sdk-docs/topics/basics/persisting_sensitive_data.md`、`disposers.md`：凭据与生命周期。

DeepTutor：

- `deeptutor/partners/channels/{base,wecom,feishu,manager,registry}.py`
- `deeptutor/partners/bus/{events,queue}.py`
- `deeptutor/services/partners/{manager,channel_onboarding}.py`
- `tests/services/partners/test_wecom_channel.py` 及第 12 节所列飞书/manager 测试。

claude-codex-wechat：

- `src/channels/types.ts`、`src/channels/weixin-direct/{adapter,outboundGate}.ts`
- `src/providers/{types,defaultProviders}.ts`
- `src/providers/claude-code/claudeStreamingRunner.ts`
- `src/providers/codex/codexInteractiveRunner.ts`
- `src/session/{messageRouter,providerAutoAttach,currentConversationStore}.ts`

本轮读取的官方上游源码地址（不是 SDK 发布版本或账号权限证明）：

```text
企业微信：WecomTeam/aibot-node-sdk
检查时 main SHA: 80615b987ef69c6028ad764924609247c0725955
https://github.com/WecomTeam/aibot-node-sdk
src/client.ts: replyStream/sendMessage/sendMediaMessage、踢线事件处理
src/ws.ts: aibot_subscribe/ping、ack、超时、disconnected_event
src/types/message.ts: voice.content、mixed.msg_item

飞书：larksuite/oapi-sdk-java
检查时 v2_main SHA: 376ab30197588b8ade03c21a61347b927af5388c
https://github.com/larksuite/oapi-sdk-java/tree/v2_main
larksuite-oapi/src/main/java/com/lark/oapi/ws/Client.java: start/close、重连
README.md: 官方 Java SDK、消息与事件接入

Codex：openai/codex
检查时 main SHA: 970111f6cb62cc02fe36a82ea7d71b37a2889c97
https://github.com/openai/codex
codex-rs/protocol/src/models.rs: ResponseItem/ContentItem
codex-rs/rollout/src/policy.rs: 持久化保留、过滤与裁剪
```

以上 main/v2_main 源码不等于本机所有 CLI/SDK 的已安装版本。实现时应锁定依赖版本，按本机真实会话样本建立脱敏 fixture，覆盖版本差异；未知格式要显示能力受限，不猜测、不改写原记录。

## 14. 仅扫码接入的渠道筛选

筛选对象为 DeepTutor `partners/channels` 中的 16 个实际渠道类；base、manager、registry、lark_http 和 weixin_qr 是基础设施，不重复算渠道。

“满足接入形式”指有证据支持二维码获取、手机确认、自动取得凭据的流程，不代表 imux 已适配、当前账号一定有权限，或真实通讯已经验收。手机上的一次确认属于用户允许的流程；跳转开发者后台手工创建、拷贝凭据、审批配置不属于。

| 渠道 | 核实结果 | 按用户标准 |
| --- | --- | --- |
| 企业微信 | DeepTutor 有 generate -> query_result -> 自动 apply -> 连接的完整源码 | 纳入扫码候选；移植前验证 imux 来源、账号权限、目标用户识别与真实收发 |
| 飞书 | DeepTutor 有 PersonalAgent registration begin/poll，取得 app_id/secret/open_id 后自动保存 | 纳入扫码候选；组织策略不允许时提示不可用，不回退手动配置 |
| 钉钉 | DeepTutor 当前只有 client_id/secret；官方连接器另有扫码授权，并明确可在手机页一键创建新机器人 | 可纳入，但要参考官方扫码实现，不能宣称 DeepTutor 已经做完 |
| 普通微信 | DeepTutor `weixin_onboarding.py` 已完成扫码、token 保存和启动渠道 | 除上述三者外，明确满足扫码接入形式的渠道 |
| QQ 官方机器人 | DeepTutor 要 app_id/secret；腾讯官方 SDK 有 startQrConnect；后续检查手机页确认内置新建机器人并返回连接流程 | 纳入五渠道范围；首次扫码需在手机取名、创建、连接，凭据自动取得。真实新账号收发仍待实测 |
| WhatsApp | DeepTutor 能显示桥接服务送来的 QR，但 start() 只连接外部 Node/Baileys bridge_url | 不纳入现成合格名单；除非后续能封装所有依赖并验证无需用户另行部署 |
| NapCat/QQ | 要连接独立 NapCat/OneBot 服务，配置 ws_url/access_token | 排除；不能把 QQ 客户端扫码当成完整通讯部署 |
| Telegram | token 配置 | 排除现有接入路径 |
| Discord | bot token 配置 | 排除现有接入路径 |
| Slack | bot_token + app_token | 排除现有接入路径 |
| Matrix | homeserver、用户与 access_token 等 | 排除现有接入路径 |
| Mattermost | server_url + bot_token | 排除现有接入路径 |
| Mochat | claw_token | 排除现有接入路径 |
| Microsoft Teams | app_id + app_password 等 | 排除现有接入路径 |
| Email | IMAP/SMTP 服务器与凭据 | 排除现有接入路径 |
| Zulip | site、email、api_key | 排除现有接入路径 |

Lark 是飞书适配器的域名分支，onboarding 也检查 tenant_brand 并切换域名；不单独增加一个“已实测扫码渠道”的计数。其账号和区域适用性仍需验证。

上述“排除”只表示 DeepTutor 当前实现不满足本次免配置要求，不是断言对应平台永远没有 OAuth 或安装应用流程。

### 进一步核实的源码

- DeepTutor `services/partners/channel_onboarding.py: apply()`：企业微信自动补名单，飞书加入扫码用户；前文旧结论已纠正。
- DeepTutor `web/components/partners/ChannelOnboardingPanel.tsx`：轮询发现 ready 后自动调用 apply，不需要手工复制凭据。
- DeepTutor `services/partners/weixin_onboarding.py: start_login/poll_login/_persist_token`：微信扫码后的保存和渠道启动。
- DeepTutor `partners/channels/whatsapp.py: start/_handle_bridge_message`：外部 bridge 依赖与二维码转呈。
- DeepTutor `partners/channels/napcat.py`：已有服务的 WS 接入，而不是负责安装和扫码启动该服务。

官方补充来源：

```text
https://github.com/DingTalk-Real-AI/dingtalk-openclaw-connector
README.md: 一键安装 + 扫码授权，手机页一键创建机器人
src/onboarding.ts: tryScanAuthorizeDingtalk，手机页可创建或绑定

https://github.com/tencent-connect/openclaw-qqbot
src/setup/login.ts: startQrLogin/waitQrLogin，调用 qqbot-connector.startQrConnect
README.zh.md: 扫码无需复制凭据，但仍列出预创建机器人步骤；该疑点已继续追到手机页面核实，见第 15 节
```

即使扫码拿到凭据，也必须等连接认证成功才能显示“已连接”；发送目的地未建立时，要引导在新聊天里发第一条消息，不能把“手机确认”误报为完整通讯已经可用。第一条消息是否可以由平台自动提供，应逐渠道验证。

本次补充执行 DeepTutor 的 `test_channel_onboarding.py` 和 `test_weixin_onboarding.py`，35 项通过。这些是模拟登录/保存流程测试，没有向平台申请真实二维码，也没有扫码或发送真实消息。

## 15. 飞书、钉钉、QQ 首次扫码的进一步核实

本节只做官方文档、源码、发布包和公开手机页面代码的只读核查。没有申请真实绑定任务、创建机器人、使用账号扫码或操作生产连接。

### 飞书

官方 Java SDK `scene/registration/RegisterApp.java` 中的完整流程为：

```text
registration(action=begin, archetype=PersonalAgent, auth_method=client_secret)
  -> device_code + verification_uri_complete
  -> onQRCode 回调，插件可自行画二维码
  -> 手机确认创建/授权
  -> registration(action=poll, device_code)
  -> client_id + client_secret + user_info.open_id
```

SDK 提供 `createOnly(true)`、机器人名称/描述预设以及 `addons`，可将消息权限、事件订阅、卡片回调预填到手机确认页，用户确认后生效。因此有依据将“创建机器人”和“配置通讯权限”并入扫码流程，无需用户手动配置开发者后台。

已有机器人也能通过 appId 发起配置更新授权。上述能力来自当前上游 Java SDK；最终依赖发布版本和组织策略必须实际验证，不声称所有企业账号都可绕过组织审批。

### 钉钉

官方连接器 README 明确说明：显示二维码后，手机扫码点击“一键创建新机器人”完成授权。

已读取 `src/device-auth.ts`、`device-auth-config.ts`、`onboarding.ts`，流程为：

```text
POST /app/registration/init {source}
  -> nonce
POST /app/registration/begin {nonce}
  -> device_code + verification_uri_complete
手机页创建新机器人或选择已有机器人
POST /app/registration/poll {device_code}
  -> SUCCESS + client_id + client_secret
```

官方代码默认服务是 `oapi.dingtalk.com`，默认来源标识为 `DING_DWS_CLAW`。这是存在扫码流程的证据，不等于 imux 已获任何额外接入资格；移植时需核实来源参数适用性。官方实现同时保留手动填写回退，imux 按本需求不提供该回退，扫码失败就清楚提示失败。

### QQ

本次读取腾讯发布的 `@tencent-connect/qqbot-connector` **1.2.0** 包（README、`qr-connect.js`、`qqbot-session.js`），仅下载解析，没有安装或执行包：

```text
startQrConnect(...)
  -> /lite/create_bind_task，携带本地生成的随机密钥
  -> onQrDisplayed(connect.html?task_id=...&source=...)
  -> 手机 QQ 选择/创建机器人并连接
  -> /lite/poll_bind_result
  -> bot_appid + bot_encrypt_secret + user_openid
  -> 本地 AES-256-GCM 解密取得 AppSecret
```

该包不要求预填 AppID/Secret；`source` 可为空，扫码页默认显示第三方机器人。自定义展示品牌另有接洽说明，不是基本扫码必需条件。每次扫码当前返回单个机器人凭据数组，不能把返回数组误解成一次扫码必然绑定多个机器人。

为排除 README 中“先创建机器人”的歧义，继续读取了腾讯公开手机页面及其 `a53a3adb` 静态资源：

- `openclaw/connect.html` 的 RobotListPanel 启用了 `show-create-bot`。
- “创建新机器人”进入 `mobile/create.html?...&come_from=4`。
- 创建页要求非空昵称，可填写简介并确认协议，调用 `createBot`。
- 没有 existing developerId 时走 `ensureDeveloperAfterCreate`；源码存在新用户分支，不要求在扫码前手工传开发者 ID。
- 从连接流程进入时，创建成功记录 botAppid 后 `popBack()` 返回连接页；之后确认连接，SDK 获取凭据。

因此可以更正先前的“尚未找到首次创建路径”：**QQ 存在手机扫码流程内的新建机器人路径，无需用户去电脑开发者后台复制密钥。** 但首次需要手机取名、创建和连接，不是“扫一下只点一个确定”。源码仍包含额度和失败提示，不能据此承诺任意账号必然成功。

可复核来源：

```text
飞书
https://github.com/larksuite/oapi-sdk-java/blob/v2_main/README.md
https://github.com/larksuite/oapi-sdk-java/blob/v2_main/larksuite-oapi/src/main/java/com/lark/oapi/scene/registration/RegisterApp.java

钉钉
https://github.com/DingTalk-Real-AI/dingtalk-openclaw-connector/blob/main/README.md
https://github.com/DingTalk-Real-AI/dingtalk-openclaw-connector/blob/main/src/device-auth.ts
https://github.com/DingTalk-Real-AI/dingtalk-openclaw-connector/blob/main/src/device-auth-config.ts
https://github.com/DingTalk-Real-AI/dingtalk-openclaw-connector/blob/main/src/onboarding.ts

QQ
https://registry.npmjs.org/@tencent-connect/qqbot-connector/-/qqbot-connector-1.2.0.tgz
https://github.com/tencent-connect/openclaw-qqbot/blob/main/src/setup/login.ts
https://q.qq.com/qqbot/openclaw/connect.html
https://q.qq.com/qqbot/mobile/create.html
https://qq-ai.cdn-go.cn/web/neon/-/a53a3adb/q.qq.com_qqbot_openclaw/assets/connect.js
https://qq-ai.cdn-go.cn/web/neon/-/a53a3adb/q.qq.com_qqbot_mobile/assets/create.js
```

采用结论：五个渠道进入设计范围；本轮新增核实的飞书、钉钉、QQ 都有免手工复制密钥的扫码方案，QQ 首次有手机取名/创建步骤。imux 的真实扫码、认证、输入和输出回执验收仍需逐个完成，不能只凭成功拿到凭据就点亮“可通讯”。

## 16. 机器人管理与会话灵活绑定

核实日期：2026-10-26。用户确认的模型：**机器人管理**与**现有会话管理**分开，两者通过可变绑定关系连接。机器人扫码一次长期复用，在 imux 内随时绑定、解绑、换绑。本节取代第 8 节“一个机器人绑定一个会话”中隐含的固定关系；第 8 节的输入、输出与 epoch 规则继续适用。

### 模块与关系

| 模块 | 职责 | 生命周期 |
| --- | --- | --- |
| 机器人管理（应用级） | 扫码添加、名称、渠道、连接状态、配对的本人身份、重新登录、移除 | 跨项目、跨会话长期保存 |
| 现有会话管理 | 新建、运行、恢复、关闭，保持原逻辑 | 不因机器人存在而改变 |
| 绑定关系 | 机器人 ↔ 终端会话，1:1；支持解绑、换绑 | 由用户操作或会话结束改变 |

第一版约束：一个机器人同一时刻只绑定一个会话，一个会话同一时刻只绑定一个机器人。入口有两个：机器人管理中选择会话；会话右键（`Terminal.ReworkedTerminalContextMenu`，与结对编程、交接等已有动作同组）选择机器人。新建普通会话不自动占用机器人。

### imux 代码核实

| 设计点 | 代码依据 | 结论 |
| --- | --- | --- |
| 运行期绑定身份 | `AgentTerminalVirtualFile.tabId` 注释为“一生不变”；`sessionKey`/`sessionId` 可变 | 运行期按 tabId 绑定；持久化记录 Agent 类型 + 原生 sessionId |
| `/new`、`/clear`、新会话落盘 | `TerminalHost.rebindKey` 沿用同一虚拟文件，并广播 `SessionKeyMigratedListener` | 绑定跟随迁移，无需新增探测；桥接只订阅现有广播 |
| 关闭释放 | `AgentTerminalFileEditor.dispose` 在 `CLOSING_TO_REOPEN` 时提前返回，否则调用 `closeSession` | 拖动、分屏不会误释放 |
| 关闭来源 | `closeSession` 同时承接：用户关标签、CLI 自行退出（`closeTabWhenTerminated`）、删除会话（`closeTabBySessionKey`）、项目关闭时编辑器释放 | `closeSession` 本身不区分来源；桥接须结合 `ProjectCloseListener.projectClosing` 自行区分，不能改 `closeSession` 语义 |
| 项目重开 | `RestorableSessionTabs` 恢复标签，进程按 initOnShow 懒启动 | 项目关闭/IDE 退出不应释放绑定；恢复后仍为离线，直到标签真正启动 |
| 机器人存储 | `ImuxSettings` 为应用级 `imux.xml`、`RoamingType.DISABLED` | 机器人列表与绑定放应用级状态，Secret 放 `PasswordSafe`；每个 IDE 安装各有一份 |

释放规则据此定为：

| 事件 | 绑定 |
| --- | --- |
| 用户关闭标签、删除会话 | 释放，机器人回到空闲，向聊天发送“已断开”提示（尽力送达） |
| CLI 自行退出（标签随之关闭） | 同上；平台无法区分崩溃与 `/exit`，按会话结束处理 |
| 拖动、分屏重建 | 不变 |
| 项目关闭、IDE 退出 | 保留为离线；恢复标签后按 sessionId 重新挂上；离线期间远程消息明确回复“会话离线”，不自动启动 CLI |
| 移除机器人 | 解除绑定，不影响会话 |

### 渠道核实：复用可以，并发入口数量不同

此前“五个渠道都能复用、换绑”的结论成立，因为换绑只改插件内路由。但“可以同时有多个机器人”并不对所有渠道成立：

| 渠道 | 同一用户可并存的入口 | 依据 |
| --- | --- | --- |
| 企业微信 | 多个，受成员 20 个额度约束 | 每个机器人独立 Bot ID |
| 飞书 | 多个；DeepTutor 注释“a new scan creates a new app” | 每次扫码新建应用；已有应用可按 appId 走更新授权 |
| 钉钉 | 多个；扫码页可新建或选择已有机器人 | 官方连接器 README 与 `device-auth.ts` |
| QQ | 多个，受开发者认证额度约束；扫码页可选择已有机器人 | 第 15 节 |
| **普通微信** | **实际只有一个** | 见下 |

普通微信核实（`@tencent-weixin/openclaw-weixin@2.4.10` 源码）：

- `fetchQRCode` 会带上本机已有的 bot token（`local_token_list`）请求二维码；同一微信已绑定时服务端返回 `binded_redirect`，客户端提示“已连接过此 OpenClaw，无需重复连接”，不发新凭据。
- 扫码成功后 `clearStaleAccountsForUserId` 删除同一 `ilink_user_id` 的其它本地账号，注释说明是为避免 contextToken 匹配歧义。

因此普通微信在机器人管理中按“每个微信号一个入口”处理：可以随时换绑到不同会话，但不能同时为两个会话各开一个普通微信入口。需要同时远程操作多个会话时，使用其它渠道。

### 本人身份与配对

终端输入相当于在开发机上执行指令，机器人必须只接受本人的消息。各渠道扫码结果是否带扫码人身份：

| 渠道 | 扫码结果中的身份 | 处理 |
| --- | --- | --- |
| 飞书 | `user_info.open_id` | 直接作为本人 |
| QQ | `user_openid` | 直接作为本人 |
| 普通微信 | `ilink_user_id` | 直接作为本人 |
| 企业微信 | 无，`query_result` 只有 `botid`、`secret` | 需配对 |
| 钉钉 | 无，poll 只有 `client_id`、`client_secret` | 需配对 |

DeepTutor 对企业微信扫码后的处理是 `allow_from = ["*"]`（`channel_onboarding.py: apply`），即任何人都可发消息。imux 不能照搬。企业微信、钉钉扫码成功后，IDE 显示一次性配对码，用户在手机上把配对码发给该机器人；首个匹配的发送者记为本人，配对码随即作废。配对完成前机器人显示“待配对”，不能绑定会话。这一步也顺带满足企业微信主动发送要求“用户先在该会话发过消息”的前提。

非本人消息不进入 CLI，也不回复，避免陌生人刷消息消耗机器人的发送额度。

### 空闲机器人与换绑行为

- 会话右键“连接到手机”：只有一个空闲机器人时直接绑定；多个时弹出列表，默认上次使用的；全部占用时列出占用者，用户选择替换某个绑定或去添加新机器人。
- 换绑后，机器人在聊天中发送“当前连接：工程 · Agent · 会话标题”。普通微信的 context_token 可能已失效，提示可能送不到，失败时在 IDE 显示，不阻塞绑定。
- 新绑定从会话当前记录边界开始转发，不倾倒历史。
- 发送额度和回复时效按机器人计算，不因换绑重置。
- 解绑或换绑时，未发出的旧会话内容不转投新会话（第 8 节 epoch 规则）。

### 连接策略

- 已配对的机器人在功能开启时保持连接，即使未绑定：用于回复“未绑定会话”、保持普通微信 context_token 新鲜、接收配对消息。
- 同一机器人只能由一个 IDE 进程连接（企业微信新连接会踢掉旧连接）。机器人列表随 IDE 安装各自保存，多个 JetBrains IDE 同时使用 imux 时，各自扫码添加各自的机器人；同机账号锁用于发现误把同一凭据导入两处的情况。普通微信在两个 IDE 中分别扫码同一微信号的服务端行为未验证，暂按“只在一个 IDE 中使用”提示。
- 被踢下线后标记为“被其它客户端占用”，不自动重连抢占。

### 待用户决定

1. **手机端能否切换会话。** 普通微信只有一个入口，切换只能在 IDE 中操作。若要在手机上切换，需要保留一个不与 CLI 冲突的前缀指令（如 `#imux`），这与第 8 节“普通 slash 文本原样交给 CLI”不冲突，但属于新增的插件指令。建议第一版不做，按需再加。
2. **离线会话收到消息是否允许启动。** 当前设计是不启动、只回复离线。若允许，等同于远程替用户点开该标签（同一进程，不是第二个 CLI），但会改变“不自动初始化恢复标签”的边界。建议第一版不做。

### 本轮验证范围

只读核实 imux 源码、DeepTutor 源码、`@tencent-weixin/openclaw-weixin@2.4.10` 发布包和钉钉官方连接器 `device-auth.ts`。没有修改业务代码，没有真实扫码或收发消息。企业微信、飞书扫码重复执行时会新建还是可选回原机器人，以及普通微信多 IDE 扫码的服务端行为，仍需真实账号验证。

## 17. 代码结构与隔离约束

实施计划：`docs/superpowers/plans/2026-10-26-terminal-channel-bridge-core.md`。

| 层 | 模式 | 位置 | 约束 |
| --- | --- | --- | --- |
| 渠道 | Adapter | `bridge/core/BridgePorts.kt: ChannelAdapter`，实现放 `bridge/channels/` | 不认识 Agent、终端、绑定 |
| 路由 | Mediator | `bridge/core/BridgeRouter.kt` | 不按 AgentType 分支；不依赖 IntelliJ API |
| 绑定 | 注册表 | `bridge/core/BindingRegistry.kt` | 1:1；用户换绑递增 epoch，迁移/重新挂接沿用 |
| 终端 | Facade | `bridge/terminal/ImuxTerminalSessionPort.kt` | 只调用 TerminalHost 公开方法；不启动 CLI |
| 记录 | Strategy | `bridge/transcript/` | 只读；全文不截断；自有读位置 |
| 事件 | Observer | `turn/TurnEvents.kt` | SessionMonitor 只新增两处广播，监听异常不影响原通知 |

依赖方向由 `BridgeIsolationSourceTest` 强制：`bridge/` 以外的源码不得引用桥接包。总开关 `BridgeSettings.enabled` 默认关闭，关闭时不订阅、不轮询、不读取。

### 实施中相对计划的补强

- TranscriptTail.poll 在文件消失时不抛异常
- BindingRegistry.reattach 拒绝占用他人终端
- BridgeRouter 提交异常时清理回显登记、加锁后复核绑定
- TerminalBridgeService 轮询逐会话隔离异常、迁移到新建会话时从头转发（按会话创建时间判断，避免 resume 旧会话倒灌历史）
- 隔离测试防止空集合静默通过

## 18. 机器人管理与企业微信接入（第二份计划）

实施计划：`docs/superpowers/plans/2026-10-27-robot-management-wecom.md`；协议依据：`docs/channel-protocols.md`。

| 模块 | 位置 | 说明 |
| --- | --- | --- |
| 机器人记录与持久化 | `bridge/robot/RobotModels.kt`、`RobotStore.kt` | `imux-bridge.xml` 的 `ImuxBridgeRobots`；凭据只在 PasswordSafe |
| 认主 | `bridge/robot/Pairing.kt` | 6 位配对码，10 分钟有效；扫码带本人身份的渠道直接认主 |
| 生命周期 | `bridge/robot/RobotManager.kt` | 开关打开才连接；绑定以注册表为准写回持久化；重启后按会话恢复 |
| 渠道 SPI | `bridge/channel/ChannelSpi.kt` | 新渠道只实现 `ChannelOnboarding` + `ChannelConnection` 并登记到 `ChannelFactories` |
| 企业微信 | `bridge/channel/wecom/` | 订阅、30 秒心跳、指数退避、被踢不重连、单聊主动推送（chat_type=1、30 条/分钟） |
| 界面 | `bridge/ui/` | 设置 › 工具 › Imux › 通讯机器人；添加机器人对话框；终端右键「连接到手机」 |

### 真实联调清单（企业微信）

1. 设置页添加机器人：选择企业微信 → 手机扫码确认创建 → 对话框出现配对码。
2. 在企业微信里把配对码发给机器人：收到"配对成功"；设置页状态变为"在线"。
3. 在 Codex 或 Claude 会话标签上右键 → 连接到手机 → 选择机器人：手机收到"当前连接：…"。
4. 手机发一句需求：终端出现这句输入并开始执行；助手回复转发到手机；本轮结束收到"本轮完成 · 工具调用 N 次"。
5. 本轮中途若需要权限确认：手机收到"等待你在 IDE 中处理"，在 IDE 确认后继续。
6. 电脑上在同一终端输入：手机收到"电脑端输入：…"。
7. 同一轮中调用一次 IDEA MCP（例如检查文件问题），确认在手机触发的轮次里可用。
8. 换绑到另一个会话：确认框出现；旧会话收到"已断开会话"，新会话收到"当前连接"。
9. 关闭会话标签：手机收到"已断开会话"，机器人回到空闲；再发消息收到"还没有连接会话"。
10. 重启 IDE：机器人自动重连；已连接的会话恢复标签后继续转发。
11. 另开一个 IDE 用同一机器人：先连接的一方提示"已被其它客户端接管"，且不自动重连。
12. 关闭开关：连接断开、记录不再读取；重新开启后恢复，且关闭期间终端产生的输出不会推送到手机（从重新开启时的位置继续）。
13. 在 IDE 代理 / 公司内网环境下扫码添加机器人并建立连接。
14. 机器人被别处接管后（状态"已被其它客户端接管"），在设置页选中它点「重新连接」恢复在线。
15. 对尚未配对的机器人（例如配对码已过期），在设置页点「配对…」：出现新配对码，发给机器人后状态变为"在线"。

已知限制：每个会话主动推送超过 30 条/分钟时，多出的消息会被丢弃（日志里记一条警告），直到下一份计划实现出站合并。

尚待确认的协议事项见 `docs/channel-protocols.md` 第 6 节。渠道范围为企业微信、飞书、钉钉、普通微信；QQ 已决定不纳入，设计文档第 14–16 节中关于 QQ 的内容仅为调研记录。
