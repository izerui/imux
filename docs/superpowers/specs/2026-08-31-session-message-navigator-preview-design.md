# 会话内消息导航：悬停预览卡片

## 背景

`SessionMessageNavigator` 已经在终端右侧画了一条刻度轨道，刻度对应 transcript 里的用户消息，点击滚动过去，悬停出一个 tooltip。实机看下来问题不在醒目程度，而在**刻度不携带语义**：几个灰点，看不出代表什么、是第几轮、聊的什么，得先猜到它能悬停才拿得到信息。

参照 t3 code 的做法改成：刻度用短横线，悬停时弹出一张卡片，卡片里是**一问一答**——用户消息作标题，助手回复的开头作正文。

为什么带上助手回复：只列提问，用户得靠"自己当时怎么问"来回忆；带上回答，用户是靠"那次聊出了什么"来定位。终端里往回滚特别费劲，这个信息差是这次改动的主要价值。数据是现成的——`parseTranscriptMessage` 本来就同时解析 user 和 assistant，当初为导航只过滤了 user。

## 范围

做：刻度改横线、当前轮次强调、悬停预览卡片、transcript 配对提取。

不做：快捷键唤起的可搜索跳转弹窗。它解决的是"几十轮里搜关键词"，与本次要解决的"这个刻度是什么"是两个问题，等真的翻不过来了再补。

## 架构

三层，边界与现状一致，只有数据层的返回类型和视图层的绘制/悬停行为变化。

### 数据层 `SessionTitleRegenerator.kt`

`recentUserMessages(session): List<String>` 换成：

```kotlin
internal data class SessionExchange(
    val userText: String,
    val assistantReply: String,
)

internal fun recentExchanges(
    session: AgentSession,
    maxExchanges: Int = MAX_NAVIGATOR_MESSAGES,
): List<SessionExchange>
```

仍然走 `scanTail(initialTailBytes = 4MB, maxTailBytes = 4MB)` 读尾部、`parseTranscriptMessage` 解析。配对规则：

- 顺序扫描消息序列，每遇到一条 user 开一个 exchange；
- 其后**第一条** assistant 作为该 exchange 的回复，再往后的 assistant 忽略（一轮里 CLI 可能连发多条）；
- 下一条 user 到来前没有任何 assistant，回复为空串——最后一轮还在生成时就是这个状态，卡片只显示提问。

`conversationExcerpt`（生成标题用）继续走 `sessionTranscriptMessages`，不受影响。

### 定位层 `SessionMessageNavigator.kt`

`locateUserMessageAnchors` 的匹配算法不动——空白归一化后按对话顺序前缀匹配，保留归一化字符到原文 offset 的映射，匹配不到就跳过。只是入参从 `List<String>` 变成 `List<SessionExchange>`，`UserMessageAnchor` 的预览字段拆成两个：

```kotlin
internal data class UserMessageAnchor(
    val offset: Int,
    val userPreview: String,
    val replyPreview: String,
)
```

不引入轮次序号。`recentExchanges` 只读 transcript 尾部窗口（200 条 / 4MB 封顶），超长会话里序号不等于绝对轮次，标出来反而误导；而卡片带了内容之后，用户是靠内容认路的，编号没有额外价值。

截断长度：`userPreview` 取 60 字符（卡片两行），`replyPreview` 取 160 字符（四行），超出加省略号。卡片宽度固定 `JBUI.scale(360)`，文本用 HTML 折行。

### 视图层 `NavigatorRail`

绘制：

- 刻度画成统一长度的短横线。t3 的图里横线长短不一，判断是装饰节奏；在终端里按消息长度映射会让人误读成"重要程度"，所以统一。
- 当前视口内的那条加长并换高对比色，其余用次要色。这条规则和现在一致，只是形状从圆点变横线。

悬停预览：

- 命中某条刻度（沿用现有 `nearestAnchor` 的 `MARK_HIT_RADIUS` 判定）时弹出卡片；
- 卡片走 `JBPopupFactory.createComponentPopupBuilder`，不自绘——圆角、阴影、边界翻转、主题与缩放适配都由平台处理；
- 内容两段：`userPreview` 用 `UIUtil.getLabelForeground()` 加粗；`replyPreview` 用 `UIUtil.getContextHelpForeground()`；
- 定位在刻度**左侧**（`RelativePoint`），向左展开。轨道在右侧不动。悬停期间会盖住终端输出，比放左侧盖住每行开头要好，移开即消失。

生命周期：

- 同一条 anchor 上重复 `mouseMoved` 不重建 popup，只有命中的 anchor 变化才重建——否则鼠标微动就闪烁；
- popup 用 `setRequestFocus(false)`，不能抢终端焦点；
- 移出组件、`unbind()`、`dispose()` 三处都要关闭当前 popup。

## 数据流

```
SessionMonitor 变更 / Document 变更
  → scheduleRefresh()（250ms 防抖）
  → [IO] recentExchanges(session)          读 transcript 尾部 4MB
  → [read action] document.text 快照
  → [IO] locateUserMessageAnchors()        归一化匹配
  → [EDT] 校验 editor 未换、modificationStamp 未变 → applyAnchors()
```

与现状一致，只有 `recentExchanges` 替换了 `recentUserMessages`。EDT 上仍然只做快照读取和最终装配。

## 错误处理

- transcript 读不到、解析失败、匹配为零 → anchors 为空，轨道 `isVisible = false`，不画也不响应鼠标。这是现有行为，保持。
- 匹配到但助手回复为空 → 卡片只渲染提问段，不留空白占位。
- 刷新期间 editor 被换掉或文档已变（`modificationStamp` 不符）→ 丢弃本次结果并重排一次刷新。现有逻辑，保持。

## 测试

数据层（`SessionTitleRegeneratorTest`）：

- `user 与其后第一条 assistant 配成一轮` —— 断言配对结果的 userText 和 assistantReply 都是期望值。
- `一轮里的多条 assistant 只取第一条作回复`
- `末轮没有回复时 assistantReply 为空串` —— 覆盖"还在生成"的状态。
- 已有的 `三种 CLI 的最近用户消息都可用于会话内导航` 改成断言 exchange，**三种 CLI 各自喂自己格式的 transcript 行**，保持现在这条测试对三条解析分支各有一次真实调用的形态。

定位层（`SessionMessageNavigatorTest`）：

- 现有三条（软换行定位、重复消息顺序定位、历史裁剪不阻碍后续）继续有效，入参改成 exchange。
- 新增 `助手回复随对应的用户消息一起进入锚点` —— 断言匹配出的 anchor 上 `replyPreview` 是同一轮的回复，不是相邻轮的。
- `markerY` 等比分布那条不变。

源码层（`SessionMessageNavigatorSourceTest`）：

- 现有两条（绑定/释放、tooltip 与滚动）中，tooltip 那条改为断言 `createComponentPopupBuilder` 与 `setRequestFocus(false)`。
- 新增断言：`dispose` 与 `unbind` 路径都关闭 popup。

`tasks.test` 已把整个 `src/main/kotlin` 声明为输入，新增文件自动纳入，不存在源码级断言被 UP-TO-DATE 跳过的风险。

## 验证

单元测试之外必须跑 `runIde` 实看：轨道可见、悬停出卡片、卡片不抢焦点、点击跳转正确、切换 buffer 后无残留。上一版 error stripe 方案就是"测试全绿但终端根本不画"，源码断言证明不了终端的实际渲染。
