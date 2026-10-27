# 机器人管理框架与企业微信接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在桥接核心之上，做出四个渠道（企业微信、飞书、钉钉、普通微信）通用的机器人管理框架，并接入第一个渠道企业微信：用户扫码添加机器人、发送配对码认主，在终端右键「连接到手机」后，手机与 IDE 里的同一个 CLI 会话双向对话。

**Architecture:** 新增 `bridge/robot`（机器人记录、持久化、凭据库、配对、生命周期）、`bridge/channel`（渠道 SPI、帧组装、限流）、`bridge/channel/wecom`（协议编解码、长连接、扫码接入）、`bridge/ui`（二维码、设置页、添加机器人对话框、右键菜单）。渠道只实现 `ChannelOnboarding` 与 `ChannelConnection`，不认识终端与 Agent；`RobotManager` 负责连接生命周期与认主，把消息交给第一份计划的 `BridgeHub`。

**Tech Stack:** Kotlin 2.3、IntelliJ Platform 262（Kotlin UI DSL、PasswordSafe、ToolbarDecorator、DialogWrapper）、kotlinx.coroutines、java.net.http（WebSocket、HttpClient）、Gson、ZXing core 3.5.3、JUnit 4。

**依据:** `docs/terminal-channel-bridge-feasibility.md`（第 16、17 节）、`docs/channel-protocols.md`（第 0、1 节）、`docs/channel-limits.md`、原型 `docs/prototypes/terminal-channel-bridge.html`。第一份计划已完成的桥接核心见 `docs/superpowers/plans/2026-10-26-terminal-channel-bridge-core.md`。

**不在本计划内（后续计划）：** 终端顶部连接提示条、标签与会话树上的手机标记、状态栏组件（计划 3）；飞书、钉钉、普通微信（计划 4–6，各自只新增一个 `ChannelFactory`）。QQ 不纳入范围。

**渠道范围：** 企业微信、飞书、钉钉、普通微信四个。

## Global Constraints

- 只允许新增或修改：`src/main/kotlin/com/github/izerui/imux/bridge/**`、`src/test/kotlin/com/github/izerui/imux/bridge/**`、`src/main/resources/META-INF/plugin.xml`（只新增注册）、10 个 `ImuxBundle*.properties`（只追加 `bridge.*` 键）、`build.gradle.kts`（只新增 ZXing 依赖）、`docs/**`。其它现有文件一律不改。
- `src/main/kotlin` 中 `bridge/` 以外的文件不得出现 `com.github.izerui.imux.bridge`（第一份计划的 `BridgeIsolationSourceTest` 继续生效）。
- `bridge/channel/**` 不得引用 `AgentType`、`com.github.izerui.imux.bridge.terminal`、`com.github.izerui.imux.bridge.transcript`、`TerminalHost`、`SessionMonitor`（Task 3 新增源码测试强制）。
- 机器人凭据只存 `PasswordSafe`，不写入 XML、日志或异常消息。
- 同一个机器人在一个 IDE 内只有一条连接；企业微信收到 `disconnected_event`（被别处接管）后不得自动重连。
- 总开关 `BridgeSettings.enabled` 关闭时：不建立任何渠道连接，不轮询会话记录，入站消息直接丢弃；已保存的机器人和绑定保留。
- 企业微信主动推送必须带 `chat_type: 1`；单条正文按 UTF-8 不超过 20000 字节（平台上限 20480，留余量）；每个会话 30 条/分钟限流。
- 本人认主：扫码结果带本人身份的渠道直接认主；不带的（企业微信）用 6 位数字配对码，10 分钟有效，只有发来正确配对码的单聊用户成为本人。非本人消息不进入终端、也不回复。
- 文案：无占位符的消息不写 `''`；有占位符的消息里单引号写成 `''`（`ImuxBundleTest` 强制）。英文、简体中文按本计划原文；其余 8 种语言由实现者忠实翻译，遵守同一规则。
- 测试命令：`./gradlew test --tests '<pattern>'`；没有平台 test-framework，UI 与平台接线用纯函数测试和 `SourceCode` 源码断言。
- 每个提交信息末尾空一行加 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。

## File Structure

| 文件 | 职责 |
| --- | --- |
| Create `bridge/robot/RobotModels.kt` | `ChannelKind`、`RobotRecord`、`SavedBinding`、不可变的 `RobotBook` |
| Create `bridge/robot/RobotStore.kt` | 应用级持久化（`imux-bridge.xml` 的 `ImuxBridgeRobots`），XML ↔ `RobotBook` 映射 |
| Create `bridge/robot/CredentialVault.kt` | 凭据库接口与 `PasswordSafe` 实现 |
| Create `bridge/robot/Pairing.kt` | 配对码生成与认主判定（纯函数） |
| Create `bridge/channel/ChannelSpi.kt` | `ConnectionStatus`、`OnboardingStep`、`ChannelOnboarding`、`ChannelConnection`、`ChannelFactory` |
| Create `bridge/channel/ChannelFactories.kt` | 已实现渠道的注册表 |
| Create `bridge/channel/TextFrameAssembler.kt` | WebSocket 文本分片拼接 |
| Create `bridge/channel/SlidingWindowLimiter.kt` | 按会话的滑动窗口限流 |
| Create `bridge/channel/Transports.kt` | `FrameTransport`/`HttpJsonGet` 端口与 JDK 实现 |
| Create `bridge/channel/wecom/WecomProtocol.kt` | 企业微信帧的构造与解析（纯函数） |
| Create `bridge/channel/wecom/WecomConnection.kt` | 订阅、心跳、重连、被踢、收消息、主动推送 |
| Create `bridge/channel/wecom/WecomOnboarding.kt` | 扫码接入 |
| Create `bridge/channel/wecom/WecomChannelFactory.kt` | 企业微信工厂 |
| Modify `bridge/core/BridgePorts.kt`、`bridge/core/BridgeRouter.kt` | `BridgeTexts` 新增三条文案；路由器新增 `onBound` |
| Modify `bridge/BundleBridgeTexts.kt` | 实现新增文案 |
| Modify `bridge/BridgeHub.kt` | 新增 `detachRobot` |
| Create `bridge/robot/RobotManager.kt` | 连接生命周期、认主、绑定持久化与恢复 |
| Create `bridge/BridgeLifecycle.kt` | 开关切换时启停；会话标题查询 |
| Modify `bridge/TerminalBridgeService.kt` | 开关关闭时不轮询不处理；释放、迁移后同步持久化 |
| Modify `bridge/BridgeStartupActivity.kt` | 启动时恢复机器人连接 |
| Create `bridge/ui/QrCodes.kt` | ZXing 生成二维码图像 |
| Create `bridge/ui/OnboardingDriver.kt` | 扫码流程驱动（纯协程逻辑，可测） |
| Create `bridge/ui/AddRobotDialog.kt` | 添加机器人对话框 |
| Create `bridge/ui/RobotRows.kt` | 设置页表格行模型（纯函数） |
| Create `bridge/ui/RobotsConfigurable.kt` | 设置 › 工具 › Imux › 通讯机器人 |
| Create `bridge/ui/PhoneMenu.kt` | 右键菜单模型（纯函数） |
| Create `bridge/ui/PhoneActions.kt` | 「连接到手机」动作组与各动作、通知 |
| Modify `plugin.xml`、`build.gradle.kts`、10 个 bundle | 注册与文案 |

（路径前缀 `src/main/kotlin/com/github/izerui/imux/`，测试对应 `src/test/kotlin/com/github/izerui/imux/`。）

---

### Task 1: 机器人模型、持久化与凭据库

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/robot/RobotModels.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/robot/RobotStore.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/robot/CredentialVault.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/robot/RobotBookTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/robot/RobotStoreMappingTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/robot/SecretCodecTest.kt`

**Interfaces:**
- Consumes: `com.github.izerui.imux.bridge.core.RobotOwner(senderId, chatId)`
- Produces: `ChannelKind { WECOM, FEISHU, DINGTALK, WEIXIN }`（`id`、`of(id)`）；`RobotRecord(id, name, kind, ownerSenderId?, ownerChatId?, pairCode?, pairCodeExpiresAtMillis, createdAtMillis)` 与 `owner(): RobotOwner?`；`SavedBinding(robotId, projectPath, agentCli, sessionId)`；`RobotBook(robots, bindings)` 的 `robot/add/rename/remove/withOwner/withPairCode/withBindings`；`RobotStore.getInstance().book()`、`update((RobotBook) -> RobotBook): RobotBook`；`toBook(State)`、`fromBook(RobotBook)`；`interface CredentialVault { load(robotId): Map<String,String>?; save(robotId, values); delete(robotId) }`、`PasswordSafeVault`、`encodeSecret`、`decodeSecret`

- [ ] **Step 1: 写失败测试**

`RobotBookTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.core.RobotOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RobotBookTest {
    private fun robot(id: String) = RobotRecord(id, "bot-$id", ChannelKind.WECOM, pairCode = "123456", pairCodeExpiresAtMillis = 99)

    @Test
    fun `重命名只改对应机器人`() {
        val book = RobotBook().add(robot("a")).add(robot("b")).rename("a", "开发助手")
        assertEquals("开发助手", book.robot("a")!!.name)
        assertEquals("bot-b", book.robot("b")!!.name)
    }

    @Test
    fun `认主后清除配对码`() {
        val owned = RobotBook().add(robot("a")).withOwner("a", RobotOwner("u1", "u1")).robot("a")!!
        assertEquals(RobotOwner("u1", "u1"), owned.owner())
        assertNull(owned.pairCode)
        assertEquals(0L, owned.pairCodeExpiresAtMillis)
    }

    @Test
    fun `删除机器人同时删除它的绑定`() {
        val book =
            RobotBook()
                .add(robot("a"))
                .add(robot("b"))
                .withBindings(listOf(SavedBinding("a", "/p", "codex", "s1"), SavedBinding("b", "/p", "claude", "s2")))
                .remove("a")
        assertEquals(listOf("b"), book.robots.map { it.id })
        assertEquals(listOf("b"), book.bindings.map { it.robotId })
    }

    @Test
    fun `保存绑定时丢弃不存在的机器人`() {
        val book = RobotBook().add(robot("a")).withBindings(listOf(SavedBinding("ghost", "/p", "codex", "s1")))
        assertEquals(emptyList<SavedBinding>(), book.bindings)
    }

    @Test
    fun `未认主时没有本人`() {
        assertNull(robot("a").owner())
    }
}
```

`RobotStoreMappingTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.robot

import org.junit.Assert.assertEquals
import org.junit.Test

class RobotStoreMappingTest {
    @Test
    fun `映射往返保持一致`() {
        val book =
            RobotBook(
                robots =
                    listOf(
                        RobotRecord("a", "开发助手 1", ChannelKind.WECOM, pairCode = "123456", pairCodeExpiresAtMillis = 10, createdAtMillis = 5),
                        RobotRecord("b", "审查", ChannelKind.FEISHU, ownerSenderId = "o", ownerChatId = "o", createdAtMillis = 6),
                    ),
                bindings = listOf(SavedBinding("b", "/p", "codex", "s1")),
            )
        assertEquals(book, toBook(fromBook(book)))
    }

    @Test
    fun `未知渠道与空 id 的记录被丢弃`() {
        val state =
            RobotStore.State(
                robots =
                    listOf(
                        RobotStore.RobotXml(id = "a", name = "x", kind = "telegram"),
                        RobotStore.RobotXml(id = "", name = "y", kind = "wecom"),
                        RobotStore.RobotXml(id = "c", name = "z", kind = "wecom"),
                    ),
                bindings = listOf(RobotStore.BindingXml(robotId = "c", projectPath = "/p", agentCli = "codex", sessionId = "")),
            )
        val book = toBook(state)
        assertEquals(listOf("c"), book.robots.map { it.id })
        assertEquals(emptyList<SavedBinding>(), book.bindings)
    }
}
```

`SecretCodecTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecretCodecTest {
    @Test
    fun `凭据编码往返`() {
        val values = mapOf("bot_id" to "B1", "secret" to "s\"e:c")
        assertEquals(values, decodeSecret(encodeSecret(values)))
    }

    @Test
    fun `损坏的内容返回 null`() {
        assertNull(decodeSecret("not json"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*RobotBookTest' --tests '*RobotStoreMappingTest' --tests '*SecretCodecTest'`
Expected: 编译失败，`Unresolved reference: RobotRecord`

- [ ] **Step 3: 实现 `RobotModels.kt`**

```kotlin
package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.core.RobotOwner

/** 渠道种类。[id] 写入持久化，不能改名。 */
enum class ChannelKind(val id: String) {
    WECOM("wecom"),
    FEISHU("feishu"),
    DINGTALK("dingtalk"),
    WEIXIN("weixin"),
    ;

    companion object {
        fun of(id: String): ChannelKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 一个长期保存的机器人。它与会话无关：会话来来去去，机器人反复绑定。
 *
 * 扫码拿不到本人身份的渠道（企业微信、钉钉）在认主前带一个 [pairCode]，
 * 等本人把它发给机器人；认主后只接受本人的消息。
 */
data class RobotRecord(
    val id: String,
    val name: String,
    val kind: ChannelKind,
    val ownerSenderId: String? = null,
    val ownerChatId: String? = null,
    val pairCode: String? = null,
    val pairCodeExpiresAtMillis: Long = 0,
    val createdAtMillis: Long = 0,
) {
    fun owner(): RobotOwner? {
        val sender = ownerSenderId ?: return null
        val chat = ownerChatId ?: return null
        return RobotOwner(sender, chat)
    }
}

/** 持久化的绑定：IDE 重启后按原生会话 id 重新挂接到恢复的标签。 */
data class SavedBinding(
    val robotId: String,
    val projectPath: String,
    val agentCli: String,
    val sessionId: String,
)

/** 全部机器人与绑定的不可变快照。修改都是纯函数，持久化层只负责读写。 */
data class RobotBook(
    val robots: List<RobotRecord> = emptyList(),
    val bindings: List<SavedBinding> = emptyList(),
) {
    fun robot(id: String): RobotRecord? = robots.firstOrNull { it.id == id }

    fun add(record: RobotRecord): RobotBook = copy(robots = robots.filterNot { it.id == record.id } + record)

    fun rename(
        id: String,
        name: String,
    ): RobotBook = update(id) { it.copy(name = name) }

    fun remove(id: String): RobotBook =
        copy(robots = robots.filterNot { it.id == id }, bindings = bindings.filterNot { it.robotId == id })

    fun withOwner(
        id: String,
        owner: RobotOwner,
    ): RobotBook =
        update(id) {
            it.copy(ownerSenderId = owner.senderId, ownerChatId = owner.chatId, pairCode = null, pairCodeExpiresAtMillis = 0)
        }

    fun withPairCode(
        id: String,
        code: String,
        expiresAtMillis: Long,
    ): RobotBook = update(id) { it.copy(pairCode = code, pairCodeExpiresAtMillis = expiresAtMillis) }

    fun withBindings(next: List<SavedBinding>): RobotBook =
        copy(bindings = next.filter { binding -> robots.any { it.id == binding.robotId } })

    private fun update(
        id: String,
        transform: (RobotRecord) -> RobotRecord,
    ): RobotBook = copy(robots = robots.map { if (it.id == id) transform(it) else it })
}
```

- [ ] **Step 4: 实现 `RobotStore.kt`**

```kotlin
package com.github.izerui.imux.bridge.robot

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 机器人与绑定的应用级持久化。与开关共用 imux-bridge.xml，但是独立的组件名。
 * 凭据不在这里，见 [CredentialVault]。
 */
@Service(Service.Level.APP)
@State(name = "ImuxBridgeRobots", storages = [Storage("imux-bridge.xml", roamingType = RoamingType.DISABLED)])
class RobotStore : SerializablePersistentStateComponent<RobotStore.State>(State()) {
    data class State(
        @JvmField val robots: List<RobotXml> = emptyList(),
        @JvmField val bindings: List<BindingXml> = emptyList(),
    )

    // XMLB 通过写字段还原集合元素；业务代码只通过 RobotBook 使用这些数据。
    data class RobotXml(
        @JvmField var id: String = "",
        @JvmField var name: String = "",
        @JvmField var kind: String = "",
        @JvmField var ownerSenderId: String? = null,
        @JvmField var ownerChatId: String? = null,
        @JvmField var pairCode: String? = null,
        @JvmField var pairCodeExpiresAtMillis: Long = 0,
        @JvmField var createdAtMillis: Long = 0,
    )

    data class BindingXml(
        @JvmField var robotId: String = "",
        @JvmField var projectPath: String = "",
        @JvmField var agentCli: String = "",
        @JvmField var sessionId: String = "",
    )

    fun book(): RobotBook = toBook(state)

    fun update(transform: (RobotBook) -> RobotBook): RobotBook {
        var result = RobotBook()
        updateState { current ->
            result = transform(toBook(current))
            fromBook(result)
        }
        return result
    }

    companion object {
        fun getInstance(): RobotStore = ApplicationManager.getApplication().getService(RobotStore::class.java)
    }
}

internal fun toBook(state: RobotStore.State): RobotBook {
    val robots =
        state.robots.mapNotNull { xml ->
            val kind = ChannelKind.of(xml.kind) ?: return@mapNotNull null
            if (xml.id.isBlank()) return@mapNotNull null
            RobotRecord(
                id = xml.id,
                name = xml.name,
                kind = kind,
                ownerSenderId = xml.ownerSenderId,
                ownerChatId = xml.ownerChatId,
                pairCode = xml.pairCode,
                pairCodeExpiresAtMillis = xml.pairCodeExpiresAtMillis,
                createdAtMillis = xml.createdAtMillis,
            )
        }
    val bindings =
        state.bindings
            .filter { it.robotId.isNotBlank() && it.sessionId.isNotBlank() && it.agentCli.isNotBlank() }
            .map { SavedBinding(it.robotId, it.projectPath, it.agentCli, it.sessionId) }
    return RobotBook(robots).withBindings(bindings)
}

internal fun fromBook(book: RobotBook): RobotStore.State =
    RobotStore.State(
        robots =
            book.robots.map {
                RobotStore.RobotXml(
                    id = it.id,
                    name = it.name,
                    kind = it.kind.id,
                    ownerSenderId = it.ownerSenderId,
                    ownerChatId = it.ownerChatId,
                    pairCode = it.pairCode,
                    pairCodeExpiresAtMillis = it.pairCodeExpiresAtMillis,
                    createdAtMillis = it.createdAtMillis,
                )
            },
        bindings = book.bindings.map { RobotStore.BindingXml(it.robotId, it.projectPath, it.agentCli, it.sessionId) },
    )
```

- [ ] **Step 5: 实现 `CredentialVault.kt`**

```kotlin
package com.github.izerui.imux.bridge.robot

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/** 机器人凭据库。实现方必须把内容存在系统凭据存储里，不能落到普通配置文件。 */
interface CredentialVault {
    fun load(robotId: String): Map<String, String>?

    fun save(
        robotId: String,
        values: Map<String, String>,
    )

    fun delete(robotId: String)
}

/** 基于 IDE PasswordSafe（macOS 钥匙串 / Windows 凭据管理器 / KeePass）。可能阻塞，勿在 EDT 调用。 */
class PasswordSafeVault : CredentialVault {
    override fun load(robotId: String): Map<String, String>? =
        PasswordSafe.instance.getPassword(attributes(robotId))?.let(::decodeSecret)

    override fun save(
        robotId: String,
        values: Map<String, String>,
    ) {
        PasswordSafe.instance.setPassword(attributes(robotId), encodeSecret(values))
    }

    override fun delete(robotId: String) {
        PasswordSafe.instance.setPassword(attributes(robotId), null)
    }

    private fun attributes(robotId: String) = CredentialAttributes(generateServiceName("imux bridge", "robot:$robotId"))
}

private val secretType = object : TypeToken<Map<String, String>>() {}.type

internal fun encodeSecret(values: Map<String, String>): String = Gson().toJson(values)

internal fun decodeSecret(text: String): Map<String, String>? =
    runCatching { Gson().fromJson<Map<String, String>>(text, secretType) }.getOrNull()
```

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew test --tests '*RobotBookTest' --tests '*RobotStoreMappingTest' --tests '*SecretCodecTest'`
Expected: 9 tests PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/robot src/test/kotlin/com/github/izerui/imux/bridge/robot
git commit -m "feat(bridge): 新增机器人记录、持久化与凭据库"
```

---

### Task 2: 配对码认主

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/robot/Pairing.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/robot/PairingTest.kt`

**Interfaces:**
- Consumes: `RobotRecord`（Task 1）、`InboundMessage`、`RobotOwner`（核心）
- Produces: `object Pairing { CODE_LENGTH = 6; TTL_MILLIS = 600_000; newCode(random): String; decide(record, message, nowMillis): PairDecision }`；`sealed interface PairDecision { Pass; Paired(owner); Expired; Ignore }`

判定规则：已认主 → `Pass`（交给路由器，路由器再按本人过滤）；未认主且没有配对码 → `Ignore`；消息正文去掉空白后等于配对码：未过期 → `Paired(RobotOwner(senderId, chatId))`，已过期 → `Expired`；其它一律 `Ignore`（不回复陌生人，避免消耗发送额度）。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.RobotOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PairingTest {
    private val pending = RobotRecord("r", "bot", ChannelKind.WECOM, pairCode = "482713", pairCodeExpiresAtMillis = 1_000)

    private fun msg(text: String) = InboundMessage(RobotId("r"), "m1", "user-1", "user-1", text)

    @Test
    fun `已认主的机器人直接放行`() {
        val owned = pending.copy(ownerSenderId = "u", ownerChatId = "u", pairCode = null)
        assertEquals(PairDecision.Pass, Pairing.decide(owned, msg("hello"), nowMillis = 0))
    }

    @Test
    fun `发来正确配对码即认主，容忍空白`() {
        assertEquals(PairDecision.Paired(RobotOwner("user-1", "user-1")), Pairing.decide(pending, msg(" 482 713\n"), nowMillis = 500))
    }

    @Test
    fun `错误配对码与普通消息被忽略`() {
        assertEquals(PairDecision.Ignore, Pairing.decide(pending, msg("111111"), nowMillis = 500))
        assertEquals(PairDecision.Ignore, Pairing.decide(pending, msg("在吗"), nowMillis = 500))
    }

    @Test
    fun `配对码过期时只对正确配对码提示过期`() {
        assertEquals(PairDecision.Expired, Pairing.decide(pending, msg("482713"), nowMillis = 1_001))
        assertEquals(PairDecision.Ignore, Pairing.decide(pending, msg("在吗"), nowMillis = 1_001))
    }

    @Test
    fun `没有配对码的未认主机器人忽略一切`() {
        assertEquals(PairDecision.Ignore, Pairing.decide(pending.copy(pairCode = null), msg("482713"), nowMillis = 0))
    }

    @Test
    fun `配对码是 6 位数字`() {
        val code = Pairing.newCode(Random(42))
        assertEquals(6, code.length)
        assertTrue(code.all(Char::isDigit))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*PairingTest'`
Expected: 编译失败，`Unresolved reference: Pairing`

- [ ] **Step 3: 实现 `Pairing.kt`**

```kotlin
package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.RobotOwner
import java.security.SecureRandom
import java.util.Random

sealed interface PairDecision {
    /** 已认主，交给路由器（路由器再按本人过滤）。 */
    data object Pass : PairDecision

    data class Paired(val owner: RobotOwner) : PairDecision

    /** 发来的是正确配对码，但已过期：提示到 IDE 重新生成。 */
    data object Expired : PairDecision

    /** 不处理也不回复。陌生人的消息不能消耗机器人的发送额度。 */
    data object Ignore : PairDecision
}

/**
 * 扫码拿不到本人身份的渠道用配对码认主：IDE 显示一次性数字，本人发给机器人，
 * 第一个发来正确数字的单聊用户成为本人。这条消息同时满足企业微信
 * “用户先发过消息才能主动推送”的前提。
 */
object Pairing {
    const val CODE_LENGTH = 6
    const val TTL_MILLIS = 10 * 60_000L

    fun newCode(random: Random = SecureRandom()): String = (0 until CODE_LENGTH).joinToString("") { random.nextInt(10).toString() }

    fun decide(
        record: RobotRecord,
        message: InboundMessage,
        nowMillis: Long,
    ): PairDecision {
        if (record.owner() != null) return PairDecision.Pass
        val code = record.pairCode ?: return PairDecision.Ignore
        if (message.text.filterNot(Char::isWhitespace) != code) return PairDecision.Ignore
        if (nowMillis > record.pairCodeExpiresAtMillis) return PairDecision.Expired
        return PairDecision.Paired(RobotOwner(message.senderId, message.chatId))
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests '*PairingTest'`
Expected: 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/robot/Pairing.kt src/test/kotlin/com/github/izerui/imux/bridge/robot/PairingTest.kt
git commit -m "feat(bridge): 新增配对码认主判定"
```

---

### Task 3: 渠道 SPI、传输端口、分片拼接与限流

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/ChannelSpi.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/ChannelFactories.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/TextFrameAssembler.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/SlidingWindowLimiter.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/Transports.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/TextFrameAssemblerTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/SlidingWindowLimiterTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/ChannelIsolationSourceTest.kt`

**Interfaces:**
- Consumes: `ChannelAdapter`、`InboundSink`、`RobotId`（核心）；`ChannelKind`（Task 1）
- Produces:
  - `sealed interface ConnectionStatus { Connecting; Online; Kicked; Failed(reason: String); Stopped }`
  - `sealed interface OnboardingStep { ShowQr(content); Waiting; NeedVerifyCode; Succeeded(credentials: Map<String,String>, ownerId: String?, suggestedName: String?); Failed(messageKey: String) }`
  - `interface ChannelOnboarding { val pollIntervalMillis: Long; suspend fun start(): OnboardingStep; suspend fun poll(): OnboardingStep; suspend fun submitVerifyCode(code: String): OnboardingStep }`
  - `fun interface ConnectionListener { fun statusChanged(status: ConnectionStatus) }`
  - `interface ChannelConnection : ChannelAdapter { fun start(sink: InboundSink, listener: ConnectionListener); fun stop() }`
  - `interface ChannelFactory { val kind: ChannelKind; fun onboarding(): ChannelOnboarding; fun connect(robotId: RobotId, credentials: Map<String,String>, scope: CoroutineScope): ChannelConnection }`
  - `object ChannelFactories { fun all(): List<ChannelFactory>; fun forKind(kind): ChannelFactory? }`
  - `class TextFrameAssembler(maxChars = 4_194_304) { fun accept(part: CharSequence, last: Boolean): String? }`
  - `class SlidingWindowLimiter(limit: Int, windowMillis: Long, now: () -> Long) { fun tryAcquire(key: String): Long }`（返回 0 表示放行并已记账，否则为需等待毫秒数）
  - `interface FrameListener { fun onText(text: String); fun onClosed(reason: String) }`、`interface FrameTransport { suspend fun open(url: String, listener: FrameListener); suspend fun send(text: String); fun close() }`、`JdkWebSocketTransport`
  - `fun interface HttpJsonGet { suspend fun get(url: String): JsonObject }`、`JdkHttpJsonGet`

- [ ] **Step 1: 写失败测试**

`TextFrameAssemblerTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextFrameAssemblerTest {
    @Test
    fun `分片拼接到 last 才输出`() {
        val assembler = TextFrameAssembler()
        assertNull(assembler.accept("{\"a\":", false))
        assertEquals("{\"a\":1}", assembler.accept("1}", true))
        assertEquals("x", assembler.accept("x", true))
    }

    @Test
    fun `超长消息整条丢弃，下一条正常`() {
        val assembler = TextFrameAssembler(maxChars = 4)
        assertNull(assembler.accept("12345", false))
        assertNull(assembler.accept("6", true))
        assertEquals("ok", assembler.accept("ok", true))
    }
}
```

`SlidingWindowLimiterTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel

import org.junit.Assert.assertEquals
import org.junit.Test

class SlidingWindowLimiterTest {
    @Test
    fun `窗口内超过上限需要等待，窗口滑过后放行`() {
        var now = 0L
        val limiter = SlidingWindowLimiter(limit = 2, windowMillis = 1_000) { now }
        assertEquals(0L, limiter.tryAcquire("u"))
        now = 100
        assertEquals(0L, limiter.tryAcquire("u"))
        now = 200
        assertEquals(800L, limiter.tryAcquire("u"))
        now = 1_000
        assertEquals(0L, limiter.tryAcquire("u"))
    }

    @Test
    fun `不同会话分别计数`() {
        val limiter = SlidingWindowLimiter(limit = 1, windowMillis = 1_000) { 0L }
        assertEquals(0L, limiter.tryAcquire("a"))
        assertEquals(0L, limiter.tryAcquire("b"))
    }
}
```

`ChannelIsolationSourceTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 渠道只做收发：不认识 Agent、终端、会话记录。新增渠道时这条测试自动覆盖。 */
class ChannelIsolationSourceTest {
    @Test
    fun `渠道代码不依赖终端与 Agent`() {
        val files = File("src/main/kotlin/com/github/izerui/imux/bridge/channel").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("没有找到渠道源码", files.isNotEmpty())
        val forbidden =
            listOf("AgentType", "com.github.izerui.imux.bridge.terminal", "com.github.izerui.imux.bridge.transcript", "TerminalHost", "SessionMonitor")
        files.forEach { file ->
            val code = SourceCode(file.path).normalized
            forbidden.forEach { word -> assertTrue("${file.name} 引用了 $word", !code.contains(word)) }
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*TextFrameAssemblerTest' --tests '*SlidingWindowLimiterTest' --tests '*ChannelIsolationSourceTest'`
Expected: 编译失败，`Unresolved reference: TextFrameAssembler`

- [ ] **Step 3: 实现 `ChannelSpi.kt`**

```kotlin
package com.github.izerui.imux.bridge.channel

import com.github.izerui.imux.bridge.core.ChannelAdapter
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.robot.ChannelKind
import kotlinx.coroutines.CoroutineScope

sealed interface ConnectionStatus {
    data object Connecting : ConnectionStatus

    data object Online : ConnectionStatus

    /** 同一机器人在别处建立了连接。不得自动重连，否则两边无限互踢。 */
    data object Kicked : ConnectionStatus

    data class Failed(val reason: String) : ConnectionStatus

    data object Stopped : ConnectionStatus
}

sealed interface OnboardingStep {
    data class ShowQr(val content: String) : OnboardingStep

    data object Waiting : OnboardingStep

    /** 普通微信等渠道可能要求输入手机上显示的数字。 */
    data object NeedVerifyCode : OnboardingStep

    /** [ownerId] 为 null 表示扫码拿不到本人身份，需要配对码认主。 */
    data class Succeeded(
        val credentials: Map<String, String>,
        val ownerId: String?,
        val suggestedName: String?,
    ) : OnboardingStep

    /** [messageKey] 是 ImuxBundle 的键。 */
    data class Failed(val messageKey: String) : OnboardingStep
}

/** 一次扫码接入。调用方按 [pollIntervalMillis] 反复 [poll]，并负责总超时。 */
interface ChannelOnboarding {
    val pollIntervalMillis: Long

    suspend fun start(): OnboardingStep

    suspend fun poll(): OnboardingStep

    suspend fun submitVerifyCode(code: String): OnboardingStep
}

fun interface ConnectionListener {
    fun statusChanged(status: ConnectionStatus)
}

/**
 * 一条渠道连接。单聊场景下必须把 InboundMessage.chatId 规范为发送时使用的对端 id，
 * 因此本人的 senderId 与 chatId 相同。[start] 不阻塞；断线重连由实现自行负责。
 */
interface ChannelConnection : ChannelAdapter {
    fun start(
        sink: InboundSink,
        listener: ConnectionListener,
    )

    fun stop()
}

interface ChannelFactory {
    val kind: ChannelKind

    fun onboarding(): ChannelOnboarding

    fun connect(
        robotId: RobotId,
        credentials: Map<String, String>,
        scope: CoroutineScope,
    ): ChannelConnection
}
```

- [ ] **Step 4: 实现 `ChannelFactories.kt`（企业微信在 Task 6 注册）**

```kotlin
package com.github.izerui.imux.bridge.channel

import com.github.izerui.imux.bridge.robot.ChannelKind

/** 已实现的渠道。新增渠道只需在这里登记一个工厂。 */
object ChannelFactories {
    fun all(): List<ChannelFactory> = emptyList()

    fun forKind(kind: ChannelKind): ChannelFactory? = all().firstOrNull { it.kind == kind }
}
```

- [ ] **Step 5: 实现 `TextFrameAssembler.kt` 与 `SlidingWindowLimiter.kt`**

```kotlin
package com.github.izerui.imux.bridge.channel

/**
 * java.net.http.WebSocket 的 onText 可能把一条消息分成多段，拼到 last=true 才是完整 JSON。
 * 超过 [maxChars] 的消息整条丢弃，避免异常大帧占满内存。
 */
class TextFrameAssembler(
    private val maxChars: Int = 4 * 1024 * 1024,
) {
    private val buffer = StringBuilder()
    private var overflowed = false

    fun accept(
        part: CharSequence,
        last: Boolean,
    ): String? {
        if (!overflowed) {
            if (buffer.length + part.length > maxChars) {
                overflowed = true
                buffer.setLength(0)
            } else {
                buffer.append(part)
            }
        }
        if (!last) return null
        val complete = if (overflowed) null else buffer.toString()
        buffer.setLength(0)
        overflowed = false
        return complete
    }
}
```

```kotlin
package com.github.izerui.imux.bridge.channel

/** 按会话的滑动窗口限流。线程安全。 */
class SlidingWindowLimiter(
    private val limit: Int,
    private val windowMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val history = HashMap<String, ArrayDeque<Long>>()

    /** 放行时记账并返回 0；否则返回还需等待的毫秒数，不记账。 */
    @Synchronized
    fun tryAcquire(key: String): Long {
        val current = now()
        val times = history.getOrPut(key) { ArrayDeque() }
        while (times.isNotEmpty() && current - times.first() >= windowMillis) times.removeFirst()
        if (times.size < limit) {
            times.addLast(current)
            return 0
        }
        return times.first() + windowMillis - current
    }
}
```

- [ ] **Step 6: 实现 `Transports.kt`**

```kotlin
package com.github.izerui.imux.bridge.channel

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage

interface FrameListener {
    fun onText(text: String)

    fun onClosed(reason: String)
}

/** 文本帧传输。渠道连接只依赖这个端口，测试用假实现。 */
interface FrameTransport {
    suspend fun open(
        url: String,
        listener: FrameListener,
    )

    suspend fun send(text: String)

    fun close()
}

fun interface HttpJsonGet {
    suspend fun get(url: String): JsonObject
}

/** JDK WebSocket：拼接分片、串行发送（并发 sendText 会抛 IllegalStateException）。 */
class JdkWebSocketTransport(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
) : FrameTransport {
    private val sendLock = Mutex()

    @Volatile
    private var socket: WebSocket? = null

    override suspend fun open(
        url: String,
        listener: FrameListener,
    ) {
        val assembler = TextFrameAssembler()
        socket =
            client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(
                    URI(url),
                    object : WebSocket.Listener {
                        override fun onText(
                            webSocket: WebSocket,
                            data: CharSequence,
                            last: Boolean,
                        ): CompletionStage<*>? {
                            assembler.accept(data, last)?.let(listener::onText)
                            webSocket.request(1)
                            return null
                        }

                        override fun onClose(
                            webSocket: WebSocket,
                            statusCode: Int,
                            reason: String,
                        ): CompletionStage<*>? {
                            listener.onClosed("$statusCode $reason")
                            return null
                        }

                        override fun onError(
                            webSocket: WebSocket,
                            error: Throwable,
                        ) {
                            listener.onClosed(error.message ?: error.javaClass.simpleName)
                        }
                    },
                ).await()
    }

    override suspend fun send(text: String) {
        sendLock.withLock {
            val current = socket ?: throw IOException("WebSocket 未连接")
            current.sendText(text, true).await()
        }
    }

    override fun close() {
        socket?.abort()
        socket = null
    }
}

class JdkHttpJsonGet(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
) : HttpJsonGet {
    override suspend fun get(url: String): JsonObject {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).GET().build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() !in 200..299) throw IOException("HTTP ${response.statusCode()}")
        val root = JsonParser.parseString(response.body())
        if (!root.isJsonObject) throw IOException("返回的不是 JSON 对象")
        return root.asJsonObject
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

Run: `./gradlew test --tests '*TextFrameAssemblerTest' --tests '*SlidingWindowLimiterTest' --tests '*ChannelIsolationSourceTest'`
Expected: 5 tests PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/channel src/test/kotlin/com/github/izerui/imux/bridge/channel
git commit -m "feat(bridge): 新增渠道接口、传输端口、分片拼接与限流"
```

---

### Task 4: 企业微信协议编解码

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomProtocol.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomProtocolTest.kt`

**Interfaces:**
- Produces: `object WecomProtocol { URL; reqId(cmd, nowMillis, random): String; subscribe(reqId, botId, secret): String; ping(reqId): String; sendMarkdown(reqId, userId, content): String; parse(text): WecomFrame }`；`sealed interface WecomFrame { Ack(reqId, errcode, errmsg); Message(reqId, msgId, single, fromUserId, text: String?); Kicked; Event(eventType: String?); Unknown }`

依据 `docs/channel-protocols.md` 第 1 节：响应没有 `cmd`；单聊没有 `chatid`；`voice.content` 是转写；`mixed.msg_item[]`；带 `compat_info` 的消息不是普通用户消息（`text = null`）。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.wecom

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class WecomProtocolTest {
    private fun obj(text: String) = JsonParser.parseString(text).asJsonObject

    @Test
    fun `订阅帧带 bot_id 与 secret`() {
        val frame = obj(WecomProtocol.subscribe("r1", "B", "S"))
        assertEquals("aibot_subscribe", frame["cmd"].asString)
        assertEquals("r1", frame["headers"].asJsonObject["req_id"].asString)
        assertEquals("B", frame["body"].asJsonObject["bot_id"].asString)
        assertEquals("S", frame["body"].asJsonObject["secret"].asString)
    }

    @Test
    fun `主动推送显式单聊并使用 markdown`() {
        val body = obj(WecomProtocol.sendMarkdown("r2", "zhangsan", "你好\n`x`"))["body"].asJsonObject
        assertEquals("zhangsan", body["chatid"].asString)
        assertEquals(1, body["chat_type"].asInt)
        assertEquals("markdown", body["msgtype"].asString)
        assertEquals("你好\n`x`", body["markdown"].asJsonObject["content"].asString)
    }

    @Test
    fun `req_id 以命令名开头且唯一`() {
        val random = Random(1)
        val a = WecomProtocol.reqId("ping", 1000, random)
        val b = WecomProtocol.reqId("ping", 1000, random)
        assertTrue(a.startsWith("ping_1000_"))
        assertTrue(a != b)
    }

    @Test
    fun `解析确认帧`() {
        assertEquals(WecomFrame.Ack("r1", 0, "ok"), WecomProtocol.parse("""{"headers":{"req_id":"r1"},"errcode":0,"errmsg":"ok"}"""))
        assertEquals(WecomFrame.Ack("r2", 846607, "too many"), WecomProtocol.parse("""{"headers":{"req_id":"r2"},"errcode":846607,"errmsg":"too many"}"""))
    }

    @Test
    fun `解析单聊文本、语音与图文混排`() {
        val text =
            """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m1","aibotid":"A","chattype":"single","from":{"userid":"u1"},"msgtype":"text","text":{"content":"跑一下单测"}}}"""
        assertEquals(WecomFrame.Message("R", "m1", true, "u1", "跑一下单测"), WecomProtocol.parse(text))
        val voice =
            """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m2","chattype":"single","from":{"userid":"u1"},"msgtype":"voice","voice":{"content":"帮我检查代码"}}}"""
        assertEquals("帮我检查代码", (WecomProtocol.parse(voice) as WecomFrame.Message).text)
        val mixed =
            """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m3","chattype":"single","from":{"userid":"u1"},"msgtype":"mixed","mixed":{"msg_item":[{"msgtype":"text","text":{"content":"看图"}},{"msgtype":"image","image":{"url":"u"}},{"msgtype":"text","text":{"content":"修一下"}}]}}}"""
        assertEquals("看图\n修一下", (WecomProtocol.parse(mixed) as WecomFrame.Message).text)
    }

    @Test
    fun `群聊、图片和兼容来源的消息没有可提交的正文`() {
        val group =
            """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m","chatid":"g","chattype":"group","from":{"userid":"u"},"msgtype":"text","text":{"content":"hi"}}}"""
        assertEquals(false, (WecomProtocol.parse(group) as WecomFrame.Message).single)
        val image =
            """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m","chattype":"single","from":{"userid":"u"},"msgtype":"image","image":{"url":"x","aeskey":"k"}}}"""
        assertNull((WecomProtocol.parse(image) as WecomFrame.Message).text)
        val compat =
            """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m","chattype":"single","from":{"userid":"u"},"msgtype":"text","text":{"content":"hi"},"compat_info":{"source":"ai_assistant"}}}"""
        assertNull((WecomProtocol.parse(compat) as WecomFrame.Message).text)
    }

    @Test
    fun `被踢事件与其它事件`() {
        val kicked =
            """{"cmd":"aibot_event_callback","headers":{"req_id":"R"},"body":{"msgid":"m","msgtype":"event","event":{"eventtype":"disconnected_event"}}}"""
        assertEquals(WecomFrame.Kicked, WecomProtocol.parse(kicked))
        val enter =
            """{"cmd":"aibot_event_callback","headers":{"req_id":"R"},"body":{"msgtype":"event","from":{"userid":"u"},"event":{"eventtype":"enter_chat"}}}"""
        assertEquals(WecomFrame.Event("enter_chat"), WecomProtocol.parse(enter))
    }

    @Test
    fun `无法解析的内容返回 Unknown`() {
        assertEquals(WecomFrame.Unknown, WecomProtocol.parse("not json"))
        assertEquals(WecomFrame.Unknown, WecomProtocol.parse("""{"cmd":"something_new"}"""))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*WecomProtocolTest'`
Expected: 编译失败，`Unresolved reference: WecomProtocol`

- [ ] **Step 3: 实现 `WecomProtocol.kt`**

```kotlin
package com.github.izerui.imux.bridge.channel.wecom

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Random

sealed interface WecomFrame {
    data class Ack(
        val reqId: String,
        val errcode: Int,
        val errmsg: String?,
    ) : WecomFrame

    /** [text] 为 null 表示没有可提交给终端的正文（图片、文件、兼容来源等）。 */
    data class Message(
        val reqId: String,
        val msgId: String,
        val single: Boolean,
        val fromUserId: String,
        val text: String?,
    ) : WecomFrame

    data object Kicked : WecomFrame

    data class Event(val eventType: String?) : WecomFrame

    data object Unknown : WecomFrame
}

/** 企业微信智能机器人长连接的帧格式，见 docs/channel-protocols.md 第 1 节。 */
object WecomProtocol {
    const val URL = "wss://openws.work.weixin.qq.com"

    fun reqId(
        cmd: String,
        nowMillis: Long,
        random: Random,
    ): String = "${cmd}_${nowMillis}_${"%08x".format(random.nextInt())}"

    fun subscribe(
        reqId: String,
        botId: String,
        secret: String,
    ): String =
        frame("aibot_subscribe", reqId) {
            addProperty("bot_id", botId)
            addProperty("secret", secret)
        }

    fun ping(reqId: String): String = frame("ping", reqId, body = null)

    /** 单聊主动推送。chat_type 必须显式为 1，否则优先按群聊解析。 */
    fun sendMarkdown(
        reqId: String,
        userId: String,
        content: String,
    ): String =
        frame("aibot_send_msg", reqId) {
            addProperty("chatid", userId)
            addProperty("chat_type", 1)
            addProperty("msgtype", "markdown")
            add("markdown", JsonObject().apply { addProperty("content", content) })
        }

    fun parse(text: String): WecomFrame {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return WecomFrame.Unknown
        val reqId = root.obj("headers")?.str("req_id").orEmpty()
        val cmd = root.str("cmd")
        if (cmd == null) {
            val errcode = root["errcode"]?.takeIf { it.isJsonPrimitive }?.asInt ?: return WecomFrame.Unknown
            return WecomFrame.Ack(reqId, errcode, root.str("errmsg"))
        }
        val body = root.obj("body") ?: return WecomFrame.Unknown
        return when (cmd) {
            "aibot_msg_callback" -> message(reqId, body)
            "aibot_event_callback" -> {
                val eventType = body.obj("event")?.str("eventtype")
                if (eventType == "disconnected_event") WecomFrame.Kicked else WecomFrame.Event(eventType)
            }
            else -> WecomFrame.Unknown
        }
    }

    private fun message(
        reqId: String,
        body: JsonObject,
    ): WecomFrame {
        val text =
            if (body.has("compat_info")) {
                null
            } else {
                when (body.str("msgtype")) {
                    "text" -> body.obj("text")?.str("content")
                    "voice" -> body.obj("voice")?.str("content")
                    "mixed" ->
                        body.obj("mixed")?.get("msg_item")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                            ?.mapNotNull { item -> item.takeIf(JsonElement::isJsonObject)?.asJsonObject }
                            ?.filter { it.str("msgtype") == "text" }
                            ?.mapNotNull { it.obj("text")?.str("content") }
                            ?.joinToString("\n")
                            ?.takeIf(String::isNotEmpty)
                    else -> null
                }
            }
        return WecomFrame.Message(
            reqId = reqId,
            msgId = body.str("msgid").orEmpty(),
            single = body.str("chattype") == "single",
            fromUserId = body.obj("from")?.str("userid").orEmpty(),
            text = text,
        )
    }

    private fun frame(
        cmd: String,
        reqId: String,
        body: (JsonObject.() -> Unit)?,
    ): String =
        JsonObject().apply {
            addProperty("cmd", cmd)
            add("headers", JsonObject().apply { addProperty("req_id", reqId) })
            if (body != null) add("body", JsonObject().apply(body))
        }.toString()

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests '*WecomProtocolTest'`
Expected: 8 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomProtocol.kt src/test/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomProtocolTest.kt
git commit -m "feat(bridge): 新增企业微信长连接帧编解码"
```

---

### Task 5: 企业微信长连接

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomConnection.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomConnectionTest.kt`

**Interfaces:**
- Consumes: `FrameTransport`、`FrameListener`、`ConnectionStatus`、`ChannelConnection`、`ConnectionListener`、`SlidingWindowLimiter`（Task 3）；`WecomProtocol`、`WecomFrame`（Task 4）；核心 `InboundMessage`、`OutboundMessage`、`DeliveryResult`、`InboundSink`、`RobotId`
- Produces: `class WecomConnection(robotId, botId, secret, scope, transportFactory = { JdkWebSocketTransport() }, now = System::currentTimeMillis, random = SecureRandom(), heartbeatMillis = 30_000, ackTimeoutMillis = 5_000, backoff = ::wecomBackoffMillis, limiter = SlidingWindowLimiter(30, 60_000, now), maxRateWaitMillis = 20_000) : ChannelConnection`（`maxTextBytes = 20_000`）；`fun wecomBackoffMillis(attempt: Int): Long`

行为（`docs/channel-protocols.md` 第 1 节）：
1. 打开连接后发订阅帧，等对应 req_id 的确认；errcode=0 → `Online`；非 0 → 认证失败，单独计数，满 5 次 → `Failed` 并停止；确认超时 → 普通断线。
2. 认证成功后每 `heartbeatMillis` 发一次 ping；连续 2 次未确认 → 关闭并重连。
3. 普通断线按 `backoff(attempt)` 重连，期间状态为 `Connecting`；`Online` 后 attempt 清零。
4. 收到 `Kicked` → 状态 `Kicked`，关闭，不再重连。
5. 只把单聊且有正文的消息交给 sink：`InboundMessage(robotId, msgId, fromUserId, chatId = fromUserId, text)`；按 msgId 去重（最近 1000 条）。
6. `send`：未在线 → `Rejected("offline")`；限流等待超过 `maxRateWaitMillis` → `Rejected("rate limited")`，否则等待后发送；确认 errcode=0 → `Accepted(null)`，非 0 → `Rejected("<errcode> <errmsg>")`，超时或 IO 异常 → `Unknown`。
7. `stop()` 取消循环、关闭传输、状态 `Stopped`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.channel.FrameListener
import com.github.izerui.imux.bridge.channel.FrameTransport
import com.github.izerui.imux.bridge.channel.SlidingWindowLimiter
import com.github.izerui.imux.bridge.core.DeliveryResult
import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class WecomConnectionTest {
    /** 假传输：记录发出的帧；按 [acker] 决定是否确认、errcode 多少。 */
    private class FakeTransport(
        private val opens: AtomicInteger,
        private val acker: (cmd: String) -> Int?,
    ) : FrameTransport {
        val sent = CopyOnWriteArrayList<String>()
        var listener: FrameListener? = null

        override suspend fun open(
            url: String,
            listener: FrameListener,
        ) {
            opens.incrementAndGet()
            this.listener = listener
        }

        override suspend fun send(text: String) {
            sent += text
            val frame = JsonParser.parseString(text).asJsonObject
            val cmd = frame["cmd"].asString
            val reqId = frame["headers"].asJsonObject["req_id"].asString
            val errcode = acker(cmd) ?: return
            listener?.onText("""{"headers":{"req_id":"$reqId"},"errcode":$errcode,"errmsg":"e"}""")
        }

        override fun close() {
            listener?.onClosed("closed")
            listener = null
        }

        fun emit(text: String) = listener!!.onText(text)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val statuses = CopyOnWriteArrayList<ConnectionStatus>()
    private val received = CopyOnWriteArrayList<InboundMessage>()
    private val opens = AtomicInteger()
    private val transports = CopyOnWriteArrayList<FakeTransport>()

    @After
    fun tearDown() = scope.cancel()

    private fun connection(
        heartbeatMillis: Long = 60_000,
        limiter: SlidingWindowLimiter = SlidingWindowLimiter(30, 60_000),
        acker: (String) -> Int? = { 0 },
    ) = WecomConnection(
        robotId = RobotId("r"),
        botId = "B",
        secret = "S",
        scope = scope,
        transportFactory = { FakeTransport(opens, acker).also { transports += it } },
        random = Random(7),
        heartbeatMillis = heartbeatMillis,
        ackTimeoutMillis = 200,
        backoff = { 0L },
        limiter = limiter,
        maxRateWaitMillis = 50,
    )

    private fun startAndAwaitOnline(conn: WecomConnection) =
        runBlocking {
            conn.start(InboundSink { received += it }) { statuses += it }
            withTimeout(2_000) { while (ConnectionStatus.Online !in statuses) delay(5) }
        }

    private fun singleText(
        msgId: String,
        user: String,
        text: String,
        chat: String = "single",
    ) = """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"$msgId","chattype":"$chat","from":{"userid":"$user"},"msgtype":"text","text":{"content":"$text"}}}"""

    @Test
    fun `连接后订阅并上线`() {
        startAndAwaitOnline(connection())
        val subscribe = JsonParser.parseString(transports.first().sent.first()).asJsonObject
        assertEquals("aibot_subscribe", subscribe["cmd"].asString)
        assertEquals("B", subscribe["body"].asJsonObject["bot_id"].asString)
    }

    @Test
    fun `只转交单聊文本并按 msgid 去重`() {
        startAndAwaitOnline(connection())
        val t = transports.first()
        t.emit(singleText("m1", "u1", "跑一下单测"))
        t.emit(singleText("m1", "u1", "跑一下单测"))
        t.emit(singleText("m2", "u2", "群里的话", chat = "group"))
        assertEquals(listOf(InboundMessage(RobotId("r"), "m1", "u1", "u1", "跑一下单测")), received.toList())
    }

    @Test
    fun `被踢后不再重连`() =
        runBlocking {
            startAndAwaitOnline(connection())
            transports.first().emit("""{"cmd":"aibot_event_callback","headers":{"req_id":"R"},"body":{"msgtype":"event","event":{"eventtype":"disconnected_event"}}}""")
            withTimeout(2_000) { while (ConnectionStatus.Kicked !in statuses) delay(5) }
            delay(100)
            assertEquals(1, opens.get())
        }

    @Test
    fun `认证连续失败 5 次后停止`() =
        runBlocking {
            val conn = connection(acker = { cmd -> if (cmd == "aibot_subscribe") 40001 else 0 })
            conn.start(InboundSink { }) { statuses += it }
            withTimeout(3_000) { while (statuses.none { it is ConnectionStatus.Failed }) delay(5) }
            assertEquals(5, opens.get())
        }

    @Test
    fun `心跳连续两次未确认则重连`() =
        runBlocking {
            val conn = connection(heartbeatMillis = 20, acker = { cmd -> if (cmd == "ping") null else 0 })
            startAndAwaitOnline(conn)
            withTimeout(3_000) { while (opens.get() < 2) delay(5) }
        }

    @Test
    fun `主动推送单聊 markdown 并按确认结果返回`() =
        runBlocking {
            val conn = connection(acker = { cmd -> if (cmd == "aibot_send_msg") 846607 else 0 })
            startAndAwaitOnline(conn)
            val result = conn.send(OutboundMessage(RobotId("r"), "u1", 1, "hi"))
            assertEquals(DeliveryResult.Rejected("846607 e"), result)
            val frame = JsonParser.parseString(transports.first().sent.last()).asJsonObject
            assertEquals(1, frame["body"].asJsonObject["chat_type"].asInt)
            assertEquals("u1", frame["body"].asJsonObject["chatid"].asString)
        }

    @Test
    fun `确认成功返回 Accepted，离线返回 Rejected`() =
        runBlocking {
            val conn = connection()
            assertEquals(DeliveryResult.Rejected("offline"), conn.send(OutboundMessage(RobotId("r"), "u1", 1, "hi")))
            startAndAwaitOnline(conn)
            assertEquals(DeliveryResult.Accepted(null), conn.send(OutboundMessage(RobotId("r"), "u1", 1, "hi")))
        }

    @Test
    fun `超出限流等待上限时拒绝`() =
        runBlocking {
            val conn = connection(limiter = SlidingWindowLimiter(1, 60_000))
            startAndAwaitOnline(conn)
            assertEquals(DeliveryResult.Accepted(null), conn.send(OutboundMessage(RobotId("r"), "u1", 1, "a")))
            assertEquals(DeliveryResult.Rejected("rate limited"), conn.send(OutboundMessage(RobotId("r"), "u1", 1, "b")))
        }

    @Test
    fun `停止后状态为 Stopped`() =
        runBlocking {
            val conn = connection()
            startAndAwaitOnline(conn)
            conn.stop()
            withTimeout(2_000) { while (ConnectionStatus.Stopped !in statuses) delay(5) }
            assertTrue(opens.get() >= 1)
        }

    @Test
    fun `退避从 1 秒翻倍到 30 秒封顶`() {
        assertEquals(1_000L, wecomBackoffMillis(1))
        assertEquals(2_000L, wecomBackoffMillis(2))
        assertEquals(30_000L, wecomBackoffMillis(10))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*WecomConnectionTest'`
Expected: 编译失败，`Unresolved reference: WecomConnection`

- [ ] **Step 3: 实现 `WecomConnection.kt`**

```kotlin
package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ConnectionListener
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.channel.FrameListener
import com.github.izerui.imux.bridge.channel.FrameTransport
import com.github.izerui.imux.bridge.channel.JdkWebSocketTransport
import com.github.izerui.imux.bridge.channel.SlidingWindowLimiter
import com.github.izerui.imux.bridge.core.DeliveryResult
import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

fun wecomBackoffMillis(attempt: Int): Long = minOf(1_000L shl (attempt - 1).coerceIn(0, 15), 30_000L)

/**
 * 企业微信智能机器人长连接（见 docs/channel-protocols.md 第 1 节）。
 *
 * 一个机器人一条连接；被别处接管（disconnected_event）后停止，不自动重连。
 * 所有出站一律走单聊主动推送：配对消息已经满足“用户先发过消息”的前提。
 */
class WecomConnection(
    override val robotId: RobotId,
    private val botId: String,
    private val secret: String,
    private val scope: CoroutineScope,
    private val transportFactory: () -> FrameTransport = { JdkWebSocketTransport() },
    private val now: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom(),
    private val heartbeatMillis: Long = 30_000,
    private val ackTimeoutMillis: Long = 5_000,
    private val backoff: (Int) -> Long = ::wecomBackoffMillis,
    private val limiter: SlidingWindowLimiter = SlidingWindowLimiter(30, 60_000, now),
    private val maxRateWaitMillis: Long = 20_000,
) : ChannelConnection {
    override val maxTextBytes: Int = 20_000

    private sealed interface SessionEnd {
        data object Kicked : SessionEnd

        data class AuthFailed(val reason: String) : SessionEnd

        data class Dropped(val reason: String) : SessionEnd
    }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<WecomFrame.Ack>>()
    private val seen = LinkedHashSet<String>()

    @Volatile
    private var online: FrameTransport? = null

    @Volatile
    private var job: Job? = null

    private var listener: ConnectionListener = ConnectionListener { }

    override fun start(
        sink: InboundSink,
        listener: ConnectionListener,
    ) {
        if (job != null) return
        this.listener = listener
        job = scope.launch { runLoop(sink) }
    }

    override fun stop() {
        job?.cancel()
        job = null
        online?.close()
        online = null
        listener.statusChanged(ConnectionStatus.Stopped)
    }

    override suspend fun send(message: OutboundMessage): DeliveryResult {
        val transport = online ?: return DeliveryResult.Rejected("offline")
        var wait = limiter.tryAcquire(message.chatId)
        if (wait > 0) {
            if (wait > maxRateWaitMillis) return DeliveryResult.Rejected("rate limited")
            delay(wait)
            wait = limiter.tryAcquire(message.chatId)
            if (wait > 0) return DeliveryResult.Rejected("rate limited")
        }
        val reqId = WecomProtocol.reqId("aibot_send_msg", now(), random)
        val ack =
            try {
                request(transport, reqId, WecomProtocol.sendMarkdown(reqId, message.chatId, message.text))
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                return DeliveryResult.Unknown
            } ?: return DeliveryResult.Unknown
        return if (ack.errcode == 0) DeliveryResult.Accepted(null) else DeliveryResult.Rejected("${ack.errcode} ${ack.errmsg.orEmpty()}".trim())
    }

    private suspend fun runLoop(sink: InboundSink) {
        var attempt = 0
        var authFailures = 0
        while (scope.isActive) {
            listener.statusChanged(ConnectionStatus.Connecting)
            when (val end = runSession(sink) { attempt = 0 }) {
                SessionEnd.Kicked -> {
                    listener.statusChanged(ConnectionStatus.Kicked)
                    return
                }
                is SessionEnd.AuthFailed -> {
                    authFailures++
                    if (authFailures >= MAX_AUTH_FAILURES) {
                        listener.statusChanged(ConnectionStatus.Failed(end.reason))
                        return
                    }
                }
                is SessionEnd.Dropped -> authFailures = 0
            }
            attempt++
            delay(backoff(attempt))
        }
    }

    private suspend fun runSession(
        sink: InboundSink,
        onOnline: () -> Unit,
    ): SessionEnd {
        val transport = transportFactory()
        val ended = CompletableDeferred<SessionEnd>()
        val frameListener =
            object : FrameListener {
                override fun onText(text: String) {
                    when (val frame = WecomProtocol.parse(text)) {
                        is WecomFrame.Ack -> pending.remove(frame.reqId)?.complete(frame)
                        is WecomFrame.Message -> deliver(frame, sink)
                        WecomFrame.Kicked -> ended.complete(SessionEnd.Kicked)
                        else -> Unit
                    }
                }

                override fun onClosed(reason: String) {
                    ended.complete(SessionEnd.Dropped(reason))
                }
            }
        try {
            transport.open(WecomProtocol.URL, frameListener)
            val subscribeId = WecomProtocol.reqId("aibot_subscribe", now(), random)
            val ack = request(transport, subscribeId, WecomProtocol.subscribe(subscribeId, botId, secret))
            when {
                ack == null -> return SessionEnd.Dropped("subscribe timeout").also { transport.close() }
                ack.errcode != 0 -> return SessionEnd.AuthFailed("${ack.errcode} ${ack.errmsg.orEmpty()}".trim()).also { transport.close() }
            }
            online = transport
            onOnline()
            listener.statusChanged(ConnectionStatus.Online)
            return coroutineScope {
                val heartbeat = launch { heartbeat(transport, ended) }
                val end = ended.await()
                heartbeat.cancel()
                end
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SessionEnd.Dropped(e.message ?: e.javaClass.simpleName)
        } finally {
            if (online === transport) online = null
            transport.close()
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
    }

    private suspend fun heartbeat(
        transport: FrameTransport,
        ended: CompletableDeferred<SessionEnd>,
    ) {
        var missed = 0
        while (true) {
            delay(heartbeatMillis)
            val id = WecomProtocol.reqId("ping", now(), random)
            val ack = runCatching { request(transport, id, WecomProtocol.ping(id)) }.getOrNull()
            missed = if (ack == null) missed + 1 else 0
            if (missed >= MAX_MISSED_PINGS) {
                ended.complete(SessionEnd.Dropped("heartbeat timeout"))
                return
            }
        }
    }

    private suspend fun request(
        transport: FrameTransport,
        reqId: String,
        frame: String,
    ): WecomFrame.Ack? {
        val deferred = CompletableDeferred<WecomFrame.Ack>()
        pending[reqId] = deferred
        try {
            transport.send(frame)
            return withTimeoutOrNull(ackTimeoutMillis) { deferred.await() }
        } finally {
            pending.remove(reqId)
        }
    }

    private fun deliver(
        frame: WecomFrame.Message,
        sink: InboundSink,
    ) {
        val text = frame.text ?: return
        if (!frame.single || frame.fromUserId.isEmpty() || frame.msgId.isEmpty()) return
        synchronized(seen) {
            if (!seen.add(frame.msgId)) return
            while (seen.size > SEEN_CAPACITY) seen.remove(seen.first())
        }
        sink.accept(InboundMessage(robotId, frame.msgId, frame.fromUserId, frame.fromUserId, text))
    }

    private companion object {
        const val MAX_AUTH_FAILURES = 5
        const val MAX_MISSED_PINGS = 2
        const val SEEN_CAPACITY = 1000
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests '*WecomConnectionTest'`
Expected: 10 tests PASS（全部使用毫秒级参数，单个测试在 3 秒内结束）

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomConnection.kt src/test/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomConnectionTest.kt
git commit -m "feat(bridge): 新增企业微信长连接（订阅、心跳、重连、被踢、推送）"
```

---

### Task 6: 企业微信扫码接入与工厂

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomOnboarding.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomChannelFactory.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/channel/ChannelFactories.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomOnboardingTest.kt`

**Interfaces:**
- Consumes: `ChannelOnboarding`、`OnboardingStep`、`HttpJsonGet`、`JdkHttpJsonGet`、`ChannelFactory`（Task 3）；`WecomConnection`（Task 5）
- Produces: `class WecomOnboarding(http: HttpJsonGet = JdkHttpJsonGet(), plat: Int = wecomPlat()) : ChannelOnboarding`（`pollIntervalMillis = 3_000`）；`fun wecomPlat(osName: String = System.getProperty("os.name")): Int`；`object WecomChannelFactory : ChannelFactory`；凭据键 `bot_id`、`secret`
- 失败的 `messageKey`：`bridge.onboarding.noQr`、`bridge.onboarding.noCredentials`、`bridge.onboarding.unsupported`（文案在 Task 7 添加）

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.channel.HttpJsonGet
import com.github.izerui.imux.bridge.channel.OnboardingStep
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WecomOnboardingTest {
    private val urls = mutableListOf<String>()

    private fun http(vararg responses: String): HttpJsonGet {
        val queue = ArrayDeque(responses.toList())
        return HttpJsonGet { url ->
            urls += url
            JsonParser.parseString(queue.removeFirst()).asJsonObject
        }
    }

    @Test
    fun `申请二维码时带 imux 来源与平台`() =
        runBlocking {
            val onboarding = WecomOnboarding(http("""{"data":{"scode":"S1","auth_url":"https://work.weixin.qq.com/ai/qc/c?s=S1"}}"""), plat = 1)
            assertEquals(OnboardingStep.ShowQr("https://work.weixin.qq.com/ai/qc/c?s=S1"), onboarding.start())
            assertEquals("https://work.weixin.qq.com/ai/qc/generate?source=imux&plat=1", urls.single())
        }

    @Test
    fun `扫码前等待，成功后返回凭据且没有本人`() =
        runBlocking {
            val onboarding =
                WecomOnboarding(
                    http(
                        """{"data":{"scode":"S1","auth_url":"A"}}""",
                        """{"data":{"status":"init"}}""",
                        """{"data":{"status":"success","bot_info":{"botid":"B","secret":"X"}}}""",
                    ),
                    plat = 2,
                )
            onboarding.start()
            assertEquals(OnboardingStep.Waiting, onboarding.poll())
            assertEquals(OnboardingStep.Succeeded(mapOf("bot_id" to "B", "secret" to "X"), ownerId = null, suggestedName = null), onboarding.poll())
            assertTrue(urls.last().endsWith("/ai/qc/query_result?scode=S1"))
        }

    @Test
    fun `兼容 bot_id 字段名`() =
        runBlocking {
            val onboarding = WecomOnboarding(http("""{"data":{"scode":"S","auth_url":"A"}}""", """{"data":{"status":"success","bot_info":{"bot_id":"B","secret":"X"}}}"""), plat = 3)
            onboarding.start()
            assertEquals(mapOf("bot_id" to "B", "secret" to "X"), (onboarding.poll() as OnboardingStep.Succeeded).credentials)
        }

    @Test
    fun `成功但缺凭据、没有二维码、未申请就轮询都返回失败`() =
        runBlocking {
            val missing = WecomOnboarding(http("""{"data":{"scode":"S","auth_url":"A"}}""", """{"data":{"status":"success","bot_info":{"botid":"B"}}}"""), plat = 1)
            missing.start()
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noCredentials"), missing.poll())
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noQr"), WecomOnboarding(http("""{"data":{}}"""), plat = 1).start())
            assertEquals(OnboardingStep.Failed("bridge.onboarding.noQr"), WecomOnboarding(http(), plat = 1).poll())
            assertEquals(OnboardingStep.Failed("bridge.onboarding.unsupported"), WecomOnboarding(http(), plat = 1).submitVerifyCode("1"))
        }

    @Test
    fun `平台编码`() {
        assertEquals(1, wecomPlat("Mac OS X"))
        assertEquals(2, wecomPlat("Windows 11"))
        assertEquals(3, wecomPlat("Linux"))
        assertEquals(0, wecomPlat("FreeBSD"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*WecomOnboardingTest'`
Expected: 编译失败，`Unresolved reference: WecomOnboarding`

- [ ] **Step 3: 实现 `WecomOnboarding.kt`**

```kotlin
package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.channel.HttpJsonGet
import com.github.izerui.imux.bridge.channel.JdkHttpJsonGet
import com.github.izerui.imux.bridge.channel.OnboardingStep
import com.google.gson.JsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** 与官方 wecom-cli 一致：1 macOS、2 Windows、3 Linux、0 其他。 */
fun wecomPlat(osName: String = System.getProperty("os.name")): Int {
    val name = osName.lowercase()
    return when {
        name.contains("mac") -> 1
        name.contains("win") -> 2
        name.contains("linux") -> 3
        else -> 0
    }
}

/**
 * 企业微信扫码创建智能机器人（docs/channel-protocols.md 第 1 节）。
 * ai/qc 是管理后台接口而非公开 API，可能变化；失败时明确报错，不提供手填密钥的回退。
 * 不返回扫码人身份，本人由配对码确定。
 */
class WecomOnboarding(
    private val http: HttpJsonGet = JdkHttpJsonGet(),
    private val plat: Int = wecomPlat(),
) : ChannelOnboarding {
    override val pollIntervalMillis: Long = 3_000

    private var scode: String? = null

    override suspend fun start(): OnboardingStep {
        val data = http.get("$BASE/generate?source=imux&plat=$plat").obj("data")
        val code = data?.str("scode")
        val authUrl = data?.str("auth_url")
        if (code.isNullOrEmpty() || authUrl.isNullOrEmpty()) return OnboardingStep.Failed("bridge.onboarding.noQr")
        scode = code
        return OnboardingStep.ShowQr(authUrl)
    }

    override suspend fun poll(): OnboardingStep {
        val code = scode ?: return OnboardingStep.Failed("bridge.onboarding.noQr")
        val data = http.get("$BASE/query_result?scode=${URLEncoder.encode(code, StandardCharsets.UTF_8)}").obj("data")
        if (data?.str("status") != "success") return OnboardingStep.Waiting
        val info = data.obj("bot_info")
        val botId = info?.str("botid") ?: info?.str("bot_id")
        val secret = info?.str("secret")
        if (botId.isNullOrEmpty() || secret.isNullOrEmpty()) return OnboardingStep.Failed("bridge.onboarding.noCredentials")
        return OnboardingStep.Succeeded(mapOf("bot_id" to botId, "secret" to secret), ownerId = null, suggestedName = null)
    }

    override suspend fun submitVerifyCode(code: String): OnboardingStep = OnboardingStep.Failed("bridge.onboarding.unsupported")

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private companion object {
        const val BASE = "https://work.weixin.qq.com/ai/qc"
    }
}
```

- [ ] **Step 4: 实现 `WecomChannelFactory.kt` 并注册**

```kotlin
package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ChannelFactory
import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.robot.ChannelKind
import kotlinx.coroutines.CoroutineScope

object WecomChannelFactory : ChannelFactory {
    override val kind: ChannelKind = ChannelKind.WECOM

    override fun onboarding(): ChannelOnboarding = WecomOnboarding()

    override fun connect(
        robotId: RobotId,
        credentials: Map<String, String>,
        scope: CoroutineScope,
    ): ChannelConnection =
        WecomConnection(
            robotId = robotId,
            botId = credentials.getValue("bot_id"),
            secret = credentials.getValue("secret"),
            scope = scope,
        )
}
```

`ChannelFactories.kt` 中把 `all()` 改为：

```kotlin
    fun all(): List<ChannelFactory> = listOf(WecomChannelFactory)
```

并加 `import com.github.izerui.imux.bridge.channel.wecom.WecomChannelFactory`。

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew test --tests '*WecomOnboardingTest' --tests '*ChannelIsolationSourceTest'`
Expected: 6 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/channel src/test/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomOnboardingTest.kt
git commit -m "feat(bridge): 新增企业微信扫码接入并注册渠道工厂"
```

---

### Task 7: 路由器“已连接”通知与全部新文案

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgePorts.kt`（`BridgeTexts` 新增 3 个方法）
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeRouter.kt`（新增 `onBound`）
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/BundleBridgeTexts.kt`
- Modify: `src/test/kotlin/com/github/izerui/imux/bridge/core/BridgeRouterTest.kt`（假文案实现新方法；新增 `onBound` 测试）
- Modify: 10 个 `src/main/resources/messages/ImuxBundle*.properties`（文件末尾追加）

**Interfaces:**
- Produces: `BridgeTexts.connected(project: String, agent: String, sessionTitle: String): String`、`pairingSucceeded(): String`、`pairingExpired(): String`；`suspend fun BridgeRouter.onBound(binding: Binding, project: String, agent: String, sessionTitle: String)`（按绑定代次发送，换绑途中不发）；下表全部 bundle 键

- [ ] **Step 1: 在 `BridgeRouterTest` 写失败测试并补全假文案**

把测试里 `private object Texts : BridgeTexts` 补上：

```kotlin
        override fun connected(
            project: String,
            agent: String,
            sessionTitle: String,
        ) = "CONNECTED($project/$agent/$sessionTitle)"

        override fun pairingSucceeded() = "PAIRED"

        override fun pairingExpired() = "PAIR_EXPIRED"
```

新增测试：

```kotlin
    @Test
    fun `绑定后向本人发送当前连接`() =
        runBlocking {
            val bound = (registry.bind(robot, target) as BindResult.Bound).binding
            router.onBound(bound, "imux", "Codex", "修复终端滚动")
            assertEquals(listOf("CONNECTED(imux/Codex/修复终端滚动)"), sentTexts())
        }

    @Test
    fun `已被换绑的旧绑定不再发送当前连接`() =
        runBlocking {
            val old = (registry.bind(robot, target) as BindResult.Bound).binding
            registry.bind(robot, target.copy(tabId = "tab-2"))
            router.onBound(old, "imux", "Codex", "旧会话")
            assertTrue(channel.sent.isEmpty())
        }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*BridgeRouterTest'`
Expected: 编译失败（`BridgeTexts` 没有 `connected`；`onBound` 未定义）

- [ ] **Step 3: 扩展 `BridgeTexts` 与路由器**

`BridgePorts.kt` 的 `BridgeTexts` 末尾追加：

```kotlin
    fun connected(
        project: String,
        agent: String,
        sessionTitle: String,
    ): String

    fun pairingSucceeded(): String

    fun pairingExpired(): String
```

`BridgeRouter.kt` 在 `onReleased` 之前加入：

```kotlin
    /** 用户把机器人绑到会话后，告诉本人当前连接到哪里。走正常的代次校验。 */
    suspend fun onBound(
        binding: Binding,
        project: String,
        agent: String,
        sessionTitle: String,
    ) {
        send(binding, texts.connected(project, agent, sessionTitle))
    }
```

`BundleBridgeTexts.kt` 追加：

```kotlin
    override fun connected(
        project: String,
        agent: String,
        sessionTitle: String,
    ) = ImuxBundle.message("bridge.status.connected", project, agent, sessionTitle)

    override fun pairingSucceeded() = ImuxBundle.message("bridge.pairing.succeeded")

    override fun pairingExpired() = ImuxBundle.message("bridge.pairing.expired")
```

- [ ] **Step 4: 追加文案**

`ImuxBundle.properties`（英文，原文）：

```properties
bridge.status.connected=Connected: {0} · {1} · {2}\nMessages you send here go to this session.
bridge.pairing.succeeded=Paired. From now on only your messages are accepted. Connect a session to this bot in the IDE to continue.
bridge.pairing.expired=This pairing code has expired. Create a new one in the IDE.
bridge.settings.displayName=Phone Bridge
bridge.settings.enable=Enable terminal session bridge
bridge.settings.enable.comment=Messages from your phone are submitted to the terminal already open in the IDE and handled by its CLI. No new agent process is started, and IDEA MCP keeps working.
bridge.settings.column.name=Bot
bridge.settings.column.channel=Channel
bridge.settings.column.status=Status
bridge.settings.column.session=Connected session
bridge.settings.empty=No bots yet. Add one once and reuse it for different sessions.
bridge.settings.unbind=Disconnect Session
bridge.settings.rename=New name:
bridge.settings.remove.confirm=Remove "{0}"? Its credentials are deleted and the bound session stops receiving phone messages.
bridge.settings.note=You only need as many bots as sessions you want to operate from your phone at the same time. A bot is released automatically when its session tab closes.
bridge.robot.status.stopped=Stopped
bridge.robot.status.connecting=Connecting…
bridge.robot.status.online=Online
bridge.robot.status.pairing=Waiting for pairing
bridge.robot.status.kicked=Taken over by another client
bridge.robot.status.failed=Failed: {0}
bridge.robot.status.noCredentials=Credentials missing, scan again
bridge.channel.wecom=WeCom
bridge.channel.feishu=Feishu
bridge.channel.dingtalk=DingTalk
bridge.channel.weixin=WeChat
bridge.channel.later=Coming later
bridge.add.title=Add Bot
bridge.add.pickChannel=Choose a channel. You only need to scan a QR code with your phone.
bridge.add.next=Next
bridge.add.scan=Scan with {0}
bridge.add.scan.waiting=Waiting for the scan. The QR code is valid for 5 minutes.
bridge.add.scan.starting=Requesting a QR code…
bridge.add.scan.timeout=The QR code has expired.
bridge.add.retry=Get a New QR Code
bridge.add.verifyCode=Enter the number shown on your phone:
bridge.add.verifyCode.submit=Submit
bridge.add.pair.title=Bot created. Pair it with your account
bridge.add.pair.instruction=Within 10 minutes, send this pairing code to the bot in {0}:
bridge.add.pair.waiting=Waiting for the pairing code…
bridge.add.pair.done=Paired. You can now connect sessions to this bot.
bridge.add.pair.renew=New Pairing Code
bridge.add.defaultName=imux bot {0}
bridge.add.failed=Adding the bot failed: {0}
bridge.add.enabledBridge=The bridge has been turned on.
bridge.onboarding.noQr=The channel did not return a QR code.
bridge.onboarding.noCredentials=The scan succeeded, but no bot credentials were returned.
bridge.onboarding.unsupported=This channel does not use a verification code.
bridge.onboarding.network=Network error: {0}
bridge.action.group=Connect to Phone
bridge.action.robot.idle={0} (idle)
bridge.action.robot.bound={0} (connected: {1})
bridge.action.robot.unpaired={0} (not paired)
bridge.action.robot.current={0} (connected to this session)
bridge.action.add=Add Bot…
bridge.action.disconnect=Disconnect Phone
bridge.action.none=No bots yet
bridge.action.disabled=Turn On the Bridge in Settings…
bridge.action.replace.title=Replace Connection?
bridge.action.replace.message="{0}" is connected to "{1}". Connect it to "{2}" instead? "{1}" keeps running in the IDE but stops exchanging phone messages.
bridge.action.replace.ok=Replace
bridge.notify.connected="{0}" is connected to {1}.
bridge.notify.disconnected=Phone disconnected from "{0}".
bridge.notify.kicked={0} was taken over by another IDE or client and will not reconnect automatically.
```

`ImuxBundle_zh_CN.properties`（简体中文，原文）：

```properties
bridge.status.connected=当前连接：{0} · {1} · {2}\n后续消息将发送到这个会话。
bridge.pairing.succeeded=配对成功，之后只接受你本人的消息。在 IDE 里把某个会话连接到我，就能在这里继续操作它。
bridge.pairing.expired=这个配对码已过期，请在 IDE 中重新生成。
bridge.settings.displayName=通讯机器人
bridge.settings.enable=启用终端会话通讯桥接
bridge.settings.enable.comment=手机消息提交到 IDE 里已打开的终端，由原来的 CLI 处理；不会启动新的 Agent 进程，IDEA MCP 照常可用。
bridge.settings.column.name=机器人
bridge.settings.column.channel=渠道
bridge.settings.column.status=状态
bridge.settings.column.session=当前连接的会话
bridge.settings.empty=还没有机器人。添加一次，之后可以反复连接不同会话。
bridge.settings.unbind=断开会话
bridge.settings.rename=新名称：
bridge.settings.remove.confirm=移除「{0}」？它的凭据会被删除，所连接的会话不再收发手机消息。
bridge.settings.note=机器人数量 = 想同时在手机上操作几个会话。会话标签关闭后，机器人自动回到空闲。
bridge.robot.status.stopped=已停止
bridge.robot.status.connecting=连接中…
bridge.robot.status.online=在线
bridge.robot.status.pairing=等待配对
bridge.robot.status.kicked=已被其它客户端接管
bridge.robot.status.failed=失败：{0}
bridge.robot.status.noCredentials=凭据丢失，请重新扫码
bridge.channel.wecom=企业微信
bridge.channel.feishu=飞书
bridge.channel.dingtalk=钉钉
bridge.channel.weixin=微信
bridge.channel.later=后续版本
bridge.add.title=添加机器人
bridge.add.pickChannel=选择渠道，只需手机扫码确认。
bridge.add.next=下一步
bridge.add.scan=用{0}扫码
bridge.add.scan.waiting=等待扫码，二维码 5 分钟内有效。
bridge.add.scan.starting=正在获取二维码…
bridge.add.scan.timeout=二维码已过期。
bridge.add.retry=重新获取二维码
bridge.add.verifyCode=输入手机上显示的数字：
bridge.add.verifyCode.submit=提交
bridge.add.pair.title=机器人已创建，请完成配对
bridge.add.pair.instruction=请在 10 分钟内，在{0}里把下面的配对码发给这个机器人：
bridge.add.pair.waiting=等待配对码…
bridge.add.pair.done=配对成功，现在可以把会话连接到这个机器人了。
bridge.add.pair.renew=重新生成配对码
bridge.add.defaultName=imux 机器人 {0}
bridge.add.failed=添加机器人失败：{0}
bridge.add.enabledBridge=已自动开启通讯桥接。
bridge.onboarding.noQr=渠道没有返回二维码。
bridge.onboarding.noCredentials=扫码成功，但没有返回机器人凭据。
bridge.onboarding.unsupported=这个渠道不需要验证码。
bridge.onboarding.network=网络错误：{0}
bridge.action.group=连接到手机
bridge.action.robot.idle={0}（空闲）
bridge.action.robot.bound={0}（正在连接：{1}）
bridge.action.robot.unpaired={0}（未配对）
bridge.action.robot.current={0}（已连接此会话）
bridge.action.add=添加机器人…
bridge.action.disconnect=断开手机连接
bridge.action.none=还没有机器人
bridge.action.disabled=先在设置中开启通讯桥接…
bridge.action.replace.title=替换连接？
bridge.action.replace.message=「{0}」正在连接「{1}」。改连到「{2}」后，「{1}」继续在 IDE 里运行，只是不再收发手机消息。
bridge.action.replace.ok=替换连接
bridge.notify.connected=「{0}」已连接到 {1}。
bridge.notify.disconnected=「{0}」已断开手机连接。
bridge.notify.kicked={0} 已被其它 IDE 或客户端接管，不会自动重连。
```

其余 8 个文件（`zh_TW`、`ja`、`ko`、`de`、`es`、`fr`、`pt_BR`、`ru`）追加同样的键，由实现者忠实翻译英文/中文原文：
- 保持占位符 `{0}`、`{1}`、`{2}` 与 `\n` 不变；
- 有占位符的行中的单引号写成 `''`（例如法语 `l''IDE`），无占位符的行写单个 `'`；
- 引号用 `"` 或该语言的书名号/引号，不用单引号包裹占位符。

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew test --tests '*BridgeRouterTest' --tests '*ImuxBundleTest'`
Expected: 全部 PASS（`BridgeRouterTest` 比原来多 2 个用例）

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge src/test/kotlin/com/github/izerui/imux/bridge/core/BridgeRouterTest.kt src/main/resources/messages
git commit -m "feat(bridge): 绑定后通知当前连接，并补齐机器人管理文案"
```

---

### Task 8: 机器人管理器、绑定持久化与运行时开关

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/robot/RobotManager.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/BridgeLifecycle.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/BridgeHub.kt`（新增 `detachRobot`）
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/TerminalBridgeService.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/BridgeStartupActivity.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/robot/BindingPersistenceTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/robot/RobotManagerSourceTest.kt`

**Interfaces:**
- Consumes: Task 1–7 全部；核心 `BindingRegistry`、`Binding`、`SessionTarget`；`BridgeHub.getInstance()`（`bindings`、`router`、`inboundSink`、`registerRobot`、`unregisterRobot`）；`BridgeSettings`；`TerminalBridgeService.getInstance(project).start()`；`SessionMonitor.getInstance(project).model.sessionOf(id)`
- Produces:
  - `RobotManager.getInstance()`：`robots(): List<RobotRecord>`、`robot(id)`、`statusOf(id): ConnectionStatus?`、`val revision: StateFlow<Long>`（任何机器人、状态、绑定变化时加一，供界面刷新）、`suspend fun add(kind, credentials, ownerId: String?, name: String): RobotRecord`、`renewPairCode(id): RobotRecord?`、`rename(id, name)`、`suspend fun remove(id)`、`startAll()`、`stopAll()`、`syncBindings()`、`fun launch(block: suspend CoroutineScope.() -> Unit): Job`、`notifyKicked: (RobotRecord) -> Unit`（UI 层在 Task 10 设置）
  - `BridgeHub.detachRobot(robotId: RobotId)`：只摘除渠道与本人，不动绑定
  - `object BridgeLifecycle { fun applyEnabled(enabled: Boolean); fun sessionTitle(binding: Binding): String }`
  - 纯函数：`internal fun bindingsToSave(snapshot: List<Binding>): List<SavedBinding>`、`internal fun restoredTarget(saved: SavedBinding): SessionTarget?`（tabId 为 `"restored:" + sessionId`，未知 cli 返回 null）

关键行为：
- 应用启动时（`RobotManager` 构造）把持久化的绑定恢复进 `BindingRegistry`；恢复的 tabId 不在任何打开的标签中，`tabChangeFor` 会在会话以新标签打开时 `Reattach`，在本服务没见过该 tabId 时 `Keep`（离线）。
- 认主流程：渠道入站先经过 `Pairing.decide`：`Pass` → `BridgeHub.inboundSink`；`Paired` → 存本人、`registerRobot(conn, owner)`、给本人发 `pairingSucceeded`；`Expired` → 给发送者发 `pairingExpired`；`Ignore` → 丢弃。
- `TerminalBridgeService`：开关关闭时轮询循环与轮次事件处理直接跳过；`release` 与 `onMigrated` 之后调用 `RobotManager.getInstance().syncBindings()`。
- `BridgeLifecycle.applyEnabled(true)`：`startAll()`，并对所有打开的项目 `TerminalBridgeService.start()`；`false`：`stopAll()`（绑定保留）。

- [ ] **Step 1: 写失败测试**

`BindingPersistenceTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BindingPersistenceTest {
    @Test
    fun `只保存已有原生会话 id 的绑定`() {
        val snapshot =
            listOf(
                Binding(RobotId("a"), SessionTarget("/p", "tab-1", AgentType.CODEX, "s1"), 1),
                Binding(RobotId("b"), SessionTarget("/p", "tab-2", AgentType.CLAUDE, null), 2),
            )
        assertEquals(listOf(SavedBinding("a", "/p", "codex", "s1")), bindingsToSave(snapshot))
    }

    @Test
    fun `恢复时使用占位 tabId 并按 cli 还原 Agent`() {
        assertEquals(
            SessionTarget("/p", "restored:s1", AgentType.PI, "s1"),
            restoredTarget(SavedBinding("a", "/p", "pi", "s1")),
        )
        assertNull(restoredTarget(SavedBinding("a", "/p", "unknown-cli", "s1")))
    }
}
```

`RobotManagerSourceTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotManagerSourceTest {
    private val manager = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/robot/RobotManager.kt").normalized
    private val service = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/TerminalBridgeService.kt").normalized
    private val startup = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BridgeStartupActivity.kt").normalized

    @Test
    fun `入站先经过配对判定`() {
        assertTrue(manager.contains("Pairing.decide("))
        assertTrue(manager.contains("hub.inboundSink.accept("))
    }

    @Test
    fun `开关关闭时不建立连接`() {
        assertTrue(manager.contains("if (!BridgeSettings.getInstance().state.enabled) return"))
    }

    @Test
    fun `凭据只经凭据库读写`() {
        assertTrue(manager.contains("vault.save("))
        assertTrue(manager.contains("vault.load("))
        assertTrue(manager.contains("vault.delete("))
    }

    @Test
    fun `观察服务在开关关闭时不轮询并同步持久化`() {
        assertTrue(service.contains("BridgeSettings.getInstance().state.enabled"))
        assertTrue(service.contains("RobotManager.getInstance().syncBindings()"))
    }

    @Test
    fun `启动时恢复机器人连接`() {
        assertTrue(startup.contains("RobotManager.getInstance().startAll()"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*BindingPersistenceTest' --tests '*RobotManagerSourceTest'`
Expected: 编译失败，`Unresolved reference: bindingsToSave`

- [ ] **Step 3: `BridgeHub` 新增 `detachRobot`**

在 `unregisterRobot` 之后加入：

```kotlin
    /** 停止连接时调用：摘除渠道与本人，但保留绑定，重新连接后继续生效。 */
    fun detachRobot(robotId: RobotId) {
        channels.remove(robotId)
        owners.remove(robotId)
    }
```

- [ ] **Step 4: 实现 `RobotManager.kt`**

```kotlin
package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.BridgeSettings
import com.github.izerui.imux.bridge.BundleBridgeTexts
import com.github.izerui.imux.bridge.channel.ChannelConnection
import com.github.izerui.imux.bridge.channel.ChannelFactories
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.OutboundMessage
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal fun bindingsToSave(snapshot: List<Binding>): List<SavedBinding> =
    snapshot.mapNotNull { binding ->
        val sessionId = binding.target.sessionId ?: return@mapNotNull null
        SavedBinding(binding.robotId.value, binding.target.projectPath, binding.target.agentType.cli, sessionId)
    }

internal fun restoredTarget(saved: SavedBinding): SessionTarget? {
    val agentType = AgentType.entries.firstOrNull { it.cli == saved.agentCli } ?: return null
    return SessionTarget(saved.projectPath, RESTORED_TAB_PREFIX + saved.sessionId, agentType, saved.sessionId)
}

private const val RESTORED_TAB_PREFIX = "restored:"

/**
 * 机器人的生命周期：凭据、连接、认主、状态，以及绑定的持久化。
 *
 * 只在开关打开时建立连接。每个机器人一条连接；被别处接管后不重连，提示用户。
 */
@Service(Service.Level.APP)
class RobotManager(parentScope: CoroutineScope) {
    private val scope =
        CoroutineScope(
            parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, e -> LOG.warn("机器人任务异常，已隔离", e) },
        )
    private val store get() = RobotStore.getInstance()
    private val hub get() = BridgeHub.getInstance()
    private val vault: CredentialVault = PasswordSafeVault()
    private val connections = ConcurrentHashMap<String, ChannelConnection>()
    private val statuses = ConcurrentHashMap<String, ConnectionStatus>()
    private val _revision = MutableStateFlow(0L)

    /** 机器人、状态或绑定任何变化时加一，界面据此刷新。 */
    val revision: StateFlow<Long> = _revision

    /** 由界面层设置：机器人被别处接管时的提示。 */
    @Volatile
    var notifyKicked: (RobotRecord) -> Unit = {}

    init {
        store.book().bindings.forEach { saved ->
            val target = restoredTarget(saved) ?: return@forEach
            hub.bindings.bind(RobotId(saved.robotId), target)
        }
    }

    fun robots(): List<RobotRecord> = store.book().robots

    fun robot(id: String): RobotRecord? = store.book().robot(id)

    fun statusOf(id: String): ConnectionStatus? = statuses[id]

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    fun startAll() {
        if (!BridgeSettings.getInstance().state.enabled) return
        robots().forEach { record -> scope.launch(Dispatchers.IO) { connect(record) } }
    }

    fun stopAll() {
        connections.keys.toList().forEach(::disconnect)
        bump()
    }

    suspend fun add(
        kind: ChannelKind,
        credentials: Map<String, String>,
        ownerId: String?,
        name: String,
    ): RobotRecord {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        withContext(Dispatchers.IO) { vault.save(id, credentials) }
        val record =
            RobotRecord(
                id = id,
                name = name,
                kind = kind,
                ownerSenderId = ownerId,
                ownerChatId = ownerId,
                pairCode = if (ownerId == null) Pairing.newCode() else null,
                pairCodeExpiresAtMillis = if (ownerId == null) now + Pairing.TTL_MILLIS else 0,
                createdAtMillis = now,
            )
        store.update { it.add(record) }
        bump()
        withContext(Dispatchers.IO) { connect(record) }
        return record
    }

    fun renewPairCode(id: String): RobotRecord? {
        val book = store.update { it.withPairCode(id, Pairing.newCode(), System.currentTimeMillis() + Pairing.TTL_MILLIS) }
        bump()
        return book.robot(id)
    }

    fun rename(
        id: String,
        name: String,
    ) {
        store.update { it.rename(id, name) }
        bump()
    }

    suspend fun remove(id: String) {
        disconnect(id)
        hub.unregisterRobot(RobotId(id))
        store.update { it.remove(id) }
        withContext(Dispatchers.IO) { vault.delete(id) }
        syncBindings()
    }

    /** 绑定表是权威来源；把当前快照写入持久化。 */
    fun syncBindings() {
        store.update { it.withBindings(bindingsToSave(hub.bindings.snapshot())) }
        bump()
    }

    private fun connect(record: RobotRecord) {
        if (!BridgeSettings.getInstance().state.enabled) return
        if (connections.containsKey(record.id)) return
        val factory = ChannelFactories.forKind(record.kind) ?: return
        val credentials = vault.load(record.id)
        if (credentials == null) {
            setStatus(record.id, ConnectionStatus.Failed("noCredentials"))
            return
        }
        val connection = factory.connect(RobotId(record.id), credentials, scope)
        if (connections.putIfAbsent(record.id, connection) != null) return
        hub.registerRobot(connection, record.owner())
        connection.start(pairingSink(record.id, connection)) { status ->
            setStatus(record.id, status)
            if (status == ConnectionStatus.Kicked) robot(record.id)?.let { notifyKicked(it) }
        }
    }

    private fun disconnect(id: String) {
        connections.remove(id)?.stop()
        hub.detachRobot(RobotId(id))
        statuses.remove(id)
    }

    private fun pairingSink(
        id: String,
        connection: ChannelConnection,
    ) = InboundSink { message ->
        val record = robot(id) ?: return@InboundSink
        when (val decision = Pairing.decide(record, message, System.currentTimeMillis())) {
            PairDecision.Pass -> hub.inboundSink.accept(message)
            is PairDecision.Paired -> {
                store.update { it.withOwner(id, decision.owner) }
                hub.registerRobot(connection, decision.owner)
                bump()
                scope.launch {
                    connection.send(OutboundMessage(connection.robotId, decision.owner.chatId, null, BundleBridgeTexts.pairingSucceeded()))
                }
            }
            PairDecision.Expired ->
                scope.launch {
                    connection.send(OutboundMessage(connection.robotId, message.chatId, null, BundleBridgeTexts.pairingExpired()))
                }
            PairDecision.Ignore -> Unit
        }
    }

    private fun setStatus(
        id: String,
        status: ConnectionStatus,
    ) {
        statuses[id] = status
        bump()
    }

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    companion object {
        private val LOG = logger<RobotManager>()

        fun getInstance(): RobotManager = ApplicationManager.getApplication().getService(RobotManager::class.java)
    }
}
```

- [ ] **Step 5: 实现 `BridgeLifecycle.kt`**

```kotlin
package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.robot.RobotManager
import com.github.izerui.imux.monitor.SessionMonitor
import com.intellij.openapi.project.ProjectManager

/** 开关切换与会话标题查询。两者都要跨项目查找，集中在这里。 */
object BridgeLifecycle {
    fun applyEnabled(enabled: Boolean) {
        val manager = RobotManager.getInstance()
        if (!enabled) {
            manager.stopAll()
            return
        }
        manager.startAll()
        ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .forEach { TerminalBridgeService.getInstance(it).start() }
    }

    fun sessionTitle(binding: Binding): String {
        val sessionId = binding.target.sessionId ?: return binding.target.tabId
        val project = ProjectManager.getInstance().openProjects.firstOrNull { it.basePath == binding.target.projectPath && !it.isDisposed }
        return project?.let { SessionMonitor.getInstance(it).model.sessionOf(sessionId)?.title } ?: sessionId.take(8)
    }
}
```

- [ ] **Step 6: 修改 `TerminalBridgeService.kt`**

1. 轮询循环：在 `for (binding in boundHere())` 之前加入 `if (BridgeSettings.getInstance().state.enabled) {` … `}`，即开关关闭时本轮不读取任何记录，仍按间隔 `delay`。
2. `turnCompleted` 与 `turnWaiting` 回调的开头各加一行：`if (!BridgeSettings.getInstance().state.enabled) return`。
3. `release(binding)`：在 `hub.bindings.unbind(binding.robotId)` 之后加入 `RobotManager.getInstance().syncBindings()`。
4. `onMigrated(to)`：在 `migrateSession` 成功分支内加入 `RobotManager.getInstance().syncBindings()`。
5. 新增 import：`com.github.izerui.imux.bridge.robot.RobotManager`。

- [ ] **Step 7: 修改 `BridgeStartupActivity.kt`**

```kotlin
class BridgeStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!BridgeSettings.getInstance().state.enabled) return
        RobotManager.getInstance().startAll()
        if (!project.isDisposed) TerminalBridgeService.getInstance(project).start()
    }
}
```

（`startAll` 幂等：已有连接的机器人不会重复连接。）新增 import `com.github.izerui.imux.bridge.robot.RobotManager`。

- [ ] **Step 8: 运行测试确认通过，含第一份计划的相关测试**

Run: `./gradlew test --tests '*BindingPersistenceTest' --tests '*RobotManagerSourceTest' --tests '*BridgeWiringSourceTest' --tests '*BridgeIsolationSourceTest' --tests '*ChannelIsolationSourceTest'`
Expected: 全部 PASS。若 `BridgeWiringSourceTest` 因开关判断改变了被断言的源码片段而失败，按归一化后的实际代码更新断言字符串，保持断言意图不变。

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge src/test/kotlin/com/github/izerui/imux/bridge
git commit -m "feat(bridge): 新增机器人管理器、绑定持久化与运行时开关"
```

---

### Task 9: 二维码、扫码流程驱动、设置页与添加机器人对话框

**Files:**
- Modify: `build.gradle.kts`（`dependencies` 中 `sqlite-jdbc` 之后加入 ZXing）
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/ui/QrCodes.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/ui/OnboardingDriver.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/ui/RobotRows.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/ui/AddRobotDialog.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/ui/RobotsConfigurable.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`（在 LSP 子页之后注册）
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/ui/QrCodesTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/ui/OnboardingDriverTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/ui/RobotRowsTest.kt`

**Interfaces:**
- Consumes: `ChannelOnboarding`、`OnboardingStep`、`ConnectionStatus`、`ChannelFactories`（Task 3、6）；`RobotManager`、`RobotRecord`、`ChannelKind`（Task 1、8）；`BridgeSettings`、`BridgeLifecycle`、`BridgeHub`
- Produces: `fun qrImage(content: String, size: Int): BufferedImage`；`sealed interface DriverState { Starting; Qr(content); NeedVerifyCode; Done(step: OnboardingStep.Succeeded); Error(messageKey, detail: String?); TimedOut }`；`class OnboardingDriver(onboarding, onState, sleep = { delay(it) }, now = System::currentTimeMillis, timeoutMillis = 300_000) { suspend fun run(); fun submitVerifyCode(code) }`；`data class RobotRow(id, name, channel, status, session: String?)`；`interface RowLabels { channel(kind); status(status: ConnectionStatus?, paired: Boolean) }`；`fun robotRows(robots, statusOf, bindingOf, titleOf, labels): List<RobotRow>`；`class AddRobotDialog(project: Project?) : DialogWrapper`；`class RobotsConfigurable : BoundConfigurable`；`fun channelLabel(kind): String`、`fun statusLabel(status, paired): String`

设置页布局（原型第 1–6 步）：顶部“启用终端会话通讯桥接”复选框及说明；中间机器人表格（列：机器人、渠道、状态、当前连接的会话），工具条：添加、移除、改名、断开会话；底部说明“机器人数量 = 同时在手机上操作的会话数”。添加对话框依次为：选择渠道（只有已实现工厂的渠道可选，其余标“后续版本”）→ 二维码（等待、过期可重试、需要时输入验证码）→ 本人确认：扫码带本人身份（飞书、普通微信）则直接完成，否则显示配对码与倒计时说明，直到 `RobotManager` 记录已认主 → 完成。添加机器人时若开关关闭，自动开启并提示。

- [ ] **Step 1: 加入 ZXing 依赖**

`build.gradle.kts` 的 `dependencies` 中，`implementation("org.xerial:sqlite-jdbc:3.49.1.0")` 之后加入：

```kotlin
    // 通讯机器人扫码：在 IDE 内绘制二维码。平台未捆绑 ZXing；只用 core，不引入 javase。
    implementation("com.google.zxing:core:3.5.3")
```

- [ ] **Step 2: 写失败测试**

`QrCodesTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class QrCodesTest {
    @Test
    fun `生成的二维码可以被解码回原文`() {
        val content = "https://work.weixin.qq.com/ai/qc/c?s=SCODE&hide_more_btn=true"
        val image = qrImage(content, 240)
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))
        assertEquals(content, QRCodeReader().decode(bitmap).text)
    }
}
```

`OnboardingDriverTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.channel.OnboardingStep
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class OnboardingDriverTest {
    private class Scripted(
        private val start: OnboardingStep,
        polls: List<OnboardingStep>,
        private val afterCode: OnboardingStep = OnboardingStep.Waiting,
    ) : ChannelOnboarding {
        private val queue = ArrayDeque(polls)
        var code: String? = null
        override val pollIntervalMillis = 10L

        override suspend fun start() = start

        override suspend fun poll(): OnboardingStep = queue.removeFirstOrNull() ?: throw IOException("boom")

        override suspend fun submitVerifyCode(code: String): OnboardingStep {
            this.code = code
            return afterCode
        }
    }

    private val states = mutableListOf<DriverState>()

    @Test
    fun `显示二维码，等待后成功`() =
        runBlocking {
            val success = OnboardingStep.Succeeded(mapOf("k" to "v"), null, null)
            OnboardingDriver(Scripted(OnboardingStep.ShowQr("QR"), listOf(OnboardingStep.Waiting, success)), states::add, sleep = {}).run()
            assertEquals(listOf(DriverState.Starting, DriverState.Qr("QR"), DriverState.Done(success)), states)
        }

    @Test
    fun `超时`() =
        runBlocking {
            var clock = 0L
            val driver = OnboardingDriver(Scripted(OnboardingStep.ShowQr("QR"), List(10) { OnboardingStep.Waiting }), states::add, sleep = { clock += it }, now = { clock }, timeoutMillis = 25)
            driver.run()
            assertEquals(DriverState.TimedOut, states.last())
        }

    @Test
    fun `需要验证码时等待输入并提交`() =
        runBlocking {
            val success = OnboardingStep.Succeeded(emptyMap(), "owner", null)
            val onboarding = Scripted(OnboardingStep.ShowQr("QR"), listOf(OnboardingStep.NeedVerifyCode), afterCode = success)
            val driver = OnboardingDriver(onboarding, states::add, sleep = {})
            val job = launch { driver.run() }
            while (DriverState.NeedVerifyCode !in states) yield()
            driver.submitVerifyCode("1234")
            job.join()
            assertEquals("1234", onboarding.code)
            assertEquals(DriverState.Done(success), states.last())
        }

    @Test
    fun `渠道失败与网络异常`() =
        runBlocking {
            OnboardingDriver(Scripted(OnboardingStep.Failed("bridge.onboarding.noQr"), emptyList()), states::add, sleep = {}).run()
            assertEquals(DriverState.Error("bridge.onboarding.noQr", null), states.last())
            states.clear()
            OnboardingDriver(Scripted(OnboardingStep.ShowQr("QR"), emptyList()), states::add, sleep = {}).run()
            assertEquals(DriverState.Error("bridge.onboarding.network", "boom"), states.last())
        }
}
```

`RobotRowsTest.kt`：

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
import org.junit.Test

class RobotRowsTest {
    private val labels =
        object : RowLabels {
            override fun channel(kind: ChannelKind) = kind.id

            override fun status(
                status: ConnectionStatus?,
                paired: Boolean,
            ) = if (!paired) "pairing" else (status?.toString() ?: "none")
        }

    @Test
    fun `表格行包含渠道、状态与当前会话`() {
        val robots =
            listOf(
                RobotRecord("a", "开发助手 1", ChannelKind.WECOM, ownerSenderId = "u", ownerChatId = "u"),
                RobotRecord("b", "开发助手 2", ChannelKind.WECOM, pairCode = "123456"),
            )
        val binding = Binding(RobotId("a"), SessionTarget("/p", "t", AgentType.CODEX, "s"), 1)
        val rows =
            robotRows(
                robots,
                statusOf = { if (it == "a") ConnectionStatus.Online else null },
                bindingOf = { if (it == "a") binding else null },
                titleOf = { "修复终端滚动" },
                labels = labels,
            )
        assertEquals(
            listOf(
                RobotRow("a", "开发助手 1", "wecom", "Online", "修复终端滚动"),
                RobotRow("b", "开发助手 2", "wecom", "pairing", null),
            ),
            rows,
        )
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./gradlew test --tests '*QrCodesTest' --tests '*OnboardingDriverTest' --tests '*RobotRowsTest'`
Expected: 编译失败，`Unresolved reference: qrImage`

- [ ] **Step 4: 实现 `QrCodes.kt`**

```kotlin
package com.github.izerui.imux.bridge.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage

/** 黑白二维码图像。只用 ZXing core，直接把 BitMatrix 画进 BufferedImage。 */
fun qrImage(
    content: String,
    size: Int,
): BufferedImage {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            image.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
        }
    }
    return image
}
```

- [ ] **Step 5: 实现 `OnboardingDriver.kt`**

```kotlin
package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.channel.ChannelOnboarding
import com.github.izerui.imux.bridge.channel.OnboardingStep
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

sealed interface DriverState {
    data object Starting : DriverState

    data class Qr(val content: String) : DriverState

    data object NeedVerifyCode : DriverState

    data class Done(val step: OnboardingStep.Succeeded) : DriverState

    data class Error(
        val messageKey: String,
        val detail: String?,
    ) : DriverState

    data object TimedOut : DriverState
}

/**
 * 驱动一次扫码接入：申请二维码、按渠道间隔轮询、需要时等待验证码、总超时。
 * 与 Swing 无关，对话框只负责把状态画出来。
 */
class OnboardingDriver(
    private val onboarding: ChannelOnboarding,
    private val onState: (DriverState) -> Unit,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = 5 * 60_000L,
) {
    private val verifyCodes = Channel<String>(Channel.CONFLATED)

    fun submitVerifyCode(code: String) {
        verifyCodes.trySend(code)
    }

    suspend fun run() {
        onState(DriverState.Starting)
        val deadline = now() + timeoutMillis
        var step = guard { onboarding.start() } ?: return
        while (true) {
            when (val current = step) {
                is OnboardingStep.ShowQr -> onState(DriverState.Qr(current.content))
                OnboardingStep.Waiting -> Unit
                OnboardingStep.NeedVerifyCode -> {
                    onState(DriverState.NeedVerifyCode)
                    val code = verifyCodes.receive()
                    step = guard { onboarding.submitVerifyCode(code) } ?: return
                    continue
                }
                is OnboardingStep.Succeeded -> {
                    onState(DriverState.Done(current))
                    return
                }
                is OnboardingStep.Failed -> {
                    onState(DriverState.Error(current.messageKey, null))
                    return
                }
            }
            if (now() >= deadline) {
                onState(DriverState.TimedOut)
                return
            }
            sleep(onboarding.pollIntervalMillis)
            step = guard { onboarding.poll() } ?: return
        }
    }

    private suspend fun guard(block: suspend () -> OnboardingStep): OnboardingStep? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onState(DriverState.Error("bridge.onboarding.network", e.message ?: e.javaClass.simpleName))
            null
        }
}
```

- [ ] **Step 6: 实现 `RobotRows.kt`**

```kotlin
package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.channel.ConnectionStatus
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.robot.ChannelKind
import com.github.izerui.imux.bridge.robot.RobotRecord

data class RobotRow(
    val id: String,
    val name: String,
    val channel: String,
    val status: String,
    val session: String?,
)

interface RowLabels {
    fun channel(kind: ChannelKind): String

    fun status(
        status: ConnectionStatus?,
        paired: Boolean,
    ): String
}

fun robotRows(
    robots: List<RobotRecord>,
    statusOf: (String) -> ConnectionStatus?,
    bindingOf: (String) -> Binding?,
    titleOf: (Binding) -> String,
    labels: RowLabels,
): List<RobotRow> =
    robots.map { robot ->
        RobotRow(
            id = robot.id,
            name = robot.name,
            channel = labels.channel(robot.kind),
            status = labels.status(statusOf(robot.id), paired = robot.owner() != null),
            session = bindingOf(robot.id)?.let(titleOf),
        )
    }

fun channelLabel(kind: ChannelKind): String = ImuxBundle.message("bridge.channel.${kind.id}")

fun statusLabel(
    status: ConnectionStatus?,
    paired: Boolean,
): String =
    when {
        status is ConnectionStatus.Failed && status.reason == "noCredentials" -> ImuxBundle.message("bridge.robot.status.noCredentials")
        status is ConnectionStatus.Failed -> ImuxBundle.message("bridge.robot.status.failed", status.reason)
        status == ConnectionStatus.Kicked -> ImuxBundle.message("bridge.robot.status.kicked")
        status == ConnectionStatus.Connecting -> ImuxBundle.message("bridge.robot.status.connecting")
        status == ConnectionStatus.Online && !paired -> ImuxBundle.message("bridge.robot.status.pairing")
        status == ConnectionStatus.Online -> ImuxBundle.message("bridge.robot.status.online")
        else -> ImuxBundle.message("bridge.robot.status.stopped")
    }

object BundleRowLabels : RowLabels {
    override fun channel(kind: ChannelKind) = channelLabel(kind)

    override fun status(
        status: ConnectionStatus?,
        paired: Boolean,
    ) = statusLabel(status, paired)
}
```

- [ ] **Step 7: 实现 `AddRobotDialog.kt`**

```kotlin
package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BridgeLifecycle
import com.github.izerui.imux.bridge.BridgeSettings
import com.github.izerui.imux.bridge.channel.ChannelFactories
import com.github.izerui.imux.bridge.channel.OnboardingStep
import com.intellij.CommonBundle
import com.github.izerui.imux.bridge.robot.ChannelKind
import com.github.izerui.imux.bridge.robot.RobotManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 添加机器人：选渠道 → 扫码 →（需要时）配对 → 完成。
 * 网络与轮询在 RobotManager 的协程里进行，界面状态经 invokeLater(ModalityState.any()) 刷新。
 */
class AddRobotDialog(project: Project?) : DialogWrapper(project, true) {
    private val manager = RobotManager.getInstance()
    private val cards = CardLayout()
    private val root = JPanel(cards)
    private val radios = ChannelKind.entries.associateWith { JBRadioButton(label(it)) }
    private val qrLabel = JBLabel()
    private val qrStatus = JBLabel()
    private val verifyField = JBTextField(8)
    private val verifyButton = JButton(ImuxBundle.message("bridge.add.verifyCode.submit"))
    private val verifyPanel = JPanel()
    private val retryButton = JButton(ImuxBundle.message("bridge.add.retry"))
    private val pairCode = JBLabel()
    private val pairInstruction = JBLabel()
    private val pairStatus = JBLabel()
    private val renewButton = JButton(ImuxBundle.message("bridge.add.pair.renew"))
    private var job: Job? = null
    private var driver: OnboardingDriver? = null
    private var kind: ChannelKind = ChannelKind.WECOM
    private var robotId: String? = null
    private var step = Card.CHANNEL

    private enum class Card { CHANNEL, QR, PAIR, DONE }

    init {
        title = ImuxBundle.message("bridge.add.title")
        setOKButtonText(ImuxBundle.message("bridge.add.next"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val group = ButtonGroup()
        val channelPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JBLabel(ImuxBundle.message("bridge.add.pickChannel")))
                radios.forEach { (kind, radio) ->
                    radio.isEnabled = ChannelFactories.forKind(kind) != null
                    group.add(radio)
                    add(radio)
                }
            }
        radios.getValue(ChannelKind.WECOM).isSelected = true

        verifyPanel.apply {
            add(JBLabel(ImuxBundle.message("bridge.add.verifyCode")))
            add(verifyField)
            add(verifyButton)
            isVisible = false
        }
        verifyButton.addActionListener { driver?.submitVerifyCode(verifyField.text.trim()) }
        retryButton.addActionListener { startScan() }
        retryButton.isVisible = false
        val qrPanel =
            JPanel(BorderLayout(JBUI.scale(16), 0)).apply {
                add(qrLabel, BorderLayout.WEST)
                add(
                    JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        add(qrStatus)
                        add(verifyPanel)
                        add(retryButton)
                    },
                    BorderLayout.CENTER,
                )
            }

        pairCode.font = pairCode.font.deriveFont(Font.BOLD, JBUI.scaleFontSize(32f).toFloat())
        renewButton.addActionListener { robotId?.let { manager.renewPairCode(it) }?.let { showPair(it.pairCode.orEmpty()) } }
        val pairPanel =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JBLabel(ImuxBundle.message("bridge.add.pair.title")))
                add(pairInstruction)
                add(pairCode)
                add(pairStatus)
                add(renewButton)
            }
        val donePanel = JPanel().apply { add(JBLabel(ImuxBundle.message("bridge.add.pair.done"))) }

        root.add(channelPanel, Card.CHANNEL.name)
        root.add(qrPanel, Card.QR.name)
        root.add(pairPanel, Card.PAIR.name)
        root.add(donePanel, Card.DONE.name)
        root.preferredSize = JBUI.size(520, 300)
        return root
    }

    override fun doOKAction() {
        when (step) {
            Card.CHANNEL -> {
                kind = radios.entries.first { it.value.isSelected }.key
                ensureBridgeEnabled()
                show(Card.QR)
                isOKActionEnabled = false
                startScan()
            }
            Card.DONE -> super.doOKAction()
            else -> Unit
        }
    }

    override fun dispose() {
        job?.cancel()
        super.dispose()
    }

    private fun startScan() {
        job?.cancel()
        retryButton.isVisible = false
        verifyPanel.isVisible = false
        val factory = ChannelFactories.forKind(kind) ?: return
        val current = OnboardingDriver(factory.onboarding(), { state -> ui { render(state) } })
        driver = current
        job = manager.launch { current.run() }
    }

    private fun render(state: DriverState) {
        when (state) {
            DriverState.Starting -> qrStatus.text = ImuxBundle.message("bridge.add.scan.starting")
            is DriverState.Qr -> {
                qrLabel.icon = ImageIcon(qrImage(state.content, JBUI.scale(200)))
                qrStatus.text = ImuxBundle.message("bridge.add.scan", label(kind)) + " · " + ImuxBundle.message("bridge.add.scan.waiting")
            }
            DriverState.NeedVerifyCode -> verifyPanel.isVisible = true
            DriverState.TimedOut -> {
                qrStatus.text = ImuxBundle.message("bridge.add.scan.timeout")
                retryButton.isVisible = true
            }
            is DriverState.Error -> {
                val reason = if (state.detail != null) ImuxBundle.message(state.messageKey, state.detail) else ImuxBundle.message(state.messageKey)
                qrStatus.text = ImuxBundle.message("bridge.add.failed", reason)
                retryButton.isVisible = true
            }
            is DriverState.Done -> created(state.step)
        }
    }

    private fun created(result: OnboardingStep.Succeeded) {
        val name = result.suggestedName ?: ImuxBundle.message("bridge.add.defaultName", manager.robots().size + 1)
        job =
            manager.launch {
                val record = manager.add(kind, result.credentials, result.ownerId, name)
                robotId = record.id
                ui {
                    if (record.owner() != null) finish() else showPair(record.pairCode.orEmpty())
                }
                while (manager.robot(record.id)?.owner() == null) delay(500)
                ui { finish() }
            }
    }

    private fun showPair(code: String) {
        pairInstruction.text = ImuxBundle.message("bridge.add.pair.instruction", label(kind))
        pairCode.text = code.chunked(3).joinToString(" ")
        pairStatus.text = ImuxBundle.message("bridge.add.pair.waiting")
        show(Card.PAIR)
    }

    private fun finish() {
        show(Card.DONE)
        setOKButtonText(CommonBundle.getOkButtonText())
        isOKActionEnabled = true
    }

    private fun ensureBridgeEnabled() {
        val settings = BridgeSettings.getInstance()
        if (settings.state.enabled) return
        settings.state.enabled = true
        BridgeLifecycle.applyEnabled(true)
    }

    private fun show(card: Card) {
        step = card
        cards.show(root, card.name)
    }

    private fun label(kind: ChannelKind): String =
        channelLabel(kind) + if (ChannelFactories.forKind(kind) == null) "（" + ImuxBundle.message("bridge.channel.later") + "）" else ""

    private fun ui(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!isDisposed) block() }, ModalityState.any())
    }
}
```

`label()` 中的全角括号仅用于标注“后续版本”，与原型一致。

- [ ] **Step 8: 实现 `RobotsConfigurable.kt`**

```kotlin
package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.BridgeLifecycle
import com.github.izerui.imux.bridge.BridgeSettings
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.robot.RobotManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import kotlinx.coroutines.launch
import javax.swing.Timer
import javax.swing.table.AbstractTableModel

/** 设置 › 工具 › Imux › 通讯机器人。机器人管理与会话管理分开：这里只管机器人。 */
class RobotsConfigurable : BoundConfigurable(ImuxBundle.message("bridge.settings.displayName")) {
    private val model = RobotTableModel()
    private var timer: Timer? = null

    override fun createPanel(): DialogPanel {
        val settings = BridgeSettings.getInstance()
        val table =
            JBTable(model).apply {
                setShowGrid(false)
                emptyText.text = ImuxBundle.message("bridge.settings.empty")
            }
        val decorated =
            ToolbarDecorator.createDecorator(table)
                .setAddAction {
                    AddRobotDialog(null).show()
                    refresh()
                }
                .setRemoveAction { remove(table) }
                .setEditAction { rename(table) }
                .addExtraAction(
                    object : DumbAwareAction(ImuxBundle.message("bridge.settings.unbind"), null, AllIcons.Actions.Cancel) {
                        override fun getActionUpdateThread() = ActionUpdateThread.EDT

                        override fun update(e: AnActionEvent) {
                            e.presentation.isEnabled = selected(table)?.session != null
                        }

                        override fun actionPerformed(e: AnActionEvent) = unbind(table)
                    },
                ).createPanel()
        refresh()
        timer = Timer(1_000) { refresh() }.also { it.start() }
        return panel {
            row {
                checkBox(ImuxBundle.message("bridge.settings.enable"))
                    .bindSelected(settings.state::enabled)
                    .comment(ImuxBundle.message("bridge.settings.enable.comment"))
            }
            row { cell(decorated).align(Align.FILL) }.resizableRow()
            row { comment(ImuxBundle.message("bridge.settings.note")) }
        }
    }

    override fun apply() {
        val before = BridgeSettings.getInstance().state.enabled
        super.apply()
        val after = BridgeSettings.getInstance().state.enabled
        if (before != after) BridgeLifecycle.applyEnabled(after)
    }

    override fun disposeUIResources() {
        timer?.stop()
        timer = null
        super.disposeUIResources()
    }

    private fun refresh() {
        val manager = RobotManager.getInstance()
        val hub = BridgeHub.getInstance()
        model.rows =
            robotRows(
                manager.robots(),
                statusOf = manager::statusOf,
                bindingOf = { hub.bindings.bindingOf(RobotId(it)) },
                titleOf = BridgeLifecycle::sessionTitle,
                labels = BundleRowLabels,
            )
    }

    private fun selected(table: JBTable): RobotRow? = table.selectedRow.takeIf { it >= 0 }?.let { model.rows.getOrNull(it) }

    private fun remove(table: JBTable) {
        val row = selected(table) ?: return
        val ok = Messages.showOkCancelDialog(ImuxBundle.message("bridge.settings.remove.confirm", row.name), ImuxBundle.message("bridge.settings.displayName"), Messages.getOkButton(), Messages.getCancelButton(), Messages.getWarningIcon())
        if (ok != Messages.OK) return
        val manager = RobotManager.getInstance()
        manager.launch { manager.remove(row.id) }
    }

    private fun rename(table: JBTable) {
        val row = selected(table) ?: return
        val name = Messages.showInputDialog(ImuxBundle.message("bridge.settings.rename"), ImuxBundle.message("bridge.settings.displayName"), null, row.name, null)
        if (!name.isNullOrBlank()) RobotManager.getInstance().rename(row.id, name.trim())
        refresh()
    }

    private fun unbind(table: JBTable) {
        val row = selected(table) ?: return
        val hub = BridgeHub.getInstance()
        val binding = hub.bindings.unbind(RobotId(row.id)) ?: return
        RobotManager.getInstance().syncBindings()
        hub.launch { hub.router.onReleased(binding, BridgeLifecycle.sessionTitle(binding)) }
        refresh()
    }

    private class RobotTableModel : AbstractTableModel() {
        var rows: List<RobotRow> = emptyList()
            set(value) {
                if (field == value) return
                field = value
                fireTableDataChanged()
            }

        private val columns =
            listOf("bridge.settings.column.name", "bridge.settings.column.channel", "bridge.settings.column.status", "bridge.settings.column.session")

        override fun getRowCount() = rows.size

        override fun getColumnCount() = columns.size

        override fun getColumnName(column: Int): String = ImuxBundle.message(columns[column])

        override fun getValueAt(
            rowIndex: Int,
            columnIndex: Int,
        ): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.name
                1 -> row.channel
                2 -> row.status
                else -> row.session ?: "—"
            }
        }
    }
}
```

`refresh()` 中 `fireTableDataChanged()` 会清掉表格选择；`rows` 的 setter 只在内容变化时触发，避免每秒刷新打断用户选择。

- [ ] **Step 9: 在 `plugin.xml` 注册设置页**

在 LSP 子页（`com.github.izerui.imux.settings.lsp`）之后加入：

```xml
        <applicationConfigurable
                parentId="com.github.izerui.imux.settings"
                instance="com.github.izerui.imux.bridge.ui.RobotsConfigurable"
                id="com.github.izerui.imux.settings.bridge"
                key="bridge.settings.displayName"
                bundle="messages.ImuxBundle"/>
```

- [ ] **Step 10: 运行测试与编译**

Run: `./gradlew test --tests '*QrCodesTest' --tests '*OnboardingDriverTest' --tests '*RobotRowsTest' --tests '*PluginXmlRegistrationTest'` 然后 `./gradlew compileKotlin`
Expected: 全部 PASS；编译无错误。若某个平台 API（如 `ToolbarDecorator.addExtraAction`、`BoundConfigurable`、`Messages.showInputDialog` 的重载）签名在 262 上不同，按编译器提示改用等价重载，并在报告里写明。

- [ ] **Step 11: Commit**

```bash
git add build.gradle.kts src/main/kotlin/com/github/izerui/imux/bridge/ui src/test/kotlin/com/github/izerui/imux/bridge/ui src/main/resources/META-INF/plugin.xml
git commit -m "feat(bridge): 新增通讯机器人设置页与扫码添加对话框"
```

---

### Task 10: 终端右键「连接到手机」与通知

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneMenu.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneActions.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`（终端右键菜单组、通知分组、应用启动时设置被踢提示）
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/ui/PhoneMenuTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/ui/PhoneActionsSourceTest.kt`

**Interfaces:**
- Consumes: `RobotManager`、`RobotRecord`（Task 1、8）；`BridgeHub`（`bindings.bind/unbind/bindingOf/bindingForTab`、`router.onBound/onReleased`、`launch`）；`BridgeLifecycle`；`TerminalBridgeService`；`TerminalHost.getInstance(project).fileFor(view)`、`TerminalView.DATA_KEY`；`SessionMonitor`；`AddRobotDialog`、`RobotsConfigurable`（Task 9）
- Produces: `sealed interface PhoneMenuEntry { Robot(robotId, label, enabled); NoRobots; AddRobot; Disconnect(robotId) }`；`interface MenuLabels { idle(name); bound(name, session); unpaired(name); current(name) }`；`fun phoneMenuModel(robots, bindingOf, thisTabId, titleOf, labels): List<PhoneMenuEntry>`；`class ConnectToPhoneGroup : ActionGroup`；`object BridgeNotifications { fun info(project: Project?, text: String) }`

菜单规则（原型第 7、11 步）：每个机器人一项；未认主 → 禁用；已连接此会话 → 禁用并标“已连接此会话”；连接着别的会话 → 可选，选择时先确认替换；空闲 → 可选。没有机器人 → 一条禁用的“还没有机器人”。之后是分隔线和“添加机器人…”。此会话已连接时末尾加“断开手机连接”。开关关闭时整个子菜单只有一项“先在设置中开启通讯桥接…”，打开设置页。

- [ ] **Step 1: 写失败测试**

`PhoneMenuTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.bridge.robot.ChannelKind
import com.github.izerui.imux.bridge.robot.RobotRecord
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneMenuTest {
    private val labels =
        object : MenuLabels {
            override fun idle(name: String) = "$name/idle"

            override fun bound(
                name: String,
                session: String,
            ) = "$name/bound:$session"

            override fun unpaired(name: String) = "$name/unpaired"

            override fun current(name: String) = "$name/current"
        }

    private fun robot(
        id: String,
        paired: Boolean = true,
    ) = if (paired) RobotRecord(id, "bot-$id", ChannelKind.WECOM, ownerSenderId = "u", ownerChatId = "u") else RobotRecord(id, "bot-$id", ChannelKind.WECOM, pairCode = "1")

    private fun binding(
        id: String,
        tab: String,
    ) = Binding(RobotId(id), SessionTarget("/p", tab, AgentType.CODEX, "s-$tab"), 1)

    @Test
    fun `空闲、连接别处、连接本会话与未配对`() {
        val bindings = mapOf("b" to binding("b", "other"), "c" to binding("c", "this"))
        val entries =
            phoneMenuModel(
                listOf(robot("a"), robot("b"), robot("c"), robot("d", paired = false)),
                bindingOf = { bindings[it] },
                thisTabId = "this",
                titleOf = { "T:" + it.target.tabId },
                labels = labels,
            )
        assertEquals(
            listOf(
                PhoneMenuEntry.Robot("a", "bot-a/idle", true),
                PhoneMenuEntry.Robot("b", "bot-b/bound:T:other", true),
                PhoneMenuEntry.Robot("c", "bot-c/current", false),
                PhoneMenuEntry.Robot("d", "bot-d/unpaired", false),
                PhoneMenuEntry.AddRobot,
                PhoneMenuEntry.Disconnect("c"),
            ),
            entries,
        )
    }

    @Test
    fun `没有机器人`() {
        assertEquals(
            listOf(PhoneMenuEntry.NoRobots, PhoneMenuEntry.AddRobot),
            phoneMenuModel(emptyList(), bindingOf = { null }, thisTabId = "t", titleOf = { "" }, labels = labels),
        )
    }
}
```

`PhoneActionsSourceTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneActionsSourceTest {
    private val actions = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/ui/PhoneActions.kt").normalized
    private val pluginXml = File("src/main/resources/META-INF/plugin.xml").readText()

    @Test
    fun `绑定后通知手机并持久化，换绑先通知旧会话断开`() {
        assertTrue(actions.contains("router.onReleased("))
        assertTrue(actions.contains("router.onBound("))
        assertTrue(actions.contains("RobotManager.getInstance().syncBindings()"))
        assertTrue(actions.contains("TerminalBridgeService.getInstance(project).start()"))
    }

    @Test
    fun `替换已有连接前需要确认`() {
        assertTrue(actions.contains("bridge.action.replace.message"))
    }

    @Test
    fun `注册了右键菜单组与通知分组`() {
        assertTrue(pluginXml.contains("com.github.izerui.imux.bridge.ui.ConnectToPhoneGroup"))
        assertTrue(pluginXml.contains("imux.phoneBridge"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*PhoneMenuTest' --tests '*PhoneActionsSourceTest'`
Expected: 编译失败，`Unresolved reference: phoneMenuModel`

- [ ] **Step 3: 实现 `PhoneMenu.kt`**

```kotlin
package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.robot.RobotRecord

sealed interface PhoneMenuEntry {
    data class Robot(
        val robotId: String,
        val label: String,
        val enabled: Boolean,
    ) : PhoneMenuEntry

    data object NoRobots : PhoneMenuEntry

    data object AddRobot : PhoneMenuEntry

    data class Disconnect(val robotId: String) : PhoneMenuEntry
}

interface MenuLabels {
    fun idle(name: String): String

    fun bound(
        name: String,
        session: String,
    ): String

    fun unpaired(name: String): String

    fun current(name: String): String
}

fun phoneMenuModel(
    robots: List<RobotRecord>,
    bindingOf: (String) -> Binding?,
    thisTabId: String,
    titleOf: (Binding) -> String,
    labels: MenuLabels,
): List<PhoneMenuEntry> {
    val entries = mutableListOf<PhoneMenuEntry>()
    var boundHere: String? = null
    robots.forEach { robot ->
        val binding = bindingOf(robot.id)
        entries +=
            when {
                robot.owner() == null -> PhoneMenuEntry.Robot(robot.id, labels.unpaired(robot.name), false)
                binding?.target?.tabId == thisTabId -> {
                    boundHere = robot.id
                    PhoneMenuEntry.Robot(robot.id, labels.current(robot.name), false)
                }
                binding != null -> PhoneMenuEntry.Robot(robot.id, labels.bound(robot.name, titleOf(binding)), true)
                else -> PhoneMenuEntry.Robot(robot.id, labels.idle(robot.name), true)
            }
    }
    if (robots.isEmpty()) entries += PhoneMenuEntry.NoRobots
    entries += PhoneMenuEntry.AddRobot
    boundHere?.let { entries += PhoneMenuEntry.Disconnect(it) }
    return entries
}

object BundleMenuLabels : MenuLabels {
    override fun idle(name: String) = ImuxBundle.message("bridge.action.robot.idle", name)

    override fun bound(
        name: String,
        session: String,
    ) = ImuxBundle.message("bridge.action.robot.bound", name, session)

    override fun unpaired(name: String) = ImuxBundle.message("bridge.action.robot.unpaired", name)

    override fun current(name: String) = ImuxBundle.message("bridge.action.robot.current", name)
}
```

- [ ] **Step 4: 实现 `PhoneActions.kt`**

```kotlin
package com.github.izerui.imux.bridge.ui

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.BridgeHub
import com.github.izerui.imux.bridge.BridgeLifecycle
import com.github.izerui.imux.bridge.BridgeSettings
import com.github.izerui.imux.bridge.TerminalBridgeService
import com.github.izerui.imux.bridge.core.BindResult
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.bridge.robot.RobotManager
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.terminal.AgentTerminalVirtualFile
import com.github.izerui.imux.terminal.TerminalHost
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages
import com.intellij.terminal.frontend.view.TerminalView

object BridgeNotifications {
    fun info(
        project: Project?,
        text: String,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("imux.phoneBridge")
            .createNotification(text, NotificationType.INFORMATION)
            .notify(project)
    }
}

/** 应用启动后设置“被接管”提示；放在启动活动里，RobotManager 不依赖界面层。 */
class BridgeNotificationsActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        RobotManager.getInstance().notifyKicked = { robot ->
            BridgeNotifications.info(null, ImuxBundle.message("bridge.notify.kicked", robot.name))
        }
    }
}

private fun terminalFile(event: AnActionEvent): AgentTerminalVirtualFile? {
    val project = event.project ?: return null
    val view = event.getData(TerminalView.DATA_KEY) ?: return null
    return TerminalHost.getInstance(project).fileFor(view)
}

private fun sessionTitle(
    project: Project,
    file: AgentTerminalVirtualFile,
): String = file.sessionId?.let { SessionMonitor.getInstance(project).model.sessionOf(it)?.title } ?: file.tabTitle

/** 终端右键「连接到手机」。机器人与会话分开管理，这里只改绑定关系。 */
class ConnectToPhoneGroup : ActionGroup(), DumbAware {
    init {
        templatePresentation.isPopup = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.text = ImuxBundle.message("bridge.action.group")
        event.presentation.isEnabledAndVisible = terminalFile(event) != null
    }

    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        val e = event ?: return emptyArray()
        val file = terminalFile(e) ?: return emptyArray()
        if (!BridgeSettings.getInstance().state.enabled) return arrayOf(OpenSettingsAction())
        val hub = BridgeHub.getInstance()
        return phoneMenuModel(
            RobotManager.getInstance().robots(),
            bindingOf = { hub.bindings.bindingOf(RobotId(it)) },
            thisTabId = file.tabId,
            titleOf = BridgeLifecycle::sessionTitle,
            labels = BundleMenuLabels,
        ).flatMap { entry ->
            when (entry) {
                is PhoneMenuEntry.Robot -> listOf(BindRobotAction(entry.robotId, entry.label, entry.enabled))
                PhoneMenuEntry.NoRobots -> listOf(DisabledLabelAction(ImuxBundle.message("bridge.action.none")))
                PhoneMenuEntry.AddRobot -> listOf(Separator.getInstance(), AddRobotAction())
                is PhoneMenuEntry.Disconnect -> listOf(DisconnectPhoneAction(entry.robotId))
            }
        }.toTypedArray()
    }
}

private class BindRobotAction(
    private val robotId: String,
    label: String,
    private val selectable: Boolean,
) : DumbAwareAction(label) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = selectable
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = terminalFile(e) ?: return
        val robot = RobotManager.getInstance().robot(robotId) ?: return
        val hub = BridgeHub.getInstance()
        val title = sessionTitle(project, file)
        val current = hub.bindings.bindingOf(RobotId(robotId))
        if (current != null && current.target.tabId != file.tabId) {
            val message = ImuxBundle.message("bridge.action.replace.message", robot.name, BridgeLifecycle.sessionTitle(current), title)
            val choice =
                Messages.showOkCancelDialog(project, message, ImuxBundle.message("bridge.action.replace.title"), ImuxBundle.message("bridge.action.replace.ok"), Messages.getCancelButton(), Messages.getQuestionIcon())
            if (choice != Messages.OK) return
        }
        // 这个标签若已被另一个机器人占用，先让它断开
        val other = hub.bindings.bindingForTab(file.tabId)?.takeIf { it.robotId.value != robotId }
        other?.let { hub.bindings.unbind(it.robotId) }
        val target = SessionTarget(project.basePath ?: System.getProperty("user.home"), file.tabId, file.agentType, file.sessionId)
        val result = hub.bindings.bind(RobotId(robotId), target) as? BindResult.Bound ?: return
        TerminalBridgeService.getInstance(project).start()
        RobotManager.getInstance().syncBindings()
        val replaced = result.replaced?.takeIf { it.target.tabId != file.tabId }
        hub.launch {
            other?.let { hub.router.onReleased(it, BridgeLifecycle.sessionTitle(it)) }
            replaced?.let { hub.router.onReleased(it, BridgeLifecycle.sessionTitle(it)) }
            hub.router.onBound(result.binding, project.name, file.agentType.displayName, title)
        }
        BridgeNotifications.info(project, ImuxBundle.message("bridge.notify.connected", title, robot.name))
    }
}

private class DisconnectPhoneAction(private val robotId: String) : DumbAwareAction(ImuxBundle.message("bridge.action.disconnect")) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val hub = BridgeHub.getInstance()
        val binding = hub.bindings.unbind(RobotId(robotId)) ?: return
        RobotManager.getInstance().syncBindings()
        val title = BridgeLifecycle.sessionTitle(binding)
        hub.launch { hub.router.onReleased(binding, title) }
        BridgeNotifications.info(project, ImuxBundle.message("bridge.notify.disconnected", title))
    }
}

private class AddRobotAction : DumbAwareAction(ImuxBundle.message("bridge.action.add")) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        AddRobotDialog(e.project).show()
    }
}

private class OpenSettingsAction : DumbAwareAction(ImuxBundle.message("bridge.action.disabled")) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, RobotsConfigurable::class.java)
    }
}

private class DisabledLabelAction(label: String) : DumbAwareAction(label) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = false
    }

    override fun actionPerformed(e: AnActionEvent) = Unit
}
```

- [ ] **Step 5: 在 `plugin.xml` 注册**

在 `<extensions>` 内（与其它 `notificationGroup` 相邻）加入：

```xml
        <notificationGroup id="imux.phoneBridge" displayType="BALLOON"/>
        <postStartupActivity
                implementation="com.github.izerui.imux.bridge.ui.BridgeNotificationsActivity"/>
```

在 `<actions>` 内、现有终端右键菜单项之后加入：

```xml
        <group
                id="imux.bridge.ConnectToPhone"
                class="com.github.izerui.imux.bridge.ui.ConnectToPhoneGroup"
                popup="true">
            <add-to-group group-id="Terminal.ReworkedTerminalContextMenu" anchor="last"/>
        </group>
```

- [ ] **Step 6: 运行测试与编译**

Run: `./gradlew test --tests '*PhoneMenuTest' --tests '*PhoneActionsSourceTest' --tests '*PluginXmlRegistrationTest' --tests '*BridgeIsolationSourceTest'` 然后 `./gradlew compileKotlin`
Expected: 全部 PASS；编译无错误。`AgentTerminalVirtualFile` 的 `tabTitle`、`sessionId`、`agentType`、`tabId` 均为现有公开属性；若平台 API 签名不同，按编译器提示改用等价重载并在报告里写明。

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/ui src/test/kotlin/com/github/izerui/imux/bridge/ui src/main/resources/META-INF/plugin.xml
git commit -m "feat(bridge): 新增终端右键「连接到手机」与连接通知"
```

---

### Task 11: 全量回归、文档与真实联调清单

**Files:**
- Modify: `docs/terminal-channel-bridge-feasibility.md`（追加第 18 节）

- [ ] **Step 1: 全量测试**

Run: `./gradlew test`
Expected: 全部 PASS，失败数 0。既有测试失败视为回归，先修复。

- [ ] **Step 2: 编译与打包**

Run: `./gradlew compileKotlin compileTestKotlin buildPlugin`
Expected: 成功；`bridge/` 下新文件没有编译警告。确认 `build/distributions/` 中的插件包含 `zxing core` jar（`unzip -l build/distributions/*.zip | grep zxing`）。

- [ ] **Step 3: 插件验证（网络可用时）**

Run: `./gradlew verifyPlugin`
Expected: 不新增兼容性问题。无法下载 IDE 时记录原因，不得报告为通过。

- [ ] **Step 4: 在设计文档追加第 18 节**

```markdown
## 18. 机器人管理与企业微信接入（第二份计划）

实施计划：`docs/superpowers/plans/2026-10-27-robot-management-wecom.md`；协议依据：`docs/channel-protocols.md`。

| 模块 | 位置 | 说明 |
| --- | --- | --- |
| 机器人记录与持久化 | `bridge/robot/RobotModels.kt`、`RobotStore.kt` | `imux-bridge.xml` 的 `ImuxBridgeRobots`；凭据只在 PasswordSafe |
| 认主 | `bridge/robot/Pairing.kt` | 6 位配对码，10 分钟有效；扫码带本人身份的渠道直接认主 |
| 生命周期 | `bridge/robot/RobotManager.kt` | 开关打开才连接；绑定以注册表为准写回持久化；重启后按会话恢复 |
| 渠道 SPI | `bridge/channel/ChannelSpi.kt` | 新渠道只实现 `ChannelOnboarding` + `ChannelConnection` 并登记到 `ChannelFactories` |
| 企业微信 | `bridge/channel/wecom/` | 订阅、30 秒心跳、指数退避、被踢不重连、单聊主动推送（chat_type=1、30 条/分钟） |
| 界面 | `bridge/ui/` | 设置 › 工具 › Imux › 通讯机器人；添加机器人对话框；终端右键「连接到手机」 |

### 真实联调清单（企业微信）

1. 设置页添加机器人：选择企业微信 → 手机扫码确认创建 → 对话框出现配对码。
2. 在企业微信里把配对码发给机器人：收到“配对成功”；设置页状态变为“在线”。
3. 在 Codex 或 Claude 会话标签上右键 → 连接到手机 → 选择机器人：手机收到“当前连接：…”。
4. 手机发一句需求：终端出现这句输入并开始执行；助手回复转发到手机；本轮结束收到“本轮完成 · 工具调用 N 次”。
5. 本轮中途若需要权限确认：手机收到“等待你在 IDE 中处理”，在 IDE 确认后继续。
6. 电脑上在同一终端输入：手机收到“电脑端输入：…”。
7. 同一轮中调用一次 IDEA MCP（例如检查文件问题），确认在手机触发的轮次里可用。
8. 换绑到另一个会话：确认框出现；旧会话收到“已断开会话”，新会话收到“当前连接”。
9. 关闭会话标签：手机收到“已断开会话”，机器人回到空闲；再发消息收到“还没有连接会话”。
10. 重启 IDE：机器人自动重连；已连接的会话恢复标签后继续转发。
11. 另开一个 IDE 用同一机器人：先连接的一方提示“已被其它客户端接管”，且不自动重连。
12. 关闭开关：连接断开、记录不再读取；重新开启后恢复。

尚待确认的协议事项见 `docs/channel-protocols.md` 第 6 节。渠道范围为企业微信、飞书、钉钉、普通微信；QQ 已决定不纳入，设计文档第 14–16 节中关于 QQ 的内容仅为调研记录。
```

- [ ] **Step 5: Commit**

```bash
git add docs/terminal-channel-bridge-feasibility.md
git commit -m "docs(bridge): 记录机器人管理与企业微信接入及联调清单"
```
