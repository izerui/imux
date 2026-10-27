# 终端会话通讯桥接核心 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 imux 内建立与具体通讯渠道无关的桥接内核：远程文字按顺序提交到已绑定的现有终端，旁路读取 CLI 结构化记录并把文本转发给绑定的机器人；默认关闭，对现有功能零行为改变。

**Architecture:** 新代码全部放在 `com.github.izerui.imux.bridge` 包。渠道（Adapter）与会话（Facade 端口）互不认识，由 `BridgeRouter`（Mediator）按 `BindingRegistry` 的 1:1 绑定连接。三种 CLI 的记录解析用 Strategy（`TranscriptDecoder`）。现有代码只被订阅（Observer），唯一的业务代码改动是在 `SessionMonitor` 中新增两处轮次事件广播，事件接口放在 `turn` 包，因此现有代码不 import `bridge`。

**Tech Stack:** Kotlin 2.3、IntelliJ Platform 262（捆绑 Terminal 插件）、kotlinx.coroutines、Gson（平台自带）、JUnit 4。

**设计依据:** `docs/terminal-channel-bridge-feasibility.md` 第 1–16 节。本计划是拆分后的第一份，只交付内核；真实渠道、扫码、配对、机器人管理界面与持久化见文末“后续计划”。

## Global Constraints

- 不修改 `TerminalHost`、`AgentCommand`、`TurnNotifier`、`PeerCoordinator`、`NavigationTranscriptIndex`、`SessionTitleRegenerator` 的任何代码。
- 现有代码唯一允许的改动：`monitor/SessionMonitor.kt` 新增恰好两处 `publishTurnEvent(...)` 调用；`plugin.xml` 新增一个 `postStartupActivity`；10 个 `ImuxBundle*.properties` 新增 `bridge.*` 键。
- `src/main/kotlin` 中 `bridge/` 以外的文件不得出现 `com.github.izerui.imux.bridge`（由 Task 9 的源码测试强制）。
- 功能开关 `BridgeSettings.enabled` 默认 `false`；关闭时不订阅、不轮询、不读取会话文件。
- 不启动、恢复或终止任何 CLI 进程；不等待 Agent 空闲；不拦截本地键盘。
- 远程输入只用 `requireBracketedPasteMode().shouldExecute().trySend(text)` 一次发送，与副驾驶的已验证形态一致。
- 会话记录只读；转发文本不截断，超过渠道上限时分段。
- 渠道相关代码不得引用 `AgentType`；`BridgeRouter` 不得按 `AgentType` 分支。
- 新的源码断言必须复用 `src/test/kotlin/com/github/izerui/imux/SourceCode.kt`。
- 文案：无占位符的消息不写 `''`；有占位符的消息里单引号必须写成 `''`（`ImuxBundleTest` 强制）。
- 测试命令使用 `./gradlew test --tests '<pattern>'`；项目没有平台 test-framework，平台接线层只做源码断言与纯函数测试。
- 修改 Kotlin 文件后按仓库约定运行 IDE `build_project` 与 `lint_files`（Task 10）。

## File Structure

| 文件 | 职责 |
| --- | --- |
| Create `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeModels.kt` | 渠道中立的值类型：`RobotId`、`SessionTarget`、`InboundMessage`、`OutboundMessage`、`SessionEvent`、结果枚举 |
| Create `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgePorts.kt` | 端口接口：`ChannelAdapter`、`InboundSink`、`TerminalSessionPort`、`BridgeTexts`、`RobotOwner` |
| Create `src/main/kotlin/com/github/izerui/imux/bridge/core/TextSegmenter.kt` | 按 UTF-8 字节分段，保持 Markdown 代码块闭合 |
| Create `src/main/kotlin/com/github/izerui/imux/bridge/core/BindingRegistry.kt` | 机器人 ↔ 终端 1:1 绑定、epoch、迁移、重新挂接 |
| Create `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeRouter.kt` | Mediator：入站校验/去重/提交，出站渲染/回显抑制/分段/epoch 校验 |
| Create `src/main/kotlin/com/github/izerui/imux/bridge/transcript/TranscriptDecoders.kt` | Claude/Codex/Pi 三个解码 Strategy |
| Create `src/main/kotlin/com/github/izerui/imux/bridge/transcript/TranscriptTail.kt` | 从当前末尾开始的增量只读，处理半行、超长行、文件替换 |
| Create `src/main/kotlin/com/github/izerui/imux/turn/TurnEvents.kt` | 轮次完成/等待的项目级 Topic 与不抛异常的发布函数 |
| Modify `src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt:745-772` | 两处 `publishTurnEvent` |
| Create `src/main/kotlin/com/github/izerui/imux/bridge/terminal/ImuxTerminalSessionPort.kt` | Facade：按 tabId 找终端并提交文字 |
| Create `src/main/kotlin/com/github/izerui/imux/bridge/terminal/BindingLifecycle.kt` | 标签变化时绑定的保留/重新挂接/释放判定（纯函数） |
| Create `src/main/kotlin/com/github/izerui/imux/bridge/BridgeSettings.kt` | 应用级开关，独立存储 `imux-bridge.xml` |
| Create `src/main/kotlin/com/github/izerui/imux/bridge/BundleBridgeTexts.kt` | `BridgeTexts` 的资源包实现 |
| Create `src/main/kotlin/com/github/izerui/imux/bridge/BridgeHub.kt` | 应用级服务：注册表、路由器、渠道表、入站串行队列 |
| Create `src/main/kotlin/com/github/izerui/imux/bridge/TerminalBridgeService.kt` | 项目级服务：订阅现有事件、轮询记录、生命周期 |
| Create `src/main/kotlin/com/github/izerui/imux/bridge/BridgeStartupActivity.kt` | 开关打开时启动项目服务 |
| Modify `src/main/resources/META-INF/plugin.xml` | 注册 `BridgeStartupActivity` |
| Modify `src/main/resources/messages/ImuxBundle*.properties`（10 个） | `bridge.*` 文案 |
| Tests under `src/test/kotlin/com/github/izerui/imux/bridge/` 与 `turn/` | 见各任务 |

---

### Task 1: 渠道中立模型、端口接口与文本分段

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeModels.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgePorts.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/core/TextSegmenter.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/core/TextSegmenterTest.kt`

**Interfaces:**
- Produces: `RobotId(value: String)`、`SessionTarget(projectPath, tabId, agentType, sessionId)`、`InboundMessage`、`OutboundMessage`、`SessionEventKind`、`SessionEvent(kind, text, toolCallId)`、`SubmitResult`、`DeliveryResult`、`InboundOutcome`、`RobotOwner(senderId, chatId)`、`ChannelAdapter`、`InboundSink`、`TerminalSessionPort`、`BridgeTexts`、`TextSegmenter.split(text: String, maxBytes: Int): List<String>`、`TextSegmenter.MIN_SEGMENT_BYTES = 64`。

- [ ] **Step 1: 写模型与端口（无行为，供后续任务编译）**

`BridgeModels.kt`：

```kotlin
package com.github.izerui.imux.bridge.core

import com.github.izerui.imux.model.AgentType

/** 机器人在 imux 内的稳定身份，跨渠道唯一，由机器人管理分配。 */
@JvmInline
value class RobotId(val value: String)

/**
 * 绑定目标。运行期按 [tabId] 定位终端（标签一生不变），
 * [sessionId] 用于项目重开后按原生会话重新挂接；新会话落盘前为 null。
 * [agentType] 只交给记录解码器，路由与渠道不得据此分支。
 */
data class SessionTarget(
    val projectPath: String,
    val tabId: String,
    val agentType: AgentType,
    val sessionId: String?,
)

data class InboundMessage(
    val robotId: RobotId,
    /** 平台消息 id，用于抵御平台重投；不能用文本去重。 */
    val externalMessageId: String,
    val senderId: String,
    val chatId: String,
    val text: String,
)

data class OutboundMessage(
    val robotId: RobotId,
    val chatId: String,
    /** 未绑定时的提示回复为 null。 */
    val bindingEpoch: Long?,
    val text: String,
)

enum class SessionEventKind { USER_TEXT, ASSISTANT_TEXT, TOOL_STARTED, TOOL_FINISHED }

data class SessionEvent(
    val kind: SessionEventKind,
    val text: String,
    val toolCallId: String? = null,
)

enum class SubmitResult { SUBMITTED_TO_TERMINAL, NOT_READY, OFFLINE }

sealed interface DeliveryResult {
    data class Accepted(val platformMessageId: String?) : DeliveryResult
    data class Rejected(val reason: String) : DeliveryResult
    data object Unknown : DeliveryResult
}

enum class InboundOutcome {
    NOT_PAIRED,
    UNAUTHORIZED,
    DUPLICATE,
    EMPTY,
    UNBOUND,
    SUBMITTED_TO_TERMINAL,
    NOT_READY,
    OFFLINE,
}
```

`BridgePorts.kt`：

```kotlin
package com.github.izerui.imux.bridge.core

/** 机器人的本人身份：只接受该发送者在该聊天中的消息，输出也只发往该聊天。 */
data class RobotOwner(
    val senderId: String,
    val chatId: String,
)

/** 渠道适配器。只做收发，不认识 Agent、终端和绑定。 */
interface ChannelAdapter {
    val robotId: RobotId

    /** 单条文本允许的最大 UTF-8 字节数，取自对应平台限制，不得小于 [TextSegmenter.MIN_SEGMENT_BYTES]。 */
    val maxTextBytes: Int

    suspend fun send(message: OutboundMessage): DeliveryResult
}

/** 渠道收到消息后交给桥接的入口。实现方保证按调用顺序处理。 */
fun interface InboundSink {
    fun accept(message: InboundMessage)
}

/** 向现有终端提交一条文字。实现方不得启动或恢复 CLI。 */
fun interface TerminalSessionPort {
    suspend fun submit(
        target: SessionTarget,
        text: String,
    ): SubmitResult
}

/** 发往手机的提示文案，由资源包实现，测试用假实现。 */
interface BridgeTexts {
    fun unbound(): String

    fun offline(): String

    fun notReady(): String

    fun emptyInput(): String

    fun localInputLabel(): String

    fun completed(toolCalls: Int): String

    fun waiting(reason: String?): String

    fun released(sessionTitle: String): String

    fun transcriptRestarted(): String
}
```

- [ ] **Step 2: 写分段的失败测试**

`TextSegmenterTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSegmenterTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size

    @Test
    fun `未超限时原样返回`() {
        assertEquals(listOf("你好\nworld"), TextSegmenter.split("你好\nworld", 64))
    }

    @Test
    fun `按行切分且每段不超过上限并可无损拼回`() {
        val text = (1..40).joinToString("\n") { "第 $it 行：一些内容 some content" }
        val parts = TextSegmenter.split(text, 200)
        assertTrue(parts.size > 1)
        parts.forEach { assertTrue("超限：${bytes(it)}", bytes(it) <= 200) }
        assertEquals(text, parts.joinToString("\n"))
    }

    @Test
    fun `超长单行按码点切分不产生乱码`() {
        val text = "中文字符".repeat(100)
        val parts = TextSegmenter.split(text, 64)
        parts.forEach { assertTrue(bytes(it) <= 64) }
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.none { it.contains('�') })
    }

    @Test
    fun `代码块跨段时补齐闭合并在下一段重开`() {
        val body = (1..30).joinToString("\n") { "val x$it = $it" }
        val text = "说明\n```kotlin\n$body\n```\n结尾"
        val parts = TextSegmenter.split(text, 120)
        assertTrue(parts.size > 1)
        parts.forEach { part ->
            assertTrue(bytes(part) <= 120)
            val fences = part.lines().count { it.trimStart().startsWith("```") }
            assertEquals("代码块未闭合：$part", 0, fences % 2)
        }
        assertTrue(parts[1].startsWith("```kotlin"))
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./gradlew test --tests '*TextSegmenterTest'`
Expected: 编译失败，`Unresolved reference: TextSegmenter`

- [ ] **Step 4: 实现 `TextSegmenter.kt`**

```kotlin
package com.github.izerui.imux.bridge.core

/**
 * 把正文切成不超过渠道上限的若干段，绝不截断。
 *
 * 优先在换行处切；单行超限时按码点切，不会把一个汉字劈成两半。
 * 切点落在 Markdown 代码块里时，本段末尾补上闭合围栏、下一段用原围栏头重开，
 * 否则手机端从切点往后会整段按代码渲染。
 */
object TextSegmenter {
    const val MIN_SEGMENT_BYTES = 64
    private const val FENCE = "```"
    private const val CLOSING = "\n" + FENCE

    fun split(
        text: String,
        maxBytes: Int,
    ): List<String> {
        require(maxBytes >= MIN_SEGMENT_BYTES) { "maxBytes 过小：$maxBytes" }
        if (bytes(text) <= maxBytes) return listOf(text)

        // 任何一段都可能需要补闭合围栏，统一预留
        val budget = maxBytes - bytes(CLOSING)
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var currentBytes = 0
        var carriedPrefixLength = 0
        var openFence: String? = null

        fun flush() {
            if (openFence != null) current.append(CLOSING)
            out += current.toString()
            current.setLength(0)
            currentBytes = 0
            carriedPrefixLength = 0
            openFence?.let { header ->
                current.append(header)
                currentBytes = bytes(header)
                carriedPrefixLength = header.length
            }
        }

        for (line in text.split('\n')) {
            val headerBytes = openFence?.let { bytes(it) + 1 } ?: 0
            for (piece in splitLongLine(line, budget - headerBytes)) {
                val needed = (if (current.isEmpty()) 0 else 1) + bytes(piece)
                if (currentBytes + needed > budget && current.length > carriedPrefixLength) flush()
                if (current.isNotEmpty()) {
                    current.append('\n')
                    currentBytes += 1
                }
                current.append(piece)
                currentBytes += bytes(piece)
            }
            if (line.trimStart().startsWith(FENCE)) {
                openFence =
                    if (openFence == null) {
                        // 围栏头过长时只用裸围栏重开，保证后续正文仍有足够空间
                        line.trimStart().takeIf { bytes(it) <= budget / 4 } ?: FENCE
                    } else {
                        null
                    }
            }
        }
        if (current.length > carriedPrefixLength) out += current.toString()
        return out
    }

    private fun splitLongLine(
        line: String,
        limit: Int,
    ): List<String> {
        if (bytes(line) <= limit) return listOf(line)
        val pieces = mutableListOf<String>()
        val piece = StringBuilder()
        var pieceBytes = 0
        var index = 0
        while (index < line.length) {
            val codePoint = line.codePointAt(index)
            val chars = Character.charCount(codePoint)
            val size = bytes(line.substring(index, index + chars))
            if (pieceBytes + size > limit && piece.isNotEmpty()) {
                pieces += piece.toString()
                piece.setLength(0)
                pieceBytes = 0
            }
            piece.appendCodePoint(codePoint)
            pieceBytes += size
            index += chars
        }
        if (piece.isNotEmpty()) pieces += piece.toString()
        return pieces
    }

    private fun bytes(text: String): Int = text.toByteArray(Charsets.UTF_8).size
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew test --tests '*TextSegmenterTest'`
Expected: 4 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/core src/test/kotlin/com/github/izerui/imux/bridge/core/TextSegmenterTest.kt
git commit -m "feat(bridge): 新增渠道中立模型、端口与文本分段"
```

---

### Task 2: 三种 CLI 的记录解码器

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/transcript/TranscriptDecoders.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/transcript/TranscriptDecodersTest.kt`

**Interfaces:**
- Consumes: `SessionEvent`、`SessionEventKind`（Task 1）
- Produces: `fun interface TranscriptDecoder { fun decode(line: String): List<SessionEvent> }`、`object TranscriptDecoders { fun forAgent(agentType: AgentType): TranscriptDecoder }`、`ClaudeTranscriptDecoder`、`CodexTranscriptDecoder`、`PiTranscriptDecoder`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.transcript

import com.github.izerui.imux.bridge.core.SessionEvent
import com.github.izerui.imux.bridge.core.SessionEventKind.ASSISTANT_TEXT
import com.github.izerui.imux.bridge.core.SessionEventKind.TOOL_FINISHED
import com.github.izerui.imux.bridge.core.SessionEventKind.TOOL_STARTED
import com.github.izerui.imux.bridge.core.SessionEventKind.USER_TEXT
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptDecodersTest {
    @Test
    fun `Claude 区分用户文本、助手文本、工具调用与结果且保留换行`() {
        val user = """{"type":"user","uuid":"u1","message":{"role":"user","content":"第一行\n第二行"}}"""
        val assistant =
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":[""" +
                """{"type":"thinking","thinking":"内部推理"},""" +
                """{"type":"text","text":"```kt\nval a = 1\n```"},""" +
                """{"type":"tool_use","id":"t1","name":"Read","input":{}}]}}"""
        val result =
            """{"type":"user","uuid":"u2","message":{"role":"user","content":[""" +
                """{"type":"tool_result","tool_use_id":"t1","content":"文件内容"}]}}"""

        assertEquals(listOf(SessionEvent(USER_TEXT, "第一行\n第二行")), ClaudeTranscriptDecoder.decode(user))
        assertEquals(
            listOf(SessionEvent(ASSISTANT_TEXT, "```kt\nval a = 1\n```"), SessionEvent(TOOL_STARTED, "Read", "t1")),
            ClaudeTranscriptDecoder.decode(assistant),
        )
        assertEquals(listOf(SessionEvent(TOOL_FINISHED, "", "t1")), ClaudeTranscriptDecoder.decode(result))
    }

    @Test
    fun `Claude 跳过元数据、压缩摘要、子代理、命令包裹与中断标记`() {
        listOf(
            """{"type":"user","isMeta":true,"message":{"role":"user","content":"meta"}}""",
            """{"type":"user","isCompactSummary":true,"message":{"role":"user","content":"摘要"}}""",
            """{"type":"assistant","isSidechain":true,"message":{"role":"assistant","content":[{"type":"text","text":"子代理"}]}}""",
            """{"type":"user","message":{"role":"user","content":"<command-name>/clear</command-name>"}}""",
            """{"type":"user","message":{"role":"user","content":"[Request interrupted by user]"}}""",
            """{"type":"summary","summary":"x"}""",
            "not json",
        ).forEach { assertTrue(it, ClaudeTranscriptDecoder.decode(it).isEmpty()) }
    }

    @Test
    fun `Codex 只读 response_item 并过滤环境与 AGENTS 注入`() {
        val user =
            """{"type":"response_item","payload":{"type":"message","role":"user","content":[""" +
                """{"type":"input_text","text":"<environment_context>x</environment_context>"},""" +
                """{"type":"input_text","text":"修一下\n测试"}]}}"""
        val agents =
            """{"type":"response_item","payload":{"type":"message","role":"user","content":[""" +
                """{"type":"input_text","text":"# AGENTS.md instructions for /p"}]}}"""
        val assistant =
            """{"type":"response_item","payload":{"type":"message","role":"assistant","content":[""" +
                """{"type":"output_text","text":"好的"}]}}"""
        val call = """{"type":"response_item","payload":{"type":"function_call","name":"shell","call_id":"c1","arguments":"{}"}}"""
        val output = """{"type":"response_item","payload":{"type":"function_call_output","call_id":"c1","output":"ok"}}"""
        val mirror = """{"type":"event_msg","payload":{"type":"agent_message","message":"好的"}}"""

        assertEquals(listOf(SessionEvent(USER_TEXT, "修一下\n测试")), CodexTranscriptDecoder.decode(user))
        assertTrue(CodexTranscriptDecoder.decode(agents).isEmpty())
        assertEquals(listOf(SessionEvent(ASSISTANT_TEXT, "好的")), CodexTranscriptDecoder.decode(assistant))
        assertEquals(listOf(SessionEvent(TOOL_STARTED, "shell", "c1")), CodexTranscriptDecoder.decode(call))
        assertEquals(listOf(SessionEvent(TOOL_FINISHED, "", "c1")), CodexTranscriptDecoder.decode(output))
        assertTrue("event_msg 是镜像，不能重复转发", CodexTranscriptDecoder.decode(mirror).isEmpty())
    }

    @Test
    fun `Pi 区分 text、toolCall、toolResult 并跳过 system 与会话头`() {
        val user = """{"type":"message","id":"1","parentId":null,"message":{"role":"user","content":"你好"}}"""
        val assistant =
            """{"type":"message","id":"2","parentId":"1","message":{"role":"assistant","content":[""" +
                """{"type":"text","text":"看看"},{"type":"toolCall","id":"k1","name":"bash","arguments":{}}]}}"""
        val result =
            """{"type":"message","id":"3","parentId":"2","message":{"role":"toolResult","toolCallId":"k1","content":[]}}"""
        val system = """{"type":"message","id":"0","parentId":null,"message":{"role":"system","content":""}}"""
        val header = """{"type":"session","version":3,"id":"s","cwd":"/p"}"""

        assertEquals(listOf(SessionEvent(USER_TEXT, "你好")), PiTranscriptDecoder.decode(user))
        assertEquals(
            listOf(SessionEvent(ASSISTANT_TEXT, "看看"), SessionEvent(TOOL_STARTED, "bash", "k1")),
            PiTranscriptDecoder.decode(assistant),
        )
        assertEquals(listOf(SessionEvent(TOOL_FINISHED, "", "k1")), PiTranscriptDecoder.decode(result))
        assertTrue(PiTranscriptDecoder.decode(system).isEmpty())
        assertTrue(PiTranscriptDecoder.decode(header).isEmpty())
    }

    @Test
    fun `按 Agent 选择解码器`() {
        assertSame(ClaudeTranscriptDecoder, TranscriptDecoders.forAgent(AgentType.CLAUDE))
        assertSame(CodexTranscriptDecoder, TranscriptDecoders.forAgent(AgentType.CODEX))
        assertSame(PiTranscriptDecoder, TranscriptDecoders.forAgent(AgentType.PI))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*TranscriptDecodersTest'`
Expected: 编译失败，`Unresolved reference: ClaudeTranscriptDecoder`

- [ ] **Step 3: 实现 `TranscriptDecoders.kt`**

```kotlin
package com.github.izerui.imux.bridge.transcript

import com.github.izerui.imux.bridge.core.SessionEvent
import com.github.izerui.imux.bridge.core.SessionEventKind
import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 把会话记录的一行解成桥接事件。全文保留，不截断、不压缩换行——
 * 这正是不能复用导航索引解析的原因（它为悬停卡片截断并压平了正文）。
 */
fun interface TranscriptDecoder {
    fun decode(line: String): List<SessionEvent>
}

object TranscriptDecoders {
    fun forAgent(agentType: AgentType): TranscriptDecoder =
        when (agentType) {
            AgentType.CLAUDE -> ClaudeTranscriptDecoder
            AgentType.CODEX -> CodexTranscriptDecoder
            AgentType.PI -> PiTranscriptDecoder
        }
}

object ClaudeTranscriptDecoder : TranscriptDecoder {
    override fun decode(line: String): List<SessionEvent> {
        val root = parseObject(line) ?: return emptyList()
        // 子代理、注入的元数据与压缩摘要都不是这条会话里人和助手说的话
        if (root.flag("isSidechain") || root.flag("isMeta") || root.flag("isCompactSummary")) return emptyList()
        val message = root.obj("message") ?: return emptyList()
        val content = message.get("content") ?: return emptyList()
        return when (message.str("role")) {
            "user" -> userEvents(content)
            "assistant" -> assistantEvents(content)
            else -> emptyList()
        }
    }

    private fun userEvents(content: JsonElement): List<SessionEvent> {
        if (content.isJsonPrimitive) return listOfNotNull(userText(content.asString))
        return blocks(content).mapNotNull { block ->
            when (block.str("type")) {
                "text" -> block.str("text")?.let(::userText)
                "tool_result" -> SessionEvent(SessionEventKind.TOOL_FINISHED, "", block.str("tool_use_id"))
                else -> null
            }
        }
    }

    private fun userText(text: String): SessionEvent? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isWrappedMarkup(trimmed) || trimmed.startsWith(CLAUDE_INTERRUPTED_PREFIX)) return null
        return SessionEvent(SessionEventKind.USER_TEXT, trimmed)
    }

    private fun assistantEvents(content: JsonElement): List<SessionEvent> {
        if (content.isJsonPrimitive) return listOfNotNull(assistantText(content.asString))
        return blocks(content).mapNotNull { block ->
            when (block.str("type")) {
                "text" -> block.str("text")?.let(::assistantText)
                "tool_use" -> SessionEvent(SessionEventKind.TOOL_STARTED, block.str("name").orEmpty(), block.str("id"))
                else -> null
            }
        }
    }

    // 与 SessionTitleRegenerator 中的私有常量同值；那边是标题逻辑的私有细节，不跨包引用
    private const val CLAUDE_INTERRUPTED_PREFIX = "[Request interrupted by user"
}

object CodexTranscriptDecoder : TranscriptDecoder {
    override fun decode(line: String): List<SessionEvent> {
        val root = parseObject(line) ?: return emptyList()
        // event_msg 里的 user_message/agent_message 是同一内容的镜像，只认 response_item
        if (root.str("type") != "response_item") return emptyList()
        val payload = root.obj("payload") ?: return emptyList()
        return when (val type = payload.str("type")) {
            "message" -> messageEvents(payload)
            "function_call", "custom_tool_call" ->
                listOf(SessionEvent(SessionEventKind.TOOL_STARTED, payload.str("name") ?: type, payload.str("call_id")))
            "function_call_output", "custom_tool_call_output" ->
                listOf(SessionEvent(SessionEventKind.TOOL_FINISHED, "", payload.str("call_id")))
            else -> emptyList()
        }
    }

    private fun messageEvents(payload: JsonObject): List<SessionEvent> {
        val content = payload.get("content") ?: return emptyList()
        return when (payload.str("role")) {
            "user" -> {
                val text =
                    blocks(content)
                        .filter { it.str("type") == "input_text" }
                        .mapNotNull { it.str("text")?.trim() }
                        .filter { it.isNotEmpty() && !isWrappedMarkup(it) && !it.startsWith(CODEX_AGENTS_INSTRUCTIONS_PREFIX) }
                        .joinToString("\n")
                listOfNotNull(text.takeIf(String::isNotEmpty)?.let { SessionEvent(SessionEventKind.USER_TEXT, it) })
            }
            "assistant" -> {
                val text =
                    blocks(content)
                        .filter { it.str("type") == "output_text" }
                        .mapNotNull { it.str("text") }
                        .joinToString("\n")
                listOfNotNull(assistantText(text))
            }
            else -> emptyList()
        }
    }

    // 与 SessionTitleRegenerator 中的私有常量同值
    private const val CODEX_AGENTS_INSTRUCTIONS_PREFIX = "# AGENTS.md instructions"
}

object PiTranscriptDecoder : TranscriptDecoder {
    override fun decode(line: String): List<SessionEvent> {
        val root = parseObject(line) ?: return emptyList()
        if (root.str("type") != "message") return emptyList()
        val message = root.obj("message") ?: return emptyList()
        return when (message.str("role")) {
            "user" -> {
                val content = message.get("content") ?: return emptyList()
                val text =
                    if (content.isJsonPrimitive) {
                        content.asString
                    } else {
                        blocks(content).filter { it.str("type") == "text" }.mapNotNull { it.str("text") }.joinToString("\n")
                    }
                listOfNotNull(text.trim().takeIf(String::isNotEmpty)?.let { SessionEvent(SessionEventKind.USER_TEXT, it) })
            }
            "assistant" ->
                blocks(message.get("content")).mapNotNull { block ->
                    when (block.str("type")) {
                        "text" -> block.str("text")?.let(::assistantText)
                        "toolCall" -> SessionEvent(SessionEventKind.TOOL_STARTED, block.str("name").orEmpty(), block.str("id"))
                        else -> null
                    }
                }
            "toolResult" -> listOf(SessionEvent(SessionEventKind.TOOL_FINISHED, "", message.str("toolCallId")))
            else -> emptyList()
        }
    }
}

private fun assistantText(text: String): SessionEvent? =
    text.takeIf(String::isNotBlank)?.let { SessionEvent(SessionEventKind.ASSISTANT_TEXT, it) }

/** 形如 `<command-name>…</command-name>` 的整段标记是 CLI 注入的，不是人写的。 */
private fun isWrappedMarkup(text: String): Boolean = text.startsWith("<") && text.endsWith(">")

private fun parseObject(line: String): JsonObject? =
    runCatching { JsonParser.parseString(line) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

private fun blocks(content: JsonElement?): List<JsonObject> =
    content?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
        ?: emptyList()

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.flag(key: String): Boolean =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests '*TranscriptDecodersTest'`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/transcript/TranscriptDecoders.kt src/test/kotlin/com/github/izerui/imux/bridge/transcript/TranscriptDecodersTest.kt
git commit -m "feat(bridge): 新增 Claude、Codex、Pi 会话记录解码器"
```

---

### Task 3: 增量只读记录尾随器

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/transcript/TranscriptTail.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/transcript/TranscriptTailTest.kt`

**Interfaces:**
- Consumes: `TranscriptDecoder`（Task 2）
- Produces: `class TranscriptTail(path: Path, decoder: TranscriptDecoder, maxLineBytes: Int = 8 * 1024 * 1024)`，方法 `startAtEnd()`、`poll(): TailPoll`，属性 `skippedOversizeLines: Int`；`data class TailPoll(events: List<SessionEvent>, restarted: Boolean)`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.transcript

import com.github.izerui.imux.bridge.core.SessionEvent
import com.github.izerui.imux.bridge.core.SessionEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class TranscriptTailTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val echo = TranscriptDecoder { listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, it)) }

    private fun texts(poll: TailPoll) = poll.events.map { it.text }

    private fun append(
        file: java.nio.file.Path,
        bytes: ByteArray,
    ) {
        Files.write(file, bytes, StandardOpenOption.APPEND)
    }

    @Test
    fun `从当前末尾开始，不倾倒历史`() {
        val file = temp.newFile("s.jsonl").toPath()
        Files.writeString(file, "old-1\nold-2\n")
        val tail = TranscriptTail(file, echo).apply { startAtEnd() }
        assertTrue(tail.poll().events.isEmpty())
        append(file, "new\n".toByteArray())
        assertEquals(listOf("new"), texts(tail.poll()))
    }

    @Test
    fun `半行保留到换行到达，跨写入的多字节字符不乱码`() {
        val file = temp.newFile("s.jsonl").toPath()
        val tail = TranscriptTail(file, echo).apply { startAtEnd() }
        val line = "中文消息".toByteArray(Charsets.UTF_8)
        append(file, line.copyOfRange(0, 4))
        assertTrue(tail.poll().events.isEmpty())
        append(file, line.copyOfRange(4, line.size) + "\n".toByteArray())
        assertEquals(listOf("中文消息"), texts(tail.poll()))
    }

    @Test
    fun `文件变短视为被替换，从新末尾重新开始`() {
        val file = temp.newFile("s.jsonl").toPath()
        Files.writeString(file, "a\nb\n")
        val tail = TranscriptTail(file, echo).apply { startAtEnd() }
        Files.writeString(file, "x\n")
        val poll = tail.poll()
        assertTrue(poll.restarted)
        assertTrue(poll.events.isEmpty())
        append(file, "y\n".toByteArray())
        val next = tail.poll()
        assertFalse(next.restarted)
        assertEquals(listOf("y"), texts(next))
    }

    @Test
    fun `超长行被跳过并计数，后续行正常`() {
        val file = temp.newFile("s.jsonl").toPath()
        val tail = TranscriptTail(file, echo, maxLineBytes = 8).apply { startAtEnd() }
        append(file, "0123456789abcdef\nok\n".toByteArray())
        assertEquals(listOf("ok"), texts(tail.poll()))
        assertEquals(1, tail.skippedOversizeLines)
    }

    @Test
    fun `文件暂不存在时不报错`() {
        val file = temp.root.toPath().resolve("missing.jsonl")
        val tail = TranscriptTail(file, echo).apply { startAtEnd() }
        Files.writeString(file, "first\n")
        assertEquals(listOf("first"), texts(tail.poll()))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*TranscriptTailTest'`
Expected: 编译失败，`Unresolved reference: TranscriptTail`

- [ ] **Step 3: 实现 `TranscriptTail.kt`**

```kotlin
package com.github.izerui.imux.bridge.transcript

import com.github.izerui.imux.bridge.core.SessionEvent
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

data class TailPoll(
    val events: List<SessionEvent>,
    /** 记录文件被替换或截短；已从新末尾重新开始，调用方应提示同步出现缺口。 */
    val restarted: Boolean,
)

/**
 * 只读跟随一份会话记录的新增内容。
 *
 * 只按完整行解码：半行留在缓冲里等换行，所以跨两次写入的多字节字符不会被拆坏。
 * 自己维护读位置，不与导航索引、轮次监控共享任何状态——它们“读一次就推进”，
 * 桥接若去调用它们，会抢走原有的完成通知。
 */
class TranscriptTail(
    private val path: Path,
    private val decoder: TranscriptDecoder,
    private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
) {
    private var position = 0L
    private var fileKey: Any? = null
    private val pending = ByteArrayOutputStream()
    private var discarding = false

    var skippedOversizeLines: Int = 0
        private set

    /** 以当前文件末尾为边界，之前的历史不转发。文件尚不存在时边界为 0。 */
    fun startAtEnd() {
        val attrs = attributes()
        position = attrs?.size() ?: 0L
        fileKey = attrs?.fileKey()
        pending.reset()
        discarding = false
    }

    fun poll(): TailPoll {
        val attrs = attributes() ?: return TailPoll(emptyList(), restarted = false)
        val key = attrs.fileKey()
        val replaced = (fileKey != null && key != null && key != fileKey) || attrs.size() < position
        if (replaced) {
            startAtEnd()
            return TailPoll(emptyList(), restarted = true)
        }
        if (fileKey == null) fileKey = key
        if (attrs.size() == position) return TailPoll(emptyList(), restarted = false)

        val events = mutableListOf<SessionEvent>()
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            channel.position(position)
            val buffer = ByteBuffer.allocate(READ_BUFFER_BYTES)
            while (true) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read <= 0) break
                position += read
                for (i in 0 until read) consume(buffer.get(i), events)
            }
        }
        return TailPoll(events, restarted = false)
    }

    private fun consume(
        byte: Byte,
        events: MutableList<SessionEvent>,
    ) {
        if (byte == NEWLINE) {
            if (!discarding && pending.size() > 0) {
                events += decoder.decode(pending.toString(StandardCharsets.UTF_8))
            }
            pending.reset()
            discarding = false
            return
        }
        if (discarding) return
        if (pending.size() >= maxLineBytes) {
            pending.reset()
            discarding = true
            skippedOversizeLines++
            return
        }
        pending.write(byte.toInt())
    }

    private fun attributes(): BasicFileAttributes? =
        runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }.getOrNull()

    private companion object {
        const val DEFAULT_MAX_LINE_BYTES = 8 * 1024 * 1024
        const val READ_BUFFER_BYTES = 64 * 1024
        const val NEWLINE: Byte = 10
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests '*TranscriptTailTest'`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/transcript/TranscriptTail.kt src/test/kotlin/com/github/izerui/imux/bridge/transcript/TranscriptTailTest.kt
git commit -m "feat(bridge): 新增会话记录增量只读尾随器"
```

---

### Task 4: 绑定注册表

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/core/BindingRegistry.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/core/BindingRegistryTest.kt`

**Interfaces:**
- Consumes: `RobotId`、`SessionTarget`（Task 1）
- Produces: `data class Binding(robotId, target, epoch: Long)`；`sealed interface BindResult { Bound(binding, replaced: Binding?); TargetTaken(by: Binding) }`；`class BindingRegistry` 的方法 `bind`、`unbind(robotId): Binding?`、`bindingOf(robotId): Binding?`、`bindingForTab(tabId): Binding?`、`isCurrent(robotId, epoch): Boolean`、`migrateSession(tabId, sessionId): Binding?`、`reattach(robotId, tabId): Binding?`、`snapshot(): List<Binding>`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.core

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BindingRegistryTest {
    private val a = RobotId("a")
    private val b = RobotId("b")

    private fun target(tab: String) = SessionTarget("/p", tab, AgentType.CLAUDE, "s-$tab")

    @Test
    fun `一个终端只能被一个机器人绑定`() {
        val registry = BindingRegistry()
        registry.bind(a, target("t1"))
        val result = registry.bind(b, target("t1"))
        assertTrue(result is BindResult.TargetTaken)
        assertEquals(a, (result as BindResult.TargetTaken).by.robotId)
        assertNull(registry.bindingOf(b))
    }

    @Test
    fun `换绑返回旧绑定并递增 epoch`() {
        val registry = BindingRegistry()
        val first = (registry.bind(a, target("t1")) as BindResult.Bound).binding
        val second = registry.bind(a, target("t2")) as BindResult.Bound
        assertEquals(first, second.replaced)
        assertTrue(second.binding.epoch > first.epoch)
        assertFalse(registry.isCurrent(a, first.epoch))
        assertTrue(registry.isCurrent(a, second.binding.epoch))
        assertNull(registry.bindingForTab("t1"))
    }

    @Test
    fun `会话迁移与重新挂接保留 epoch`() {
        val registry = BindingRegistry()
        val bound = (registry.bind(a, target("t1")) as BindResult.Bound).binding
        val migrated = registry.migrateSession("t1", "s-new")!!
        assertEquals("s-new", migrated.target.sessionId)
        assertEquals(bound.epoch, migrated.epoch)
        val reattached = registry.reattach(a, "t9")!!
        assertEquals("t9", reattached.target.tabId)
        assertTrue(registry.isCurrent(a, bound.epoch))
    }

    @Test
    fun `解绑后不再是当前绑定`() {
        val registry = BindingRegistry()
        val bound = (registry.bind(a, target("t1")) as BindResult.Bound).binding
        assertEquals(bound, registry.unbind(a))
        assertFalse(registry.isCurrent(a, bound.epoch))
        assertTrue(registry.snapshot().isEmpty())
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*BindingRegistryTest'`
Expected: 编译失败，`Unresolved reference: BindingRegistry`

- [ ] **Step 3: 实现 `BindingRegistry.kt`**

```kotlin
package com.github.izerui.imux.bridge.core

data class Binding(
    val robotId: RobotId,
    val target: SessionTarget,
    /** 用户每次绑定/换绑都会换新；会话迁移和重新挂接沿用，表示仍是同一个对话目标。 */
    val epoch: Long,
)

sealed interface BindResult {
    data class Bound(
        val binding: Binding,
        val replaced: Binding?,
    ) : BindResult

    data class TargetTaken(val by: Binding) : BindResult
}

/**
 * 机器人 ↔ 终端的 1:1 绑定表。全应用一份（机器人连接也是全应用一份）。
 *
 * 只记关系，不碰终端、渠道和网络；所有方法短小且同步，可在任意线程调用。
 */
class BindingRegistry {
    private val byRobot = LinkedHashMap<RobotId, Binding>()
    private var lastEpoch = 0L

    @Synchronized
    fun bind(
        robotId: RobotId,
        target: SessionTarget,
    ): BindResult {
        byRobot.values
            .firstOrNull { it.target.tabId == target.tabId && it.robotId != robotId }
            ?.let { return BindResult.TargetTaken(it) }
        val replaced = byRobot[robotId]
        val binding = Binding(robotId, target, ++lastEpoch)
        byRobot[robotId] = binding
        return BindResult.Bound(binding, replaced)
    }

    @Synchronized
    fun unbind(robotId: RobotId): Binding? = byRobot.remove(robotId)

    @Synchronized
    fun bindingOf(robotId: RobotId): Binding? = byRobot[robotId]

    @Synchronized
    fun bindingForTab(tabId: String): Binding? = byRobot.values.firstOrNull { it.target.tabId == tabId }

    @Synchronized
    fun isCurrent(
        robotId: RobotId,
        epoch: Long,
    ): Boolean = byRobot[robotId]?.epoch == epoch

    /** `/new`、`/clear` 或新会话落盘后，同一终端换了原生会话。 */
    @Synchronized
    fun migrateSession(
        tabId: String,
        sessionId: String,
    ): Binding? {
        val current = byRobot.values.firstOrNull { it.target.tabId == tabId } ?: return null
        return current.copy(target = current.target.copy(sessionId = sessionId)).also { byRobot[current.robotId] = it }
    }

    /** 项目重开后同一原生会话以新标签恢复。 */
    @Synchronized
    fun reattach(
        robotId: RobotId,
        tabId: String,
    ): Binding? {
        val current = byRobot[robotId] ?: return null
        return current.copy(target = current.target.copy(tabId = tabId)).also { byRobot[robotId] = it }
    }

    @Synchronized
    fun snapshot(): List<Binding> = byRobot.values.toList()
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests '*BindingRegistryTest'`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/core/BindingRegistry.kt src/test/kotlin/com/github/izerui/imux/bridge/core/BindingRegistryTest.kt
git commit -m "feat(bridge): 新增机器人与终端的 1:1 绑定注册表"
```

---

### Task 5: 路由器（Mediator）

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeRouter.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/core/BridgeRouterTest.kt`

**Interfaces:**
- Consumes: Task 1 全部类型、`TextSegmenter.split`、`BindingRegistry`、`Binding`
- Produces: `class BridgeRouter(bindings, ownerOf: (RobotId) -> RobotOwner?, sessionPort: TerminalSessionPort, channelOf: (RobotId) -> ChannelAdapter?, texts: BridgeTexts, onDelivery: (OutboundMessage, DeliveryResult) -> Unit = { _, _ -> })`，挂起方法 `onInbound(InboundMessage): InboundOutcome`、`onSessionEvents(tabId: String, events: List<SessionEvent>)`、`onTurnCompleted(tabId: String)`、`onWaiting(tabId: String, reason: String?)`、`onTranscriptRestarted(tabId: String)`、`onReleased(binding: Binding, sessionTitle: String)`

非本人消息的处理：静默丢弃、不回复。第 16 节原写“回复无权限（受频率限制）”，这里收紧为不回复，避免陌生人刷消息消耗机器人的发送额度；Task 10 同步修订文档。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.github.izerui.imux.bridge.core

import com.github.izerui.imux.model.AgentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRouterTest {
    private val robot = RobotId("r1")
    private val owner = RobotOwner(senderId = "me", chatId = "chat-1")
    private val target = SessionTarget("/p", "tab-1", AgentType.CODEX, "s1")

    private class FakePort(var result: SubmitResult = SubmitResult.SUBMITTED_TO_TERMINAL) : TerminalSessionPort {
        val submitted = mutableListOf<Pair<String, String>>()

        override suspend fun submit(
            target: SessionTarget,
            text: String,
        ): SubmitResult {
            submitted += target.tabId to text
            return result
        }
    }

    private class FakeChannel(
        override val robotId: RobotId,
        override val maxTextBytes: Int = 1000,
    ) : ChannelAdapter {
        val sent = mutableListOf<OutboundMessage>()
        var afterSend: () -> Unit = {}

        override suspend fun send(message: OutboundMessage): DeliveryResult {
            sent += message
            afterSend()
            return DeliveryResult.Accepted(null)
        }
    }

    private object Texts : BridgeTexts {
        override fun unbound() = "UNBOUND"

        override fun offline() = "OFFLINE"

        override fun notReady() = "NOT_READY"

        override fun emptyInput() = "EMPTY"

        override fun localInputLabel() = "LOCAL:"

        override fun completed(toolCalls: Int) = "DONE($toolCalls)"

        override fun waiting(reason: String?) = "WAIT($reason)"

        override fun released(sessionTitle: String) = "RELEASED($sessionTitle)"

        override fun transcriptRestarted() = "RESTARTED"
    }

    private val registry = BindingRegistry()
    private val port = FakePort()
    private val channel = FakeChannel(robot)
    private val router = BridgeRouter(registry, { if (it == robot) owner else null }, port, { if (it == robot) channel else null }, Texts)

    private fun inbound(
        id: String,
        text: String,
        sender: String = "me",
        chat: String = "chat-1",
    ) = InboundMessage(robot, id, sender, chat, text)

    private fun sentTexts() = channel.sent.map { it.text }

    @Test
    fun `非本人或其它聊天的消息不进入终端也不回复`() =
        runBlocking {
            registry.bind(robot, target)
            assertEquals(InboundOutcome.UNAUTHORIZED, router.onInbound(inbound("1", "rm -rf", sender = "stranger")))
            assertEquals(InboundOutcome.UNAUTHORIZED, router.onInbound(inbound("2", "hi", chat = "group-9")))
            assertTrue(port.submitted.isEmpty())
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun `未配对机器人的消息被忽略`() =
        runBlocking {
            val stranger = InboundMessage(RobotId("other"), "1", "me", "chat-1", "hi")
            assertEquals(InboundOutcome.NOT_PAIRED, router.onInbound(stranger))
            assertTrue(port.submitted.isEmpty())
        }

    @Test
    fun `未绑定时回复提示`() =
        runBlocking {
            assertEquals(InboundOutcome.UNBOUND, router.onInbound(inbound("1", "hi")))
            assertEquals(listOf("UNBOUND"), sentTexts())
            assertEquals(null, channel.sent.single().bindingEpoch)
        }

    @Test
    fun `平台重投同一消息只提交一次，相同文本不同 id 照常提交`() =
        runBlocking {
            registry.bind(robot, target)
            router.onInbound(inbound("1", "继续"))
            assertEquals(InboundOutcome.DUPLICATE, router.onInbound(inbound("1", "继续")))
            router.onInbound(inbound("2", "继续"))
            assertEquals(listOf("tab-1" to "继续", "tab-1" to "继续"), port.submitted)
        }

    @Test
    fun `空消息不提交并提示`() =
        runBlocking {
            registry.bind(robot, target)
            assertEquals(InboundOutcome.EMPTY, router.onInbound(inbound("1", "   ")))
            assertTrue(port.submitted.isEmpty())
            assertEquals(listOf("EMPTY"), sentTexts())
        }

    @Test
    fun `远程输入在记录中出现时不回显，电脑输入带标签转发，助手回复原文转发`() =
        runBlocking {
            registry.bind(robot, target)
            router.onInbound(inbound("1", "修一下  测试"))
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent(SessionEventKind.USER_TEXT, "修一下 测试"),
                    SessionEvent(SessionEventKind.ASSISTANT_TEXT, "好的\n```\ncode\n```"),
                    SessionEvent(SessionEventKind.USER_TEXT, "电脑上补一句"),
                ),
            )
            assertEquals(listOf("好的\n```\ncode\n```", "LOCAL:\n电脑上补一句"), sentTexts())
        }

    @Test
    fun `终端未就绪或离线时回复对应提示`() =
        runBlocking {
            registry.bind(robot, target)
            port.result = SubmitResult.NOT_READY
            assertEquals(InboundOutcome.NOT_READY, router.onInbound(inbound("1", "a")))
            port.result = SubmitResult.OFFLINE
            assertEquals(InboundOutcome.OFFLINE, router.onInbound(inbound("2", "b")))
            assertEquals(listOf("NOT_READY", "OFFLINE"), sentTexts())
        }

    @Test
    fun `未提交成功的远程文本不参与回显抑制`() =
        runBlocking {
            registry.bind(robot, target)
            port.result = SubmitResult.NOT_READY
            router.onInbound(inbound("1", "同一句"))
            channel.sent.clear()
            router.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.USER_TEXT, "同一句")))
            assertEquals(listOf("LOCAL:\n同一句"), sentTexts())
        }

    @Test
    fun `轮次完成报告工具次数，等待确认带原因`() =
        runBlocking {
            registry.bind(robot, target)
            router.onSessionEvents(
                "tab-1",
                listOf(
                    SessionEvent(SessionEventKind.TOOL_STARTED, "Read", "t1"),
                    SessionEvent(SessionEventKind.TOOL_FINISHED, "", "t1"),
                    SessionEvent(SessionEventKind.TOOL_STARTED, "Edit", "t2"),
                ),
            )
            router.onWaiting("tab-1", "Edit")
            router.onTurnCompleted("tab-1")
            router.onTurnCompleted("tab-1")
            assertEquals(listOf("WAIT(Edit)", "DONE(2)", "DONE(0)"), sentTexts())
        }

    @Test
    fun `未绑定终端的事件不转发`() =
        runBlocking {
            router.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "x")))
            router.onTurnCompleted("tab-1")
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun `分段发送途中换绑，剩余分段不再发往旧目标`() =
        runBlocking {
            val small = FakeChannel(robot, maxTextBytes = 64)
            val r = BridgeRouter(registry, { owner }, port, { small }, Texts)
            registry.bind(robot, target)
            small.afterSend = { registry.bind(robot, target.copy(tabId = "tab-2")) }
            r.onSessionEvents("tab-1", listOf(SessionEvent(SessionEventKind.ASSISTANT_TEXT, "行内容\n".repeat(40))))
            assertEquals(1, small.sent.size)
        }

    @Test
    fun `释放时向原聊天发送断开提示`() =
        runBlocking {
            val bound = (registry.bind(robot, target) as BindResult.Bound).binding
            registry.unbind(robot)
            router.onReleased(bound, "修复滚动")
            assertEquals(listOf("RELEASED(修复滚动)"), sentTexts())
        }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*BridgeRouterTest'`
Expected: 编译失败，`Unresolved reference: BridgeRouter`

- [ ] **Step 3: 实现 `BridgeRouter.kt`**

```kotlin
package com.github.izerui.imux.bridge.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * 渠道与终端之间的中介。两边互不认识，只通过绑定关系连接。
 *
 * 入站：校验本人 → 平台消息去重 → 找绑定 → 交给终端端口。不判断 Agent 忙闲，
 * 排队还是插入由 CLI 自己决定；这里只保证按到达顺序逐条提交。
 * 出站：助手文本原文转发；电脑端输入带标签转发；远程输入在记录中再次出现时不回显；
 * 工具调用只计数，在轮次完成时汇总，避免逐条通知触发平台限频。
 */
class BridgeRouter(
    private val bindings: BindingRegistry,
    private val ownerOf: (RobotId) -> RobotOwner?,
    private val sessionPort: TerminalSessionPort,
    private val channelOf: (RobotId) -> ChannelAdapter?,
    private val texts: BridgeTexts,
    private val onDelivery: (OutboundMessage, DeliveryResult) -> Unit = { _, _ -> },
) {
    private val submitLock = Mutex()
    private val lock = Any()
    private val seen = HashMap<RobotId, LinkedHashSet<String>>()
    private val pendingRemoteEcho = HashMap<String, ArrayDeque<String>>()
    private val toolCalls = HashMap<String, Int>()

    suspend fun onInbound(message: InboundMessage): InboundOutcome {
        val owner = ownerOf(message.robotId) ?: return InboundOutcome.NOT_PAIRED
        if (message.senderId != owner.senderId || message.chatId != owner.chatId) return InboundOutcome.UNAUTHORIZED
        if (!markSeen(message.robotId, message.externalMessageId)) return InboundOutcome.DUPLICATE

        val text = message.text.trim()
        if (text.isEmpty()) {
            reply(message.robotId, owner, texts.emptyInput())
            return InboundOutcome.EMPTY
        }
        val binding = bindings.bindingOf(message.robotId)
        if (binding == null) {
            reply(message.robotId, owner, texts.unbound())
            return InboundOutcome.UNBOUND
        }

        val tabId = binding.target.tabId
        val result =
            submitLock.withLock {
                // 先登记再提交：CLI 可能在 submit 返回前就把这句写进记录
                rememberRemote(tabId, text)
                sessionPort.submit(binding.target, text)
            }
        if (result != SubmitResult.SUBMITTED_TO_TERMINAL) forgetRemote(tabId, text)
        return when (result) {
            SubmitResult.SUBMITTED_TO_TERMINAL -> InboundOutcome.SUBMITTED_TO_TERMINAL
            SubmitResult.NOT_READY -> {
                reply(message.robotId, owner, texts.notReady())
                InboundOutcome.NOT_READY
            }
            SubmitResult.OFFLINE -> {
                reply(message.robotId, owner, texts.offline())
                InboundOutcome.OFFLINE
            }
        }
    }

    suspend fun onSessionEvents(
        tabId: String,
        events: List<SessionEvent>,
    ) {
        val binding = bindings.bindingForTab(tabId) ?: return
        for (event in events) {
            when (event.kind) {
                SessionEventKind.ASSISTANT_TEXT -> send(binding, event.text)
                SessionEventKind.USER_TEXT ->
                    if (!consumeRemoteEcho(tabId, event.text)) send(binding, texts.localInputLabel() + "\n" + event.text)
                SessionEventKind.TOOL_STARTED -> synchronized(lock) { toolCalls.merge(tabId, 1, Int::plus) }
                SessionEventKind.TOOL_FINISHED -> Unit
            }
        }
    }

    suspend fun onTurnCompleted(tabId: String) {
        val binding = bindings.bindingForTab(tabId) ?: return
        val count = synchronized(lock) { toolCalls.remove(tabId) ?: 0 }
        send(binding, texts.completed(count))
    }

    suspend fun onWaiting(
        tabId: String,
        reason: String?,
    ) {
        val binding = bindings.bindingForTab(tabId) ?: return
        send(binding, texts.waiting(reason))
    }

    suspend fun onTranscriptRestarted(tabId: String) {
        val binding = bindings.bindingForTab(tabId) ?: return
        send(binding, texts.transcriptRestarted())
    }

    /** 绑定已被移除后调用：不再做 epoch 校验，提示发往原聊天。 */
    suspend fun onReleased(
        binding: Binding,
        sessionTitle: String,
    ) {
        synchronized(lock) {
            pendingRemoteEcho.remove(binding.target.tabId)
            toolCalls.remove(binding.target.tabId)
        }
        val owner = ownerOf(binding.robotId) ?: return
        val channel = channelOf(binding.robotId) ?: return
        deliver(channel, OutboundMessage(binding.robotId, owner.chatId, binding.epoch, texts.released(sessionTitle)))
    }

    private suspend fun send(
        binding: Binding,
        text: String,
    ) {
        val owner = ownerOf(binding.robotId) ?: return
        val channel = channelOf(binding.robotId) ?: return
        for (segment in TextSegmenter.split(text, channel.maxTextBytes)) {
            // 每段发送前都核对：分段途中被换绑，剩余内容不能落到新目标的聊天里
            if (!bindings.isCurrent(binding.robotId, binding.epoch)) return
            deliver(channel, OutboundMessage(binding.robotId, owner.chatId, binding.epoch, segment))
        }
    }

    private suspend fun reply(
        robotId: RobotId,
        owner: RobotOwner,
        text: String,
    ) {
        val channel = channelOf(robotId) ?: return
        deliver(channel, OutboundMessage(robotId, owner.chatId, bindingEpoch = null, text = text))
    }

    private suspend fun deliver(
        channel: ChannelAdapter,
        message: OutboundMessage,
    ) {
        val result =
            try {
                channel.send(message)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 渠道异常不能冒泡到终端或监控；结果不明，交给上层显示，不自动重发
                DeliveryResult.Unknown
            }
        onDelivery(message, result)
    }

    private fun markSeen(
        robotId: RobotId,
        externalMessageId: String,
    ): Boolean =
        synchronized(lock) {
            val ids = seen.getOrPut(robotId) { LinkedHashSet() }
            if (!ids.add(externalMessageId)) return false
            while (ids.size > SEEN_CAPACITY) ids.remove(ids.first())
            true
        }

    private fun rememberRemote(
        tabId: String,
        text: String,
    ) = synchronized(lock) {
        val queue = pendingRemoteEcho.getOrPut(tabId) { ArrayDeque() }
        queue.addLast(normalize(text))
        while (queue.size > ECHO_CAPACITY) queue.removeFirst()
    }

    private fun forgetRemote(
        tabId: String,
        text: String,
    ) = synchronized(lock) {
        pendingRemoteEcho[tabId]?.remove(normalize(text))
    }

    private fun consumeRemoteEcho(
        tabId: String,
        text: String,
    ): Boolean = synchronized(lock) { pendingRemoteEcho[tabId]?.remove(normalize(text)) == true }

    /** CLI 写入记录时可能调整空白；只按空白归一后的全文匹配，匹配不上就当电脑输入。 */
    private fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    private companion object {
        const val SEEN_CAPACITY = 1000
        const val ECHO_CAPACITY = 20
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests '*BridgeRouterTest'`
Expected: 12 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeRouter.kt src/test/kotlin/com/github/izerui/imux/bridge/core/BridgeRouterTest.kt
git commit -m "feat(bridge): 新增渠道与终端之间的路由器"
```

---

### Task 6: 轮次事件广播（唯一的现有代码改动）

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/turn/TurnEvents.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt`（`TurnNotifier.notifyCompleted(...)` 调用之后；`waiting` 循环里 `if (!waitingNotificationWanted(...)) return@forEach` 之前）
- Test: `src/test/kotlin/com/github/izerui/imux/turn/TurnEventsTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/turn/TurnEventsSourceTest.kt`

**Interfaces:**
- Produces: `interface TurnEventsListener { fun turnCompleted(sessionId: String); fun turnWaiting(sessionId: String, reason: String?) }`，`TurnEventsListener.TOPIC`；`fun publishTurnEvent(project: Project, event: (TurnEventsListener) -> Unit)`；可测试的内核 `internal fun deliverTurnEvent(publisher: () -> TurnEventsListener, event: (TurnEventsListener) -> Unit, onFailure: (Throwable) -> Unit)`

等待事件放在“正在看就不弹气泡”的过滤之前：人坐在电脑前不需要气泡，但手机端仍要知道会话停下了。广播用 `deliverTurnEvent` 包住，任何监听方异常都不会影响原有通知。

- [ ] **Step 1: 写失败测试**

`TurnEventsTest.kt`：

```kotlin
package com.github.izerui.imux.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class TurnEventsTest {
    private val failing =
        object : TurnEventsListener {
            override fun turnCompleted(sessionId: String) {
                throw IllegalStateException("boom")
            }

            override fun turnWaiting(
                sessionId: String,
                reason: String?,
            ) {
            }
        }

    @Test
    fun `监听方异常被吞下并上报，不影响调用方`() {
        val failures = mutableListOf<Throwable>()
        deliverTurnEvent({ failing }, { it.turnCompleted("s") }, failures::add)
        assertEquals(1, failures.size)
    }

    @Test
    fun `取消信号照常向上传递`() {
        val cancelling =
            object : TurnEventsListener {
                override fun turnCompleted(sessionId: String) {
                    throw CancellationException("cancel")
                }

                override fun turnWaiting(
                    sessionId: String,
                    reason: String?,
                ) {
                }
            }
        val thrown = runCatching { deliverTurnEvent({ cancelling }, { it.turnCompleted("s") }, {}) }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }
}
```

`TurnEventsSourceTest.kt`：

```kotlin
package com.github.izerui.imux.turn

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnEventsSourceTest {
    private val monitor =
        SourceCode("src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt").normalized

    @Test
    fun `完成广播紧跟在原有完成通知之后`() {
        val notify = monitor.indexOf("TurnNotifier.notifyCompleted(")
        val publish = monitor.indexOf("publishTurnEvent(project) { it.turnCompleted(sessionId) }")
        assertTrue(notify >= 0 && publish > notify)
    }

    @Test
    fun `等待广播在气泡过滤之前`() {
        val publish = monitor.indexOf("publishTurnEvent(project) { it.turnWaiting(waiting.sessionId, waiting.reason) }")
        val filter = monitor.indexOf("if (!waitingNotificationWanted(waiting.sessionId, selected)) return@forEach")
        assertTrue(publish >= 0 && filter > publish)
    }

    @Test
    fun `SessionMonitor 只新增两处广播`() {
        assertEquals(2, Regex("publishTurnEvent\\(").findAll(monitor).count())
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*TurnEventsTest' --tests '*TurnEventsSourceTest'`
Expected: 编译失败，`Unresolved reference: deliverTurnEvent`

- [ ] **Step 3: 实现 `TurnEvents.kt`**

```kotlin
package com.github.izerui.imux.turn

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.util.EventListener
import kotlin.coroutines.cancellation.CancellationException

/**
 * 会话轮次事件的项目级广播。只广播，不改变 [TurnNotifier] 与监控状态机的任何行为。
 *
 * 放在 turn 包而不是消费方的包里：发布方（SessionMonitor）因此不必知道谁在听，
 * 依赖方向始终是“新功能依赖现有代码”，而不是反过来。
 */
interface TurnEventsListener : EventListener {
    fun turnCompleted(sessionId: String)

    fun turnWaiting(
        sessionId: String,
        reason: String?,
    )

    companion object {
        @JvmField
        @Topic.ProjectLevel
        val TOPIC: Topic<TurnEventsListener> =
            Topic(TurnEventsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/** 发布一条轮次事件。监听方的任何异常都只记日志，绝不影响调用方原有的通知流程。 */
fun publishTurnEvent(
    project: Project,
    event: (TurnEventsListener) -> Unit,
) {
    if (project.isDisposed) return
    deliverTurnEvent({ project.messageBus.syncPublisher(TurnEventsListener.TOPIC) }, event) {
        LOG.warn("轮次事件监听方异常，已忽略", it)
    }
}

internal fun deliverTurnEvent(
    publisher: () -> TurnEventsListener,
    event: (TurnEventsListener) -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    try {
        event(publisher())
    } catch (e: Exception) {
        if (e is CancellationException || e is ControlFlowException) throw e
        onFailure(e)
    }
}

private val LOG = logger<TurnEventsListener>()
```

- [ ] **Step 4: 修改 `SessionMonitor.kt` 的两处**

在 `completed.forEach { sessionId -> … }` 中，紧跟 `TurnNotifier.notifyCompleted(…)` 调用之后插入：

```kotlin
                        TurnNotifier.notifyCompleted(
                            project,
                            sessionId,
                            title,
                            session?.agentType,
                            duration,
                            ::openSessionFromNotification,
                        )
                        publishTurnEvent(project) { it.turnCompleted(sessionId) }
```

在 `outcome.waiting.forEach { waiting -> … }` 中，`markUnread(waiting.sessionId)` 之后、过滤之前插入：

```kotlin
                            markUnread(waiting.sessionId)
                            // 人正看着就不弹气泡，但手机端仍需要知道会话停下了
                            publishTurnEvent(project) { it.turnWaiting(waiting.sessionId, waiting.reason) }

                            // 人正看着它就别弹了，CLI 的选择框已经占在屏幕上
                            if (!waitingNotificationWanted(waiting.sessionId, selected)) return@forEach
```

并在文件 import 区加入：

```kotlin
import com.github.izerui.imux.turn.publishTurnEvent
```

- [ ] **Step 5: 运行新测试与现有监控测试**

Run: `./gradlew test --tests '*TurnEventsTest' --tests '*TurnEventsSourceTest' --tests '*SessionMonitorTest' --tests '*TurnWatcherTest' --tests '*PeerCoordinatorSourceTest'`
Expected: 全部 PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/turn/TurnEvents.kt src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt src/test/kotlin/com/github/izerui/imux/turn/TurnEventsTest.kt src/test/kotlin/com/github/izerui/imux/turn/TurnEventsSourceTest.kt
git commit -m "feat(turn): 广播轮次完成与等待事件，不改变原有通知"
```

---

### Task 7: 终端端口（Facade）与绑定生命周期判定

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/terminal/ImuxTerminalSessionPort.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/terminal/BindingLifecycle.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/terminal/TerminalSubmitTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/terminal/BindingLifecycleTest.kt`

**Interfaces:**
- Consumes: `TerminalSessionPort`、`SessionTarget`、`SubmitResult`、`Binding`（Task 1/4）；现有 `TerminalHost.getInstance(project)`、`openTabsByTabId(): Map<String, String>`、`terminalViewOf(sessionKey): TerminalView?`
- Produces: `class ImuxTerminalSessionPort : TerminalSessionPort`；`internal fun submitText(builder: TerminalSendTextBuilder, text: String): Boolean`；`sealed interface TabChange { data object Keep; data class Reattach(val tabId: String); data object Release }`；`fun tabChangeFor(binding: Binding, openTabs: Map<String, String>, seenTabs: Set<String>, projectClosing: Boolean): TabChange`

生命周期规则（第 16 节）：标签仍开着 → Keep；同一原生会话以新标签打开 → Reattach；本服务见过该标签打开、现在没了、项目不在关闭中 → Release（用户关标签、删除会话、CLI 退出）；项目关闭中或本服务从未见过该标签（例如项目重开、标签尚在恢复） → Keep（离线）。“从未见过”这条防止重开项目逐个恢复标签期间误释放。

- [ ] **Step 1: 写失败测试**

`TerminalSubmitTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.terminal

import org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class TerminalSubmitTest {
    private fun builder(
        calls: MutableList<String>,
        accept: Boolean,
    ): TerminalSendTextBuilder {
        lateinit var proxy: TerminalSendTextBuilder
        proxy =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TerminalSendTextBuilder::class.java)) { _, method, args ->
                calls += method.name + (args?.joinToString(prefix = "(", postfix = ")") ?: "")
                when (method.name) {
                    "trySend" -> accept
                    "send" -> null
                    else -> proxy
                }
            } as TerminalSendTextBuilder
        return proxy
    }

    @Test
    fun `一次发送正文并提交，要求括号粘贴模式`() {
        val calls = mutableListOf<String>()
        assertTrue(submitText(builder(calls, accept = true), "修一下"))
        assertEquals(listOf("requireBracketedPasteMode", "shouldExecute", "trySend(修一下)"), calls)
    }

    @Test
    fun `终端未开启括号粘贴时如实返回未送出`() {
        assertFalse(submitText(builder(mutableListOf(), accept = false), "x"))
    }
}
```

`BindingLifecycleTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Test

class BindingLifecycleTest {
    private val binding = Binding(RobotId("r"), SessionTarget("/p", "tab-1", AgentType.CLAUDE, "s1"), epoch = 1)

    @Test
    fun `标签仍开着则保持`() {
        assertEquals(TabChange.Keep, tabChangeFor(binding, mapOf("tab-1" to "s1"), setOf("tab-1"), projectClosing = false))
    }

    @Test
    fun `同一会话以新标签打开则重新挂接`() {
        assertEquals(
            TabChange.Reattach("tab-9"),
            tabChangeFor(binding, mapOf("tab-9" to "s1"), emptySet(), projectClosing = false),
        )
    }

    @Test
    fun `见过的标签消失且项目未在关闭则释放`() {
        assertEquals(TabChange.Release, tabChangeFor(binding, emptyMap(), setOf("tab-1"), projectClosing = false))
    }

    @Test
    fun `项目关闭中保持离线`() {
        assertEquals(TabChange.Keep, tabChangeFor(binding, emptyMap(), setOf("tab-1"), projectClosing = true))
    }

    @Test
    fun `本服务从未见过的标签不释放，等待恢复`() {
        assertEquals(TabChange.Keep, tabChangeFor(binding, emptyMap(), emptySet(), projectClosing = false))
    }

    @Test
    fun `新会话未落盘时不按 sessionId 误挂接`() {
        val pending = binding.copy(target = binding.target.copy(sessionId = null))
        assertEquals(TabChange.Release, tabChangeFor(pending, mapOf("tab-2" to "other"), setOf("tab-1"), projectClosing = false))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*TerminalSubmitTest' --tests '*BindingLifecycleTest'`
Expected: 编译失败，`Unresolved reference: submitText`

- [ ] **Step 3: 实现 `BindingLifecycle.kt`**

```kotlin
package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.Binding

sealed interface TabChange {
    data object Keep : TabChange

    data class Reattach(val tabId: String) : TabChange

    data object Release : TabChange
}

/**
 * 标签集合变化后，一条绑定该怎么处理。
 *
 * TerminalHost.closeSession 不区分“用户关标签”“CLI 退出”“项目关闭”，这里按
 * [projectClosing] 与 [seenTabs]（本服务实例亲眼见过打开的标签）自行区分，
 * 不改 closeSession 的语义。拖动、分屏由平台的 CLOSING_TO_REOPEN 保证不触发关闭。
 *
 * @param openTabs 当前打开的 tabId → sessionKey
 */
fun tabChangeFor(
    binding: Binding,
    openTabs: Map<String, String>,
    seenTabs: Set<String>,
    projectClosing: Boolean,
): TabChange {
    val target = binding.target
    if (target.tabId in openTabs) return TabChange.Keep
    target.sessionId
        ?.let { sessionId -> openTabs.entries.firstOrNull { it.value == sessionId }?.key }
        ?.let { return TabChange.Reattach(it) }
    if (projectClosing || target.tabId !in seenTabs) return TabChange.Keep
    return TabChange.Release
}
```

- [ ] **Step 4: 实现 `ImuxTerminalSessionPort.kt`**

```kotlin
package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.bridge.core.SubmitResult
import com.github.izerui.imux.bridge.core.TerminalSessionPort
import com.github.izerui.imux.terminal.TerminalHost
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.terminal.view.TerminalSendTextBuilder

/**
 * 桥接触达终端的唯一入口，只调用 TerminalHost 已公开的方法。
 *
 * 每次提交都重新按 tabId 查找终端，不缓存 view：标签可能已关闭、拖动重建或被迁移。
 * 不检查 Agent 是否在忙；排队还是插入由 CLI 决定。找不到终端就是离线，绝不启动 CLI。
 */
class ImuxTerminalSessionPort(
    private val projectAt: (String) -> Project? = ::openProjectAt,
) : TerminalSessionPort {
    override suspend fun submit(
        target: SessionTarget,
        text: String,
    ): SubmitResult {
        repeat(NOT_READY_ATTEMPTS) { attempt ->
            val result = withContext(Dispatchers.EDT) { submitOnce(target, text) }
            if (result != SubmitResult.NOT_READY) return result
            if (attempt < NOT_READY_ATTEMPTS - 1) delay(NOT_READY_RETRY_DELAY_MS)
        }
        return SubmitResult.NOT_READY
    }

    private fun submitOnce(
        target: SessionTarget,
        text: String,
    ): SubmitResult {
        val project = projectAt(target.projectPath)?.takeUnless { it.isDisposed } ?: return SubmitResult.OFFLINE
        val host = TerminalHost.getInstance(project)
        val sessionKey = host.openTabsByTabId()[target.tabId] ?: return SubmitResult.OFFLINE
        val view = host.terminalViewOf(sessionKey) ?: return SubmitResult.OFFLINE
        if (view.sessionState.value is TerminalViewSessionState.Terminated) return SubmitResult.OFFLINE
        return if (submitText(view.createSendTextBuilder(), text)) SubmitResult.SUBMITTED_TO_TERMINAL else SubmitResult.NOT_READY
    }

    private companion object {
        const val NOT_READY_ATTEMPTS = 3
        const val NOT_READY_RETRY_DELAY_MS = 300L
    }
}

/**
 * 与副驾驶已抓包验证的形态一致：require 在括号粘贴未开启时一个字节都不写并返回 false；
 * 正文与回车同一次发送，回车落在括号外才会被 CLI 当作提交。
 */
internal fun submitText(
    builder: TerminalSendTextBuilder,
    text: String,
): Boolean = builder.requireBracketedPasteMode().shouldExecute().trySend(text)

private fun openProjectAt(projectPath: String): Project? =
    ProjectManager.getInstance().openProjects.firstOrNull { it.basePath == projectPath }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew test --tests '*TerminalSubmitTest' --tests '*BindingLifecycleTest'`
Expected: 8 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/terminal src/test/kotlin/com/github/izerui/imux/bridge/terminal
git commit -m "feat(bridge): 新增终端提交端口与绑定生命周期判定"
```

---

### Task 8: 开关、文案、应用级 Hub、项目级服务与启动接线

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/BridgeSettings.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/BundleBridgeTexts.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/BridgeHub.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/TerminalBridgeService.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/BridgeStartupActivity.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`（`ImuxStartupActivity` 注册之后）
- Modify: `src/main/resources/messages/ImuxBundle.properties` 及 9 个翻译文件（文件末尾追加）
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/BridgeWiringSourceTest.kt`

**Interfaces:**
- Consumes: Task 1–7 全部；现有 `TerminalHost.addSessionsChangedListener(Disposable, TerminalSessionsListener)`、`addSessionKeyMigratedListener(Disposable, SessionKeyMigratedListener)`、`openTabsByTabId()`；`SessionMonitor.getInstance(project).model.sessionOf(id)?.filePath/title`；`TurnEventsListener.TOPIC`
- Produces: `BridgeSettings.getInstance().state.enabled`；`BridgeHub.getInstance()`，成员 `bindings: BindingRegistry`、`router: BridgeRouter`、`inboundSink: InboundSink`、`registerRobot(adapter: ChannelAdapter, owner: RobotOwner?)`、`unregisterRobot(robotId: RobotId)`；`TerminalBridgeService.getInstance(project).start()`

平台接线层在本项目只能做源码断言（见 `build.gradle.kts` 关于 test-framework 的说明），因此这里的测试是结构断言，行为由 Task 1–7 的纯逻辑测试覆盖。

- [ ] **Step 1: 写失败的结构测试**

```kotlin
package com.github.izerui.imux.bridge

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeWiringSourceTest {
    private val service =
        SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/TerminalBridgeService.kt").normalized
    private val startup =
        SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BridgeStartupActivity.kt").normalized
    private val hub = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BridgeHub.kt").normalized
    private val pluginXml = java.io.File("src/main/resources/META-INF/plugin.xml").readText()

    @Test
    fun `开关关闭时不启动任何订阅与轮询`() {
        assertTrue(startup.contains("if (!BridgeSettings.getInstance().state.enabled) return"))
    }

    @Test
    fun `只订阅现有事件，不调用原有状态机`() {
        assertTrue(service.contains("host.addSessionsChangedListener(this)"))
        assertTrue(service.contains("host.addSessionKeyMigratedListener(this)"))
        assertTrue(service.contains("TurnEventsListener.TOPIC"))
        assertTrue(!service.contains("turnWatcher"))
        assertTrue(!service.contains("TurnNotifier"))
        assertTrue(!service.contains("clearUnread"))
    }

    @Test
    fun `桥接协程与故障隔离`() {
        assertTrue(service.contains("SupervisorJob"))
        assertTrue(hub.contains("SupervisorJob"))
    }

    @Test
    fun `入站消息经单一消费者按到达顺序处理`() {
        assertTrue(hub.contains("Channel<InboundMessage>(Channel.UNLIMITED)"))
        assertTrue(hub.contains("for (message in inbound)"))
    }

    @Test
    fun `注册了桥接启动活动`() {
        assertTrue(pluginXml.contains("com.github.izerui.imux.bridge.BridgeStartupActivity"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*BridgeWiringSourceTest'`
Expected: FAIL（文件不存在）

- [ ] **Step 3: 实现 `BridgeSettings.kt`**

```kotlin
package com.github.izerui.imux.bridge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 通讯桥接总开关。独立存储，不修改 ImuxSettings；默认关闭。
 * 设置界面在机器人管理计划中提供。
 */
@Service(Service.Level.APP)
@State(name = "ImuxBridgeSettings", storages = [Storage("imux-bridge.xml", roamingType = RoamingType.DISABLED)])
class BridgeSettings : SimplePersistentStateComponent<BridgeSettings.State>(State()) {
    class State : BaseState() {
        var enabled: Boolean by property(false)
    }

    companion object {
        fun getInstance(): BridgeSettings = ApplicationManager.getApplication().getService(BridgeSettings::class.java)
    }
}
```

- [ ] **Step 4: 追加文案（10 个文件）**

`ImuxBundle.properties`：

```properties
bridge.reply.unbound=This bot is not connected to any imux session. Connect it from a session context menu in the IDE.
bridge.reply.offline=The connected session is offline. Open it in the IDE to continue.
bridge.reply.notReady=The terminal is not ready for input, so this message was not sent. Please try again shortly.
bridge.reply.emptyInput=The message was empty, so nothing was sent.
bridge.label.localInput=Typed in the IDE:
bridge.status.completed=Turn finished · tool calls: {0}
bridge.status.waiting=Waiting for you in the IDE: {0}
bridge.status.waitingNoReason=Waiting for you in the IDE.
bridge.status.released=Disconnected from session: {0}
bridge.status.transcriptRestarted=The session record was replaced. Syncing continues from now on.
```

`ImuxBundle_zh_CN.properties`：

```properties
bridge.reply.unbound=这个机器人还没有连接 imux 会话。请在 IDE 中通过会话右键菜单连接。
bridge.reply.offline=已连接的会话当前离线，请在 IDE 中打开它后再继续。
bridge.reply.notReady=终端暂时无法接收输入，这条消息没有发送，请稍后重试。
bridge.reply.emptyInput=消息为空，没有发送。
bridge.label.localInput=电脑端输入：
bridge.status.completed=本轮完成 · 工具调用 {0} 次
bridge.status.waiting=等待你在 IDE 中处理：{0}
bridge.status.waitingNoReason=等待你在 IDE 中处理。
bridge.status.released=已断开会话：{0}
bridge.status.transcriptRestarted=会话记录已被替换，从现在起继续同步。
```

`ImuxBundle_zh_TW.properties`：

```properties
bridge.reply.unbound=這個機器人尚未連接 imux 工作階段。請在 IDE 中透過工作階段右鍵選單連接。
bridge.reply.offline=已連接的工作階段目前離線，請在 IDE 中開啟後再繼續。
bridge.reply.notReady=終端機暫時無法接收輸入，這則訊息沒有送出，請稍後重試。
bridge.reply.emptyInput=訊息為空，沒有送出。
bridge.label.localInput=電腦端輸入：
bridge.status.completed=本輪完成 · 工具呼叫 {0} 次
bridge.status.waiting=等待你在 IDE 中處理：{0}
bridge.status.waitingNoReason=等待你在 IDE 中處理。
bridge.status.released=已中斷工作階段：{0}
bridge.status.transcriptRestarted=工作階段記錄已被取代，從現在起繼續同步。
```

`ImuxBundle_ja.properties`：

```properties
bridge.reply.unbound=このボットはまだ imux セッションに接続されていません。IDE のセッションのコンテキストメニューから接続してください。
bridge.reply.offline=接続中のセッションはオフラインです。IDE で開いてから続けてください。
bridge.reply.notReady=ターミナルが入力を受け付けられないため、このメッセージは送信されませんでした。しばらくしてから再試行してください。
bridge.reply.emptyInput=メッセージが空のため、送信しませんでした。
bridge.label.localInput=IDE での入力：
bridge.status.completed=ターン完了 · ツール呼び出し {0} 回
bridge.status.waiting=IDE での対応を待っています：{0}
bridge.status.waitingNoReason=IDE での対応を待っています。
bridge.status.released=セッションから切断しました：{0}
bridge.status.transcriptRestarted=セッション記録が置き換えられました。ここから同期を続けます。
```

`ImuxBundle_ko.properties`：

```properties
bridge.reply.unbound=이 봇은 아직 imux 세션에 연결되지 않았습니다. IDE에서 세션 컨텍스트 메뉴로 연결하세요.
bridge.reply.offline=연결된 세션이 오프라인입니다. IDE에서 연 후 계속하세요.
bridge.reply.notReady=터미널이 입력을 받을 수 없어 이 메시지를 보내지 않았습니다. 잠시 후 다시 시도하세요.
bridge.reply.emptyInput=메시지가 비어 있어 보내지 않았습니다.
bridge.label.localInput=IDE에서 입력:
bridge.status.completed=턴 완료 · 도구 호출 {0}회
bridge.status.waiting=IDE에서 처리를 기다리는 중: {0}
bridge.status.waitingNoReason=IDE에서 처리를 기다리는 중입니다.
bridge.status.released=세션 연결 해제: {0}
bridge.status.transcriptRestarted=세션 기록이 교체되었습니다. 지금부터 동기화를 계속합니다.
```

`ImuxBundle_de.properties`：

```properties
bridge.reply.unbound=Dieser Bot ist mit keiner imux-Sitzung verbunden. Verbinde ihn in der IDE über das Kontextmenü einer Sitzung.
bridge.reply.offline=Die verbundene Sitzung ist offline. Öffne sie in der IDE, um fortzufahren.
bridge.reply.notReady=Das Terminal kann gerade keine Eingabe annehmen, daher wurde diese Nachricht nicht gesendet. Bitte versuche es gleich noch einmal.
bridge.reply.emptyInput=Die Nachricht war leer, es wurde nichts gesendet.
bridge.label.localInput=In der IDE eingegeben:
bridge.status.completed=Runde abgeschlossen · Tool-Aufrufe: {0}
bridge.status.waiting=Wartet auf dich in der IDE: {0}
bridge.status.waitingNoReason=Wartet auf dich in der IDE.
bridge.status.released=Von Sitzung getrennt: {0}
bridge.status.transcriptRestarted=Das Sitzungsprotokoll wurde ersetzt. Die Synchronisierung läuft ab jetzt weiter.
```

`ImuxBundle_es.properties`：

```properties
bridge.reply.unbound=Este bot no está conectado a ninguna sesión de imux. Conéctalo desde el menú contextual de una sesión en el IDE.
bridge.reply.offline=La sesión conectada está sin conexión. Ábrela en el IDE para continuar.
bridge.reply.notReady=El terminal no puede recibir entrada ahora, así que este mensaje no se envió. Vuelve a intentarlo en un momento.
bridge.reply.emptyInput=El mensaje estaba vacío, no se envió nada.
bridge.label.localInput=Escrito en el IDE:
bridge.status.completed=Turno terminado · llamadas a herramientas: {0}
bridge.status.waiting=Esperando tu acción en el IDE: {0}
bridge.status.waitingNoReason=Esperando tu acción en el IDE.
bridge.status.released=Desconectado de la sesión: {0}
bridge.status.transcriptRestarted=El registro de la sesión fue reemplazado. La sincronización continúa desde ahora.
```

`ImuxBundle_fr.properties`（无占位符的行用单个 `'`，有占位符的行用 `''`）：

```properties
bridge.reply.unbound=Ce bot n'est connecté à aucune session imux. Connectez-le depuis le menu contextuel d'une session dans l'IDE.
bridge.reply.offline=La session connectée est hors ligne. Ouvrez-la dans l'IDE pour continuer.
bridge.reply.notReady=Le terminal ne peut pas recevoir de saisie pour le moment, ce message n'a donc pas été envoyé. Réessayez dans un instant.
bridge.reply.emptyInput=Le message était vide, rien n'a été envoyé.
bridge.label.localInput=Saisi dans l'IDE :
bridge.status.completed=Tour terminé · appels d''outils : {0}
bridge.status.waiting=En attente de votre action dans l''IDE : {0}
bridge.status.waitingNoReason=En attente de votre action dans l'IDE.
bridge.status.released=Déconnecté de la session : {0}
bridge.status.transcriptRestarted=L'historique de la session a été remplacé. La synchronisation reprend à partir de maintenant.
```

`ImuxBundle_pt_BR.properties`：

```properties
bridge.reply.unbound=Este bot não está conectado a nenhuma sessão do imux. Conecte-o pelo menu de contexto de uma sessão no IDE.
bridge.reply.offline=A sessão conectada está offline. Abra-a no IDE para continuar.
bridge.reply.notReady=O terminal não pode receber entrada agora, então esta mensagem não foi enviada. Tente novamente em instantes.
bridge.reply.emptyInput=A mensagem estava vazia, nada foi enviado.
bridge.label.localInput=Digitado no IDE:
bridge.status.completed=Rodada concluída · chamadas de ferramentas: {0}
bridge.status.waiting=Aguardando você no IDE: {0}
bridge.status.waitingNoReason=Aguardando você no IDE.
bridge.status.released=Desconectado da sessão: {0}
bridge.status.transcriptRestarted=O registro da sessão foi substituído. A sincronização continua a partir de agora.
```

`ImuxBundle_ru.properties`：

```properties
bridge.reply.unbound=Этот бот не подключён ни к одной сессии imux. Подключите его через контекстное меню сессии в IDE.
bridge.reply.offline=Подключённая сессия сейчас не в сети. Откройте её в IDE, чтобы продолжить.
bridge.reply.notReady=Терминал сейчас не принимает ввод, поэтому сообщение не отправлено. Повторите попытку чуть позже.
bridge.reply.emptyInput=Сообщение пустое, ничего не отправлено.
bridge.label.localInput=Введено в IDE:
bridge.status.completed=Ход завершён · вызовов инструментов: {0}
bridge.status.waiting=Ожидает ваших действий в IDE: {0}
bridge.status.waitingNoReason=Ожидает ваших действий в IDE.
bridge.status.released=Отключено от сессии: {0}
bridge.status.transcriptRestarted=Журнал сессии был заменён. Синхронизация продолжается с этого момента.
```

- [ ] **Step 5: 实现 `BundleBridgeTexts.kt`**

```kotlin
package com.github.izerui.imux.bridge

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.bridge.core.BridgeTexts

internal object BundleBridgeTexts : BridgeTexts {
    override fun unbound() = ImuxBundle.message("bridge.reply.unbound")

    override fun offline() = ImuxBundle.message("bridge.reply.offline")

    override fun notReady() = ImuxBundle.message("bridge.reply.notReady")

    override fun emptyInput() = ImuxBundle.message("bridge.reply.emptyInput")

    override fun localInputLabel() = ImuxBundle.message("bridge.label.localInput")

    override fun completed(toolCalls: Int) = ImuxBundle.message("bridge.status.completed", toolCalls)

    override fun waiting(reason: String?) =
        if (reason.isNullOrBlank()) {
            ImuxBundle.message("bridge.status.waitingNoReason")
        } else {
            ImuxBundle.message("bridge.status.waiting", reason)
        }

    override fun released(sessionTitle: String) = ImuxBundle.message("bridge.status.released", sessionTitle)

    override fun transcriptRestarted() = ImuxBundle.message("bridge.status.transcriptRestarted")
}
```

- [ ] **Step 6: 实现 `BridgeHub.kt`**

```kotlin
package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.BindingRegistry
import com.github.izerui.imux.bridge.core.BridgeRouter
import com.github.izerui.imux.bridge.core.ChannelAdapter
import com.github.izerui.imux.bridge.core.InboundMessage
import com.github.izerui.imux.bridge.core.InboundSink
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.RobotOwner
import com.github.izerui.imux.bridge.terminal.ImuxTerminalSessionPort
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 全应用唯一的桥接中枢：绑定表、路由器、已注册的渠道。
 *
 * 机器人连接与绑定都是全应用一份，多个项目窗口共用，避免同一机器人被重复连接而互踢。
 * 入站消息进同一个队列由单一消费者处理，保证按到达顺序提交到终端。
 */
@Service(Service.Level.APP)
class BridgeHub(parentScope: CoroutineScope) {
    private val scope =
        CoroutineScope(
            parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job]) +
                CoroutineExceptionHandler { _, e -> LOG.warn("桥接任务异常，已隔离", e) },
        )
    private val channels = ConcurrentHashMap<RobotId, ChannelAdapter>()
    private val owners = ConcurrentHashMap<RobotId, RobotOwner>()
    private val inbound = Channel<InboundMessage>(Channel.UNLIMITED)

    val bindings = BindingRegistry()
    val router = BridgeRouter(bindings, owners::get, ImuxTerminalSessionPort(), channels::get, BundleBridgeTexts)

    /** 渠道收到消息后调用。开关关闭时直接丢弃，不进入终端。 */
    val inboundSink = InboundSink { message -> if (BridgeSettings.getInstance().state.enabled) inbound.trySend(message) }

    init {
        scope.launch {
            for (message in inbound) {
                runCatching { router.onInbound(message) }.onFailure { LOG.warn("处理入站消息失败", it) }
            }
        }
    }

    /** 由机器人管理在连接成功后调用；[owner] 为 null 表示尚未配对，消息一律忽略。 */
    fun registerRobot(
        adapter: ChannelAdapter,
        owner: RobotOwner?,
    ) {
        channels[adapter.robotId] = adapter
        if (owner != null) owners[adapter.robotId] = owner else owners.remove(adapter.robotId)
    }

    fun unregisterRobot(robotId: RobotId) {
        bindings.unbind(robotId)
        channels.remove(robotId)
        owners.remove(robotId)
    }

    internal fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)

    companion object {
        private val LOG = logger<BridgeHub>()

        fun getInstance(): BridgeHub = ApplicationManager.getApplication().getService(BridgeHub::class.java)
    }
}
```

`runCatching` 会吞掉 `CancellationException`；消费循环本身随 `scope` 取消而结束（`for` 在挂起点抛出取消），单条消息内的取消被吞下只影响该条，符合“故障隔离、不冒泡”的要求。

- [ ] **Step 7: 实现 `TerminalBridgeService.kt`**

```kotlin
package com.github.izerui.imux.bridge

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.terminal.TabChange
import com.github.izerui.imux.bridge.terminal.tabChangeFor
import com.github.izerui.imux.bridge.transcript.TranscriptDecoders
import com.github.izerui.imux.bridge.transcript.TranscriptTail
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.terminal.TerminalHost
import com.github.izerui.imux.turn.TurnEventsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 一个项目里绑定终端的观察者：订阅标签变化、会话迁移与轮次事件，旁路读取会话记录。
 *
 * 只订阅、只读。不调用 TurnWatcher、TurnNotifier 或未读标记；不初始化懒启动的恢复标签。
 */
@Service(Service.Level.PROJECT)
class TerminalBridgeService(
    private val project: Project,
    parentScope: CoroutineScope,
) : Disposable {
    private val scope =
        CoroutineScope(
            parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, e -> LOG.warn("项目桥接任务异常，已隔离", e) },
        )
    private val projectPath = project.basePath ?: System.getProperty("user.home")
    private val hub get() = BridgeHub.getInstance()
    private val tails = ConcurrentHashMap<String, TranscriptTail>()
    private val pollLock = Mutex()
    private val seenTabs = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var projectClosing = false
    private var started = false

    fun start() {
        if (started) return
        started = true
        val host = TerminalHost.getInstance(project)
        seenTabs += host.openTabsByTabId().keys
        host.addSessionsChangedListener(this) { onSessionsChanged() }
        host.addSessionKeyMigratedListener(this) { _, to -> onMigrated(to) }
        project.messageBus.connect(this).subscribe(
            ProjectCloseListener.TOPIC,
            object : ProjectCloseListener {
                override fun projectClosing(project: Project) {
                    if (project === this@TerminalBridgeService.project) projectClosing = true
                }
            },
        )
        project.messageBus.connect(this).subscribe(
            TurnEventsListener.TOPIC,
            object : TurnEventsListener {
                override fun turnCompleted(sessionId: String) {
                    val tabId = tabIdOf(sessionId) ?: return
                    scope.launch(Dispatchers.IO) {
                        // 先把最后的回复读出来，再报完成，手机上的顺序才和终端一致
                        pollTab(tabId)
                        hub.router.onTurnCompleted(tabId)
                    }
                }

                override fun turnWaiting(
                    sessionId: String,
                    reason: String?,
                ) {
                    val tabId = tabIdOf(sessionId) ?: return
                    scope.launch(Dispatchers.IO) {
                        pollTab(tabId)
                        hub.router.onWaiting(tabId, reason)
                    }
                }
            },
        )
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                boundHere().forEach { pollTab(it.target.tabId) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** 在 EDT 调用（TerminalHost 的回调线程）。 */
    private fun onSessionsChanged() {
        val open = TerminalHost.getInstance(project).openTabsByTabId()
        seenTabs += open.keys
        for (binding in boundHere()) {
            when (val change = tabChangeFor(binding, open, seenTabs, projectClosing)) {
                TabChange.Keep -> Unit
                is TabChange.Reattach -> {
                    hub.bindings.reattach(binding.robotId, change.tabId)
                    tails.remove(binding.target.tabId)
                }
                TabChange.Release -> release(binding)
            }
        }
    }

    private fun release(binding: Binding) {
        if (hub.bindings.bindingOf(binding.robotId)?.epoch != binding.epoch) return
        hub.bindings.unbind(binding.robotId)
        tails.remove(binding.target.tabId)
        val title = titleOf(binding.target.sessionId)
        scope.launch(Dispatchers.IO) { hub.router.onReleased(binding, title) }
    }

    private fun onMigrated(to: String) {
        val tabId = tabIdOf(to) ?: return
        if (hub.bindings.migrateSession(tabId, to) != null) tails.remove(tabId)
    }

    private suspend fun pollTab(tabId: String) {
        pollLock.withLock {
            val binding = hub.bindings.bindingForTab(tabId) ?: return
            val tail = tails[tabId] ?: newTail(binding) ?: return
            val poll = tail.poll()
            if (poll.restarted) hub.router.onTranscriptRestarted(tabId)
            if (poll.events.isNotEmpty()) hub.router.onSessionEvents(tabId, poll.events)
        }
    }

    /** 会话文件出现后才建立尾随器，并以那一刻的末尾为转发起点。 */
    private fun newTail(binding: Binding): TranscriptTail? {
        val sessionId = binding.target.sessionId ?: return null
        val file = SessionMonitor.getInstance(project).model.sessionOf(sessionId)?.filePath ?: return null
        return TranscriptTail(file, TranscriptDecoders.forAgent(binding.target.agentType))
            .also { it.startAtEnd() }
            .also { tails[binding.target.tabId] = it }
    }

    private fun boundHere(): List<Binding> = hub.bindings.snapshot().filter { it.target.projectPath == projectPath }

    private fun tabIdOf(sessionKey: String): String? =
        TerminalHost.getInstance(project).openTabsByTabId().entries.firstOrNull { it.value == sessionKey }?.key

    private fun titleOf(sessionId: String?): String =
        sessionId?.let { SessionMonitor.getInstance(project).model.sessionOf(it)?.title } ?: sessionId?.take(8).orEmpty()

    override fun dispose() = Unit

    companion object {
        private val LOG = logger<TerminalBridgeService>()
        private const val POLL_INTERVAL_MS = 1_000L

        fun getInstance(project: Project): TerminalBridgeService = project.getService(TerminalBridgeService::class.java)
    }
}
```

`pollTab` 中的 `return` 位于 `withLock` 的内联 lambda 内，会从 `pollTab` 返回并照常释放锁。

- [ ] **Step 8: 实现 `BridgeStartupActivity.kt` 并注册**

```kotlin
package com.github.izerui.imux.bridge

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** 开关打开时启动本项目的桥接观察。关闭时什么也不做，现有功能不受任何影响。 */
class BridgeStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!BridgeSettings.getInstance().state.enabled) return
        if (!project.isDisposed) TerminalBridgeService.getInstance(project).start()
    }
}
```

`plugin.xml` 在 `ImuxStartupActivity` 的注册之后追加：

```xml
        <!-- 终端会话通讯桥接：仅在开关打开时启动，见 docs/terminal-channel-bridge-feasibility.md -->
        <postStartupActivity
                implementation="com.github.izerui.imux.bridge.BridgeStartupActivity"/>
```

- [ ] **Step 9: 运行结构测试、文案与注册测试**

Run: `./gradlew test --tests '*BridgeWiringSourceTest' --tests '*ImuxBundleTest' --tests '*PluginXmlRegistrationTest'`
Expected: 全部 PASS

- [ ] **Step 10: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge src/main/resources/META-INF/plugin.xml src/main/resources/messages src/test/kotlin/com/github/izerui/imux/bridge/BridgeWiringSourceTest.kt
git commit -m "feat(bridge): 接入开关、文案、应用级中枢与项目级观察服务"
```

---

### Task 9: 依赖方向隔离测试

**Files:**
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/BridgeIsolationSourceTest.kt`

**Interfaces:**
- Consumes: 现有 `SourceCode`
- Produces: 无（守护约束）

- [ ] **Step 1: 写测试**

```kotlin
package com.github.izerui.imux.bridge

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 桥接是旁路扩展：只能是新代码依赖现有代码，现有代码不能反过来依赖桥接。
 * 这条测试失败，说明有人把桥接逻辑写进了原有功能里。
 */
class BridgeIsolationSourceTest {
    private val root = File("src/main/kotlin/com/github/izerui/imux")

    @Test
    fun `bridge 以外的源码不引用 bridge 包`() {
        val offenders =
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { it.path.contains("${File.separator}bridge${File.separator}") }
                .filter { SourceCode(it.path).normalized.contains("com.github.izerui.imux.bridge") }
                .map { it.path }
                .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `渠道与路由内核不依赖 Agent 类型和平台终端`() {
        val core = File(root, "bridge/core")
        core.listFiles { f -> f.extension == "kt" && f.name != "BridgeModels.kt" }!!.forEach { file ->
            val code = SourceCode(file.path).normalized
            assertTrue("${file.name} 引用了 AgentType", !code.contains("AgentType"))
            assertTrue("${file.name} 引用了终端平台 API", !code.contains("org.jetbrains.plugins.terminal"))
            assertTrue("${file.name} 引用了 intellij API", !code.contains("com.intellij"))
        }
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `./gradlew test --tests '*BridgeIsolationSourceTest'`
Expected: 2 tests PASS。若失败，按报错路径把越界引用移回 `bridge/`，不得放宽断言。

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/github/izerui/imux/bridge/BridgeIsolationSourceTest.kt
git commit -m "test(bridge): 守护桥接的依赖方向与内核中立性"
```

---

### Task 10: 全量回归、IDE 检查与文档同步

**Files:**
- Modify: `docs/terminal-channel-bridge-feasibility.md`（追加第 17 节；修订第 16 节“非本人消息”一句）

- [ ] **Step 1: 运行全部测试**

Run: `./gradlew test`
Expected: 全部 PASS，失败数 0。任何既有测试失败都视为回归，先修复再继续。

- [ ] **Step 2: IDE 编译与检查**

使用 IDEA MCP：`build_project`（不传参数）应无错误；`lint_files` 检查本计划创建或修改的全部 Kotlin 文件、`plugin.xml` 与 10 个 properties 文件，不新增 warning 以上的问题。

- [ ] **Step 3: 插件验证（网络可用时）**

Run: `./gradlew verifyPlugin`
Expected: 不新增兼容性问题。若因本机网络无法下载 IDE，记录跳过原因，不得报告为通过。

- [ ] **Step 4: 在设计文档追加第 17 节并修订第 16 节**

把第 16 节“非本人消息不进入 CLI，只回复无权限（受频率限制，避免被刷消耗发送额度）。”改为：

```markdown
非本人消息不进入 CLI，也不回复，避免陌生人刷消息消耗机器人的发送额度。
```

在文档末尾追加：

```markdown
## 17. 代码结构与隔离约束

实施计划：`docs/superpowers/plans/2026-10-26-terminal-channel-bridge-core.md`。

| 层 | 模式 | 位置 | 约束 |
| --- | --- | --- | --- |
| 渠道 | Adapter | `bridge/core/BridgePorts.kt: ChannelAdapter`，实现放 `bridge/channels/` | 不认识 Agent、终端、绑定 |
| 路由 | Mediator | `bridge/core/BridgeRouter.kt` | 不按 AgentType 分支；不依赖 IntelliJ API |
| 绑定 | 注册表 | `bridge/core/BindingRegistry.kt` | 1:1；用户换绑递增 epoch，迁移/重新挂接沿用 |
| 终端 | Facade | `bridge/terminal/ImuxTerminalSessionPort.kt` | 只调用 TerminalHost 公开方法；不启动 CLI |
| 记录 | Strategy | `bridge/transcript/` | 只读；全文不截断；自有读位置 |
| 事件 | Observer | `turn/TurnEvents.kt` | SessionMonitor 只新增两处广播，监听异常不影响原通知 |

依赖方向由 `BridgeIsolationSourceTest` 强制：`bridge/` 以外的源码不得引用桥接包。总开关 `BridgeSettings.enabled` 默认关闭，关闭时不订阅、不轮询、不读取。
```

- [ ] **Step 5: Commit**

```bash
git add docs/terminal-channel-bridge-feasibility.md
git commit -m "docs(bridge): 记录桥接代码结构与隔离约束"
```

---

## 后续计划（各自独立成文，本计划完成后再写）

1. **机器人管理与企业微信**：应用级机器人列表与绑定持久化（`PasswordSafe` 存 Secret，绑定按 Agent + sessionId 持久化，IDE 重启后按 `reattach` 恢复）；企业微信长连接适配器（订阅、心跳、重连、被踢下线不抢连）；扫码接入（核实 `source` 参数可用性）；配对码确定本人；会话右键“连接到手机”与设置页开关；发送箱、未知结果与失败展示。
2. **飞书**：官方 Java SDK 的 `RegisterApp` 扫码与长连接；入站 3 秒内返回；语音需转写的分支。
3. **钉钉**：设备授权扫码与 Stream；扫码后配对；月度额度耗尽时的 20001 提示。
4. **QQ**：`startQrConnect` 流程移植、单聊被动回复与主动消息额度。
5. **普通微信**：iLink 扫码（每个微信号一个入口）、`context_token` 维护、`-14` 冷却。
6. **真实端到端验收**：设计文档第 11 节全部用例，以及“IDE 发一句手机能看到、手机回一句 IDE 能看到、同一轮 MCP 可调用”。
