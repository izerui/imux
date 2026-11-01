# 手机媒体发送 MCP 实施计划

日期：2026-11-01
状态：工作区媒体取消/提交竞态 P2 已修复；2026-11-02 按用户要求移除顺序发送队列，改为各次发送独立执行。未重建安装包；真实 CLI/IDE 与手机收发验收仍未完成。
本轮验证：最终改动后 `./gradlew test` 通过，2581 项测试，零失败、零错误、零跳过。覆盖独立完成、超时隔离、关闭取消及 P2 取消/超时竞态回归。IDE MCP 检查工具不可用，未执行 IDE lint。
设计依据：[已按审查修订的设计稿](../specs/2026-11-01-mobile-media-mcp-design.md)。
渠道依据：[官方文档、SDK 与当前实现对照](../../channel-comparison.md)。
文档基线提交：`4ce3697`，只包含上述两份文档。

### P2 取消与提交竞态修复

- 修复位置：`bridge/media/MediaSendCoordinator.kt`。提交资格检查、`submitted` 的读写、定时器/取消/失效及发送协程的结果完成共用协调器 `lock`。
- 锁序固定为“协调器锁 → BindingRegistry 绑定锁”。`route.commit` 在协调器锁内调用；不能先持有绑定锁，再进入协调器锁。等待协程结束和网络 IO 均在锁外。
- 两个独立回归用例位于 `MediaCommitRaceTest.kt`，分别在 `isActive` 已检查、`submitted` 尚未写入的窗口触发取消和超时。使用提交屏障、受控计时等待和线程状态判定交错，不用固定 sleep 猜测。
- 红灯验证：未修锁时，两条用例均失败，均为期望 `unknown`、实际 `rejected`。
- 绿灯验证：修复后，两条用例及媒体/Mobile MCP/终端接线相关测试通过；并独立断言已进入一次模拟写出、结果为 unknown、没有文件回退。
- 验证命令：`./gradlew test --tests '*MediaCommitRaceTest' --rerun-tasks`（修复前复现）；修复后执行媒体、MCP、MobileMcpLaunchTest、TerminalHostWiringSourceTest 定向回归。
- 前一轮 `./gradlew test buildPlugin` 在 `ImuxSettingsConfigurable.kt:100` 失败：并行改动中对可空 `AgentType?` 直接访问 `displayName`。本轮该编译阻断已消失，没有修改或回退该设置页。本轮未重建安装包，旧包不能视为包含最新修复。

### 实施进度

以下将代码实现与验收分开记录。后文逐项清单仍作为最终验收单，不因编译通过而整批勾选。

| 任务 | 工作区实现 | 已有验证 | 尚待验证 |
| --- | --- | --- | --- |
| 1 接线与夹具 | 已建立 | 当前 CLI 版本、SDK 签名、测试夹具 | 三个 CLI 的完整真实工具调用 |
| 2 媒体模型与端口 | 已实现 | 编译、上传/发送阶段结果、类型分派测试 | 真机返回值 |
| 3 调用身份与路由 | 已实现 | 独立令牌、撤销、参数禁止覆盖收件人、绑定检查 | IDE 多窗口操作 |
| 4 文件与独立发送 | 已实现 | 并发、超时、取消、解绑、回退、unknown、快照清理测试 | IDE 中途关闭与换绑 |
| 5 HTTP 与 MCP | 已实现 | 实际本地 multipart/二进制 HTTP 测试、JSON-RPC、四工具列表、Origin 校验 | IDE 内置 HTTP 入口的客户端联调 |
| 6 启动注入 | 已实现 | 命令生成、POSIX/PowerShell 分支、Pi 实际扩展 Node 测试、源码接线检查 | 真实 CLI 新建/恢复、Windows 真机 |
| 7 飞书 | 已实现 | 上传接口、资源键、发送守卫、令牌刷新、5xx、共享限频 | 手机实际接收与显示 |
| 8 企业微信 | 已实现 | 分片字节重组、ACK 正文、错误分片、共享限频 | 手机实际接收与显示 |
| 9 钉钉 | 已实现；按新决定改为视频直接文件交付 | 新旧令牌接口、媒体上传、消息模板、直接文件交付/不读封面/不重复发送、能力声明与异常回执 | MP4 文件真机接收 |
| 10 微信 | 已实现 | AES 密文解回原文、密钥编码、CDN 响应头、消息项和上下文、5xx | 上下文与额度、真实图片/文件接收 |
| 11 视频 | 三渠道保留原生路径；钉钉直接文件附件 | 飞书可选封面、微信视频项；钉钉策略变更另验 | 三渠道原生播放、钉钉 MP4 文件接收及回执 |
| 12 收尾 | 自动化与打包已执行 | 钉钉策略调整后 2569 项测试通过，零失败、零跳过；安装包已更新 | IDE lint、三 CLI × 四渠道真机矩阵 |

本机 CLI 版本：Claude Code `2.1.308`、Codex `0.166.0`、Pi `1.4.0`。Codex 实际 `mcp get imux_mobile --json` 已确认临时配置解析出 Streamable HTTP、专用 token 环境变量以及 180 秒工具超时；这不等于已完成服务器握手或 Agent 调用。

本会话未提供 IDEA MCP 的 build/lint/运行配置工具，已使用项目 Gradle 编译和测试；不把编译通过当成 IDE lint 通过。没有读取平台凭据、创建机器人、向真实手机发送测试消息或安装新包到正在工作的 IDE。

钉钉策略调整前，命令 `./gradlew test buildPlugin verifyPlugin` 成功：2567 项测试，零失败、零跳过；`IU-262.10315.125` 与 `IU-263.6800.24` 均为 Compatible，无兼容性错误，报告仍列出实验性/内部 API 使用警告。安装包为 `build/distributions/imux-1.3.2.zip`，已检查包含 `scripts/pi-imux-mobile-mcp.js`。验证器获取线上 API 变更豁免清单时连接被重置，但两份本地 IDE 验证正常完成，没有据此忽略错误。

钉钉策略调整后，相关测试及 `./gradlew test buildPlugin` 通过：2569 项测试，零失败、零错误、零跳过；上述安装包已重新生成。本次没有新增平台 API 引用，未重复执行插件验证器；真实 MP4 文件接收和 IDE lint 仍未执行。

实现补充：

- 飞书官方视频消息的 `image_key` 为可选字段；企业微信视频消息没有必填封面或时长；微信官方客户端的视频发送路径也不提供这两项。钉钉按用户后续决定不再尝试原生视频，直接按文件附件发送。
- 最终消息请求的 HTTP 5xx 按 unknown 处理，即使正文包含错误字段也不自动改发文件，避免请求可能已提交时产生重复。
- 飞书媒体令牌明确失效后复用一次刷新，重试前再次检查绑定；未知结果不重试。
- 终端启动准备保持恢复顺序；期间用户再次点击会话会更新选中意图；尚未打开的恢复标签不会被提前从持久化快照抹掉。

## 1. 交付范围

保留四渠道整体交付，不缩成只支持飞书、企业微信。

| 阶段 | 产物 | 完成含义 |
| --- | --- | --- |
| A | 统一 MCP、调用身份、动态路由、四工具、独立发送、回退与启动注入 | 使用假渠道证明合同和生命周期；不是实际媒体功能交付 |
| B | 飞书、企业微信图片与普通文件 | 第一个可用阶段；音频通过普通文件发送；其它能力如实显示未实现 |
| C | 钉钉、微信媒体链路及四渠道视频 | 实现约定的渠道能力与文件回退，按平台实际格式限制处理 |
| D | 三 CLI 与四渠道验收、兼容性验证、文档收尾 | 全部验收项有真实结果后，才报告整体完成 |

工具名单固定为：

- `get_mobile_status`
- `send_image`
- `send_video`
- `send_file`

音频文件由 `send_file` 发送。原生语音、独立音频工具、自动转码、URL 下载和新增长系统提示词均不在本计划范围内。

执行期间不自动扩大权限、安装 ffmpeg、登录账号、创建机器人、购买套餐或向真实联系人发送测试消息。开发和假渠道测试可独立完成；真机测试应使用已授权的测试机器人和测试文件，不能把这部分待验证项写成通过。

## 2. 全程约束

1. 遵守 AGENTS 与审查后的设计。只增加媒体工具入口，不改 CLI 任务调度，不读取回复中的路径自动上传。
2. `terminal/` 不 import `bridge/`。通过中立启动扩展注入；保留 `BridgeIsolationSourceTest`，不放宽规则。
3. 各渠道不 import 其它渠道实现；公共 HTTP、类型模型、调度端口可以复用。
4. 每次发送独立执行，不保证同终端调用的发送顺序；状态查询直接读取。
5. 120 秒服务端总时限从接纳发送调用开始，包含文件准备、上传、等待限频、回退与最终发送；由每次调用的协程计时器控制。
6. 三个 CLI 对 `imux_mobile` 单独显式设置 180 秒工具超时，不改变其它 MCP 服务的超时。
7. 原生媒体明确失败后自动回退普通文件一次；绑定失效、共同权限/额度已确定不可用以及最终发送结果不明时，遵守设计的例外规则。
8. accepted 表示平台接受；unknown 表示可能已发送，禁止自动重试和文件回退。仅上传成功不能返回 accepted。
9. 任意可读的本地普通文件目录均可使用；不新增项目根路径白名单。只处理参数明确指定的文件。
10. 文件按内容校验，上传使用流式 IO；临时文件归插件管理，用户原文件绝不删除。
11. 平台凭据留在 PasswordSafe/现有连接中；调用令牌只关联本次 CLI 运行实例，不携带平台密钥或收件人。
12. 只用 JUnit 4，中文反引号方法名；分支分开断言；结构测试复用 SourceCode。
13. `tasks.test` 当前已经把 `src/main/kotlin`、`src/main/resources`、`src/main/js` 与构建脚本声明为输入。增加结构测试时核对覆盖，新读其它路径则补输入，不能重复造规则。
14. 先检查 IDE 运行配置；匹配则使用 IDE 执行测试，否则使用本节各任务列出的 Gradle 命令。不要并发执行多个 Gradle。
15. 修改编译型代码/跨文件引用后执行有效的 IDE build，并 lint 所有修改文件；新增平台 API 依约运行 `verifyPlugin`。
16. 本计划不授权实施阶段自动提交业务代码或推送远端。每阶段保留可审查 diff 和验证记录。

## 3. 先锁定的实现细节

### 超时与身份配置

| 客户端 | 配置 | 单位 | 必须测试 |
| --- | --- | --- | --- |
| Claude | `mcpServers.imux_mobile.timeout = 180000` | 毫秒 | JSON 合并、环境变量展开、超时仅作用于本服务 |
| Codex | `mcp_servers.imux_mobile.tool_timeout_sec = 180` | 秒 | 同时注入 URL 和 `bearer_token_env_var`；实际工具可见 |
| Pi | `registerMcpServer("imux_mobile", { timeout: 180, ... })` | 秒 | 实际注册值、reload 不丢配置、工具直接暴露 |

Claude 服务级 `timeout` 已从本轮官方 MCP 文档确认；Codex 的字段从项目本地官方文档核对；Pi 的单位由本地官方 MCP 文档与现有 `pi-imux-idea-mcp.js` 使用方式确认。实施前记录本机 CLI 版本并做配置烟测；不能仅凭文档认定旧安装版本一定识别字段。

专用环境变量拟为 `IMUX_MOBILE_URL`、`IMUX_MOBILE_TOKEN`。Claude 配置保留 `${IMUX_MOBILE_TOKEN}` 字面量，避免被用户 shell 提前展开；Codex 使用环境变量名称，不把 token 实值放进 TOML 参数；Pi 沿用独立扩展的环境读取和 reload 缓存模式。

### 独立发送

2026-11-02 用户明确不要求顺序发送，删除同终端 FIFO、单消费者与排队截止时间逻辑。每次调用独立执行，后到的小文件可以先完成。

在文件复制或网络 IO 之前捕获目标快照，发送前验证原绑定与连接，换绑不改投。活动请求登记仅用于取消、超时、解绑和关闭清理，不用于调度发送顺序。

每次调用冻结自己的文件快照，实际复制时进行前后属性检查。保留渠道自身限频及协议要求的分片顺序；提交与取消共用短状态锁，不持锁上传或发送。

## 4. 阶段 A：通用链路

### 任务 1：核对接线与建立测试夹具

读现有：

- `terminal/AgentCommand.kt`、`TerminalHost.kt`、`SessionDecorations.kt`、`PiReporterScript.kt`。
- `session/PiReportEndpoint.kt`、`PiSessionReportHandler.kt`。
- `bridge/core/BindingRegistry.kt`、`BridgeHub.kt`、`RobotManager.kt`、`channel/ChannelSpi.kt`。
- `channel/Transports.kt`、四个 Connection/Api、现有假 HTTP/WS 测试。
- `.reference/jetbrains/intellij-sdk-code-samples/oauth2/` 与平台扩展点说明；新增 API 具体签名以本机 262 SDK 确认。

实施：

- [ ] 记录当前工作树，不覆盖已有修改。
- [ ] 从实际 CLI 版本验证三项超时配置和 token 环境引用方式，不发平台消息。
- [ ] 确认 `HttpRequestHandler` 异步返回需要的 Netty 请求内容、引用计数和响应生命周期，不能直接照搬立即应答的 Pi 上报 handler。
- [ ] 建立可注入时钟、可控制上传/发送完成的假渠道和屏障，不用固定 sleep 断言尚未发送。
- [ ] 定下 JSON-RPC 版本协商与取消格式；只声明已实现的 MCP 能力。

完成条件：后续各任务不再依赖猜测 API 或未知客户端配置；烟测若失败，记录阻塞的是哪一版本/能力，不悄悄改成全局超时或另一套桥接进程。

### 任务 2：媒体模型、能力与发送端口

拟新增：

- `bridge/core/OutboundMedia.kt`
- `bridge/core/MediaPorts.kt`
- `bridge/core/MediaDeliveryResult.kt`
- 对应 `bridge/core/` 测试。

实现：

- [ ] 类型仅含 IMAGE、VIDEO、FILE；音频是 FILE 的文件内容，不增加原生语音行为。
- [ ] 定义实际展示形式、平台消息 ID（可空）、错误分类、`fallback_used` 与两阶段失败原因。
- [ ] 定义 PreparedMedia/UploadedMedia，资源标识按渠道与用途隔离，不把 image_key 当 file_key。
- [ ] 定义上传与最终发送的分离端口，使协调器能在二者之间做绑定检查。
- [ ] 表示“上传尚未提交消息”“最终请求未提交”“最终请求已进入发送”“收到明确回执”，为 unknown 分类提供事实。
- [ ] 能力返回 native/file 支持、大小、格式、视频所需字段；未实现=false，未知上限=null，标注本地保护值。
- [ ] 保持既有文本 `send(OutboundMessage)` 行为与调用者不变。

验证：纯模型转换、工具返回结构、异常阶段到结果分类；不让“无消息 ID”自动成为 unknown（有的渠道明确成功但不返回 ID）。

### 任务 3：调用身份与目标快照

拟新增：

- `bridge/mcp/MobileCallerRegistry.kt`
- `bridge/media/MobileRouteResolver.kt`
- 在 `BridgeHub`/`RobotManager` 添加受控只读快照方法；必要时在 `BindingRegistry` 添加短同步校验操作。

实现：

- [ ] 注册随机 token，映射 project、tabId、运行代次；没有有效运行实例不能列取其它会话目标。
- [ ] 工具参数不接受目标覆盖字段；未知额外参数按 schema 拒绝。
- [ ] 目标快照绑定 robotId、epoch、owner、连接实例，不只绑定渠道名。
- [ ] “最终发送提交标记”与绑定检查使用明确的短同步边界；不在锁里执行网络 IO，不泄露内部可变 map。
- [ ] 列出所有失效入口：解绑、换绑、同机器人再绑定、断线/重连、移除、CLI 退出、项目关闭、IDE 退出。
- [ ] 复用现有 revision/生命周期事件取消失效请求；每次最终提交仍重新检查。
- [ ] 状态查询不要用 `isWorking` 代替是否在线；正确显示 Connecting、SendFailing 等。

独立测试：同项目两个终端、跨项目同名会话、旧 token、错误 token、同机器人同终端但 epoch 已变化、owner 变化、连接实例变化。断言具体假渠道收到的目标与调用次数。

### 任务 4：文件快照、独立发送与自动文件回退

拟新增：

- `bridge/media/MediaFilePreparer.kt`
- `bridge/media/MediaSendCoordinator.kt`
- 对应纯行为测试。

实现：

- [ ] 在服务端接纳调用时登记活动请求及单次总时限，不先做慢文件 IO。
- [ ] 每次发送独立执行；get_mobile_status 走直接读取路径。
- [ ] 提交前检查取消、运行身份、原绑定和原连接；已中断的请求不得提交。
- [ ] 校验绝对路径、可读普通文件；允许项目外路径；不支持目录、URL、通配符。
- [ ] 不用展示文件名决定实际内容类型；原生格式校验失败可进入文件回退。
- [ ] 流式冻结文件及可选封面，大小上限考虑原生和可回退文件两条路径，避免图片超过原生上限后还没判断文件上限就提前拒绝。
- [ ] 复制前后校验文件身份、大小、修改时间；复制增长越界停止，返回 file_changed/too_large。
- [ ] 把剩余时限传入文件、上传、限频、最终发送和文件回退；任何阶段都不重置为新的 120 秒。
- [ ] 原生失败最多执行一次 FILE 路径；send_file 不递归，文件回退必须沿用同一快照、目标与限频器。
- [ ] unknown、取消、绑定失效、离线、文件不可用，以及明确对两种形式均生效的权限/额度失败不回退。
- [ ] finally 清理各阶段临时文件；取消、异常退出或关闭终端不删除用户原文件。

测试必须分别覆盖：

| 触发 | 断言 |
| --- | --- |
| 首调用上传被屏障阻塞，第二调用到达 | 第二调用可独立完成，首调用仍在等待 |
| 一个请求上传期间超时 | 该请求 rejected/timeout，另一个请求仍可完成 |
| 原生明确拒绝 | 文件路径恰好尝试一次，最终返回实际结果 |
| 原生上传成功但最终消息结果未知 | 没有文件回退，整体 unknown |
| 原生失败后发生换绑 | 原、新渠道都没有收到该调用的文件回退 |
| 文件回退成功 | 整体 accepted，delivered_as=file，保留 primary_error |
| 回退请求已提交后超时 | 整体 unknown，不再执行第三条路径 |
| 同一文件两次不同调用 | 两次合法发送均执行，不按文件哈希去重 |
| CLI 退出时存在并发活动请求 | 各项取消并清理；没有未来重连后的补发 |

### 任务 5：HTTP 上传传输与 MCP handler

拟改：

- `channel/Transports.kt` 或同目录独立上传文件，避免把所有实现塞进单个大类。
- 新增 `bridge/mcp/MobileMcpHandler.kt`、`MobileMcpProtocol.kt`、`MobileMcpTools.kt`、`MobileMcpEndpoint.kt`。
- `plugin.xml` 注册独立 handler。

实现：

- [ ] HTTP 上传提供 multipart 和二进制请求端口，支持文件流、需要的响应头、可取消、剩余时限；沿用现有代理配置。
- [ ] 非 2xx 的业务 JSON 可解析，不能只因 HTTP 200 就成功；不可解析响应按请求所处阶段分类。
- [ ] 不把平台凭据带往 CDN 等不需要认证的地址；不在异常里暴露完整签名 URL。
- [ ] MCP 使用单一路径；正确处理 initialize、initialized、ping、tools/list、tools/call、取消通知、GET 405。
- [ ] tools/list 精确只有四个工具；未实现的渠道能力由 get_mobile_status 如实报告。
- [ ] token 校验、local/Host/Origin 校验和参数校验在读取用户文件前执行。
- [ ] 活动 RPC 按 token + id 区分；重复活动 id 不产生第二份发送；不同 token 的同 id 不互相取消。
- [ ] JSON-RPC 通知返回 202 空体，业务错误放 tools/call 的 `isError`；不在 Netty EventLoop 执行 IO 或等待协程。
- [ ] 不因 HTTP 客户端断开重新执行工具；显式取消通知按发送阶段处理。
- [ ] 超时或取消仅中断对应请求，不让已中断请求之后提交。

验证：本地 HTTP 假服务器检查 multipart 真实字段与文件内容；协议夹具覆盖各方法、HTTP 状态和响应结构；端口不监听外部接口。IDE 内真实 handler 测试与纯分派测试分开报告。

### 任务 6：中立启动扩展、三 CLI 与释放接线

拟新增或修改：

- `terminal/TerminalLaunchContributions.kt`
- `terminal/TerminalHost.kt`、`AgentCommand.kt`、`PiReporterScript.kt`
- `bridge/mcp/MobileLaunchContributor.kt`
- `src/main/js/pi-imux-mobile-mcp.js`
- `plugin.xml` 与脚本打包配置（按实际 `build.gradle.kts` 规则）。

实现：

- [ ] 中立扩展返回结构化 MCP 配置/环境/扩展路径，不返回自由拼接的 shell 字符串。
- [ ] 端口后台准备一次，本次命令和环境共用同一份 token/URL；启动失败时注销预留身份。
- [ ] 每个真实 CLI 运行实例对应一个 lease；准备后未成功启动也必须释放。
- [ ] 绑定到真实 TerminalView 的释放；拖动、分屏重建 FileEditor 不释放；CLI 退出立即撤销。
- [ ] `/new`、`/clear` 沿用身份；恢复真实新进程分配新身份。
- [ ] Claude 合并 idea/mobile 配置，注入 `timeout:180000`；保持原有初始 prompt `--` 分隔符。
- [ ] Codex 同时注入 url、bearer_token_env_var、tool_timeout_sec=180；不修改用户全局配置。
- [ ] Pi 使用独立缓存 key，注册 timeout=180、直接暴露；reload 后配置仍在。
- [ ] IDEA MCP 关闭时，mobile 仍接入；单次后台 Agent 不自动获得 mobile 配置。
- [ ] 端点准备失败时 CLI 仍能启动，但显示媒体工具未接入；安装前旧进程需重开一次，不强制退出。
- [ ] 补齐必要提示的全部语言资源，不新增长提示词或每次发送确认弹窗。

验证：

- 扩展 `AgentCommandTest`，分别调用 POSIX/PowerShell 的实际命令生成函数，并解析/核对最终配置、超时与 secret 字面量；不能只测手写 JSON。
- 仿照 `PiIdeaMcpBehaviorTest`，在 Node 里加载实际新扩展，断言注册名、headers、180 秒、reload 行为、没有额外 prompt hooks。
- `BridgeIsolationSourceTest` 与渠道隔离测试继续通过；新增结构断言复用 SourceCode。
- 三种实际 CLI 用假渠道查询状态/发测试文件，验证工具可见且 token 指向正确 tab。此时不向任何真实通讯账号发送。

## 5. 阶段 B：首个可用阶段

### 任务 7：飞书图片和文件

拟新增 `channel/feishu/FeishuOutboundMedia.kt`，扩展该渠道 Api/Connection。

- [ ] 核对并实现图片与文件 multipart 上传，分别取 image_key/file_key。
- [ ] 普通文件（包括音频附件）使用与 file 消息匹配的上传类型；不把音频上传成原生 audio 后再当 file 发。
- [ ] 在 Connection 中最终发送 image/file，共用现有文本限频与 token 缓存。
- [ ] 图片权限/大小失败时检查 FILE 能力后回退；平台全局权限/额度失败则按设计结束。
- [ ] 已明确失效的 token 最多刷新一次；原消息回执不明不重发。
- [ ] 分别检查上传和消息 API 业务码，成功消息 ID 有则返回，无则不虚构。
- [ ] 能力暂时只标已完成的 image/file；video 不提前标原生可用。

验证：上传字段与字节、图片/文件不同 key、所有权路由、限频共享、30 MB/10 MB 对应边界、中文文件名、普通音频附件、回退重传类型、超时阶段。

### 任务 8：企业微信图片和文件

拟新增 `channel/wecom/WecomOutboundMedia.kt`，扩展 Protocol/Connection。

- [ ] 复用现有 WS 连接和 req_id 回执表；扩展 ACK 模型保留上传结果 body，不能破坏旧文本 ACK 处理。
- [ ] 实现 init → chunk → finish，遵守单片 512 KB、最多 100 片、各媒体类型实际大小限制。
- [ ] 文件流按片读取，不全量 base64 常驻内存；取消时停止后续分片和最终消息。
- [ ] 最终 image/file 使用主动命令，显式 chat_type=1 和本人 userid。
- [ ] 文件普通上限 20 MB、图片 10 MB，最少字节等限制来自对应素材接口。
- [ ] 素材上传独立频控，最终消息共用现有文本频控；只拿到 media_id 不能报告已发送。
- [ ] 旧会话 ACK 不得完成重连后请求；断线后不把旧请求接到新 WS。

验证：多分片顺序、req_id 配对、失败分片不发最终消息、取消清理、图片回退为 file 后重新取得正确资源类型、小时频控服务端错误、重连不补发。

阶段 B 验收记录应明确写“飞书/企业微信图片文件已完成”，其它渠道与 video 的能力仍如实报告未完成；不可把这一步写成四渠道功能完成。

## 6. 阶段 C：补齐四渠道

### 任务 9：钉钉图片和文件

拟新增 `channel/dingtalk/DingtalkOutboundMedia.kt`，扩展该渠道 Api/Connection。

- [ ] 核对媒体上传所需 token 与旧、新 API 域名；不假设现有 accessToken 可直接套用任意上传接口。
- [ ] 上传图片后使用 sampleImageMsg，普通文件使用 sampleFile；robotCode 与本人 staffId 沿用现有路径。
- [ ] 交集校验上传页和消息模板页允许的后缀/格式，不把“可上传”当“可作为该消息发送”。
- [ ] 钉钉视频按最新决定直接尝试 MP4 普通文件；这是用户要求的发送策略，不将它记录为官方已保证支持的文件类型。平台拒绝时返回错误，不改后缀、不重复发。
- [ ] HTTP 200 仍检查 invalidStaffIdList/flowControlledStaffIdList；不能漏判部分失败。
- [ ] 明确的原生失败自动 FILE 回退一次，重新计算 API 请求开销；不新增额度计数器或宣称发送不计量。
- [ ] 接收额度受阻与发送链路是否可用分开报告；不能因 SendFailing 一律禁发。
- [ ] 音频通过 send_file；若当前模板不接受其格式，明确返回 unsupported，而不是改扩展名。

验证：实际请求 msgKey/msgParam、两种收件人名单、媒体资源类型、类型限制、共享限频、回退次数、有效会话和权限错误。

### 任务 10：微信图片和文件

拟新增 `channel/weixin/WeixinOutboundMedia.kt`，扩展该渠道 Api/Connection。

- [ ] 按官方客户端核对 getuploadurl、明文/密文长度、MD5、AES key 编码和文件 key 生成。
- [ ] AES-128-ECB/PKCS7 流式加密到临时文件，再上传 CDN；不把用户原文件改成密文。
- [ ] 正确使用完整上传 URL 或上传参数，并验证所需成功响应头，不只看 HTTP 200。
- [ ] 构造 image/file item；file_name、len 等字段的单位、类型与官方客户端一致。
- [ ] 复用当前本人 context_token 与失败提示。上下文缺失/过期行为跟既有文本路径一致，但最终发送未知时不得自动重试或回退。
- [ ] “每次输入后最多 10 条”只作为经验提示：媒体、文件回退与最终文字都可能消耗同一窗口；不做本地计数、排队等额度恢复。
- [ ] 本地未知官方大小上限采用设计的 100 MiB 保护并明确标注；实际服务端拒绝仍按结果处理。
- [ ] 不引入第二个轮询器、二维码流程或机器人 token 存储。

验证：上传与下载密码格式可用确定样本往返、PKCS7 对齐边界、签名响应头、ret/errcode 非零、context_token 用的是原目标、最终 -14 与网络未知的区别、临时密文清理。

### 任务 11：四渠道视频与能力收尾

在各渠道 OutboundMedia 中添加 VIDEO，不另建一套会话路由。

- [ ] 飞书使用官方 media 消息要求的资源/封面字段，文件类型正确；不能只上传 MP4 就假定全部字段已齐。
- [ ] 企业微信上传 video，再主动发 video，采用该类型 10 MB 上限。
- [ ] 钉钉 `send_video` 直接使用 `type=file` 上传和 `sampleFile` 发送，不读封面、不要求时长、不调用 sampleVideo；能力查询 native=false、delivery_as=file。
- [ ] 微信沿官方视频发送路径构造视频媒体项，不把视频当文件项却报告原生视频成功。
- [ ] 各渠道 required_fields 如实反映实际必要字段；钉钉视频为空。原生路径缺少必需字段时按已批准规则回退 FILE。
- [ ] 封面失败属于原生视频失败，可回退主视频文件；不能误把封面图片当成要发送的文件。
- [ ] 文件回退不强制改名或转码；钉钉 MP4 普通文件支持不确定时，只按用户要求尝试，不承诺平台一定接受。
- [ ] 钉钉不报告原生视频可用；其它渠道对外说明区分代码支持与真机验收，不能将协议测试当作手机播放验收。

验证：四渠道分别断言实际消息种类、资源字段、元数据与单位；缺封面、无时长、格式错误、视频超限但文件可接收、两条路径都超限分别测试。没有支持的媒体探测能力不自行安装依赖。

## 7. 阶段 D：集成与最终验收

### 任务 12：真实客户端、平台兼容性与文档

自动化：

- [ ] 执行当前任务相关测试；修复错误后再跑完整 `./gradlew test`，不在无新增疑点时重复跑全套。
- [ ] IDE build（有效时）、lint 所有修改代码文件。
- [ ] `./gradlew verifyPlugin` 覆盖 `.verify/ides/` 中全部目标，逐份记录结果；没有目标 IDE 必须报告未验证。
- [ ] 检查插件产物包含新 Pi 脚本、plugin.xml 扩展注册、语言资源；不遗漏脚本打包。
- [ ] 检查没有 bridge 反向依赖、渠道交叉 import、token 实值日志、新持久发送队列或长提示词。

实际客户端矩阵：

| CLI | 新建工具可见 | 恢复工具可见 | 状态正确 | 180 秒配置生效 | 同项目两终端隔离 |
| --- | --- | --- | --- | --- | --- |
| Claude | 待验 | 待验 | 待验 | 待验 | 待验 |
| Codex | 待验 | 待验 | 待验 | 待验 | 待验 |
| Pi | 待验 | 待验 | 待验 | 待验 | 待验 |

超时验证用假渠道维持超过旧默认时限、低于 120 秒的真实调用，并验证总时限到达时结束；不用真实平台大文件慢传来“碰运气”证明超时。正常手工验证不能由模拟注册对象代替。

真实渠道矩阵：

| 渠道 | 图片 | 普通文件 | 音频附件 | 原生视频 | 可回退场景 | unknown 不补发 |
| --- | --- | --- | --- | --- | --- | --- |
| 飞书 | 待验 | 待验 | 待验 | 待验 | 待验 | 待验 |
| 企业微信 | 待验 | 待验 | 待验 | 待验 | 待验 | 待验 |
| 钉钉 | 待验 | 待验，单列 MP4 文件 | 按实际格式验 | 不做，直接发文件附件 | 图片回退按实际格式验；视频文件不重复发 | 待验 |
| 微信 | 待验 | 待验 | 待验 | 待验 | 待验 | 待验 |

同时逐项验证：未绑定→绑定、并发发送时换绑、上传时解绑、关闭 CLI 取消活动发送、断线后不补发、旧 CLI 没有新配置时明确提示。已经进入平台发送阶段的取消只报告真实结果，不承诺撤回。

视频专项用例：

- 钉钉只传 MP4 路径：确认只有文件上传和 sampleFile 消息，无封面、时长或原生视频请求；手机接收结果按文件附件记录，不能要求原生视频样式。
- 钉钉带无效封面路径：直接文件路径不读取封面；结果 requested_kind=video、delivered_as=file、fallback_used=false。
- 钉钉 MP4 被平台拒绝：报告真实错误，只尝试一次文件发送；不以“已回退”当成功。
- 飞书、企业微信、微信：分别不传封面与时长验证各自原生视频路径，分别记录手机显示和播放；飞书额外验证可选封面。

收尾：

- [ ] 更新对照文档中的“imux 未实现”项，只改已实际完成且验收过的能力。
- [ ] 设计/计划状态注明实现、自动化验证、真机验证分别进度；不能以阶段 B 结果覆盖其它行。
- [ ] 发布说明解释四工具、音频走文件、文件回退、旧 CLI 需正常重开一次及平台限制。
- [ ] 交付报告列变更、测试、真实矩阵、尚未验证的账号权益；没有全部完成就保留未完成项。

## 8. 建议验证命令

以下是无匹配 IDE 运行配置时的回退命令；类名采用拟新增路径，实施时与最终类名核对。各条按任务顺序执行，不同时启动多个 Gradle。

```bash
# A：合同、身份、独立发送、回退、MCP
./gradlew test --tests 'com.github.izerui.imux.bridge.media.*' --tests 'com.github.izerui.imux.bridge.mcp.*'

# A：启动参数、Pi 扩展与隔离
./gradlew test --tests '*AgentCommandTest' --tests '*PiMobileMcpBehaviorTest' --tests '*BridgeIsolationSourceTest' --tests '*ChannelIsolationSourceTest'

# B/C：只跑本次修改的渠道，再跑需要的传输测试
./gradlew test --tests 'com.github.izerui.imux.bridge.channel.feishu.*'
./gradlew test --tests 'com.github.izerui.imux.bridge.channel.wecom.*'
./gradlew test --tests 'com.github.izerui.imux.bridge.channel.dingtalk.*'
./gradlew test --tests 'com.github.izerui.imux.bridge.channel.weixin.*'

# D：全量测试和平台兼容
./gradlew test
./gradlew verifyPlugin
```

其中模拟平台失败、取消和多并发需要独立屏障与可注入 IO。不得用“构造一个 expected 集合，然后遍历 expected 断言自身存在”的方式证明工具或渠道全部实现。

## 9. 核对记录与来源

最初编写计划时只提交了研究与设计文档。用户随后要求“继续”，现已执行工作区实现与自动验证，进度见顶部表格。业务代码与本计划尚未提交；真实收发未验证，不将后文验收清单整批标为完成。

新增确定项：

- Claude 官方 MCP 文档明确支持服务级 `timeout`，单位毫秒，覆盖该服务的工具超时，不需要修改全局 MCP_TOOL_TIMEOUT。
- Codex 本地官方 MCP 文档给出 `tool_timeout_sec`，默认 60 秒；本计划明确设置为 180。
- Pi 本地官方 MCP 文档给出服务级 `timeout`，单位秒；现有 IDEA MCP 扩展已使用该配置。

```text
Claude 本轮直接核对：
https://code.claude.com/docs/en/mcp.md
定位：Time limits on MCP tool calls；per-server timeout

MCP 协议与渠道来源：
见设计稿第 13 节、docs/channel-comparison.md 来源索引
```

本地依据：

- `docs/codex/pages/docs/extend/mcp.md`
- `docs/codex/pages/docs/config-file/config-reference.md`
- `docs/pi/repo/coding-agent/docs/mcp.md`
- `src/main/js/pi-imux-idea-mcp.js`
- `src/test/kotlin/com/github/izerui/imux/terminal/AgentCommandTest.kt`
- `src/test/kotlin/com/github/izerui/imux/terminal/PiIdeaMcpBehaviorTest.kt`
- `src/test/kotlin/com/github/izerui/imux/bridge/BridgeIsolationSourceTest.kt`
- `build.gradle.kts` 的 `tasks.test` inputs。
