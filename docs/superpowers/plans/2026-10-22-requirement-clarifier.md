# 需求澄清 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在会话终端里加入「需求澄清」：单次 Agent 结合项目代码逐轮找出需求里没想到的方向，逐个让用户确认，最后把调整后的需求直接发给当前会话执行。

**Architecture:** 插件持有台账（原需求、方向列表、问答记录、调查笔记），每轮把台账完整交给一次全新的单次 Agent 调用（沿用轨迹的 `runOneShotAgent`）；Agent 只返回 JSON 提议，插件解析、校验后合并，用户操作始终优先。纯逻辑（提示词、协议、台账、状态机）不依赖 IDE，可直接单测；项目级服务负责调用 CLI、进度、取消、迁移与发送；非模态 `DialogWrapper` 只负责显示与转发。

**Tech Stack:** Kotlin、IntelliJ Platform 262（Swing / JB 组件 / Action System）、Gson、kotlinx.coroutines、JUnit 4。

**设计依据:** `docs/superpowers/specs/2026-10-22-requirement-clarifier-design.md`（下称"设计文档"）。

## Global Constraints

- IntelliJ Platform build `262` 及以上；不加旧版兼容分支。
- 原生 UI 优先：`DialogWrapper`、`JBSplitter`、`JBList`、`JBTextArea`、`JBLabel`、`JBScrollPane`、`AllIcons`、`JBUI`；图标按钮与右键菜单走 Action System。
- 测试只用 JUnit 4；测试方法用中文反引号命名；方法名含「任何 / 都 / 两 / 一律 / 全部 / 各」时，承诺的每一支各有一条独立断言。
- 读源文件做结构断言的测试必须复用 `src/test/kotlin/com/github/izerui/imux/SourceCode.kt`。
- 提示词中英两套：`ImuxBundle.currentLanguage()` 为 `SIMPLIFIED_CHINESE` / `TRADITIONAL_CHINESE` 用中文，其余用英文。
- 单次 Agent 一律经 `oneShotAgentInvocation` + `runOneShotAgent`（最大权限、不保存会话、按设置注入 IDEA MCP），固定用当前会话的 `agentType`；每轮超时 `600` 秒。
- 背景快照上限 `8_000` 字符；调查笔记上限 `6_000` 字符。
- 输出只有调整后的需求本身，不附带任何内容；发送复用 `trySendPeerFeedback`（括号粘贴 + 回车），不写文件。
- 发送时机与结对编程对齐：会话 ID 在 `SessionMonitor.runningIds` 中、或 Claude 运行态 `isWaiting` 时等待，其余立即发；等待与重试可取消。
- 界面线程上不做文件读取；背景快照在 IO 线程读取。
- 所有用户可见文案进 `ImuxBundle*.properties`，10 个语言文件键集合相同、占位符一致（`ImuxBundleTest` 会检查）。
- 测试命令：`./gradlew test --tests '<全限定类名>'`；全量 `./gradlew test`。

## 文件结构

| 文件 | 职责 |
|---|---|
| Create `src/main/kotlin/com/github/izerui/imux/clarification/ClarificationModels.kt` | 台账与问答的数据类型、常量 |
| Create `src/main/kotlin/com/github/izerui/imux/clarification/ClarificationPrompt.kt` | 读取内嵌指南、各阶段提示词拼装 |
| Create `src/main/kotlin/com/github/izerui/imux/clarification/ClarificationProtocol.kt` | Agent 返回的 JSON 解析与结构校验 |
| Create `src/main/kotlin/com/github/izerui/imux/clarification/ClarificationLedger.kt` | 用户操作、合并 Agent 提议（临时 ID、锁定、previous、next 校验）、兜底拼接 |
| Create `src/main/kotlin/com/github/izerui/imux/clarification/ClarificationFlow.kt` | 状态机：一轮调查（含重试一次）、生成结果、取消、失败、发送状态 |
| Create `src/main/kotlin/com/github/izerui/imux/clarification/ClarificationService.kt` | 项目级服务：按会话保存流程、调 CLI、进度、取消、迁移、关闭清理、发送 |
| Create `src/main/kotlin/com/github/izerui/imux/clarification/ClarificationDialog.kt` | 非模态窗口 |
| Create `src/main/kotlin/com/github/izerui/imux/terminal/ClarifyRequirementAction.kt` | 终端右键菜单入口 |
| Modify `src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt` | 抽出 `latestConversationOf` 供澄清复用 |
| Modify `src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt` | 横幅入口、关闭标签页时清理 |
| Modify `src/main/resources/META-INF/plugin.xml` | 注册右键菜单 action |
| Modify `src/main/resources/messages/ImuxBundle*.properties`（10 个） | 文案 |
| Test `src/test/kotlin/com/github/izerui/imux/clarification/*Test.kt` | 纯逻辑测试 |

---

### Task 1: 数据模型与提示词拼装

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/clarification/ClarificationModels.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/clarification/ClarificationPrompt.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/clarification/ClarificationPromptTest.kt`

**Interfaces:**
- Consumes: 资源 `/clarification/requirement-clarifier.zh.md`、`/clarification/requirement-clarifier.en.md`（已存在）。
- Produces:
  - `internal enum class DirectionStatus { OPEN, ACTIVE, DECIDED, DEFAULTED, DROPPED }`
  - `internal data class Direction(id, title, why, source, fallback, status, userOwned, conclusion: String?, openPoints: List<String>)`
  - `internal data class ChoiceOption(id, label, recommended: Boolean, conclusion)`
  - `internal data class Question(directionId, text, reason, basis, options: List<ChoiceOption>)`
  - `internal sealed interface Answer { Picked(optionId) ; OwnWords(text) ; Skipped }`
  - `internal data class AnswerRecord(question: Question, answer: Answer, recorded: String)`
  - `internal data class Ledger(...)`，含 `direction(id)`、`replace(direction)`
  - `internal enum class Phase { FIRST, ROUND, CORRECTED, RESULT }`
  - `internal fun loadGuidance(isChinese: Boolean): String`
  - `internal fun stripFrontMatter(text: String): String`
  - `internal fun clarificationPrompt(phase: Phase, ledger: Ledger, isChinese: Boolean, guidance: String, errors: List<String> = emptyList()): String`
  - `internal const val JSON_ONLY_ZH` / `JSON_ONLY_EN`、`MAX_BACKGROUND_CHARS = 8_000`、`MAX_NOTES_CHARS = 6_000`

- [ ] **Step 1: 写模型**

```kotlin
// ClarificationModels.kt
package com.github.izerui.imux.clarification

/** 方向的状态，见设计文档「方向状态」。 */
internal enum class DirectionStatus { OPEN, ACTIVE, DECIDED, DEFAULTED, DROPPED }

internal data class Direction(
    val id: String,
    val title: String,
    val why: String,
    val source: String,
    /** 用户跳过时的默认处理，事先写明；用户没见过的默认，他也就没法拒绝。 */
    val fallback: String,
    val status: DirectionStatus = DirectionStatus.OPEN,
    /** 用户添加或重新打开的方向：Agent 不能去掉，只能由用户的回答来定。 */
    val userOwned: Boolean = false,
    val conclusion: String? = null,
    val openPoints: List<String> = emptyList(),
)

internal data class ChoiceOption(
    val id: String,
    val label: String,
    val recommended: Boolean,
    /** 选了它，方向就定为什么；界面原样显示，选中后原样记下。 */
    val conclusion: String,
)

internal data class Question(
    val directionId: String,
    val text: String,
    val reason: String,
    val basis: String,
    val options: List<ChoiceOption>,
)

internal sealed interface Answer {
    data class Picked(val optionId: String) : Answer

    data class OwnWords(val text: String) : Answer

    data object Skipped : Answer
}

/** 一次问答；[recorded] 是记下的方向结论：选项自带的结论、用户原话或跳过时的默认处理。 */
internal data class AnswerRecord(
    val question: Question,
    val answer: Answer,
    val recorded: String,
)

/** 插件持有的台账，每轮完整交给 Agent。 */
internal data class Ledger(
    val requirement: String,
    val background: String?,
    val understanding: String? = null,
    val corrections: List<String> = emptyList(),
    val directions: List<Direction> = emptyList(),
    val history: List<AnswerRecord> = emptyList(),
    val notes: String = "",
    /** 用户「先问这个」指定的方向；该方向的问题展示出来后清空。 */
    val pinned: String? = null,
    /** 用户刚回答（非跳过）的方向；下一轮返回必须用 previous 交代它。 */
    val lastAsked: String? = null,
    val nextDirectionNumber: Int = 1,
    val nextUserNumber: Int = 1,
) {
    fun direction(id: String): Direction? = directions.firstOrNull { it.id == id }

    fun replace(direction: Direction): Ledger =
        copy(directions = directions.map { if (it.id == direction.id) direction else it })
}

internal const val MAX_BACKGROUND_CHARS = 8_000
internal const val MAX_NOTES_CHARS = 6_000
```

- [ ] **Step 2: 写失败的测试**

```kotlin
// ClarificationPromptTest.kt
package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationPromptTest {
    private val ledger =
        Ledger(
            requirement = "给终端增加断线重连",
            background = "User: 之前的对话",
            directions = listOf(
                Direction("d1", "恢复范围", "影响状态保存", "TerminalSession.kt", "只恢复连接"),
                Direction("d2", "多标签页", "影响范围", "TerminalHost.kt", "逐个重连", status = DirectionStatus.DEFAULTED),
            ),
            pinned = "d1",
            lastAsked = "d1",
        )

    private fun rawResource(name: String): String =
        ClarificationPromptTest::class.java.getResourceAsStream("/clarification/$name")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `去掉 front matter 只留正文`() {
        assertEquals("# 标题\n正文", stripFrontMatter("---\nname: x\n---\n# 标题\n正文"))
        assertEquals("没有头", stripFrontMatter("没有头"))
    }

    @Test
    fun `中英两套指南各自读到对应资源的正文`() {
        val zh = loadGuidance(isChinese = true)
        val en = loadGuidance(isChinese = false)
        assertEquals(stripFrontMatter(rawResource("requirement-clarifier.zh.md")).trim(), zh)
        assertEquals(stripFrontMatter(rawResource("requirement-clarifier.en.md")).trim(), en)
        assertFalse(zh.startsWith("---"))
        assertFalse(en.startsWith("---"))
    }

    @Test
    fun `提示词包含指南与台账全部内容`() {
        val prompt = clarificationPrompt(Phase.ROUND, ledger, isChinese = true, guidance = "指南正文")
        assertTrue(prompt.startsWith("指南正文"))
        assertTrue(prompt.contains("给终端增加断线重连"))
        assertTrue(prompt.contains("User: 之前的对话"))
        assertTrue(prompt.contains("恢复范围"))
        assertTrue(prompt.contains("多标签页"))
    }

    @Test
    fun `提示词写明先问这个与冻结方向与需要交代的上一轮方向`() {
        val prompt = clarificationPrompt(Phase.ROUND, ledger, isChinese = false, guidance = "g")
        assertTrue(prompt.contains("\"mustAsk\": \"d1\""))
        assertTrue(prompt.contains("\"frozen\": [\n      \"d2\"\n    ]"))
        assertTrue(prompt.contains("\"reportPrevious\": \"d1\""))
    }

    @Test
    fun `上一次的错误附在提示词里`() {
        val prompt = clarificationPrompt(Phase.ROUND, ledger, isChinese = false, guidance = "g", errors = listOf("options must have 2-4 items"))
        assertTrue(prompt.contains("options must have 2-4 items"))
    }

    @Test
    fun `各阶段提示词都以只输出 JSON 且不修改文件结尾`() {
        assertTrue(clarificationPrompt(Phase.FIRST, ledger, true, "g").trimEnd().endsWith(JSON_ONLY_ZH))
        assertTrue(clarificationPrompt(Phase.ROUND, ledger, true, "g").trimEnd().endsWith(JSON_ONLY_ZH))
        assertTrue(clarificationPrompt(Phase.CORRECTED, ledger, true, "g").trimEnd().endsWith(JSON_ONLY_ZH))
        assertTrue(clarificationPrompt(Phase.RESULT, ledger, true, "g").trimEnd().endsWith(JSON_ONLY_ZH))
        assertTrue(clarificationPrompt(Phase.RESULT, ledger, false, "g").trimEnd().endsWith(JSON_ONLY_EN))
    }

    @Test
    fun `生成结果阶段只要求返回 requirement`() {
        val prompt = clarificationPrompt(Phase.RESULT, ledger, isChinese = false, guidance = "g")
        assertTrue(prompt.contains("{\"requirement\": \"...\"}"))
        assertFalse(prompt.contains("\"next\""))
    }
}
```

- [ ] **Step 3: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.clarification.ClarificationPromptTest'`
Expected: 编译失败（`loadGuidance`、`clarificationPrompt` 等未定义）。

- [ ] **Step 4: 写实现**

```kotlin
// ClarificationPrompt.kt
package com.github.izerui.imux.clarification

import com.google.gson.GsonBuilder

internal enum class Phase { FIRST, ROUND, CORRECTED, RESULT }

internal const val JSON_ONLY_ZH = "只输出一个 JSON 对象，不要输出其他文字。不修改任何文件。"
internal const val JSON_ONLY_EN = "Output exactly one JSON object and nothing else. Do not modify any file."

private object GuidanceAnchor

/** 内嵌指南正文（随插件 jar 分发）；拼进提示词前去掉 front matter。 */
internal fun loadGuidance(isChinese: Boolean): String {
    val name = if (isChinese) "requirement-clarifier.zh.md" else "requirement-clarifier.en.md"
    val stream = GuidanceAnchor::class.java.getResourceAsStream("/clarification/$name")
        ?: error("Missing bundled guidance /clarification/$name")
    return stripFrontMatter(stream.bufferedReader().use { it.readText() }).trim()
}

internal fun stripFrontMatter(text: String): String {
    val normalized = text.replace("\r\n", "\n")
    if (!normalized.startsWith("---\n")) return normalized
    val end = normalized.indexOf("\n---\n", startIndex = 4)
    return if (end < 0) normalized else normalized.substring(end + "\n---\n".length)
}

internal fun clarificationPrompt(
    phase: Phase,
    ledger: Ledger,
    isChinese: Boolean,
    guidance: String,
    errors: List<String> = emptyList(),
): String =
    buildString {
        append(guidance.trim()).append("\n\n")
        append(if (isChinese) "## 本阶段\n\n" else "## This Phase\n\n")
        append(phaseInstruction(phase, isChinese)).append("\n\n")
        append(if (isChinese) "## 当前状态\n\n" else "## Current State\n\n")
        append("```json\n").append(ledgerJson(ledger)).append("\n```\n\n")
        if (errors.isNotEmpty()) {
            append(if (isChinese) "## 上一次的输出有问题，请修正后重新输出\n\n" else "## Your previous output was rejected; fix these and answer again\n\n")
            errors.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        append(if (isChinese) "## 输出格式\n\n" else "## Output Format\n\n")
        append(if (phase == Phase.RESULT) RESULT_FORMAT else ROUND_FORMAT).append("\n\n")
        append(if (isChinese) JSON_ONLY_ZH else JSON_ONLY_EN)
    }

private fun phaseInstruction(phase: Phase, isChinese: Boolean): String =
    if (isChinese) {
        when (phase) {
            Phase.FIRST -> "这是首轮。先调查项目，给出 understanding（你的理解、代码现状与这次会碰到哪里），在 directions.add 里列出所有需要用户确认的方向，并给出第一个问题；没有需要确认的方向时直接返回 done。"
            Phase.ROUND -> "用户已回答上一轮的问题（见 history 最后一条）。带着全部已确认内容继续调查：用 previous 交代 reportPrevious 指向的方向是否已定，重新审视方向列表，给出下一个问题；没有需要用户拍板的方向了就返回 done。"
            Phase.CORRECTED -> "用户纠正了你的理解（见 corrections）。按新理解重新调查，重新给出 understanding，并重新审视方向列表；其余同逐轮要求。"
            Phase.RESULT -> "用户要求现在生成结果。把原需求与所有 DECIDED、DEFAULTED 方向的 conclusion 融合成调整后的需求：精炼，只到方向层，不附带任何说明、依据、未决事项或标记；没定的方向不要写成已定。"
        }
    } else {
        when (phase) {
            Phase.FIRST -> "This is the first round. Investigate the project, give `understanding` (your understanding, how the code works today and what the change touches), list every direction that needs the user's confirmation in `directions.add`, and ask the first question; if nothing needs confirming, return `done`."
            Phase.ROUND -> "The user answered the last question (see the last entry of `history`). Continue investigating with everything confirmed so far: use `previous` to report whether the direction in `reportPrevious` is settled, re-examine the list, and ask the next question; when no direction needs the user's call, return `done`."
            Phase.CORRECTED -> "The user corrected your understanding (see `corrections`). Investigate again with the new understanding, give `understanding` again and re-examine the list; otherwise as in a normal round."
            Phase.RESULT -> "The user asked for the result now. Merge the original requirement with the `conclusion` of every DECIDED and DEFAULTED direction into the adjusted requirement: concise, at the level of direction, with no explanations, evidence, open items or markers; never write an unsettled direction as settled."
        }
    }

private val ROUND_FORMAT =
    """
    {
      "understanding": "...（首轮与纠正理解后的那一轮必填，其余省略 / required in the first round and after a correction, omit otherwise）",
      "directions": {
        "add": [{"tempId": "new-1", "title": "...", "why": "...", "source": "...", "fallback": "..."}],
        "drop": [{"id": "d3", "reason": "..."}]
      },
      "previous": {"id": "d1", "status": "DECIDED | OPEN", "conclusion": "...", "openPoints": ["..."]},
      "notes": "...",
      "next": {
        "type": "question",
        "directionId": "new-1",
        "question": "...", "reason": "...", "basis": "...",
        "options": [{"id": "a", "label": "...", "recommended": true, "conclusion": "..."}]
      }
    }
    next 也可以是 / next may also be: {"type": "done", "requirement": "..."}
    tempId 以 new- 开头且本次内不重复；options 2～4 个，每个都有 conclusion；reportPrevious 不为 null 时 previous 必填；notes 不超过 $MAX_NOTES_CHARS 字符。
    """.trimIndent()

private const val RESULT_FORMAT = "{\"requirement\": \"...\"}"

private fun ledgerJson(ledger: Ledger): String {
    val state =
        linkedMapOf(
            "requirement" to ledger.requirement,
            "background" to ledger.background,
            "understanding" to ledger.understanding,
            "corrections" to ledger.corrections,
            "directions" to ledger.directions.map {
                linkedMapOf(
                    "id" to it.id, "title" to it.title, "why" to it.why, "source" to it.source,
                    "fallback" to it.fallback, "status" to it.status.name, "userOwned" to it.userOwned,
                    "conclusion" to it.conclusion, "openPoints" to it.openPoints,
                )
            },
            "history" to ledger.history.map {
                linkedMapOf(
                    "directionId" to it.question.directionId,
                    "question" to it.question.text,
                    "options" to it.question.options.map { o -> linkedMapOf("id" to o.id, "label" to o.label, "conclusion" to o.conclusion) },
                    "answer" to when (val a = it.answer) {
                        is Answer.Picked -> "picked:${a.optionId}"
                        is Answer.OwnWords -> "own-words"
                        Answer.Skipped -> "skipped"
                    },
                    "recorded" to it.recorded,
                )
            },
            "notes" to ledger.notes,
            "constraints" to linkedMapOf(
                "mustAsk" to ledger.pinned,
                "frozen" to ledger.directions.filter { it.status == DirectionStatus.DEFAULTED }.map { it.id },
                "reportPrevious" to ledger.lastAsked,
            ),
        )
    return GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create().toJson(state)
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.clarification.ClarificationPromptTest'`
Expected: PASS。若 `frozen` 的断言因 Gson 缩进不同失败，按实际输出调整该断言里的空白（只改空白，不改内容）。

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/clarification/ClarificationModels.kt src/main/kotlin/com/github/izerui/imux/clarification/ClarificationPrompt.kt src/test/kotlin/com/github/izerui/imux/clarification/ClarificationPromptTest.kt
git commit -m "feat(clarification): 添加需求澄清的台账模型与各阶段提示词拼装"
```

---

### Task 2: 协议解析

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/clarification/ClarificationProtocol.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/clarification/ClarificationProtocolTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `Question`、`ChoiceOption`。
- Produces:
  - `internal class ProtocolException(val errors: List<String>) : RuntimeException`
  - `internal data class NewDirection(tempId, title, why, source, fallback)`
  - `internal data class Previous(id: String, decided: Boolean, conclusion: String, openPoints: List<String>)`
  - `internal sealed interface Next { data class Ask(val question: Question); data class Done(val requirement: String) }`
  - `internal data class RoundResponse(understanding: String?, added: List<NewDirection>, dropped: List<String>, previous: Previous?, notes: String, next: Next)`
  - `internal fun parseRoundResponse(raw: String): RoundResponse`（结构错误抛 `ProtocolException`）
  - `internal fun parseRequirementResponse(raw: String): String`

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClarificationProtocolTest {
    private val question =
        """
        {"understanding":"理解","directions":{"add":[{"tempId":"new-1","title":"恢复范围","why":"w","source":"s","fallback":"f"}],
         "drop":[{"id":"d3","reason":"r"}]},
         "previous":{"id":"d1","status":"OPEN","conclusion":"c","openPoints":["p"]},
         "notes":"n",
         "next":{"type":"question","directionId":"new-1","question":"Q","reason":"R","basis":"B",
           "options":[{"id":"a","label":"A","recommended":true,"conclusion":"CA"},{"id":"b","label":"B","recommended":false,"conclusion":"CB"}]}}
        """.trimIndent()

    private fun errorsOf(raw: String): List<String> =
        try {
            parseRoundResponse(raw)
            fail("应抛 ProtocolException"); emptyList()
        } catch (e: ProtocolException) {
            e.errors
        }

    @Test
    fun `完整的提问返回被解析`() {
        val r = parseRoundResponse(question)
        assertEquals("理解", r.understanding)
        assertEquals(listOf(NewDirection("new-1", "恢复范围", "w", "s", "f")), r.added)
        assertEquals(listOf("d3"), r.dropped)
        assertEquals(Previous("d1", decided = false, conclusion = "c", openPoints = listOf("p")), r.previous)
        val ask = r.next as Next.Ask
        assertEquals("new-1", ask.question.directionId)
        assertEquals(listOf("CA", "CB"), ask.question.options.map { it.conclusion })
    }

    @Test
    fun `JSON 前后有说明文字或代码围栏时仍能解析`() {
        val r = parseRoundResponse("好的，结果如下：\n```json\n$question\n```\n")
        assertTrue(r.next is Next.Ask)
    }

    @Test
    fun `done 返回带出调整后的需求`() {
        val r = parseRoundResponse("""{"notes":"","next":{"type":"done","requirement":"调整后的需求"}}""")
        assertEquals(Next.Done("调整后的需求"), r.next)
        assertNull(r.previous)
    }

    @Test
    fun `没有 JSON 时报错`() {
        assertTrue(errorsOf("我觉得没问题").isNotEmpty())
    }

    @Test
    fun `选项少于两个或多于四个都判为错误`() {
        val one = question.replace(""",{"id":"b","label":"B","recommended":false,"conclusion":"CB"}""", "")
        assertTrue(errorsOf(one).any { it.contains("2-4") })
        val five = question.replace(
            """{"id":"b","label":"B","recommended":false,"conclusion":"CB"}""",
            (1..4).joinToString(",") { """{"id":"x$it","label":"L","recommended":false,"conclusion":"C"}""" },
        )
        assertTrue(errorsOf(five).any { it.contains("2-4") })
    }

    @Test
    fun `选项缺少结论时判为错误`() {
        assertTrue(errorsOf(question.replace("\"conclusion\":\"CB\"", "\"conclusion\":\"\"")).any { it.contains("conclusion") })
    }

    @Test
    fun `previous 状态不是 DECIDED 或 OPEN 时判为错误`() {
        assertTrue(errorsOf(question.replace("\"status\":\"OPEN\"", "\"status\":\"PAUSED\"")).any { it.contains("previous") })
    }

    @Test
    fun `生成结果阶段只取 requirement`() {
        assertEquals("需求", parseRequirementResponse("""{"requirement":"需求"}"""))
        try {
            parseRequirementResponse("""{"requirement":"  "}""")
            fail("空 requirement 应报错")
        } catch (_: ProtocolException) {
        }
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.clarification.ClarificationProtocolTest'`
Expected: 编译失败（`parseRoundResponse` 未定义）。

- [ ] **Step 3: 写实现**

```kotlin
package com.github.izerui.imux.clarification

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Agent 的返回不符合协议；[errors] 会附进下一次提示词，所以用英文写给模型看。 */
internal class ProtocolException(val errors: List<String>) : RuntimeException(errors.joinToString("; "))

internal data class NewDirection(
    val tempId: String,
    val title: String,
    val why: String,
    val source: String,
    val fallback: String,
)

internal data class Previous(
    val id: String,
    val decided: Boolean,
    val conclusion: String,
    val openPoints: List<String>,
)

internal sealed interface Next {
    data class Ask(val question: Question) : Next

    data class Done(val requirement: String) : Next
}

internal data class RoundResponse(
    val understanding: String?,
    val added: List<NewDirection>,
    val dropped: List<String>,
    val previous: Previous?,
    val notes: String,
    val next: Next,
)

/** 取第一个 `{` 到最后一个 `}`：模型常在 JSON 外包一层说明文字或代码围栏。 */
private fun jsonObjectIn(raw: String): JsonObject {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) throw ProtocolException(listOf("no JSON object found in the output"))
    return runCatching { JsonParser.parseString(raw.substring(start, end + 1)).asJsonObject }
        .getOrElse { throw ProtocolException(listOf("invalid JSON: ${it.message}")) }
}

private fun JsonObject.str(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()

private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arr(name: String): JsonArray = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

private fun JsonElement.asObjOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

internal fun parseRoundResponse(raw: String): RoundResponse {
    val root = jsonObjectIn(raw)
    val errors = mutableListOf<String>()

    val directions = root.obj("directions")
    val added =
        directions?.arr("add").orEmpty().mapNotNull { element ->
            val o = element.asObjOrNull()
            val fields = listOf("tempId", "title", "why", "source", "fallback").associateWith { o?.str(it).orEmpty() }
            val missing = fields.filterValues { it.isEmpty() }.keys
            if (missing.isNotEmpty()) {
                errors += "directions.add item is missing ${missing.joinToString()}"
                null
            } else {
                NewDirection(fields.getValue("tempId"), fields.getValue("title"), fields.getValue("why"), fields.getValue("source"), fields.getValue("fallback"))
            }
        }
    val dropped = directions?.arr("drop").orEmpty().mapNotNull { it.asObjOrNull()?.str("id")?.takeIf(String::isNotEmpty) }

    val previous =
        root.obj("previous")?.let { p ->
            val status = p.str("status")
            if (status != "DECIDED" && status != "OPEN") errors += "previous.status must be DECIDED or OPEN"
            Previous(
                id = p.str("id").orEmpty(),
                decided = status == "DECIDED",
                conclusion = p.str("conclusion").orEmpty(),
                openPoints = p.arr("openPoints").mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotEmpty) },
            )
        }

    val nextObj = root.obj("next")
    val next: Next? =
        when (nextObj?.str("type")) {
            "done" -> nextObj.str("requirement")?.takeIf(String::isNotEmpty)?.let(Next::Done)
                ?: run { errors += "next.requirement must not be empty"; null }
            "question" -> parseQuestion(nextObj, errors)
            else -> { errors += "next.type must be question or done"; null }
        }

    if (errors.isNotEmpty() || next == null) throw ProtocolException(errors.ifEmpty { listOf("next is missing") })
    return RoundResponse(root.str("understanding")?.takeIf(String::isNotEmpty), added, dropped, previous, root.str("notes").orEmpty(), next)
}

private fun parseQuestion(o: JsonObject, errors: MutableList<String>): Next.Ask? {
    val directionId = o.str("directionId").orEmpty()
    val text = o.str("question").orEmpty()
    if (directionId.isEmpty()) errors += "next.directionId must not be empty"
    if (text.isEmpty()) errors += "next.question must not be empty"
    val options =
        o.arr("options").mapNotNull { it.asObjOrNull() }.map { opt ->
            ChoiceOption(
                id = opt.str("id").orEmpty(),
                label = opt.str("label").orEmpty(),
                recommended = opt.get("recommended")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
                conclusion = opt.str("conclusion").orEmpty(),
            )
        }
    if (options.size !in 2..4) errors += "next.options must have 2-4 items"
    if (options.any { it.id.isEmpty() || it.label.isEmpty() }) errors += "every option needs id and label"
    if (options.any { it.conclusion.isEmpty() }) errors += "every option needs a non-empty conclusion"
    if (options.map { it.id }.toSet().size != options.size) errors += "option ids must be unique"
    return Next.Ask(Question(directionId, text, o.str("reason").orEmpty(), o.str("basis").orEmpty(), options))
}

internal fun parseRequirementResponse(raw: String): String =
    jsonObjectIn(raw).str("requirement")?.takeIf(String::isNotEmpty)
        ?: throw ProtocolException(listOf("requirement must not be empty"))
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.clarification.ClarificationProtocolTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/clarification/ClarificationProtocol.kt src/test/kotlin/com/github/izerui/imux/clarification/ClarificationProtocolTest.kt
git commit -m "feat(clarification): 解析并校验单次 Agent 返回的澄清 JSON"
```

---

### Task 3: 台账：用户操作与合并 Agent 提议

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/clarification/ClarificationLedger.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/clarification/ClarificationLedgerTest.kt`

**Interfaces:**
- Consumes: Task 1 模型；Task 2 的 `RoundResponse`、`Next`、`Previous`、`NewDirection`。
- Produces:
  - `internal fun Ledger.recordAnswer(question: Question, answer: Answer, correction: String?): Ledger`
  - `internal fun Ledger.skip(id: String): Ledger`
  - `internal fun Ledger.pin(id: String): Ledger`
  - `internal fun Ledger.reopen(id: String): Ledger`
  - `internal fun Ledger.addUserDirection(title: String): Pair<Ledger, String>`（返回新方向 ID）
  - `internal sealed interface MergedNext { data class Ask(val question: Question); data class Done(val requirement: String) }`
  - `internal data class MergeOutcome(ledger: Ledger, next: MergedNext?, errors: List<String>, invalidatedByUser: Boolean)`
  - `internal fun Ledger.merge(response: RoundResponse, changedByUser: Set<String>): MergeOutcome`
  - `internal fun composeFallback(ledger: Ledger): String`

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationLedgerTest {
    private fun dir(id: String, status: DirectionStatus = DirectionStatus.OPEN, userOwned: Boolean = false) =
        Direction(id, "title-$id", "why", "src", "fallback-$id", status, userOwned)

    private fun option(id: String) = ChoiceOption(id, "label-$id", recommended = id == "a", conclusion = "conclusion-$id")

    private fun ask(directionId: String) = Next.Ask(Question(directionId, "Q", "R", "B", listOf(option("a"), option("b"))))

    private fun response(
        next: Next,
        added: List<NewDirection> = emptyList(),
        dropped: List<String> = emptyList(),
        previous: Previous? = null,
        notes: String = "",
    ) = RoundResponse(null, added, dropped, previous, notes, next)

    private val base = Ledger("需求", null, directions = listOf(dir("d1"), dir("d2")), nextDirectionNumber = 3)

    @Test
    fun `首轮新增两个方向时问题关联到第二个`() {
        val out = Ledger("需求", null).merge(
            response(
                ask("new-2"),
                added = listOf(NewDirection("new-1", "第一", "w", "s", "f"), NewDirection("new-2", "第二", "w", "s", "f")),
            ),
            emptySet(),
        )
        assertTrue(out.errors.isEmpty())
        val second = out.ledger.directions.single { it.title == "第二" }
        val first = out.ledger.directions.single { it.title == "第一" }
        val asked = (out.next as MergedNext.Ask).question.directionId
        assertEquals(second.id, asked)
        assertNotEquals(first.id, asked)
        assertEquals(DirectionStatus.ACTIVE, second.status)
    }

    @Test
    fun `临时 ID 重复或前缀不对或引用不存在都判为错误`() {
        val dup = base.merge(response(ask("new-1"), added = listOf(NewDirection("new-1", "x", "w", "s", "f"), NewDirection("new-1", "y", "w", "s", "f"))), emptySet())
        assertTrue(dup.errors.any { it.contains("duplicate") })
        val prefix = base.merge(response(ask("d1"), added = listOf(NewDirection("tmp-1", "x", "w", "s", "f"))), emptySet())
        assertTrue(prefix.errors.any { it.contains("new-") })
        val missing = base.merge(response(ask("d9")), emptySet())
        assertTrue(missing.errors.any { it.contains("d9") })
    }

    @Test
    fun `出错时台账保持原样`() {
        val out = base.merge(response(ask("d9"), added = listOf(NewDirection("new-1", "x", "w", "s", "f"))), emptySet())
        assertEquals(base, out.ledger)
        assertNull(out.next)
    }

    @Test
    fun `模型去掉跳过的方向与用户添加的方向都被丢弃`() {
        val ledger = base.copy(directions = listOf(dir("d1", DirectionStatus.DEFAULTED), dir("user-1", userOwned = true), dir("d2")))
        val out = ledger.merge(response(ask("d2"), dropped = listOf("d1", "user-1")), emptySet())
        assertTrue(out.errors.isEmpty())
        assertEquals(DirectionStatus.DEFAULTED, out.ledger.direction("d1")!!.status)
        assertEquals(DirectionStatus.OPEN, out.ledger.direction("user-1")!!.status)
    }

    @Test
    fun `问题针对已跳过的方向时判为错误`() {
        val ledger = base.skip("d1")
        val out = ledger.merge(response(ask("d1")), emptySet())
        assertFalse(out.errors.isEmpty())
        assertFalse(out.invalidatedByUser)
        assertEquals(DirectionStatus.DEFAULTED, out.ledger.direction("d1")!!.status)
    }

    @Test
    fun `调查期间被用户跳过的方向导致问题失效时标为用户造成`() {
        val ledger = base.skip("d1")
        val out = ledger.merge(response(ask("d1")), changedByUser = setOf("d1"))
        assertTrue(out.invalidatedByUser)
        assertEquals(DirectionStatus.DEFAULTED, out.ledger.direction("d1")!!.status)
    }

    @Test
    fun `先问这个之后模型指向别的方向判为错误`() {
        val out = base.pin("d2").merge(response(ask("d1")), emptySet())
        assertTrue(out.errors.any { it.contains("d2") })
    }

    @Test
    fun `先问这个的方向出题后指定解除`() {
        val out = base.pin("d2").merge(response(ask("d2")), emptySet())
        assertTrue(out.errors.isEmpty())
        assertNull(out.ledger.pinned)
    }

    @Test
    fun `选中选项记下的结论与选项显示的一致`() {
        val question = (ask("d1")).question
        val ledger = base.recordAnswer(question, Answer.Picked("b"), correction = null)
        assertEquals("conclusion-b", ledger.history.last().recorded)
        assertEquals("d1", ledger.lastAsked)
    }

    @Test
    fun `其他想法原话照录`() {
        val ledger = base.recordAnswer(ask("d1").question, Answer.OwnWords("  我的原话  "), correction = null)
        assertEquals("我的原话", ledger.history.last().recorded)
    }

    @Test
    fun `跳过时记下默认处理且不要求 previous`() {
        val ledger = base.recordAnswer(ask("d1").question, Answer.Skipped, correction = null)
        assertEquals("fallback-d1", ledger.history.last().recorded)
        assertEquals(DirectionStatus.DEFAULTED, ledger.direction("d1")!!.status)
        assertNull(ledger.lastAsked)
    }

    @Test
    fun `纠正理解随回答记下`() {
        val ledger = base.recordAnswer(ask("d1").question, Answer.Picked("a"), correction = "不是重连是重开")
        assertEquals(listOf("不是重连是重开"), ledger.corrections)
    }

    @Test
    fun `已定方向的结论取用户的回答而不是模型的转述`() {
        val ledger = base.recordAnswer(ask("d1").question, Answer.Picked("a"), null)
        val out = ledger.merge(response(ask("d2"), previous = Previous("d1", decided = true, conclusion = "模型改写的结论", openPoints = emptyList())), emptySet())
        assertTrue(out.errors.isEmpty())
        assertEquals(DirectionStatus.DECIDED, out.ledger.direction("d1")!!.status)
        assertEquals("conclusion-a", out.ledger.direction("d1")!!.conclusion)
    }

    @Test
    fun `未定的方向回到待确认并记下剩余疑点`() {
        val ledger = base.recordAnswer(ask("d1").question, Answer.Picked("a"), null)
        val out = ledger.merge(response(ask("d2"), previous = Previous("d1", decided = false, conclusion = "部分结论", openPoints = listOf("疑点"))), emptySet())
        assertEquals(DirectionStatus.OPEN, out.ledger.direction("d1")!!.status)
        assertEquals(listOf("疑点"), out.ledger.direction("d1")!!.openPoints)
    }

    @Test
    fun `回答后的下一轮缺少 previous 判为错误`() {
        val ledger = base.recordAnswer(ask("d1").question, Answer.Picked("a"), null)
        assertTrue(ledger.merge(response(ask("d2")), emptySet()).errors.any { it.contains("previous") })
    }

    @Test
    fun `调查笔记超长判为错误`() {
        val out = base.merge(response(ask("d1"), notes = "x".repeat(MAX_NOTES_CHARS + 1)), emptySet())
        assertTrue(out.errors.any { it.contains("notes") })
    }

    @Test
    fun `重新打开的方向归用户所有且回到待确认`() {
        val ledger = base.skip("d1").reopen("d1")
        assertEquals(DirectionStatus.OPEN, ledger.direction("d1")!!.status)
        assertTrue(ledger.direction("d1")!!.userOwned)
    }

    @Test
    fun `用户添加的方向编号与模型的不冲突`() {
        val (ledger, id) = base.addUserDirection("我的方向")
        assertEquals("user-1", id)
        assertTrue(ledger.direction("user-1")!!.userOwned)
        assertEquals(2, ledger.nextUserNumber)
    }

    @Test
    fun `兜底结果包含原需求与已定和默认的结论但不含未定的`() {
        val ledger = base.copy(
            directions = listOf(
                dir("d1", DirectionStatus.DECIDED).copy(conclusion = "已定结论"),
                dir("d2", DirectionStatus.DEFAULTED).copy(conclusion = "默认结论"),
                dir("d3", DirectionStatus.OPEN).copy(conclusion = "未定结论"),
            ),
        )
        val text = composeFallback(ledger)
        assertTrue(text.startsWith("需求"))
        assertTrue(text.contains("已定结论"))
        assertTrue(text.contains("默认结论"))
        assertFalse(text.contains("未定结论"))
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.clarification.ClarificationLedgerTest'`
Expected: 编译失败（`merge`、`skip` 等未定义）。

- [ ] **Step 3: 写实现**

```kotlin
package com.github.izerui.imux.clarification

import com.github.izerui.imux.clarification.DirectionStatus.ACTIVE
import com.github.izerui.imux.clarification.DirectionStatus.DECIDED
import com.github.izerui.imux.clarification.DirectionStatus.DEFAULTED
import com.github.izerui.imux.clarification.DirectionStatus.DROPPED
import com.github.izerui.imux.clarification.DirectionStatus.OPEN

// ---- 用户操作：只由用户触发，Agent 的返回永远不能撤销它们 ----

internal fun Ledger.recordAnswer(question: Question, answer: Answer, correction: String?): Ledger {
    val direction = direction(question.directionId) ?: return this
    val recorded =
        when (answer) {
            is Answer.Picked -> question.options.firstOrNull { it.id == answer.optionId }?.conclusion ?: return this
            is Answer.OwnWords -> answer.text.trim()
            Answer.Skipped -> direction.fallback
        }
    val skipped = answer == Answer.Skipped
    val updated = if (skipped) direction.copy(status = DEFAULTED, conclusion = recorded) else direction.copy(status = ACTIVE)
    return replace(updated).copy(
        history = history + AnswerRecord(question, answer, recorded),
        corrections = corrections + listOfNotNull(correction?.trim()?.takeIf(String::isNotEmpty)),
        lastAsked = if (skipped) null else question.directionId,
    )
}

internal fun Ledger.skip(id: String): Ledger {
    val d = direction(id) ?: return this
    if (d.status == DEFAULTED) return this
    return replace(d.copy(status = DEFAULTED, conclusion = d.fallback.ifBlank { null }))
        .copy(pinned = pinned.takeUnless { it == id }, lastAsked = lastAsked.takeUnless { it == id })
}

internal fun Ledger.pin(id: String): Ledger =
    if (direction(id)?.status == OPEN) copy(pinned = id) else this

internal fun Ledger.reopen(id: String): Ledger {
    val d = direction(id) ?: return this
    if (d.status == OPEN || d.status == ACTIVE) return this
    return replace(d.copy(status = OPEN, userOwned = true, conclusion = null, openPoints = emptyList()))
}

internal fun Ledger.addUserDirection(title: String): Pair<Ledger, String> {
    val id = "user-$nextUserNumber"
    val direction = Direction(id, title.trim(), why = "", source = "user", fallback = "", userOwned = true)
    return copy(directions = directions + direction, nextUserNumber = nextUserNumber + 1) to id
}

// ---- 合并 Agent 的提议 ----

internal sealed interface MergedNext {
    data class Ask(val question: Question) : MergedNext

    data class Done(val requirement: String) : MergedNext
}

/**
 * [errors] 非空时 [ledger] 就是合并前的台账，一处都不改。
 * [invalidatedByUser]：问题之所以无效，是因为调查期间用户改动了它针对的方向或「先问这个」——
 * 那不是模型的错，调用方应直接用新台账重新调用，而不是把错误附给模型。
 */
internal data class MergeOutcome(
    val ledger: Ledger,
    val next: MergedNext?,
    val errors: List<String>,
    val invalidatedByUser: Boolean,
)

internal fun Ledger.merge(response: RoundResponse, changedByUser: Set<String>): MergeOutcome {
    val errors = mutableListOf<String>()

    // 1. 临时 ID → 稳定 ID
    val idMap = linkedMapOf<String, String>()
    var number = nextDirectionNumber
    for (added in response.added) {
        when {
            !added.tempId.startsWith("new-") -> errors += "tempId ${added.tempId} must start with new-"
            added.tempId in idMap -> errors += "tempId ${added.tempId} is duplicate"
            else -> idMap[added.tempId] = "d${number++}"
        }
    }
    var merged =
        copy(
            directions = directions + response.added.mapNotNull { a -> idMap[a.tempId]?.let { Direction(it, a.title, a.why, a.source, a.fallback) } },
            nextDirectionNumber = number,
        )
    fun resolve(ref: String): String? = idMap[ref] ?: ref.takeIf { merged.direction(it) != null }

    // 2. 去掉：用户的方向（跳过的、添加的、重新打开的）一律不动
    for (id in response.dropped) {
        val d = merged.direction(id)
        when {
            d == null -> errors += "dropped direction $id does not exist"
            d.userOwned || d.status == DEFAULTED -> Unit
            else -> merged = merged.replace(d.copy(status = DROPPED))
        }
    }

    // 3. 交代上一轮的方向；已定时结论取用户的回答，不取模型的转述
    lastAsked?.let { asked ->
        val p = response.previous
        val d = merged.direction(asked)
        if (p == null || p.id != asked) {
            errors += "previous must report direction $asked"
        } else if (d != null && d.status == ACTIVE) {
            merged =
                merged.replace(
                    if (p.decided) {
                        d.copy(status = DECIDED, conclusion = merged.recordedFor(asked), openPoints = emptyList())
                    } else {
                        d.copy(status = OPEN, conclusion = p.conclusion.ifBlank { null }, openPoints = p.openPoints)
                    },
                )
        }
    }

    if (response.notes.length > MAX_NOTES_CHARS) errors += "notes must not exceed $MAX_NOTES_CHARS characters"
    merged = merged.copy(notes = response.notes, understanding = response.understanding ?: merged.understanding)

    // 4. 下一步
    var invalidatedByUser = false
    val next: MergedNext? =
        when (val n = response.next) {
            is Next.Done -> MergedNext.Done(n.requirement)
            is Next.Ask -> {
                val id = resolve(n.question.directionId)
                val d = id?.let(merged::direction)
                val problems = mutableListOf<String>()
                if (id == null || d == null) problems += "question targets unknown direction ${n.question.directionId}"
                else if (d.status != OPEN) problems += "question targets direction $id which is ${d.status}, not OPEN"
                val pin = merged.pinned
                if (pin != null && id != pin) problems += "the user asked to discuss $pin next"
                if (problems.isNotEmpty()) {
                    invalidatedByUser = (id != null && id in changedByUser) || (pin != null && pin in changedByUser)
                    errors += problems
                    null
                } else {
                    merged = merged.replace(d!!.copy(status = ACTIVE)).copy(pinned = merged.pinned.takeUnless { it == id })
                    MergedNext.Ask(n.question.copy(directionId = id!!))
                }
            }
        }

    if (errors.isNotEmpty()) return MergeOutcome(this, null, errors, invalidatedByUser && errors.size == errors.count { it.startsWith("question targets") || it.startsWith("the user asked") })
    return MergeOutcome(merged.copy(lastAsked = null), next, emptyList(), invalidatedByUser = false)
}

private fun Ledger.recordedFor(id: String): String =
    history.filter { it.question.directionId == id && it.answer != Answer.Skipped }.joinToString("；") { it.recorded }

/** 失败页「生成结果」不调用 Agent：原需求后面按顺序列出已定与默认的结论。 */
internal fun composeFallback(ledger: Ledger): String =
    buildString {
        append(ledger.requirement.trim())
        val settled = ledger.directions.filter { it.status == DECIDED || it.status == DEFAULTED }.mapNotNull { it.conclusion?.takeIf(String::isNotBlank) }
        if (settled.isNotEmpty()) {
            append("\n\n")
            settled.forEach { append("- ").append(it).append('\n') }
        }
    }.trimEnd()
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.clarification.ClarificationLedgerTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/clarification/ClarificationLedger.kt src/test/kotlin/com/github/izerui/imux/clarification/ClarificationLedgerTest.kt
git commit -m "feat(clarification): 台账合并 Agent 提议，用户操作优先并按稳定 ID 引用方向"
```

---

### Task 4: 状态机

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/clarification/ClarificationFlow.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/clarification/ClarificationFlowTest.kt`

**Interfaces:**
- Consumes: Task 1～3。
- Produces:
  - `internal sealed interface ClarificationState { Input; Investigating(progress: String?, startedAt: Long); Asking(question: Question); Result(requirement: String); Sending(requirement: String, waitingForTurn: Boolean); Failed(reason: FailureReason, detail: String, raw: String?) }`
  - `internal enum class FailureReason { CANCELLED, TIMEOUT, AGENT_ERROR, PROTOCOL }`
  - `internal enum class PendingWork { ROUND, RESULT }`
  - `internal class ClarificationFlow(isChinese: Boolean, guidance: String, runAgent: (String) -> String, clock: () -> Long = System::currentTimeMillis)`，成员：
    `val state`、`val ledger`、`addListener(listener: () -> Unit)`、`removeListener(listener: () -> Unit)`、
    `begin(requirement: String, background: String?)`、`advance()`（阻塞，在 IO 线程调用）、`answer(answer: Answer, correction: String?)`、
    `skip(id: String): Boolean`（返回 true 表示跳过的是当前问题，调用方需要再跑一轮）、`pin(id)`、`reopen(id)`、`addDirection(title: String)`、
    `beginResult()`、`generateResult()`（阻塞）、`fallbackResult()`、`continueClarifying()`、`retry(): PendingWork?`、
    `attachBackground(background: String?)`、`progress(text: String)`、`cancelled()`、`fail(reason, detail, raw)`、`editResult(text: String)`、
    `sending(waitingForTurn: Boolean)`、`sendCancelled()`

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.github.izerui.imux.clarification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ClarificationFlowTest {
    private val prompts = CopyOnWriteArrayList<String>()

    private fun flow(vararg outputs: String): ClarificationFlow {
        val queue = ArrayDeque(outputs.toList())
        return ClarificationFlow(isChinese = false, guidance = "GUIDE", runAgent = { prompt -> prompts += prompt; queue.removeFirst() })
    }

    private val first =
        """{"understanding":"u","directions":{"add":[{"tempId":"new-1","title":"T1","why":"w","source":"s","fallback":"F1"},
           {"tempId":"new-2","title":"T2","why":"w","source":"s","fallback":"F2"}]},"notes":"n",
           "next":{"type":"question","directionId":"new-1","question":"Q1","reason":"r","basis":"b",
             "options":[{"id":"a","label":"A","recommended":true,"conclusion":"CA"},{"id":"b","label":"B","recommended":false,"conclusion":"CB"}]}}"""

    private fun askD2(previousStatus: String = "DECIDED") =
        """{"previous":{"id":"d1","status":"$previousStatus","conclusion":"","openPoints":[]},"notes":"n",
           "next":{"type":"question","directionId":"d2","question":"Q2","reason":"r","basis":"b",
             "options":[{"id":"a","label":"A","recommended":true,"conclusion":"C2A"},{"id":"b","label":"B","recommended":false,"conclusion":"C2B"}]}}"""

    private fun done(previous: String = "d2") =
        """{"previous":{"id":"$previous","status":"DECIDED","conclusion":"","openPoints":[]},"notes":"n","next":{"type":"done","requirement":"ADJUSTED"}}"""

    @Test
    fun `首轮没有需要确认的方向时直接给出结果且只调用一次`() {
        val f = flow("""{"understanding":"u","notes":"","next":{"type":"done","requirement":"ADJUSTED"}}""")
        f.begin("REQ", null)
        f.advance()
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
        assertEquals(1, prompts.size)
    }

    @Test
    fun `每轮提示词都带上之前全部已确认的内容`() {
        val f = flow(first, askD2(), done())
        f.begin("REQ", "BACKGROUND")
        f.advance()
        f.answer(Answer.Picked("b"), correction = "FIX")
        f.advance()
        f.answer(Answer.OwnWords("MY WORDS"), correction = null)
        f.advance()
        val last = prompts.last()
        assertTrue(last.contains("REQ"))
        assertTrue(last.contains("BACKGROUND"))
        assertTrue(last.contains("CB"))
        assertTrue(last.contains("FIX"))
        assertTrue(last.contains("MY WORDS"))
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `协议错误附带错误重试一次后成功`() {
        val f = flow("not json", first)
        f.begin("REQ", null)
        f.advance()
        assertTrue(f.state is ClarificationState.Asking)
        assertEquals(2, prompts.size)
        assertTrue(prompts[1].contains("no JSON object"))
    }

    @Test
    fun `重试后仍出错进入失败状态且不再调用`() {
        val f = flow("not json", "still not json")
        f.begin("REQ", null)
        f.advance()
        val state = f.state as ClarificationState.Failed
        assertEquals(FailureReason.PROTOCOL, state.reason)
        assertEquals("still not json", state.raw)
        assertEquals(2, prompts.size)
    }

    @Test
    fun `调查期间跳过的方向使问题失效时用新台账重新调用且跳过保持`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outputs = ArrayDeque(listOf(askD2(), """{"previous":{"id":"d1","status":"DECIDED","conclusion":"","openPoints":[]},"notes":"n","next":{"type":"done","requirement":"ADJUSTED"}}"""))
        var calls = 0
        lateinit var f: ClarificationFlow
        f = ClarificationFlow(false, "G", runAgent = { prompt ->
            prompts += prompt
            calls++
            if (calls == 2) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            if (calls == 1) first else outputs.removeFirst()
        })
        f.begin("REQ", null)
        f.advance()
        f.answer(Answer.Picked("a"), null)
        val worker = thread { f.advance() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(!f.skip("d2"))
        release.countDown()
        worker.join(5_000)
        assertEquals(3, calls)
        assertEquals(DirectionStatus.DEFAULTED, f.ledger.direction("d2")!!.status)
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `跳过当前问题等同于回答跳过`() {
        val f = flow(first)
        f.begin("REQ", null)
        f.advance()
        assertTrue(f.skip("d1"))
        assertEquals("F1", f.ledger.history.last().recorded)
        assertTrue(f.state is ClarificationState.Investigating)
    }

    @Test
    fun `取消后晚到的结果被丢弃`() {
        val release = CountDownLatch(1)
        val f = ClarificationFlow(false, "G", runAgent = { release.await(5, TimeUnit.SECONDS); first })
        f.begin("REQ", null)
        val worker = thread { f.advance() }
        f.cancelled()
        release.countDown()
        worker.join(5_000)
        assertEquals(FailureReason.CANCELLED, (f.state as ClarificationState.Failed).reason)
        assertTrue(f.ledger.directions.isEmpty())
    }

    @Test
    fun `生成结果阶段取 requirement`() {
        val f = flow(first, """{"requirement":"FINAL"}""")
        f.begin("REQ", null)
        f.advance()
        f.beginResult()
        f.generateResult()
        assertEquals(ClarificationState.Result("FINAL"), f.state)
        assertTrue(prompts.last().contains("{\"requirement\": \"...\"}"))
    }

    @Test
    fun `失败后重试的是失败的那一步`() {
        val f = flow(first, "x", "y")
        f.begin("REQ", null)
        f.advance()
        f.beginResult()
        f.generateResult()
        assertEquals(PendingWork.RESULT, f.retry())
        assertTrue(f.state is ClarificationState.Investigating)
    }

    @Test
    fun `发送中切换等待原因且取消后回到结果页文本保留`() {
        val f = flow("""{"notes":"","next":{"type":"done","requirement":"ADJUSTED"}}""")
        f.begin("REQ", null)
        f.advance()
        f.sending(waitingForTurn = true)
        assertEquals(ClarificationState.Sending("ADJUSTED", waitingForTurn = true), f.state)
        f.sending(waitingForTurn = false)
        assertEquals(ClarificationState.Sending("ADJUSTED", waitingForTurn = false), f.state)
        f.sendCancelled()
        assertEquals(ClarificationState.Result("ADJUSTED"), f.state)
    }

    @Test
    fun `背景快照补进台账后进入提示词`() {
        val f = flow(first)
        f.begin("REQ", null)
        f.attachBackground("LATE BACKGROUND")
        f.advance()
        assertTrue(prompts.single().contains("LATE BACKGROUND"))
    }

    @Test
    fun `兜底结果不调用 Agent`() {
        val f = flow(first)
        f.begin("REQ", null)
        f.advance()
        f.fallbackResult()
        assertEquals(ClarificationState.Result("REQ"), f.state)
        assertEquals(1, prompts.size)
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.clarification.ClarificationFlowTest'`
Expected: 编译失败（`ClarificationFlow` 未定义）。

- [ ] **Step 3: 写实现**

```kotlin
package com.github.izerui.imux.clarification

import java.util.concurrent.CopyOnWriteArrayList

internal sealed interface ClarificationState {
    data object Input : ClarificationState

    data class Investigating(val progress: String?, val startedAt: Long) : ClarificationState

    data class Asking(val question: Question) : ClarificationState

    data class Result(val requirement: String) : ClarificationState

    /** 正在发送：[waitingForTurn] 为 true 时在等主会话这一轮结束，否则在等终端可以接收。 */
    data class Sending(val requirement: String, val waitingForTurn: Boolean) : ClarificationState

    data class Failed(val reason: FailureReason, val detail: String, val raw: String?) : ClarificationState
}

internal enum class FailureReason { CANCELLED, TIMEOUT, AGENT_ERROR, PROTOCOL }

internal enum class PendingWork { ROUND, RESULT }

/**
 * 一个会话的澄清流程。状态读写都在 [lock] 内；[advance] 与 [generateResult] 阻塞地调用 Agent，
 * 只在 IO 线程调用，调用期间用户仍可跳过、先问这个、添加或重新打开方向。
 *
 * [generation] 在每次开始调查、取消时递增：Agent 返回时若它已变，说明这一轮已被取消或被新一轮取代，结果丢弃。
 */
internal class ClarificationFlow(
    private val isChinese: Boolean,
    private val guidance: String,
    private val runAgent: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val changedDuringRun = mutableSetOf<String>()
    private var phase = Phase.FIRST
    private var pending = PendingWork.ROUND
    private var generation = 0L

    @Volatile var ledger: Ledger = Ledger("", null)
        private set

    @Volatile var state: ClarificationState = ClarificationState.Input
        private set

    fun addListener(listener: () -> Unit) { listeners += listener }

    fun removeListener(listener: () -> Unit) { listeners -= listener }

    private fun changed() = listeners.forEach { it() }

    private fun investigate(work: PendingWork): Long {
        generation++
        pending = work
        state = ClarificationState.Investigating(null, clock())
        return generation
    }

    fun begin(requirement: String, background: String?) {
        synchronized(lock) {
            ledger = Ledger(requirement.trim(), background?.take(MAX_BACKGROUND_CHARS)?.ifBlank { null })
            phase = Phase.FIRST
            investigate(PendingWork.ROUND)
        }
        changed()
    }

    /** 背景快照在 IO 线程读取，读完后、调用 Agent 之前补进台账。 */
    fun attachBackground(background: String?) {
        synchronized(lock) { ledger = ledger.copy(background = background?.take(MAX_BACKGROUND_CHARS)?.ifBlank { null }) }
    }

    fun answer(answer: Answer, correction: String?) {
        synchronized(lock) {
            val asking = state as? ClarificationState.Asking ?: return
            ledger = ledger.recordAnswer(asking.question, answer, correction)
            phase = if (correction.isNullOrBlank()) Phase.ROUND else Phase.CORRECTED
            investigate(PendingWork.ROUND)
        }
        changed()
    }

    /** 返回 true 表示跳过的正是当前问题，调用方需要再跑一轮。 */
    fun skip(id: String): Boolean {
        val current = synchronized(lock) { (state as? ClarificationState.Asking)?.question?.takeIf { it.directionId == id } }
        if (current != null) {
            answer(Answer.Skipped, null)
            return true
        }
        userEdit(id) { it.skip(id) }
        return false
    }

    fun pin(id: String) = userEdit(id) { it.pin(id) }

    fun reopen(id: String) = userEdit(id) { it.reopen(id) }

    fun addDirection(title: String) {
        if (title.isBlank()) return
        synchronized(lock) {
            val (updated, id) = ledger.addUserDirection(title)
            ledger = updated
            changedDuringRun += id
        }
        changed()
    }

    private fun userEdit(id: String, edit: (Ledger) -> Ledger) {
        synchronized(lock) {
            ledger = edit(ledger)
            changedDuringRun += id
        }
        changed()
    }

    /** 一轮调查：调用 Agent、合并；协议错误或问题因用户操作失效时重新调用一次。 */
    fun advance() {
        var feedback = emptyList<String>()
        var retried = false
        while (true) {
            val (prompt, runId) =
                synchronized(lock) {
                    // 取消可能抢在这里之前：此时已不在调查中，不能再开一轮
                    if (state !is ClarificationState.Investigating) return
                    changedDuringRun.clear()
                    clarificationPrompt(phase, ledger, isChinese, guidance, feedback) to generation
                }
            val raw = runAgent(prompt)
            val again: List<String>? =
                synchronized(lock) {
                    if (runId != generation) return
                    val outcome =
                        try {
                            ledger.merge(parseRoundResponse(raw), changedDuringRun.toSet())
                        } catch (e: ProtocolException) {
                            MergeOutcome(ledger, null, e.errors, invalidatedByUser = false)
                        }
                    when {
                        outcome.errors.isEmpty() -> {
                            ledger = outcome.ledger
                            phase = Phase.ROUND
                            state =
                                when (val next = outcome.next!!) {
                                    is MergedNext.Ask -> ClarificationState.Asking(next.question)
                                    is MergedNext.Done -> ClarificationState.Result(next.requirement)
                                }
                            null
                        }
                        retried -> {
                            state = ClarificationState.Failed(FailureReason.PROTOCOL, outcome.errors.joinToString("\n"), raw)
                            null
                        }
                        outcome.invalidatedByUser -> emptyList()
                        else -> outcome.errors
                    }
                }
            if (again == null) {
                changed()
                return
            }
            feedback = again
            retried = true
        }
    }

    fun beginResult() {
        synchronized(lock) { investigate(PendingWork.RESULT) }
        changed()
    }

    fun generateResult() {
        var feedback = emptyList<String>()
        repeat(2) { attempt ->
            val (prompt, runId) =
                synchronized(lock) {
                    if (state !is ClarificationState.Investigating) return
                    clarificationPrompt(Phase.RESULT, ledger, isChinese, guidance, feedback) to generation
                }
            val raw = runAgent(prompt)
            synchronized(lock) {
                if (runId != generation) return
                try {
                    state = ClarificationState.Result(parseRequirementResponse(raw))
                } catch (e: ProtocolException) {
                    if (attempt == 1) state = ClarificationState.Failed(FailureReason.PROTOCOL, e.errors.joinToString("\n"), raw)
                    feedback = e.errors
                }
            }
            if (state !is ClarificationState.Investigating) {
                changed()
                return
            }
        }
    }

    fun fallbackResult() {
        synchronized(lock) {
            generation++
            state = ClarificationState.Result(composeFallback(ledger))
        }
        changed()
    }

    fun continueClarifying() {
        synchronized(lock) {
            if (state !is ClarificationState.Result) return
            phase = Phase.ROUND
            investigate(PendingWork.ROUND)
        }
        changed()
    }

    /** 从失败状态重试失败的那一步；不在失败状态返回 null。 */
    fun retry(): PendingWork? {
        val work =
            synchronized(lock) {
                if (state !is ClarificationState.Failed) return null
                pending.also { investigate(it) }
            }
        changed()
        return work
    }

    fun progress(text: String) {
        synchronized(lock) {
            val current = state as? ClarificationState.Investigating ?: return
            state = current.copy(progress = text)
        }
        changed()
    }

    fun cancelled() = fail(FailureReason.CANCELLED, "", null, force = true)

    fun fail(reason: FailureReason, detail: String, raw: String?) = fail(reason, detail, raw, force = false)

    private fun fail(reason: FailureReason, detail: String, raw: String?, force: Boolean) {
        synchronized(lock) {
            if (!force && state !is ClarificationState.Investigating) return
            generation++
            state = ClarificationState.Failed(reason, detail, raw)
        }
        changed()
    }

    fun editResult(text: String) {
        synchronized(lock) { if (state is ClarificationState.Result) state = ClarificationState.Result(text) }
    }

    /** 从结果页进入发送，或在发送中切换"等这一轮结束 / 等终端接收"。状态不变时不通知。 */
    fun sending(waitingForTurn: Boolean) {
        val changedNow =
            synchronized(lock) {
                val requirement =
                    when (val s = state) {
                        is ClarificationState.Result -> s.requirement
                        is ClarificationState.Sending -> if (s.waitingForTurn == waitingForTurn) return else s.requirement
                        else -> return
                    }
                state = ClarificationState.Sending(requirement, waitingForTurn)
                true
            }
        if (changedNow) changed()
    }

    /** 取消发送或发送失败：回到结果页，文本保留。 */
    fun sendCancelled() {
        synchronized(lock) { (state as? ClarificationState.Sending)?.let { state = ClarificationState.Result(it.requirement) } }
        changed()
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.clarification.ClarificationFlowTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/clarification/ClarificationFlow.kt src/test/kotlin/com/github/izerui/imux/clarification/ClarificationFlowTest.kt
git commit -m "feat(clarification): 澄清状态机，支持逐轮调查、重试一次、取消与生成结果"
```

---

### Task 5: 抽出会话最近对话的读取

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt:539-556`（`collectLatestConversation`）与 `:704-706`（三个常量）
- Test: `src/test/kotlin/com/github/izerui/imux/peer/PeerConversationTest.kt`

**Interfaces:**
- Produces: `internal fun latestConversationOf(session: AgentSession, sentFeedback: List<String>, maxChars: Int): String`（文件不存在返回空串）

- [ ] **Step 1: 写失败的测试**（加在 `PeerConversationTest` 末尾，复用其中的 `claudeSession`、`user`、`assistant` 辅助函数）

```kotlin
    @Test
    fun `从会话文件读出最近对话并受字数上限约束`() {
        val session = claudeSession(user("最早的需求"), assistant("最早的回复"), user("最新的需求"))
        val full = latestConversationOf(session, emptyList(), maxChars = 1_000)
        assertTrue(full.contains("最新的需求"))
        assertTrue(full.contains("最早的需求"))
        val short = latestConversationOf(session, emptyList(), maxChars = 12)
        assertTrue(short.contains("最新的需求"))
        assertFalse(short.contains("最早的需求"))
    }

    @Test
    fun `会话文件不存在时最近对话为空`() {
        val session = AgentSession("m", "m", AgentType.CLAUDE, Instant.EPOCH, Instant.EPOCH, Path.of("build", "missing-clarify.jsonl"))
        assertEquals("", latestConversationOf(session, emptyList(), maxChars = 100))
    }
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.peer.PeerConversationTest'`
Expected: 编译失败（`latestConversationOf` 未定义）。

- [ ] **Step 3: 实现**

在 `PeerCoordinator.kt` 顶层（`latestConversation` 函数之后）加：

```kotlin
/** 读会话文件末尾的最近对话；结对编程与需求澄清共用。文件不存在返回空串。 */
internal fun latestConversationOf(
    session: AgentSession,
    sentFeedback: List<String>,
    maxChars: Int,
): String {
    if (!Files.isRegularFile(session.filePath)) return ""
    return scanTail(
        session.filePath,
        initialTailBytes = CONVERSATION_TAIL_BYTES,
        maxTailBytes = CONVERSATION_TAIL_BYTES,
    ) { lines ->
        latestConversation(
            lines.mapNotNull {
                transcriptMessage(it, session.agentType, MAX_TRANSCRIPT_MESSAGE_LENGTH, includeToolContent = true)
                    ?.let { message -> markSentFeedback(message, sentFeedback) }
            },
            maxChars,
        )
    }.orEmpty()
}

internal const val CONVERSATION_TAIL_BYTES = 32L * 1024 * 1024
internal const val MAX_TRANSCRIPT_MESSAGE_LENGTH = 100_000
```

把 `collectLatestConversation` 改为委托：

```kotlin
    private fun collectLatestConversation(sessionKey: String, maxConversationChars: Int = MAX_CONVERSATION_LENGTH): String {
        val session = model.sessionOf(sessionKey) ?: return ""
        return latestConversationOf(session, sentFeedback[sessionKey]?.toList().orEmpty(), maxConversationChars)
    }
```

删掉 companion 里的 `MAX_MESSAGE_LENGTH`、`CONVERSATION_TAIL_BYTES`（改用顶层常量）；先 `grep -n "MAX_MESSAGE_LENGTH\|CONVERSATION_TAIL_BYTES" src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt`，其余引用一并改成 `MAX_TRANSCRIPT_MESSAGE_LENGTH` / 顶层 `CONVERSATION_TAIL_BYTES`。文件顶部补 `import com.github.izerui.imux.model.AgentSession`（若尚未导入）。

- [ ] **Step 4: 运行 peer 包全部测试，确认通过**

Run: `./gradlew test --tests 'com.github.izerui.imux.peer.*'`
Expected: PASS（包括 `PeerCoordinatorSourceTest` 等既有用例）

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt src/test/kotlin/com/github/izerui/imux/peer/PeerConversationTest.kt
git commit -m "refactor(peer): 抽出会话最近对话读取函数，供需求澄清复用"
```

---

### Task 6: 项目级服务

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/clarification/ClarificationService.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/clarification/ClarificationServiceSourceTest.kt`

**Interfaces:**
- Consumes: Task 4 `ClarificationFlow`；Task 5 `latestConversationOf`；`oneShotAgentInvocation`、`runOneShotAgent`、`runTracked`、`cleanupProcesses`、`UnexitedProcesses`、`OneShotAgentTimeoutException`（`agent` 包）；`overviewProgressText`（`overview/TrailViews.kt`）；`trySendPeerFeedback`（`peer` 包）；`TerminalHost.terminalViewOf`、`TerminalHost.addSessionKeyMigratedListener`；`SessionMonitor.getInstance(project).model.sessionOf`；`resolveShell`。
- Produces（Task 7、8 使用）：
  - `class ClarificationService(project: Project, scope: CoroutineScope) : Disposable`，`companion fun getInstance(project): ClarificationService`
  - `internal fun open(sessionKey: String, tabId: String, agentType: AgentType, title: String)`
  - `internal fun flowOf(sessionKey: String): ClarificationFlow?`
  - `internal fun hasBackground(sessionKey: String): Boolean`
  - `internal fun start(sessionKey: String, requirement: String, includeBackground: Boolean)`
  - `internal fun answer(sessionKey: String, answer: Answer, correction: String?)`
  - `internal fun skip(sessionKey: String, id: String)`、`pin(...)`、`reopen(...)`、`addDirection(sessionKey: String, title: String)`
  - `internal fun finish(sessionKey: String)`、`fallback(sessionKey: String)`、`continueClarifying(sessionKey: String)`、`retry(sessionKey: String)`、`cancel(sessionKey: String)`
  - `internal fun send(sessionKey: String, text: String)`、`internal fun cancelSend(sessionKey: String)`
  - `internal fun dialogClosed(sessionKey: String)`
  - `internal fun releaseOwner(tabId: String)`
  - `internal fun mainSessionBusy(running: Set<String>, waiting: Set<String>, sessionKey: String): Boolean`（顶层纯函数）

- [ ] **Step 1: 写失败的测试**（服务依赖 IDE，按项目惯例对接线做源码断言；发送时机的判定抽成纯函数单测）

```kotlin
package com.github.izerui.imux.clarification

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationServiceSourceTest {
    private val source = SourceCode("src/main/kotlin/com/github/izerui/imux/clarification/ClarificationService.kt")

    @Test
    fun `单次调用沿用轨迹的调用方式与超时`() {
        assertTrue(source.normalized.contains("oneShotAgentInvocation("))
        assertTrue(source.normalized.contains("runOneShotAgent("))
        assertTrue(source.normalized.contains("AGENT_TIMEOUT_SECONDS = 600L"))
    }

    @Test
    fun `发送复用结对编程的括号粘贴加回车`() {
        assertTrue(source.normalized.contains("trySendPeerFeedback("))
        assertFalse(source.normalized.contains("tryStagePeerFeedback("))
    }

    @Test
    fun `发送时机读取运行集合与 Claude 等待确认状态`() {
        val busy = source.bodyAfter("private fun isMainSessionBusy(", '{')
        assertTrue(busy.contains("runningIds"))
        assertTrue(busy.contains("isWaiting"))
    }

    @Test
    fun `背景快照在后台线程读取`() {
        val start = source.bodyAfter("internal fun start(", '{')
        val launch = start.indexOf("launch(")
        val read = start.indexOf("latestConversationOf(")
        assertTrue("背景快照必须在 launch 的 IO 任务里读取", launch >= 0 && read > launch)
    }

    @Test
    fun `项目关闭时服务关闭打开的窗口`() {
        val dispose = source.bodyAfter("override fun dispose()", '{')
        assertTrue(dispose.contains(".close("))
    }

    @Test
    fun `会话 key 迁移时移动状态而不取消在途一轮`() {
        val migrate = source.normalized.substringAfter("private fun migrate(").substringBefore("override fun dispose")
        assertTrue(migrate.contains("flows.remove(from)"))
        assertFalse(migrate.contains(".cancel("))
    }
}
```

再新建 `src/test/kotlin/com/github/izerui/imux/clarification/MainSessionBusyTest.kt`：

```kotlin
package com.github.izerui.imux.clarification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSessionBusyTest {
    @Test
    fun `执行中与等待确认都算忙而空闲不算`() {
        assertTrue(mainSessionBusy(running = setOf("s1"), waiting = emptySet(), sessionKey = "s1"))
        assertTrue(mainSessionBusy(running = emptySet(), waiting = setOf("s1"), sessionKey = "s1"))
        assertFalse(mainSessionBusy(running = setOf("s2"), waiting = setOf("s3"), sessionKey = "s1"))
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.clarification.ClarificationServiceSourceTest' --tests 'com.github.izerui.imux.clarification.MainSessionBusyTest'`
Expected: FAIL（文件不存在）。

- [ ] **Step 3: 写实现**

```kotlin
package com.github.izerui.imux.clarification

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.agent.AgentMcpConfig
import com.github.izerui.imux.agent.OneShotAgentTimeoutException
import com.github.izerui.imux.agent.TrackedProcess
import com.github.izerui.imux.agent.UnexitedProcesses
import com.github.izerui.imux.agent.cleanupProcesses
import com.github.izerui.imux.agent.oneShotAgentInvocation
import com.github.izerui.imux.agent.runOneShotAgent
import com.github.izerui.imux.agent.runTracked
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.monitor.SessionMonitor
import com.github.izerui.imux.overview.overviewProgressText
import com.github.izerui.imux.peer.latestConversationOf
import com.github.izerui.imux.peer.trySendPeerFeedback
import com.github.izerui.imux.settings.PluginLanguage
import com.github.izerui.imux.terminal.TerminalHost
import com.github.izerui.imux.terminal.resolveShell
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.jetbrains.plugins.terminal.settings.TerminalLocalOptions
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * 按会话保存需求澄清流程，负责调用单次 Agent、取消、会话 key 迁移、关闭清理与发送。
 *
 * 任务挂在服务的作用域上：关闭窗口不打断在途一轮，重新打开时接着显示。
 * 关闭清理按标签页身份（tabId），与轨迹相同：`/clear`、`/new` 迁移后旧 key 已不在。
 */
@Service(Service.Level.PROJECT)
class ClarificationService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {
    private val flows = ConcurrentHashMap<String, ClarificationFlow>()
    private val owners = ConcurrentHashMap<String, String>()
    private val agentTypes = ConcurrentHashMap<String, AgentType>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val dialogs = ConcurrentHashMap<String, ClarificationDialog>()
    private val unexited = UnexitedProcesses()

    init {
        TerminalHost.getInstance(project).addSessionKeyMigratedListener(this) { from, to -> migrate(from, to) }
    }

    internal fun flowOf(sessionKey: String): ClarificationFlow? = flows[sessionKey]

    internal fun currentKeyOf(flow: ClarificationFlow): String? = flows.entries.firstOrNull { it.value === flow }?.key

    /** 打开或聚焦该会话的窗口；第一次打开时创建流程。 */
    internal fun open(sessionKey: String, tabId: String, agentType: AgentType, title: String) {
        dialogs[sessionKey]?.let {
            it.toFront()
            return
        }
        owners[sessionKey] = tabId
        agentTypes[sessionKey] = agentType
        val flow = flows.getOrPut(sessionKey) { newFlow(agentType) }
        val dialog = ClarificationDialog(project, this, flow, title)
        dialogs[sessionKey] = dialog
        dialog.show()
    }

    private fun newFlow(agentType: AgentType): ClarificationFlow {
        val chinese = ImuxBundle.currentLanguage() in setOf(PluginLanguage.SIMPLIFIED_CHINESE, PluginLanguage.TRADITIONAL_CHINESE)
        lateinit var flow: ClarificationFlow
        flow = ClarificationFlow(chinese, loadGuidance(chinese), runAgent = { prompt -> runAgent(agentType, prompt) { flow } })
        return flow
    }

    internal fun hasBackground(sessionKey: String): Boolean =
        SessionMonitor.getInstance(project).model.sessionOf(sessionKey)?.let { Files.isRegularFile(it.filePath) } == true

    internal fun start(sessionKey: String, requirement: String, includeBackground: Boolean) {
        val flow = flows[sessionKey] ?: return
        val session = if (includeBackground) SessionMonitor.getInstance(project).model.sessionOf(sessionKey) else null
        flow.begin(requirement, null)
        // 最多读会话文件末尾 32MB，不能放在界面线程
        launch(sessionKey, flow) {
            it.attachBackground(session?.let { s -> latestConversationOf(s, emptyList(), MAX_BACKGROUND_CHARS) })
            it.advance()
        }
    }

    internal fun answer(sessionKey: String, answer: Answer, correction: String?) {
        val flow = flows[sessionKey] ?: return
        flow.answer(answer, correction)
        launch(sessionKey, flow) { it.advance() }
    }

    internal fun skip(sessionKey: String, id: String) {
        val flow = flows[sessionKey] ?: return
        if (flow.skip(id)) launch(sessionKey, flow) { it.advance() }
    }

    internal fun pin(sessionKey: String, id: String) { flows[sessionKey]?.pin(id) }

    internal fun reopen(sessionKey: String, id: String) { flows[sessionKey]?.reopen(id) }

    internal fun addDirection(sessionKey: String, title: String) { flows[sessionKey]?.addDirection(title) }

    internal fun finish(sessionKey: String) {
        val flow = flows[sessionKey] ?: return
        jobs.remove(sessionKey)?.cancel()
        flow.beginResult()
        launch(sessionKey, flow) { it.generateResult() }
    }

    internal fun fallback(sessionKey: String) { flows[sessionKey]?.fallbackResult() }

    internal fun continueClarifying(sessionKey: String) {
        val flow = flows[sessionKey] ?: return
        flow.continueClarifying()
        launch(sessionKey, flow) { it.advance() }
    }

    internal fun retry(sessionKey: String) {
        val flow = flows[sessionKey] ?: return
        when (flow.retry()) {
            PendingWork.ROUND -> launch(sessionKey, flow) { it.advance() }
            PendingWork.RESULT -> launch(sessionKey, flow) { it.generateResult() }
            null -> Unit
        }
    }

    internal fun cancel(sessionKey: String) {
        jobs.remove(sessionKey)?.cancel()
        flows[sessionKey]?.cancelled()
    }

    private fun launch(sessionKey: String, flow: ClarificationFlow, body: (ClarificationFlow) -> Unit) {
        jobs.remove(sessionKey)?.cancel()
        jobs[sessionKey] =
            scope.launch(Dispatchers.IO) {
                try {
                    runInterruptible { body(flow) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OneShotAgentTimeoutException) {
                    flow.fail(FailureReason.TIMEOUT, e.timeoutSeconds.toString(), null)
                } catch (e: Throwable) {
                    flow.fail(FailureReason.AGENT_ERROR, e.message ?: e.javaClass.simpleName, null)
                }
            }
    }

    private fun runAgent(agentType: AgentType, prompt: String, flow: () -> ClarificationFlow): String {
        val projectPath = project.basePath ?: "."
        val shell =
            resolveShell(
                System.getenv("SHELL"),
                isWindows = SystemInfo.isWindows,
                configuredShell = ApplicationManager.getApplication()?.let { TerminalLocalOptions.getInstance().shellPath },
            )
        val invocation = oneShotAgentInvocation(shell, agentType, projectPath, AgentMcpConfig.configured(project, projectPath, agentType))
        unexited.retry()
        val running = ConcurrentLinkedQueue<AtomicReference<TrackedProcess?>>()
        return try {
            runTracked(running) { processRef ->
                runOneShotAgent(
                    agentType,
                    invocation,
                    Path.of(projectPath),
                    prompt,
                    AGENT_TIMEOUT_SECONDS,
                    processRef,
                    onProgress = { event -> overviewProgressText(event)?.let { flow().progress(it) } },
                )
            }
        } finally {
            unexited.addAll(cleanupProcesses(running))
        }
    }

    /**
     * 与结对编程对齐的发送时机：主会话正在执行、或 Claude 停在确认框上时等这一轮结束，其余立即发。
     * 判定读的是 [SessionMonitor] 已有的运行集合与运行态，与结对编程触发审查用的是同一组信号；
     * 在循环里轮询而不是挂在「轮次完成」事件上，确认框被 Esc 取消、直接回到空闲时也能发出去。
     * 括号粘贴未开启时持续重试（与 `PeerCoordinator.injectFeedback` 相同）；可随时取消；
     * 标签页已关闭则通知并提供复制。
     */
    internal fun send(sessionKey: String, text: String) {
        val flow = flows[sessionKey] ?: return
        jobs.remove(sessionKey)?.cancel()
        flow.sending(waitingForTurn = isMainSessionBusy(sessionKey))
        jobs[sessionKey] =
            scope.launch(Dispatchers.EDT) {
                while (isActive) {
                    val key = currentKeyOf(flow) ?: return@launch
                    val view = TerminalHost.getInstance(project).terminalViewOf(key)
                    if (view == null) {
                        flow.sendCancelled()
                        notifyUndelivered(text)
                        return@launch
                    }
                    val busy = isMainSessionBusy(key)
                    flow.sending(waitingForTurn = busy)
                    if (!busy && trySendPeerFeedback(view.createSendTextBuilder(), text)) {
                        discard(key)
                        return@launch
                    }
                    delay(if (busy) BUSY_POLL_MILLIS else SEND_RETRY_MILLIS)
                }
            }
    }

    internal fun cancelSend(sessionKey: String) {
        jobs.remove(sessionKey)?.cancel()
        flows[sessionKey]?.sendCancelled()
    }

    private fun isMainSessionBusy(sessionKey: String): Boolean {
        val monitor = SessionMonitor.getInstance(project)
        val waiting = monitor.runtime.values.filter { it.isWaiting }.map { it.sessionId }.toSet()
        return mainSessionBusy(monitor.runningIds, waiting, sessionKey)
    }

    private fun notifyUndelivered(text: String) {
        val notification =
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(ImuxBundle.message("clarify.notify.title"), ImuxBundle.message("clarify.notify.closed"), NotificationType.WARNING)
        notification.addAction(
            NotificationAction.createSimpleExpiring(ImuxBundle.message("clarify.notify.copy")) {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            },
        )
        notification.notify(project)
    }

    /** 窗口关闭只摘掉窗口，流程与在途一轮保留，重新打开时恢复。 */
    internal fun dialogClosed(sessionKey: String) {
        dialogs.remove(sessionKey)
    }

    /** 发送成功：关窗并清空该会话的澄清状态。 */
    private fun discard(sessionKey: String) {
        jobs.remove(sessionKey)?.cancel()
        flows.remove(sessionKey)
        owners.remove(sessionKey)
        agentTypes.remove(sessionKey)
        dialogs.remove(sessionKey)?.close(com.intellij.openapi.ui.DialogWrapper.OK_EXIT_CODE)
    }

    /** 会话标签页真正关闭：取消、清理、丢弃状态。 */
    internal fun releaseOwner(tabId: String) {
        owners.filterValues { it == tabId }.keys.forEach { key ->
            flows[key]?.cancelled()
            discard(key)
        }
    }

    private fun migrate(from: String, to: String) {
        if (from == to) return
        flows.remove(from)?.let { flows[to] = it }
        owners.remove(from)?.let { owners[to] = it }
        agentTypes.remove(from)?.let { agentTypes[to] = it }
        jobs.remove(from)?.let { jobs[to] = it }
        dialogs.remove(from)?.let {
            dialogs[to] = it
            it.sessionKey = to
        }
    }

    override fun dispose() {
        jobs.values.forEach { it.cancel() }
        // 项目关闭时窗口不会随之关闭，必须由服务收起
        dialogs.values.toList().forEach { it.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE) }
        dialogs.clear()
    }

    companion object {
        fun getInstance(project: Project): ClarificationService = project.service()

        internal const val AGENT_TIMEOUT_SECONDS = 600L
        private const val SEND_RETRY_MILLIS = 100L
        private const val BUSY_POLL_MILLIS = 500L
        private const val NOTIFICATION_GROUP = "imux.turnCompleted"
    }
}

/** 主会话正在执行或停在确认框上（只有 Claude 有这个信号）。纯函数，便于单测。 */
internal fun mainSessionBusy(running: Set<String>, waiting: Set<String>, sessionKey: String): Boolean =
    sessionKey in running || sessionKey in waiting
```

说明：
- `ClarificationDialog` 在 Task 7 创建；本任务先写好服务，编译要等 Task 7 完成。为了让本任务能单独编译，先在 `ClarificationDialog.kt` 放一个最小占位类（Task 7 整体替换）：

```kotlin
package com.github.izerui.imux.clarification

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import javax.swing.JComponent
import javax.swing.JPanel

internal class ClarificationDialog(
    project: Project,
    private val service: ClarificationService,
    private val flow: ClarificationFlow,
    title: String,
) : DialogWrapper(project, null, true, IdeModalityType.MODELESS) {
    @Volatile var sessionKey: String = service.currentKeyOf(flow).orEmpty()

    init {
        this.title = title
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel()
}
```

- `overviewProgressText` 若为 `private`/`internal` 以外的可见性，保持 `internal` 即可（同一模块可见）。
- 先确认 `TerminalHost.getInstance(project).addSessionKeyMigratedListener` 的第二个参数是 `SessionKeyMigratedListener`（`fun interface`），上面 lambda 写法即可。

- [ ] **Step 4: 编译并运行测试**

Run: `./gradlew test --tests 'com.github.izerui.imux.clarification.*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/clarification/ClarificationService.kt src/main/kotlin/com/github/izerui/imux/clarification/ClarificationDialog.kt src/test/kotlin/com/github/izerui/imux/clarification/ClarificationServiceSourceTest.kt src/test/kotlin/com/github/izerui/imux/clarification/MainSessionBusyTest.kt
git commit -m "feat(clarification): 项目级服务负责单次调用、取消、迁移、关闭清理，并按结对编程的时机发送"
```

---

### Task 7: 非模态窗口与文案

**Files:**
- Modify (整体替换 Task 6 的占位): `src/main/kotlin/com/github/izerui/imux/clarification/ClarificationDialog.kt`
- Modify: `src/main/resources/messages/ImuxBundle.properties` 及其余 9 个语言文件
- Test: 既有 `src/test/kotlin/com/github/izerui/imux/ImuxBundleTest.kt`（检查各语言键集合与占位符）

**Interfaces:**
- Consumes: Task 6 服务的全部 `internal` 方法；Task 4 的 `ClarificationState`、`ClarificationFlow.ledger/state/addListener/removeListener/editResult`。
- Produces: `internal class ClarificationDialog(project, service, flow, title)`，`var sessionKey: String`。

- [ ] **Step 1: 加文案**

在 `ImuxBundle.properties`（英文）末尾加：

```properties
clarify.action.text=Clarify Requirement
clarify.action.description=Before the agent starts, find unconsidered directions with the project's code
clarify.dialog.title=Clarify Requirement · {0}
clarify.input.label=What do you want to do? A sentence, a paragraph or a document path:
clarify.input.background=Include the recent conversation of this session
clarify.input.start=Start
clarify.directions.title=Directions
clarify.directions.add=Add Direction
clarify.directions.add.prompt=Describe the direction you want confirmed:
clarify.directions.skip=Skip (Use Default)
clarify.directions.pin=Ask This Next
clarify.directions.reopen=Reopen
clarify.understanding.title=My understanding
clarify.understanding.correct=Correct Understanding
clarify.question.other=Other idea:
clarify.question.confirm=Confirm
clarify.question.skip=Skip
clarify.question.recommended={0} (recommended)
clarify.question.becomes=Direction becomes: {0}
clarify.question.basis=Basis: {0}
clarify.investigating=Investigating… {0}
clarify.elapsed={0}s
clarify.cancel=Cancel
clarify.retry=Retry
clarify.finish=Enough, Generate Result
clarify.result.title=Adjusted requirement
clarify.result.send=Send to Agent
clarify.result.copy=Copy
clarify.result.back=Continue Clarifying
clarify.sending=Waiting for the terminal to accept input…
clarify.sending.turn=Waiting for the current turn to finish…
clarify.sending.cancel=Cancel Sending
clarify.round=Round {0}
clarify.failed.cancelled=Cancelled
clarify.failed.timeout=Timed out after {0}s
clarify.failed.protocol=The agent's answer could not be understood
clarify.failed.error=The agent failed: {0}
clarify.failed.raw=Show Agent Output
clarify.close=Close
clarify.notify.title=Requirement Clarification
clarify.notify.closed=The session tab is closed; the requirement was not sent.
clarify.notify.copy=Copy Requirement
```

`ImuxBundle_zh_CN.properties`：

```properties
clarify.action.text=需求澄清
clarify.action.description=动手前结合项目代码找出没想到的方向
clarify.dialog.title=需求澄清 · {0}
clarify.input.label=这次要做什么？一句话、一段文字或需求文档路径：
clarify.input.background=附带当前会话的最近对话
clarify.input.start=开始澄清
clarify.directions.title=方向
clarify.directions.add=添加方向
clarify.directions.add.prompt=描述你想确认的方向：
clarify.directions.skip=跳过（按默认处理）
clarify.directions.pin=先问这个
clarify.directions.reopen=重新打开
clarify.understanding.title=我的理解
clarify.understanding.correct=纠正理解
clarify.question.other=其他想法：
clarify.question.confirm=确认
clarify.question.skip=跳过
clarify.question.recommended={0}（推荐）
clarify.question.becomes=方向定为：{0}
clarify.question.basis=依据：{0}
clarify.investigating=调查中… {0}
clarify.elapsed={0} 秒
clarify.cancel=取消
clarify.retry=重试
clarify.finish=够了，生成结果
clarify.result.title=调整后的需求
clarify.result.send=交给 Agent
clarify.result.copy=复制
clarify.result.back=继续澄清
clarify.sending=等待终端可以接收…
clarify.sending.turn=等待当前回合结束…
clarify.sending.cancel=取消发送
clarify.round=第 {0} 轮
clarify.failed.cancelled=已取消
clarify.failed.timeout={0} 秒后超时
clarify.failed.protocol=无法理解 Agent 的回答
clarify.failed.error=Agent 执行失败：{0}
clarify.failed.raw=查看 Agent 输出
clarify.close=关闭
clarify.notify.title=需求澄清
clarify.notify.closed=会话标签页已关闭，需求没有发送。
clarify.notify.copy=复制需求
```

`ImuxBundle_zh_TW.properties`：同上逐条转为繁体（如「需求澄清」「動手前結合專案程式碼找出沒想到的方向」「調查中…」「交給 Agent」等），键与占位符不变。
`de`、`es`、`fr`、`ja`、`ko`、`pt_BR`、`ru` 六个语言（加 `ja`、`ko` 共七个）：把英文值逐条翻译为对应语言，键与 `{0}` 占位符保持不变；不确定的术语保留英文 "Agent"。

Run: `./gradlew test --tests 'com.github.izerui.imux.ImuxBundleTest'`
Expected: PASS（键集合一致、占位符一致）

- [ ] **Step 2: 确认图标常量存在**

Run:
```bash
javap -classpath "/Applications/IntelliJ IDEA.app/Contents/lib/intellij.platform.util.ui.jar" 'com.intellij.icons.AllIcons$RunConfigurations' | grep -E "TestNotRan|TestPassed|TestIgnored"
javap -classpath "/Applications/IntelliJ IDEA.app/Contents/lib/intellij.platform.util.ui.jar" 'com.intellij.icons.AllIcons$Actions' | grep -E " Execute;| Cancel;| IntentionBulb;"
```
Expected: 每个名字都能找到。缺失的，到 `.reference/icon-gallery/index.html` 换一个语义相近的官方图标。

- [ ] **Step 3: 写窗口**

```kotlin
package com.github.izerui.imux.clarification

import com.github.izerui.imux.ImuxBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.Timer

/**
 * 需求澄清窗口：只负责显示流程状态并把操作转给 [ClarificationService]。
 * 非模态，打开期间仍可看代码、操作终端；关闭不中断在途一轮。
 */
internal class ClarificationDialog(
    private val project: Project,
    private val service: ClarificationService,
    private val flow: ClarificationFlow,
    title: String,
) : DialogWrapper(project, null, true, IdeModalityType.MODELESS) {
    @Volatile var sessionKey: String = service.currentKeyOf(flow).orEmpty()

    private val directionModel = CollectionListModel<Direction>()
    private val directionList = JBList(directionModel).apply { cellRenderer = DirectionRenderer() }
    private val requirementLabel = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty(4)
    }
    private val right = JPanel(BorderLayout())
    private val roundLabel = JBLabel()
    /** 右栏当前显示的状态。只有状态变了才重建右栏，否则正在输入的文字、选中的选项会被清掉。 */
    private var renderedState: ClarificationState? = null
    private var elapsedLabel: JBLabel? = null
    private val finishButton = JButton(ImuxBundle.message("clarify.finish")).apply { addActionListener { service.finish(sessionKey) } }
    private val elapsedTimer = Timer(1_000) { updateElapsed() }
    private val listener: () -> Unit = { ApplicationManager.getApplication().invokeLater({ render() }, ModalityState.any()) }

    init {
        this.title = ImuxBundle.message("clarify.dialog.title", title)
        setCancelButtonText(ImuxBundle.message("clarify.close"))
        installDirectionMenu()
        flow.addListener(listener)
        init()
        render()
        elapsedTimer.start()
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun createCenterPanel(): JComponent {
        val addButton = JButton(ImuxBundle.message("clarify.directions.add"), AllIcons.General.Add).apply {
            addActionListener {
                val text = Messages.showInputDialog(project, ImuxBundle.message("clarify.directions.add.prompt"), ImuxBundle.message("clarify.directions.add"), null)
                if (!text.isNullOrBlank()) service.addDirection(sessionKey, text)
            }
        }
        val left = JPanel(BorderLayout()).apply {
            add(requirementLabel, BorderLayout.NORTH)
            add(JBScrollPane(directionList), BorderLayout.CENTER)
            add(addButton, BorderLayout.SOUTH)
            border = JBUI.Borders.emptyRight(8)
        }
        val splitter = JBSplitter(false, 0.35f).apply {
            firstComponent = left
            secondComponent = JBScrollPane(right).apply { border = JBUI.Borders.empty() }
        }
        val bottom = JPanel(BorderLayout()).apply {
            add(roundLabel, BorderLayout.WEST)
            add(finishButton, BorderLayout.EAST)
            border = JBUI.Borders.emptyTop(8)
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = JBUI.size(900, 560)
            add(splitter, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
    }

    override fun dispose() {
        elapsedTimer.stop()
        flow.removeListener(listener)
        service.dialogClosed(sessionKey)
        super.dispose()
    }

    private fun render() {
        val ledger = flow.ledger
        val state = flow.state
        requirementLabel.text = ledger.requirement
        requirementLabel.isVisible = ledger.requirement.isNotEmpty()
        directionModel.replaceAll(ledger.directions.filter { it.status != DirectionStatus.DROPPED })
        roundLabel.text = if (ledger.history.isEmpty() && state is ClarificationState.Input) "" else ImuxBundle.message("clarify.round", ledger.history.size + 1)
        finishButton.isEnabled = state is ClarificationState.Asking
        if (state == renderedState) return
        renderedState = state
        elapsedLabel = null
        right.removeAll()
        right.add(
            when (state) {
                ClarificationState.Input -> inputPanel()
                is ClarificationState.Investigating -> investigatingPanel(state)
                is ClarificationState.Asking -> askingPanel(state.question, ledger)
                is ClarificationState.Result -> resultPanel(state.requirement)
                is ClarificationState.Sending -> sendingPanel(state)
                is ClarificationState.Failed -> failedPanel(state)
            },
            BorderLayout.NORTH,
        )
        right.revalidate()
        right.repaint()
    }

    private fun updateElapsed() {
        val state = flow.state as? ClarificationState.Investigating ?: return
        elapsedLabel?.text = ImuxBundle.message("clarify.elapsed", ((System.currentTimeMillis() - state.startedAt) / 1_000).coerceAtLeast(0))
    }

    private fun column(): JPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, JBUI.scale(6), true, false))

    private fun wrapped(text: String, bold: Boolean = false): JBTextArea =
        JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            if (bold) font = font.deriveFont(java.awt.Font.BOLD)
        }

    private fun buttons(vararg items: JComponent): JPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { items.forEach(::add) }

    private fun button(key: String, onClick: () -> Unit): JButton = JButton(ImuxBundle.message(key)).apply { addActionListener { onClick() } }

    private fun inputPanel(): JComponent {
        val area = JBTextArea(6, 50).apply { lineWrap = true; wrapStyleWord = true }
        val hasBackground = service.hasBackground(sessionKey)
        val background = JBCheckBox(ImuxBundle.message("clarify.input.background"), hasBackground).apply { isVisible = hasBackground }
        return column().apply {
            add(JBLabel(ImuxBundle.message("clarify.input.label")))
            add(JBScrollPane(area))
            add(background)
            add(buttons(button("clarify.input.start") {
                if (area.text.isNotBlank()) service.start(sessionKey, area.text, background.isSelected && hasBackground)
            }))
        }
    }

    private fun investigatingPanel(state: ClarificationState.Investigating): JComponent {
        val elapsed = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
        elapsedLabel = elapsed
        updateElapsed()
        return column().apply {
            add(JBLabel(ImuxBundle.message("clarify.investigating", state.progress.orEmpty()), AllIcons.Process.Step_1, JBLabel.LEADING))
            add(elapsed)
            add(buttons(button("clarify.cancel") { service.cancel(sessionKey) }))
        }
    }

    private fun askingPanel(question: Question, ledger: Ledger): JComponent {
        val panel = column()
        val correction = JBTextArea(3, 50).apply { lineWrap = true; wrapStyleWord = true; isVisible = false }
        ledger.understanding?.takeIf { ledger.history.isEmpty() || ledger.corrections.isNotEmpty() }?.let { understanding ->
            panel.add(JBLabel(ImuxBundle.message("clarify.understanding.title")).apply { font = font.deriveFont(java.awt.Font.BOLD) })
            panel.add(wrapped(understanding))
            panel.add(ActionLink(ImuxBundle.message("clarify.understanding.correct")) { correction.isVisible = !correction.isVisible; panel.revalidate() })
            panel.add(correction)
        }
        panel.add(wrapped(question.text, bold = true))
        if (question.reason.isNotBlank()) panel.add(wrapped(question.reason))
        if (question.basis.isNotBlank()) panel.add(wrapped(ImuxBundle.message("clarify.question.basis", question.basis)).apply { foreground = UIUtil.getContextHelpForeground() })

        val group = ButtonGroup()
        val radios = question.options.map { option ->
            val label = if (option.recommended) ImuxBundle.message("clarify.question.recommended", option.label) else option.label
            JBRadioButton(label, option.recommended).also { radio ->
                group.add(radio)
                panel.add(radio)
                panel.add(wrapped(ImuxBundle.message("clarify.question.becomes", option.conclusion)).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.emptyLeft(24)
                })
            }
        }
        val otherRadio = JBRadioButton(ImuxBundle.message("clarify.question.other"))
        val otherField = JBTextField()
        group.add(otherRadio)
        otherField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) { if (otherField.text.isNotEmpty()) otherRadio.isSelected = true }
        })
        panel.add(otherRadio)
        panel.add(otherField)

        panel.add(buttons(
            button("clarify.question.skip") { service.skip(sessionKey, question.directionId) },
            button("clarify.question.confirm") {
                val picked = radios.indexOfFirst { it.isSelected }
                val answer = when {
                    otherRadio.isSelected && otherField.text.isNotBlank() -> Answer.OwnWords(otherField.text)
                    picked >= 0 -> Answer.Picked(question.options[picked].id)
                    else -> return@button
                }
                service.answer(sessionKey, answer, correction.text.takeIf { correction.isVisible && it.isNotBlank() })
            },
        ))
        return panel
    }

    private fun resultPanel(requirement: String): JComponent {
        val area = JBTextArea(requirement, 14, 50).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        return column().apply {
            add(JBLabel(ImuxBundle.message("clarify.result.title")).apply { font = font.deriveFont(java.awt.Font.BOLD) })
            add(JBScrollPane(area))
            add(buttons(
                button("clarify.result.back") { service.continueClarifying(sessionKey) },
                button("clarify.result.copy") { CopyPasteManager.getInstance().setContents(StringSelection(area.text)) },
                button("clarify.result.send") {
                    if (area.text.isBlank()) return@button
                    flow.editResult(area.text)
                    service.send(sessionKey, area.text)
                },
            ))
        }
    }

    private fun sendingPanel(state: ClarificationState.Sending): JComponent =
        column().apply {
            add(JBLabel(ImuxBundle.message("clarify.result.title")).apply { font = font.deriveFont(java.awt.Font.BOLD) })
            add(JBScrollPane(JBTextArea(state.requirement, 14, 50).apply { lineWrap = true; wrapStyleWord = true; isEditable = false }))
            add(JBLabel(ImuxBundle.message(if (state.waitingForTurn) "clarify.sending.turn" else "clarify.sending"), AllIcons.Process.Step_1, JBLabel.LEADING))
            add(buttons(button("clarify.sending.cancel") { service.cancelSend(sessionKey) }))
        }

    private fun failedPanel(state: ClarificationState.Failed): JComponent {
        val message = when (state.reason) {
            FailureReason.CANCELLED -> ImuxBundle.message("clarify.failed.cancelled")
            FailureReason.TIMEOUT -> ImuxBundle.message("clarify.failed.timeout", state.detail)
            FailureReason.PROTOCOL -> ImuxBundle.message("clarify.failed.protocol")
            FailureReason.AGENT_ERROR -> ImuxBundle.message("clarify.failed.error", state.detail)
        }
        val panel = column()
        panel.add(JBLabel(message, AllIcons.General.Warning, JBLabel.LEADING))
        state.raw?.let { raw ->
            panel.add(ActionLink(ImuxBundle.message("clarify.failed.raw")) {
                Messages.showInfoMessage(project, raw.take(20_000), ImuxBundle.message("clarify.failed.raw"))
            })
        }
        panel.add(buttons(
            button("clarify.finish") { service.fallback(sessionKey) },
            button("clarify.retry") { service.retry(sessionKey) },
        ))
        return panel
    }

    private fun installDirectionMenu() {
        fun selected(): Direction? = directionList.selectedValue
        val group = DefaultActionGroup(
            menuAction("clarify.directions.skip", { it.status == DirectionStatus.OPEN || it.status == DirectionStatus.ACTIVE }) { service.skip(sessionKey, it.id) },
            menuAction("clarify.directions.pin", { it.status == DirectionStatus.OPEN }) { service.pin(sessionKey, it.id) },
            menuAction("clarify.directions.reopen", { it.status == DirectionStatus.DECIDED || it.status == DirectionStatus.DEFAULTED }) { service.reopen(sessionKey, it.id) },
        )
        PopupHandler.installPopupMenu(directionList, group, "ClarifyDirections")
    }

    private fun menuAction(key: String, enabled: (Direction) -> Boolean, perform: (Direction) -> Unit) =
        object : DumbAwareAction(ImuxBundle.message(key)) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = directionList.selectedValue?.let(enabled) == true
            }

            override fun actionPerformed(e: AnActionEvent) {
                directionList.selectedValue?.let(perform)
            }
        }

    private class DirectionRenderer : ColoredListCellRenderer<Direction>() {
        override fun customizeCellRenderer(list: JList<out Direction>, value: Direction, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = when (value.status) {
                DirectionStatus.OPEN -> AllIcons.RunConfigurations.TestNotRan
                DirectionStatus.ACTIVE -> AllIcons.Actions.Execute
                DirectionStatus.DECIDED -> AllIcons.RunConfigurations.TestPassed
                DirectionStatus.DEFAULTED -> AllIcons.RunConfigurations.TestIgnored
                DirectionStatus.DROPPED -> AllIcons.Actions.Cancel
            }
            append(value.title)
            value.conclusion?.takeIf { value.status == DirectionStatus.DECIDED || value.status == DirectionStatus.DEFAULTED }?.let {
                append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }
}
```

- [ ] **Step 4: 编译并跑全部测试**

Run: `./gradlew test`
Expected: PASS。编译报错时按报错修正导入或平台 API 名称（只改到能编译，不改行为）；`DialogWrapper.setCancelButtonText` 不存在时改为 `cancelAction.putValue(Action.NAME, ImuxBundle.message("clarify.close"))`。

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/clarification/ClarificationDialog.kt src/main/resources/messages/
git commit -m "feat(clarification): 需求澄清非模态窗口与多语言文案"
```

---

### Task 8: 入口接线

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/terminal/ClarifyRequirementAction.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`（`<actions>` 内，`imux.PeerProgramming` 之后）
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt`（`peerReviewOnceLink` 附近：约 187-198、218、272、407、413 行；`dispose` 里约 606-609 行）
- Test: `src/test/kotlin/com/github/izerui/imux/PluginXmlRegistrationTest.kt`、`src/test/kotlin/com/github/izerui/imux/clarification/ClarificationEntrySourceTest.kt`

**Interfaces:**
- Consumes: `ClarificationService.getInstance(project).open(sessionKey, tabId, agentType, title)`、`releaseOwner(tabId)`；`TerminalHost.fileFor(view)`；`AgentTerminalVirtualFile.sessionKey/tabId/agentType/tabTitle`。

- [ ] **Step 1: 写失败的测试**

在 `PluginXmlRegistrationTest` 加：

```kotlin
    @Test
    fun `注册了需求澄清的终端右键菜单入口`() {
        assertTrue(
            "plugin.xml 未注册 ClarifyRequirementAction，终端右键菜单里不会出现「需求澄清」",
            pluginXml.contains("com.github.izerui.imux.terminal.ClarifyRequirementAction"),
        )
    }
```

新建 `ClarificationEntrySourceTest.kt`：

```kotlin
package com.github.izerui.imux.clarification

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationEntrySourceTest {
    private val editor = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt")

    @Test
    fun `横幅里的需求澄清入口打开当前会话的窗口`() {
        assertTrue(editor.normalized.contains("ClarificationService.getInstance(project).open(virtualFile.sessionKey, virtualFile.tabId, virtualFile.agentType, virtualFile.tabTitle)"))
    }

    @Test
    fun `关闭标签页时按标签页身份清理需求澄清且不为此新建服务`() {
        val dispose = editor.bodyAfter("override fun dispose()", '{')
        assertTrue("按标签页身份清理", dispose.contains(".releaseOwner(virtualFile.tabId)"))
        assertTrue("项目关闭途中不能新建服务", dispose.contains("project.serviceIfCreated<ClarificationService>()?.releaseOwner(virtualFile.tabId)"))
    }

    @Test
    fun `需求澄清入口与单次审查同时显示和隐藏`() {
        assertTrue(editor.normalized.contains("peerReviewOnceLink.isVisible = true clarifyLink.isVisible = true"))
        assertTrue(editor.normalized.contains("peerReviewOnceLink.isVisible = false clarifyLink.isVisible = false"))
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew test --tests 'com.github.izerui.imux.PluginXmlRegistrationTest' --tests 'com.github.izerui.imux.clarification.ClarificationEntrySourceTest'`
Expected: FAIL

- [ ] **Step 3: 右键菜单 action**

```kotlin
package com.github.izerui.imux.terminal

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.clarification.ClarificationService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.terminal.frontend.view.TerminalView

/** 终端正文右键菜单的「需求澄清」，在 plugin.xml 注册。 */
class ClarifyRequirementAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.text = ImuxBundle.message("clarify.action.text")
        event.presentation.description = ImuxBundle.message("clarify.action.description")
        event.presentation.icon = AllIcons.Actions.IntentionBulb
        event.presentation.isEnabledAndVisible = fileOf(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = fileOf(event) ?: return
        ClarificationService.getInstance(project).open(file.sessionKey, file.tabId, file.agentType, file.tabTitle)
    }

    private fun fileOf(event: AnActionEvent): AgentTerminalVirtualFile? {
        val project = event.project ?: return null
        val view = event.getData(TerminalView.DATA_KEY) ?: return null
        return TerminalHost.getInstance(project).fileFor(view)
    }
}
```

`plugin.xml` 在 `imux.PeerProgramming` 的 `</group>` 之后加：

```xml
        <action
                id="imux.ClarifyRequirement"
                class="com.github.izerui.imux.terminal.ClarifyRequirementAction"
                text="Clarify Requirement"
                description="Before the agent starts, find unconsidered directions with the project's code"
                icon="AllIcons.Actions.IntentionBulb">
            <add-to-group group-id="Terminal.ReworkedTerminalContextMenu" anchor="last"/>
        </action>
```

- [ ] **Step 4: 横幅入口与关闭清理**

在 `AgentTerminalFileEditor` 的 `peerReviewOnceLink` 定义之后加：

```kotlin
    private val clarifyLink: ActionLink =
        ActionLink(ImuxBundle.message("clarify.action.text")) {
            ClarificationService.getInstance(project).open(virtualFile.sessionKey, virtualFile.tabId, virtualFile.agentType, virtualFile.tabTitle)
        }.apply {
            isFocusable = false
            border = JBUI.Borders.emptyLeft(12)
        }
```

- `peerBanner` 里 `add(peerReviewOnceLink)` 之后加 `add(clarifyLink)`；
- 语言监听里 `peerReviewOnceLink.text = …` 之后加 `clarifyLink.text = ImuxBundle.message("clarify.action.text")`；
- `refreshPeerBanner()` 里 `peerReviewOnceLink.isVisible = true` 之后紧接一行 `clarifyLink.isVisible = true`，`peerReviewOnceLink.isVisible = false` 之后紧接一行 `clarifyLink.isVisible = false`；
- `dispose` 里 `ProgressOverviewService…releaseOwner(virtualFile.tabId)` 之后加（用 `serviceIfCreated`：从没用过澄清的项目，关闭途中不该为此新建服务、注册监听）：

```kotlin
        project.serviceIfCreated<ClarificationService>()?.releaseOwner(virtualFile.tabId)
```

  并在文件顶部加 `import com.github.izerui.imux.clarification.ClarificationService` 与 `import com.intellij.openapi.components.serviceIfCreated`。
  注意保持轨迹那一行在前：`SessionTrailOverlaySourceTest` 用 `indexOf(".releaseOwner(virtualFile.tabId)")` 取第一处。

- [ ] **Step 5: 运行测试，确认通过**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/terminal/ClarifyRequirementAction.kt src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt src/main/resources/META-INF/plugin.xml src/test/kotlin/com/github/izerui/imux/PluginXmlRegistrationTest.kt src/test/kotlin/com/github/izerui/imux/clarification/ClarificationEntrySourceTest.kt
git commit -m "feat(clarification): 横幅与终端右键菜单接入需求澄清，关闭标签页时清理"
```

---

### Task 9: 整体验证并更新设计文档的实现状态

**Files:**
- Modify: `docs/superpowers/specs/2026-10-22-requirement-clarifier-design.md`（「实现状态」表）

- [ ] **Step 1: 全量测试**

Run: `./gradlew test`
Expected: PASS（记下用例总数）

- [ ] **Step 2: 插件验证**

Run: `./gradlew verifyPlugin`
Expected: 无 Critical。若 `.verify/ides/` 为空，记录"未验证：本地无验证用 IDE"，不要写成已通过。

- [ ] **Step 3: 实际走一遍**

Run: `./gradlew runIde`
在沙箱 IDE 里打开一个 Claude 会话标签页，依次确认：
1. 横幅「单次审查」旁出现「需求澄清」，右键菜单也有；
2. 输入一句话需求 → 调查中显示进度与秒数 → 首轮同屏显示理解、方向列表、第一个问题；
3. 选一个选项确认 → 下一轮的问题建立在回答之上；右键某方向「跳过」后它显示默认结论；
4. 「够了，生成结果」→ 结果可编辑 → 「交给 Agent」→ 需求出现在会话里并被提交，窗口关闭；
   再试一次：先让主会话执行一个较长的任务，执行中点「交给 Agent」，窗口显示「等待当前回合结束」，回合结束后才发出；
   让 Claude 停在一个权限确认框上再点「交给 Agent」，确认框不被误答，处理完确认框、回合结束后才发出；
   等待期间点「取消发送」回到结果页，文本还在；
5. 中途关闭窗口再打开，状态恢复；关闭会话标签页后再打开窗口，从头开始。

每一项记下通过或失败现象；失败的修复后从 Step 1 重来。

- [ ] **Step 4: 更新实现状态**

把设计文档「实现状态」表改为实际结果：已完成的写"已完成"；内嵌指南改为"已接入（`loadGuidance` 读取）"；`verifyPlugin` 未跑的写"未验证"并写原因。

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-10-22-requirement-clarifier-design.md
git commit -m "docs: 更新需求澄清实现状态"
```
