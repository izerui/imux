# 飞书渠道接入设计

日期：2026-10-28
状态：待用户确认，确认后再写实施计划。

## 1. 目标与范围

在手机桥接中新增飞书渠道。用户在 IDE 里扫码创建飞书应用，扫码完成即认主、自动连接；之后与企业微信一样：手机消息转交给绑定的终端会话，CLI 新的最终回复推回手机。

**做**

- 扫码创建应用并认主（不需要配对码）。
- 长连接收消息：文本、富文本、图片、文件、视频、语音、引用。语音优先用飞书语音识别转成文字（与企业微信的语音转写一致），识别失败时作为音频附件转交。
- 发消息：Markdown（富文本 `post` 的 `md` 标签），分段、限频。
- 连接状态、重连、发送失败（含月度额度用完）的如实显示。

**不做**

- 不引入飞书官方 SDK；长连接、HTTP 全部用 JDK 自己实现。唯一新增依赖是 Concentus（纯 Java Opus 解码，BSD 风格许可证，412 KB，无传递依赖），用于把 Opus 语音解成 pcm（用户 2026-10-28 同意）。
- 群聊、卡片、流式回复、出站媒体、欢迎语、反馈事件（与企业微信相同的产品决定）。
- 通用语音识别服务（可配置 OpenAI 兼容接口）：不做。语音只用飞书自己的识别接口，失败时作为音频附件转交（用户 2026-10-28 决定）。
- 不改核心层（路由、绑定、会话记录读取）。

**约定不变**（AGENTS.md）：桥接只做简单转接；断开不接收，重连不补历史；不为保证消息不丢增加复杂机制；连接失败如实显示并可重连。

## 2. 协议事实与依据

依据分三类：**官方**（官方文档或官方 SDK 源码），**参考**（Hermes Agent、DeepTutor 的实现），**待确认**（需真实账号扫码确认，见第 12 节）。

| 事实 | 内容 | 依据 |
|---|---|---|
| 扫码 | `POST https://accounts.feishu.cn/oauth/v1/app/registration`，表单编码；`init` → `begin` → `poll`；4xx 也返回 JSON | 官方 SDK `RegisterApp`；参考 |
| 扫码参数 | `begin`：`archetype=PersonalAgent&auth_method=client_secret&request_user_info=open_id`；`poll`：`device_code`、`tp=ob_app` | 参考（Hermes、DeepTutor 一致） |
| 扫码结果 | `client_id`、`client_secret`、`user_info.open_id`、`user_info.tenant_brand`；`error` 为 `authorization_pending` / `slow_down`（间隔加 5 秒）/ `access_denied` / `expired_token` | 官方 SDK；参考 |
| Lark | `tenant_brand=lark` 时换到 `accounts.larksuite.com` / `open.larksuite.com` | 官方 SDK；参考 |
| 长连接地址 | `POST {open}/callback/ws/endpoint` `{"AppID","AppSecret"}` → `data.URL`、`data.ClientConfig`；URL 查询参数含 `device_id`、`service_id` | 官方 Python SDK 1.7.5 `ws/client.py` |
| 帧 | protobuf `pbbp2.Frame`：`SeqID(1,uint64) LogID(2,uint64) service(3,int32) method(4,int32) headers(5,repeated Header{key=1,value=2}) payload_encoding(6) payload_type(7) payload(8,bytes) LogIDNew(9)`；前 4 个是 proto2 required | 官方 SDK 内嵌的 `pbbp2.proto` 描述 |
| 控制帧 | `method=0`；ping 帧 header `type=ping`、`service=service_id`；pong 的 payload 可能带新 `ClientConfig` | 官方 SDK |
| 数据帧 | `method=1`；header `message_id`、`sum`、`seq`、`type`（`event` / `card`）、`trace_id`；`sum>1` 时按 `seq` 拼包 | 官方 SDK |
| 确认 | 把原帧回写，payload 换成 `{"code":200}` 或 `{"code":500}`；成功回 200 并带 header `biz_rt`；处理异常回 500，不带 `biz_rt`（同 `lark-oapi` `client.py:361-379`）；须 3 秒内；处理失败按 15 秒、5 分钟、1 小时、6 小时最多重推 4 次 | 官方 SDK；官方长连接说明 |
| 握手失败 | 响应头 `handshake-status`、`handshake-msg`、`handshake-autherrcode`；514 + 1000040350（连接数超限）或 403 不重连 | 官方 SDK |
| 重连参数 | `ReconnectCount`（-1 无限）、`ReconnectInterval`（秒，默认 120）、`ReconnectNonce`（首次随机抖动上限，默认 30）、`PingInterval`（秒，默认 120） | 官方 SDK |
| 多连接 | 每应用最多 50 条；同一事件只随机投给一条；没有踢线事件 | 官方 |
| 事件 | 信封 `{"schema":"2.0","header":{event_id,event_type,...},"event":{...}}`；`im.message.receive_v1`：`sender.sender_id.open_id`、`sender.sender_type`、`message.{message_id,parent_id,chat_type,message_type,content}`，`content` 是 JSON 字符串 | 官方 SDK 模型 |
| 消息内容 | text `{"text"}`；image `{"image_key"}`；file `{"file_key","file_name"}`；media `{"file_key","image_key","file_name","duration"}`；audio `{"file_key","duration"}`，**无转写**；post `{"title","content":[[...]]}`，接收时 `md` 会被转成其他标签 | 官方 |
| 下载附件 | `GET {open}/open-apis/im/v1/messages/{message_id}/resources/{file_key}?type=image|file`（音频、视频用 `file`）；100 MB 以下整包下载；需 `im:message` 或 `im:message:readonly` | 官方 |
| 取引用 | `GET {open}/open-apis/im/v1/messages/{message_id}` | 官方 SDK |
| 令牌 | `POST {open}/open-apis/auth/v3/tenant_access_token/internal` `{"app_id","app_secret"}` → `code`、`tenant_access_token`、`expire`（秒，最长 7200）；剩余不足 30 分钟时再取会得到新令牌 | 官方 |
| 发送 | `POST {open}/open-apis/im/v1/messages?receive_id_type=open_id`，`Authorization: Bearer <tenant_access_token>`；同一用户 5 QPS；文本请求体 ≤ 150 KB，富文本 ≤ 30 KB；权限 `im:message:send_as_bot` 等三者之一 | 官方 |
| Markdown | 富文本 `post` 的 `md` 标签：CommonMark 0.31 + GFM（标题、加粗、列表、代码块、表格、链接、引用） | 官方 |
| 额度 | 免费版租户内所有自建应用共用月度额度（2026 年 6 月限时 100 万次，之后未公开）；**收消息（事件订阅）、取令牌（认证授权）不计入**；发消息、下载附件、取消息计入；用完返回 99991403 | 官方 |
| 语音 | 语音消息是 Opus（上传文件文档：音频须为 OPUS，转换示例为 libopus + Ogg 封装）；识别 `POST {open}/open-apis/speech_to_text/v1/speech/file_recognize`，只收 pcm、`16k_auto`、适合 60 秒以内，租户 20 QPS，权限 `speech_to_text:speech`；请求 `{"speech":{"speech":<base64>},"config":{"file_id":<16 位字母数字>,"format":"pcm","engine_type":"16k_auto"}}`，返回 `data.recognition_text` | 官方；是否计入额度、默认权限、字节序待确认 |
| 错误码 | 令牌无效 99991661 / 99991663 / 99991665 / 20005 / 20013；凭据或应用不可用 10014 / 10015 / 11207 / 11209 / 99991662 / 99991673 / 20009；限频 99991400 / 1000004 / 1000005；额度 99991403；权限 99991672 / 99991676；用户不在可用范围 230013；未启用机器人 230006 | 官方通用错误码、发送消息文档 |

## 3. 分层与对接

### 3.1 总体结构（新增与改动标注）

```
core/                     不改（只给 AttachmentKind 加 AUDIO）
channel/
  ChannelSpi.kt           改：ConnectionStatus 加 SendFailing；pollIntervalMillis 允许变化
  ChannelFactories.kt     改：登记 FeishuChannelFactory
  Transports.kt           改：WebSocket 支持二进制帧与握手失败信息；新增 HttpJson；下载可带请求头
  BinaryFrameAssembler.kt 新：二进制分片拼接（对应 TextFrameAssembler）
  wecom/                  不改
  feishu/                 新
    FeishuChannelFactory.kt   凭据 → 连接
    FeishuOnboarding.kt       扫码
    FeishuDomain.kt           feishu / lark 两套域名
    FeishuFrameCodec.kt       pbbp2 帧编解码（纯函数）
    FeishuEvents.kt           事件 JSON → 消息（纯函数）
    FeishuApi.kt              HTTP：长连接地址、令牌缓存、发消息、取消息
    FeishuConnection.kt       连接生命周期
    FeishuAttachment.kt       附件按需下载
    FeishuSpeech.kt           语音转文字
  OggOpus.kt              新：Ogg Opus → 16k 单声道 pcm（渠道公共，可复用）
ui/                       改：扫码说明按渠道取文案；状态文字支持 SendFailing
robot/、根包              不改（RobotManager.add 已支持 ownerId 非空直接认主）
```

依赖方向与企业微信一致：`feishu/` 只依赖 `channel/` 的端口和 `core/` 的模型；`ChannelIsolationSourceTest` 自动覆盖新目录，禁止引用终端、Agent、会话记录。

### 3.2 飞书各单元

| 单元 | 职责 | 依赖 | 对应企业微信 |
|---|---|---|---|
| `FeishuChannelFactory` | 从凭据 `app_id`、`app_secret`、`domain` 构造连接 | — | `WecomChannelFactory` |
| `FeishuDomain` | `FEISHU`、`LARK` 两个枚举值，给出 accounts 与 open 的地址；`id` 写入凭据 | — | — |
| `FeishuOnboarding` | `init` 检查支持 `client_secret` → `begin` 出二维码 → `poll`；处理 `slow_down`、Lark 切换 | `HttpJson` | `WecomOnboarding` |
| `FeishuFrameCodec` | `Frame` 与字节互转；只实现 varint 与长度前缀两种编码，未知字段跳过 | — | `WecomProtocol` 帧部分 |
| `FeishuEvents` | 解析事件信封与 `im.message.receive_v1`，产出 `FeishuMessage`（文字、附件引用、`parentId`） | Gson | `WecomProtocol` 消息部分 |
| `FeishuApi` | 取长连接地址；令牌缓存与失效重取；发 Markdown；取引用消息文字；所有返回统一成 `FeishuResult`（成功 / 业务错误码 / 网络错误） | `HttpJson` | 无（企业微信发送走长连接） |
| `FeishuConnection` | 建连、心跳、确认、拼包、去重、重连、入站排队、发送与状态上报 | 以上全部、`FrameTransport` | `WecomConnection` |
| `FeishuAttachment` | `InboundAttachment` 实现：带令牌下载，文件名优先用消息里的 `file_name` | `HttpFileDownload`、`FeishuApi` | `WecomAttachment` |
| `FeishuSpeech` | 下载语音 → `OggOpus` 解码 → 整段一次调用识别（不切段；60 秒以上也提交，仅超过 10 分钟作为内存保护不识别）；任何一步失败返回 null | `FeishuAttachment`、`FeishuApi`、`OggOpus` | 无（企业微信消息自带转写） |
| `OggOpus`（`channel/`） | 拆 Ogg 页、丢 pre-skip、用 Concentus 解成 16k 单声道 pcm；上限 10 分钟 | Concentus | — |

### 3.3 公共层改动（企业微信行为不变）

1. **二进制帧**：`FrameListener` 增加 `fun onBinary(bytes: ByteArray) {}`（默认空实现）；`FrameTransport` 增加 `suspend fun sendBinary(bytes: ByteArray)`。`JdkWebSocketTransport` 实现 `onBinary` 分片拼接（上限 4 MB，超限整条丢弃，同文本规则），二进制发送与文本发送共用同一把串行锁。
2. **握手失败信息**：`open` 握手被拒时抛出 `WebSocketHandshakeRejected(status, headers)`（由 JDK 的 `WebSocketHandshakeException` 转换），飞书据此读取 `handshake-*` 头；企业微信照旧当普通异常处理。
3. **HTTP JSON 客户端**：新增端口 `HttpJson`，两个方法 `get(url, headers)` 与 `post(url, contentType, body, headers)`，都返回 `HttpJsonResponse(status, json)`；4xx/5xx 只要正文是 JSON 也返回，不抛异常（飞书扫码等待态和业务错误都是这样返回的）。JDK 实现 `JdkHttpJson`，代理与超时同现有实现。现有 `HttpJsonGet` 不动，企业微信继续用它。
4. **下载带请求头**：`HttpFileDownload.download` 增加 `headers: Map<String, String> = emptyMap()`。
5. **扫码轮询间隔可变**：`ChannelOnboarding.pollIntervalMillis` 改为每次轮询前读取（接口形状不变，`OnboardingDriver` 本来每次都读）；KDoc 写明“可随轮询结果变化”。
6. **附件种类**：`AttachmentKind` 增加 `AUDIO`；`AttachmentNames` 识别 `OggS` 文件头为 `.ogg`，无文件名时命名 `audio-HHmmss.<扩展名>`。
7. **发送失败状态**：`ConnectionStatus` 增加 `SendFailing(messageKey: String, detail: String?)`：连接在、能收消息，但发送被平台拒绝。`isWorking` 为真（配对和收消息不受影响），界面按“需要注意”显示。下一次发送成功后恢复 `Online`。飞书的受阻标记跨重连保留：长连接不在线时照样记下但不显示（如实显示链路状态），链路回到在线时显示 `SendFailing ?: Online`，只在 stop() 或下一次发送成功时清除（2026-10-30 审查修复，与钉钉一致）。企业微信暂不使用。

## 4. 扫码接入

```
start():
  POST accounts/oauth/v1/app/registration  action=init
    supported_auth_methods 不含 client_secret → Failed("bridge.onboarding.unsupported")
  POST ...  action=begin&archetype=PersonalAgent&auth_method=client_secret&request_user_info=open_id
    无 device_code 或 verification_uri_complete → Failed("bridge.onboarding.noQr")
  interval = begin.interval ?: 5 秒
  → ShowQr(verification_uri_complete)

poll():
  POST ...  action=poll&device_code=..&tp=ob_app   （4xx 正文照常解析；网络异常 → Waiting，下一轮再查）
  user_info.tenant_brand == "lark" 且尚未切换 → domain = LARK（同一响应若已带凭据，照常成功）
  有 client_id 与 client_secret → Succeeded(
        credentials = {app_id, app_secret, domain},
        ownerId = user_info.open_id（可能为 null），
        suggestedName = null)
  error == slow_down → interval += 5 秒，Waiting
  error == access_denied → Failed("bridge.onboarding.denied")
  error == expired_token → Failed("bridge.onboarding.expired")
  其他（含 authorization_pending）→ Waiting
```

- 二维码内容直接用 `verification_uri_complete`，不追加来源参数（DeepTutor 做法）。
- 总超时沿用 `OnboardingDriver` 的 5 分钟（飞书设备码有效 10 分钟，够用）。
- `ownerId` 非空时，`RobotManager.add` 直接写入本人身份、不生成配对码，`AddRobotDialog` 跳过配对页直接完成；为空时自动走配对码流程。这两条现有代码已支持。
- **权限**：本版按参考实现不带 `addons`，依赖 PersonalAgent 默认模板。若真机确认缺少收消息或发消息权限，再按官方文档在 `begin` 时加 `addons` 申请（编码为 gzip 后 base64url 的 JSON），那时另行核实格式。（此前口头说过“扫码时显式申请”，这里改为先不带，原因是两份参考实现都没带且能用，而 `addons` 的编码我们没有验证过。）

## 5. 长连接

### 5.1 生命周期（结构与 `WecomConnection` 相同）

```
runLoop:
  循环直到 stop()：
    上报 Connecting（失败过则 Retrying(原因)；掉线时已报过 Connecting 则不重复）
    runSession() 结束原因：
      Fatal(原因)      → 上报 Failed，退出（凭据无效、连接数超限、403）
      Dropped(原因)    → 在线后被断开：立即上报 Connecting；否则上报 Retrying(原因)；等待重连间隔，继续
runSession:
  1. FeishuApi.endpoint()：code=0 取 URL 与 ClientConfig；
     code 为 1（系统繁忙）或 1000040343（内部错误）→ Dropped；其余非 0 → Fatal
  2. transport.open(URL)；握手被拒：514+1000040350 或 403 → Fatal，其他 → Dropped
     其他异常 → Dropped(异常类名)：异常消息可能带完整 wss 地址（含 ticket 等），原因会写进日志
  3. 上报 Online，启动心跳；等待会话结束
```

- **重连间隔**：遵循服务端下发的参数。首次重连先随机等待 `[0, ReconnectNonce)` 秒，之后每次间隔 `ReconnectInterval` 秒；`ReconnectCount ≥ 0` 时用完次数上报 Failed。因为默认间隔长达 120 秒，**第一次重连失败就上报 `Retrying(原因)`**，不像企业微信等 5 次。在线后被断开**立即**上报 `Connecting` 再等随机抖动，不让界面在掉线后仍显示“在线”最多约 30 秒（2026-10-30 审查修复）。
- **心跳**：每 `PingInterval` 秒发 ping；收到 pong 时如带 `ClientConfig` 就更新参数。**另加一条存活检查**：超过 3 个心跳周期没有收到任何帧，判定连接已死，按 Dropped 重连。ping 的发送限时一个心跳周期，写入挂起时也能照常做存活检查。官方 SDK 只靠读错误发现断线，这是我们加的保护（对应企业微信“连续 2 次 ping 无回应”）。
- **stop()**：取消循环、关闭传输、上报 Stopped，与企业微信一致。

### 5.2 收帧

```
onBinary(bytes):
  frame = FeishuFrameCodec.decode(bytes)   失败则记日志丢弃
  method=0：type=pong → 更新 ClientConfig；其他忽略
  method=1：
    校验 sum 为正整数、seq 在 [0,sum) 内、同一消息 sum 一致
    按 message_id 拼包（缓存 10 秒，与 Node SDK 相同）；未齐则返回
    type=event：
      解析事件，交给入站队列处理
      成功回写 {"code":200} 并带 biz_rt；处理异常回写 {"code":500}，不带 biz_rt
    type=card：不处理、不回写（与 SDK 相同）
```

- **处理后确认**：与官方 SDK 一致（`lark-oapi` `ws/client.py` `_handle_data_frame`：先调用事件处理，处理完再回写确认帧），拼齐并处理后才确认；这里处理完成指取引用、语音转写并调用 `sink.accept` 完成，不等待 CLI 执行完成。成功回 200，处理异常回 500，并移除本次去重标记以允许平台重投。慢处理可能触发平台重投，按消息 id 去重。2026-10-30 用户要求与官方 SDK 保持一致，撤回本轮曾改的“先确认后处理”。
- **入站队列**：每个连接一个单消费者协程，按到达顺序处理：取引用文字（有 `parent_id` 时）→ 语音识别（有语音时；失败则作为音频附件）→ 组装 `InboundMessage` → `sink.accept`，完成后用事件到达时的会话回写确认（掉线后旧会话写不出去，平台会重投）。取引用失败时 `quote = null`，照常转交。按序处理是本项目为满足 `InboundSink` 顺序要求的补充，SDK 为并发处理（`lark-oapi` 1.7.5 `ws/client.py:243` 对每条消息 `loop.create_task(self._handle_message(msg))`）。
- **去重**：按 `message_id`（平台重投用同一个），容量 1000，与企业微信相同。

## 6. 收消息解析

只处理 `event_type == im.message.receive_v1`、`sender_type == user`、`chat_type == p2p` 的消息；`senderId = chatId = sender.sender_id.open_id`（满足 `ChannelSpi` 的单聊约定）。

| message_type | 文字 | 附件 |
|---|---|---|
| text | `text` | — |
| post | 标题 + 各段 `text`、`a`（取文字）、`at`（取名字）、`code_block`（取代码）按顺序拼接，段之间换行；兼容外层带语言键的形状 | 每个 `img` → IMAGE |
| image | — | IMAGE（`image_key`，`type=image`） |
| file | — | FILE（`file_key`，`type=file`，文件名 `file_name`） |
| media | — | VIDEO（`file_key`，`type=file`，文件名 `file_name`） |
| audio | — | AUDIO（`file_key`，`type=file`） |
| 其他（表情包、卡片、分享等） | — | — → 无文字无附件，丢弃（与企业微信一致） |

- **引用**：`parent_id` 非空时调用取消息接口，只取被引用消息的文字（text / post），以现有方式交给 `InboundMessage.quote`；引用的是图片、文件时为 null。
- **附件**：只记引用，路由器确认本人、非重复、已绑定后才调用 `fetch`，与企业微信相同。
- **语音**：在连接的按序队列里先调用 `FeishuSpeech`。识别成功：文字作为消息正文（有文字时用换行拼在后面），不再附带音频；失败（没权限、额度、解码、识别为空等）：作为 AUDIO 附件转交，落盘后由路由器交出相对路径。识别在路由器校验本人之前进行，这是与其他附件不同的地方：飞书单聊应用只有本人会发消息，接受这点开销。

## 7. 发消息

- **格式**：`msg_type=post`，`content = {"zh_cn":{"content":[[{"tag":"md","text":<分段文本>}]]}}`（JSON 字符串）。
- **分段**：`maxTextBytes = 15_000`。富文本请求体上限 30 KB，而正文要在 JSON 里被转义两层（换行、引号会变长），取一半作余量；发送前再算一次请求体字节数，超过 30 KB（引号、反斜杠多的正文转义后会超）时在渠道内把这段对半切开分别发送，优先切在中点附近的换行之后、不切开代理对，递归直到每段不超限（只剩一个字符仍超限时 `Rejected("too large")`，实际不会发生，只为防止无限递归）。每段照常过限频；全部成功返回 `Accepted`，遇到第一段失败即返回该失败，已发出的不撤回（2026-10-30 审查修复，原先整段拒发）。
- **限频**：`SlidingWindowLimiter(5, 1000 毫秒)` 按 `chatId`；等待超过 20 秒则 `Rejected("rate limited")`，与企业微信相同。
- **令牌**：`FeishuApi` 缓存令牌，剩余有效期不足 10 分钟就重新获取（与官方 SDK 相同）；遇到令牌无效类错误码，清空缓存重取并**只重试一次**。令牌只在内存，不落盘；断网多久都不影响，恢复后随用随取。
- **结果映射**：

| 情况 | 返回 | 状态 |
|---|---|---|
| `code=0` | `Accepted` | 若之前是 `SendFailing`，恢复 `Online`（链路在线时） |
| 网络异常、超时 | `Unknown` | 不变 |
| 限频（99991400 / 1000004 / 1000005） | `Rejected("rate limited")` | 不变 |
| 额度用完（99991403） | `Rejected` | `SendFailing("bridge.feishu.quota")` |
| 无权限（99991672 / 99991676）、未启用机器人（230006）、用户不在可用范围（230013） | `Rejected` | `SendFailing("bridge.feishu.permission", 错误码)` |
| 凭据或应用不可用（取令牌返回 10014 / 10015 / 11207 / 11209 / 99991662 / 99991673 / 20009） | `Rejected` | `SendFailing("bridge.feishu.app", 错误码)` |
| 其他业务错误 | `Rejected("<code> <msg>")` | 不变 |

- 不自动重发失败的消息（现有约定）；`SendFailing` 只负责让界面如实显示。额度用完后不做任何定时恢复，下一次发送成功自然回到 `Online`（失败的调用不占额度）。受阻标记跨重连保留，见第 3 节第 7 条。
- 不使用 `uuid` 去重：我们不重发，用不上。

## 8. 附件下载

`FeishuAttachment.fetch(maxBytes)`：

1. 向 `FeishuApi` 要令牌；
2. `download(resourceUrl(messageId, fileKey, type), maxBytes, headers = {"Authorization": "Bearer …"})`；
3. 文件名：消息里的 `file_name` 优先，其次响应头 `Content-Disposition`；
4. 返回 `FetchResult`。不加密，没有解密步骤。

令牌无效时与发送相同，重取一次再下载。100 MB 上限沿用 `AttachmentStore.MAX_BYTES`，超过 100 MB 的文件按 TooLarge 处理（飞书要求更大的文件分片下载，本版不做）。

## 9. 状态、文案与界面

- **添加对话框**：飞书在 `ChannelFactories` 登记后自动变为可选。扫码说明改为按渠道取文案：`bridge.add.scan.steps.<渠道 id>`，没有专用文案时退回现有 `bridge.add.scan.steps`。飞书文案大意：“1. 用飞书扫码 2. 在手机上确认创建应用 3. 完成后自动连接”。
- **扫码失败文案**：新增 `bridge.onboarding.unsupported`、`bridge.onboarding.denied`、`bridge.onboarding.expired`。
- **SendFailing 文案**：`bridge.feishu.quota`（“本月飞书 API 调用额度已用完，下月 1 日恢复；手机消息仍会转交给终端”）、`bridge.feishu.permission`（“飞书应用缺少发消息权限或你不在可用范围（{0}）”）、`bridge.feishu.app`（“飞书应用不可用（{0}），请移除后重新扫码”）。
- `RobotRows` 的 `statusText` 与 `robotHealth` 处理 `SendFailing`：显示上述文字，健康度为“需要注意”；状态栏、横幅自动跟随（它们都走 `needsAttention`）。
- 所有新文案补齐 10 种语言。

## 10. 凭据与持久化

- 凭据存在 `PasswordSafeVault`：`{"app_id","app_secret","domain"}`；`OnboardingStep.Succeeded.toString()` 已屏蔽凭据值。
- `RobotRecord.kind = FEISHU`，`ownerSenderId = ownerChatId = open_id`。持久化结构不变。
- 日志不打印 `app_secret`、令牌、消息正文（与企业微信一致）。

## 11. 测试

| 测试 | 覆盖 |
|---|---|
| `FeishuFrameCodecTest` | 用官方 Python SDK（`lark-oapi` 1.7.5 的 `pbbp2_pb2.Frame`）生成的字节作为固定样本：解码、编码后逐字节一致；required 字段为 0 也写出；未知字段跳过；截断输入报错 |
| `FeishuEventsTest` | 各消息类型的解析表；非 p2p、非 user、非 receive_v1 被忽略；post 带语言外壳；`parent_id` |
| `FeishuOnboardingTest` | 假 HTTP：正常成功、`slow_down` 间隔递增、Lark 切换且同一响应带凭据、`access_denied`、`expired_token`、init 不支持、缺 `open_id` 时 `ownerId=null` |
| `FeishuApiTest` | 令牌缓存与提前刷新；令牌无效重取只重试一次；各错误码分类 |
| `FeishuConnectionTest` | 假传输：处理成功后确认（取引用完成并交出后才确认）；异常回 500 且允许重投；缺片不确认；非法元数据不处理；拼包；去重；入站顺序（第一条取引用慢，第二条也不会先到）；pong 更新心跳间隔；3 个周期无帧判死重连；握手 514+1000040350 不重连；endpoint 业务错误分类；额度用完 → `SendFailing`，随后成功 → `Online`；受阻跨重连保留；ping 发送挂起仍判死；掉线立即报 Connecting；超限正文分段发出；建连异常原因不含地址；stop 上报 Stopped |
| `FeishuAttachmentTest` | 带令牌请求头；文件名优先级；超限 |
| `OggOpusTest` | 用 ffmpeg（libopus）生成的单声道 16k、立体声 48k 样本：时长与非静音；超限、非 Ogg、截断报错 |
| `FeishuSpeechTest` | 整段一次识别、请求参数、临时文件删除；识别报错、结果为空、解码失败、异常大文件都返回 null |
| 传输层 | `BinaryFrameAssemblerTest`；`JdkWebSocketTransport` 握手被拒转换 |
| 现有测试 | `ChannelIsolationSourceTest` 自动覆盖新目录；企业微信相关测试全部保持通过 |

收尾按 AGENTS.md：全量测试、`lint_files`、`./gradlew verifyPlugin`。

## 12. 需要真机确认的事项

实现完成后用你的飞书账号扫码一次，确认：

1. 扫码返回的 `open_id` 与发消息时的发送人 `open_id` 一致。不一致时，`FeishuOnboarding` 改为返回 `ownerId = null`，走配对码认主（现有机制）。
2. 扫码创建的应用能直接收单聊消息、能发消息，不需要后台发布。缺权限时按第 4 节加 `addons`。
3. 语音：消息是否为标准 Ogg Opus；应用是否有 `speech_to_text:speech` 权限（没有则在扫码时加 `addons` 或后台手动开通，期间退回附件）；pcm 字节序；识别是否计入月度额度。
4. 本月额度与用量（管理后台查看），决定是否长期使用。

## 13. 文档更新

- `docs/channel-protocols.md` 第 2 节：补充本次核实的事实（额度计入范围、`md` 标签、附件下载、语音无转写、错误码、令牌刷新规则），删去“依赖可能冲突”等不再适用的选型描述，改为“自实现，不引入 SDK”。
- `docs/channel-limits.md` 飞书部分：补“收消息与取令牌不计入额度”。
