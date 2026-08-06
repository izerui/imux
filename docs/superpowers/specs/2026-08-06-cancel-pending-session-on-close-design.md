# 关闭未绑定新会话时立即移除 pending 的设计

## 背景

当前新建会话的流程是：

1. 在 `SessionListModel` 中调用 `registerPending(agentType)` 先登记一个 `pending-*` 占位项
2. `TerminalHost.openNew(...)` 以该 pending key 打开终端标签页
3. 等 CLI 落盘出真实会话后，再通过绑定逻辑把 pending key 迁移为真实会话 id

这保证了“新会话”可以在首条消息出现前立刻显示在列表中。

问题在于关闭路径不对称：

- 关闭标签页时，`AgentTerminalFileEditor.dispose()` 只会调用 `TerminalHost.closeSession(sessionKey)` 结束终端
- 但不会通知 `SessionListModel` 删除尚未绑定的 pending

因此，当用户“新建会话后、尚未发送首条消息就直接关闭”时：

- 终端已关闭
- 但 `pending-*` 仍保留在 `SessionListModel.pendings`
- 列表会继续显示“新会话（等待首条消息）”
- 直到 30 分钟 TTL 到期才被 `expirePendings()` 清除

这与用户预期不符。用户期望是：

- **未绑定真实会话 id 的 pending 新会话**：关闭时立即从列表移除
- **已绑定为真实会话的记录**：关闭标签页后仍保留会话记录

## 目标

补齐 pending 的关闭生命周期，使“创建 pending”和“取消未绑定 pending”形成对称操作。

## 非目标

本次不改变以下行为：

- 不修改真实会话的保留策略
- 不修改 pending 的绑定规则
- 不修改 30 分钟 TTL，TTL 仍作为兜底清理机制存在
- 不引入“已关闭 pending”的额外状态

## 方案选择

### 方案 A：在 `SessionListModel` 增加取消 pending 能力，由关闭路径显式调用（推荐）

做法：

- 在 `SessionListModel` 增加 `cancelPending(key: String): Boolean`
- 在 `SessionMonitor` 增加协调入口 `cancelPendingSession(key: String)`
- `AgentTerminalFileEditor.dispose()` 在真正关闭标签页时：
  1. 调用 `TerminalHost.closeSession(sessionKey)`
  2. 再调用 `SessionMonitor.cancelPendingSession(sessionKey)`

优点：

- 状态边界清晰：pending 的增删改查仍归 `SessionListModel`
- `TerminalHost` 仍只负责终端与标签页，不耦合会话列表状态
- 改动小，符合现有结构
- 容易做成幂等行为

缺点：

- 关闭路径上多了一次显式状态同步

### 方案 B：在 `TerminalHost.closeSession()` 内部直接处理 pending

不采用。原因是这会让终端宿主层直接依赖会话状态模型，破坏当前分层。

### 方案 C：给 pending 增加“已关闭”状态并在渲染层过滤

不采用。需求仅要求“关闭后立即消失”，引入额外状态会增加复杂度，收益不足。

## 详细设计

### 1. `SessionListModel`

新增方法：

```kotlin
fun cancelPending(key: String): Boolean
```

语义：

- 仅删除“key 匹配且尚未绑定真实会话 id”的 pending
- 以下情况返回 `false` 且不做任何修改：
  - key 不存在
  - key 已绑定到真实会话 id
  - key 本身不是 pending key，而是一个真实会话 id
- 删除成功时返回 `true`
- 仅在实际发生删除时通知监听者

实现原则：

- 仍使用 `pendings` 与 `bindings` 作为唯一事实来源
- “尚未绑定”的判断以 `key !in bindings` 为准
- 不清理 `bindings`：因为能进入删除分支的前提就是该 key 尚未绑定

### 2. `SessionMonitor`

新增协调方法：

```kotlin
fun cancelPendingSession(key: String)
```

职责：

- 作为界面层与模型层之间的稳定入口
- 内部转调 `model.cancelPending(key)`
- 不额外引入副作用；刷新与通知由 model 自己负责

这样可保持 `AgentTerminalFileEditor` 不直接依赖 `SessionListModel` 的内部细节。

### 3. `AgentTerminalFileEditor.dispose()`

在真正关闭标签页（且不是 `CLOSING_TO_REOPEN` 的平台重建场景）时，执行：

1. `TerminalHost.getInstance(project).closeSession(virtualFile.sessionKey)`
2. `SessionMonitor.getInstance(project).cancelPendingSession(virtualFile.sessionKey)`

设计理由：

- 若 `sessionKey` 仍是 `pending-*` 且未绑定，则该调用会删除 pending
- 若 `sessionKey` 已经通过 `rebindKey(...)` 迁移成真实会话 id，则该调用是 no-op
- 这样可以无分支复用同一关闭路径，不需要在界面层自行判断 key 类型

## 调用时序

### 未绑定新会话关闭

1. 用户点击“新建会话”
2. `registerPending()` 创建 `pending-*`
3. 终端打开，但真实会话尚未落盘
4. 用户关闭标签页
5. `closeSession(pendingKey)` 结束终端
6. `cancelPendingSession(pendingKey)` 删除未绑定 pending
7. `SessionListModel` 通知监听者，列表立即刷新

结果：列表中的“新会话（等待首条消息）”立即消失。

### 已绑定真实会话关闭

1. `pending-*` 已绑定到真实会话 id
2. `TerminalHost.rebindKey(...)` 已将标签页 key 迁移为真实 id
3. 用户关闭标签页
4. `closeSession(realSessionId)` 结束终端
5. `cancelPendingSession(realSessionId)` 命中 no-op

结果：真实会话记录继续保留在列表中。

## 边界条件

1. **新会话刚创建就关闭**
   - pending 立即删除
   - 列表立即消失

2. **已绑定后关闭**
   - 不删除真实会话
   - 只关闭终端/标签页

3. **重复关闭或重入**
   - `cancelPendingSession()` / `cancelPending()` 必须幂等
   - 第二次调用无副作用

4. **拖动标签页、分屏重建 editor**
   - 现有 `FileEditorManagerKeys.CLOSING_TO_REOPEN` 判定继续保留
   - 这些场景不应触发 pending 删除

5. **TTL 兜底清理仍有效**
   - 未被主动关闭的孤儿 pending 仍可由现有 30 分钟 TTL 机制回收

## 测试设计

在 `SessionListModelTest` 增加以下测试：

1. **取消未绑定 pending 会移除条目**
   - `registerPending()` 后调用 `cancelPending(key)`
   - 断言对应分组 entries 为空

2. **取消已绑定 pending 不影响真实会话**
   - 先绑定到真实 id
   - 再调用 `cancelPending(pending.key)`
   - 断言真实会话仍存在

3. **取消不存在 key 无副作用**
   - 调用 `cancelPending("missing")`
   - 断言返回 false，entries 不变

4. **仅成功取消时通知监听者**
   - 成功删除时 listener 被通知一次
   - 无效取消时不重复通知

如有必要，可补一个更高层的关闭路径测试；但本次核心逻辑集中在 `SessionListModel`，优先用模型层单测锁定语义。

## 实施摘要

本次改动采用方案 A：

- 在 `SessionListModel` 增加 `cancelPending(key)`
- 在 `SessionMonitor` 增加 `cancelPendingSession(key)` 作为协调入口
- 在 `AgentTerminalFileEditor.dispose()` 的真实关闭路径中调用该入口
- 用单测覆盖未绑定、已绑定、无效 key 与通知行为

该设计最小化改动范围，同时保持现有分层与状态归属不变。