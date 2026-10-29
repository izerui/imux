# 扫码渠道的协议参考

**实现范围：企业微信、飞书、钉钉、普通微信。QQ 已决定不纳入（2026-10-27），第 4 节仅保留为调研记录。**

核实日期：2026-10-26。来源为官方文档、官方 SDK 或客户端源码，以及 DeepTutor、Hermes Agent 等参考实现。标“未核实”的需要真实账号联调确认。本文是各渠道实现计划的事实依据，与 [渠道限制](channel-limits.md)、[终端通讯设计](terminal-channel-bridge-feasibility.md) 配合阅读。

## 0. 对框架接口的影响

| | 企业微信 | 飞书 | 钉钉 | QQ | 普通微信 |
| --- | --- | --- | --- | --- | --- |
| 扫码返回本人身份 | 否 → 配对码 | 是（open_id） | 否 → 配对码 | 是（user_openid） | 是（ilink_user_id） |
| 扫码额外步骤 | — | — | — | 首次在手机上取名创建 | 可能要求输入手机显示的验证码；二维码可刷新 3 次 |
| 收消息 | WebSocket JSON | WebSocket protobuf，3 秒内 ACK | WebSocket JSON，ticket 一次性 | WebSocket JSON（op 帧） | HTTP 长轮询 + 游标 |
| 语音 | voice.content 即转写 | 无转写，需下载后转写 | content.recognition（文档未保证一定有） | attachments[].asr_refer_text | voice_item.text |
| 回复凭证 | 无（主动推送需用户先发过消息） | 无 | sessionWebhook（官方示例约 90 分钟，未给定值） | msg_id（5 或 60 分钟，文档矛盾） | context_token（有效期未核实，必须带） |
| 主动发送 | aibot_send_msg，chat_type=1 | im/v1/messages?receive_id_type=open_id | oToMessages/batchSend（需 staffId，机器人发布后才返回） | /v2/users/{openid}/messages（用户可关闭） | 实际上依赖 context_token |
| 官方 Java SDK | 无 | 有，但不引入：长连接与 HTTP 自实现（2026-10-28 决定） | 有（app-stream-client 1.3.12，5.4 MB，内含 shade 的 Netty），但不引入：Stream、扫码与 OpenAPI 自实现（2026-10-29 决定） | 无 | 无 |
| 扫码接口是否公开 | 否（ai/qc/* 为后台接口） | 是（RegisterApp，OAuth device flow） | 否（官方 connector 源码） | 否（混淆 npm 包） | 否（开源客户端协议） |

框架约定：
- 扫码结果的 `ownerId` 可为空；为空时进入“配对码认主”。
- 单聊场景下适配器把 `InboundMessage.chatId` 规范为发送时使用的对端 id（企业微信 userid、飞书 open_id、钉钉 staffId、QQ openid、微信 user id），因此本人的 `senderId == chatId`。
- 发送结果需要区分“窗口过期 / 缺少上下文”和“额度不足”，界面据此提示。
- 同一机器人只允许一个 IDE 进程连接（企业微信被踢、飞书和钉钉随机投递）。

## 1. 企业微信（智能机器人 API 模式长连接）

文档：https://developer.work.weixin.qq.com/document/path/101463 （长连接）、/100719（接收消息）、/101031（被动回复）。官方 Node SDK：WecomTeam/aibot-node-sdk（npm `@wecom/aibot-node-sdk` 1.0.7）。

**连接**
- `wss://openws.work.weixin.qq.com`，无额外 header。
- 所有帧：`{"cmd","headers":{"req_id"},"body"}`；所有响应没有 `cmd`，只有 `headers.req_id` + `errcode`/`errmsg`。
- 订阅：`{"cmd":"aibot_subscribe","headers":{"req_id":"aibot_subscribe_<ms>_<hex8>"},"body":{"bot_id","secret"}}`，响应 errcode=0 表示成功。订阅有频率保护，成功后不要反复订阅。
- 心跳：`{"cmd":"ping","headers":{"req_id":"ping_..."}}`，30 秒一次；连续 2 次未确认则断开重连。认证成功后才开始心跳。
- 重连退避：`min(1s·2^(n-1), 30s)`；认证失败单独计数，最多 5 次。
- 被踢：收到 `{"cmd":"aibot_event_callback",...,"body":{"msgtype":"event","event":{"eventtype":"disconnected_event"}}}` 表示同一 bot 在别处建立了新连接，**不得自动重连**。
- `java.net.http.WebSocket`：`onText(data,last)` 需拼接到 `last=true`；`sendText` 必须串行；Gson 用 lenient 解析（偶有未转义控制字符）。

**收消息** `cmd=aibot_msg_callback`
```json
{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"..","aibotid":"..","chatid":"(仅群聊)","chattype":"single|group","from":{"userid":".."},"msgtype":"text","text":{"content":".."}}}
```
- 单聊时没有 `chatid`，对端 id 用 `from.userid`。
- `voice.content` 为转写文本（仅单聊）；`mixed.msg_item[]`；`image/file` 为 url+aeskey（5 分钟有效，AES-256-CBC，IV=key 前 16 字节，按 32 字节块去 PKCS#7）。
- 已接入（2026-10-27）：`image`、`file`、`video` 与 `mixed` 中的图片。渠道只解析引用，路由器确认本人、非重复、已绑定后才下载；aeskey 为 base64（常缺结尾 `=`）；消息不含文件名，从下载响应的 `Content-Disposition` 取，取不到按文件头识别扩展名；文件、视频上限 100 MB。附件存到 `<项目>/.imux/attachments/<日期>/`，相对路径随文字提交给终端，见 `docs/superpowers/specs/2026-10-27-wecom-inbound-media-design.md`。
- 引用：消息体可带 `quote`（text / image / mixed / voice / file）。只取其中的文字（文本、语音转写、混排中的文字），以 Markdown 引用块 `> …` 放在正文前提交；引用图片、文件不处理。
- 带 `compat_info` 的不是普通用户消息，忽略。
- `msgid` 可能重复推送，用于去重。
- 事件 `aibot_event_callback`：`enter_chat`、`template_card_event`、`feedback_event`、`disconnected_event`。
- 欢迎语：`enter_chat` 是用户当天首次进入单聊时推送，5 秒内用该事件的 `req_id` 发 `aibot_respond_welcome_msg`（`msgtype: text`）。协议层已支持解析与构造回复帧（`WecomProtocol.respondWelcome`），业务上暂不使用：每人每天只推送一次，触发不稳定。

**主动推送**
```json
{"cmd":"aibot_send_msg","headers":{"req_id":"aibot_send_msg_<ms>_<hex8>"},"body":{"chatid":"<userid>","chat_type":1,"msgtype":"markdown","markdown":{"content":".."}}}
```
- 必须显式 `chat_type:1`（不填优先按群聊解析）。
- content ≤ 20480 UTF-8 字节。
- 前提：用户在该会话给机器人发过消息。
- 回复 + 主动推送合计每会话 30 条/分钟、1000 条/小时；超频 errcode 846607（社区实现注释，未核实）。
- 被动回复 `aibot_respond_msg` 需带回调的 req_id，24 小时内有效；流式回复建议 ≤ 5.5 分钟（官方 10 分钟、实测约 6 分钟）。

**扫码接入**（非公开接口，可能变化）
- `GET https://work.weixin.qq.com/ai/qc/generate?source=imux&plat=<1 macOS|2 Windows|3 Linux|0 其他>` → `{"data":{"scode","auth_url"}}`；二维码内容为 `auth_url`。`source` 非必填，实测自定义值可用。
- `GET https://work.weixin.qq.com/ai/qc/query_result?scode=<scode>`，每 3 秒一次、5 分钟超时；`data.status=="success"` 时 `data.bot_info.botid`（或 `bot_id`）与 `data.bot_info.secret`；其他状态都继续等待。
- 不返回扫码人；本人在第一条单聊消息的 `from.userid` 中取得。扫码倾向于每次新建机器人（未核实能否选已有）。
- 机器人创建者不是超级管理员时 userid 为加密值，能否直接用于主动推送未核实。

## 2. 飞书

**扫码**（官方 Java SDK `scene/registration/RegisterApp.java`，OAuth device flow）
- `POST https://accounts.feishu.cn/oauth/v1/app/registration`（Lark：accounts.larksuite.com），`application/x-www-form-urlencoded`，无鉴权；4xx 也返回 JSON。
- begin：`action=begin&archetype=PersonalAgent&auth_method=client_secret&request_user_info=open_id` → `device_code, verification_uri_complete, user_code, interval(默认5), expire_in(默认600)`。
- 二维码 = `verification_uri_complete` 追加 `from=sdk&source=java-sdk/<自定义>&tp=sdk`，可选 `name/desc/avatar`、`addons`（gzip JSON 再 base64url，可追加 scopes/events，如 `im.message.receive_v1`、`im:message:send_as_bot`、`im:message.p2p_msg:readonly`）、`createOnly=true`、`clientID=cli_xxx`（更新已有应用）。
- poll：`action=poll&device_code=..` → `client_id, client_secret, user_info{open_id, tenant_brand}, error`；`authorization_pending` 继续，`slow_down` 间隔加 5 秒，其余终止；`tenant_brand=="lark"` 时切到 Lark 域名重新 poll。

**长连接**
- bootstrap：`POST https://open.feishu.cn/callback/ws/endpoint` `{"AppID","AppSecret","ClientAssertion":""}` → `data{URL, ClientConfig{ReconnectCount, ReconnectInterval, ReconnectNonce, PingInterval}}`。
- 帧为 protobuf `pbbp2.Frame`（SeqID=1, LogID=2, service=3, method=4[0 控制/1 数据], headers=5, payload_encoding=6, payload_type=7, payload=8, LogIDNew=9）；心跳为控制帧 header `type=ping`，默认 120 秒；分片按 header `sum/seq` 合并。
- 收到数据帧后须在 **3 秒内** 回写同帧（payload `{"code":200}`），处理放到异步队列。
- 每应用最多 50 条连接，事件随机投给其中一个；握手 514+autherrcode 1000040350 或 403 时不重连。
- 实现：自实现，不引入 SDK。帧格式以官方 Python SDK `lark-oapi` 1.7.5 内嵌的 `pbbp2.proto` 为准，测试样本字节由该 SDK 生成。收帧对齐 Node SDK：校验 `sum/seq`，拼齐后按序处理，成功回 200、处理异常回 500，`biz_rt` 记录耗时；未拼齐不确认，未完成缓存保留 10 秒。官方 SDK 只靠读错误发现断线，我们另加"3 个心跳周期无任何帧即重连"。

**收发**
- 事件 `im.message.receive_v1`：`sender.sender_id.open_id`、`sender.sender_type`(user/bot)、`message.message_id/chat_type(p2p)/message_type/content`（JSON 字符串，文本为 `{"text":..}`）；用 `message_id` 去重。
- 发送：`POST /open-apis/im/v1/messages?receive_id_type=open_id`，`Authorization: Bearer <tenant_access_token>`（`/open-apis/auth/v3/tenant_access_token/internal`），body `{"receive_id","msg_type":"text","content":"{\"text\":..}","uuid"}`；同一用户 5 QPS；文本请求体 ≤ 150 KB。

**本轮核实（2026-10-28）**
- 发 Markdown：`msg_type=post`，`content={"zh_cn":{"content":[[{"tag":"md","text":..}]]}}`；`md` 支持 CommonMark 0.31 + GFM；富文本请求体 ≤ 30 KB。
- 收消息类型：text、post（接收时 `md` 会被转成其他标签）、image（`image_key`）、file（`file_key`、`file_name`）、media（视频）、audio（`file_key`、`duration`，**无转写**）。
- 下载附件：`GET /open-apis/im/v1/messages/{message_id}/resources/{file_key}?type=image|file`，音频与视频用 `file`；100 MB 以下整包下载，更大需 Range 分片（本版不做）；内容不加密。
- 引用：事件只有 `parent_id`，文字需 `GET /open-apis/im/v1/messages/{message_id}` 获取。
- 语音：消息是 Opus（Ogg 封装）；识别接口 `speech_to_text/v1/speech/file_recognize` 只收 16k pcm、适合 60 秒以内、权限 `speech_to_text:speech`。插件用 Concentus 解码后整段一次识别（不切段；60 秒以上也提交，仅超过 10 分钟作为内存保护不识别），失败时退回音频附件。
- 令牌：`tenant_access_token` 最长 2 小时；剩余不足 30 分钟时再取会得到新令牌。
- 错误码：令牌无效 99991661 / 99991663 / 99991665 / 20005 / 20013；应用不可用 10014 / 10015 / 11207 / 11209 / 99991662 / 99991673 / 20009；限频 99991400 / 1000004 / 1000005；额度 99991403；权限 99991672 / 99991676；不在可用范围 230013；未启用机器人 230006。

## 3. 钉钉

第二轮核实：2026-10-29。官方文档读自钉钉开放平台（含其 OSS 文档原文件）；官方连接器读 npm `@dingtalk-real-ai/dingtalk-connector` 0.8.27（2026-10-20），SDK 读 Maven `app-stream-client`（正式版 1.3.12，另有 1.3.14-beta.1）与 PyPI `dingtalk-stream` 0.24.3。下文“官方”指官方文档原文，“连接器”指官方连接器源码（行为参考，不是平台契约）。

**扫码**（连接器 `src/device-auth.ts`，非公开接口；init/begin 已实测，poll 只测过无效 device_code）
- 三个接口都是 `POST https://oapi.dingtalk.com/app/registration/{init|begin|poll}`，JSON 请求体；响应都带 `errcode`、`errmsg`，`errcode=0` 才成功。
- `init` `{"source"}` → `nonce`、`expires_in=300`。
- `begin` `{"nonce"}` → `device_code`、`user_code`、`verification_uri`、`verification_uri_complete`（二维码内容）、`expires_in=7200`、`interval=2`；nonce 失效返回 `errcode=-1`。
- `poll` `{"device_code"}` → `status` 为 WAITING / SUCCESS / FAIL / EXPIRED，成功时 `client_id`、`client_secret`，失败时 `fail_reason`。FAIL 不走 HTTP 错误（无效 device_code 也是 `errcode=0, status=FAIL`）。连接器对 FAIL/EXPIRED 和网络错误有 2 分钟重试窗口。
- **不返回任何用户身份**（无 userId、staffId、unionId、corpId）→ 配对码认主。连接器本身也不认主（默认 `dmPolicy=open`）。
- `source`：连接器默认 `DING_DWS_CLAW`（可用 `DINGTALK_REGISTRATION_SOURCE` 覆盖）。2026-10-29 实测：`DING_DWS_CLAW`、`imux`、空值三者 init/begin 都成功，但只有 `DING_DWS_CLAW` 出现在二维码链接（`https://open-dev.dingtalk.com/openapp/registration/openClaw?user_code=..&source=DING_DWS_CLAW`）里，其余被去掉。服务端只认已知取值；不带 source 扫码后能否正常建应用、权限是否相同，未核实。没有找到第三方申请 source 的文档。
- 扫码后需登录，登录页文案为“仅支持管理员登录……可直接创建新组织”（推断：扫码人须为组织管理员，建的是该组织的企业内部应用）；授权页可新建或选择已有机器人（连接器提示语）。是否自动开通机器人、Stream、发消息权限，是否已发布，未核实。
- `robotCode`：连接器发消息、下载附件都用 `clientId`（`src/services/messaging.ts`、`media.ts`）。

**Stream 长连接**（官方《Stream模式协议接入说明》、开发者百科）
- `POST https://api.dingtalk.com/v1.0/gateway/connections/open` `{clientId, clientSecret, subscriptions:[{type:"CALLBACK",topic:"/v1.0/im/bot/messages/get"}], ua?, localIp?}`，请求头带 `Accept: application/json`（否则可能返回 XML）→ `{endpoint, ticket}`；`ua`、`localIp` 非必填；ticket 90 秒内一次性。
- `endpoint` 已含 `/connect`，直接 `{endpoint}?ticket=<urlencode>` 建 WebSocket。
- 推送帧 `{specVersion,type:SYSTEM|EVENT|CALLBACK,headers{topic,messageId,contentType,time,appId},data:"<json>"}`；ACK `{"code":200,"headers":{"messageId","contentType":"application/json"},"message":"OK","data":"{\"response\": null}"}`。
- 系统帧：`ping` 原样回传 data（opaque）与 messageId；`disconnect` 后不再推送新消息，10 秒后断开，应立即重新取 ticket 建新连接。
- 同一应用多条连接时，每条消息随机推给其中一条（百科 FAQ）；连接数上限、是否互踢未查到。
- 重推：百科说机器人消息不因超时重推；连接器发布说明记录过处理超 60 秒后被重推，重推时 `headers.messageId` 变、`data.msgId` 不变。按可能重推设计：收到即 ACK，用 `data.msgId` 去重。
- SDK 心跳参考：Java 空闲 60 秒发 WS ping、5 秒无 pong 断开；连接器每 10 秒应用层 ping。

**机器人消息**（官方《消息发送与接收类型》2026-05-15）
- 字段：`msgId`（加密）、`conversationType`（字符串 `"1"` 单聊、`"2"` 群聊）、`conversationId`、`senderId`（加密）、`senderStaffId`、`senderUnionId`、`senderNick`、`senderCorpId`、`isAdmin`、`chatbotUserId`、`robotCode`、`sessionWebhook`、`sessionWebhookExpiredTime`（毫秒，官方示例比 `createAt` 晚 90 分钟，未给定值）、`createAt`。
- `senderStaffId` 官方原文：“如果发送人非机器人所在企业成员，则此字段为空……**机器人发布上线后生效。否则不会返回。**”
- 类型：`text.content`；`richText`（`content.richText[]`，元素为 `{text}` 或 `{type:"picture",downloadCode}`）；`picture`（`content.downloadCode`）；`audio`（`content.downloadCode, recognition, duration`）；`video`；`file`（`content.downloadCode, fileName`）；不支持的类型为 `unknownMsgType`。单聊可收语音、视频、文件；群里 @ 机器人收不到这三种。
- 语音 `recognition` 文档未保证一定有（连接器缺失时显示“[语音消息]”）。
- 引用：`text.isReplyMsg` + `text.repliedMsg{msgType,content,msgId,senderId}`（richText 在 `content` 下）；**只见于连接器源码，官方文档未写**。
- 附件：`POST https://api.dingtalk.com/v1.0/robot/messageFiles/download`，头 `x-acs-dingtalk-access-token`，`{downloadCode, robotCode}` → `downloadUrl`（OSS 签名地址，有效期未公布）；文件大小上限未公布；downloadCode 会过期。

**发送**
- token：`POST https://api.dingtalk.com/v1.0/oauth2/accessToken` `{appKey, appSecret}` → `accessToken, expireIn 7200`；有效期内重复获取返回相同结果并续期；不计入 API 额度（附录 A）。
- 主动：`POST https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend`，头 `x-acs-dingtalk-access-token`，`{"robotCode","userIds":[userId],"msgKey","msgParam":"<JSON 字符串>"}`。官方：“本接口只支持企业内部应用机器人调用”，`userIds` 每次最多 20 个（错误码说明残留 100），权限“企业内机器人发送消息权限”，第三方个人应用暂不支持。响应 `processQueryKey`、`invalidStaffIdList`、`flowControlledStaffIdList`，HTTP 200 也要检查后两个列表。加密 senderId、unionId 不能当 userId（无依据）。
- 相关错误码：`invalidParameter.userId.empty`（缺少 staffId）、`staffId.notExisted`、`chatbotId.notAllow.sendOTO`（不允许主动发单聊，检查机器人是否启用）、`robot.oto.notExist`（没有有效的机器人单聊会话）、`session.expired`、`bot.forbidden.sendMessage`、`send.too.fast`、`msgParam.tooLong`。是否要求用户先发过消息、多久后不能主动推送，官方未写。
- 被动：POST `sessionWebhook`，`{"msgtype":"text|markdown",...}`；只适合刚收到消息后的即时回复；官方消息类型页另写“webhook 方式只支持在群聊会话”，与连接器单聊也用它相矛盾。
- Markdown：`msgKey=sampleMarkdown`，`msgParam={"title","text"}`。官方支持子集：标题、引用、加粗、斜体、链接、图片、无序列表、有序列表；换行建议 `\n` 前后各加两个空格。**代码块、表格不在清单内。**单聊长度上限未公布（群发 `msgParam` 15000 字节）。连接器优先用 AI 卡片（需模板和权限），失败退回普通消息。
- 其他：撤回 `POST /v1.0/robot/otoMessages/batchRecall`（24 小时内）；出站图片、文件、语音、视频需先上传媒体（`sampleImageMsg`、`sampleFile`、`sampleAudio`、`sampleVideo`）。

## 4. QQ 官方机器人（不纳入实现，仅存档）

**扫码**（`@tencent-connect/qqbot-connector` 1.2.0，混淆、UNLICENSED，非公开接口）
- 本地生成 32 字节 AES key，`POST https://q.qq.com/lite/create_bind_task` `{"key":"<base64>"}` → `retcode==0, data.task_id`。
- 二维码：`https://q.qq.com/qqbot/openclaw/connect.html?task_id=<id>&source=<src>&_wv=2`（自定义 source 未核实）。
- `POST https://q.qq.com/lite/poll_bind_result` `{"task_id"}`，每 2 秒：`status 0 NONE/1 PENDING/2 COMPLETED/3 EXPIRED`（过期重建任务），完成时 `bot_appid, bot_encrypt_secret, user_openid`。
- 解密：`buf=base64(bot_encrypt_secret)`，nonce=前 12 字节，tag=后 16 字节，AES-256-GCM（Java：`AES/GCM/NoPadding`、`GCMParameterSpec(128, nonce)`、`doFinal(ciphertext‖tag)`），UTF-8 即 AppSecret。

**网关**
- token：`POST https://bots.qq.com/app/getAppAccessToken` `{"appId","clientSecret"}` → `access_token, expires_in(7200)`；鉴权头 `Authorization: QQBot <token>`；API 主机 `https://api.sgroup.qq.com`（文档另写 api.bot.qq.com，两者并存）。
- `GET /gateway` → `url`；收 `op:10 Hello(heartbeat_interval)` → 发 `op:2 Identify {token:"QQBot <tok>", intents: 1<<25, shard:[0,1]}` 或 `op:6 Resume {token, session_id, seq}`；心跳 `op:1 {d:lastSeq}`；`READY` 保存 session_id；`op:7` 重连，`op:9 d=false` 重新 Identify；关闭码 4914/4915 停止重连。
- `C2C_MESSAGE_CREATE`：`id, author.user_openid, content, attachments[].asr_refer_text, message_scene.ext(msg_idx)`。

**发送**
- `POST /v2/users/{openid}/messages` `{content, msg_type:0, msg_id?, msg_seq?}`：带 msg_id 为被动回复（5 分钟或 60 分钟×4 次，文档矛盾），不带为主动消息（同用户 20 条/分钟、1000 条/日，用户可关闭；错误码 40034100 频控、40054013 拒收、304103/40034005 msg_id 过期）。

## 5. 普通微信（Tencent iLink，openclaw-weixin 2.4.10）

协议仅来自 MIT 开源客户端与其 `docs/protocol_zh_CN.md`，没有服务端契约；`iLink-App-Id: bot`、`bot_type=3`、`iLink-App-ClientVersion`（版本按 0x00MMNNPP 编码，2.4.10 → 132106）为客户端身份，应做成可配置。

**扫码**
- `POST https://ilinkai.weixin.qq.com/ilink/bot/get_bot_qrcode?bot_type=3` body `{"local_token_list":[本地已有 bot_token，最多 10 个]}`，header `Content-Type: application/json`、`AuthorizationType: ilink_bot_token`、`X-WECHAT-UIN`（随机 uint32 十进制再 base64）、`iLink-App-Id`、`iLink-App-ClientVersion` → `qrcode, qrcode_img_content`。
- `GET {host}/ilink/bot/get_qrcode_status?qrcode=<enc>[&verify_code=<enc>]`，35 秒长轮询，间隔 1 秒，单二维码 5 分钟：`wait`；`scaned`；`need_verifycode`（提示输入手机显示的数字）；`verify_code_blocked`；`expired`（最多刷新 3 次）；`scaned_but_redirect`（切到 `https://<redirect_host>`）；`binded_redirect`（已绑定本端，沿用本地凭据）；`confirmed` → `bot_token, ilink_bot_id, baseurl, ilink_user_id`。
- 同一微信号重新扫码会替换旧绑定：每个微信号只有一个入口。

**收发**
- `POST {baseUrl}/ilink/bot/getupdates`，header 加 `Authorization: Bearer <bot_token>`，body `{"get_updates_buf":"<游标>","base_info":{"channel_version","bot_agent"}}`；只在响应游标非空时替换并持久化；超时按空结果；`longpolling_timeout_ms` 指定下一次超时。
- 错误 `-14`：token 失效，官方客户端暂停 1 小时，需提示重新扫码；其他错误 2 秒重试，连续 3 次后退避 30 秒。
- 消息：`message_id(uint64，须按字符串解析), from_user_id, message_type(1 用户), item_list[{type:1 text_item.text | type:3 voice_item.text}], context_token`。
- 发送：`POST {baseUrl}/ilink/bot/sendmessage` `{"msg":{"from_user_id":"","to_user_id","client_id","message_type":2,"message_state":2,"context_token","item_list":[{"type":1,"text_item":{"text"}}]},"base_info":{..}}`；`context_token` 取该用户最近一条入站消息，必须带回；有效期与发送额度未核实。
- 启停时可调 `/ilink/bot/msg/notifystart`、`notifystop`。

## 6. 待真实账号确认

- 企业微信：流式回复实际时长；加密 userid 能否用于主动推送；扫码能否选已有机器人。
- 飞书：扫码返回的 open_id 与消息发送人是否一致；PersonalAgent 默认模板是否已开启长连接、单聊收发与语音识别（speech_to_text:speech）权限；语音文件是否为标准 Ogg Opus；pcm 字节序；语音识别是否计入月度额度。
- 钉钉（按重要性）：扫码是否要求组织管理员、不带 `DING_DWS_CLAW` 能否正常建应用；建出的应用是否已发布、是否开通 Stream 与发消息权限；单聊消息有无 `senderStaffId`、`batchSend` 能否发出、`robotCode` 是否等于 `client_id`；隔数小时后主动推送是否仍成功；后台“资源管理”显示的版本与额度、收消息是否计入 Webhook&Stream 额度；代码块和表格的实际显示；语音是否总带 `recognition`；引用字段实际形态；不 ACK 是否重推；`downloadUrl` 有效期；两个客户端同时连接的表现。
- QQ：msg_id 有效期；重复扫码是否复用机器人；扫码机器人是否为沙箱。
- 普通微信：context_token 有效期；非官方客户端身份是否被拒；ilink_user_id 与 from_user_id 是否一致。
