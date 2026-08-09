# 未读标记同步到 IDE 窗口标题

## 背景

未读状态目前有两处呈现：编辑器标签页图标（`AgentIcons.forTab`，`AgentIcons.kt:67`）
和会话列表项的图标 + 粗体标题（`AgentSessionTree.kt:189,198`）。两者都在项目窗口**内部**。

用户在 macOS 上用「Merge All Windows」把多个项目窗口合并成标签栏，
标签上显示的是各窗口的 NSWindow title（`项目名 – 文件名`）。
切到别的项目时，看不出哪个项目有未读会话。

## 目标

项目有未读会话时，该窗口标题显示为 `✳ 项目名 – 文件名`；全部已读后标记自动消失。

## 非目标

- **不改 IDEA 新 UI 工具栏左上角那个 `imux ∨` widget**。它由 `main.toolbar.Project`
  这个 action 渲染，基类 `ExpandableComboAction` 是 `@ApiStatus.Internal`，
  只能靠 `action overrides` 硬顶，2026.x 升级容易碎。窗口标题这条路稳得多。
- **不做未读数量**。`✳3 imux` 的数字会频繁跳动，且标签宽度有限。有/无二值够用。
- **不做彩色标记**。NSWindow title 是纯字符串，既放不进 `Icon` 也无法着色。
  唯一能带颜色的是 emoji，但它在 macOS 窗口标签里渲染高度不一致，且与 IDEA
  图标风格不搭。用同形字符 + 跟随标题的文字颜色是这个位置能做到的上限。
- **不做 Dock 角标**（`AppIcon`）。它只在 IDE 非激活时可见，且是应用级而非窗口级，
  解决不了「多个项目窗口之间区分」这个问题。

## 设计

### 1. `frame/UnreadFrameTitleBuilder.kt`（新增）

NSWindow title 由 `com.intellij.openapi.wm.impl.FrameTitleBuilder` 生成。
它在平台里声明为 `open="true"` 且 serviceInterface / serviceImplementation 分离，
是留给插件的正规覆盖点：

```kotlin
class UnreadFrameTitleBuilder : PlatformFrameTitleBuilder() {
    override fun getProjectTitle(project: Project): String =
        decorate(super.getProjectTitle(project), hasUnread(project))

    companion object {
        const val UNREAD_PREFIX = "✳ "

        /** 纯函数，供测试直接调用 */
        internal fun decorate(base: String, unread: Boolean): String =
            if (unread && !base.startsWith(UNREAD_PREFIX)) "$UNREAD_PREFIX$base" else base
    }
}
```

`hasUnread(project)` 是文件内的私有函数，负责 dispose 检查与 service 查询。

`getFileTitle` / `getFileTitleAsync` 不覆盖，一律走父类。

前缀加在**项目名之前**而非末尾：窗口标签宽度有限，末尾会被省略号截掉。

**关键约束——必须用 `getServiceIfCreated`：**

```kotlin
project.getServiceIfCreated(SessionMonitor::class.java)?.hasUnread() == true
```

这个 builder 对 IDE 里**所有**项目窗口生效，包括从没开过 AI 会话的项目。
用 `SessionMonitor.getInstance(project)` 会在渲染标题的那一刻把 service
连同它的监控线程一起意外初始化。没创建过就当作无未读。

同理要防 `project.isDisposed`——标题可能在项目关闭过程中被重算。

`plugin.xml` 注册：

```xml
<applicationService serviceInterface="com.intellij.openapi.wm.impl.FrameTitleBuilder"
                    serviceImplementation="com.github.izerui.imux.frame.UnreadFrameTitleBuilder"
                    overrides="true"/>
```

### 2. `SessionMonitor.updateFrameTitle()`（新增私有方法）

只有 builder 还不够：它仅在平台自己重算标题时被调用（切文件、项目状态变化）。
未读状态刚变化的那一刻不会触发重算，标记要等到下次切文件才出现。

所以在 `markUnread`（`SessionMonitor.kt:308`）和 `clearUnread`（`:315`）里，
紧挨现有的 `updateOpenTabIcons(...)` 追加一次 `updateFrameTitle()`，
取 `WindowManager.getInstance().getIdeFrame(project)`，把 `getProjectTitle(project)`
的结果 `setFrameTitle(...)` 设回去。必须在 EDT 上执行。

**只需传项目名，不用自己拼文件名。** `ProjectFrameHelper` 把 `title` 与 `fileTitle`
分成两个字段存（反编译确认），`IdeFrame.setFrameTitle(String)` 只改前者，
文件名与分隔符仍由平台自己组合。

两者是双保险，职责分明：

| 机制 | 覆盖场景 |
|---|---|
| `UnreadFrameTitleBuilder` | 平台自发重算标题时前缀不丢 |
| `updateFrameTitle()` | 未读状态变化时立即上屏 |

会话 id 漂移时撤销旧 id 未读的路径（`SessionMonitor.kt:275`）走的也是
`clearUnread`，自动覆盖，无需额外改动。

### 3. 前缀常量

`UNREAD_PREFIX = "✳ "`（U+2733）。放在 `UnreadFrameTitleBuilder` 的 companion 里，
供实现和测试共用。

选星号而不是圆点，是为了与另外两处未读呈现同形：会话列表和标签角标用的
`AllIcons.General.Modified` 是**三条间隔 60° 的线构成的六辐星号**
（`general/modified.svg`，`#6E6E6E` / 深色 `#AFB1B3`）——不是彩色圆点。

没法直接复用那个 `Icon`：窗口标题只能是纯文本。但 `✳` 的颜色跟随标题文字，
深色主题下同样是浅灰，与图标的灰色语义正好对上。

`decorate` 里带 `startsWith` 判重，避免同一状态下多次重算叠加出 `✳ ✳ imux`。

## 风险与取舍

**`overrides="true"` 是全局唯一坑位。** 若用户装了另一个也覆盖 `FrameTitleBuilder`
的插件，会互相顶掉（后加载者赢）。这类插件极少见，且我们的实现在无未读时
完全等价于原生，冲突后果只是标记不显示，不会破坏标题本身。

**`FrameTitleBuilder` 位于 `openapi.wm.impl` 包**，属实现级 API。但类本身
未标 `@Internal`、声明为 `open="true"`、interface/impl 分离，是平台留出的覆盖点。
风险显著低于动主工具栏 widget。

**新 UI 下原生标题栏文字通常不可见。** 这个功能只在开启 macOS 窗口合并、
或看 Dock/任务栏/窗口列表/⌘` 切换时才体现价值。这正是用户的使用场景。

## 测试

新增 `src/test/kotlin/.../frame/UnreadTitlePrefixTest.kt`，风格对齐现有的
`TreeRowHitTest.kt`（纯逻辑、不起平台）。前缀决策抽成纯函数后覆盖：

| 输入 | 期望 |
|---|---|
| 有未读 | 带 `✳ ` 前缀 |
| 无未读 | 原样返回 |
| `SessionMonitor` 未创建 | 原样返回，且不触发 service 创建 |
| 项目已 dispose | 原样返回，不抛异常 |
| 已带前缀的标题 | 不重复叠加 |

另在 `PluginXmlRegistrationTest` 钉住 `overrides="true"` 注册，在
`PlatformApiAlignmentSourceTest` 钉住两条源码级约束（必须用 `getServiceIfCreated`、
`markUnread`/`clearUnread` 都要刷新标题）——这两类错误编译器和单测都碰不到。

标题真正上屏是平台行为，靠 sandbox IDE 手工验证：开两个项目窗口合并成标签，
在其中一个跑完一轮对话，确认另一个标签出现星号；切回去后星号消失。

## 影响面

新增两个文件（builder + 测试），改动四个文件（`plugin.xml`、`SessionMonitor.kt`
与两个既有的守护测试）。
不触碰 `TerminalHost`、`AgentIcons`、`AgentSessionTree` 或任何会话打开逻辑。
未读状态本身的语义、标记时机、清除时机全部沿用现状。
