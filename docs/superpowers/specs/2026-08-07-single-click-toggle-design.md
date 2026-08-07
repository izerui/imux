# 会话列表单击/双击打开的可配置开关

## 背景

`AgentSessionTree` 目前把左键单击写死为「打开/恢复会话」（`AgentSessionTree.kt:187-194`）。
用户希望像 IDEA 项目视图的 *Behavior → Open Files with Single Click* 那样，
自己决定是单击触发还是双击触发。

插件当前没有任何持久化设置类，也没有自建的工具窗口菜单，两者都需要新建。

## 目标

- 用户可以在会话列表的 `⋮` 菜单里勾选/取消「单击打开会话」
- 该偏好跨项目、跨重启生效
- 默认双击打开

## 非目标

- 不做 Settings 对话框里的 `Configurable` 页面。目前只有一个开关，
  放进 `⋮` 菜单已经够用；等设置项多起来再说。
- 不做树的右键菜单。插件现在根本没有右键菜单，为一个开关引入一套 popup 不划算。
- 不改 `handleActivate` 的任何行为。打开会话的逻辑保持原样。

## 设计

### 1. 设置存储：`settings/ImuxSettings.kt`

Application 级 `PersistentStateComponent`，`roamingType = RoamingType.DISABLED`
（这是本机手感偏好，不该跟着账号漫游）。

```kotlin
@Service(Service.Level.APP)
@State(name = "ImuxSettings", storages = [Storage("imux.xml", roamingType = RoamingType.DISABLED)])
class ImuxSettings : SimplePersistentStateComponent<ImuxSettings.State>(State()) {
    class State : BaseState() {
        var openWithSingleClick: Boolean by property(false)
    }
    companion object { fun getInstance(): ImuxSettings = service() }
}
```

不设监听/通知机制。树在每次点击时现读设置，改完立刻生效，没有缓存需要失效。

### 2. 点击判定：`AgentSessionTree.kt`

把写死的 `clickCount != 1` 换成读设置。判定逻辑抽成 companion 里的纯函数，
这样不起 Swing 也能测：

```kotlin
internal fun shouldActivate(singleClickMode: Boolean, clickCount: Int): Boolean =
    clickCount == if (singleClickMode) 1 else 2
```

`onClick` 里保留左键判断，然后调用它。

**需要在实现时验证的一点**：Swing 双击会派发两次事件（`clickCount` 先 1 后 2），
预期是双击模式下第一次被忽略、只留下 JTree 默认的选中行为，第二次才激活——
不需要额外的延时判定。但 `ClickListener` 对多击的合并策略要实际跑一遍确认。
若它不透传 `clickCount == 2`，退回用裸的 `MouseAdapter.mouseClicked` 处理双击分支
（双击不像单击那样受手指抖动困扰，`mouseClicked` 的同点约束在这里可以接受）。

Enter 键的激活路径（`AgentSessionTree.kt:197-203`）不动，两种模式下行为一致。

### 3. 菜单入口：`AgentToolWindowFactory.kt`

`doCreateContent` 里 `setTitleActions` 之后追加：

```kotlin
toolWindow.setAdditionalGearActions(DefaultActionGroup(ToggleSingleClickAction()))
```

`ToggleSingleClickAction` 是 `ToggleAction`（`DumbAware`），标题「单击打开会话」，
`isSelected` / `setSelected` 直接读写 `ImuxSettings`，`getActionUpdateThread` 返回 `BGT`。
它挂在 IDEA 工具窗口自带的 `⋮` 齿轮菜单上，视觉上与 IDEA 文件树的 Behavior 菜单一致。

## 测试

新增 `src/test/kotlin/.../toolwindow/ClickActivationTest.kt`，风格对齐现有的
`TreeRowHitTest.kt`（纯逻辑、无 Swing）。覆盖四个分支：

| singleClickMode | clickCount | 期望 |
|---|---|---|
| true | 1 | 激活 |
| true | 2 | 不激活 |
| false | 1 | 不激活 |
| false | 2 | 激活 |

设置类本身是平台样板，不单测。

## 影响面

新增两个文件，改动两个文件。不触碰 `SessionMonitor`、`TerminalHost`、
`SessionListModel` 或任何会话打开逻辑。行为变更只有一处：升级后默认从单击变双击。
