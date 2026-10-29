# 钉钉渠道接入设计

日期：2026-10-29
状态：用户已确认（2026-10-29），直接实现，不先跑验证脚本。

## 1. 目标与范围

在手机桥接中新增钉钉渠道，与企业微信、飞书并列。用户在 IDE 里扫码创建（或选择）钉钉机器人，用配对码认主；之后与其他渠道一样：手机单聊消息转交给绑定的终端会话，CLI 新的最终回复主动推回手机。

**做**

- 扫码创建或选择机器人，配对码认主（扫码结果不带本人身份）。
- Stream 长连接收单聊消息：文本、富文本、图片、文件、视频、语音（用平台给的 `recognition`）、引用。
- 主动推送：`batchSend` + `sampleMarkdown`，分段、限频。
- 连接状态、重连、额度用完、权限不足的如实显示。

**不做**

- 不引入钉钉 SDK（`app-stream-client` 5.4 MB，内含 shade 的 Netty，且只覆盖 Stream）；Stream、扫码、OpenAPI 全部用 JDK 自实现，与企业微信、飞书一致（用户 2026-10-29 决定）。
- 群聊、AI 卡片、`sessionWebhook` 回复、出站媒体、撤回、自行语音识别。
- 不改核心层（路由、绑定、会话记录读取）；限频重发沿用路由器现有的 `DeliveryResult.RATE_LIMITED` 机制。

**约定不变**（AGENTS.md）：桥接只做简单转接；断开不接收，重连不补历史；不为保证消息不丢增加复杂机制；连接失败如实显示并可重连。

**前提（未验证，用户 2026-10-29 决定不先跑验证脚本、直接实现）**：用 `DING_DWS_CLAW` 扫码能建出应用；单聊消息带 `senderStaffId`，`batchSend` 用它能发出。实现完成后真机联调确认；不成立时界面如实显示（第 6、7 节的 `noStaffId`、`permission` 状态），再回来改设计。

## 2. 协议事实与依据

事实与出处见 [`docs/channel-protocols.md`](../../channel-protocols.md) 第 3 节（2026-10-29 第二轮核实）与 [`docs/channel-limits.md`](../../channel-limits.md) 第 4 节“第三轮核实”。本设计只引用其中结论，标“官方”的已读原文，标“连接器”的只见于官方连接器源码，标“待真机”的见第 12 节。

| 事实 | 内容 | 依据 |
|---|---|---|
| 扫码 | `POST https://oapi.dingtalk.com/app/registration/{init,begin,poll}`，JSON；`errcode=0` 才成功；init `{"source"}` → `nonce`；begin `{"nonce"}` → `device_code`、`verification_uri_complete`、`expires_in=7200`、`interval=2`；poll `{"device_code"}` → `status` WAITING/SUCCESS/FAIL/EXPIRED、`client_id`、`client_secret`、`fail_reason` | 连接器 + 实测 init/begin |
| source | 用 `DING_DWS_CLAW`（用户 2026-10-29 决定）；自定义值会被去掉 | 实测 |
| 身份 | poll 不返回任何用户身份 | 连接器 + 实测 |
| robotCode | 等于 `client_id` | 连接器；待真机 |
| 建连 | `POST https://api.dingtalk.com/v1.0/gateway/connections/open`，`Accept: application/json`，`{clientId, clientSecret, subscriptions:[{type:"CALLBACK",topic:"/v1.0/im/bot/messages/get"}], ua}` → `{endpoint, ticket}`；ticket 90 秒一次性；`endpoint` 已含 `/connect` | 官方 |
| 帧 | `{specVersion,type,headers{topic,messageId,contentType,time},data:"<json>"}`；ACK `{"code":200,"headers":{"messageId","contentType":"application/json"},"message":"OK","data":"{\"response\": null}"}` | 官方 |
| 系统帧 | `ping`：原样回传 `data` 与 `messageId`；`disconnect`：不再推送，10 秒后断开，应立即重建 | 官方 |
| 重推 | 文档说不重推；连接器记录过超 60 秒被重推，`headers.messageId` 变、`data.msgId` 不变 | 官方 / 连接器 |
| 多连接 | 每条消息随机推给其中一条连接；未见互踢 | 官方 |
| 消息字段 | `msgId`、`conversationType`（`"1"` 单聊）、`senderStaffId`（发布上线后才返回）、`senderId`（加密）、`robotCode`、`msgtype`、`createAt` 等 | 官方 |
| 消息类型 | `text.content`；`richText`（`content.richText[]`：`{text}` 或 `{type:"picture",downloadCode}`）；`picture`、`video`（`content.downloadCode`）；`file`（`content.downloadCode, fileName`）；`audio`（`content.downloadCode, recognition, duration`）；`unknownMsgType` | 官方 |
| 引用 | `text.isReplyMsg` + `text.repliedMsg{msgType, content, msgId}`；richText 在 `content` 下 | 连接器；待真机 |
| 超额入站 | 消息带 `errorCode=20001`、`errorMessage`，无 text 与 content | 官方 |
| 附件 | `POST https://api.dingtalk.com/v1.0/robot/messageFiles/download`，头 `x-acs-dingtalk-access-token`，`{downloadCode, robotCode}` → `downloadUrl` | 官方 |
| 令牌 | `POST https://api.dingtalk.com/v1.0/oauth2/accessToken` `{appKey, appSecret}` → `accessToken, expireIn`（7200）；有效期内重复获取返回相同结果；不计额度 | 官方 |
| 发送 | `POST https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend`，`{robotCode, userIds:[userId], msgKey, msgParam}`；只支持企业内部应用机器人；响应 `processQueryKey`、`invalidStaffIdList`、`flowControlledStaffIdList` | 官方 |
| Markdown | `sampleMarkdown` `{title, text}`；官方子集：标题、引用、加粗、斜体、链接、图片、无序/有序列表；无代码块、表格 | 官方 |
| 长度 | 单聊上限未公布；群发 `msgParam` 15000 字节 | 官方 |
| 额度 | 标准版 API 10000 次/月、20 QPS（2026-10-21 起）；Webhook&Stream 5000 次/月（计费页），机器人收消息很可能计入 | 官方 |
| 错误码 | 额度 `Forbidden.AccessDenied.ApiCountLimitForOrg`；限流 `Forbidden.AccessDenied.QpsLimitForAppkeyAndApi`、`QpsLimitForApi`、`send.too.fast`、`send.byToken.tooFast`；发送受阻 `chatbotId.notAllow.sendOTO`、`robot.oto.notExist`、`bot.forbidden.sendMessage`、`staffId.notExisted`、`invalidParameter.userId.empty`；凭据 `invalidClientIdOrSecret` | 官方 |

## 3. 分层与对接

### 3.1 总体结构（新增与改动标注）

```
core/                     不改
channel/
  ChannelSpi.kt           改：SendFailing 注释改为“收发受阻”（行为不变）
  ChannelFactories.kt     改：登记 DingtalkChannelFactory
  Transports.kt           改：FrameTransport 增加 WebSocket ping，FrameListener 增加 onPong（均有默认实现）
  wecom/、feishu/         不改
  dingtalk/               新
    DingtalkChannelFactory.kt   凭据 → 连接；扫码说明文案键
    DingtalkOnboarding.kt       扫码
    DingtalkApi.kt              HTTP：令牌缓存、建连、发送、附件下载地址
    DingtalkEvents.kt           Stream 帧与机器人消息 JSON → 消息（纯函数）
    DingtalkConnection.kt       连接生命周期
    DingtalkAttachment.kt       附件按需下载
ui/                       不改（扫码说明按渠道取文案、SendFailing 显示均已支持）
robot/、根包              不改（ownerId 为 null 时走配对码，现有流程）
```

依赖方向与企业微信、飞书一致：`dingtalk/` 只依赖 `channel/` 的端口和 `core/` 的模型；`ChannelIsolationSourceTest` 自动覆盖新目录。

**各渠道各自一套 SDK，互不复用**（用户 2026-10-29 要求）：`dingtalk/` 不引用 `feishu/`、`wecom/` 的任何类，即使逻辑相似（令牌缓存、重连循环、结果类型、凭据键）也在 `dingtalk/` 内自己写一份；反过来也一样。文中“与飞书相同”只指行为一致，不指代码共用。能共用的只有 `channel/` 根包里的框架端口（`FrameTransport`、`HttpJson`、`HttpFileDownload`、`SlidingWindowLimiter`、`ConnectionStatus` 等）和 `core/` 模型，它们不属于任何渠道。`ChannelIsolationSourceTest` 增加一条：`channel/<渠道>/` 下的源码不得 import 其他渠道子包。

### 3.2 钉钉各单元

| 单元 | 职责 | 依赖 | 对应飞书 |
|---|---|---|---|
| `DingtalkChannelFactory` | 从凭据 `client_id`、`client_secret` 构造连接；`scanStepsKey = "bridge.add.scan.steps.dingtalk"` | — | `FeishuChannelFactory` |
| `DingtalkCredentialKeys` | 凭据键名常量 | — | `FeishuCredentialKeys` |
| `DingtalkOnboarding` | init → begin 出二维码 → poll | `HttpJson` | `FeishuOnboarding` |
| `DingtalkEvents` | 解析 Stream 帧（类型、topic、messageId、data）；解析机器人消息为 `DingtalkMessage`（staffId、msgId、文字、附件引用、引用文字、超额标记） | Gson | `FeishuEvents` + `FeishuFrameCodec` |
| `DingtalkApi` | 令牌缓存与失效重取；`openConnection()`；`send()`；`downloadUrl()`；所有返回统一成 `DingtalkResult`（成功 / 业务错误 / 网络错误） | `HttpJson` | `FeishuApi` |
| `DingtalkConnection` | 建连、ACK、系统帧、ping 存活、去重、按序入站、重连、发送与状态上报 | 以上全部、`FrameTransport` | `FeishuConnection` |
| `DingtalkAttachment` | `InboundAttachment` 实现：换下载地址再下载 | `DingtalkApi`、`HttpFileDownload` | `FeishuAttachment` |

### 3.3 公共层改动（企业微信、飞书行为不变）

1. **WebSocket ping**：`FrameTransport` 增加 `suspend fun sendPing(): Unit = throw UnsupportedOperationException(...)`；`FrameListener` 增加 `fun onPong() {}`。`JdkWebSocketTransport` 用 JDK `WebSocket.sendPing` 实现，与文本、二进制发送共用串行锁，`onPong` 转给监听器。原因：钉钉服务端应用层 ping 的频率未公布，官方 Java SDK 靠 WebSocket ping 判断存活。
2. **SendFailing 注释**：由“发送被平台拒绝”改为“收发受阻（额度用完、缺权限等）”，因为钉钉收消息额度用完也用它显示。类型、`isWorking`、界面行为都不变。

## 4. 扫码接入

```
start():
  POST init {"source":"DING_DWS_CLAW"}
    errcode≠0 或无 nonce → Failed("bridge.onboarding.noQr")
  POST begin {"nonce"}
    无 device_code 或 verification_uri_complete → Failed("bridge.onboarding.noQr")
  interval = begin.interval ?: 2 秒
  → ShowQr(verification_uri_complete)

poll():
  POST poll {"device_code"}
  网络错误、errcode≠0 → Waiting（总超时由 OnboardingDriver 的 5 分钟兜底）
  status=SUCCESS 且有 client_id、client_secret → Succeeded(
        credentials = {client_id, client_secret}, ownerId = null, suggestedName = null)
  status=SUCCESS 但缺凭据 → Failed("bridge.onboarding.noCredentials")
  status=EXPIRED → Failed("bridge.onboarding.expired")
  status=FAIL → Failed("bridge.dingtalk.onboardingFailed")
  其他（含 WAITING）→ Waiting
```

- `OnboardingStep.Failed` 只有文案键没有参数，界面显示固定文案“扫码授权失败，请重试”；`fail_reason` 不显示（现有渠道代码不写日志）。
- `ownerId = null` → `RobotManager.add` 生成配对码，`AddRobotDialog` 进入配对页（现有流程）。
- 扫码说明 `bridge.add.scan.steps.dingtalk`：“1. 打开手机{0}，扫一扫\n2. 按提示创建或选择机器人（可能需要组织管理员）\n3. 回到这里继续配对”。

## 5. 长连接

### 5.1 生命周期（结构与 `FeishuConnection` 相同）

```
runLoop:
  循环直到 stop()：
    上报 Connecting（连续失败达到 5 次后上报 Retrying(原因)）
    runSession() 结束原因：
      Fatal(原因)       → 上报 Failed，退出（凭据被拒累计达上限）
      Disconnect        → 立即重建，不计失败
      Dropped(原因)     → 退避后重连
runSession:
  1. DingtalkApi.openConnection()：成功取 endpoint、ticket；
     凭据类错误（HTTP 401/403 或 code=invalidClientIdOrSecret）计一次鉴权失败；其他 → Dropped
  2. transport.open("$endpoint?ticket=<urlencode>")
  3. 并行启动：writer（发 ACK 与回复）、keepAlive（存活检查）
  4. 收到第一帧（任意文本帧或 pong）时上报 Online（若之前有收发受阻状态则保留）；等待会话结束
     在线以收到第一帧为准；与官方 SDK 一致，握手后不主动 ping，只在空闲周期 ping（官方 Java SDK KeepAliveHandler）。
     服务端连上后不发帧时，在线显示可能延后到首次空闲 ping 的 pong（2026-10-30 用户要求与 SDK 保持一致，去掉握手后立即 ping）
     未收到任何帧即断开的会话不报 Online，直接按 Dropped 处理
```

- **凭据失败**：凭据被拒累计 5 次、期间未成功上线即报 Failed（2026-10-30 决定：只有会话真正上线才清零 `authFailures`，网络错误导致的 Dropped 不清零，避免凭据失效又遇网络不稳时永远到不了 Failed）。
- **退避**：2、4、8…秒，上限 60 秒；成功连上后清零。
- **存活**：每 60 秒 `sendPing()`；距上一次收到任何帧或 pong 超过 120 秒，判定连接已死，按 Dropped 重连。
- **disconnect**：收到后立即结束本会话、关闭传输、重新取 ticket 建连；已收到的消息照常处理完。
- **stop()**：取消循环、关闭传输、上报 Stopped。
- 不上报 Kicked（钉钉多连接随机投递，没有踢线）。

### 5.2 收帧

```
onText(text):
  frame = DingtalkEvents.frame(text)   解析失败记日志丢弃
  type=SYSTEM：
    topic=ping → 回 {"code":200,"headers":{"messageId","contentType":"application/json"},"message":"OK","data":<原 data>}
    topic=disconnect → 结束会话（Disconnect）
    其他 → 忽略
  type=CALLBACK 且 topic=/v1.0/im/bot/messages/get：
    立即回写 ACK
    解析 data → 交给入站队列
  其他 → 回写 ACK 后忽略
```

- **先 ACK 后处理**：ACK 在 WebSocket 回调里经串行发送锁同步发出，不等解析和转交。
- **去重**：按 `data.msgId`，容量 1000（与企业微信、飞书相同）；不用 `headers.messageId`。
- **入站队列**：单消费者协程按到达顺序转交，满足 `InboundSink` 的顺序要求。

## 6. 收消息解析

只处理 `conversationType == "1"`（单聊）。`senderId = chatId = senderStaffId`（满足 `ChannelSpi` 的单聊约定）。

| msgtype | 文字 | 附件 |
|---|---|---|
| text | `text.content` | — |
| richText | 各 `{text}` 按顺序拼接 | 每个 `type=picture` → IMAGE |
| picture | — | IMAGE |
| file | — | FILE（文件名 `fileName`） |
| video | — | VIDEO |
| audio | `recognition` 非空白时作为正文 | `recognition` 为空时 → AUDIO |
| 其他（`unknownMsgType` 等） | — | 无文字无附件，丢弃 |

- **引用**：`isReplyMsg` 为真时取 `repliedMsg` 的文字：`msgType` 为 `text` 取 `content.text`，为 `richText` 拼接其中各段文字（与连接器相同；兼容 `content` 为字符串或对象）；引用的是图片、文件等时为 null。
- **没有 `senderStaffId`**：丢弃该消息，状态显示 `SendFailing("bridge.dingtalk.noStaffId")`（机器人可能未发布，无法回复也无法配对）。下一条带 staffId 的消息到达后清除。
- **超额（`errorCode` 存在）**：丢弃该消息，状态显示 `SendFailing("bridge.dingtalk.receiveQuota")`；下一条正常消息到达后清除。
- **附件**：只记引用（`downloadCode`、文件名），路由器确认本人、非重复、已绑定后才调用 `fetch`，与其他渠道相同。

## 7. 发消息

- **格式**：`msgKey = "sampleMarkdown"`，`msgParam = {"title": <标题>, "text": <分段文本>}`（JSON 字符串）。标题取正文第一行去掉 Markdown 标记，截到 20 字；为空时用“imux”。代码块、表格原样发送，真机截图后再决定是否转换。
- **分段**：`maxTextBytes = 6_000`。发送前计算 `msgParam` 字节数，超过 15000 直接 `Rejected("too large")`（单聊上限未公布，参照群发）。
- **限频**：`SlidingWindowLimiter(5, 1000 毫秒)` 按 `chatId`；等待超过 20 秒则 `Rejected(RATE_LIMITED)`，与飞书相同。
- **令牌**：`DingtalkApi` 缓存，剩余不足 10 分钟重取；发送或下载遇到 HTTP 401 或令牌类错误码时清缓存重取，只重试一次。令牌只在内存。
- **结果映射**：

| 情况 | 返回 | 状态 |
|---|---|---|
| HTTP 200，`invalidStaffIdList`、`flowControlledStaffIdList` 都为空 | `Accepted` | 清除发送类 `SendFailing` |
| `flowControlledStaffIdList` 非空；`QpsLimitForAppkeyAndApi`、`QpsLimitForApi`、`send.too.fast`、`send.byToken.tooFast` | `Rejected(RATE_LIMITED)` | 不变 |
| `ApiCountLimitForOrg` | `Rejected` | `SendFailing("bridge.dingtalk.quota")` |
| `chatbotId.notAllow.sendOTO`、`robot.oto.notExist`、`bot.forbidden.sendMessage`、`staffId.notExisted`、`invalidStaffIdList` 非空 | `Rejected` | `SendFailing("bridge.dingtalk.permission", 错误码)` |
| 取令牌返回 `invalidClientIdOrSecret` | `Rejected` | `SendFailing("bridge.dingtalk.app", 错误码)` |
| 网络异常、超时 | `Unknown` | 不变 |
| 其他业务错误 | `Rejected("<code> <message>")` | 不变 |

- 不自动重发（现有约定，限频除外由路由器处理）；额度用完不做定时恢复，下一次发送成功自然清除。
- 收类与发类受阻并存时，界面只显示一条：发类优先（它影响回复能否送达）。

## 8. 附件下载

`DingtalkAttachment.fetch(maxBytes)`：

1. 向 `DingtalkApi` 要 `downloadUrl(downloadCode)`（带令牌，`robotCode = client_id`）；失败 → `FetchResult.Failed(原因)`。
2. `download(downloadUrl, maxBytes)`（不需要额外请求头）。
3. 文件名：消息里的 `fileName` 优先；否则交给现有 `AttachmentNames` 按文件头命名（下载地址给出的扩展名可能是 `.file`，不采用）。
4. 上限沿用 `AttachmentStore.MAX_BYTES`（100 MB）。

## 9. 状态、文案与界面

- 添加对话框：钉钉登记后自动可选；扫码说明用 `bridge.add.scan.steps.dingtalk`；配对页沿用现有流程，但等待配对码时若连接处于收发受阻（如 `noStaffId`），在“等待配对码”后附上该状态文字（整体评审后用户 2026-10-29 同意改 `AddRobotDialog`，对所有渠道生效）。
- 新增文案（10 种语言）：
  - `bridge.add.scan.steps.dingtalk`（见第 4 节）
  - `bridge.dingtalk.onboardingFailed`：“钉钉扫码授权失败，请重试。”
  - `bridge.dingtalk.quota`：“本月钉钉 API 调用额度已用完；手机消息仍会转交给终端，但回复发不出去。”
  - `bridge.dingtalk.receiveQuota`：“本月钉钉消息接收额度已用完，手机发来的消息收不到。”
  - `bridge.dingtalk.permission`：“钉钉机器人缺少发消息权限或未启用（{0}）”
  - `bridge.dingtalk.app`：“钉钉应用不可用（{0}），请移除后重新扫码。”
  - `bridge.dingtalk.noStaffId`：“钉钉没有给出发送人 userId，机器人可能未发布，无法回复。”
- `RobotRows` 等界面对 `SendFailing` 的处理已通用，不改。

## 10. 凭据与持久化

- 凭据存 `PasswordSafeVault`：`{"client_id","client_secret"}`；`OnboardingStep.Succeeded.toString()` 已屏蔽凭据值。
- `RobotRecord.kind = DINGTALK`（`ChannelKind` 已预留），`ownerSenderId = ownerChatId = senderStaffId`（配对时写入）。持久化结构不变。
- 日志不打印 `client_secret`、令牌、`ticket`、消息正文、`downloadCode`。

## 11. 测试

| 测试 | 覆盖 |
|---|---|
| `DingtalkEventsTest` | 帧解析（SYSTEM/CALLBACK、topic、messageId）；消息类型表；richText 拼接与图片；语音有无 recognition；引用（对象与字符串两种 content）；非单聊忽略；无 staffId、`errorCode` 标记 |
| `DingtalkOnboardingTest` | 假 HTTP：正常成功（source 为 `DING_DWS_CLAW`、`ownerId=null`）；WAITING；EXPIRED；FAIL；缺凭据；网络错误继续等；init/begin 失败 |
| `DingtalkApiTest` | 令牌缓存与提前刷新；令牌失效重取只重试一次；`batchSend` 请求体（robotCode、userIds、msgKey、msgParam 为 JSON 字符串）；两个名单与各错误码分类；`downloadUrl` |
| `DingtalkConnectionTest` | 假传输：先 ACK 再处理；ping 原样回传；disconnect 立即重建且不计失败；msgId 去重（headers.messageId 不同）；入站顺序；ping 超时判死重连；凭据连续无效 → Failed；退避与 Retrying；无 staffId / 20001 显示与恢复；发送结果映射与 SendFailing 清除；stop 上报 Stopped |
| `DingtalkAttachmentTest` | 两步下载；文件名优先级；换地址失败；超限 |
| 传输层 | `JdkWebSocketTransport` 的 ping/pong（如现有测试方式可行） |
| 现有测试 | `ChannelIsolationSourceTest` 自动覆盖新目录，并新增“渠道子包之间不得互相 import”；企业微信、飞书测试全部保持通过 |

收尾按 AGENTS.md：全量测试、`lint_files`、`./gradlew verifyPlugin`。

## 12. 需要真机确认的事项

实现后联调首先确认（设计前提）：

1. 用 `DING_DWS_CLAW` 扫码能否建应用；是否要求组织管理员。
2. 单聊消息有无 `senderStaffId`；`batchSend` 能否发出；`robotCode` 是否等于 `client_id`。

其后：

3. 隔数小时后主动推送是否仍成功。
4. `sampleMarkdown` 中代码块、表格的实际显示；单聊长度上限。
5. 语音是否总带 `recognition`；引用字段实际形态。
6. 后台“资源管理”显示的版本与额度；收消息是否计入 Webhook&Stream 额度。
7. 服务端 ping、disconnect 的实际频率；不 ACK 是否重推。

## 13. 文档更新

- `docs/channel-protocols.md` 第 3 节：补真机确认结果。
- `docs/channel-limits.md` 第 4 节：补实际额度显示。
