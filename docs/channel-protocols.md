# 扫码渠道的协议参考

**实现范围：企业微信、飞书、钉钉、普通微信。QQ 已决定不纳入（2026-10-27），第 4 节仅保留为调研记录。**

核实日期：2026-10-26。来源为官方文档、官方 SDK 或客户端源码，以及 DeepTutor、Hermes Agent 等参考实现。标“未核实”的需要真实账号联调确认。本文是各渠道实现计划的事实依据，与 [渠道限制](channel-limits.md)、[终端通讯设计](terminal-channel-bridge-feasibility.md) 配合阅读。

## 0. 对框架接口的影响

| | 企业微信 | 飞书 | 钉钉 | QQ | 普通微信 |
| --- | --- | --- | --- | --- | --- |
| 扫码返回本人身份 | 否 → 配对码 | 是（open_id） | 否 → 配对码 | 是（user_openid） | 是（ilink_user_id） |
| 扫码额外步骤 | — | — | — | 首次在手机上取名创建 | 可能要求输入手机显示的验证码；二维码可刷新 3 次 |
| 收消息 | WebSocket JSON | WebSocket protobuf，3 秒内 ACK | WebSocket JSON，ticket 一次性 | WebSocket JSON（op 帧） | HTTP 长轮询 + 游标 |
| 语音 | voice.content 即转写 | 无转写，需下载后转写 | content.recognition | attachments[].asr_refer_text | voice_item.text |
| 回复凭证 | 无（主动推送需用户先发过消息） | 无 | sessionWebhook（约 90 分钟，未核实） | msg_id（5 或 60 分钟，文档矛盾） | context_token（有效期未核实，必须带） |
| 主动发送 | aibot_send_msg，chat_type=1 | im/v1/messages?receive_id_type=open_id | oToMessages/batchSend（需 staffId） | /v2/users/{openid}/messages（用户可关闭） | 实际上依赖 context_token |
| 官方 Java SDK | 无 | 有（oapi-sdk 2.8.7，21 MB，依赖可能冲突） | 有（app-stream-client 1.3.12，5.4 MB，已 shade） | 无 | 无 |
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
- 带 `compat_info` 的不是普通用户消息，忽略。
- `msgid` 可能重复推送，用于去重。
- 事件 `aibot_event_callback`：`enter_chat`、`template_card_event`、`feedback_event`、`disconnected_event`。

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
- 选型：自实现（protobuf 9 个字段）或引入 SDK（21 MB，guava/httpclient/gson 2.9 等可能与平台冲突，`ws.Client` 线程池不随 close 释放）。

**收发**
- 事件 `im.message.receive_v1`：`sender.sender_id.open_id`、`sender.sender_type`(user/bot)、`message.message_id/chat_type(p2p)/message_type/content`（JSON 字符串，文本为 `{"text":..}`）；用 `message_id` 去重。
- 发送：`POST /open-apis/im/v1/messages?receive_id_type=open_id`，`Authorization: Bearer <tenant_access_token>`（`/open-apis/auth/v3/tenant_access_token/internal`），body `{"receive_id","msg_type":"text","content":"{\"text\":..}","uuid"}`；同一用户 5 QPS；文本请求体 ≤ 150 KB。

## 3. 钉钉

**扫码**（官方 connector `device-auth.ts`，非公开接口）
- `POST https://oapi.dingtalk.com/app/registration/init` `{"source":"DING_DWS_CLAW"}` → `nonce`；自定义 source 是否被接受未核实。
- `POST /app/registration/begin` `{"nonce"}` → `device_code, verification_uri_complete（二维码）, expires_in, interval`。
- `POST /app/registration/poll` `{"device_code"}` → `status WAITING|SUCCESS|FAIL|EXPIRED, client_id, client_secret`；不返回用户身份。`robotCode` 按惯例等于 `client_id`（未核实）。

**Stream 长连接**（官方 Java SDK `com.dingtalk.open:app-stream-client:1.3.12`，MIT，已 shade）
- `POST https://api.dingtalk.com/v1.0/gateway/connections/open` `{clientId, clientSecret, subscriptions:[{type:"CALLBACK",topic:"/v1.0/im/bot/messages/get"}], ua, localIp}` → `{endpoint, ticket}`；ticket 90 秒内一次性。
- `GET {endpoint}?ticket=..` 建 WebSocket；推送帧 `{specVersion,type:SYSTEM|EVENT|CALLBACK,headers{topic,messageId,...},data:"<json>"}`；ACK `{"code":200,"headers":{"messageId","contentType":"application/json"},"message":"OK","data":"{\"response\": null}"}`；系统消息 `ping` 原样回传 opaque，`disconnect` 后 10 秒断开需重连。
- 机器人消息字段：`msgId, senderStaffId（机器人发布上线后才有）, senderId（加密）, conversationType("1" 单聊), conversationId, sessionWebhook, sessionWebhookExpiredTime, msgtype, text.content, content.recognition`。

**发送**
- token：`POST https://api.dingtalk.com/v1.0/oauth2/accessToken` `{appKey, appSecret}` → `accessToken, expireIn 7200`。
- 被动：POST `sessionWebhook`，`{"msgtype":"text","text":{"content"}}`。
- 主动：`POST https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend`，header `x-acs-dingtalk-access-token`，`{"robotCode","userIds":[staffId],"msgKey":"sampleText","msgParam":"{\"content\":..}"}`；userIds 必须是 staffId。

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
- 飞书：PersonalAgent 默认模板是否已开启长连接与 p2p 收消息权限。
- 钉钉：自定义 source 是否被接受；扫码创建的应用是否已发布机器人（否则没有 senderStaffId）。
- QQ：msg_id 有效期；重复扫码是否复用机器人；扫码机器人是否为沙箱。
- 普通微信：context_token 有效期；非官方客户端身份是否被拒；ilink_user_id 与 from_user_id 是否一致。
