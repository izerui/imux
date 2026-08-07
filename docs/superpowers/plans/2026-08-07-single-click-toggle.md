# 会话列表单击/双击打开开关 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户在会话列表的 `⋮` 菜单里自选单击还是双击打开会话，偏好跨项目跨重启生效，默认双击。

**Architecture:** 新增一个 Application 级 `PersistentStateComponent` 存布尔开关；`AgentSessionTree` 的 `ClickListener` 每次点击时现读该设置，判定逻辑抽成顶层纯函数便于单测；开关的 UI 是挂在工具窗口自带 `⋮` 齿轮菜单上的一个 `ToggleAction`。

**Tech Stack:** Kotlin 2.3.21 / JVM 25，IntelliJ Platform Plugin SDK 2.18.1（IDEA 2026.2，`sinceBuild = 262`），Swing，JUnit 4。

## Global Constraints

- 语言 Kotlin，源码根 `src/main/kotlin/com/github/izerui/imux/`，测试根 `src/test/kotlin/com/github/izerui/imux/`
- 注释与用户可见文案一律中文，与现有代码一致
- 测试用 JUnit 4（`org.junit.Test` + `org.junit.Assert.*`），测试方法名用中文反引号命名，参照 `src/test/kotlin/com/github/izerui/imux/toolwindow/TreeRowHitTest.kt`
- 跑测试：`./gradlew test`
- `@Service` 注解的类**不需要**在 `plugin.xml` 里声明（现有 `SessionMonitor` 即是如此），不要去改 `plugin.xml`
- 设置项默认值必须是 `false`（默认双击打开）
- 不修改 `handleActivate`、`SessionMonitor`、`TerminalHost`、`SessionListModel`

---

### Task 1: 点击判定纯函数

把「什么样的点击算激活」从 Swing 事件处理里剥出来，成为可单测的纯函数。本任务不接任何线，只交付函数和它的测试。

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt`（在文件顶层的 `sessionStatusIcon` 之后、`enableRendererAnimation` 之前插入）
- Test: `src/test/kotlin/com/github/izerui/imux/toolwindow/ClickActivationTest.kt`（新建）

**Interfaces:**
- Consumes: 无
- Produces: `internal fun shouldActivate(singleClickMode: Boolean, clickCount: Int): Boolean` —— Task 2 的 `ClickListener` 会调用它

注：函数放在**文件顶层**而不是 companion 对象里，与同文件已有的 `pathForRowAt` / `limitCovering` / `sessionStatusIcon` 风格保持一致。

- [ ] **Step 1: 写失败的测试**

新建 `src/test/kotlin/com/github/izerui/imux/toolwindow/ClickActivationTest.kt`：

```kotlin
package com.github.izerui.imux.toolwindow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickActivationTest {

    @Test
    fun `单击模式下单击激活`() {
        assertTrue(shouldActivate(singleClickMode = true, clickCount = 1))
    }

    @Test
    fun `单击模式下双击的第二下不再激活`() {
        assertFalse(
            "双击会先后派发 clickCount 1 和 2，单击模式已在第一下激活过，第二下必须忽略，否则同一会话被打开两次",
            shouldActivate(singleClickMode = true, clickCount = 2),
        )
    }

    @Test
    fun `双击模式下单击不激活`() {
        assertFalse(shouldActivate(singleClickMode = false, clickCount = 1))
    }

    @Test
    fun `双击模式下双击激活`() {
        assertTrue(shouldActivate(singleClickMode = false, clickCount = 2))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew test --tests "com.github.izerui.imux.toolwindow.ClickActivationTest"`

Expected: 编译失败，报 `Unresolved reference: shouldActivate`。

- [ ] **Step 3: 写最小实现**

在 `AgentSessionTree.kt` 里 `sessionStatusIcon` 函数之后插入：

```kotlin
/**
 * 这次点击是否应该打开会话。
 *
 * 双击时 Swing 会先后派发 clickCount 为 1 和 2 的两次事件，所以两种模式都只认
 * 其中一次：单击模式认第一次，双击模式认第二次。双击模式下被放过的第一次
 * 仍会走 JTree 默认逻辑选中该行，正是想要的效果。
 */
internal fun shouldActivate(singleClickMode: Boolean, clickCount: Int): Boolean =
    clickCount == if (singleClickMode) 1 else 2
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew test --tests "com.github.izerui.imux.toolwindow.ClickActivationTest"`

Expected: 4 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt \
        src/test/kotlin/com/github/izerui/imux/toolwindow/ClickActivationTest.kt
git commit -m "feat: 抽出点击激活判定的纯函数"
```

---

### Task 2: 设置存储、树接线与 ⋮ 菜单开关

交付完整可用的功能：持久化设置 + 树读设置 + 菜单里能勾选。三者拆开都无法独立验收（没有设置类，开关无处可存；没有菜单项，设置改不了），所以合为一个任务。

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/settings/ImuxSettings.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt:187-194`（`init` 块里的 `ClickListener`）
- Modify: `src/main/kotlin/com/github/izerui/imux/toolwindow/AgentToolWindowFactory.kt`（`doCreateContent` 内 `setTitleActions` 之后；文件末尾追加 action 类）

**Interfaces:**
- Consumes: `shouldActivate(singleClickMode: Boolean, clickCount: Int): Boolean`（Task 1）
- Produces: `ImuxSettings.getInstance().state.openWithSingleClick: Boolean`（可读可写）

- [ ] **Step 1: 建设置类**

新建 `src/main/kotlin/com/github/izerui/imux/settings/ImuxSettings.kt`：

```kotlin
package com.github.izerui.imux.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * 插件的全局偏好。
 *
 * 应用级而非项目级：这是操作手感，不是项目配置，换个项目还要重设一遍很别扭。
 * 关掉漫游是同样的道理——手感跟着这台机器的输入设备走，不该跟着账号跑到别的机器上。
 */
@Service(Service.Level.APP)
@State(
    name = "ImuxSettings",
    storages = [Storage("imux.xml", roamingType = RoamingType.DISABLED)],
)
class ImuxSettings : SimplePersistentStateComponent<ImuxSettings.State>(State()) {

    class State : BaseState() {
        /** 单击即打开会话；false 表示需要双击。 */
        var openWithSingleClick: Boolean by property(false)
    }

    companion object {
        fun getInstance(): ImuxSettings = service()
    }
}
```

- [ ] **Step 2: 编译，确认设置类本身没问题**

Run: `./gradlew compileKotlin`

Expected: BUILD SUCCESSFUL。若报 `SimplePersistentStateComponent` 或 `property` 无法解析，检查 import 是否齐全。

- [ ] **Step 3: 树的点击判定改读设置**

在 `AgentSessionTree.kt` 顶部 import 区加入：

```kotlin
import com.github.izerui.imux.settings.ImuxSettings
```

把 `init` 块里的 `ClickListener`（原 187-194 行）整体替换为：

```kotlin
        object : ClickListener() {
            override fun onClick(event: MouseEvent, clickCount: Int): Boolean {
                if (!SwingUtilities.isLeftMouseButton(event)) return false
                val singleClickMode = ImuxSettings.getInstance().state.openWithSingleClick
                if (!shouldActivate(singleClickMode, clickCount)) return false
                val path = tree.pathForRowAt(event.y) ?: return false
                handleActivate(path)
                return true
            }
        }.installOn(tree)
```

设置是现读的，没有缓存，所以在菜单里改完下一次点击就生效。上方那段解释为何用 `ClickListener` 而非裸 `mouseClicked` 的注释保留不动。

- [ ] **Step 4: 加 ⋮ 菜单里的开关**

在 `AgentToolWindowFactory.kt` 的 import 区加入：

```kotlin
import com.github.izerui.imux.settings.ImuxSettings
import com.intellij.openapi.actionSystem.ToggleAction
```

在 `doCreateContent` 里 `toolWindow.setTitleActions(...)` 这段之后（原第 58 行 `)` 之后）插入：

```kotlin
        // 挂在工具窗口自带的 ⋮ 齿轮菜单上，与 IDEA 项目视图的 Behavior 菜单同一位置
        toolWindow.setAdditionalGearActions(DefaultActionGroup(ToggleSingleClickAction()))
```

在文件末尾（`RefreshAction` 类之后）追加：

```kotlin
private class ToggleSingleClickAction :
    ToggleAction("单击打开会话"), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(event: AnActionEvent): Boolean =
        ImuxSettings.getInstance().state.openWithSingleClick

    override fun setSelected(event: AnActionEvent, state: Boolean) {
        ImuxSettings.getInstance().state.openWithSingleClick = state
    }
}
```

`DefaultActionGroup` 与 `DumbAware`、`ActionUpdateThread`、`AnActionEvent` 该文件已经 import 过，不用重复加。

- [ ] **Step 5: 编译并跑全量测试**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL，含 Task 1 的 4 个测试在内全部通过，且原有测试（`AgentSessionTreeIconTest`、`TreeRowHitTest`、`RevealLimitTest`、`RelativeTimeTest`）无回归。

- [ ] **Step 6: 沙箱手动验证**

Run: `./gradlew runIde`

在沙箱 IDE 里打开 imux 工具窗口，依次确认：

1. 默认状态下**单击**会话只选中、不打开终端标签页；**双击**才打开
2. `⋮` 菜单里有「单击打开会话」且未勾选；勾上之后**单击**立即打开
3. 勾上后**双击**只打开一次，不会开出两个标签页，也不会打开两次同一会话
4. 两种模式下，选中一条后按 **Enter** 都能打开
5. 关掉沙箱 IDE 再 `./gradlew runIde` 重开，勾选状态仍在

**这一步是本计划唯一能验证的关键未知**：spec 里标注了 `ClickListener` 是否会把 `clickCount == 2` 透传给 `onClick` 未经证实。若第 1 条中双击无法打开，说明 `ClickListener` 把多击合并了或不透传 2，此时改用裸的 `MouseAdapter`：

```kotlin
        tree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                val singleClickMode = ImuxSettings.getInstance().state.openWithSingleClick
                if (!shouldActivate(singleClickMode, event.clickCount)) return
                val path = tree.pathForRowAt(event.y) ?: return
                handleActivate(path)
            }
        })
```

注意这是**退路而非首选**：`mouseClicked` 要求按下与抬起严格同点，单击模式下会退化成「点了没反应」——正是当初改用 `ClickListener` 要解决的问题。所以只在确认 `ClickListener` 不可行时才换，且换完必须重测第 1、2 条。若两者都不理想，停下来汇报，不要自行发明第三种方案。

- [ ] **Step 7: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/settings/ImuxSettings.kt \
        src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt \
        src/main/kotlin/com/github/izerui/imux/toolwindow/AgentToolWindowFactory.kt
git commit -m "feat: 会话列表支持自选单击或双击打开"
```

---

## 完成标准

- `./gradlew test` 全绿
- 沙箱里 Step 6 的五条逐条走过，其中第 3 条（双击不重复打开）和第 5 条（重启后保持）尤其容易被漏测
- 默认行为是双击打开
