# 企业微信入站媒体 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 手机通过企业微信发来的图片、文件、视频（含图文混排中的图片）下载解密后保存到绑定会话所在项目的 `.imux/attachments/<日期>/`，并把相对路径随文字提交给终端里的 Agent。

**Architecture:** 渠道层只解析不下载，把每个附件包装成带 `fetch()` 的 `InboundAttachment`；`BridgeRouter` 在确认本人、非重复、已绑定后，通过新的 `AttachmentStore` 端口调用 `fetch()` 并落盘，再拼装提交文字。下载（`HttpFileDownload`）与解密（`WecomMedia`）在渠道层，落盘（`ProjectAttachmentStore`）在 `bridge/terminal`，命名规则是 `core` 里的纯函数。

**Tech Stack:** Kotlin 2.3、IntelliJ Platform 262、kotlinx.coroutines、JDK `java.net.http.HttpClient`、`javax.crypto`、Gson、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-10-27-wecom-inbound-media-design.md`

## Global Constraints

- 不新增第三方依赖。
- `bridge/core` 下除 `BridgeModels.kt` 外的文件不得引用 `AgentType`、`org.jetbrains.plugins.terminal`、`com.intellij`（`BridgeIsolationSourceTest`）。
- `bridge/channel` 下的文件不得引用 `AgentType`、`com.github.izerui.imux.bridge.terminal`、`com.github.izerui.imux.bridge.transcript`、`TerminalHost`、`SessionMonitor`（`ChannelIsolationSourceTest`）。
- bridge 以外的源码不得引用 bridge 包。
- 附件上限：`100L * 1024 * 1024` 字节。
- 下载超时 60 秒；连接超时 15 秒。
- 解密：AES-256-CBC，密钥为 `aeskey` 的 base64 解码（32 字节，允许缺少结尾 `=`），IV 取密钥前 16 字节，`NoPadding` 解密后手工去除 PKCS#7 填充：末字节值 n 满足 1 ≤ n ≤ 32 且末 n 字节都等于 n。
- 保存目录：`<项目根>/.imux/attachments/YYYY-MM-DD/`（本地日期）；文件名最长 120 字符；同名编号 `a.png`、`a-1.png`、`a-2.png`；返回相对项目根、分隔符为 `/` 的路径。
- 清理：每次保存后删除 `.imux/attachments/` 下目录名日期早于“今天减 7 天”的日期目录；目录名不是日期的不动。
- git：项目根有 `.git` 目录且 `.git/info/exclude` 中没有 `.imux/` 行时追加一行 `.imux/`；`.git` 不是目录时跳过。
- 文案 10 种语言都要提供：`ImuxBundle.properties`、`_zh_CN`、`_zh_TW`、`_ja`、`_ko`、`_de`、`_es`、`_fr`、`_pt_BR`、`_ru`，位于 `src/main/resources/messages/`。
- 日志不得记录附件内容、对端 id、aeskey。
- 测试命令统一用 `timeout 590 ./gradlew test --tests '<全限定类名>'`；全量用 `timeout 590 ./gradlew test --rerun -q`。
- 提交信息结尾必须是 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`，提交信息正文用中文。

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeModels.kt` | 修改 | 新增 `AttachmentKind`、`FetchedFile`、`FetchResult`、`InboundAttachment`；`InboundMessage` 增加 `attachments`；`InboundOutcome` 增加 `ATTACHMENT_FAILED` |
| `src/main/kotlin/com/github/izerui/imux/bridge/core/AttachmentNames.kt` | 新建 | 文件名清理、文件头识别扩展名、默认名、同名编号（纯函数） |
| `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgePorts.kt` | 修改 | 新增 `PlaceResult`、`AttachmentStore` 端口；`BridgeTexts` 增加 4 个方法 |
| `src/main/kotlin/com/github/izerui/imux/bridge/terminal/ProjectAttachmentStore.kt` | 新建 | 落盘、命名、编号、exclude、清理 |
| `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeRouter.kt` | 修改 | 入站时取回附件并拼装提交文字 |
| `src/main/kotlin/com/github/izerui/imux/bridge/BundleBridgeTexts.kt` | 修改 | 新文案实现 |
| `src/main/kotlin/com/github/izerui/imux/bridge/BridgeHub.kt` | 修改 | 路由器注入 `ProjectAttachmentStore()` |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/Transports.kt` | 修改 | 新增 `DownloadResult`、`HttpFileDownload`、`JdkHttpFileDownload`、`contentDispositionFileName` |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomMedia.kt` | 新建 | 流式解密、`WecomAttachment` |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomProtocol.kt` | 修改 | 解析 image、file、video、mixed 中的媒体引用 |
| `src/main/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomConnection.kt` | 修改 | 转交带附件的消息 |
| `docs/channel-protocols.md`、`docs/terminal-channel-bridge-feasibility.md` | 修改 | 记录入站媒体与联调项 |

---

### Task 1: 核心模型与附件命名

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeModels.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/core/AttachmentNames.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/core/AttachmentNamesTest.kt`

**Interfaces:**
- Produces（后续任务原样使用）：
  - `enum class AttachmentKind { IMAGE, FILE, VIDEO }`
  - `data class FetchedFile(val path: java.nio.file.Path, val suggestedName: String?)`
  - `sealed interface FetchResult { data class Ok(val file: FetchedFile); data object TooLarge; data class Failed(val reason: String) }`
  - `interface InboundAttachment { val kind: AttachmentKind; suspend fun fetch(maxBytes: Long): FetchResult }`
  - `InboundMessage(..., val text: String, val attachments: List<InboundAttachment> = emptyList())`
  - `InboundOutcome.ATTACHMENT_FAILED`
  - `object AttachmentNames { const val MAX_NAME_LENGTH = 120; fun sanitize(raw: String?): String?; fun extensionOf(head: ByteArray): String?; fun fallback(kind: AttachmentKind, time: java.time.LocalTime, extension: String?): String; fun unique(name: String, taken: (String) -> Boolean): String }`

- [ ] **Step 1: 写失败测试**

`src/test/kotlin/com/github/izerui/imux/bridge/core/AttachmentNamesTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class AttachmentNamesTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    @Test
    fun `清理文件名：去掉路径、控制字符与非法字符，空名返回 null`() {
        assertEquals("报告.pdf", AttachmentNames.sanitize("../../etc/报告.pdf"))
        assertEquals("a.png", AttachmentNames.sanitize("C:\\temp\\a.png"))
        assertEquals("ab.txt", AttachmentNames.sanitize("a\u0000b?.txt"))
        assertEquals("x.log", AttachmentNames.sanitize("  x.log.. "))
        assertNull(AttachmentNames.sanitize(null))
        assertNull(AttachmentNames.sanitize(" .. "))
    }

    @Test
    fun `过长文件名截断到 120 字符并保留扩展名`() {
        val name = AttachmentNames.sanitize("a".repeat(300) + ".pdf")!!
        assertEquals(120, name.length)
        assertEquals(true, name.endsWith(".pdf"))
        assertEquals(120, AttachmentNames.sanitize("b".repeat(300))!!.length)
    }

    @Test
    fun `按文件头识别扩展名`() {
        assertEquals("png", AttachmentNames.extensionOf(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertEquals("jpg", AttachmentNames.extensionOf(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("gif", AttachmentNames.extensionOf(ascii("GIF89a")))
        assertEquals("webp", AttachmentNames.extensionOf(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP")))
        assertEquals("bmp", AttachmentNames.extensionOf(ascii("BM") + bytes(0, 0)))
        assertEquals("pdf", AttachmentNames.extensionOf(ascii("%PDF-1.7")))
        assertEquals("zip", AttachmentNames.extensionOf(bytes(0x50, 0x4B, 0x03, 0x04)))
        assertEquals("mp4", AttachmentNames.extensionOf(bytes(0, 0, 0, 0x18) + ascii("ftypisom")))
        assertEquals("mp4", AttachmentNames.extensionOf(bytes(0, 0, 0, 0x14) + ascii("ftypqt  ")))
        assertNull(AttachmentNames.extensionOf(ascii("hello")))
        assertNull(AttachmentNames.extensionOf(ByteArray(0)))
    }

    @Test
    fun `默认文件名按类型与时间命名，识别不了用 bin`() {
        assertEquals("image-153012.png", AttachmentNames.fallback(AttachmentKind.IMAGE, LocalTime.of(15, 30, 12), "png"))
        assertEquals("file-090501.bin", AttachmentNames.fallback(AttachmentKind.FILE, LocalTime.of(9, 5, 1), null))
        assertEquals("video-000000.mp4", AttachmentNames.fallback(AttachmentKind.VIDEO, LocalTime.MIDNIGHT, "mp4"))
    }

    @Test
    fun `同名时依次编号`() {
        val taken = setOf("a.png", "a-1.png", "noext")
        assertEquals("b.png", AttachmentNames.unique("b.png") { it in taken })
        assertEquals("a-2.png", AttachmentNames.unique("a.png") { it in taken })
        assertEquals("noext-1", AttachmentNames.unique("noext") { it in taken })
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `timeout 590 ./gradlew test --tests 'com.github.izerui.imux.bridge.core.AttachmentNamesTest'`
Expected: 编译失败，`AttachmentNames`、`AttachmentKind` 未定义。

- [ ] **Step 3: 在 `BridgeModels.kt` 中新增模型**

把 `InboundMessage` 改为（保留原有 KDoc，新增最后一个参数）：

```kotlin
data class InboundMessage(
    val robotId: RobotId,
    /** 平台消息 id，用于抵御平台重投；不能用文本去重。 */
    val externalMessageId: String,
    val senderId: String,
    val chatId: String,
    val text: String,
    /** 渠道只解析不下载；路由器确认本人、非重复、已绑定后才调用 [InboundAttachment.fetch]。 */
    val attachments: List<InboundAttachment> = emptyList(),
)
```

在 `InboundMessage` 之前加入：

```kotlin
enum class AttachmentKind { IMAGE, FILE, VIDEO }

/** 渠道取回并解密后的临时文件；调用方负责移走或删除。[suggestedName] 是平台给的原始文件名，未清理。 */
data class FetchedFile(
    val path: java.nio.file.Path,
    val suggestedName: String?,
)

sealed interface FetchResult {
    data class Ok(val file: FetchedFile) : FetchResult

    /** 超过上限；实现方不得留下临时文件。 */
    data object TooLarge : FetchResult

    data class Failed(val reason: String) : FetchResult
}

/** 入站附件。[fetch] 不得抛出 CancellationException 以外的异常。 */
interface InboundAttachment {
    val kind: AttachmentKind

    suspend fun fetch(maxBytes: Long): FetchResult
}
```

在 `enum class InboundOutcome` 的最后一个取值之后加入 `ATTACHMENT_FAILED,`（附件全部失败且没有文字，什么都没提交）。

- [ ] **Step 4: 新建 `AttachmentNames.kt`**

```kotlin
package com.github.izerui.imux.bridge.core

import java.time.LocalTime

/** 入站附件的命名规则。纯函数，不碰文件系统。 */
object AttachmentNames {
    const val MAX_NAME_LENGTH = 120

    private const val ILLEGAL = "<>:\"|?*"

    /** 去掉路径部分、控制字符与 Windows 非法字符，去掉首尾空白和点；空名返回 null。 */
    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.filterNot { it.isISOControl() || it in ILLEGAL }.trim().trim('.').trim()
        if (cleaned.isEmpty()) return null
        return truncate(cleaned)
    }

    private fun truncate(name: String): String {
        if (name.length <= MAX_NAME_LENGTH) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0) name.substring(dot) else ""
        if (extension.length in 2..11) return name.substring(0, MAX_NAME_LENGTH - extension.length) + extension
        return name.substring(0, MAX_NAME_LENGTH)
    }

    /** 按文件头识别扩展名；识别不了返回 null。[head] 至少给 12 字节才能识别全部类型。 */
    fun extensionOf(head: ByteArray): String? =
        when {
            head.startsWith(0x89, 0x50, 0x4E, 0x47) -> "png"
            head.startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
            head.startsWithAscii("GIF8") -> "gif"
            head.startsWithAscii("RIFF") && head.asciiAt(8, "WEBP") -> "webp"
            head.startsWithAscii("BM") -> "bmp"
            head.startsWithAscii("%PDF") -> "pdf"
            head.startsWith(0x50, 0x4B, 0x03, 0x04) -> "zip"
            head.asciiAt(4, "ftyp") -> "mp4"
            else -> null
        }

    /** 平台没给文件名时的默认名，例如 `image-153012.png`。 */
    fun fallback(
        kind: AttachmentKind,
        time: LocalTime,
        extension: String?,
    ): String = "%s-%02d%02d%02d.%s".format(kind.name.lowercase(), time.hour, time.minute, time.second, extension ?: "bin")

    /** 有同名时依次尝试 `a-1.png`、`a-2.png`。 */
    fun unique(
        name: String,
        taken: (String) -> Boolean,
    ): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        val stem = dot?.let { name.substring(0, it) } ?: name
        val extension = dot?.let { name.substring(it) }.orEmpty()
        var index = 1
        while (true) {
            val candidate = "$stem-$index$extension"
            if (!taken(candidate)) return candidate
            index++
        }
    }

    private fun ByteArray.startsWith(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { (this[it].toInt() and 0xFF) == values[it] }

    private fun ByteArray.startsWithAscii(text: String): Boolean = asciiAt(0, text)

    private fun ByteArray.asciiAt(
        offset: Int,
        text: String,
    ): Boolean = size >= offset + text.length && text.indices.all { this[offset + it] == text[it].code.toByte() }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `timeout 590 ./gradlew test --tests 'com.github.izerui.imux.bridge.core.AttachmentNamesTest'`
Expected: 5 个测试全部 PASS。

- [ ] **Step 6: 运行全量测试**

Run: `timeout 590 ./gradlew test --rerun -q`
Expected: 无失败（`InboundMessage` 新参数有默认值，现有调用不受影响）。

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeModels.kt src/main/kotlin/com/github/izerui/imux/bridge/core/AttachmentNames.kt src/test/kotlin/com/github/izerui/imux/bridge/core/AttachmentNamesTest.kt
git commit -m "新增入站附件模型与附件命名规则

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 附件存放（AttachmentStore 与 ProjectAttachmentStore）

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgePorts.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/terminal/ProjectAttachmentStore.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/terminal/ProjectAttachmentStoreTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `AttachmentKind`、`FetchedFile`、`FetchResult`、`InboundAttachment`、`AttachmentNames`；已有 `SessionTarget(projectPath, tabId, agentType, sessionId)`。
- Produces:
  - `sealed interface PlaceResult { data class Saved(val relativePath: String); data object TooLarge; data class Failed(val reason: String) }`
  - `fun interface AttachmentStore { suspend fun place(target: SessionTarget, attachment: InboundAttachment): PlaceResult; companion object { const val MAX_BYTES: Long = 100L * 1024 * 1024; val NONE: AttachmentStore } }`
  - `class ProjectAttachmentStore(clock: () -> LocalDateTime = LocalDateTime::now, keepDays: Long = 7) : AttachmentStore`

- [ ] **Step 1: 写失败测试**

`src/test/kotlin/com/github/izerui/imux/bridge/terminal/ProjectAttachmentStoreTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import com.github.izerui.imux.bridge.core.InboundAttachment
import com.github.izerui.imux.bridge.core.PlaceResult
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.model.AgentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

class ProjectAttachmentStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val now = LocalDateTime.of(2026, 10, 27, 15, 30, 12)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)

    private fun project(git: Boolean = true): Path {
        val root = temp.newFolder().toPath()
        if (git) Files.createDirectories(root.resolve(".git"))
        return root
    }

    private fun target(root: Path) = SessionTarget(root.toString(), "tab-1", AgentType.CLAUDE, "s1")

    private inner class FakeAttachment(
        private val content: ByteArray,
        private val name: String?,
        override val kind: AttachmentKind = AttachmentKind.IMAGE,
        private val result: FetchResult? = null,
    ) : InboundAttachment {
        var fetched = 0

        override suspend fun fetch(maxBytes: Long): FetchResult {
            fetched++
            result?.let { return it }
            val file = temp.newFile().toPath()
            Files.write(file, content)
            return FetchResult.Ok(FetchedFile(file, name))
        }
    }

    private fun store() = ProjectAttachmentStore(clock = { now })

    @Test
    fun `没有文件名时按类型、时间与文件头命名，存到日期目录并返回相对路径`() =
        runBlocking {
            val root = project()
            val result = store().place(target(root), FakeAttachment(png, null))
            assertEquals(PlaceResult.Saved(".imux/attachments/2026-10-27/image-153012.png"), result)
            assertTrue(Files.exists(root.resolve(".imux/attachments/2026-10-27/image-153012.png")))
        }

    @Test
    fun `有文件名时清理后使用，没有扩展名时补上识别出的扩展名，同名编号`() =
        runBlocking {
            val root = project()
            val s = store()
            assertEquals(PlaceResult.Saved(".imux/attachments/2026-10-27/报告.pdf"), s.place(target(root), FakeAttachment("%PDF-1".toByteArray(), "../报告.pdf", AttachmentKind.FILE)))
            assertEquals(PlaceResult.Saved(".imux/attachments/2026-10-27/报告-1.pdf"), s.place(target(root), FakeAttachment("%PDF-1".toByteArray(), "报告.pdf", AttachmentKind.FILE)))
            assertEquals(PlaceResult.Saved(".imux/attachments/2026-10-27/shot.png"), s.place(target(root), FakeAttachment(png, "shot")))
        }

    @Test
    fun `git 仓库只追加一次 exclude，非 git 仓库跳过`() =
        runBlocking {
            val root = project()
            Files.createDirectories(root.resolve(".git/info"))
            Files.writeString(root.resolve(".git/info/exclude"), "# local\n*.tmp")
            store().place(target(root), FakeAttachment(png, null))
            store().place(target(root), FakeAttachment(png, null))
            assertEquals(listOf("# local", "*.tmp", ".imux/"), Files.readAllLines(root.resolve(".git/info/exclude")))

            val plain = project(git = false)
            store().place(target(plain), FakeAttachment(png, null))
            assertFalse(Files.exists(plain.resolve(".git")))
        }

    @Test
    fun `删除早于 7 天的日期目录，保留其它目录`() =
        runBlocking {
            val root = project()
            val base = root.resolve(".imux/attachments")
            listOf("2026-10-19", "2026-10-20", "2026-10-26", "notes").forEach { Files.createDirectories(base.resolve(it)) }
            Files.writeString(base.resolve("2026-10-19/old.txt"), "x")
            store().place(target(root), FakeAttachment(png, null))
            assertFalse(Files.exists(base.resolve("2026-10-19")))
            assertTrue(Files.exists(base.resolve("2026-10-20")))
            assertTrue(Files.exists(base.resolve("2026-10-26")))
            assertTrue(Files.exists(base.resolve("notes")))
        }

    @Test
    fun `取回过大或失败时原样返回，不创建目录`() =
        runBlocking {
            val root = project()
            assertEquals(PlaceResult.TooLarge, store().place(target(root), FakeAttachment(png, null, result = FetchResult.TooLarge)))
            assertEquals(PlaceResult.Failed("boom"), store().place(target(root), FakeAttachment(png, null, result = FetchResult.Failed("boom"))))
            assertFalse(Files.exists(root.resolve(".imux")))
        }

    @Test
    fun `取回时传入 100 MB 上限`() =
        runBlocking {
            var limit = 0L
            val attachment =
                object : InboundAttachment {
                    override val kind = AttachmentKind.FILE

                    override suspend fun fetch(maxBytes: Long): FetchResult {
                        limit = maxBytes
                        return FetchResult.Failed("x")
                    }
                }
            store().place(target(project()), attachment)
            assertEquals(100L * 1024 * 1024, limit)
        }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `timeout 590 ./gradlew test --tests 'com.github.izerui.imux.bridge.terminal.ProjectAttachmentStoreTest'`
Expected: 编译失败，`PlaceResult`、`ProjectAttachmentStore` 未定义。

- [ ] **Step 3: 在 `BridgePorts.kt` 末尾加入端口**

```kotlin
sealed interface PlaceResult {
    /** [relativePath] 相对项目根，分隔符为 `/`。 */
    data class Saved(val relativePath: String) : PlaceResult

    data object TooLarge : PlaceResult

    data class Failed(val reason: String) : PlaceResult
}

/** 把入站附件放到目标会话能读到的位置。不得抛出 CancellationException 以外的异常。 */
fun interface AttachmentStore {
    suspend fun place(
        target: SessionTarget,
        attachment: InboundAttachment,
    ): PlaceResult

    companion object {
        const val MAX_BYTES: Long = 100L * 1024 * 1024

        /** 不支持附件时的默认实现。 */
        val NONE = AttachmentStore { _, _ -> PlaceResult.Failed("unsupported") }
    }
}
```

- [ ] **Step 4: 新建 `ProjectAttachmentStore.kt`**

```kotlin
package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.AttachmentNames
import com.github.izerui.imux.bridge.core.AttachmentStore
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import com.github.izerui.imux.bridge.core.InboundAttachment
import com.github.izerui.imux.bridge.core.PlaceResult
import com.github.izerui.imux.bridge.core.SessionTarget
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * 把附件存到绑定会话所在项目的 `.imux/attachments/<日期>/`：Agent 读取项目内文件不会触发权限确认。
 * 顺带把 `.imux/` 写进本地 `.git/info/exclude`，并清理过期的日期目录。
 */
class ProjectAttachmentStore(
    private val clock: () -> LocalDateTime = LocalDateTime::now,
    private val keepDays: Long = 7,
) : AttachmentStore {
    override suspend fun place(
        target: SessionTarget,
        attachment: InboundAttachment,
    ): PlaceResult =
        when (val fetched = attachment.fetch(AttachmentStore.MAX_BYTES)) {
            FetchResult.TooLarge -> PlaceResult.TooLarge
            is FetchResult.Failed -> PlaceResult.Failed(fetched.reason)
            is FetchResult.Ok ->
                withContext(Dispatchers.IO) {
                    try {
                        save(Path.of(target.projectPath), attachment.kind, fetched.file)
                    } catch (e: IOException) {
                        Files.deleteIfExists(fetched.file.path)
                        LOG.warn("保存附件失败：${e.javaClass.simpleName}")
                        PlaceResult.Failed("save")
                    }
                }
        }

    private fun save(
        root: Path,
        kind: AttachmentKind,
        file: FetchedFile,
    ): PlaceResult.Saved {
        val now = clock()
        val base = root.resolve(".imux").resolve("attachments")
        val dir = base.resolve(now.toLocalDate().toString())
        Files.createDirectories(dir)
        val sniffed = AttachmentNames.extensionOf(readHead(file.path))
        val name =
            AttachmentNames.sanitize(file.suggestedName)?.let { if ('.' !in it && sniffed != null) "$it.$sniffed" else it }
                ?: AttachmentNames.fallback(kind, now.toLocalTime(), sniffed)
        val dest = dir.resolve(AttachmentNames.unique(name) { Files.exists(dir.resolve(it)) })
        Files.move(file.path, dest)
        ensureGitExclude(root)
        cleanup(base, now.toLocalDate())
        return PlaceResult.Saved(root.relativize(dest).joinToString("/"))
    }

    private fun readHead(path: Path): ByteArray =
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(HEAD_BYTES)
            val read = input.readNBytes(buffer, 0, HEAD_BYTES)
            buffer.copyOf(read)
        }

    private fun ensureGitExclude(root: Path) {
        val git = root.resolve(".git")
        if (!Files.isDirectory(git)) return
        val exclude = git.resolve("info").resolve("exclude")
        val lines = if (Files.exists(exclude)) Files.readAllLines(exclude) else emptyList()
        if (lines.any { it.trim() == EXCLUDE_LINE }) return
        Files.createDirectories(exclude.parent)
        val prefix = if (lines.isNotEmpty() && !Files.readString(exclude).endsWith("\n")) "\n" else ""
        Files.writeString(exclude, "$prefix$EXCLUDE_LINE\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    /** 尽力清理：失败只记日志，不影响本次保存。 */
    private fun cleanup(
        base: Path,
        today: LocalDate,
    ) {
        val cutoff = today.minusDays(keepDays)
        try {
            Files.list(base).use { entries ->
                entries.filter(Files::isDirectory).forEach { dir ->
                    val date =
                        try {
                            LocalDate.parse(dir.fileName.toString())
                        } catch (_: DateTimeParseException) {
                            null
                        }
                    if (date != null && date.isBefore(cutoff)) deleteRecursively(dir)
                }
            }
        } catch (e: IOException) {
            LOG.warn("清理过期附件失败：${e.javaClass.simpleName}")
        }
    }

    private fun deleteRecursively(dir: Path) {
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private companion object {
        const val HEAD_BYTES = 16
        const val EXCLUDE_LINE = ".imux/"
        val LOG = logger<ProjectAttachmentStore>()
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run: `timeout 590 ./gradlew test --tests 'com.github.izerui.imux.bridge.terminal.ProjectAttachmentStoreTest'`
Expected: 6 个测试全部 PASS。

- [ ] **Step 6: 运行全量测试**

Run: `timeout 590 ./gradlew test --rerun -q`
Expected: 无失败（含 `BridgeIsolationSourceTest`：`BridgePorts.kt` 只引用 core 类型）。

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/core/BridgePorts.kt src/main/kotlin/com/github/izerui/imux/bridge/terminal/ProjectAttachmentStore.kt src/test/kotlin/com/github/izerui/imux/bridge/terminal/ProjectAttachmentStoreTest.kt
git commit -m "新增附件存放端口与项目目录实现：日期目录、同名编号、本地 exclude、过期清理

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 路由器接入附件与文案

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgePorts.kt`（`BridgeTexts`）
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeRouter.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/BundleBridgeTexts.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/BridgeHub.kt`
- Modify: 10 个 `src/main/resources/messages/ImuxBundle*.properties`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/core/BridgeRouterTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `InboundAttachment`、`InboundMessage.attachments`、`InboundOutcome.ATTACHMENT_FAILED`；Task 2 的 `AttachmentStore`、`PlaceResult`、`ProjectAttachmentStore`。
- Produces:
  - `BridgeTexts` 新增：`fun attachmentLine(path: String): String`、`fun attachmentsOnly(paths: List<String>): String`、`fun attachmentFailed(): String`、`fun attachmentTooLarge(): String`
  - `BridgeRouter(..., onDelivery = ..., attachmentStore: AttachmentStore = AttachmentStore.NONE, notReadyRetryDelayMs = 300)`

- [ ] **Step 1: 写失败测试**

在 `BridgeRouterTest` 的 `Texts` 对象中加入：

```kotlin
        override fun attachmentLine(path: String) = "ATT($path)"

        override fun attachmentsOnly(paths: List<String>) = "ONLY(${paths.joinToString(",")})"

        override fun attachmentFailed() = "ATT_FAILED"

        override fun attachmentTooLarge() = "ATT_TOO_LARGE"
```

在类中（`Texts` 之后）加入假实现与辅助函数：

```kotlin
    private class FakeAttachment(override val kind: AttachmentKind = AttachmentKind.IMAGE) : InboundAttachment {
        override suspend fun fetch(maxBytes: Long): FetchResult = error("路由器不应直接调用 fetch")
    }

    /** 按附件在列表中的顺序返回预设结果，并记录调用。 */
    private class FakeStore(private val results: List<PlaceResult>) : AttachmentStore {
        val placed = mutableListOf<InboundAttachment>()

        override suspend fun place(
            target: SessionTarget,
            attachment: InboundAttachment,
        ): PlaceResult {
            placed += attachment
            return results[placed.size - 1]
        }
    }

    private fun routerWith(store: AttachmentStore) =
        BridgeRouter(
            registry,
            { if (it == robot) owner else null },
            port,
            { if (it == robot) channel else null },
            Texts,
            attachmentStore = store,
            notReadyRetryDelayMs = 0,
        )

    private fun inboundWith(
        id: String,
        text: String,
        vararg attachments: InboundAttachment,
        sender: String = owner.senderId,
    ) = InboundMessage(robot, id, sender, owner.chatId, text, attachments.toList())
```

新增测试：

```kotlin
    @Test
    fun `非本人、重复或未绑定时不取回附件`() =
        runBlocking {
            val store = FakeStore(listOf(PlaceResult.Saved("p.png"), PlaceResult.Saved("p.png")))
            val r = routerWith(store)
            assertEquals(InboundOutcome.UNAUTHORIZED, r.onInbound(inboundWith("1", "", FakeAttachment(), sender = "stranger")))
            assertEquals(InboundOutcome.UNBOUND, r.onInbound(inboundWith("2", "", FakeAttachment())))
            registry.bind(robot, target)
            r.onInbound(inboundWith("3", "", FakeAttachment()))
            assertEquals(InboundOutcome.DUPLICATE, r.onInbound(inboundWith("3", "", FakeAttachment())))
            assertEquals(1, store.placed.size)
        }

    @Test
    fun `文字加附件时提交文字与附件行`() =
        runBlocking {
            registry.bind(robot, target)
            val r = routerWith(FakeStore(listOf(PlaceResult.Saved(".imux/a.png"), PlaceResult.Saved(".imux/b.pdf"))))
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inboundWith("1", " 看看 ", FakeAttachment(), FakeAttachment(AttachmentKind.FILE))))
            assertEquals(listOf("tab-1" to "看看\n\nATT(.imux/a.png)\nATT(.imux/b.pdf)"), port.submitted)
            assertTrue(channel.sent.isEmpty())
        }

    @Test
    fun `只有附件时用附件提示提交`() =
        runBlocking {
            registry.bind(robot, target)
            val r = routerWith(FakeStore(listOf(PlaceResult.Saved(".imux/a.png"))))
            r.onInbound(inboundWith("1", "", FakeAttachment()))
            assertEquals(listOf("tab-1" to "ONLY(.imux/a.png)"), port.submitted)
        }

    @Test
    fun `部分附件失败时回复一次提示，成功的照常提交`() =
        runBlocking {
            registry.bind(robot, target)
            val r = routerWith(FakeStore(listOf(PlaceResult.Failed("x"), PlaceResult.Saved(".imux/b.png"), PlaceResult.Failed("y"))))
            r.onInbound(inboundWith("1", "", FakeAttachment(), FakeAttachment(), FakeAttachment()))
            assertEquals(listOf("ATT_FAILED"), sentTexts())
            assertEquals(listOf("tab-1" to "ONLY(.imux/b.png)"), port.submitted)
        }

    @Test
    fun `附件全部失败但有文字时，回复提示并照常提交文字`() =
        runBlocking {
            registry.bind(robot, target)
            val r = routerWith(FakeStore(listOf(PlaceResult.TooLarge)))
            assertEquals(InboundOutcome.SUBMITTED_TO_TERMINAL, r.onInbound(inboundWith("1", "看看", FakeAttachment(AttachmentKind.VIDEO))))
            assertEquals(listOf("ATT_TOO_LARGE"), sentTexts())
            assertEquals(listOf("tab-1" to "看看"), port.submitted)
        }

    @Test
    fun `附件全部失败且没有文字时不提交`() =
        runBlocking {
            registry.bind(robot, target)
            val r = routerWith(FakeStore(listOf(PlaceResult.Failed("x"), PlaceResult.TooLarge)))
            assertEquals(InboundOutcome.ATTACHMENT_FAILED, r.onInbound(inboundWith("1", "", FakeAttachment(), FakeAttachment())))
            assertEquals(listOf("ATT_TOO_LARGE", "ATT_FAILED"), sentTexts())
            assertTrue(port.submitted.isEmpty())
        }

    @Test
    fun `只有空白文字且没有附件仍按空输入处理`() =
        runBlocking {
            registry.bind(robot, target)
            assertEquals(InboundOutcome.EMPTY, routerWith(FakeStore(emptyList())).onInbound(inboundWith("1", "  ")))
        }
```

在测试文件 import 中补充 `AttachmentKind`、`AttachmentStore`、`FetchResult`、`InboundAttachment`、`PlaceResult`（都在 `com.github.izerui.imux.bridge.core` 包内，与测试同包，无需 import）。确认 `sentTexts()`、`port`、`channel`、`target` 已存在于该测试类（现有辅助）。

- [ ] **Step 2: 运行测试，确认失败**

Run: `timeout 590 ./gradlew test --tests 'com.github.izerui.imux.bridge.core.BridgeRouterTest'`
Expected: 编译失败，`BridgeTexts` 没有 `attachmentLine` 等方法、`BridgeRouter` 没有 `attachmentStore` 参数。

- [ ] **Step 3: 扩展 `BridgeTexts`**

在 `BridgePorts.kt` 的 `interface BridgeTexts` 中 `fun pairingLocked(): String` 之后加入：

```kotlin

    /** 提交给终端的附件行，例如 `[附件] .imux/attachments/2026-10-27/a.png`。 */
    fun attachmentLine(path: String): String

    /** 只有附件、没有文字时提交给终端的内容。 */
    fun attachmentsOnly(paths: List<String>): String

    fun attachmentFailed(): String

    fun attachmentTooLarge(): String
```

- [ ] **Step 4: 修改 `BridgeRouter`**

构造参数：在 `onDelivery` 之后、`notReadyRetryDelayMs` 之前加入

```kotlin
    /** 入站附件落盘；默认不支持附件。 */
    private val attachmentStore: AttachmentStore = AttachmentStore.NONE,
```

`onInbound` 中把

```kotlin
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
```

替换为

```kotlin
        val typed = message.text.trim()
        if (typed.isEmpty() && message.attachments.isEmpty()) {
            reply(message.robotId, owner, texts.emptyInput())
            return InboundOutcome.EMPTY
        }
        val binding = bindings.bindingOf(message.robotId)
        if (binding == null) {
            reply(message.robotId, owner, texts.unbound())
            return InboundOutcome.UNBOUND
        }
        // 确认本人、非重复、已绑定后才取回附件；放在提交锁之外，下载不占用提交顺序
        val composed =
            if (message.attachments.isEmpty()) {
                typed
            } else {
                try {
                    withAttachments(message.robotId, owner, binding.target, typed, message.attachments)
                } catch (e: CancellationException) {
                    unmarkSeen(message.robotId, message.externalMessageId)
                    throw e
                }
            }
        val text = composed ?: return InboundOutcome.ATTACHMENT_FAILED
```

（`onInbound` 后续代码继续使用变量名 `text`，无需再改。）

在 `onInbound` 之后加入：

```kotlin
    /** 取回并落盘全部附件，拼装提交文字；附件全部失败且没有文字时返回 null。失败提示各回复一次。 */
    private suspend fun withAttachments(
        robotId: RobotId,
        owner: RobotOwner,
        target: SessionTarget,
        typed: String,
        attachments: List<InboundAttachment>,
    ): String? {
        val saved = mutableListOf<String>()
        var tooLarge = false
        var failed = false
        for (attachment in attachments) {
            val result =
                try {
                    attachmentStore.place(target, attachment)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PlaceResult.Failed(e.javaClass.simpleName)
                }
            when (result) {
                is PlaceResult.Saved -> saved += result.relativePath
                PlaceResult.TooLarge -> tooLarge = true
                is PlaceResult.Failed -> failed = true
            }
        }
        if (tooLarge) reply(robotId, owner, texts.attachmentTooLarge())
        if (failed) reply(robotId, owner, texts.attachmentFailed())
        return when {
            saved.isEmpty() -> typed.ifEmpty { null }
            typed.isEmpty() -> texts.attachmentsOnly(saved)
            else -> typed + "\n\n" + saved.joinToString("\n") { texts.attachmentLine(it) }
        }
    }
```

确认 `kotlin.coroutines.cancellation.CancellationException` 已在文件 import 中（现有代码已使用）。

- [ ] **Step 5: 实现文案**

`BundleBridgeTexts.kt` 中 `override fun pairingLocked()` 之后加入：

```kotlin

    override fun attachmentLine(path: String) = ImuxBundle.message("bridge.attachment.line", path)

    override fun attachmentsOnly(paths: List<String>) =
        if (paths.size == 1) {
            ImuxBundle.message("bridge.attachment.only", paths.single())
        } else {
            ImuxBundle.message("bridge.attachment.onlyMany") + "\n" + paths.joinToString("\n") { attachmentLine(it) }
        }

    override fun attachmentFailed() = ImuxBundle.message("bridge.reply.attachmentFailed")

    override fun attachmentTooLarge() = ImuxBundle.message("bridge.reply.attachmentTooLarge")
```

在 10 个资源文件中，各自 `bridge.reply.emptyInput=` 那一行之后插入 5 行（值原样使用）：

| 文件 | 内容 |
|---|---|
| `ImuxBundle.properties` | `bridge.attachment.line=[Attachment] {0}`<br>`bridge.attachment.only=Please look at this attachment: {0}`<br>`bridge.attachment.onlyMany=Please look at these attachments:`<br>`bridge.reply.attachmentFailed=Couldn't receive the attachment. Please send it again.`<br>`bridge.reply.attachmentTooLarge=The attachment is too large (limit 100 MB) and wasn't received.` |
| `_zh_CN` | `bridge.attachment.line=[附件] {0}`<br>`bridge.attachment.only=请查看这个附件：{0}`<br>`bridge.attachment.onlyMany=请查看这些附件：`<br>`bridge.reply.attachmentFailed=附件接收失败，请重新发送。`<br>`bridge.reply.attachmentTooLarge=附件过大（上限 100 MB），未能接收。` |
| `_zh_TW` | `bridge.attachment.line=[附件] {0}`<br>`bridge.attachment.only=請查看這個附件：{0}`<br>`bridge.attachment.onlyMany=請查看這些附件：`<br>`bridge.reply.attachmentFailed=附件接收失敗，請重新傳送。`<br>`bridge.reply.attachmentTooLarge=附件過大（上限 100 MB），未能接收。` |
| `_ja` | `bridge.attachment.line=[添付] {0}`<br>`bridge.attachment.only=この添付ファイルを確認してください：{0}`<br>`bridge.attachment.onlyMany=これらの添付ファイルを確認してください：`<br>`bridge.reply.attachmentFailed=添付ファイルを受信できませんでした。もう一度送信してください。`<br>`bridge.reply.attachmentTooLarge=添付ファイルが大きすぎるため（上限 100 MB）受信できませんでした。` |
| `_ko` | `bridge.attachment.line=[첨부] {0}`<br>`bridge.attachment.only=이 첨부 파일을 확인해 주세요: {0}`<br>`bridge.attachment.onlyMany=다음 첨부 파일을 확인해 주세요:`<br>`bridge.reply.attachmentFailed=첨부 파일을 받지 못했습니다. 다시 보내 주세요.`<br>`bridge.reply.attachmentTooLarge=첨부 파일이 너무 커서(최대 100 MB) 받지 못했습니다.` |
| `_de` | `bridge.attachment.line=[Anhang] {0}`<br>`bridge.attachment.only=Bitte sieh dir diesen Anhang an: {0}`<br>`bridge.attachment.onlyMany=Bitte sieh dir diese Anhänge an:`<br>`bridge.reply.attachmentFailed=Der Anhang konnte nicht empfangen werden. Bitte erneut senden.`<br>`bridge.reply.attachmentTooLarge=Der Anhang ist zu groß (maximal 100 MB) und wurde nicht empfangen.` |
| `_es` | `bridge.attachment.line=[Adjunto] {0}`<br>`bridge.attachment.only=Revisa este adjunto: {0}`<br>`bridge.attachment.onlyMany=Revisa estos adjuntos:`<br>`bridge.reply.attachmentFailed=No se pudo recibir el adjunto. Vuelve a enviarlo.`<br>`bridge.reply.attachmentTooLarge=El adjunto es demasiado grande (límite 100 MB) y no se recibió.` |
| `_fr` | `bridge.attachment.line=[Pièce jointe] {0}`<br>`bridge.attachment.only=Consultez cette pièce jointe : {0}`<br>`bridge.attachment.onlyMany=Consultez ces pièces jointes :`<br>`bridge.reply.attachmentFailed=Impossible de recevoir la pièce jointe. Veuillez la renvoyer.`<br>`bridge.reply.attachmentTooLarge=La pièce jointe est trop volumineuse (limite 100 Mo) et n’a pas été reçue.` |
| `_pt_BR` | `bridge.attachment.line=[Anexo] {0}`<br>`bridge.attachment.only=Veja este anexo: {0}`<br>`bridge.attachment.onlyMany=Veja estes anexos:`<br>`bridge.reply.attachmentFailed=Não foi possível receber o anexo. Envie novamente.`<br>`bridge.reply.attachmentTooLarge=O anexo é grande demais (limite de 100 MB) e não foi recebido.` |
| `_ru` | `bridge.attachment.line=[Вложение] {0}`<br>`bridge.attachment.only=Посмотрите это вложение: {0}`<br>`bridge.attachment.onlyMany=Посмотрите эти вложения:`<br>`bridge.reply.attachmentFailed=Не удалось получить вложение. Отправьте его ещё раз.`<br>`bridge.reply.attachmentTooLarge=Вложение слишком большое (не более 100 МБ) и не было получено.` |

- [ ] **Step 6: 中枢注入实现**

`BridgeHub.kt` 中把

```kotlin
        BridgeRouter(bindings, owners::get, ImuxTerminalSessionPort(), channels::get, BundleBridgeTexts, onDelivery = ::logDelivery)
```

改为

```kotlin
        BridgeRouter(
            bindings,
            owners::get,
            ImuxTerminalSessionPort(),
            channels::get,
            BundleBridgeTexts,
            onDelivery = ::logDelivery,
            attachmentStore = ProjectAttachmentStore(),
        )
```

并加入 `import com.github.izerui.imux.bridge.terminal.ProjectAttachmentStore`。

- [ ] **Step 7: 运行测试，确认通过**

Run: `timeout 590 ./gradlew test --tests 'com.github.izerui.imux.bridge.core.BridgeRouterTest'`
Expected: 全部 PASS（含新增 7 个）。

- [ ] **Step 8: 运行全量测试**

Run: `timeout 590 ./gradlew test --rerun -q`
Expected: 无失败（包括资源包一致性测试 `ImuxBundleTest`：10 个文件键集合一致）。

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/core/BridgePorts.kt src/main/kotlin/com/github/izerui/imux/bridge/core/BridgeRouter.kt src/main/kotlin/com/github/izerui/imux/bridge/BundleBridgeTexts.kt src/main/kotlin/com/github/izerui/imux/bridge/BridgeHub.kt src/main/resources/messages src/test/kotlin/com/github/izerui/imux/bridge/core/BridgeRouterTest.kt
git commit -m "路由器在确认本人、非重复、已绑定后取回附件，并把附件路径随文字提交给终端

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: HTTP 文件下载

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/channel/Transports.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/HttpFileDownloadTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface DownloadResult { data class Ok(val path: Path, val fileName: String?); data object TooLarge; data class Failed(val reason: String) }`
  - `fun interface HttpFileDownload { suspend fun download(url: String, maxBytes: Long): DownloadResult }`
  - `class JdkHttpFileDownload(client: HttpClient = …, timeout: Duration = Duration.ofSeconds(60), tempDir: Path? = null) : HttpFileDownload`
  - `fun contentDispositionFileName(header: String?): String?`

- [ ] **Step 1: 写失败测试**

`src/test/kotlin/com/github/izerui/imux/bridge/channel/HttpFileDownloadTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.file.Files

class HttpFileDownloadTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private var started = false

    @After
    fun tearDown() = server.stop(0)

    private fun serve(
        path: String,
        body: ByteArray,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
        chunked: Boolean = false,
    ) {
        server.createContext(path) { exchange ->
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.sendResponseHeaders(status, if (chunked) 0 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    private fun url(path: String): String {
        if (!started) {
            server.start()
            started = true
        }
        return "http://127.0.0.1:${server.address.port}$path"
    }

    @Test
    fun `解析两种 Content-Disposition 文件名`() {
        assertEquals("报告.pdf", contentDispositionFileName("attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf"))
        assertEquals("a b.png", contentDispositionFileName("attachment; filename=\"a b.png\""))
        assertEquals("x.txt", contentDispositionFileName("inline; filename=x.txt"))
        assertEquals("报告.pdf", contentDispositionFileName("attachment; filename=\"fallback.pdf\"; filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf"))
        assertNull(contentDispositionFileName("attachment"))
        assertNull(contentDispositionFileName(null))
    }

    @Test
    fun `下载到临时文件并带回文件名`() =
        runBlocking {
            val body = ByteArray(200_000) { (it % 251).toByte() }
            serve("/f", body, headers = mapOf("Content-Disposition" to "attachment; filename=\"a.bin\""))
            val result = JdkHttpFileDownload().download(url("/f"), 1_000_000) as DownloadResult.Ok
            assertArrayEquals(body, Files.readAllBytes(result.path))
            assertEquals("a.bin", result.fileName)
            Files.delete(result.path)
        }

    @Test
    fun `声明长度或实际长度超过上限时返回过大且不留临时文件`() =
        runBlocking {
            serve("/declared", ByteArray(2_000))
            serve("/chunked", ByteArray(2_000), chunked = true)
            val dir = temp.newFolder().toPath()
            val download = JdkHttpFileDownload(tempDir = dir)
            assertEquals(DownloadResult.TooLarge, download.download(url("/declared"), 1_000))
            assertEquals(DownloadResult.TooLarge, download.download(url("/chunked"), 1_000))
            assertEquals("不应残留临时文件", 0L, Files.list(dir).use { it.count() })
        }

    @Test
    fun `非 2xx 返回失败`() =
        runBlocking {
            serve("/missing", ByteArray(0), status = 404)
            val result = JdkHttpFileDownload().download(url("/missing"), 1_000)
            assertEquals(DownloadResult.Failed("HTTP 404"), result)
        }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `timeout 590 ./gradlew test --tests 'com.github.izerui.imux.bridge.channel.HttpFileDownloadTest'`
Expected: 编译失败，`JdkHttpFileDownload`、`contentDispositionFileName` 未定义。

- [ ] **Step 3: 在 `Transports.kt` 末尾实现**

```kotlin
sealed interface DownloadResult {
    data class Ok(
        val path: Path,
        /** 响应头 Content-Disposition 里的文件名，未清理。 */
        val fileName: String?,
    ) : DownloadResult

    data object TooLarge : DownloadResult

    data class Failed(val reason: String) : DownloadResult
}

/** 流式下载到临时文件。超过上限中止并删除临时文件。不得抛出 CancellationException 以外的异常。 */
fun interface HttpFileDownload {
    suspend fun download(
        url: String,
        maxBytes: Long,
    ): DownloadResult
}

/** 不显式设代理：JDK 走默认 ProxySelector，IDE 中即 IDE 的代理设置。 */
class JdkHttpFileDownload(
    private val client: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build(),
    private val timeout: Duration = Duration.ofSeconds(60),
    /** 临时文件目录；null 用系统临时目录。测试传入独立目录以检查残留。 */
    private val tempDir: Path? = null,
) : HttpFileDownload {
    override suspend fun download(
        url: String,
        maxBytes: Long,
    ): DownloadResult =
        withContext(Dispatchers.IO) {
            val response =
                try {
                    val request = HttpRequest.newBuilder(URI(url)).timeout(timeout).GET().build()
                    client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@withContext DownloadResult.Failed(e.javaClass.simpleName)
                }
            response.body().use { input ->
                if (response.statusCode() !in 200..299) return@withContext DownloadResult.Failed("HTTP ${response.statusCode()}")
                if (response.headers().firstValueAsLong("Content-Length").orElse(-1) > maxBytes) return@withContext DownloadResult.TooLarge
                val file = if (tempDir != null) Files.createTempFile(tempDir, "imux-", ".part") else Files.createTempFile("imux-", ".part")
                try {
                    var total = 0L
                    var tooLarge = false
                    Files.newOutputStream(file).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxBytes) {
                                tooLarge = true
                                break
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                    if (tooLarge) {
                        Files.deleteIfExists(file)
                        return@withContext DownloadResult.TooLarge
                    }
                    DownloadResult.Ok(file, contentDispositionFileName(response.headers().firstValue("Content-Disposition").orElse(null)))
                } catch (e: CancellationException) {
                    Files.deleteIfExists(file)
                    throw e
                } catch (e: IOException) {
                    Files.deleteIfExists(file)
                    DownloadResult.Failed(e.javaClass.simpleName)
                }
            }
        }
}

/** 取 Content-Disposition 里的文件名：优先 RFC 5987 的 `filename*=charset''百分号编码`，其次 `filename=`。 */
fun contentDispositionFileName(header: String?): String? {
    if (header == null) return null
    val parts = header.split(';').map(String::trim)
    parts.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }?.let { part ->
        val value = part.substringAfter('=').trim().removeSurrounding("\"")
        val charset = value.substringBefore("''", "UTF-8").ifEmpty { "UTF-8" }
        val encoded = value.substringAfter("''", value)
        runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), charset) }.getOrNull()?.takeIf(String::isNotBlank)?.let { return it }
    }
    return parts.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim()
        ?.removeSurrounding("\"")
        ?.takeIf(String::isNotBlank)
}
```

补充 import：`kotlinx.coroutines.Dispatchers`、`kotlinx.coroutines.ensureActive`、`kotlinx.coroutines.withContext`、`java.net.URLDecoder`、`java.nio.file.Files`、`java.nio.file.Path`、`kotlin.coroutines.cancellation.CancellationException`（`await`、`URI`、`HttpClient`、`HttpRequest`、`HttpResponse`、`Duration`、`IOException` 已有）。`ensureActive()` 在 `withContext` 的协程作用域内调用。

- [ ] **Step 4: 运行测试，确认通过**

Run: `timeout 590 ./gradlew test --tests 'com.github.izerui.imux.bridge.channel.HttpFileDownloadTest'`
Expected: 4 个测试全部 PASS。

- [ ] **Step 5: 运行全量测试**

Run: `timeout 590 ./gradlew test --rerun -q`
Expected: 无失败（含 `ChannelIsolationSourceTest`）。

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/channel/Transports.kt src/test/kotlin/com/github/izerui/imux/bridge/channel/HttpFileDownloadTest.kt
git commit -m "新增流式文件下载：60 秒超时、超过上限中止、解析 Content-Disposition 文件名

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 企业微信解密与取回

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomMedia.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomMediaTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `AttachmentKind`、`FetchedFile`、`FetchResult`、`InboundAttachment`；Task 4 的 `HttpFileDownload`、`DownloadResult`。
- Produces:
  - `object WecomMedia { fun decryptFile(source: Path, target: Path, aesKeyBase64: String) }`
  - `class WecomAttachment(override val kind: AttachmentKind, private val url: String, private val aesKey: String, private val download: HttpFileDownload) : InboundAttachment`

- [ ] **Step 1: 写失败测试**

`src/test/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomMediaTest.kt`：

```kotlin
package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class WecomMediaTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val key = ByteArray(32).also { Random(1).nextBytes(it) }

    /** 企业微信下发的 aeskey 常省略结尾的 `=`。 */
    private val keyText = Base64.getEncoder().withoutPadding().encodeToString(key)

    /** 按官方规则加密：PKCS#7 填充到 32 字节倍数，AES-256-CBC，IV 为密钥前 16 字节。 */
    private fun encrypt(plain: ByteArray): ByteArray {
        val pad = 32 - plain.size % 32
        val padded = plain + ByteArray(pad) { pad.toByte() }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key, 0, 16))
        return cipher.doFinal(padded)
    }

    private fun file(bytes: ByteArray): Path = temp.newFile().toPath().also { Files.write(it, bytes) }

    @Test
    fun `按 32 字节填充规则解回原文`() {
        listOf(0, 1, 31, 32, 33, 200_003).forEach { size ->
            val plain = ByteArray(size) { (it * 7).toByte() }
            val target = temp.newFile().toPath()
            WecomMedia.decryptFile(file(encrypt(plain)), target, keyText)
            assertArrayEquals("size=$size", plain, Files.readAllBytes(target))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `填充不合法时报错`() {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key, 0, 16))
        val bad = cipher.doFinal(ByteArray(32) { 0 })
        WecomMedia.decryptFile(file(bad), temp.newFile().toPath(), keyText)
    }

    private class FakeDownload(private val result: () -> DownloadResult) : HttpFileDownload {
        var maxBytes = 0L

        override suspend fun download(
            url: String,
            maxBytes: Long,
        ): DownloadResult {
            this.maxBytes = maxBytes
            return result()
        }
    }

    @Test
    fun `取回：下载、解密、删除密文，带回文件名`() =
        runBlocking {
            val plain = "hello".toByteArray()
            val encrypted = file(encrypt(plain))
            val download = FakeDownload { DownloadResult.Ok(encrypted, "a.txt") }
            val result = WecomAttachment(AttachmentKind.FILE, "u", keyText, download).fetch(1_000) as FetchResult.Ok
            assertArrayEquals(plain, Files.readAllBytes(result.file.path))
            assertEquals("a.txt", result.file.suggestedName)
            assertFalse("密文临时文件应删除", Files.exists(encrypted))
            assertEquals("密文最多比明文多 32 字节填充", 1_032L, download.maxBytes)
        }

    @Test
    fun `取回：下载过大或失败原样返回`() =
        runBlocking {
            assertEquals(FetchResult.TooLarge, WecomAttachment(AttachmentKind.VIDEO, "u", keyText, FakeDownload { DownloadResult.TooLarge }).fetch(10))
            assertEquals(FetchResult.Failed("HTTP 404"), WecomAttachment(AttachmentKind.IMAGE, "u", keyText, FakeDownload { DownloadResult.Failed("HTTP 404") }).fetch(10))
        }

    @Test
    fun `取回：解密后超过上限返回过大，解密失败返回失败，都不留临时文件`() =
        runBlocking {
            val big = file(encrypt(ByteArray(40)))
            assertEquals(FetchResult.TooLarge, WecomAttachment(AttachmentKind.FILE, "u", keyText, FakeDownload { DownloadResult.Ok(big, null) }).fetch(20))
            assertFalse(Files.exists(big))
            // 长度不是 16 的倍数，一定解不开
            val garbage = file(ByteArray(47) { 1 })
            assertTrue(WecomAttachment(AttachmentKind.FILE, "u", keyText, FakeDownload { DownloadResult.Ok(garbage, null) }).fetch(1_000) is FetchResult.Failed)
            assertFalse(Files.exists(garbage))
        }
}
```

- [ ] **Step 2: 运行测试，确认失败**

Run: `timeout 590 ./gradlew test --tests 'com.github.izerui.imux.bridge.channel.wecom.WecomMediaTest'`
Expected: 编译失败，`WecomMedia`、`WecomAttachment` 未定义。

- [ ] **Step 3: 新建 `WecomMedia.kt`**

```kotlin
package com.github.izerui.imux.bridge.channel.wecom

import com.github.izerui.imux.bridge.channel.DownloadResult
import com.github.izerui.imux.bridge.channel.HttpFileDownload
import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import com.github.izerui.imux.bridge.core.InboundAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException

/** 企业微信媒体解密，与官方 SDK decryptFile 一致。 */
object WecomMedia {
    private const val BLOCK = 32

    /**
     * AES-256-CBC：密钥为 aeskey 的 base64 解码（允许缺少结尾 `=`），IV 取密钥前 16 字节。
     * 填充是 PKCS#7 到 32 字节倍数，JDK 的 PKCS5Padding 只认 16 字节块，所以用 NoPadding 手工去除。
     * 流式处理：始终扣住最后 32 字节，读完后再校验并去掉填充。
     */
    fun decryptFile(
        source: Path,
        target: Path,
        aesKeyBase64: String,
    ) {
        val key = Base64.getDecoder().decode(aesKeyBase64.trim())
        require(key.size == 32) { "aeskey 不是 32 字节" }
        val size = Files.size(source)
        require(size > 0 && size % 16 == 0L) { "密文长度不合法" }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key, 0, 16))
        Files.newInputStream(source).use { input ->
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var held = ByteArray(0)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val chunk = cipher.update(buffer, 0, read) ?: continue
                    val all = held + chunk
                    val keep = minOf(BLOCK, all.size)
                    output.write(all, 0, all.size - keep)
                    held = all.copyOfRange(all.size - keep, all.size)
                }
                val tail = held + (cipher.doFinal() ?: ByteArray(0))
                require(tail.isNotEmpty()) { "填充不合法" }
                val pad = tail.last().toInt() and 0xFF
                require(pad in 1..BLOCK && pad <= tail.size) { "填充不合法" }
                for (index in tail.size - pad until tail.size) require((tail[index].toInt() and 0xFF) == pad) { "填充不合法" }
                output.write(tail, 0, tail.size - pad)
            }
        }
    }
}

/** 企业微信消息里的一个附件：只在 [fetch] 时才下载与解密。 */
class WecomAttachment(
    override val kind: AttachmentKind,
    private val url: String,
    private val aesKey: String,
    private val download: HttpFileDownload,
) : InboundAttachment {
    override suspend fun fetch(maxBytes: Long): FetchResult =
        when (val downloaded = download.download(url, maxBytes + PADDING_ALLOWANCE)) {
            DownloadResult.TooLarge -> FetchResult.TooLarge
            is DownloadResult.Failed -> FetchResult.Failed(downloaded.reason)
            is DownloadResult.Ok ->
                withContext(Dispatchers.IO) {
                    val plain = Files.createTempFile(downloaded.path.parent, "imux-", ".bin")
                    try {
                        WecomMedia.decryptFile(downloaded.path, plain, aesKey)
                        if (Files.size(plain) > maxBytes) {
                            Files.deleteIfExists(plain)
                            FetchResult.TooLarge
                        } else {
                            FetchResult.Ok(FetchedFile(plain, downloaded.fileName))
                        }
                    } catch (e: CancellationException) {
                        Files.deleteIfExists(plain)
                        throw e
                    } catch (e: Exception) {
                        Files.deleteIfExists(plain)
                        FetchResult.Failed("decrypt: ${e.javaClass.simpleName}")
                    } finally {
                        Files.deleteIfExists(downloaded.path)
                    }
                }
        }

    private companion object {
        /** 密文最多比明文多一个 32 字节的填充块。 */
        const val PADDING_ALLOWANCE = 32L
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

Run: `timeout 590 ./gradlew test --tests 'com.github.izerui.imux.bridge.channel.wecom.WecomMediaTest'`
Expected: 5 个测试全部 PASS。

- [ ] **Step 5: 运行全量测试**

Run: `timeout 590 ./gradlew test --rerun -q`
Expected: 无失败。

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomMedia.kt src/test/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomMediaTest.kt
git commit -m "新增企业微信媒体解密与按需取回：32 字节 PKCS#7 填充、流式解密、超限与失败不留临时文件

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: 协议解析、连接接入与文档

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomProtocol.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomConnection.kt`
- Modify: `docs/channel-protocols.md`、`docs/terminal-channel-bridge-feasibility.md`
- Test: `src/test/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomProtocolTest.kt`、`src/test/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomConnectionTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `AttachmentKind`、`InboundMessage(..., attachments)`；Task 4 的 `HttpFileDownload`、`JdkHttpFileDownload`；Task 5 的 `WecomAttachment`。
- Produces:
  - `data class WecomMediaRef(val kind: AttachmentKind, val url: String, val aesKey: String)`
  - `WecomFrame.Message(reqId, msgId, single, fromUserId, text, media: List<WecomMediaRef> = emptyList())`
  - `WecomConnection(..., download: HttpFileDownload = JdkHttpFileDownload())`

- [ ] **Step 1: 写失败测试**

`WecomProtocolTest` 新增：

```kotlin
    @Test
    fun `解析图片、文件、视频与图文混排中的媒体引用`() {
        fun body(json: String) = """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m","chattype":"single","from":{"userid":"u"},$json}}"""
        val image = WecomProtocol.parse(body(""""msgtype":"image","image":{"url":"https://i","aeskey":"k1"}""")) as WecomFrame.Message
        assertEquals(listOf(WecomMediaRef(AttachmentKind.IMAGE, "https://i", "k1")), image.media)
        assertNull(image.text)
        val file = WecomProtocol.parse(body(""""msgtype":"file","file":{"url":"https://f","aeskey":"k2"}""")) as WecomFrame.Message
        assertEquals(listOf(WecomMediaRef(AttachmentKind.FILE, "https://f", "k2")), file.media)
        val video = WecomProtocol.parse(body(""""msgtype":"video","video":{"url":"https://v","aeskey":"k3"}""")) as WecomFrame.Message
        assertEquals(listOf(WecomMediaRef(AttachmentKind.VIDEO, "https://v", "k3")), video.media)
        val mixed =
            WecomProtocol.parse(
                body(""""msgtype":"mixed","mixed":{"msg_item":[{"msgtype":"text","text":{"content":"看"}},{"msgtype":"image","image":{"url":"https://a","aeskey":"ka"}},{"msgtype":"image","image":{"url":"https://b","aeskey":"kb"}}]}"""),
            ) as WecomFrame.Message
        assertEquals("看", mixed.text)
        assertEquals(listOf(WecomMediaRef(AttachmentKind.IMAGE, "https://a", "ka"), WecomMediaRef(AttachmentKind.IMAGE, "https://b", "kb")), mixed.media)
    }

    @Test
    fun `缺 url 或 aeskey 的媒体与兼容来源消息不产生媒体引用`() {
        fun body(json: String) = """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m","chattype":"single","from":{"userid":"u"},$json}}"""
        assertEquals(emptyList<WecomMediaRef>(), (WecomProtocol.parse(body(""""msgtype":"image","image":{"url":"https://i"}""")) as WecomFrame.Message).media)
        assertEquals(
            emptyList<WecomMediaRef>(),
            (WecomProtocol.parse(body(""""msgtype":"image","image":{"url":"https://i","aeskey":"k"},"compat_info":{"source":"x"}""")) as WecomFrame.Message).media,
        )
    }
```

并在 import 中加入 `com.github.izerui.imux.bridge.core.AttachmentKind`。

`WecomConnectionTest`：`connection(...)` 构造中加入参数 `download = HttpFileDownload { _, _ -> DownloadResult.Failed("test") }`（import `com.github.izerui.imux.bridge.channel.DownloadResult`、`com.github.izerui.imux.bridge.channel.HttpFileDownload`），并新增：

```kotlin
    @Test
    fun `只有附件的单聊消息也转交，附件按需取回`() {
        startAndAwaitOnline(connection())
        transports.first().emit(
            """{"cmd":"aibot_msg_callback","headers":{"req_id":"R"},"body":{"msgid":"m9","chattype":"single","from":{"userid":"u1"},"msgtype":"image","image":{"url":"https://i","aeskey":"k"}}}""",
        )
        val message = received.single()
        assertEquals("", message.text)
        assertEquals(listOf(AttachmentKind.IMAGE), message.attachments.map { it.kind })
    }
```

（import `com.github.izerui.imux.bridge.core.AttachmentKind`。）

- [ ] **Step 2: 运行测试，确认失败**

Run: `timeout 590 ./gradlew test --tests 'com.github.izerui.imux.bridge.channel.wecom.*'`
Expected: 编译失败，`WecomMediaRef`、`media`、`download` 参数未定义。

- [ ] **Step 3: 修改 `WecomProtocol.kt`**

在 `sealed interface WecomFrame` 之前加入：

```kotlin
/** 消息里的一个媒体：只记引用，下载与解密由 [WecomAttachment] 按需进行。 */
data class WecomMediaRef(
    val kind: AttachmentKind,
    val url: String,
    val aesKey: String,
)
```

`WecomFrame.Message` 增加最后一个参数并更新 KDoc：

```kotlin
    /** [text] 为 null 表示没有文字；[media] 为消息里的图片、文件、视频引用。 */
    data class Message(
        val reqId: String,
        val msgId: String,
        val single: Boolean,
        val fromUserId: String,
        val text: String?,
        val media: List<WecomMediaRef> = emptyList(),
    ) : WecomFrame
```

`message(reqId, body)` 中，在 `return WecomFrame.Message(` 之前加入：

```kotlin
        val media =
            if (body.has("compat_info")) {
                emptyList()
            } else {
                when (body.str("msgtype")) {
                    "image" -> listOfNotNull(mediaRef(AttachmentKind.IMAGE, body.obj("image")))
                    "file" -> listOfNotNull(mediaRef(AttachmentKind.FILE, body.obj("file")))
                    "video" -> listOfNotNull(mediaRef(AttachmentKind.VIDEO, body.obj("video")))
                    "mixed" ->
                        body.obj("mixed")?.get("msg_item")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                            ?.mapNotNull { item -> item.takeIf(JsonElement::isJsonObject)?.asJsonObject }
                            ?.filter { it.str("msgtype") == "image" }
                            ?.mapNotNull { mediaRef(AttachmentKind.IMAGE, it.obj("image")) }
                            .orEmpty()
                    else -> emptyList()
                }
            }
```

在 `WecomFrame.Message(...)` 构造中追加 `media = media,`。在 `private fun frame(` 之前加入：

```kotlin
    private fun mediaRef(
        kind: AttachmentKind,
        obj: JsonObject?,
    ): WecomMediaRef? {
        val url = obj?.str("url")?.takeIf(String::isNotBlank) ?: return null
        val aesKey = obj.str("aeskey")?.takeIf(String::isNotBlank) ?: return null
        return WecomMediaRef(kind, url, aesKey)
    }
```

import 加入 `com.github.izerui.imux.bridge.core.AttachmentKind`。

- [ ] **Step 4: 修改 `WecomConnection.kt`**

构造参数在 `maxRateWaitMillis` 之后加入：

```kotlin
    private val download: HttpFileDownload = JdkHttpFileDownload(),
```

`deliver` 改为：

```kotlin
    private fun deliver(
        frame: WecomFrame.Message,
        sink: InboundSink,
    ) {
        // 既没有文字也没有附件（兼容来源、缺字段等）才丢弃；附件在路由器确认本人、已绑定后才下载
        if (frame.text == null && frame.media.isEmpty()) return
        if (!frame.single || frame.fromUserId.isEmpty() || frame.msgId.isEmpty()) return
        synchronized(seen) {
            if (!seen.add(frame.msgId)) return
            while (seen.size > SEEN_CAPACITY) seen.remove(seen.first())
        }
        val attachments = frame.media.map { WecomAttachment(it.kind, it.url, it.aesKey, download) }
        sink.accept(InboundMessage(robotId, frame.msgId, frame.fromUserId, frame.fromUserId, frame.text.orEmpty(), attachments))
    }
```

import 加入 `com.github.izerui.imux.bridge.channel.HttpFileDownload`、`com.github.izerui.imux.bridge.channel.JdkHttpFileDownload`（`WecomAttachment` 同包）。

- [ ] **Step 5: 运行测试，确认通过**

Run: `timeout 590 ./gradlew test --tests 'com.github.izerui.imux.bridge.channel.wecom.*'`
Expected: 全部 PASS（原有“只转交单聊文本并按 msgid 去重”与“群聊、图片和兼容来源的消息没有可提交的正文”仍通过）。

- [ ] **Step 6: 更新文档**

`docs/channel-protocols.md` 第 1 节“收消息”列表中，把 `- \`voice.content\` 为转写文本（仅单聊）；\`mixed.msg_item[]\`；\`image/file\` 为 url+aeskey（5 分钟有效，AES-256-CBC，IV=key 前 16 字节，按 32 字节块去 PKCS#7）。` 这一行之后加入：

```markdown
- 已接入（2026-10-27）：`image`、`file`、`video` 与 `mixed` 中的图片。渠道只解析引用，路由器确认本人、非重复、已绑定后才下载；aeskey 为 base64（常缺结尾 `=`）；消息不含文件名，从下载响应的 `Content-Disposition` 取，取不到按文件头识别扩展名；文件、视频上限 100 MB。附件存到 `<项目>/.imux/attachments/<日期>/`，相对路径随文字提交给终端，见 `docs/superpowers/specs/2026-10-27-wecom-inbound-media-design.md`。
```

`docs/terminal-channel-bridge-feasibility.md` 中找到含“本轮结束时手机只收到最终回复一条”的联调清单条目，在其后追加一条（编号顺延）：

```markdown
N. 手机分别发图片、文件、视频、图文混排：附件出现在项目的 `.imux/attachments/<日期>/`，终端收到带 `[附件]` 路径的输入，Agent 能读取；未绑定时发附件只回复“未连接会话”且不下载；超过 100 MB 的视频收到“附件过大”提示。
```

（`N` 为该条目的实际序号。）

- [ ] **Step 7: 运行全量测试**

Run: `timeout 590 ./gradlew test --rerun -q`
Expected: 无失败。

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomProtocol.kt src/main/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomConnection.kt src/test/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomProtocolTest.kt src/test/kotlin/com/github/izerui/imux/bridge/channel/wecom/WecomConnectionTest.kt docs/channel-protocols.md docs/terminal-channel-bridge-feasibility.md
git commit -m "企业微信接入图片、文件、视频与图文混排中的图片：解析媒体引用并随消息转交

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## 验收（全部任务完成后）

1. `timeout 590 ./gradlew test --rerun -q` 无失败。
2. 启动沙箱（`./gradlew runIde`），在已绑定的会话里，手机分别发送：一张图片、一个 PDF、一段短视频、一条带图片的图文混排。确认项目里出现 `.imux/attachments/<今天>/` 下的文件、`.git/info/exclude` 含 `.imux/`、终端收到带 `[附件]` 路径的输入、Agent 能描述图片内容。
3. 断开会话后发一张图片：手机收到“未连接会话”，项目目录里没有新文件。
