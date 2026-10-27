# 企业微信入站媒体设计

日期：2026-10-27
范围：手机通过企业微信发来的图片、文件、视频（含图文混排中的图片）交给终端里的 Agent。
不在范围：出站媒体（Agent 产物发到手机）、流式被动回复、欢迎语与反馈事件。

## 1. 背景与目标

现状：`WecomProtocol` 只取文本（`text`、语音转写、混排里的文字），图片、文件、视频消息在 `WecomConnection.deliver` 中因 `text == null` 被静默丢弃，手机端没有任何反馈。

目标：本人在手机上发来的附件下载、解密后保存到绑定会话所在项目内，把相对路径随文字一起提交给终端，Agent（Claude、Codex、Pi）可直接读取。

官方协议事实（文档 100719、官方 Node SDK `@wecom/aibot-node-sdk`）：

- 媒体消息体只含下载 `url` 与 `aeskey`，**不含文件名和大小**；url 五分钟内有效，下载内容已加密。
- 加密：AES-256-CBC，IV 取 aeskey 前 16 字节，PKCS#7 填充到 **32 字节**的倍数。
- 文件、视频上限 100 MB。
- 官方 SDK 下载时从响应头 `Content-Disposition` 取文件名。

## 2. 架构与数据流

```
企业微信 ──image/file/video/mixed──▶ WecomConnection
   │ 只解析不下载：每个附件生成 InboundAttachment(kind, fetch)
   ▼
InboundMessage(..., text, attachments)
   ▼
BridgeRouter.onInbound
   │ ① 校验本人 → 去重 → 查绑定；任一步未通过都不调用 fetch
   │ ② 提交锁之外：AttachmentStore.place(target, attachment) → 调用 fetch 下载解密，
   │    移入 <项目>/.imux/attachments/<日期>/，返回相对路径
   ▼
拼装提交文字 → 加提交锁、复核绑定 → TerminalSessionPort.submit
```

分工：

| 单元 | 位置 | 职责 | 依赖 |
|---|---|---|---|
| `InboundAttachment` | `bridge/core/BridgeModels.kt` | 附件类型 + `suspend fun fetch(maxBytes): FetchedFile`（临时文件 + 建议文件名） | 无 |
| `AttachmentStore` 端口 | `bridge/core/BridgePorts.kt` | `suspend fun place(target, attachment): PlaceResult`（相对路径或失败原因） | 无 |
| 文件下载 `HttpFileDownload` | `bridge/channel/Transports.kt`（新增） | 流式下载到临时文件、60 秒超时、边写边计数、返回响应头文件名；JDK `HttpClient` 不显式设代理，走默认 `ProxySelector`（IDE 代理设置） | JDK |
| 企业微信取回实现 | `bridge/channel/wecom/WecomMedia.kt` | 调用下载、AES 解密、`Content-Disposition` 解析 | `HttpFileDownload` |
| `ProjectAttachmentStore` | `bridge/terminal/ProjectAttachmentStore.kt` | 落盘、命名、编号、`.git/info/exclude`、7 天清理 | JDK 文件 API |
| `AttachmentNames` | `bridge/core/AttachmentNames.kt` | 文件名清理、按文件头识别扩展名、加随机后缀（纯函数） | 无 |
| `BridgeRouter` | 已有 | 决定是否、何时取回；拼装提交文字 | 以上端口 |

`core` 中新增的类型不依赖 IntelliJ API 与 `AgentType`，满足现有 `BridgeIsolationSourceTest`。

## 3. 规则

### 3.1 协议解析

- `image`、`file`、`video`：各生成一个附件；`mixed.msg_item` 中的文字按原顺序拼接，每个 `image` 子项生成一个附件。
- 带 `compat_info` 的消息、群聊消息仍然忽略（与现有一致）。
- 消息既无文字也无附件时仍然丢弃。

### 3.2 取回与解密

- 只在路由器确认本人、非重复、已绑定后调用 `fetch`。
- 下载用新增的 `HttpFileDownload`（与现有 `JdkHttpJsonGet` 一样走默认 `ProxySelector`，即 IDE 代理设置），超时 60 秒；累计超过 100 MB 立即中止并删除已写入部分，返回“过大”。
- 解密用 `AES/CBC/NoPadding`，手工去除填充：末字节值 n 须满足 1 ≤ n ≤ 32 且末 n 字节都等于 n，否则视为解密失败。
- 文件名优先取 `Content-Disposition`（同时支持 `filename*=UTF-8''…` 与 `filename="…"`）。

### 3.3 命名与位置

- 目录：`<项目根>/.imux/attachments/YYYY-MM-DD/`（本地日期）。
- 文件名：
  1. 响应头文件名：去掉路径分隔符与控制字符，限制 120 字符；
  2. 否则 `<类型>-<HHmmss>.<扩展名>`，扩展名按文件头识别：png、jpg、gif、webp、bmp、pdf、zip、mp4（含 mov 的 `ftyp`）；
  3. 仍无法识别用 `.bin`。
- 每个文件名都在扩展名前加 4 位随机后缀（小写字母与数字），例如 `报告-k3f9.pdf`；用“不存在才创建”原子占名，撞名换后缀，再原子改名落盘，同名附件并发保存互不覆盖。
- 提交给 Agent 的是相对项目根的路径，分隔符统一为 `/`。

### 3.4 提交格式

```
<用户文字>

[附件] `.imux/attachments/2026-10-27/image-153012-a7k2.png`
[附件] `.imux/attachments/2026-10-27/报告-k3f9.pdf`
```

- 只有附件：`请查看这个附件：<路径>`；多个时 `请查看这些附件：` 加路径列表。
- 前缀与提示文案走资源包，跟随插件语言设置（10 种语言）。
- 收到即提交，不等待后续文字。

### 3.5 git 与清理

- 项目是 git 仓库且 `.git/info/exclude` 中没有 `.imux/` 时追加一行；不是 git 仓库则跳过。只改本地 exclude，不改 `.gitignore`。
- 每次保存附件时删除目录名日期早于 7 天前的日期目录；目录名不是日期格式的不动。

## 4. 错误处理

| 情况 | 处理 |
|---|---|
| 非本人、重复消息 | 静默忽略，不下载 |
| 未绑定会话 | 回复“未连接会话”（现有文案），不下载 |
| 下载失败、链接失效、解密失败 | 回复“附件接收失败，请重新发送”；同条消息里的文字照常提交 |
| 超过 100 MB | 中止下载并清理，回复“附件过大（上限 100 MB）”；文字照常提交 |
| 落盘失败（磁盘、权限） | 同下载失败处理，记一条 WARN 日志（不含文件内容与对端 id） |
| 部分附件失败 | 成功的照常附上路径；失败的统一回复一次 |

新增提示文案：附件接收失败、附件过大、只有附件时的提交提示（单个、多个）、`[附件]` 前缀，共 10 种语言。

## 5. 测试

| 层 | 内容 |
|---|---|
| 协议解析 | image、file、video、mixed 的 url 与 aeskey；只有附件的消息不再被丢弃；compat_info 与群聊仍忽略 |
| 解密 | 测试内按 32 字节 PKCS#7 加密后能解回原文；非法填充报错 |
| 命名（纯函数） | 两种 `Content-Disposition`；危险文件名清理；文件头识别；随机后缀；同名并发保存 |
| 路由器 | 非本人、重复、未绑定时 fetch 调用次数为 0；成功时提交文字格式；部分失败与全部失败的回复与文字提交；过大回复 |
| 落盘 | 临时项目目录中：日期目录、exclude 只追加一次、非 git 跳过、7 天清理、编号 |
| 真机 | 手机分别发图片、文件、视频、图文混排，Agent 能读取 |
