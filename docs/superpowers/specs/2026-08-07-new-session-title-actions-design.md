# 新建会话：标题栏直接放 Agent 按钮

## 背景

新建会话是高频操作，但目前要点两次：先点标题栏的 `+`
（`NewSessionAction`，`AgentToolWindowFactory.kt:136-162`），弹出「选择 Agent」
popup，再从中挑 Claude Code 或 Codex。

这个 popup 只承载两个选项，却付出了完整的弹窗代价——窗口小、要瞄准、
遮挡会话列表。用户两个 agent 都常用、来回切换，所以「记住上次选择」
之类的默认值优化没有意义；真正该做的是把「选择」这一步整个去掉。

## 目标

- 新建任意 agent 的会话都只需一次点击
- 标题栏按钮随 `AgentType` 自动生成，新增 agent 时不必再改工具窗口代码

## 非目标

- 不改会话创建流程。`registerPending` → `openNew` → `refresh`
  （`AgentToolWindowFactory.kt:171-180`）一行不动。
- 不在会话树的 agent 分组行上加新建入口。标题栏按钮位置固定、发现性好，
  已经够用；分组行入口是「就近操作」的优化，等真的觉得鼠标要跑太远再说。
- 不加快捷键。插件目前没有 keymap 注册机制（`plugin.xml` 里没有 `<action>`），
  为这个改动引入一套不划算。

## 设计

### 1. 删除 `NewSessionAction`

整个类删掉，连同它的 `JBPopupFactory.createActionGroupPopup` 调用和
锚点定位逻辑（`:143-160`）。popup 的 `SPEEDSEARCH` 过滤能力随之消失——
两个选项本来也用不上，等 agent 多到 5 个以上再考虑回退到菜单形式。

### 2. `CreateAction` 提升为标题栏 action

`CreateAction` 目前只在 popup 内部当菜单项用，所以没有图标、
文案还得由调用方传入（`:147-148` 硬编码了 "Claude Code" / "Codex"，
和 `AgentType.displayName` 重复）。改为自带完整呈现信息：

```kotlin
private class CreateAction(private val agentType: AgentType) : DumbAwareAction(
    "新建 ${agentType.displayName} 会话",
    "新建一个 ${agentType.displayName} 会话",
    AgentIcons.forNewSession(agentType),
)
```

`actionPerformed` 保持原样。`label` 参数删除，文案统一由 `displayName` 派生，
消掉字面量重复。

### 3. 图标：agent logo 叠加号角标

`AgentIcons.forAgent` 返回的是纯品牌 logo（claude.png / codex.png）。
放进标题栏当按钮时，纯 logo 读起来像「筛选」而不是「新建」，
所以叠一个缩小的 `AllIcons.General.Add` 到右下角。

角标构造放在 `AgentIcons` 里，与 `forAgent` 并列，
让图标相关的知识集中在一处，工具窗口只管取用：

```kotlin
// AgentIcons.kt
fun forNewSession(agentType: AgentType): Icon =
    LayeredIcon(2).apply {
        setIcon(forAgent(agentType), 0)
        setIcon(IconUtil.scale(AllIcons.General.Add, null, 0.5f), 1, SwingConstants.SOUTH_EAST)
    }
```

`CreateAction` 的构造参数即 `AgentIcons.forNewSession(agentType)`。

**实现时需要实际跑一遍确认**：角标缩放后在 HiDPI 下是否发虚、
是否盖住 logo 的可辨识部分。若效果不佳，退回纯 logo + tooltip
表达语义（两个 agent logo 并排放在新建位置，语义其实已经不弱）。

### 4. 标题栏装配

```kotlin
toolWindow.setTitleActions(
    AgentType.entries.map { CreateAction(it) } + RefreshAction(),
)
```

由枚举驱动，将来 `AgentType` 加一项，标题栏自动多一个按钮。
最终标题栏为 `[✻+] [◎+] [↻]`，加上平台自带的 `[⋮] [—]`。
窗口窄到放不下时 IntelliJ 会自动折叠进 `»`，不需要特殊处理。

齿轮菜单的 `ToggleSingleClickAction`（`:63`）不动。

## 测试

不新增单元测试。这个改动是纯 UI 接线：创建逻辑未改动，
新增的两处逻辑（枚举映射、图标叠加）都是平台样板，单测价值低于维护成本。
项目现有测试（`TreeRowHitTest`、`ClickActivationTest`）覆盖的是纯判定函数，
这里没有同类产物。

验证方式是跑沙箱 IDE（`./gradlew runIde`）手动确认：

| 检查项 | 期望 |
|---|---|
| 点 Claude 按钮 | 直接开出 Claude Code 终端标签页，无弹窗 |
| 点 Codex 按钮 | 直接开出 Codex 终端标签页，无弹窗 |
| 悬停两个按钮 | tooltip 分别为「新建 Claude Code 会话」「新建 Codex 会话」 |
| 图标 | 能区分两个 agent，加号角标清晰不糊 |
| 拖窄工具窗口 | 按钮折叠进 `»`，功能仍可达 |

## 影响面

只改 `AgentToolWindowFactory.kt` 和 `AgentIcons.kt` 两个文件，不新增文件。
不触碰 `SessionMonitor`、`TerminalHost`、`SessionListModel`、`AgentType`
或任何会话打开逻辑。行为变更只有一处：新建入口从「一个 + 按钮 + 弹窗」
变成「每个 agent 一个按钮」。
