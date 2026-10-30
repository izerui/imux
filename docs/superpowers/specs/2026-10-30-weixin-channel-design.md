# 普通微信渠道接入设计

日期：2026-10-30
状态：用户要求“设计好了开始实现”（2026-10-30），按本设计直接进入实施计划。

## 1. 目标与范围

在手机桥接中新增普通微信渠道（腾讯 iLink，即官方 OpenClaw 微信插件所用协议），与企业微信、飞书、钉钉并列。用户在 IDE 里用微信扫码登录，扫码人即本人（不需要配对码），之后与其他渠道一样：微信消息转交给绑定的终端会话，CLI 新的最终回复推回微信。

**做**

- 扫码登录（含二维码过期刷新、换域名），扫码人直接认主。
- 长轮询收单聊消息：文本、图片、文件、视频、语音（用微信给的转写文字；没有转写时作为音频附件）、引用。
- 发消息：文本（Markdown 原样发送），分段、限频；失败如实显示。
- 连接状态、重试、bot_token 失效的如实显示。

**不做**

- 不引入任何 SDK，HTTP 全部 JDK 自实现（与企业微信、飞书、钉钉一致）。
- 群聊、出站媒体、“正在输入”（typing / getconfig）、notifystart / notifystop、扫码时的手机验证码输入（`need_verifycode`）。
- 不补发：发送失败不自动重发（AGENTS.md：不为保证消息不丢增加复杂机制）。
- 不改核心层；不复用其它渠道的任何类（各渠道各自一套）。

**取舍原则**（用户 2026-10-30）：有官方或成功案例的就照着写，选最简单、能跑通的；不强求与某一家逐字节一致。

## 2. 依据与协议事实

来源：官方包 `@tencent-weixin/openclaw-weixin` 2.4.10 源码（下称“官方”，`/tmp/weixin-research/package/src/`）；参考工程 DeepTutor `partners/channels/weixin*.py`（DT）、hermes `gateway/platforms/weixin.py`（HM）、claude-codex-wechat `src/channels/weixin-direct/`（CCW）。没有服务端公开契约，标“待真机”的见第 11 节。

| 事实 | 内容 | 依据 |
|---|---|---|
| 公共请求头 | `Content-Type: application/json`、`AuthorizationType: ilink_bot_token`、`X-WECHAT-UIN`（每次随机 uint32 → 十进制字符串 → base64）、`iLink-App-Id: bot`、`iLink-App-ClientVersion: 132106`（2.4.10 按 major<<16\|minor<<8\|patch 编码）；登录后加 `Authorization: Bearer <bot_token>` | 官方 api.ts:221-254、package.json:73；DT 头齐全 |
| 取码 | `GET https://ilinkai.weixin.qq.com/ilink/bot/get_bot_qrcode?bot_type=3`（不带 body）→ `qrcode`（轮询票据）、`qrcode_img_content`（**链接**，客户端自己编码成二维码） | DT、HM、CCW 均用 GET 且跑通；官方 2.3.1 起改 POST 带 `local_token_list`（可选） |
| 轮询扫码状态 | `GET {host}/ilink/bot/get_qrcode_status?qrcode=<enc>`，服务端最长挂 35 秒；超时或网络错误当 `wait` | 官方 login-qr.ts；HM |
| 扫码状态 | `wait`、`scaned`：继续；`expired`：重新取码（最多刷新 3 次）；`scaned_but_redirect`：之后改轮询 `https://<redirect_host>`；`confirmed`：返回 `bot_token`、`ilink_bot_id`、`baseurl`、`ilink_user_id`（缺 `ilink_bot_id` 判失败）；`need_verifycode`、`verify_code_blocked`：本版不支持 | 官方 login-qr.ts:334-476；DT/HM 只处理 wait/scaned/redirect/expired/confirmed |
| 凭据 | 保存 `bot_token`、`baseurl`（空则用默认 `https://ilinkai.weixin.qq.com`）、`ilink_bot_id`、`ilink_user_id` | 官方 channel.ts:446-451；DT、HM 同 |
| 本人 | `ilink_user_id` 即扫码人，官方直接用它与入站 `from_user_id` 比对 | 官方 process-message.ts:184-195；待真机 |
| 收消息 | `POST {baseUrl}/ilink/bot/getupdates`，body `{"get_updates_buf":"<游标>","base_info":{"channel_version":"2.4.10"}}`（`base_info` 只带 `channel_version`，同 DT、CCW；官方另带 `bot_agent: OpenClaw`，不冒用）→ `ret`、`errcode`、`errmsg`、`msgs[]`、`get_updates_buf`；只在返回游标非空时替换；服务端最长挂起约 35 秒 | 官方 api.ts、monitor.ts:94-156；DT、CCW |
| 空游标 | 首次空游标可能推送服务端积压的历史消息 | 官方 types.ts:215 注释；DT weixin.py:196-199 注释；待真机 |
| 消息 | 顶层 `message_id`（uint64，按字符串处理）、`from_user_id`、`create_time_ms`、`group_id`、`message_type`（1 用户、2 BOT）、`item_list`、`context_token`；item：1 文本 `text_item.text`、2 图片 `image_item{media,aeskey}`、3 语音 `voice_item{media,text}`、4 文件 `file_item{media,file_name}`、5 视频 `video_item{media}`；引用在 item 的 `ref_msg{message_item,title}` | 官方 types.ts:70-209 |
| 媒体下载 | 地址优先 `media.full_url`，否则 `https://novac2c.cdn.weixin.qq.com/c2c/download?encrypted_query_param=<urlencode>`；无认证头 GET；AES-128-ECB + PKCS7（JVM `AES/ECB/PKCS5Padding`）；密钥：图片优先 `image_item.aeskey`（hex），否则 `media.aes_key`（base64，解码后 16 字节即密钥，或 32 个 hex 字符再转 16 字节）；图片无密钥时为明文；非图片缺 `aes_key` 时不下载（同官方） | 官方 cdn/、media-download.ts、pic-decrypt.ts、accounts.ts:14；CCW mediaCrypto.ts |
| 发消息 | `POST {baseUrl}/ilink/bot/sendmessage`，body `{"msg":{"from_user_id":"","to_user_id":<对方>,"client_id":<唯一串>,"message_type":2,"message_state":2,"item_list":[{"type":1,"text_item":{"text":<文本>}}],"context_token":<最新>},"base_info":{...}}`；HTTP 2xx 且 `ret`、`errcode` 均为 0（或缺省）才算成功 | 官方 send.ts:57-80；CCW、DT、HM 同时检查 ret 与 errcode |
| Markdown | 官方 2.1.3 起部分支持（代码块、行内代码、表格、粗体保留） | 官方 markdown-filter.ts、CHANGELOG |
| 分段 | 4000 字符分段，段间约 0.3 秒 | 官方 textChunkLimit 4000；CCW、DT |
| context_token | 每条入站消息带最新值，发送时带回；缺失时官方仍照发（只记警告）；有效期官方未给 | 官方 send.ts:109-111；DT/CCW 本地拒发；经验值互相矛盾（见第 7 节） |
| 错误 | `ret`/`errcode` = -14：会话过期，官方 getupdates 暂停 1 小时后继续轮询（不终止）；sendmessage 非 0 只抛普通错误、不影响收消息（HM：发送 -14 时去掉 context_token 重发一次）；其它非 0 与网络错误：2 秒重试，连续 3 次后退避 30 秒 | 官方 session-guard.ts、monitor.ts:13-16、110-124，api.ts:585-596；HM weixin.py:1020-1042 |
| 其它接口 | notifystart/stop、getconfig/typing 均非必须 | 三个参考工程都不调用 notify；官方失败也忽略 |

## 3. 分层与对接

```
core/                     不改
channel/
  Transports.kt           改：JdkHttpJson 增加可选请求超时参数（默认仍 15 秒）
  ChannelFactories.kt     改：登记 WeixinChannelFactory
  weixin/                 新（不引用 wecom/、feishu/、dingtalk/ 的任何类）
    WeixinChannelFactory.kt   凭据 → 连接；扫码说明文案键
    WeixinOnboarding.kt       扫码登录；WeixinCredentialKeys
    WeixinApi.kt              请求头、取码、扫码状态、getupdates、sendmessage；WeixinResult
    WeixinEvents.kt           消息 JSON → 文字、附件引用、引用文字（纯函数）
    WeixinMedia.kt            媒体下载地址、AES-128-ECB 解密、InboundAttachment
    WeixinConnection.kt       长轮询、去重、按序交出、context_token、发送、状态
ui/                       不改（扫码说明按渠道取文案、SendFailing 显示均已支持）
```

`ChannelKind.WEIXIN` 与 `bridge.channel.weixin*` 文案已预留。`ChannelIsolationSourceTest` 自动覆盖新目录（含“渠道子包互不引用”）。

公共层唯一改动：`JdkHttpJson(client, requestTimeout: Duration = NETWORK_TIMEOUT)`。长轮询需要比服务端挂起时间（35 秒）更长的请求超时；其它渠道仍用默认值，行为不变。

## 4. 扫码登录（WeixinOnboarding）

```
start():
  GET get_bot_qrcode?bot_type=3（公共头）
    无 qrcode 或 qrcode_img_content → Failed("bridge.onboarding.noQr")
  记下 qrcode、host = 默认域名、refreshes = 0
  → ShowQr(qrcode_img_content)

poll():（调用方每 1 秒调用；单次请求服务端最长挂 35 秒，请求超时 60 秒，与 getupdates 共用长轮询客户端）
  GET {host}/ilink/bot/get_qrcode_status?qrcode=<enc>
  网络错误 / 超时 → Waiting
  status:
    wait、scaned → Waiting
    scaned_but_redirect → host = https://<redirect_host>（已带协议头则原样），Waiting
    expired → refreshes < 3 ? 重新取码并 ShowQr(新链接) : Failed("bridge.onboarding.expired")
    confirmed → 有 bot_token 与 ilink_bot_id → Succeeded(
                  credentials = {bot_token, base_url, bot_id, user_id},
                  ownerId = ilink_user_id（空则 null → 走配对码），
                  suggestedName = null)
                缺字段 → Failed("bridge.onboarding.noCredentials")
    need_verifycode、verify_code_blocked → Failed("bridge.weixin.verifyCode")
    其它 → Waiting
```

- 总超时沿用 `OnboardingDriver` 的 5 分钟。
- 扫码说明 `bridge.add.scan.steps.weixin`：“1. 用手机微信扫一扫\n2. 在手机上确认登录\n3. 扫码后自动完成，无需配对码”，末行附推送限制提示（见第 7 节）。
- 同一微信号重复扫码：本版不在本地去重（官方 `clearStaleAccountsForUserId` 是客户端清理），由用户移除旧机器人；界面描述已写明“每个微信号一个入口”。

## 5. 收消息（WeixinConnection 长轮询）

```
runLoop（直到 stop）：
  首轮：游标 = ""，connectedAt = now()，上报 Connecting
  循环：
    POST getupdates（Bearer，请求超时固定 60 秒；请求超时按“本轮无新消息”处理，不计失败，立即下一轮；
                     连接超时 HttpConnectTimeoutException 是连不上，按网络错误计入失败）
    成功（ret、errcode 为 0 或缺省）：
      首次成功 → 上报 Online（失败计数清零）
      返回游标非空 → 替换（只在内存）
      每条消息 → 入站队列
    ret 或 errcode == -14 → 照官方 monitor.ts:110-124 暂停后继续：上报 Retrying("<ret>/<errcode> <errmsg>")（链路非在线），
                         等 1 小时（expiredPauseMillis）后从当前游标继续轮询；之后成功照常上报 Online
    其它错误 / 网络错误 → 失败计数 +1；连续 3 次后等 30 秒，否则等 2 秒；
                         连续失败 5 次起上报 Retrying(原因)（如实显示，可重连）
```

- **不补历史**（AGENTS.md）：游标只在内存，不落盘；每次 `start()` 从空游标开始，**丢弃 `create_time_ms` 早于本次 `connectedAt` 减 60 秒的消息**（空游标可能推送积压历史；留 60 秒余量防本机与服务器时钟偏差把新消息误丢）。没有 `create_time_ms` 的消息照常处理。
- **过滤**：只处理 `message_type == 1` 且 `group_id` 为空的消息。
- **身份**：`senderId = chatId = from_user_id`（满足 `ChannelSpi` 单聊约定）。
- **去重**：按 `message_id`（字符串）去重，容量 1000；无 `message_id` 时用 `seq` 兜底（DT 做法），都没有则不去重。
- **按序**：单个消费协程按到达顺序交给 `InboundSink`。
- **context_token**：每条通过过滤的消息若带 `context_token`，按 `from_user_id` 记下最新值（只在内存；IDE 重启后在下一条入站消息到达前为空，按官方做法照发）。
- **在线判定**：`getupdates` 首次成功即 Online（长轮询本身就是保活，没有心跳接口）。

## 6. 收消息解析（WeixinEvents）

| item type | 文字 | 附件 |
|---|---|---|
| 1 文本 | `text_item.text` | — |
| 2 图片 | — | IMAGE |
| 3 语音 | `voice_item.text` 非空白时作为正文 | 无转写时 → AUDIO（原始 silk 文件，不转码） |
| 4 文件 | — | FILE（文件名 `file_name`） |
| 5 视频 | — | VIDEO |
| 其它 | — | 忽略 |

- 多个 item 的文字按顺序换行拼接；无文字无附件的消息丢弃。
- **引用**：任一 item 的 `ref_msg` 存在时，取 `ref_msg.message_item` 的文字（文本 item 的 `text_item.text`，或语音的转写）；取不到用 `ref_msg.title`；都没有为 null。新版只带 `svr_id` 的情况不查（官方需本地缓存，本版不做）。
- 附件只记引用，路由器确认本人、非重复、已绑定后才下载（与其它渠道一致）。

## 7. 发消息与 context_token

- **格式**：每段一个请求，`item_list` 只放一项文本；`client_id` 用 UUID；`context_token` 取该用户最新值，没有则不带（官方照发）。
- **分段**：`maxTextBytes = 4_000`（路由器按 UTF-8 字节切段，保证每段不超过 4000 字符，偏保守）。
- **限频**：`SlidingWindowLimiter(3, 1000 毫秒)` 按 `chatId`（约每 0.3 秒一条，对应参考工程段间 0.3 秒）；等待超过 20 秒 → `Rejected(RATE_LIMITED)`。
- **结果映射**：

| 情况 | 返回 | 状态 |
|---|---|---|
| HTTP 2xx 且 ret、errcode 均为 0（或缺省） | `Accepted` | 清除发送受阻 |
| ret 或 errcode == -14 且带了 context_token | 丢弃该用户的 context_token，不带 token 重发一次（不再占限频名额；HM weixin.py:1020-1042）；重发成功 `Accepted`，仍失败按下一行 | 不影响链路（官方 api.ts:585-596 发送失败只抛普通错误） |
| 其它非 0 ret/errcode（含没带 token 的 -14、重发仍失败） | `Rejected("<code> <errmsg>")` | `SendFailing("bridge.weixin.sendRejected", "<code>")`；code 为 `<ret>/<errcode>`，两者都缺省且 HTTP 非 2xx 时为 `HTTP <status>` |
| 网络异常、超时 | `Unknown` | 不变 |

- **为什么所有拒绝（含重发后仍 -14）都显示同一提示**：官方没有定义其它错误码；参考工程的经验值互相矛盾（DT：上下文闲置约 90–160 秒失效、约 7 条/5 分钟；CCW 依据第三方 SDK：24 小时 10 条、超出返回 -3；HM：-2 视为限频或会话失效），都不是官方结论。最简单且如实的做法是：发送被拒就显示“微信拒收：可能是会话过期或发送过多，请在微信给机器人发任意一条消息后再试（{0}）”，下一次发送成功自动恢复。**不在本地按经验值预先拦截**，以服务端返回为准。
- **推送条数限制只做提示**（用户 2026-10-30 决定）：用户每发一条消息后机器人最多推 10 条（CCW `weixinStateStore.ts` `PUSH_QUOTA_LIMIT = 10`，用户实测一致），在添加机器人的扫码说明 `bridge.add.scan.steps.weixin` 末行简短提示（“你每给机器人发一条消息，它最多回 10 条（长回复每段算一条）”）；其它渠道的限额在个人日常使用中碰不到或已有超额状态显示，不加提示；代码不计数、不拦截、不排队。
- **长任务结束后的主动推送**：能发但不保证送达（上下文可能已过期）。失败时按上表如实显示；不自动补发。
- **收发受阻显示**：与钉钉、飞书一致——跨重连保留，只在链路在线时显示，stop 或下一次发送成功时清除。

## 8. 附件下载（WeixinMedia）

`WeixinAttachment.fetch(maxBytes)`：

1. 地址：`media.full_url`，否则拼 CDN 下载地址（`encrypted_query_param` 做 URL 编码）。
2. `download(url, maxBytes + 16)`（不带认证头；预留 PKCS7 填充）。
3. 有密钥则 AES-128-ECB/PKCS5Padding 解密覆盖为明文；图片无密钥按明文。密钥解析见第 2 节。
4. 文件名：文件 item 用 `file_name`；其它为 null，交给附件落盘按文件头命名。
5. 解密后超过 `maxBytes` → `TooLarge`；失败 → `Failed(原因)`（原因不含地址）。

## 9. 状态、文案与界面

- 扫码说明：`bridge.add.scan.steps.weixin`（见第 4 节）。
- 新增文案（10 种语言）：
  - `bridge.add.scan.steps.weixin`
  - `bridge.weixin.verifyCode`：“微信要求在手机上输入验证码，本版暂不支持，请稍后重新扫码。”
  - `bridge.weixin.sendRejected`：“微信拒收：可能是会话过期或发送过多，请在微信给机器人发任意一条消息后再试（{0}）”
- 界面无需改动：SendFailing 显示、受阻时不提供“重新连接”均已通用。

## 10. 凭据与日志

- 凭据存 `PasswordSafeVault`：`{bot_token, base_url, bot_id, user_id}`；`OnboardingStep.Succeeded.toString()` 已屏蔽值。
- `RobotRecord.kind = WEIXIN`，`ownerSenderId = ownerChatId = ilink_user_id`。
- 日志与状态原因不含 bot_token、context_token、消息正文、媒体地址与密钥；数据类涉及这些字段的重写 `toString` 屏蔽。

## 11. 测试

| 测试 | 覆盖 |
|---|---|
| `WeixinApiTest` | 公共请求头（App-Id、ClientVersion、AuthorizationType、X-WECHAT-UIN 格式、Bearer）；取码；扫码状态；getupdates 请求体与长轮询超时；sendmessage 请求体（from 空、message_type 2、message_state 2、单项文本、context_token 有无）；ret/errcode 判定 |
| `WeixinOnboardingTest` | 正常确认（ownerId=ilink_user_id）；wait/scaned；redirect 换 host；expired 刷新且超过 3 次失败；need_verifycode 失败；缺字段；网络错误继续等 |
| `WeixinEventsTest` | 各 item 类型；语音有无转写；文件名；引用（文本、标题兜底）；多 item 拼接；BOT 消息与群消息过滤；message_id 字符串化；无内容丢弃 |
| `WeixinMediaTest` | 下载地址（full_url 优先、CDN 拼接与编码）；三种密钥格式；AES-128-ECB 解密；图片无密钥明文；超限；失败原因不含地址 |
| `WeixinConnectionTest` | 首次成功报 Online；游标替换只在非空时；丢弃早于连接时刻的消息；去重；按序；context_token 记录并用于发送；getupdates -14 → Retrying、暂停后从当前游标继续并恢复 Online；发送 -14 去 token 重发一次、仍失败显示受阻且链路不失败；连续失败退避与 Retrying；发送结果映射与 SendFailing 跨重连保留、成功恢复；stop 上报 Stopped |
| 公共层 | `JdkHttpJson` 请求超时参数（默认不变） |
| 现有 | 隔离测试自动覆盖；`ChannelFactoriesTest` 登记；文案键 10 语言一致 |

## 12. 需要真机确认

1. `ilink_user_id` 是否等于本人消息的 `from_user_id`（不等则改为配对码认主）。
2. 首次空游标是否推送历史、`create_time_ms` 过滤是否足够。
3. 用户最后一条消息之后多久、发了多少条，sendmessage 开始被拒，返回什么 ret/errcode；不带 context_token 能否发出。
4. GET 取码是否仍可用；`iLink-App-Id`/`ClientVersion` 是否被校验。
5. Markdown 实际渲染（标题、表格、代码块）；4000 字能否一次发出。
6. `voice_item.text` 是否总有；同一消息是否会换 message_id 重复推送。
7. 同一微信号重复扫码后旧 bot_token 是否失效；两个 IDE 同时轮询同一 token 的表现。
8. -14 之后是否必须重新扫码。
9. sendmessage -14 的真实含义：是该用户 context_token 过期（去掉 token 重发能否送达），还是 bot_token 失效。
10. getupdates -14 暂停 1 小时后能否恢复收消息；不能恢复时界面会一直显示“正在重试”，需要用户移除后重新扫码。
