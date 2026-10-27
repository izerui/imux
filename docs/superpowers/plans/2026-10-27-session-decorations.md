# 会话装饰（手机连接提示）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把“这个会话连着手机”显示到用户看得见的地方：终端顶部连接提示条（带换绑、断开）、终端标签图标与会话树行上的手机标记、状态栏的连接数量，对应原型 `docs/prototypes/terminal-channel-bridge.html` 第 7 步之后的界面。

**Architecture:** 现有代码只新增一个中立扩展点 `com.github.izerui.imux.sessionDecorator`（接口 `terminal.SessionDecorator`）和一个应用级广播 `SessionDecorationsListener`；标签图标、会话树、终端编辑器只通过 `SessionDecorations` 门面询问“是否连着手机”“顶部要挂哪些横幅”，并在广播时刷新。桥接实现该扩展点（`bridge/ui/BridgeSessionDecorator`），提供提示条组件，并在机器人/绑定/状态变化时发布广播。状态栏组件完全在桥接内，通过平台扩展点注册。

**Tech Stack:** Kotlin 2.3、IntelliJ Platform 262（ExtensionPointName、MessageBus Topic、FileIconProvider、StatusBarWidgetFactory、JBPopupFactory、ActionLink）、JUnit 4。

**依据:** `docs/terminal-channel-bridge-feasibility.md` 第 16–18 节；第二份计划 `docs/superpowers/plans/2026-10-27-robot-management-wecom.md`（已完成）。

## Global Constraints

- 现有代码只允许以下改动：
  - 新增 `src/main/kotlin/com/github/izerui/imux/terminal/SessionDecorations.kt`；
  - `icons/AgentIcons.kt`：新增 `phone` 图标与 `forTab` 的 `phoneConnected` 参数（默认 `false`）；
  - `terminal/AgentTerminalFileIconProvider.kt`：询问 `SessionDecorations.phoneConnected`；
  - `terminal/AgentTerminalFileEditor.kt`：顶部改为可容纳多个横幅的竖直容器，并在广播时刷新本标签的图标；
  - `toolwindow/AgentSessionTree.kt`：会话行显示手机标记，并在广播时重载；
  - 新增 `src/main/resources/icons/phone.svg`、`phone_dark.svg`；
  - `plugin.xml`：新增 `extensionPoints`、桥接的扩展实现注册、`statusBarWidgetFactory`；
  - 10 个 `ImuxBundle*.properties` 只追加 `bridge.*` 键；
  - 测试：`PlatformApiAlignmentSourceTest` 中对 `AgentIcons.forTab(...)` 调用字符串的断言随签名更新，其余既有测试不改。
- 其余新代码全部在 `bridge/**`。`bridge/` 以外的源码仍不得引用 `com.github.izerui.imux.bridge`（`BridgeIsolationSourceTest`）。
- 现有代码对扩展的调用必须容错：任何装饰实现抛出的非取消异常都被吞下并记录，不得影响标签图标、会话树或终端编辑器。
- `SessionDecorationsListener.TOPIC` 只在 EDT 上发布；订阅方在回调里可以直接操作 Swing。
- 开关关闭时：所有装饰都视为“未连接”，提示条隐藏，状态栏文字为空；不得因为查询装饰而实例化 `BridgeHub`。
- 图标沿用现有配色：浅色 `#6C707E`、深色 `#CED0D6`，两份形状一致。
- 文案规则同前：无占位符不写 `''`；有占位符的单引号写成 `''`；英文、简体中文按本计划原文，其余 8 种语言忠实翻译。
- 测试命令：`timeout 590 ./gradlew test --tests '<pattern>'`；平台接线用 `SourceCode` 源码断言与纯函数测试。
- 每个提交信息末尾空一行加 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。

## File Structure

| 文件 | 职责 |
| --- | --- |
| Create `terminal/SessionDecorations.kt` | 扩展点接口 `SessionDecorator`、广播 `SessionDecorationsListener`、门面 `SessionDecorations`、容错工具 `collectSafely` |
| Modify `icons/AgentIcons.kt` | `phone` 图标；`forTab(..., phoneConnected)` 组合与缓存 |
| Modify `terminal/AgentTerminalFileIconProvider.kt` | 标签图标带手机标记 |
| Modify `terminal/AgentTerminalFileEditor.kt` | 顶部竖直容器挂 `peerBanner` 与装饰横幅；广播时刷新标签图标 |
| Modify `toolwindow/AgentSessionTree.kt` | `NodeData.Session.phoneConnected`；`withPhoneMarker`；广播时重载 |
| Create `resources/icons/phone.svg`、`phone_dark.svg` | 手机标记 |
| Modify `bridge/ui/PhoneActions.kt` | 抽出 `PhoneOperations.bind/disconnect`，动作与提示条共用 |
| Create `bridge/ui/PhoneBanner.kt` | 终端顶部连接提示条与纯函数模型 `phoneBannerModel` |
| Create `bridge/ui/BridgeSessionDecorator.kt` | 扩展点实现 |
| Create `bridge/ui/PhoneStatusWidgetFactory.kt` | 状态栏组件与纯函数 `statusBarText` |
| Modify `bridge/robot/RobotManager.kt` | `revision` 变化时在 EDT 发布 `SessionDecorations.changed()` |
| Modify `bridge/TerminalBridgeService.kt` | 重新挂接后 `syncBindings()`，使装饰刷新 |
| Modify `plugin.xml`、10 个 bundle | 注册与文案 |

（路径前缀 `src/main/kotlin/com/github/izerui/imux/`，测试对应 `src/test/kotlin/com/github/izerui/imux/`。）

---

### Task 1: 中立扩展点与装饰广播

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/terminal/SessionDecorations.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`（在第一个 `<extensions defaultExtensionNs="com.intellij">` 之前加入 `extensionPoints`）
- Test: `src/test/kotlin/com/github/izerui/imux/terminal/SessionDecorationsTest.kt`

**Interfaces:**
- Produces:
  - `interface SessionDecorator { fun isPhoneConnected(project: Project, tabId: String): Boolean; fun createBanner(project: Project, file: AgentTerminalVirtualFile, parent: Disposable): JComponent? }`
  - `fun interface SessionDecorationsListener : EventListener { fun decorationsChanged() }`，`SessionDecorationsListener.TOPIC`（`@Topic.AppLevel`）
  - `object SessionDecorations { fun phoneConnected(project, tabId): Boolean; fun banners(project, file, parent): List<JComponent>; fun changed() }`
  - `internal fun <R> collectSafely(decorators: List<SessionDecorator>, fallback: R, call: (SessionDecorator) -> R): List<R>`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.swing.JComponent
import kotlin.coroutines.cancellation.CancellationException

class SessionDecorationsTest {
    private class Fake(val value: Boolean, val fail: Boolean = false) : SessionDecorator {
        override fun isPhoneConnected(
            project: Project,
            tabId: String,
        ): Boolean = value

        override fun createBanner(
            project: Project,
            file: AgentTerminalVirtualFile,
            parent: Disposable,
        ): JComponent? = null

        fun ask(): Boolean {
            if (fail) throw IllegalStateException("boom")
            return value
        }
    }

    @Test
    fun `装饰实现抛异常时取回退值，其余照常`() {
        val results = collectSafely(listOf(Fake(true, fail = true), Fake(true), Fake(false)), false) { (it as Fake).ask() }
        assertEquals(listOf(false, true, false), results)
    }

    @Test
    fun `取消信号照常向上传递`() {
        val thrown =
            runCatching { collectSafely(listOf<SessionDecorator>(Fake(true)), false) { throw CancellationException("c") } }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `plugin xml 声明了会话装饰扩展点`() {
        val xml = File("src/main/resources/META-INF/plugin.xml").readText()
        assertTrue(xml.contains("qualifiedName=\"com.github.izerui.imux.sessionDecorator\""))
        assertTrue(xml.contains("interface=\"com.github.izerui.imux.terminal.SessionDecorator\""))
    }

    @Test
    fun `门面只经扩展点访问装饰，不认识具体功能`() {
        val code = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/SessionDecorations.kt").normalized
        assertTrue(code.contains("ExtensionPointName"))
        assertTrue(!code.contains("bridge"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `timeout 590 ./gradlew test --tests '*SessionDecorationsTest'`
Expected: 编译失败，`Unresolved reference: collectSafely`

- [ ] **Step 3: 实现 `SessionDecorations.kt`**

```kotlin
package com.github.izerui.imux.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.util.EventListener
import javax.swing.JComponent
import kotlin.coroutines.cancellation.CancellationException

/**
 * 其它功能给终端会话加的装饰：标签与会话树上的标记、终端顶部的横幅。
 *
 * 现有代码只认这个接口，不认识具体功能；实现通过扩展点
 * `com.github.izerui.imux.sessionDecorator` 注册。实现必须很快返回，
 * 因为标签图标和会话树会频繁询问。
 */
interface SessionDecorator {
    fun isPhoneConnected(
        project: Project,
        tabId: String,
    ): Boolean

    /** 终端顶部的横幅；不需要时返回 null。组件自己负责刷新，随 [parent] 一起释放。 */
    fun createBanner(
        project: Project,
        file: AgentTerminalVirtualFile,
        parent: Disposable,
    ): JComponent?
}

/** 装饰变化。只在 EDT 上发布，订阅方可直接操作 Swing。 */
fun interface SessionDecorationsListener : EventListener {
    fun decorationsChanged()

    companion object {
        @JvmField
        @Topic.AppLevel
        val TOPIC: Topic<SessionDecorationsListener> =
            Topic(SessionDecorationsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/** 现有代码访问装饰的唯一入口。任何实现出错都不影响标签、会话树和终端。 */
object SessionDecorations {
    private val EP_NAME = ExtensionPointName<SessionDecorator>("com.github.izerui.imux.sessionDecorator")

    fun phoneConnected(
        project: Project,
        tabId: String,
    ): Boolean = collectSafely(EP_NAME.extensionList, false) { it.isPhoneConnected(project, tabId) }.any { it }

    fun banners(
        project: Project,
        file: AgentTerminalVirtualFile,
        parent: Disposable,
    ): List<JComponent> = collectSafely(EP_NAME.extensionList, null) { it.createBanner(project, file, parent) }.filterNotNull()

    /** 由装饰实现在 EDT 上调用。 */
    fun changed() {
        val application = ApplicationManager.getApplication()
        if (application.isDisposed) return
        application.messageBus.syncPublisher(SessionDecorationsListener.TOPIC).decorationsChanged()
    }
}

internal fun <R> collectSafely(
    decorators: List<SessionDecorator>,
    fallback: R,
    call: (SessionDecorator) -> R,
): List<R> =
    decorators.map { decorator ->
        try {
            call(decorator)
        } catch (e: Exception) {
            if (e is CancellationException || e is ControlFlowException) throw e
            LOG.warn("会话装饰 ${decorator.javaClass.name} 出错，已忽略", e)
            fallback
        }
    }

private val LOG = logger<SessionDecorator>()
```

- [ ] **Step 4: 在 `plugin.xml` 声明扩展点**

在第一个 `<extensions defaultExtensionNs="com.intellij">` 之前加入：

```xml
    <!-- 会话装饰：其它功能给终端标签、会话树、终端顶部加的标记与横幅。现有代码只认这个接口。 -->
    <extensionPoints>
        <extensionPoint
                qualifiedName="com.github.izerui.imux.sessionDecorator"
                interface="com.github.izerui.imux.terminal.SessionDecorator"
                dynamic="true"/>
    </extensionPoints>
```

- [ ] **Step 5: 运行测试确认通过**

Run: `timeout 590 ./gradlew test --tests '*SessionDecorationsTest' --tests '*PluginXmlRegistrationTest' --tests '*BridgeIsolationSourceTest'`
Expected: 全部 PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/terminal/SessionDecorations.kt src/test/kotlin/com/github/izerui/imux/terminal/SessionDecorationsTest.kt src/main/resources/META-INF/plugin.xml
git commit -m "feat(terminal): 新增会话装饰扩展点与变化广播"
```

---

### Task 2: 手机图标与标签图标标记

**Files:**
- Create: `src/main/resources/icons/phone.svg`、`src/main/resources/icons/phone_dark.svg`
- Modify: `src/main/kotlin/com/github/izerui/imux/icons/AgentIcons.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileIconProvider.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt`（只加广播订阅）
- Modify: `src/test/kotlin/com/github/izerui/imux/PlatformApiAlignmentSourceTest.kt`（`forTab` 调用字符串）
- Test: `src/test/kotlin/com/github/izerui/imux/terminal/PhoneTabIconTest.kt`

**Interfaces:**
- Consumes: `SessionDecorations.phoneConnected`、`SessionDecorationsListener.TOPIC`（Task 1）
- Produces: `AgentIcons.phone: Icon`；`AgentIcons.forTab(agentType, running, unread, phoneConnected: Boolean = false)`；`terminalTabIcon(agentType, running, unread, phoneConnected: Boolean = false)`

标签图标从左到右：未读标记（有时）→ 品牌图标（忙碌时转圈）→ 手机标记（连着手机时）。手机标记放在品牌图标之后、标题之前，与原型一致；不连手机时不占位。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.intellij.ui.RowIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneTabIconTest {
    @Test
    fun `连着手机时多一格手机标记，且实例被缓存`() {
        val plain = terminalTabIcon(AgentType.CODEX, running = false, unread = true, phoneConnected = false)
        val phone = terminalTabIcon(AgentType.CODEX, running = false, unread = true, phoneConnected = true)
        assertNotSame(plain, phone)
        assertSame(phone, terminalTabIcon(AgentType.CODEX, running = false, unread = true, phoneConnected = true))
        assertTrue(phone is RowIcon)
        assertEquals(3, (phone as RowIcon).iconCount)
    }

    @Test
    fun `不连手机时与原来完全相同`() {
        assertSame(
            terminalTabIcon(AgentType.CLAUDE, running = false, unread = false),
            terminalTabIcon(AgentType.CLAUDE, running = false, unread = false, phoneConnected = false),
        )
    }

    @Test
    fun `手机图标提供深浅两份且形状一致`() {
        val light = File("src/main/resources/icons/phone.svg").readText()
        val dark = File("src/main/resources/icons/phone_dark.svg").readText()
        assertTrue(light.contains("#6C707E") && !light.contains("#CED0D6"))
        assertTrue(dark.contains("#CED0D6") && !dark.contains("#6C707E"))
        assertEquals(light.replace("#6C707E", "C"), dark.replace("#CED0D6", "C"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `timeout 590 ./gradlew test --tests '*PhoneTabIconTest'`
Expected: 编译失败（`terminalTabIcon` 没有 `phoneConnected` 参数）

- [ ] **Step 3: 新增图标**

`phone.svg`：

```xml
<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
  <rect x="4.5" y="1.5" width="7" height="13" rx="1.5" stroke="#6C707E"/>
  <path d="M7 12H9" stroke="#6C707E" stroke-linecap="round"/>
</svg>
```

`phone_dark.svg`：同上，把两处 `#6C707E` 换成 `#CED0D6`。

- [ ] **Step 4: 修改 `AgentIcons.kt`**

在 `pi` 之后加入：

```kotlin
    /** 会话连着手机。见 [forTab]。 */
    val phone: Icon = IconLoader.getIcon("/icons/phone.svg", javaClass)
```

`TabIconKey` 增加字段 `val phoneConnected: Boolean`；`forTab` 改为：

```kotlin
    fun forTab(
        agentType: AgentType,
        running: Boolean,
        unread: Boolean,
        phoneConnected: Boolean = false,
    ): Icon =
        tabIcons.getOrPut(TabIconKey(agentType, running, unread, phoneConnected)) {
            val brand = if (running) busy(agentType) else forAgent(agentType)
            val parts = listOfNotNull(AllIcons.General.Modified.takeIf { unread }, brand, phone.takeIf { phoneConnected })
            if (parts.size == 1) {
                brand
            } else {
                RowIcon(parts.size).apply { parts.forEachIndexed { index, icon -> setIcon(icon, index) } }
            }
        }
```

在 `forTab` 的 KDoc 末尾补一段：“连着手机时在品牌图标之后再加一格手机标记，图标由会话装饰扩展提供的状态决定，见 `SessionDecorations`。”

- [ ] **Step 5: 修改 `AgentTerminalFileIconProvider.kt`**

`getIcon` 中把调用改为：

```kotlin
        return terminalTabIcon(
            agentType = terminalFile.agentType,
            running = monitor?.runningIds?.contains(terminalFile.sessionKey) == true,
            unread = monitor?.isUnread(terminalFile.sessionKey) == true,
            phoneConnected = project != null && !project.isDisposed && SessionDecorations.phoneConnected(project, terminalFile.tabId),
        )
```

`terminalTabIcon` 改为：

```kotlin
internal fun terminalTabIcon(
    agentType: AgentType,
    running: Boolean,
    unread: Boolean,
    phoneConnected: Boolean = false,
): Icon = AgentIcons.forTab(agentType, running, unread, phoneConnected)
```

- [ ] **Step 6: 修改 `AgentTerminalFileEditor.kt`（只加订阅）**

在 `init` 中现有 `ApplicationActivationListener` 订阅之前加入：

```kotlin
        // 会话装饰变化（例如连上或断开手机）时，让平台重新取本标签的图标。
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            SessionDecorationsListener.TOPIC,
            SessionDecorationsListener {
                if (!disposed && !project.isDisposed) FileEditorManager.getInstance(project).updateFilePresentation(virtualFile)
            },
        )
```

- [ ] **Step 7: 更新 `PlatformApiAlignmentSourceTest`**

把 `assertTrue(provider.contains("AgentIcons.forTab(agentType, running, unread)"))` 改为 `assertTrue(provider.contains("AgentIcons.forTab(agentType, running, unread, phoneConnected)"))`，保留旁边的注释。

- [ ] **Step 8: 运行测试确认通过**

Run: `timeout 590 ./gradlew test --tests '*PhoneTabIconTest' --tests '*AgentTerminalFileIconProviderTest' --tests '*PlatformApiAlignmentSourceTest' --tests '*IconResourceTest' --tests '*BridgeIsolationSourceTest'`
Expected: 全部 PASS

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/icons/phone.svg src/main/resources/icons/phone_dark.svg src/main/kotlin/com/github/izerui/imux/icons/AgentIcons.kt src/main/kotlin/com/github/izerui/imux/terminal src/test/kotlin/com/github/izerui/imux
git commit -m "feat(terminal): 终端标签图标显示手机连接标记"
```

---

### Task 3: 会话树手机标记

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/toolwindow/SessionTreePhoneMarkerTest.kt`

**Interfaces:**
- Consumes: `SessionDecorations.phoneConnected`、`SessionDecorationsListener.TOPIC`（Task 1）；`AgentIcons.phone`（Task 2）；`TerminalHost.openTabsByTabId()`
- Produces: `internal fun withPhoneMarker(icon: Icon?, phoneConnected: Boolean): Icon?`；`NodeData.Session.phoneConnected`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.SourceCode
import com.github.izerui.imux.icons.AgentIcons
import com.intellij.icons.AllIcons
import com.intellij.ui.RowIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTreePhoneMarkerTest {
    @Test
    fun `未连手机时图标不变`() {
        assertSame(AllIcons.Ide.GrayDot, withPhoneMarker(AllIcons.Ide.GrayDot, phoneConnected = false))
        assertNull(withPhoneMarker(null, phoneConnected = true))
    }

    @Test
    fun `连着手机时在状态图标后追加手机标记`() {
        val icon = withPhoneMarker(AllIcons.Ide.GrayDot, phoneConnected = true) as RowIcon
        assertEquals(2, icon.iconCount)
        assertSame(AllIcons.Ide.GrayDot, icon.getIcon(0))
        assertSame(AgentIcons.phone, icon.getIcon(1))
    }

    @Test
    fun `会话树在装饰变化时重载，并按标签询问手机连接`() {
        val tree = SourceCode("src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt").normalized
        assertTrue(tree.contains("SessionDecorationsListener.TOPIC"))
        assertTrue(tree.contains("SessionDecorations.phoneConnected(project,"))
        assertTrue(tree.contains("withPhoneMarker("))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `timeout 590 ./gradlew test --tests '*SessionTreePhoneMarkerTest'`
Expected: 编译失败，`Unresolved reference: withPhoneMarker`

- [ ] **Step 3: 修改 `AgentSessionTree.kt`**

1. 在 `sessionStatusIcon` 之后加入：

```kotlin
/** 连着手机的会话在状态图标后多一格手机标记，与终端标签上的标记一致。 */
internal fun withPhoneMarker(
    icon: Icon?,
    phoneConnected: Boolean,
): Icon? {
    if (!phoneConnected || icon == null) return icon
    return RowIcon(2).apply {
        setIcon(icon, 0)
        setIcon(AgentIcons.phone, 1)
    }
}
```

2. `NodeData.Session` 增加最后一个字段：

```kotlin
        /** 是否连着手机（由会话装饰扩展提供） */
        val phoneConnected: Boolean = false,
```

3. `nodesFor` 中，`val openTabs = ...` 之后加入：

```kotlin
        // 装饰按标签询问；会话 key → 标签 id 只对已打开的会话成立
        val tabIdBySession = TerminalHost.getInstance(project).openTabsByTabId().entries.associate { (tabId, key) -> key to tabId }
```

并在构造 `NodeData.Session` 时加入参数：

```kotlin
                            phoneConnected =
                                tabIdBySession[entry.session.id]?.let { SessionDecorations.phoneConnected(project, it) } == true,
```

4. 渲染器中 `is NodeData.Session ->` 分支改为：

```kotlin
                is NodeData.Session -> {
                    withPhoneMarker(sessionStatusIcon(session!!.running, session.unread, session.opened), session.phoneConnected)
                }
```

5. 在构造时 `monitor.addListener(parentDisposable) { reload() }` 之后加入：

```kotlin
        ApplicationManager.getApplication().messageBus.connect(parentDisposable)
            .subscribe(SessionDecorationsListener.TOPIC, SessionDecorationsListener { reload() })
```

补齐 import：`com.github.izerui.imux.terminal.SessionDecorations`、`com.github.izerui.imux.terminal.SessionDecorationsListener`、`com.intellij.ui.RowIcon`、`com.github.izerui.imux.icons.AgentIcons`（如未导入）、`com.intellij.openapi.application.ApplicationManager`（如未导入）。

- [ ] **Step 4: 运行测试确认通过**

Run: `timeout 590 ./gradlew test --tests '*SessionTreePhoneMarkerTest' --tests '*AgentSessionTreeIconTest' --tests '*BridgeIsolationSourceTest'`，再运行 `timeout 590 ./gradlew test` 一次
Expected: 全部 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt src/test/kotlin/com/github/izerui/imux/toolwindow/SessionTreePhoneMarkerTest.kt
git commit -m "feat(toolwindow): 会话树显示手机连接标记"
```

---

### Task 4: 终端顶部可挂多个横幅

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt`（只改 `createEditorComponent` 的组装）
- Test: `src/test/kotlin/com/github/izerui/imux/terminal/TerminalBannerHostSourceTest.kt`

**Interfaces:**
- Consumes: `SessionDecorations.banners(project, file, parent)`（Task 1）

现有 `peerBanner` 保持原样，放在最上面；装饰横幅依次放在它下面。横幅随本编辑器实例释放（拖动、分屏重建时各自重建）。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBannerHostSourceTest {
    private val editor = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt").normalized

    @Test
    fun `终端顶部同时容纳原有横幅与装饰横幅`() {
        assertTrue(editor.contains("SessionDecorations.banners(project, virtualFile, this@AgentTerminalFileEditor)"))
        assertTrue(editor.contains("add(peerBanner)"))
        assertTrue(editor.contains("BorderLayout.NORTH"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `timeout 590 ./gradlew test --tests '*TerminalBannerHostSourceTest'`
Expected: FAIL

- [ ] **Step 3: 修改 `createEditorComponent` 的返回值**

把

```kotlin
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(peerBanner, BorderLayout.NORTH)
            add(terminalLayer, BorderLayout.CENTER)
        }
```

改为

```kotlin
        // 顶部依次是结对编程横幅与其它功能的装饰横幅（例如手机连接提示）。
        val banners =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(peerBanner)
                SessionDecorations.banners(project, virtualFile, this@AgentTerminalFileEditor).forEach(::add)
                components.forEach { (it as? JComponent)?.alignmentX = Component.LEFT_ALIGNMENT }
            }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(banners, BorderLayout.NORTH)
            add(terminalLayer, BorderLayout.CENTER)
        }
```

补齐 import：`java.awt.Component`（如未导入）。`BoxLayout`、`JComponent`、`JPanel` 已在文件中使用。

- [ ] **Step 4: 运行测试确认通过**

Run: `timeout 590 ./gradlew test --tests '*TerminalBannerHostSourceTest' --tests '*PlatformApiAlignmentSourceTest' --tests '*TerminalIntegrationSourceTest'`，再运行 `timeout 590 ./gradlew test` 一次
Expected: 全部 PASS。若现有源码测试断言了 `add(peerBanner, BorderLayout.NORTH)` 原样字符串，按新结构更新为等价断言（`add(peerBanner)` 与 `add(banners, BorderLayout.NORTH)`），并在报告中说明。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt src/test/kotlin/com/github/izerui/imux
git commit -m "feat(terminal): 终端顶部支持挂载会话装饰横幅"
```

---

### Task 5: 桥接的装饰实现、连接提示条与变化广播

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneActions.kt`（抽出 `PhoneOperations`）
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneBanner.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/ui/BridgeSessionDecorator.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/robot/RobotManager.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/TerminalBridgeService.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: 10 个 `ImuxBundle*.properties`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/ui/PhoneBannerTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/ui/BridgeDecorationsSourceTest.kt`

**Interfaces:**
- Consumes: `SessionDecorator`、`SessionDecorations.changed`、`SessionDecorationsListener`（Task 1）；`AgentIcons.phone`（Task 2）；`BridgeHub`、`BridgeSettings`、`RobotManager`（`robot`、`statusOf`、`revision`、`syncBindings`）、`phoneMenuModel`、`BundleMenuLabels`、`channelLabel`、`statusLabel`（第二份计划）
- Produces:
  - `internal object PhoneOperations { fun bind(project: Project, file: AgentTerminalVirtualFile, robotId: String); fun disconnect(project: Project, robotId: String) }`（逻辑从 `BindRobotAction`、`DisconnectPhoneAction` 原样搬出，两个动作改为调用它）
  - `data class PhoneBannerModel(val robotId: String, val channel: String, val robotName: String, val problem: String?)`
  - `fun phoneBannerModel(enabled: Boolean, binding: Binding?, robot: RobotRecord?, status: ConnectionStatus?, channel: (ChannelKind) -> String, statusText: (ConnectionStatus?) -> String): PhoneBannerModel?`（未开启、未绑定或机器人不存在 → null；状态不是 Online → `problem = statusText(status)`）
  - `class PhoneBanner(project, file, parent) : JPanel`
  - `class BridgeSessionDecorator : SessionDecorator`

提示条（原型第 7 步）：手机图标 + “已连接手机 · 企业微信「开发助手 1」” + 灰色说明“手机消息会直接提交到这个终端，回复同步到手机”；不在线时追加“（已被其它客户端接管）”之类的状态；右侧两个链接「换绑」「断开」。「换绑」弹出与右键菜单相同的机器人列表（`phoneMenuModel`），选择后调用 `PhoneOperations.bind`；「断开」调用 `PhoneOperations.disconnect`。提示条订阅 `SessionDecorationsListener.TOPIC` 刷新；未连接时隐藏。

变化广播：`RobotManager` 在 `init` 中收集 `revision`，每次变化用 `invokeLater(ModalityState.any())` 调用 `SessionDecorations.changed()`；`TerminalBridgeService` 在重新挂接（`TabChange.Reattach`）后调用 `RobotManager.getInstance().syncBindings()`，保证标记随之刷新。

- [ ] **Step 1: 写失败测试**

`PhoneBannerTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.bridge.robot.ChannelKind
import com.github.izerui.imux.bridge.robot.RobotRecord
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneBannerTest {
    private val robot = RobotRecord("r", "开发助手 1", ChannelKind.WECOM, ownerSenderId = "u", ownerChatId = "u")
    private val binding = Binding(RobotId("r"), SessionTarget("/p", "t", AgentType.CODEX, "s"), 1)

    private fun model(
        enabled: Boolean = true,
        binding: Binding? = this.binding,
        robot: RobotRecord? = this.robot,
        status: ConnectionStatus? = ConnectionStatus.Online,
    ) = phoneBannerModel(enabled, binding, robot, status, channel = { it.id }, statusText = { "S:$it" })

    @Test
    fun `在线时只显示渠道与名称`() {
        assertEquals(PhoneBannerModel("r", "wecom", "开发助手 1", null), model())
    }

    @Test
    fun `不在线时带上状态`() {
        assertEquals("S:Kicked", model(status = ConnectionStatus.Kicked)!!.problem)
    }

    @Test
    fun `未开启、未绑定或机器人不存在时不显示`() {
        assertNull(model(enabled = false))
        assertNull(model(binding = null))
        assertNull(model(robot = null))
    }
}
```

`BridgeDecorationsSourceTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BridgeDecorationsSourceTest {
    private val decorator = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/BridgeSessionDecorator.kt").normalized
    private val manager = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/robot/RobotManager.kt").normalized
    private val service = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/TerminalBridgeService.kt").normalized
    private val banner = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneBanner.kt").normalized
    private val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()

    @Test
    fun `开关关闭时不查询中枢`() {
        assertTrue(decorator.contains("BridgeSettings.getInstance().state.enabled &&"))
    }

    @Test
    fun `机器人、状态或绑定变化时在 EDT 广播装饰变化`() {
        assertTrue(manager.contains("SessionDecorations.changed()"))
        assertTrue(manager.contains("ModalityState.any()"))
    }

    @Test
    fun `重新挂接后同步，标记随之刷新`() {
        assertTrue(service.contains("TabChange.Reattach"))
        assertTrue(service.contains("RobotManager.getInstance().syncBindings()"))
    }

    @Test
    fun `提示条订阅装饰变化并复用绑定操作`() {
        assertTrue(banner.contains("SessionDecorationsListener.TOPIC"))
        assertTrue(banner.contains("PhoneOperations.bind("))
        assertTrue(banner.contains("PhoneOperations.disconnect("))
    }

    @Test
    fun `注册了装饰实现`() {
        assertTrue(pluginXml.contains("<extensions defaultExtensionNs=\"com.github.izerui.imux\">"))
        assertTrue(pluginXml.contains("com.github.izerui.imux.bridge.ui.BridgeSessionDecorator"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `timeout 590 ./gradlew test --tests '*PhoneBannerTest' --tests '*BridgeDecorationsSourceTest'`
Expected: 编译失败，`Unresolved reference: phoneBannerModel`

- [ ] **Step 3: 抽出 `PhoneOperations`（`PhoneActions.kt` 内）**

把 `BindRobotAction.actionPerformed` 中从“取机器人”到“弹通知”的全部逻辑原样移到：

```kotlin
internal object PhoneOperations {
    /** 把机器人绑到这个终端：需要时确认替换，通知旧会话断开、新会话已连接，并持久化。 */
    fun bind(
        project: Project,
        file: AgentTerminalVirtualFile,
        robotId: String,
    ) {
        // （原 BindRobotAction.actionPerformed 中 project/file 解析之后的全部语句，原样搬入）
    }

    /** 断开这个机器人的会话连接，通知手机并持久化。 */
    fun disconnect(
        project: Project,
        robotId: String,
    ) {
        // （原 DisconnectPhoneAction.actionPerformed 中 project 解析之后的全部语句，原样搬入）
    }
}
```

`BindRobotAction.actionPerformed` 改为：`val project = e.project ?: return; val file = terminalFile(e) ?: return; PhoneOperations.bind(project, file, robotId)`；`DisconnectPhoneAction.actionPerformed` 改为：`val project = e.project ?: return; PhoneOperations.disconnect(project, robotId)`。不改变任何行为；`PhoneActionsSourceTest` 断言的字符串都仍在 `PhoneActions.kt` 中。

- [ ] **Step 4: 实现 `PhoneBanner.kt`**

```kotlin
package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.BridgeLifecycle
import com.github.izerui.imux.bridge.BridgeSettings
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.robot.ChannelKind
import com.github.izerui.imux.bridge.robot.RobotManager
import com.github.izerui.imux.bridge.robot.RobotRecord
import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.SessionDecorationsListener
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

data class PhoneBannerModel(
    val robotId: String,
    val channel: String,
    val robotName: String,
    val problem: String?,
)

fun phoneBannerModel(
    enabled: Boolean,
    binding: Binding?,
    robot: RobotRecord?,
    status: ConnectionStatus?,
    channel: (ChannelKind) -> String,
    statusText: (ConnectionStatus?) -> String,
): PhoneBannerModel? {
    if (!enabled || binding == null || robot == null) return null
    val problem = if (status == ConnectionStatus.Online) null else statusText(status)
    return PhoneBannerModel(robot.id, channel(robot.kind), robot.name, problem)
}

/** 终端顶部的手机连接提示条（原型第 7 步）。只在本标签连着手机时显示。 */
class PhoneBanner(
    private val project: Project,
    private val file: AgentTerminalVirtualFile,
    parent: Disposable,
) : JPanel() {
    private val title = JBLabel(AgentIcons.phone)
    private val hint = JBLabel(ImuxBundle.message("bridge.banner.hint")).apply { foreground = UIUtil.getContextHelpForeground() }
    private val rebind = ActionLink(ImuxBundle.message("bridge.banner.rebind")) { showRebindPopup() }
    private val disconnect = ActionLink(ImuxBundle.message("bridge.banner.disconnect")) { model?.let { PhoneOperations.disconnect(project, it.robotId) } }
    private var model: PhoneBannerModel? = null

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty(3, 12, 3, 24)
        isOpaque = true
        background = JBUI.CurrentTheme.Banner.SUCCESS_BACKGROUND
        add(title)
        add(Box.createHorizontalStrut(JBUI.scale(10)))
        add(hint)
        add(Box.createHorizontalGlue())
        add(rebind)
        add(Box.createHorizontalStrut(JBUI.scale(12)))
        add(disconnect)
        ApplicationManager.getApplication().messageBus.connect(parent)
            .subscribe(SessionDecorationsListener.TOPIC, SessionDecorationsListener { refresh() })
        refresh()
    }

    private fun refresh() {
        val enabled = BridgeSettings.getInstance().state.enabled
        val next =
            if (!enabled) {
                null
            } else {
                val binding = BridgeHub.getInstance().bindings.bindingForTab(file.tabId)
                val manager = RobotManager.getInstance()
                val robot = binding?.let { manager.robot(it.robotId.value) }
                phoneBannerModel(enabled, binding, robot, robot?.let { manager.statusOf(it.id) }, ::channelLabel) { statusLabel(it, paired = true) }
            }
        model = next
        isVisible = next != null
        if (next != null) {
            val text = ImuxBundle.message("bridge.banner.connected", next.channel, next.robotName)
            title.text = if (next.problem == null) text else text + " " + ImuxBundle.message("bridge.banner.status", next.problem)
        }
        revalidate()
        repaint()
    }

    private fun showRebindPopup() {
        val hub = BridgeHub.getInstance()
        val group = DefaultActionGroup()
        phoneMenuModel(
            RobotManager.getInstance().robots(),
            bindingOf = { hub.bindings.bindingOf(com.github.izerui.imux.bridge.core.RobotId(it)) },
            thisTabId = file.tabId,
            titleOf = BridgeLifecycle::sessionTitle,
            labels = BundleMenuLabels,
        ).filterIsInstance<PhoneMenuEntry.Robot>().forEach { entry ->
            group.add(
                object : DumbAwareAction(entry.label) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT

                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = entry.enabled
                    }

                    override fun actionPerformed(e: AnActionEvent) = PhoneOperations.bind(project, file, entry.robotId)
                },
            )
        }
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, DataManager.getInstance().getDataContext(rebind), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false)
            .showUnderneathOf(rebind)
    }
}
```

（`RobotId` 用全限定名只为避免与同包同名类型混淆；实现时可改为 import。若 262 上 `JBUI.CurrentTheme.Banner.SUCCESS_BACKGROUND` 不存在，改用 `INFO_BACKGROUND` 并在报告说明。）

- [ ] **Step 5: 实现 `BridgeSessionDecorator.kt` 并注册**

```kotlin
package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.BridgeSettings
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.SessionDecorator
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import javax.swing.JComponent

/** 手机连接的会话装饰。开关关闭时一律“未连接”，且不实例化中枢。 */
class BridgeSessionDecorator : SessionDecorator {
    override fun isPhoneConnected(
        project: Project,
        tabId: String,
    ): Boolean = BridgeSettings.getInstance().state.enabled && BridgeHub.getInstance().bindings.bindingForTab(tabId) != null

    override fun createBanner(
        project: Project,
        file: AgentTerminalVirtualFile,
        parent: Disposable,
    ): JComponent = PhoneBanner(project, file, parent)
}
```

`plugin.xml` 在 `</extensions>`（`com.intellij` 那一组）之后加入：

```xml
    <extensions defaultExtensionNs="com.github.izerui.imux">
        <sessionDecorator implementation="com.github.izerui.imux.bridge.ui.BridgeSessionDecorator"/>
    </extensions>
```

- [ ] **Step 6: 变化广播**

`RobotManager.init` 末尾加入：

```kotlin
        // 机器人、状态或绑定变化时，让标签、会话树、提示条、状态栏刷新
        scope.launch {
            revision.collect {
                ApplicationManager.getApplication().invokeLater({ SessionDecorations.changed() }, ModalityState.any())
            }
        }
```

（import `com.github.izerui.imux.terminal.SessionDecorations`、`com.intellij.openapi.application.ModalityState`。）

`TerminalBridgeService.onSessionsChanged` 的 `is TabChange.Reattach ->` 分支，在重新挂接之后加入 `RobotManager.getInstance().syncBindings()`。

- [ ] **Step 7: 追加文案（10 个文件）**

英文：

```properties
bridge.banner.connected=Connected to phone · {0} "{1}"
bridge.banner.hint=Phone messages are submitted to this terminal, and replies are synced to the phone.
bridge.banner.status=({0})
bridge.banner.rebind=Switch
bridge.banner.disconnect=Disconnect
bridge.statusbar.name=Phone Bridge
bridge.statusbar.connected={0} session(s) connected to phone
bridge.statusbar.idle=Phone bot idle
bridge.statusbar.tooltip=Open phone bridge settings
```

简体中文：

```properties
bridge.banner.connected=已连接手机 · {0}「{1}」
bridge.banner.hint=手机消息会直接提交到这个终端，回复同步到手机。
bridge.banner.status=（{0}）
bridge.banner.rebind=换绑
bridge.banner.disconnect=断开
bridge.statusbar.name=手机连接
bridge.statusbar.connected={0} 个会话已连接手机
bridge.statusbar.idle=手机机器人空闲
bridge.statusbar.tooltip=打开通讯机器人设置
```

其余 8 种语言忠实翻译，遵守撇号规则。（状态栏的键供 Task 6 使用，在这里一并加入。）

- [ ] **Step 8: 运行测试确认通过**

Run: `timeout 590 ./gradlew test --tests '*PhoneBannerTest' --tests '*BridgeDecorationsSourceTest' --tests '*PhoneActionsSourceTest' --tests '*ImuxBundleTest' --tests '*PluginXmlRegistrationTest'`，然后 `timeout 590 ./gradlew compileKotlin`，再 `timeout 590 ./gradlew test` 一次
Expected: 全部 PASS，编译无错误

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge src/test/kotlin/com/github/izerui/imux/bridge src/main/resources
git commit -m "feat(bridge): 终端顶部显示手机连接提示条，并在变化时刷新会话装饰"
```

---

### Task 6: 状态栏组件

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneStatusWidgetFactory.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`（`com.intellij` 扩展组内）
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/ui/PhoneStatusWidgetTest.kt`

**Interfaces:**
- Consumes: `SessionDecorationsListener.TOPIC`（Task 1）；`BridgeSettings`、`BridgeHub`、`RobotManager`、`RobotsConfigurable`
- Produces: `fun statusBarText(enabled: Boolean, robotCount: Int, boundHere: Int, connected: (Int) -> String, idle: String): String`；`class PhoneStatusWidgetFactory : StatusBarWidgetFactory`（id `imux.phoneBridge`）

文字规则：开关关闭或没有机器人 → 空字符串（不显示）；本项目有绑定 → “N 个会话已连接手机”；否则 → “手机机器人空闲”。点击打开通讯机器人设置。广播时刷新。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneStatusWidgetTest {
    private fun text(
        enabled: Boolean,
        robots: Int,
        bound: Int,
    ) = statusBarText(enabled, robots, bound, connected = { "C$it" }, idle = "IDLE")

    @Test
    fun `关闭或没有机器人时不显示`() {
        assertEquals("", text(enabled = false, robots = 2, bound = 1))
        assertEquals("", text(enabled = true, robots = 0, bound = 0))
    }

    @Test
    fun `显示本项目连接数或空闲`() {
        assertEquals("C2", text(enabled = true, robots = 3, bound = 2))
        assertEquals("IDLE", text(enabled = true, robots = 1, bound = 0))
    }

    @Test
    fun `注册了状态栏组件并在装饰变化时刷新`() {
        val xml = File("src/main/resources/META-INF/plugin.xml").readText()
        assertTrue(xml.contains("com.github.izerui.imux.bridge.ui.PhoneStatusWidgetFactory"))
        val code = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneStatusWidgetFactory.kt").normalized
        assertTrue(code.contains("SessionDecorationsListener.TOPIC"))
        assertTrue(code.contains("updateWidget("))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `timeout 590 ./gradlew test --tests '*PhoneStatusWidgetTest'`
Expected: 编译失败，`Unresolved reference: statusBarText`

- [ ] **Step 3: 实现 `PhoneStatusWidgetFactory.kt`**

```kotlin
package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.BridgeSettings
import com.github.izerui.imux.bridge.robot.RobotManager
import com.github.izerui.imux.terminal.SessionDecorationsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

fun statusBarText(
    enabled: Boolean,
    robotCount: Int,
    boundHere: Int,
    connected: (Int) -> String,
    idle: String,
): String =
    when {
        !enabled || robotCount == 0 -> ""
        boundHere > 0 -> connected(boundHere)
        else -> idle
    }

class PhoneStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID

    override fun getDisplayName(): String = ImuxBundle.message("bridge.statusbar.name")

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = PhoneStatusWidget(project)

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val ID = "imux.phoneBridge"
    }
}

private class PhoneStatusWidget(
    private val project: Project,
) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var statusBar: StatusBar? = null

    override fun ID(): String = PhoneStatusWidgetFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(SessionDecorationsListener.TOPIC, SessionDecorationsListener { statusBar.updateWidget(ID()) })
    }

    override fun getText(): String {
        val enabled = BridgeSettings.getInstance().state.enabled
        if (!enabled) return ""
        val path = project.basePath ?: System.getProperty("user.home")
        val bound = BridgeHub.getInstance().bindings.snapshot().count { it.target.projectPath == path }
        return statusBarText(
            enabled,
            RobotManager.getInstance().robots().size,
            bound,
            connected = { ImuxBundle.message("bridge.statusbar.connected", it) },
            idle = ImuxBundle.message("bridge.statusbar.idle"),
        )
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String = ImuxBundle.message("bridge.statusbar.tooltip")

    override fun getClickConsumer(): Consumer<MouseEvent> =
        Consumer { ShowSettingsUtil.getInstance().showSettingsDialog(project, RobotsConfigurable::class.java) }

    override fun dispose() {
        statusBar = null
    }
}
```

（若 262 上 `StatusBarWidget`/`TextPresentation` 的方法签名或默认实现不同，按编译器提示调整并在报告说明。）

- [ ] **Step 4: 注册**

在 `com.intellij` 扩展组内加入：

```xml
        <statusBarWidgetFactory
                id="imux.phoneBridge"
                implementation="com.github.izerui.imux.bridge.ui.PhoneStatusWidgetFactory"/>
```

- [ ] **Step 5: 运行测试与编译**

Run: `timeout 590 ./gradlew test --tests '*PhoneStatusWidgetTest' --tests '*PluginXmlRegistrationTest'`，`timeout 590 ./gradlew compileKotlin`
Expected: 全部 PASS，编译无错误

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneStatusWidgetFactory.kt src/test/kotlin/com/github/izerui/imux/bridge/ui/PhoneStatusWidgetTest.kt src/main/resources/META-INF/plugin.xml
git commit -m "feat(bridge): 状态栏显示本项目连接手机的会话数"
```

---

### Task 7: 全量回归与文档

**Files:**
- Modify: `docs/terminal-channel-bridge-feasibility.md`（追加第 19 节）

- [ ] **Step 1: 全量测试**：`timeout 590 ./gradlew test`，全部 PASS。
- [ ] **Step 2: 编译与打包**：`timeout 590 ./gradlew compileKotlin compileTestKotlin buildPlugin`，成功；记录 `bridge/**` 与本计划改动的现有文件中的编译警告（应为 0）。
- [ ] **Step 3: 插件验证**：`timeout 300 ./gradlew verifyPlugin`；下载失败时记录原因并标为跳过。
- [ ] **Step 4: 追加设计文档第 19 节**

```markdown
## 19. 会话装饰（第三份计划）

实施计划：`docs/superpowers/plans/2026-10-27-session-decorations.md`。

现有代码新增一个中立扩展点 `com.github.izerui.imux.sessionDecorator`（`terminal/SessionDecorations.kt`）与应用级广播 `SessionDecorationsListener`：

| 位置 | 现有代码的改动 | 桥接提供的内容 |
| --- | --- | --- |
| 终端标签图标 | `AgentIcons.forTab` 增加手机标记；图标提供器询问装饰；编辑器在广播时刷新本标签 | 是否连着手机 |
| 会话树 | 会话行在状态图标后追加手机标记；广播时重载 | 是否连着手机 |
| 终端顶部 | 竖直容器依次挂结对横幅与装饰横幅 | 连接提示条（换绑、断开） |
| 状态栏 | 无 | `imux.phoneBridge` 组件：本项目连接数或空闲 |

装饰实现出错时一律回退为“未连接”/不显示，不影响现有界面。广播只在 EDT 发布；`RobotManager.revision` 变化与重新挂接都会触发。

联调清单补充：
16. 连接后，终端标签与会话树出现手机标记，终端顶部出现提示条，状态栏显示“1 个会话已连接手机”。
17. 在提示条点「换绑」选择另一机器人、点「断开」：标记、提示条、状态栏同步变化。
18. 关闭开关：所有标记、提示条、状态栏文字消失；重新开启后恢复。
```

- [ ] **Step 5: Commit**

```bash
git add docs/terminal-channel-bridge-feasibility.md
git commit -m "docs(bridge): 记录会话装饰扩展点与联调项"
```
