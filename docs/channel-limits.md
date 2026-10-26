# 五个扫码通讯渠道的限制核实

核实日期：2026-10-26。本文补充 [终端通讯设计](terminal-channel-bridge-feasibility.md)。

第二轮补查：已进入钉钉官网和飞书版本页核对动态内容，沿钉钉文档目录取得官方正文，并读取腾讯微信官方后端协议及已发布的 2.4.10 包。下面区分具体模式的官方限制、通用套餐规则、客户端策略、项目经验值；数字的适用范围与数字本身同样重要。

对象是企业微信长连接智能机器人、飞书应用机器人、钉钉扫码创建/绑定的应用机器人、普通微信 iLink、QQ 官方机器人。群自定义 webhook、公众号、企业微信客服和 QQ 频道限制不能直接套到这些通道。

本轮直接读取官方文档、SDK 源码以及本地参考实现，没有用真实账号测额度。文档未公布或未核实到的数值标为未知，不等于无限制。

## 1. 对照表

| 渠道 | 创建数量 | 发送数量/频率 | 回复时效与主动通知 |
| --- | --- | --- | --- |
| 企业微信 | 官方通用智能机器人帮助页：普通成员 20 个，企业 300 个。页面属于普通模式栏目，未找到扫码 API 模式单列额度，应以实际账号剩余名额为准 | 长连接官方协议：单会话回复和主动发送合计 30 条/分钟、1000 条/小时 | 收到消息回调后 24 小时内可回复；单个流式回复从首次发送起 10 分钟。主动发送走独立命令，先要用户在该会话给机器人发过消息 |
| 飞书 | 未查到 PersonalAgent 扫码机器人统一的个人/企业创建上限；另已确认每群最多 15 个机器人，这不是创建上限 | 发送接口 1000 次/分钟、50 次/秒；同用户 5 QPS，同群由群内机器人共享 5 QPS；可能另受租户 API 月额度影响 | 发送 API 不要求引用入站消息；用户需在机器人可用范围，群聊需机器人在群且能发言。文字/富文本最多编辑 20 次，编辑时限由管理员配置 |
| 钉钉 | 未查到本次扫码机器人模式明确的统一上限 | 企业内部应用通用计费页仍列标准版 API 10000 次/月、Webhook/Stream 5000 次/月、20 QPS；现行官网的新付费 AI 版本又列付费 API 不限量。两者须按套餐和应用类型区分，不能直接当扫码机器人的统一额度 | 回调包含 sessionWebhookExpiredTime，应按返回时间处理临时回复地址；主动发送使用独立 OpenAPI，不能将 webhook 到期当作所有发送都到期 |
| 普通微信 | 官方支持一个网关多个微信账号；官方客户端对同一个微信 userId 只保留最新绑定。没有证明同一微信能创建多个独立机器人 | 项目规则：24 小时最多 10 条；另一个项目经验值：约 7 条/5 分钟。官方协议未给出对应固定数字 | 项目经验值：上下文闲置约 90–160 秒失效。官方建议回传 context_token，但客户端缺失时仍尝试发送；服务器是否接受不由客户端决定 |
| QQ | 官方指南：个人认证开发者 5 个；企业认证开发者 10 个，同企业主体下所有认证账号合计 50 个。未认证扫码账号额度未见明确数字 | 单聊主动消息：同用户 20 条/分钟、1000 条/日；认证机器人 10 QPS，未认证机器人 5 QPS 且 30 条/分钟（机器人维度） | 单聊被动回复 60 分钟，每条入站可回复 4 次；主动发送不依赖这条 msg_id，但用户可以关闭主动消息 |

QPS 表示每秒请求数，QPM 表示每分钟请求数。消息数、API 请求数、流式更新次数和卡片更新次数不是天然相同的计量单位。

### 新补齐的限制维度

| 维度 | 已确认内容 | 不能作出的推断 |
| --- | --- | --- |
| 同账号连接数量 | 企业微信一条；飞书最多 50 条，但同一事件只分发给随机一个客户端；QQ 读取 Gateway 返回的 session_start_limit | 飞书 50 条连接不等于 50 个机器人，不能拿它做广播 |
| 消息编辑/流式更新 | 飞书文本最多 20 次编辑；卡片 PATCH 限 14 天、单消息 5 QPS；CardKit 单实体 10 次/秒；QQ 单聊流式接口 50 QPS | 不同流式接口的请求次数不等于普通新消息条数 |
| 入站超时与重投 | 飞书处理回调需 3 秒内完成，不应等 AI 执行结束；钉钉事件用量耗尽可返回 20001 且无原文 | 入站消息到达不是一定成功送入 CLI，应检查错误及去重 |
| 内容大小 | 企业微信 markdown 20480 UTF-8 字节；飞书文本请求 150 KB、富文本/卡片 30 KB；钉钉群消息 msgParam 15000 字节；微信官方客户端文本分片值 4000 | SDK 分片值不是平台硬上限，字节数也不是字符数 |
| 附件 | 企业微信图片/视频 10 MB、AMR 语音 2 MB、普通文件 20 MB；飞书消息图片 10 MB、文件 30 MB | 分片协议可容纳约 50 MB，不表示企业微信允许上传 50 MB 普通文件 |
| 扫码与登录异常 | 微信协议可能要求填写手机显示的验证码；官方客户端遇到 -14 暂停一小时 | “只需扫码”有异常验证分支；一小时暂停不是聊天内容有效期 |

## 2. 企业微信

官方《智能机器人长连接》明确了以下规则：

- 同一 Bot ID 同时只能有一条有效长连接；新连接订阅成功会踢掉旧连接。
- 接收回调后 24 小时内可以向对应会话回复。
- 回复与主动推送共同受单会话 30 条/分钟、1000 条/小时限制。
- 流式消息从首次发送起有 10 分钟超时限制。
- `aibot_send_msg` 是独立主动发送命令，但前提是用户曾在对应会话给机器人发过消息。
- 主动发送段落没有明确写出同样的 24 小时时限；不能擅自解释为主动发送永久不限时，也不能把回复窗口直接套过去。

其它相关限制：

- 资源下载链接有效 5 分钟，附件应及时下载，不能等 AI 执行完再取。
- 临时上传素材有效 3 天；素材上传会话有效 30 分钟。
- 素材上传频率单机器人 30 次/分钟、1000 次/小时。
- SDK 标注流式文本最长 20480 字节；这是流式消息字段限制，不能自动推广到全部消息类型。

第二轮直接检查长连接协议还确认：

- 主动 markdown 的 content 也明确限制 20480 个 UTF-8 字节，不再只是 SDK 的流式字段说明。
- 欢迎语回复和模板卡片点击事件回复需在 5 秒内发出，不能等 AI 完成再处理事件响应。
- 临时素材最低 5 字节；图片 10 MB、语音 2 MB、视频 10 MB、普通文件 20 MB。
- 素材格式分别包括图片 png/jpg/jpeg/gif、语音 amr、视频 mp4。
- 每片最大 512 KB、最多 100 片只是分片限制；不能据此把所有文件最大值算成 50 MB，仍要同时遵守类型上限。
- `finish=true` 之后不能继续更新同一个流式消息；超时也会自动结束。

数量方面，本次终于读到了官方帮助页 21663 的 20/300 规则。该页标题为《如何使用智能机器人》，归在“普通模式机器人”栏目，同时包含 API 机器人统计等说明。可以作为一般数量规则参考，不能保证 API 扫码账号没有单独限制或管理员策略。

同一帮助中心的 21669 明确允许管理员控制哪些成员能配置长连接机器人。因此“支持扫码”不等于企业管理员一定已授权当前成员使用。

设计影响：一个 IDE 统一管理 Bot ID；批量合并工具进度；超过 10 分钟的任务不能一直依赖同一流式回复，需另发适用的后续消息。任何后续发送失败都应保留未送达状态。

## 3. 飞书

官方发送和回复接口均列出：

- 接口频率：1000 次/分钟、50 次/秒。
- 同用户：5 QPS；同群：群内机器人共享 5 QPS。
- 文本请求体最大 150 KB，卡片和富文本请求体最大 30 KB。不是“正文字符数”。
- 可传 uuid 做去重，同一 uuid 在 1 小时内至多成功发一条。

发送 API 是按 receive_id 指定目标，不要求先有入站消息 ID。权限与可用范围仍必须满足。未在查阅的发送/回复文档发现统一的 24 小时会话窗口；这不代表所有卡片、更新、撤回等接口也都无时效。

### 月度额度不能忽略，也不能拿旧公告写死

官方《自建应用 API 调用量上限调整说明》说明：

- 额度按单租户内所有企业自建应用汇总，不是每个机器人独享。
- 部分业务 API 不计入；计费目录和账号权益需要核对。
- 用尽可返回 HTTP 429、错误码 99991403。
- 页面仍列着 2024 年起的免费版 10000 次/月历史规则，但当前调整说明只明确写到 **2026 年 6 月限时 100 万次**。

当前日期是 2026-10-26，不能拿“2026 年 6 月限时”当作本月额度，也不能忽略后续权益调整，直接宣布本月只有 10000。实际值要看租户当前权益，扫码 PersonalAgent 的适用应用类型也需核对。

设计影响：区分秒/分钟限频与月额度耗尽；后者不能通过几秒退避解决。状态应显示“额度耗尽”，本地 CLI 继续运行。API 月额度与机器人创建数量是不同概念。

### 群、连接与入站处理

官方“将用户或机器人拉入群聊”接口明确：一次最多邀请 5 个机器人，加入后群内机器人不得超过 15 个。不能拿这个 15 推断个人只能创建 15 个。

官方长连接说明明确：

- 每个应用最多 50 个 client 连接。
- 多连接是集群分发，不是广播；某个事件仅随机一个 client 收到。
- 收到事件需在 3 秒内处理完成且不抛异常。原始消息落到桥接收件箱后应及时返回，不能 await AI 完成。
- 事件概述说明失败按 15 秒、5 分钟、1 小时、6 小时最多重推 4 次。

因此，多 IDE 进程拿同一个飞书账号各自建立连接，可能分走彼此的消息，不能因为没有踢线就认为互不影响。

### 更新、卡片、附件

| 操作 | 官方页面限制 |
| --- | --- |
| 编辑文本/富文本（PUT message） | 单消息最多编辑 20 次；必须是自己发送；可编辑时间由管理员设定 |
| 更新已发送卡片（PATCH message） | 14 天内；单消息 5 QPS；不支持批量发送消息/仅特定人可见卡片 |
| CardKit 流式/组件操作 | 单卡片实体 10 次/秒；具体 content 接口另列 50 次/秒、1000 次/分钟 |
| 卡片交互 | 3 秒内立即回应；异步更新另有 30 分钟路径 |
| 上传消息文件 | 30 MB，不能是空文件 |
| 上传消息图片 | 10 MB，并有格式/分辨率要求 |
| 批量发送接口 | FAQ 指明单应用每日 50 万条；这是批量接口的日额度，不是普通发送消息接口的统一额度 |

CardKit 概述一方面写单实体 10 次/秒，字段说明又称 streaming_mode 不触发接口 QPS。应按明确的接口/实体上限保守实现并处理限频，不能截取其中一句宣布无限更新。

第二轮也查看了飞书现行公开版本对比页，未获取到可以替代账号权益的本月 API 数字；PersonalAgent 统一创建名额仍未找到可靠官方公开值。

## 4. 钉钉

已核实的官方 Stream SDK `ChatbotMessage` 从回调读取：

```text
sessionWebhook
sessionWebhookExpiredTime
```

因此可以确定临时回复地址存在服务端提供的到期时间；不能从字段存在推导“固定 2 小时”或“固定 24 小时”。

DeepTutor 的主动发送路径为：

```text
POST /v1.0/robot/oToMessages/batchSend
POST /v1.0/robot/groupMessages/send
```

它们与 sessionWebhook 回复不是同一个接口。官方连接器也存在 sessionWebhook 发送路径，适配时必须知道实际使用哪个模式。

### 第二轮取得的通用计费与错误规则

通过浏览器实际加载官方文档，取得其公开目录和正文地址；此前直接猜测的“robot-message-sending-frequency-limit”页面没有正文，不能作为有效来源。本轮改读实际目录中的 `dingtalk-application-development-platform-billing-model` 等文档。

官方《计费模型》当前可访问正文列出：

| 企业内部应用通用套餐 | API 调用/自然月 | Webhook/Stream 调用/自然月 | QPS |
| --- | --- | --- | --- |
| 标准版 | 10000 | 5000 | 20 |
| 专业版 | 50 万 | 5 万 | 40 |
| 专属版 | 550 万 | 10 万 | 60 |

这是一张企业内部应用通用表，不是已核实的扫码个人机器人专属表。机器人入站、出站可能分别涉及不同计量面；基础免计量接口和事件有清单，不应把所有流量一律相加。

官方接收消息页明确：Webhook/Stream 用量超额时可收到 `errorCode=20001`，没有正常的 text/content。官方批量单聊发送接口还会返回 `flowControlledStaffIdList`，不能只因 HTTP 成功就认为全部用户都投递成功。

官方限流页列出：

- 标准版按应用及接口限制 20 QPS，对应 `Forbidden.AccessDenied.QpsLimitForAppkeyAndApi`。
- 组织月额度错误 `Forbidden.AccessDenied.ApiCountLimitForOrg`。
- IP 维度 20 秒 10000 次触发 5 分钟保护，可能返回 HTML 而非 JSON。
- 平台整体接口也可能限流，即使本机没有超标。

### 不能忽略套餐变化

2026-10-26 实际打开钉钉官网，页面已经展示 AI 基础版/AI 高级版/AI 尊享版/AI 旗舰版，其中 AI 基础版列有“付费API接口调用不限量”。

因此，不能拿上面的传统标准/专业/专属表，断言所有现行付费 AI 版本仍有同样月上限；也不能反向把付费版本的“不限量”解释成所有免费扫码机器人无限发送，或 QPS 不再受限。

另读到官方《钉钉PaaS“白名单”的不限量额度策略调整通知》：旧白名单自 2025-09-01 调整，过渡期结束后于 2025-12-01 开始执行相应额度策略。它与新套餐的权益属于不同范围，不应混成同一条政策。2022 年旧公告已经被官方标为历史文档，本报告不以它单独证明 2026 年权益。

### 消息体、批量与会话条件

- 群发送 API 的 `msgParam` 限制 15000 字节。
- 单聊 batchSend 正文和参数表均写每次最多 20 个 userId；错误码解释却残留“超过100”。这是同页矛盾，客户端应按更明确的 20 个实现，不擅自放宽。
- 主动发送可能返回 `chatbotId.notAllow.sendOTO`、`robot.oto.notExist` 或 `session.expired`；“存在主动 API”不等于未建立任何关系即可发送。
- 临时 sessionWebhook 仍以回调里的绝对到期时间为准，不从文档示例时间戳反推固定有效期。

以下仍没有取得足够的适用证据：

- 扫码创建模式的个人机器人数量。
- 通用企业应用套餐与扫码创建的具体账号/应用类型如何对应。
- 该扫码模式单会话每分钟/每日消息条数。
- 用户长时间未发言后的主动通知权限是否对所有扫码账号一致。

网上常见的“20 条/分钟、限流 10 分钟”可能针对群自定义 webhook；“120 次/分钟”的历史资料也不能未经版本/接口确认套到扫码模式。SDK 内的重试等待和 access_token 刷新时间不是消息发送额度。

设计影响：按 sessionWebhookExpiredTime 切换/拒绝过期回复；主动 API 失败按真实错误分类，不猜统一固定额度。首批真实账号联调需专门补齐这些数值。

## 5. 普通微信

本地参考工程存在两组明显不一致的描述：

- `claude-codex-wechat/docs/wechatbot-usage-guide.md` 第 6.3 节写“24 小时、最多 10 条”，但它同时说明这些精确数字不体现在客户端代码和官方文档中。
- DeepTutor `partners/channels/weixin.py` 注释记录约 90–160 秒上下文闲置失效、约 7 条/5 分钟的经验性限制。

这两组都不能当作已确认的统一平台合同。此前对话中把 24 小时/10 条说成确定的平台硬限制不够准确，应撤回该确定性。

### 本次新增：腾讯官方协议和 2.4.10 发布包

找到 `Tencent/openclaw-weixin` 官方仓库及其 `docs/protocol_zh_CN.md`，并只读检查 `@tencent-weixin/openclaw-weixin@2.4.10` 发布包。变更日志标注该版本发布于 2026-10-10。

| 项目 | 官方文档/代码实际说明 | 限制属性 |
| --- | --- | --- |
| 多账号 | 一个网关同时运行多个微信账号 | 客户端具备多账号能力；不等于同一个微信号有多个独立机器人 |
| 同一用户重复绑定 | `clearStaleAccountsForUserId` 删除相同 userId 的其它本地绑定，只留最新账号 | 官方客户端行为；不是证实服务端机器人总额度 |
| context_token | 建议回复时回传；缺失时客户端仅记录警告，仍发请求 | 不能声称官方 SDK 无 token 必然拒发；服务器是否接受仍需看返回值 |
| 24 小时/10 条 | 未在官方协议中找到对应服务端固定额度 | 保留为参考工程规则，不能升级为官方硬限制 |
| 90–160 秒、7 条/5 分钟 | 未在官方协议中找到 | 仅保留为 DeepTutor 经验值 |
| `-14` | bot 登录 token 陈旧/失效，官方客户端暂停该账号一小时 | 客户端冷却策略，不是聊天消息或 context_token 的一小时有效期 |
| 文本分片 | `textChunkLimit: 4000` | SDK 分片设置，不是已证实的微信服务端最大长度 |
| QR 会话 | 客户端缓存 TTL 5 分钟，存在自动刷新次数控制 | 登录客户端策略，不是登录后只能使用五分钟 |
| 验证分支 | 可能收到 need_verifycode/verify_code_blocked，需要输入手机显示数字或重试 | 扫码流程不保证所有情况下只点一次确认 |

官方协议也分别处理 HTTP 状态和 ret/errcode，不存在“HTTP 200 就一定发成功”的保证。当前包增加了 run_id 和工具进度消息类型，但未因此公布各类消息的配额计算方式。

当前工程结论：

- 适配器需要维护来自入站消息的 context_token。
- 发送被拒和上下文失效应明确提示用户刷新会话。
- 不承诺无限主动通知，也不把 iLink 当作企业微信的长连接智能机器人 API。
- 本地 quota counter 只是一种实现策略，不是查到了服务端官方配额。
- 不应把“缺 token”本地直接判定为官方永久禁止，但也不以客户端尝试发送证明可以绕过服务端限制。

已有“单微信账号单绑定”的说法来自先前云厂商接入说明，不应升级为微信所有机器人产品永久只有一个的结论。此次没有找到可直接引用的官方独立机器人数上限。

第二轮的官方客户端代码补强了“同一 userId 在该客户端只保留最新绑定”的依据；这比把整个网关说成只能接一个微信账号更准确。

设计影响：普通微信暂按通知可能受上下文限制设计，不作为长时间无人交互任务必达通知的保证通道；用户无需因此改变本地编程方式。

## 6. QQ

官方指南的数量说明：

| 账号类型 | 机器人数量 |
| --- | --- |
| 个人认证开发者 | 5 个 |
| 企业认证开发者 | 10 个 |
| 同企业主体下所有认证账号 | 共享最多 50 个 |
| 未认证扫码账号 | 未在这份说明中确认固定数量 |

官方《消息收发概述》的被动回复规则：

| 场景 | 有效期 | 单条入站的回复次数 |
| --- | --- | --- |
| QQ 单聊 | 60 分钟 | 4 次 |
| QQ 群聊 | 5 分钟 | 5 次 |

主动消息单聊规则：

| 账号 | Bot 维度 | 同一用户关系维度 | 日上限 |
| --- | --- | --- | --- |
| 企业/个人认证 | 10 QPS | 20 QPM | 1000 条/用户 |
| 未认证 | 5 QPS 且 30 QPM | 20 QPM | 1000 条/用户 |

群主动消息：认证 Bot 60 QPM、未认证 Bot 30 QPM；同群 20 QPM，日上限 1000 条/群。

主动消息不要求携带被动回复的 msg_id，但用户能关闭“允许主动发送”，关闭后失败。因此超过回复窗口可尝试主动接口，不等于保证能发。

文档另有 is_wakeup 互动召回：用户交互后 30 天内划分当天、1–3 天、3–7 天、7–30 天四个周期，每周期一条。这不是普通主动消息的“每天只能发一条”，两种能力不能混算。

机器人需保持 WebSocket 在线。QQ 频道私信的“每用户每天 2 条”等规则不属于本次 QQ 单聊，不引入本设计。

数量规则来自页面标注 2026-10-21 更新的官方指南；消息规则来自页面标注 2026-07-21 更新的官方概述。平台可继续调整，实际接入仍要响应服务端限额错误。

### 本次新增：独立流式接口与连接额度

官方单聊流式接口 `POST /v2/users/{user_openid}/stream_messages` 标注 **50 QPS**，通过 stream_msg_id 与递增 index 关联分片，支持 append/replace。已下发的内容前缀不能修改，否则可返回 40007。

不能把该接口的 50 QPS 当成“50 条独立主动消息/秒”，也不能未经确认，把每次流式刷新都扣成普通被动回复的 4 次之一。流式与普通发送的配额联动仍应以平台确认和真实回执为准。

Gateway 获取接口返回 session_start_limit，包括 total、remaining、reset_after、max_concurrency。必须读取实际返回值；文档示例中的 1000、86400000 等不是对全部账号的统一保证。重连优先按协议 Resume，不反复 Identify 消耗建连额度。

还有两类数量不可混淆：

- 官方指南列个人认证机器人进群上限 500 个，这不是可创建 500 个机器人。
- 内部体验号码最多 20 个，这不是日消息数。

未认证扫码账号的创建剩余额度由手机页服务端数据控制，不能直接套用“个人认证 5 个”。这一点仍未通过真实未认证账号查明。

## 7. 插件应怎样体现限制

这是传输层策略，不改变 CLI 输入和任务调度：

1. 机器人账号、聊天目标分别限流，避免只设一个全局发送队列让某个群拖慢其它会话。
2. 工具进度合并、降频；最终正文保留并按渠道大小分段，不逐 token 发新消息。
3. 为临时回复记录到期时间；过期时只在渠道具备权限时改用主动接口。
4. 采用平台明确的幂等键；ACK 未知不能盲目换接口重复发。
5. 展示限流、额度用尽、上下文失效、主动通知被禁、机器人被踢线等不同原因。
6. 连接/发送失败不终止原终端，不启动第二个 AI 进程。
7. 内置数值带“来源、适用模式、核实日期”；未知额度不伪装成 0 或无限。
8. 注册机器人额度只影响创建/绑定，不与 CLI 并行会话数混淆。机器人可按已有设计重新绑定其它会话。

## 8. 官方与本地来源

以下 URL 由本轮直接 GET 读取正文或源码；部分浏览检索工具无法展开页面，因此使用了官网 HTML/Markdown 原文，未以第三方转载替代。没有通过 API 查询用户账户权益。

```text
企业微信数量的一般说明：
https://open.work.weixin.qq.com/help2/pc/21663

企业微信长连接协议：
https://developer.work.weixin.qq.com/document/path/101463

企业微信长连接创建权限：
https://open.work.weixin.qq.com/help2/pc/21669

企业微信 SDK：
https://github.com/WecomTeam/aibot-node-sdk

飞书发送和回复：
https://open.feishu.cn/document/server-docs/im-v1/message/create.md
https://open.feishu.cn/document/server-docs/im-v1/message/reply.md

飞书月度额度公告（注意正文只明确到 2026 年 6 月临时额度）：
https://open.feishu.cn/document/platform-notices/platform-updates-/custom-app-api-call-limit.md

钉钉官方 Stream SDK：
https://github.com/open-dingtalk/dingtalk-stream-sdk-python/blob/main/dingtalk_stream/chatbot.py

QQ 官方创建指南：
https://bot.q.qq.com/wiki/

QQ 官方消息规则：
https://bot.q.qq.com/wiki/develop/api-v2/server-inter/message/overview.html
```

本地：

- `/Users/liuyuhua/github/claude-codex-wechat/docs/wechatbot-usage-guide.md`
- `/Users/liuyuhua/github/claude-codex-wechat/src/channels/weixin-direct/outboundGate.ts`
- `/Users/liuyuhua/github/DeepTutor/deeptutor/partners/channels/weixin.py`
- `/Users/liuyuhua/github/DeepTutor/deeptutor/partners/channels/dingtalk.py`

未完全查明的项目：钉钉扫码模式的数字额度；飞书 PersonalAgent 的创建数量和当前租户月额度；普通微信官方数量/发送频率/上下文固定时限；企业微信扫码 API 模式是否与一般 20/300 名额完全一致；QQ 未认证扫码账号的创建额度。对此保留“未确认”，不承诺无限。

## 9. 第二轮来源与核实方法

### 新增官方来源

```text
钉钉计费模型（浏览器从官网请求的官方文档存储正文）：
https://open.dingtalk.com/document/orgapp/dingtalk-application-development-platform-billing-model
https://icms-document.oss-cn-beijing.aliyuncs.com/zh-CN/dingtalk/orgapp/topics/dingtalk-application-development-platform-billing-model.html

钉钉入站超额、发送、限流与白名单政策：
https://open.dingtalk.com/document/orgapp/receive-message
https://open.dingtalk.com/document/orgapp/chatbots-send-one-on-one-chat-messages-in-batches
https://open.dingtalk.com/document/orgapp/the-robot-sends-a-group-message
https://open.dingtalk.com/document/orgapp/how-to-process-api-throttling-on-the-dingtalk-server
https://open.dingtalk.com/document/orgapp/dingtalk-document-whitelist
https://home.dingtalk.com/

飞书文本编辑、卡片更新、CardKit、群机器人数、连接数与事件重投：
https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/im-v1/message/update.md
https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/im-v1/message/patch.md
https://open.feishu.cn/document/cardkit-v1/streaming-updates-openapi-overview.md
https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/cardkit-v1/card-element/content.md
https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/im-v1/chat-members/create.md
https://open.feishu.cn/document/ukTMukTMukTM/uYDNxYjL2QTM24iN0EjN/event-subscription-configure-/request-url-configuration-case.md
https://open.feishu.cn/document/server-docs/event-subscription-guide/overview.md
https://open.feishu.cn/document/server-docs/im-v1/faq.md
https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/im-v1/file/create.md

腾讯微信官方协议与代码：
https://github.com/Tencent/openclaw-weixin
https://raw.githubusercontent.com/Tencent/openclaw-weixin/main/docs/protocol_zh_CN.md
https://registry.npmjs.org/@tencent-weixin/openclaw-weixin/-/openclaw-weixin-2.4.10.tgz
包内 src/auth/accounts、auth/login-qr、api/session-guard、messaging/send、channel

QQ 独立流式接口与 Gateway：
https://bot.q.qq.com/wiki/develop/api-v2/autogen/api/v2_users_user_openid_stream_messages.post.html
https://bot.q.qq.com/wiki/develop/api-v2/dev-prepare/event-emit/websocket.html
```

### 仍不能从公开资料强行补出的数字

1. 飞书 PersonalAgent 每人/每租户可创建多少个，以及当前账号本月 API 额度。
2. 钉钉扫码所得具体应用是否按传统企业内部应用计费、是否受限时优惠及新 AI 套餐影响、可创建数量。
3. QQ 未认证扫码账号的机器人创建名额。
4. 普通微信服务端的固定 token 时限和消息额度；官方协议未公开，不能用 SDK timeout 或缓存 TTL 替代。
5. 企业微信 API 扫码模式与一般智能机器人 20/300 名额是否完全合并计算。

这些不是被省略的“无限制”，而是需要对应账号权益/服务端响应才能落定的字段。没有登录用户后台，没有创建真实机器人，没有发送测试消息或故意触发限流。本轮只修改研究文档。
