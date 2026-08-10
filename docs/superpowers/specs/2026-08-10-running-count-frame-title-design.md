# 窗口标题显示运行中会话数

## 背景

窗口标题目前只表达未读：有未读会话时加 `✳ ` 前缀（`frame/UnreadFrameTitleBuilder.kt`）。运行中状态则只活在项目窗口**内部**——标签页品牌图标呼吸、会话树转菊花，两者都要求用户正看着这个窗口。

于是出现一个缺口：macOS 上把多个项目窗口合并成标签栏后，切到别的项目就无从知道某个项目还有没有会话在跑。而这恰恰是 `✳ ` 前缀当初要解决的同一类问题——把窗口内的状态送出窗口。未读走通了这条路，运行中没有。

## 目标

窗口标题在项目名后追加运行中会话数：`imux（2 个运行中）`。

## 非目标

**不做加粗。** 用户希望未读时标题加粗，平台做不到：`getProjectTitle` 返回纯 `String`，交给平台去设 `NSWindow.title` / Swing 窗口标题，没有富文本通道，无法着色或改字重。这正是当初选 `✳ ` 字符而非图标的原因（见 `UnreadFrameTitleBuilder.kt:28` 注释）。Unicode 数学粗体字符（`𝗶𝗺𝘂𝘅`）能伪造字重，但对中文项目名完全无效、破坏可搜索性、缺字时显示豆腐块，不采用。未读的视觉强度以 `✳ ` 前缀为上限。

**不新增状态来源。** 复用 `SessionMonitor.runningIds`，不引入新的扫描或推断。

## 设计

### 1. 行为规格

前缀与后缀是两段独立装饰，各管各：

| 未读 | 运行中数 | 标题 |
|---|---|---|
| 否 | 0 | `imux` |
| 是 | 0 | `✳ imux` |
| 否 | 2 | `imux（2 个运行中）` |
| 是 | 2 | `✳ imux（2 个运行中）` |

- **计数口径**：`SessionMonitor.runningIds.size`。该集合在 `SessionMonitor.kt:404` 已按 `projectPath` 过滤，天然只算本项目——运行态目录是全机器共享的，不过滤会把别的项目的 claude 算进来。
- **等待用户选择的会话不计入**。`waiting` 已在上一版排除出 `isBusy`（见 `2026-08-10-waiting-unread-design.md`），此处自动继承：CLI 停下来问权限时以未读星号出现，不再假装在跑。两种标记语义不重叠。
- **0 个运行中时整个括号不出现**，不显示 `（0 个运行中）`。

### 2. 位置取舍

能覆盖的只有 `getProjectTitle`（项目名那一段），平台随后拼上文件路径，所以后缀夹在中间：

```
✳ imux（2 个运行中） – src/main/kotlin/Foo.kt
      ^^^^^^^^^^^^^ 新增
```

好处是不会被右侧省略号截掉——标签宽度有限，只有开头能保证可见，这也是现有实现只覆盖项目名段的原因。代价是会话数变化时右侧文件路径横向抖动一次。已确认接受。

### 3. 类重命名

`UnreadFrameTitleBuilder` → `AgentFrameTitleBuilder`。这个类现在管两种状态，旧名会让人以为窗口标题只表达未读。同步改 `plugin.xml:72` 的注册与 `PluginXmlRegistrationTest.kt:72-73` 的断言及其文案。

### 4. 装饰函数

```kotlin
internal fun decorate(base: String, unread: Boolean, runningCount: Int = 0): String
```

`runningCount` 取默认值，现有 `UnreadTitlePrefixTest` 的用例无需改动。前缀逻辑原样保留。

**后缀的幂等比前缀麻烦。** 前缀靠 `startsWith` 判断即可，因为 `✳ ` 是定值；后缀嵌了会变的数字，`（2 个运行中）` 的标题再装饰成 3 个会得到 `（2 个运行中）（3 个运行中）`。所以后缀必须**先剥离再追加**：用锚定行尾的正则剥掉任何 `（\d+ 个运行中）`。

正常路径下 `base` 来自 `super.getProjectTitle()` 本就干净，剥离永远空转。但平台会反复重算标题，现有代码已为前缀付了这份保险，后缀没理由不付。

### 5. 状态推送

`updateFrameTitle()` 目前只在 `markUnread` / `clearUnread` 后调用（`SessionMonitor.kt:315`、`:325`）。`runningChanged` 分支（`:412-415`）需补一次调用——那里已因同样理由调了 `updateOpenTabIcons()`，标题和标签图标依赖同一个信号，就该在同一处更新。平台不会在运行数变化那刻自发重算标题。

`runningCount(project)` 沿用 `hasUnread` 的防御：`isDisposed` 检查 + `getServiceIfCreated`。理由见 `UnreadFrameTitleBuilder.kt:37-42`——该 builder 对 IDE 里**所有**项目窗口生效，包括从没开过 AI 会话的项目，不能因为渲染标题就把服务连同轮询协程创建出来。

## 测试

纯函数 `decorate` 承担主要断言，沿用 `UnreadTitlePrefixTest` 的形式：

1. 四种状态组合各产出预期标题（对应行为规格表）。
2. `runningCount = 0` 时不出现括号。
3. 幂等：`decorate(decorate(x, false, 2), false, 2) == decorate(x, false, 2)`。
4. 计数变化：`decorate(decorate(x, false, 2), false, 3)` 只保留 `（3 个运行中）`，不叠加。
5. 前缀仍在开头：有未读时 `✳ ` 位于首位，不被后缀逻辑挪动。

`PluginXmlRegistrationTest` 断言改名后的类仍在 `plugin.xml` 注册。`PlatformApiAlignmentSourceTest.kt:152` 断言 `updateFrameTitle()` 出现 ≥3 次，新增一处后为 4 次，仍通过。
