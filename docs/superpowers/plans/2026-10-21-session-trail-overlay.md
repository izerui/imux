# 会话轨迹右上角面板 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把会话轨迹从独立窗口迁到每个会话标签页正文右上角的可折叠、可调整大小的叠加面板。

**Architecture:** 生成任务与状态提到项目级服务里的 `TrailStateStore`（按 sessionKey，同会话唯一任务），界面 `SessionTrailOverlay` 作为 `JBLayeredPane` 的 `PALETTE_LAYER` 组件挂在 `AgentTerminalFileEditor` 上，只订阅状态、不持有任务。原窗口删除，结果视图拆到 `TrailViews.kt` 复用。

**Tech Stack:** Kotlin、IntelliJ Platform 262（Swing、kotlinx.coroutines）、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-10-21-session-trail-overlay-design.md`

## Global Constraints

- sinceBuild 262，不引入内部 API。
- 缓存格式（`.idea/imux/overviews/*.json`）、提示词、解析、重试、超时不变。
- 新文案必须同时加到 10 个 `ImuxBundle*.properties`（`ImuxBundleTest` 校验键集合与占位符一致）。
- 现有源码测试钉住的写法保留：`setLayer(scrollButton, JLayeredPane.PALETTE_LAYER)`、`setLayer(navigatorComponent, JLayeredPane.PALETTE_LAYER)`、`messageNavigator.bind(editor)`、`Disposer.dispose(messageNavigator)`。
- 测试命令：`./gradlew test --offline`；单个类 `./gradlew test --offline --tests '<FQN>'`。

## 文件结构

| 文件 | 职责 |
|---|---|
| `overview/TrailStateStore.kt`（新） | `TrailState`、`TrailStateStore`：状态、唯一任务、取消、过期派生、监听 |
| `overview/ProgressOverview.kt` | 服务持有 `trailStore` |
| `overview/ProgressOverviewPopup.kt` → `overview/TrailViews.kt` | 删除窗口；保留结果面板、加载行、节点面板、进度文字、错误文字；新增错误视图、提示视图 |
| `terminal/SessionTrailLayout.kt`（新） | 纯函数 `trailBounds`、`resizedTrailSize`、尺寸常量与持久化 |
| `terminal/SessionTrailOverlay.kt`（新） | 折叠图标、卡片、拖拽调整大小、焦点 |
| `terminal/AgentTerminalFileEditor.kt` | 挂载叠加层、布局、关闭时取消 |
| `terminal/AgentTerminalVirtualFile.kt` | `trailExpanded` |
| `terminal/ProgressOverviewAction.kt` | 右键入口：展开 + 无缓存时生成；新增 `ToggleSessionTrailAction` |
| `plugin.xml`、`ImuxBundle*.properties`、`README.md` | 注册、文案、文档 |

---

### Task 1: TrailStateStore

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/overview/TrailStateStore.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/overview/ProgressOverview.kt`（服务里新增 `trailStore`）
- Test: `src/test/kotlin/com/github/izerui/imux/overview/TrailStateStoreTest.kt`

**Interfaces（Produces）:**

```kotlin
sealed interface TrailState {
    data object Unavailable : TrailState
    data object Empty : TrailState
    data class Loading(val progress: String?, val startedAt: Long) : TrailState
    data class Ready(val result: OverviewResult, val stale: Boolean) : TrailState
    data class Failed(val message: String, val previous: OverviewResult?) : TrailState
}

internal class TrailStateStore(
    scope: CoroutineScope,
    loadCached: (String) -> CachedView?,
    isAvailable: (String) -> Boolean,
    generate: (String, (AgentProgressEvent) -> Unit) -> OverviewResult,
    errorText: (Throwable) -> String,
    onEdt: (() -> Unit) -> Unit,
    progressText: (AgentProgressEvent) -> String? = ::overviewProgressText,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    clock: () -> Long = System::currentTimeMillis,
) {
    fun state(key: String): TrailState
    fun generate(key: String)
    fun cancel(key: String)
    fun forget(key: String)              // cancel + 清掉记录，用于标签页真正关闭
    fun notifyChanged(key: String)       // 展开状态等界面变化，通知同一会话的所有面板
    fun addListener(listener: (String) -> Unit): () -> Unit   // 返回移除函数
    internal fun jobFor(key: String): Job?
}
```

规则：

- `state(key)`：有记录取记录（`Ready` 的 stale = `result.stale || loadCached(key)?.stale == true`）；无记录时 `!isAvailable → Unavailable`，有缓存 → `Ready(cached.result, cached.stale)`，否则 `Empty`。
- `generate`：`Unavailable` 或已有活动任务时忽略；记下生成前的记录，置 `Loading`；IO 上 `runInterruptible { generate(...) }`；成功 → `Ready(result, result.stale)`；异常 → `Failed(errorText(e), 之前的结果)`；所有回写都在 `onEdt` 中且只在任务仍是当前任务时生效。
- `cancel`：取消任务，恢复生成前的记录（原来没有记录就删除记录），通知。
- 测试用例：
  1. 无缓存 → Empty；不可用 → Unavailable；有缓存 → Ready 且带 stale。
  2. 成功生成后为 Ready，监听收到该 key。
  3. 生成中重复 `generate` 只调用一次生成函数。
  4. 失败 → Failed，`previous` 为之前的 Ready 结果，消息来自 `errorText`。
  5. `cancel` 后恢复 Empty（或之前的 Ready），晚到的结果不覆盖。
  6. 不同 key 互不影响。
  7. 进度事件更新 `Loading.progress`，返回 null 的事件保留上一条。
  8. Ready 结果写缓存后会话文件变大 → `state` 返回 stale。

- [ ] 写测试（`onEdt = { it() }`，`scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`，用 `runBlocking { store.jobFor(k)?.join() }` 等待）
- [ ] 运行确认失败：`./gradlew test --offline --tests 'com.github.izerui.imux.overview.TrailStateStoreTest'`
- [ ] 实现 `TrailStateStore`，服务中构造：
  `internal val trailStore = TrailStateStore(coroutineScope, ::cachedOverview, { SessionMonitor.getInstance(project).model.sessionOf(it) != null }, { k, p -> generateOverview(k, p) }, ::overviewErrorText, { r -> ApplicationManager.getApplication().invokeLater(r, ModalityState.any()) })`
- [ ] 运行通过

### Task 2: 拆出 TrailViews，删除窗口

**Files:**
- Rename: `overview/ProgressOverviewPopup.kt` → `overview/TrailViews.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/overview/ProgressOverviewTest.kt`

**Interfaces（Produces）:**

```kotlin
internal fun buildResultPanel(result: OverviewResult, stale: Boolean, onRegenerate: () -> Unit): JComponent   // 已有，不变
internal class LoadingRow                                                                                      // 已有，不变
internal fun buildErrorPanel(message: String, onRetry: () -> Unit): JComponent                                // 从 showError 拆出
internal fun buildFailedBanner(message: String, onRetry: () -> Unit): JComponent                               // 有旧结果时的错误横幅
internal fun buildHintPanel(text: String, action: Pair<String, () -> Unit>?): JComponent                       // Empty / Unavailable
```

- [ ] 删除 `showProgressOverviewPopup`、`ProgressOverviewDialog`、`overviewDialogTitle` 及其 3 个测试
- [ ] 新增上述三个视图函数；测试：错误视图含消息文字与「重新生成」链接，点击回调被调用；提示视图带/不带按钮
- [ ] 运行 `ProgressOverviewTest` 通过

### Task 3: 布局纯函数与尺寸持久化

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/terminal/SessionTrailLayout.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/terminal/SessionTrailLayoutTest.kt`

**Interfaces（Produces）:**（所有尺寸参数已按像素缩放）

```kotlin
internal data class TrailMetrics(val margin: Int, val chip: Int, val minWidth: Int, val minHeight: Int)
internal fun trailMetrics(): TrailMetrics      // margin 8, chip 24, minWidth 240, minHeight 140，经 JBUI.scale
internal fun trailBounds(width: Int, height: Int, rightReserve: Int, expanded: Boolean,
                         desiredWidth: Int, desiredHeight: Int, metrics: TrailMetrics): Rectangle
internal fun resizedTrailSize(startWidth: Int, startHeight: Int, dx: Int, dy: Int, resizeWidth: Boolean,
                              resizeHeight: Boolean, maxWidth: Int, maxHeight: Int, metrics: TrailMetrics): Dimension
internal object TrailSizeSetting {           // PropertiesComponent 应用级，存未缩放值
    const val DEFAULT_WIDTH = 360; const val DEFAULT_HEIGHT = 420; const val COMPACT_HEIGHT = 140
    fun width(): Int; fun height(): Int; fun save(width: Int, height: Int); fun reset()
}
```

规则：右边缘 = `width − rightReserve − margin`；顶部 = `margin`。折叠时是 `chip × chip`。展开时宽 = `desiredWidth` 夹在 `[minWidth, 可用宽]`，可用宽 = 右边缘 − margin；高同理，可用高 = `height − 2·margin`；可用区域比最小值还小时以可用区域为准（不为负）。拖拽：卡片锚定右上，`dx < 0` 变宽，`dy > 0` 变高。

- [ ] 测试：常规尺寸；窄宽时收缩且右边缘不越过导航轨道；矮高；尺寸为 0 不出现负值；拖拽变宽变高、夹在上下限
- [ ] 实现并通过

### Task 4: SessionTrailOverlay 并接入编辑器

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/terminal/SessionTrailOverlay.kt`
- Modify: `terminal/AgentTerminalFileEditor.kt`、`terminal/AgentTerminalVirtualFile.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/terminal/SessionTrailOverlaySourceTest.kt`

**Interfaces:**

```kotlin
internal class SessionTrailOverlay(
    project: Project,
    virtualFile: AgentTerminalVirtualFile,
    parent: Disposable,
    focusTerminal: () -> Unit,
    relayout: () -> Unit,
) {
    val chip: JComponent
    val card: JComponent
    fun layout(width: Int, height: Int, rightReserve: Int)
}
```

- 折叠图标：`ActionButton` + 动作，图标按状态切换（`AllIcons.Actions.Preview` / `AnimatedIcon.Default()` / 带角标 / 置灰）；点击 → `virtualFile.trailExpanded = true` + `store.notifyChanged(key)`。
- 卡片：头部标题 + 「重新生成」「收起」图标按钮；正文按状态构建；Loading 每秒刷新耗时；Esc 收起；收起时焦点在卡片内则 `focusTerminal()`。
- 拖拽：卡片左边缘、下边缘、左下角热区（6px）改变光标并拖动调整，松手 `TrailSizeSetting.save`；左下角双击 `reset()`。
- 订阅：store 监听（key 等于当前 `virtualFile.sessionKey` 时刷新）、`TerminalHost.addSessionKeyMigratedListener`、`SessionMonitor.addListener`（1 秒合并刷新）、语言切换。
- 编辑器：`setLayer(trailOverlay.chip, JLayeredPane.PALETTE_LAYER)`、`setLayer(trailOverlay.card, JLayeredPane.PALETTE_LAYER)`；`doLayout` 中 `trailOverlay.layout(width, height, navigatorWidth + JBUI.scale(NAVIGATOR_RIGHT_INSET))`；`dispose` 中 `CLOSING_TO_REOPEN` 早退之后调用 `ProgressOverviewService.getInstance(project).trailStore.forget(sessionKey)`。

- [ ] 源码测试：编辑器把 chip、card 放在 `PALETTE_LAYER`；`forget` 位于 `CLOSING_TO_REOPEN` 判断之后；叠加层订阅 key 迁移；按钮不可聚焦
- [ ] 实现并通过；`./gradlew buildPlugin --offline` 编译通过

### Task 5: 入口、注册、文案、文档

**Files:**
- Modify: `terminal/ProgressOverviewAction.kt`、`src/main/resources/META-INF/plugin.xml`、`src/main/resources/messages/ImuxBundle*.properties`（10 个）、`README.md`
- Test: `src/test/kotlin/com/github/izerui/imux/PluginXmlRegistrationTest.kt`

- `ProgressOverviewAction`：展开面板；状态为 Empty 时 `generate`。
- `ToggleSessionTrailAction`：切换展开，id `imux.ToggleSessionTrail`，不预设快捷键。
- 新文案键：`action.overview.toggle.text`、`action.overview.toggle.description`、`action.overview.generate`、`action.overview.cancel`、`action.overview.collapse`、`action.overview.pending`、`action.overview.empty`、`action.overview.failed.banner`（`{0}` 为错误信息）、`action.overview.resize.hint`。

- [ ] PluginXml 测试：两个动作已注册
- [ ] 实现、补全 10 种语言、更新 README
- [ ] 全量 `./gradlew test --offline` 与 `./gradlew buildPlugin --offline` 通过
