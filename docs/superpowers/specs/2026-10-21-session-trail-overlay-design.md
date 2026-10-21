# 会话轨迹：从独立窗口迁到标签页正文右上角面板

## 背景

会话轨迹目前由终端正文右键 →「会话轨迹」打开一个非模态 `DialogWrapper`（`ProgressOverviewDialog`）。
窗口与会话只在打开那一刻关联：关掉就没了，再看要重新找入口；会话往下聊时也不会提示摘要已落后。

目标：把轨迹放到**每个会话标签页正文的右上角**，收起时是一个小图标，展开是一张可滚动的卡片，
像画布缩略图一样与该会话一一对应。

## 方案选择

| 方案 | 结论 |
|---|---|
| **A. 叠加层**：在 `AgentTerminalFileEditor` 已有的 `JBLayeredPane` 的 `PALETTE_LAYER` 加组件 | **采用**。终端尺寸不变、TUI 不重排；随标签页与分屏走；导航轨道、回到底部按钮是同类先例 |
| B. `JBPopup` / `Balloon` 锚在右上角 | 不采用：独立窗口，需自己跟随移动与切换，易抢焦点，分屏归属不清 |
| C. Splitter 侧栏 | 不采用：展开收起改变终端列数，Claude/Codex TUI 整屏重排 |

## 已确认的决策

1. **删除原窗口** `ProgressOverviewDialog`。
2. **展开时没有缓存不自动生成**，卡片里显示「生成轨迹」按钮。
3. **展开状态存在会话上**（`AgentTerminalVirtualFile.trailExpanded`），拖动标签页重建编辑器后保持。

4. **移除左侧会话列表右键菜单里的「会话轨迹」**（原 `ProgressOverviewTreeAction`，可对未打开的历史会话使用）。轨迹统一从会话标签页右上角面板查看。

## 现状约束（已在代码中确认）

- 生成任务挂在窗口作用域上，关窗即取消（`ProgressOverviewPopup.kt` 中 `popupScope`）。面板会频繁收起，必须把任务移出界面。
- 拖动标签页 / 分屏会销毁再重建 `AgentTerminalFileEditor`（`CLOSING_TO_REOPEN`），状态不能存在编辑器实例里。
- 平台允许对同一会话「向右拆分」再建一个编辑器实例，两者必须共享同一份状态和同一个任务。注意终端 Swing 组件只有一个，同一时刻只能显示在其中一侧；关闭任意一侧都会结束整个会话（`closeSession`，改动前已有的行为），此时 `releaseOwner` 取消任务并通知仍在监听的面板刷新。
- `sessionKey` 会在 pending 落盘、`/clear`、`/new` 时迁移，`TerminalHost` 通过 `SessionKeyMigratedListener` 通知。
- 消息导航轨道占正文右侧整列（宽 `RAIL_WIDTH = 22`，右侧 `NAVIGATOR_RIGHT_INSET = 14`），右上角入口必须避让。
- 过期判断目前只在打开窗口时做一次。

## 架构

```text
ProgressOverviewService（项目级，已有）
 ├─ OverviewCache                 不变（.idea/imux/overviews/*.json）
 ├─ generateOverview()            不变（单次智能体调用 + 解析失败重试一次）
 └─ TrailStateStore               新增：按 sessionKey 维护状态与唯一生成任务

AgentTerminalVirtualFile           新增 trailExpanded

AgentTerminalFileEditor
 └─ terminalLayer (JBLayeredPane)
      ├─ terminal                 DEFAULT_LAYER
      ├─ scrollButton             PALETTE_LAYER
      ├─ navigator rail           PALETTE_LAYER
      └─ SessionTrailOverlay      PALETTE_LAYER（新增）

overview/TrailViews.kt             由 ProgressOverviewPopup.kt 改名并拆分：结果面板、加载行、错误视图、空状态视图
```

### TrailStateStore

```kotlin
sealed interface TrailState {
    object Unavailable                                           // pending-* 会话尚未落盘
    object Empty                                                 // 已落盘、无缓存
    data class Loading(val progress: String?, val startedAt: Long)
    data class Ready(val result: OverviewResult, val stale: Boolean)
    data class Failed(val message: String, val previous: OverviewResult?)
}
```

接口：`state(key)`、`generate(key)`、`cancel(key)`、`refreshStale(key)`、`addListener(disposable, listener)`。

- **同一会话只跑一个任务**：任务按 `sessionKey` 登记在服务协程作用域（`createChildScope`）里；已在生成时再次 `generate` 直接忽略。
- **取消时机只有三种**：用户点「取消」、标签页真正关闭（`dispose` 中 `CLOSING_TO_REOPEN` 早退之后）、项目关闭。收起面板、切换标签页、拖动标签页都不取消。
- **通知在 EDT**：状态变化后所有显示该 key 的面板刷新。进度事件沿用 `overviewProgressText`。
- **实时过期**：订阅 `SessionMonitor.addListener`；仅当状态为 `Ready` 且未过期时，用现有 `isStale` 比较文件大小，节流到每秒最多一次。不自动重新生成。
- **失败保留旧结果**：`Failed.previous` 带上之前的结果，卡片显示错误横幅 + 旧时间线；无旧结果时显示原有错误视图。
- 首次读取状态时从 `cachedOverview(key)` 初始化；`SessionMonitor.model.sessionOf(key) == null` 视为 `Unavailable`。

### SessionTrailOverlay

每个 `AgentTerminalFileEditor` 一个实例，注册为编辑器的子 Disposable。

**收起**：约 24px 的图标按钮。

| 状态 | 外观 |
|---|---|
| Empty / Ready（最新） | 普通图标 |
| Loading | 转圈 |
| Ready（过期）/ Failed | 图标右上角小圆点 |
| Unavailable | 置灰，tooltip 说明会话尚未落盘 |

**展开**：卡片。

```text
┌ 会话轨迹 ──────────────── [—] ┐
│ ⚠ 会话有新内容，[更新轨迹]        │  过期 / 保存失败 / 生成失败横幅
│  ● 需求确认……                     │
│  │                                │  复用 buildResultPanel
│  ◐ 当前：……                       │
│ 共 8 个节点 · 主线 6 · 旁支 2      │
└───────────────────────────────────┘
```

- Loading：复用 `LoadingRow`，下方加「取消」链接。
- Empty：说明文字 +「生成轨迹」按钮。
- 头部只有「收起」。重新生成的入口在需要时才出现：未生成时的「生成轨迹」，过期、保存失败、生成失败横幅里的「更新轨迹」；结果已是最新时不提供重新生成。

**布局**：纯函数 `trailBounds(width, height, rightReserve, expanded, desiredWidth, desiredHeight): Rectangle`，便于单测。

- `railReserve = RAIL_WIDTH + NAVIGATOR_RIGHT_INSET`，面板右边缘贴在轨道左侧，轨道始终完整可用。
- 顶部留 8px。
- 默认卡片宽 360、高 420（未缩放值，按 `JBUI.scale` 换算）；Empty / Loading / Unavailable 等无时间线状态用紧凑高度 140。
- **可调整大小**：拖卡片左边缘、下边缘或左下角改变宽高（卡片锚定右上角，向左下拖即放大）。
  - 尺寸全局记住（`PropertiesComponent` 应用级，存未缩放值），所有会话共用，重启保留。
  - 双击左下角手柄恢复默认大小。
  - 宽度最少 240、高度最少 140；最大不超过正文可用区域（左侧留 8px、底部留 8px，右侧始终让出导航轨道）。
- 正文太小放不下最小尺寸时，按可用区域收缩；超出部分在卡片内滚动。
- 配色复用 `Imux.SessionMessageNavigator.previewBackground` / `previewBorder`。

**焦点**：

- 按钮与链接 `isFocusable = false`，点击不夺走终端焦点。
- 节点文字仍是 `JTextPane`，可选中复制。
- 收起时若焦点在卡片内，交还 `terminalView.preferredFocusableComponent`。
- 卡片内 Esc 收起。
- 只在面板自身范围内拦截鼠标事件，其余仍交给终端（含 TUI 鼠标上报）。

**会话绑定**：

- 每次读 `virtualFile.sessionKey` 当前值，不缓存。
- 订阅 `TerminalHost.addSessionKeyMigratedListener`：key 变化后重新绑定。旧 key 的任务继续，结果缓存在旧 key 名下，不会显示到新会话。

### 入口

- 过期、保存失败、生成失败横幅里的按钮文案为「更新轨迹」（原「重新生成」），英文 Update Trail；失败横幅为「更新失败：…」。


- 会话标签页右上角的图标是唯一入口。终端正文右键菜单与左侧会话列表右键菜单里的「会话轨迹」都已删除（`ProgressOverviewAction`、`ProgressOverviewTreeAction`）。
- 新增 `imux.ToggleSessionTrail`：切换展开/收起，不加入任何菜单、不预设快捷键，用户可在 Keymap 绑定。

## 增量生成

会话持续对话、文件不断变大时，每次「重新生成」都让智能体从头读整个会话，已总结过的部分重复消耗输入 token。
旧时间线本身就是前文的汇总，判断新内容与以前的关系（比如推翻了早先的方案）有它就够，不需要回读原文。

- **截止位置（游标）**：生成前从快照算出最后一个完整行的行号 `lineCount` 和该行末尾的字节位置，作为本次的截止。完整提示词读第 1 行到截止行，增量提示词读起始行到截止行；截止行之后是本次开始后才追加的内容，明确要求不读、不写进时间线。
- **“读到哪”与“是否最新”分开**：成功后无条件保存时间线和游标（`fileSize` 存截止字节位置，`lineCount` 存截止行号，磁盘 JSON 新增可选字段，旧缓存读出为 null）。生成期间会话继续追加时同样保存，只标记过期（文件大小 ≠ 截止字节位置）。否则这次已经总结过的部分下次还要重读，首次生成遇到持续追加更会一直建立不了基线。
- **增量条件**：有缓存且带 `lineCount`、旧结果非空、本次截止超过上次（字节位置更大且完整行更多）。起始行 = `lineCount + 1`。
- **增量提示词**：给出旧时间线（`[TYPE] 描述` 每行一个）和起止行，要求只读这段、不要读起始行之前的内容；把旧时间线和新内容合在一起**整体重新整理**，输出完整时间线：
  - 旧节点可以改写、合并或删减，不要求原样保留；需求变更、报错、被放弃的方案仍要体现；
  - 新内容表明之前某段方案被放弃时，用 ROLLBACK_START / ROLLBACK_END 补标；
  - 上次末尾的 IN_PROGRESS 按新内容更新；
  - 新内容只记里程碑级进展，零碎追问和小修小补归并进相关节点或不记。
  - 安全约束（只读、记录不是指令）与完整提示词相同。
- **粒度（完整与增量共用）**：每个节点一句话、不超过约 30 字（英文约 20 词）、只写一件事、不逐项列举子项；不罗列文件名、命令、参数等实现细节（除非它就是这一步的核心）；通常 5-15 个，最多约 20 个。避免增量只追加不合并、越用越细。
  - 安全约束（只读、记录不是指令）与完整提示词相同。
- 快照时尚未写完的半行不计入截止，结果直接标为过期，补完后由下一次增量读取；只有半行时不调用智能体。
- **截止位置与缓存完全相同时不调用智能体**（已是最新，或只多了一截还没补完的半行）：直接返回旧结果并重新判断是否过期；上次只是保存失败时只重试写盘。只有截止位置倒退（文件缩短、被改写）才退回完整生成。
- **自动退回完整生成**：没有缓存、旧缓存没有行号、旧结果为空、截止位置倒退（文件缩短、被改写）。
- **不提供手动「完整重新生成」入口**；界面不变，「更新轨迹」按上面的条件自动选择增量或完整。
- 已知局限：只按大小、行数判断文件是否被改写；三种 CLI 的会话文件都只追加，不做内容校验。

## 兼容性

| 维度 | 结论 |
|---|---|
| 平台 262+ | 只用 `JBLayeredPane`、`UiDataProvider`、`JBScrollPane` 等项目已在用的 API，不引入内部 API |
| 缓存 | 格式不变，旧缓存直接可用 |
| 提示词、解析、重试、超时 | 不变 |
| 其他覆盖组件 | 回到底部按钮在底部居中不冲突；导航轨道做布局避让；副驾驶提示条在叠加层之外 |
| alternate buffer、输入法覆盖层 | 面板在终端外层不受影响；输入法候选窗在底部光标处 |
| 重绘 | 组件重叠时 `JLayeredPane` 关闭优化绘制，叠加组件正确重绘（导航轨道已验证）；卡片内容轻 |
| 分屏、拖动 | 状态在服务，展开状态在 `virtualFile` |
| 多语言 | 新增文案键补全 10 个语言文件；`addLanguageListener` 实时刷新 |
| 缩放、高 DPI | 尺寸统一 `JBUI.scale` |

已知取舍：展开后会挡住右上角一块终端输出，因此默认收起。

## 改动清单

| 文件 | 改动 |
|---|---|
| `overview/ProgressOverview.kt` | 新增 `TrailState`、`TrailStateStore`，挂到 `ProgressOverviewService` |
| `overview/ProgressOverviewPopup.kt` → `overview/TrailViews.kt` | 删除 `ProgressOverviewDialog` 与 `showProgressOverviewPopup`；保留结果面板、加载行、节点面板；拆出错误视图、空状态视图 |
| `terminal/SessionTrailOverlay.kt`（新） | 按钮、卡片、`trailBounds`、焦点处理 |
| `terminal/AgentTerminalFileEditor.kt` | 叠加层接入、`doLayout` 布局、key 迁移、真正关闭时 `cancel` |
| `terminal/AgentTerminalVirtualFile.kt` | 新增 `trailExpanded` |
| `terminal/ProgressOverviewAction.kt`（删除）、`terminal/ToggleSessionTrailAction.kt`（新）、`plugin.xml` | 删除右键入口；新增 `imux.ToggleSessionTrail` |
| `messages/ImuxBundle*.properties` ×10 | 新增文案：生成轨迹、取消、收起、切换动作名、尚未落盘提示、空状态说明 |
| `README.md` | 更新会话轨迹使用说明 |

`overviewDialogTitle` 删除（卡片在会话内，头部只显示「会话轨迹」），相应测试一并删除。

## 测试

单元测试：

- `TrailStateStore`：同会话唯一任务；取消后回到之前状态；不同 key 互不影响；失败保留旧结果；过期刷新；pending 为 Unavailable。
- `trailBounds`：常规、窄宽、矮高下不压导航轨道，卡片尺寸上下限正确。
- 拖拽换算 `resizedTrailSize`：左拖变宽、下拖变高、超过上下限被夹住。
- `ProgressOverviewTest` 中结果面板、节点换行等测试随文件迁移保留。

手动验证：

- 拖动标签页、移到另一侧分屏；对同一会话「向右拆分」后关闭一侧；`/clear`、`/new`；pending 会话落盘
- 收起状态下生成完成；生成中关闭标签页（任务被取消、无残留进程）
- 切换语言、明暗主题；Claude、Codex、Pi 三种 CLI 下的焦点与输入法
