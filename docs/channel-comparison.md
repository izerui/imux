# 飞书、微信、企业微信、钉钉：能力与限制对照

核对日期：2026-11-01。对象是 imux 实际接入的四种渠道，不包括公众号、微信客服、企业微信应用消息和群自定义 Webhook 机器人。QQ 不在实现范围内。

后续实现更新：四渠道的图片、视频、文件发送已在工作区接入统一 MCP，尚未完成真实手机收发验收。下面能力表保留本次研究时的实现基线；最新进度与未验证项见 [媒体 MCP 实施计划](superpowers/plans/2026-11-01-mobile-media-mcp.md)，不要将“代码已实现”理解为平台账号已联调通过。

视频交付的后续决定：**钉钉直接尝试按普通文件发送 MP4，不要求封面或时长，不先试原生视频，也不保证文件附件具有原生视频播放样式。**官方文件清单未保证接收 MP4，失败须以回执为准。飞书封面和上传时长可选；企业微信视频消息未要求封面、时长；微信官方客户端的对应发送路径也不提供这两项。后三家的原生视频代码路径仍须各自真机验证。[F5][F7][W1][X2]

本次对照了渠道设计、协议研究、实施计划、当前工作区实现，以及重新获取的官方文档和官方 SDK/客户端源码。代码基线为 `6aea05fa5d1c089e2acd9cbf7a77c349f4f0b3b7` 加当前未提交改动；“已实现”不表示本次已做真机联调或已发布。

标记含义：**平台** = 官方接口说明；**SDK** = 官方客户端的行为，不能自动视为服务端保证；**imux** = 本项目代码或产品约定；**经验** = 旧设计记录的实测或参考工程经验；**未确认** = 没有足够证据，不等于无限制。文中来源编号对应最后的来源索引。

## 1. 接入与账号

| 维度 | 飞书 | 普通微信 | 企业微信 | 钉钉 |
| --- | --- | --- | --- | --- |
| 实际产品 | PersonalAgent 应用机器人 | 腾讯 iLink / ClawBot | 智能机器人 API 长连接模式 | 应用机器人 + Stream |
| imux 扫码 | 设备授权流程创建应用 | 微信扫码登录 | 扫码取得 Bot ID / Secret | 扫码创建或选择机器人 |
| 官方接入依据 | Java SDK `RegisterApp` [F1] | 腾讯官方客户端、协议说明 [X1][X2] | 收发协议正式公开；项目扫码使用 `ai/qc/*`，没有核实到公开稳定契约 [W1][L1] | Stream 正式公开；扫码参考官方连接器，未核实到独立公开接口契约 [D4][D5] |
| 扫码认主 | 返回 `open_id`，通常无需配对码 | 返回 `ilink_user_id`，通常无需配对码 | 扫码不带本人身份，需发配对码 | 扫码不带本人身份，需发配对码 |
| 身份缺失处理 | imux 可回退配对码 | imux 可回退配对码 | 必须配对 | 必须配对；缺 `senderStaffId` 时当前实现无法配对 |
| 权限前提 | 机器人启用、发布、可用范围及收发权限；扫码默认模板的实际授权受组织策略影响 [F2] | 本人的微信扫码授权；服务端仍可能要求验证码 [X1] | 企业允许该成员创建、配置长连接机器人 [W3] | 应用机器人启用、发布、企业成员身份、发消息权限；扫码可能需要管理员 [D1][D2][L1] |
| 公网回调服务器 | 不需要 | 不需要 | 不需要 | 不需要 |
| 网络方向 | 本机主动连 WebSocket；HTTP 发消息 | 本机 HTTP 长轮询及发送 | 本机主动连 WebSocket，收发均走连接 | 本机主动连 Stream WebSocket；OpenAPI 发消息 |
| 个人/企业创建数量 | PersonalAgent 统一上限未确认 | 服务端独立机器人数未确认；官方客户端对同一微信 userId 只保留最新绑定 | 一般帮助页：普通成员 20 个、企业 300 个；扫码 API 模式是否有额外规则未确认 [W3] | 本次扫码模式统一数量上限未确认 |
| 同一机器人多客户端 | 最多 50 条连接，事件随机投一条，不广播 [F3] | 多客户端竞争/旧凭据失效行为未真机确认 | 只能一条有效连接，新连接踢旧连接 [W1] | 旧研究记录为随机分发；本轮 SDK 未证明连接数量上限 [L1] |
| imux 多会话 | 多机器人分别绑定；每个机器人同时绑定一个会话 | 按每个微信号一个入口使用，可换绑；这不是已证实的服务端永久硬上限 | 多机器人分别绑定 | 多机器人分别绑定 |
| 特殊登录缺口 | 默认模板不带额外 `addons` 权限申请 | 官方支持验证码分支；imux 目前遇到验证码直接提示不支持 | 非公开扫码接口可能变化 | 扫码 `source=DING_DWS_CLAW` 来自官方连接器，不能据此推导 imux 享有其全部政策权益 |

四个渠道的 imux 凭据均保存在 PasswordSafe。配对码是本项目的本人识别机制，不是平台的消息额度或会话有效期。

## 2. 收消息：手机发给 imux

下表“已实现”均指本人单聊。三家企业工具的平台群聊能力不能等同于 imux 已接入群聊。

| 消息/行为 | 飞书 | 普通微信 | 企业微信 | 钉钉 |
| --- | --- | --- | --- | --- |
| 普通文字 | 已实现 | 已实现 | 已实现 | 已实现 |
| 图片 | 已实现 | 已实现 | 已实现 | 已实现 |
| 文件 | 已实现 | 已实现 | 已实现 | 已实现 |
| 视频 | 已实现 | 已实现 | 已实现 | 已实现 |
| 图文混排 | 提取富文本中的文字与图片 | 支持同条消息多个 item；是否能由手机组合发送取决于客户端 | 提取 `mixed` 中的文字与图片 | 提取 `richText` 中的文字与图片 |
| 语音自带转写 | 不带，需额外识别 | 有 `voice_item.text` 时直接使用 | `voice.content` 即转写文字 | 有 `recognition` 时直接使用 |
| 无转写时 | 下载 Ogg Opus，解码后调用飞书 ASR；失败转音频附件 | 下载为音频附件，不转码、不另调 ASR | 当前无原音频兜底路径；只读取转写文字 | 下载为音频附件，不另调 ASR |
| 语音识别额外限制 | **官方 ASR 免费版不支持调用**；需 `speech_to_text:speech`；适合 60 秒以内；租户共享 20 QPS [F4] | 没有查到保证每条都有转写的契约 [X1] | 当前协议给转写，不提供与其它渠道相同的原音频下载字段 [W2] | 文档给出转写字段，但未保证永远非空 [D1] |
| imux 语音保护值 | 解码最长 10 分钟，下载保护 20 MiB；整段识别，不切 50 秒片段 | 通用附件上限 | 按文字处理 | 通用附件上限 |
| 引用回复输入 | 用 `parent_id` 再请求原消息，取文字 | 取引用内嵌文字或标题；仅有 `svr_id` 时不回查 | 取引用的文字、语音转写、混排文字 | 取 `repliedMsg` 的文字；字段依据为官方连接器 |
| 引用里的图片/文件 | 不递归下载 | 不递归下载 | 不递归下载 | 不递归下载 |
| Markdown 原样保留 | 当前解析旧 `content`，不保证原格式；官方已提供富文本 `content_v2` [F5] | 文字按原样接收 | 文本内容保留；混排归一化 | 文本内容保留；富文本归一化 |
| 卡片/表情/分享等 | 当前不支持的消息类型忽略 | 仅处理 item 1–5，其它忽略 | 非已支持类型、`compat_info` 消息忽略 | `unknownMsgType` 等忽略 |
| 收消息确认 | 官方要求 3 秒内处理完成；SDK 先处理再 ACK。imux 引用查询、转写等也在 ACK 前，慢处理可能触发重投 [F3][F10][L3] | 长轮询返回游标；imux 只在内存保存 | `msgid` 去重；回复沿用回调 ID，主动发送独立 ID | imux 收到即确认后排队处理；确认不代表 CLI 执行成功 |
| 群聊：imux | 不接收 | 不接收 | 不接收 | 不接收 |
| 群聊：平台 | 有群消息能力，取决于订阅权限 [F2][F3] | 官方客户端声明 `chatTypes: ["direct"]`；有 `group_id` 字段不能证明支持群聊 [X2] | 文本、图文混排、引用可触发；独立图片、语音、文件、视频普通用户回调限单聊 [W1][W2] | 可群内 @；不能通过群内 @ 接收语音、文件、视频 [D1] |
| 附件交给 CLI 的方式 | 保存项目附件、提交相对路径 | 同左 | 同左 | 同左 |
| 附件解析是否等于 AI 能理解 | 否；还取决于绑定 CLI 的模型和工具 | 同左 | 同左 | 同左 |

附件落盘位置为 `<项目>/.imux/attachments/<日期>/`。通用代码上限是 `100 * 1024 * 1024` 字节，即 **100 MiB**；界面和旧文档写作“100 MB”。这只是本地保护值，不能覆盖平台更小的限制。[L2]

通常先校验本人、去重和绑定再下载附件；飞书引用查询与语音识别发生在渠道入站处理阶段，是需要单独认识的额外 API 开销。[L3]

## 3. 发消息：imux 发给手机

| 消息/功能 | 飞书 | 普通微信 | 企业微信 | 钉钉 |
| --- | --- | --- | --- | --- |
| 当前发送接口 | `im/v1/messages`，`receive_id_type=open_id` | `ilink/bot/sendmessage` | `aibot_send_msg`，`chat_type=1` | `robot/oToMessages/batchSend`，收件人 `senderStaffId` |
| 当前发送格式 | 富文本 `post` 的 `md` 标签 | 文本 item，Markdown 原样提交 | `markdown` | `sampleMarkdown` |
| Markdown 平台能力 | CommonMark + GFM 常用语法，有明确例外；支持围栏代码块、表格 [F5] | SDK 保留代码块、表格、粗体等，同时过滤部分语法；不是完整服务端渲染保证 [X2] | 官方示例包括代码块、行内代码、表格 [W1] | 官方列出的子集有标题、引用、粗斜体、链接、图片和列表；代码块/表格不在支持清单 [D1] |
| Markdown 实现差异 | 已使用官方推荐 `post.md` | imux 未移植官方 Markdown 过滤器 | 正文原样提交 | 代码块/表格原样提交，不能承诺正确渲染 |
| 长回复 | 分段；序列化请求体超限时再细分 | 分段，每段是一次发送 | 分段 | 分段；`msgParam` 超本地保护值则拒绝该段 |
| 图片/文件/视频出站：imux | 未实现 | 未实现 | 未实现 | 未实现 |
| 图片/文件/视频出站：平台或 SDK | 支持，先上传资源 [F2][F7] | 官方客户端有发送路径 [X1][X2] | 支持素材上传和发送 [W1][W4] | 支持对应消息模板及媒体上传 [D1][D7] |
| 语音出站 | 平台支持，imux 未实现 | 类型定义有语音，不据此声称官方文件发送流程已完整实现语音；imux 未实现 [X1] | 平台支持，imux 未实现 | 平台支持，imux 未实现 |
| 流式打字：imux | 未实现 | 未实现 | 未实现 | 未实现 |
| 流式/更新：平台或 SDK | CardKit；普通消息编辑是另一种能力 [F8][F9] | SDK 的分块输出不等于可任意编辑同一条已发送消息 [X2] | 同一个 `stream.id` 更新，首次发送起 10 分钟结束 [W1] | 官方连接器有 AI 卡片更新路径，需模板和权限 [D5] |
| 卡片/按钮：imux | 未实现 | 未实现 | 未实现 | 未实现 |
| 卡片/按钮：平台 | 支持交互卡片 [F2][F9] | 本轮未核实到同等通用卡片能力 | 支持模板卡片和点击事件 [W1] | 支持 ActionCard、互动卡片 [D1][D5] |
| 已发消息编辑 | 平台文本/富文本最多 20 次，时间由管理员设定；imux 未实现 [F8] | 未确认通用编辑接口 | 有流式与模板卡片更新，不能概括为任意消息编辑 | 有卡片更新，不能概括为普通 Markdown 任意编辑 |
| 卡片/流式更新限制 | 已发卡片 PATCH 限 14 天、单消息 5 QPS；CardKit 单实体 10 次/秒，不能与普通发消息混算 [F9] | 未确认同一消息更新额度 | `finish=true` 后不能续更；欢迎语和卡片点击响应须 5 秒内 [W1] | 不同卡片接口各有规则，未核实适用于当前扫码账号的统一更新额度 |
| 手机完成 CLI 审批/按键 | 当前未实现；等待时提示回 IDE | 同左 | 同左 | 同左 |
| 生成文件直接回传手机 | 当前不能；文字里出现本地路径不等于已上传 | 同左 | 同左 | 同左 |
| 成功回执 | 检查平台业务码；不等于已读 | 检查 HTTP 与 `ret/errcode`；不等于已读 | 等对应 `req_id` ACK；不等于已读 | 检查无效/限流用户列表；HTTP 200 不等于投递成功，更不等于已读 |

桥接当前只转发连接后的新最终回复及必要提示；任务排队、执行中插入由 CLI 决定。绑定与换绑不重置平台额度，断线不停止已经交给 CLI 的任务，重连不补历史。这是 imux 的产品约定，不是四个平台都不具备历史接口。[L1][L2]

## 4. 发送窗口、频率与额度

| 限制 | 飞书 | 普通微信 | 企业微信 | 钉钉 |
| --- | --- | --- | --- | --- |
| 主动发送 | 无需引用入站消息；须满足权限、可用范围，用户也可拒收机器人 [F2] | 尽量携带最新 `context_token`；缺少时官方客户端仍尝试，服务端是否接受不保证 [X1][X2] | 用户先在该会话发过消息，之后可主动推送 [W1] | 有独立主动 OpenAPI，仍需有效用户/机器人会话和权限 [D2] |
| 普通回复时间窗口 | 普通发送接口未公布统一的 24 小时窗口 | `context_token` 固定有效期未公开 | 回调后 24 小时可回复；不能把它自动套到独立主动命令 [W1] | `sessionWebhookExpiredTime` 指定临时地址过期时刻，不能推导固定 90 分钟/2 小时 [D1][D4] |
| imux 是否依赖临时回复凭据 | 不依赖入站消息 ID | 使用最新 context token，但没有也尝试发 | 全部走主动命令，不依赖旧回调 `req_id` | 全部走主动 `batchSend`，不用 sessionWebhook |
| 官方单目标发送限频 | 同用户 5 QPS；同群机器人共享 5 QPS [F2] | 固定数值未确认 | 回复+主动合计每会话 30 条/分钟、1000 条/小时 [W1] | 当前单聊接口未公布统一每用户每分钟/每天数字 [D2] |
| 官方接口级限频 | 发送接口 50 次/秒、1000 次/分钟，另受单目标限频 [F2] | 未确认 | 见会话限频；素材上传另有机器人维度限频 | 传统标准版通用 API 20 QPS；实际套餐/具体接口需另核 [D3] |
| imux 本地限频 | 每聊天 5 次/秒 | 每聊天 3 次/秒 | 每聊天 30 次/分钟；没有另设小时计数器 | 每聊天 5 次/秒，是项目保护值 |
| 每次用户输入最多回复几条 | 未查到与微信同类的固定 10 条规则 | **旧设计记载用户实测为 10 条；界面也这样提示。官方协议未公布。代码不计数、不预拦截** [L1][L4][X1] | 未查到同类 10 条规则，受会话频率限制 | 未查到同类 10 条规则，受 API/消息通道额度约束 |
| 月度额度 | 租户内自建应用共享；本轮官网仍只明确 2026 年 6 月限时 100 万，不能据此确认 11 月值 [F6] | 未查到公开固定月额度 | 本轮长连接协议未给月总额度；不等于无限 [W1] | 仍可访问的传统标准版计费页：API 10000/月、Webhook&Stream 5000/月；不能当作每个扫码机器人独享额度 [D3] |
| 收消息是否占上述 API 月额度 | 事件订阅在不计量清单；资源下载、引用查询另算 [F6] | 未确认计量方式 | 未公布相同月计量规则 | 官方明确机器人消耗 API 和 Stream 资源；入站通道用量超额会影响收消息 [D1][D6] |
| 收消息超额表现 | 普通事件接收不因发送 API 额度耗尽自动停用；附件/引用可能受影响 | 服务端结果为准 | 未核实独立入站月额度错误 | `errorCode=20001`，可能没有 `text/content` 正文 [D1] |
| 发送超额表现 | `99991403`，不能靠短暂重连恢复 [F6] | 拒收原因不能一律归为某个固定窗口；imux 提示再发一条消息后重试 | 平台返回错误；本地限频值不保证覆盖服务端所有限制 | `ApiCountLimitForOrg` 或限频用户列表等 [D2][L3] |
| “不限量”政策 | 未确认本月账号权益 | 未确认 | 未确认 | OpenClaw 专项公告仅写到 2026-03-31，申请豁免到 2026-04-30；**现已过期，不能套用** [D6] |

钉钉旧研究还记载：2026-09-21 公告、2026-10-21 生效，AI 基础版以上 API 不限量、QPS 60。本轮官方搜索找到相关权益调整条目，但正文页面没有成功展开，也未重新取得这份公告原文；因此保留为**此前研究记录、本轮未独立复核**，不把它与当前已读取的计费页混成确定的统一政策。账号实际值应查看开发者后台“资源管理”。[L1]

飞书当前额度应查看管理后台“费用中心 → 权益数据”。本次没有登录账号后台、购买套餐、发送测试消息或故意触发限流。

## 5. 长度与媒体大小：分清发送和接收

| 维度 | 飞书 | 普通微信 | 企业微信 | 钉钉 |
| --- | --- | --- | --- | --- |
| 官方文字长度 | 纯文本请求体 150 KB；富文本/卡片请求体 30 KB [F2] | 未查到服务端统一硬上限；SDK `textChunkLimit=4000` 是分段策略 [X2] | Markdown 正文 20480 UTF-8 字节 [W1] | 单聊 `msgParam` 确切上限未确认；旧研究的 15000 字节是群发送接口范围 |
| imux 正文分段值 | 15000 UTF-8 字节；再检查完整 JSON 请求体 | 4000 UTF-8 字节，**不是 4000 个汉字** | 20000 UTF-8 字节 | 6000 UTF-8 字节；序列化 `msgParam` 不超过本地 15000 字节 |
| 粗略纯汉字容量 | 约 5000 字/初始段 | 约 1333 字/段 | 约 6666 字/段 | 约 2000 字/段 |
| 平台入站文件/视频 | 文件小于 100 MB 可整包；100 MB 及以上须 Range，每段≤32 MB。imux 没做 Range [F7] | 平台统一上限未确认；imux 通用保护 100 MiB | 文件/视频回调限 100M 以内 [W2] | 下载消息附件的统一平台上限未确认；imux 通用保护 100 MiB |
| 平台入站图片 | 小于 100 MB 整包，不支持 Range [F7] | 统一上限未确认 | 本轮未确认独立图片回调大小上限 | 本轮未确认独立图片回调大小上限 |
| 平台出站图片上传 | 10 MB [F7] | 统一上限未确认 | 10 MB；png/jpg/jpeg/gif [W1] | 媒体上传接口 20 MB；jpg/gif/png/bmp [D7] |
| 平台出站普通文件上传 | 30 MB；非空 [F7] | 统一上限未确认 | 20 MB；最少 5 字节 [W1] | 媒体上传接口 20 MB；机器人消息模板支持的后缀还要再取交集 [D1][D7] |
| 平台出站视频上传 | 文件接口 30 MB，MP4 [F7] | 统一上限未确认 | 10 MB，MP4 [W1] | 媒体上传接口 20 MB，MP4 [D7] |
| 平台出站语音 | 文件接口 30 MB，OPUS [F7] | 有协议字段，不推定现有发送能力与上限 | 2 MB，AMR [W1] | 上传页 2 MB，amr/mp3/wav；机器人模板页 ogg/amr，**共同明确的格式为 AMR** [D1][D7] |
| 入站下载链接 | 用资源 key 和访问令牌取文件 | CDN 下载参数或完整 URL；固定有效期未确认 | URL 5 分钟有效 [W1] | downloadCode 换临时 URL；固定有效期未确认 |
| 媒体加密 | API 下载后直接使用 | AES-128-ECB 解密 | AES-256-CBC 解密，32 字节填充规则 | 临时下载 URL 取文件，无项目侧 AES 解密 |
| 临时上传素材 | 按对应资源接口规则，不套企业微信 3 天 | 未确认统一有效期 | 素材 3 天；上传会话 30 分钟；512 KB/片、最多 100 片，仍必须服从各类型大小上限 [W1] | 本轮未复核统一有效期 |

“粗略纯汉字容量”是按常见汉字 UTF-8 占 3 字节计算的估算，不是新的平台限额；代码围栏、前缀、转义、emoji 均会影响实际切段。图片上传小于接收上限并不矛盾，它们是不同接口、不同方向。四个渠道目前都未实现出站媒体，上述上传值用于后续功能评估。

## 6. SDK 核对结果与旧文档修正

四个渠道都没有直接引入通讯平台 SDK，使用 JDK HTTP/WebSocket 自实现；飞书语音的 Concentus 是音频解码库，不是飞书 SDK。[L3]

| 渠道 | 本轮直接读取的官方实现 | 核对出的事实或差异 |
| --- | --- | --- |
| 飞书 | Java `RegisterApp.java`、`Speech.java`；Python `ws/client.py` [F1][F4][F10] | 扫码返回 open_id；支持附加授权参数；SDK 事件处理后 ACK；ASR 免费版不可调用。imux 自行实现分片、单消费者入站与重连 |
| 企业微信 | Node SDK `README.md`、`src/client.ts` [W4] | 主动 Markdown、媒体、流式、模板卡片方法确实存在；**SDK 支持参数不代表服务端全支持**，见下方冲突 |
| 钉钉 | 官方 Node Stream `src/client.ts`；Python `chatbot.py`；官方连接器 `device-auth.ts`、`services/messaging.ts` [D4][D5] | 临时 Webhook 与主动 batchSend 是两条路径；配对身份不能由扫码结果推导；Node 回调确认格式与项目一致。此次 Node 仓库 package 标记 `v2.1.7-beta.1`，不能冒称已复核旧设计的 npm 2.1.6 发布包 |
| 普通微信 | 腾讯协议原文及 npm `@tencent-weixin/openclaw-weixin@2.4.10` 的 `channel.ts`、`send.ts`、`session-guard.ts`、`accounts.ts`、`markdown-filter.ts` [X1][X2] | 4000 是 SDK 分段值；缺 context token 仍发送；-14 触发客户端一小时暂停；同一 userId 清旧绑定是客户端策略；代码不公布固定 10 条服务端额度 |

可重复定位的上游版本：飞书 Java `v2_main` SHA `376ab30197588b8ade03c21a61347b927af5388c`；企业微信 Node `main` SHA `80615b987ef69c6028ad764924609247c0725955`；微信 npm 2.4.10 shasum `e50fdb193d5eda3b7d0090bd4b8ea0a53491bbe4`。其它仓库为本次读取的当前分支，不声称是已安装依赖或最新正式版。

需要修正或补足的旧说法：

| 旧说法/遗漏 | 本轮结论 |
| --- | --- |
| 飞书语音只需开权限就能转写 | 还受版本限制；免费版不支持 ASR。代码有音频降级路径，不能宣传免费语音必转文字 |
| 飞书 `md` 接收后只会变成其它标签 | 对旧 `content` 成立；官方已有保留 Markdown 的 `content_v2`。当前实现没有优先读取它 |
| 微信 `context_token` “必须带，没带不能发” | 官方客户端缺失也尝试。不能反向保证不带也一定成功 |
| 微信固定“24 小时/10 条”是官方限制 | 官方协议没有公布；后续设计的“每次输入后 10 条”属于项目实测与界面提示，不能混成同一条官方规则 |
| 微信 `-14` 证明聊天上下文一小时有效 | 一小时是客户端暂停轮询策略；不是 context token 的官方有效期。去 token 重发来自参考实现，不是腾讯官方恢复保证 |
| 企业微信 SDK 有 `msg_item`、流式+卡片，长连接就能用 | 官方长连接页明确说暂不支持 `msg_item`，也不支持流式+模板卡片组合；SDK 与协议冲突，应按具体接口文档保守判断、真机确认 |
| 企业微信流式统一只有 6 分钟 | 6 分钟出现在短连接回调刷新说明；长连接页明确 10 分钟，不能跨模式套用 |
| 钉钉“临时回复地址过期，所有发送都不能用” | imux 使用主动 batchSend，不依赖临时 Webhook；主动发送仍受独立权限和有效会话条件约束 |
| 钉钉单聊每次最多 100 人 | 参数表和接口介绍都写 20，错误码解释残留 100；按 20。imux 每次只发一人 |
| 钉钉 batchSend “只支持内部应用”是完全无矛盾的结论 | 参数说明写仅内部机器人，但权限表列第三方企业应用支持。当前 imux 内部应用路径不受此歧义影响；不可泛化第三方支持范围 |
| 钉钉免费标准版约 5000 条入站只是推测 | 官方公告明确机器人消耗 API 和 Stream；超额缺正文也已确认。但 5000 是通用套餐表，具体账号权益、一次消息的实际计量仍要看后台 |
| 钉钉/OpenClaw 不限量可一直用 | 找到的专项免费公告已于 2026 年 3/4 月结束，不适用于 11 月 |
| 飞书 100 万/月就是当前额度 | 当前公告仍限定 2026 年 6 月；11 月额度未确认 |
| 企业微信“上传分片最多约 50 MB”意味着文件可发 50 MB | 分片上限之外还有普通文件 20 MB 等类型上限 |
| 设计里有发送箱、历史补发、逐 token 输出 | 早期可行性稿保留了未落地设想；现行 AGENTS 与代码是简单转接、不补历史、只推新最终回复 |
| 实施计划代码就是现在的实现 | 飞书旧计划仍有 50 秒切片/不同 ACK 描述，微信旧计划仍有 -14 停止分支；现行代码与后续设计修订优先 |

## 7. 使用判断与未确认项

基于上述证据的判断，而非稳定性跑分：

- **需要代码块、表格和较完整的文本展示**：飞书、企业微信的官方 Markdown 依据最明确。飞书需要额外关心租户 API 权益。
- **常用语音控制**：企业微信直接提供转写；钉钉、微信有转写字段和音频兜底。飞书免费版不能以本项目当前 ASR 路径保证转写。
- **长时间任务结束通知**：飞书、企业微信、钉钉都有独立主动发送路径；仍受账号权限、频控和在线状态约束。普通微信的上下文窗口未公开，不宜承诺久未互动后的通知必达。
- **只想用日常微信入口**：现有能力能覆盖文本和入站附件；要接受较小分段、项目记录的 10 条经验限制以及验证码分支尚未实现。
- **任务生成报告、图片、压缩包后发回手机**：四个平台至少有对应图片/文件发送依据，但 imux 当前都没接出站媒体，这是项目未实现，不能说平台不支持。

仍需账号后台或真机才能落定：飞书 2026 年 11 月实际额度与扫码模板权限；钉钉新版本权益和当前组织计量；微信 context token 时长、条数、同账号多端行为与验证码；各手机端真实 Markdown 渲染；企业微信非公开扫码的长期适用性。

## 8. 来源索引

本轮重新获取官方网页正文、GitHub 官方仓库源文件及腾讯发布包；钉钉动态目录用浏览器定位，再读取官网使用的 OSS 正文。没有用搜索摘要替代正文认定额度。以下 URL 便于复查，线上内容仍可能调整。

### 企业微信

```text
[W1] 智能机器人长连接：连接、主动发送、回复窗口、限频、Markdown、媒体上传
https://developer.work.weixin.qq.com/document/path/101463
[W2] 接收消息：消息类型、语音转写、文件/视频回调大小、引用
https://developer.work.weixin.qq.com/document/path/100719
[W3] 如何使用智能机器人：一般创建数量与权限（栏目为普通模式）
https://open.work.weixin.qq.com/help2/pc/21663
[W4] 官方 Node SDK：README.md、src/client.ts
https://github.com/WecomTeam/aibot-node-sdk
```

### 飞书

```text
[F1] 官方 Java SDK：扫码实现
https://github.com/larksuite/oapi-sdk-java/blob/v2_main/larksuite-oapi/src/main/java/com/lark/oapi/scene/registration/RegisterApp.java
[F2] 发送消息：类型、条件、频率、大小、拒收错误
https://open.feishu.cn/document/server-docs/im-v1/message/create.md
[F3] 长连接：3 秒、50 连接、随机分发
https://open.feishu.cn/document/ukTMukTMukTM/uYDNxYjL2QTM24iN0EjN/event-subscription-configure-/request-url-configuration-case.md
[F4] ASR：免费版不支持、60 秒、20 QPS、PCM
https://open.feishu.cn/document/server-docs/ai/speech_to_text-v1/file_recognize.md
https://github.com/larksuite/oapi-sdk-java/blob/v2_main/larksuite-oapi/src/main/java/com/lark/oapi/service/speech_to_text/v1/resource/Speech.java
[F5] Markdown 与接收 content_v2
https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/im-v1/message/create_json.md
https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/im-v1/message/events/message_content.md
[F6] 自建应用 API 调用量上限调整说明
https://open.feishu.cn/document/platform-notices/platform-updates-/custom-app-api-call-limit.md
[F7] 资源下载与文件上传
https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/im-v1/message-resource/get.md
https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/im-v1/file/create.md
[F8] 编辑普通消息
https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/im-v1/message/update.md
[F9] 更新卡片及 CardKit 流式更新
https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/reference/im-v1/message/patch.md
https://open.feishu.cn/document/cardkit-v1/streaming-updates-openapi-overview.md
[F10] 官方 Python SDK：处理事件后确认
https://github.com/larksuite/oapi-sdk-python/blob/v2_main/lark_oapi/ws/client.py
```

### 钉钉

```text
[D1] 消息发送与接收类型；接收额度错误
https://open.dingtalk.com/document/development/robot-message-type
https://icms-document.oss-cn-beijing.aliyuncs.com/zh-CN/dingtalk/development/topics/robot-message-type.html
[D2] 批量单聊发送：用户数、权限、无效与限流收件人列表
https://open.dingtalk.com/document/orgapp/chatbots-send-one-on-one-chat-messages-in-batches
https://icms-document.oss-cn-beijing.aliyuncs.com/zh-CN/dingtalk/orgapp/topics/chatbots-send-one-on-one-chat-messages-in-batches.html
[D3] 传统计费模型（本轮仍可访问，页面标注 2025-06-12）
https://open.dingtalk.com/document/orgapp/dingtalk-application-development-platform-billing-model
https://icms-document.oss-cn-beijing.aliyuncs.com/zh-CN/dingtalk/orgapp/topics/dingtalk-application-development-platform-billing-model.html
[D4] 官方 Stream SDK
https://github.com/open-dingtalk/dingtalk-stream-sdk-nodejs/blob/main/src/client.ts
https://github.com/open-dingtalk/dingtalk-stream-sdk-python/blob/main/dingtalk_stream/chatbot.py
[D5] 官方连接器：扫码、普通消息、主动发送、AI 卡片
https://github.com/DingTalk-Real-AI/dingtalk-openclaw-connector/blob/main/src/device-auth.ts
https://github.com/DingTalk-Real-AI/dingtalk-openclaw-connector/blob/main/src/services/messaging.ts
[D6] OpenClaw 专项额度公告：2026 年 3/4 月截止
https://open.dingtalk.com/document/development/open-ai-paas-report
https://icms-document.oss-cn-beijing.aliyuncs.com/zh-CN/dingtalk/development/topics/open-ai-paas-report.html
[D7] 上传媒体：大小与格式
https://open.dingtalk.com/document/orgapp/upload-media-files
https://icms-document.oss-cn-beijing.aliyuncs.com/zh-CN/dingtalk/orgapp/topics/upload-media-files.html
```

### 普通微信

```text
[X1] 腾讯官方协议（明确声明：客户端行为不是完整服务端契约）
https://github.com/Tencent/openclaw-weixin/blob/main/docs/protocol_zh_CN.md
[X2] 官方发布包 2.4.10
https://registry.npmjs.org/@tencent-weixin/openclaw-weixin/-/openclaw-weixin-2.4.10.tgz
读取文件：
src/channel.ts
src/messaging/send.ts
src/messaging/markdown-filter.ts
src/api/session-guard.ts
src/auth/accounts.ts
```

### 本仓库

- [L1] 研究与设计：[协议参考](channel-protocols.md)、[限制研究](channel-limits.md)、[终端通讯设计](terminal-channel-bridge-feasibility.md)；`superpowers/specs/2026-10-27-wecom-inbound-media-design.md`、`2026-10-28-feishu-channel-design.md`、`2026-10-29-dingtalk-channel-design.md`、`2026-10-30-weixin-channel-design.md`，以及对应实施计划、桥接核心与机器人管理计划。旧计划中的内嵌代码是历史草案，不作为当前行为的最终依据。
- [L2] [AGENTS.md](../AGENTS.md)、[BridgePorts.kt](../src/main/kotlin/com/github/izerui/imux/bridge/core/BridgePorts.kt)、[BridgeRouter.kt](../src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeRouter.kt)。
- [L3] [渠道实现目录](../src/main/kotlin/com/github/izerui/imux/bridge/channel)：各渠道的 Onboarding、Connection、Api/Protocol、Events、Attachment/Media；飞书另有 Speech；[build.gradle.kts](../build.gradle.kts)。
- [L4] [中文界面文案](../src/main/resources/messages/ImuxBundle_zh_CN.properties)：`bridge.add.scan.steps.weixin` 的 10 条提示。

本次仅新增本对照文档，没有修改业务代码或覆盖工作区现有改动；没有运行构建、测试或真机收发。官方确认与源码确认不等于真实账号验收通过。
