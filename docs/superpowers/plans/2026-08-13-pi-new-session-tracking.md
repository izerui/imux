# pi 会话切换的标签页跟随 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 pi 会话里的 `/new`、`/resume`、`/fork`、`/clone` 也能把终端标签页迁到新会话上。

**Architecture:** pi 不暴露"当前在跑哪个会话"（无运行态文件、不持有会话文件句柄、`process.title` 导致 `ps` 读不到环境变量），因此改由 pi 主动上报：imux 随插件自带一个 js 扩展，启动 pi 时用 `-e` 加载，它在 `session_start` 时把 `{tabId, sessionFile}` POST 到 IDE 内置 HTTP 服务。IDE 端收到后复用现有的 `driftOf` + `applyDrifts` 迁移链路。全程不写磁盘。

**Tech Stack:** Kotlin / IntelliJ Platform 262 / `org.jetbrains.ide.HttpRequestHandler` / netty（平台自带）/ 一个 ES module js 文件

## Global Constraints

- 只支持 IntelliJ IDEA 2026.2（build 262），不加旧版兼容分支
- **插件不得向磁盘写入任何文件**——这是 README 的对外承诺，token 走环境变量传递，不落盘
- 优先使用平台原生 API（`AllIcons`、平台组件、官方扩展点），不自绘、不反射绕过 internal
- 测试用 JUnit 4，临时目录用 `TemporaryFolder`，不引入 JS 测试链路
- 编译时**不得**添加 netty 依赖：`local("/Applications/IntelliJ IDEA.app")` 已把平台 jar 放进 classpath，自己引会导致运行期类冲突
- 中文注释，解释"为什么"而非"是什么"，与现有代码风格一致

## 平台事实（已查证，实现时按此为准）

- `HttpRequestHandler` 在 `lib/intellij.platform.ide.impl.jar`，扩展点 `com.intellij.httpRequestHandler`（`dynamic="true"`）
- **`isSupported` 默认只放行 GET/HEAD**，不 override 则 POST 永远进不来，且症状是 404 不是 405
- 平台在 `HttpRequestHandler` 这一层**没有任何 token 校验**，本机任意进程都能打进来——token 必须自己加
- `isAccessible` 默认实现已保证只收 loopback（校验 Host 头 + Origin/Referer），不必自己再判
- 请求 UA 不能以 `Mozilla/5.0` 开头，否则被 `isWriteFromBrowserWithoutOrigin` 静默拦掉（node fetch 的 UA 不是，安全）
- EP 是全局共享链，`isSupported` 对不属于自己的 URI 必须返回 false，否则会截胡别的插件的请求
- `process()` 跑在 netty EventLoop 线程上，不得阻塞
- 端口从 `BuiltInServerManager.getInstance().port` 取，会漂移，不能硬编码 63342

---

### Task 1: 扩展脚本与打包

**Files:**
- Create: `src/main/js/pi-imux-reporter.js`
- Modify: `build.gradle.kts`（文件末尾追加 `prepareSandbox` 配置）
- Create: `src/main/kotlin/com/github/izerui/imux/terminal/PiReporterScript.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/terminal/PiReporterScriptTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `internal fun piReporterScriptIn(pluginPath: Path?): Path?` —— 给出扩展脚本的绝对路径，文件不存在或 pluginPath 为 null 时返回 null

- [ ] **Step 1: 写失败的测试**

`src/test/kotlin/com/github/izerui/imux/terminal/PiReporterScriptTest.kt`：

```kotlin
package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PiReporterScriptTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `在插件目录下定位上报脚本`() {
        val scripts = File(tmp.root, "scripts").apply { mkdirs() }
        val script = File(scripts, "pi-imux-reporter.js").apply { writeText("// x") }

        assertEquals(script.toPath(), piReporterScriptIn(tmp.root.toPath()))
    }

    /**
     * 安装不完整时必须退回「不加 -e」，而不是把一个不存在的路径拼进命令行——
     * pi 加载不到扩展会启动失败，代价是整个会话起不来。
     */
    @Test
    fun `脚本缺失时返回 null`() {
        assertNull(piReporterScriptIn(tmp.root.toPath()))
    }

    @Test
    fun `拿不到插件路径时返回 null`() {
        assertNull(piReporterScriptIn(null))
    }

    /** 源码里的脚本必须真实存在，否则打包出来的插件缺文件，功能静默失效。 */
    @Test
    fun `仓库里带着待打包的脚本`() {
        assertEquals(true, File("src/main/js/pi-imux-reporter.js").exists())
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests "*PiReporterScriptTest*"`
Expected: 编译失败，`Unresolved reference 'piReporterScriptIn'`

- [ ] **Step 3: 写扩展脚本**

`src/main/js/pi-imux-reporter.js`：

```javascript
/**
 * imux 会话上报器。
 *
 * pi 不对外暴露「此刻在跑哪个会话」：没有运行态文件、不持有会话文件句柄，
 * 又因为设了 process.title 而让 ps 读不到它的环境变量。于是改由 pi 自己说——
 * 每次会话开始或切换（/new、/resume、/fork、/clone）都报一次，
 * imux 据此把终端标签页迁到新会话上。
 *
 * 三条硬约束，改动时不要破坏：
 * 1. 不 await：阻塞 session_start 会让用户敲 /new 时卡顿
 * 2. 全程吞异常：本扩展的任何故障都不该影响用户的 pi 会话
 * 3. 短超时：IDE 已关闭或端口变了，不能把 pi 拖住
 *
 * 只做上报，不解析、不改动任何东西——会话 id 由 IDE 端从路径解析，那边能单测。
 */
export default function (pi) {
  const url = process.env.IMUX_REPORT_URL;
  const token = process.env.IMUX_TOKEN;
  const tabId = process.env.IMUX_TAB;

  // 用户自己在终端里跑 pi 时这个扩展根本不会被加载（-e 是 imux 启动时才加的）；
  // 这里再挡一道，缺任何一项就彻底不干活。
  if (!url || !token || !tabId) return;

  pi.on("session_start", async (_event, ctx) => {
    try {
      const sessionFile = ctx?.sessionManager?.getSessionFile?.();
      if (!sessionFile) return;
      fetch(url, {
        method: "POST",
        headers: { "content-type": "application/json", "x-imux-token": token },
        body: JSON.stringify({ tabId, sessionFile }),
        signal: AbortSignal.timeout(1000),
      }).catch(() => {});
    } catch {
      // 故意留空：上报失败只影响标签页跟随，绝不能影响会话本身
    }
  });
}
```

- [ ] **Step 4: 配置打包**

在 `build.gradle.kts` 末尾追加：

```kotlin
// 上报脚本必须以**文件**形式随插件安装存在：pi 的 -e 收的是路径，读不了 jar 内资源。
// 放进插件目录而不是运行时解压到别处，是为了守住「插件不写盘」这条承诺。
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>("prepareSandbox") {
    from("src/main/js/pi-imux-reporter.js") { into("${project.name}/scripts") }
}
```

- [ ] **Step 5: 写脚本定位实现**

`src/main/kotlin/com/github/izerui/imux/terminal/PiReporterScript.kt`：

```kotlin
package com.github.izerui.imux.terminal

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Files
import java.nio.file.Path

/** 打包时放进插件目录的上报脚本，见 build.gradle.kts 的 prepareSandbox 配置。 */
private const val SCRIPT_RELATIVE_PATH = "scripts/pi-imux-reporter.js"

private const val PLUGIN_ID = "com.github.izerui.imux"

/**
 * 上报脚本的绝对路径；插件目录未知或文件不存在时返回 null。
 *
 * 返回 null 时调用方必须**不加** `-e` 参数：把一个不存在的路径拼进命令行，
 * pi 会因加载不到扩展而启动异常，代价是整个会话起不来——
 * 而少了上报只是标签页不自动跟随，退回本功能上线前的行为。
 */
internal fun piReporterScriptIn(pluginPath: Path?): Path? {
    val script = pluginPath?.resolve(SCRIPT_RELATIVE_PATH) ?: return null
    return script.takeIf { Files.isRegularFile(it) }
}

/** 生产入口：从插件自身的安装目录取。 */
internal fun piReporterScript(): Path? =
    piReporterScriptIn(PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.pluginPath)
```

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew test --tests "*PiReporterScriptTest*"`
Expected: PASS（4 个）

- [ ] **Step 7: 验证打包产物**

Run: `./gradlew buildPlugin && unzip -l build/distributions/imux-0.2.8.zip | grep scripts`
Expected: 输出包含 `imux/scripts/pi-imux-reporter.js`

- [ ] **Step 8: 提交**

```bash
git add src/main/js build.gradle.kts src/main/kotlin/com/github/izerui/imux/terminal/PiReporterScript.kt src/test/kotlin/com/github/izerui/imux/terminal/PiReporterScriptTest.kt
git commit -m "为 pi 增加会话上报扩展脚本并随插件打包"
```

---

### Task 2: 上报凭据与启动接线

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/session/PiReportEndpoint.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt`（`launchCommand`、`launchEnvironment`）
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt:396-400`（`newCommand`、`resumeCommand`、`launchEnvironment` 调用处）
- Test: `src/test/kotlin/com/github/izerui/imux/terminal/AgentCommandTest.kt`（追加）

**Interfaces:**
- Consumes: `piReporterScript(): Path?`（Task 1）
- Produces:
  - `data class PiReportEndpoint(val url: String, val token: String)`
  - `PiReportEndpoint.current(): PiReportEndpoint?`（应用级 service，token 在内存生成）
  - `launchCommand(shell: String, agentType: AgentType, resumeId: String?, piExtension: Path? = null): List<String>`
  - `launchEnvironment(agentType: AgentType, tabId: String, piReport: PiReportEndpoint? = null): Map<String, String>`

- [ ] **Step 1: 写失败的测试**

在 `AgentCommandTest.kt` 中追加：

```kotlin
    @Test
    fun `pi 带上上报扩展`() {
        val script = java.nio.file.Paths.get("/plugins/imux/scripts/pi-imux-reporter.js")

        assertEquals(
            "pi --session-id 'abc-123' -e '/plugins/imux/scripts/pi-imux-reporter.js'",
            launchCommand("/bin/zsh", AgentType.PI, "abc-123", script).last(),
        )
    }

    /**
     * 脚本缺失（安装不完整）时绝不能拼出半截 -e：pi 加载不到扩展会启动失败，
     * 代价是整个会话起不来，而少了上报只是标签页不自动跟随。
     */
    @Test
    fun `扩展脚本缺失时不加 -e`() {
        assertEquals(
            "pi --session-id 'abc-123'",
            launchCommand("/bin/zsh", AgentType.PI, "abc-123", null).last(),
        )
    }

    @Test
    fun `扩展只给 pi，不给另外两个 agent`() {
        val script = java.nio.file.Paths.get("/plugins/imux/scripts/pi-imux-reporter.js")

        assertEquals("claude --resume 'x'", launchCommand("/bin/zsh", AgentType.CLAUDE, "x", script).last())
        assertEquals("codex resume 'x'", launchCommand("/bin/zsh", AgentType.CODEX, "x", script).last())
    }

    @Test
    fun `pi 拿到上报地址与令牌`() {
        val endpoint = PiReportEndpoint("http://127.0.0.1:63342/imux/pi-session", "tok-1")
        val env = launchEnvironment(AgentType.PI, "tab-1", endpoint)

        assertEquals("http://127.0.0.1:63342/imux/pi-session", env["IMUX_REPORT_URL"])
        assertEquals("tok-1", env["IMUX_TOKEN"])
    }

    /** 令牌是这个接口唯一的门禁：平台在 HttpRequestHandler 这层不做任何校验。 */
    @Test
    fun `令牌不发给 pi 以外的 agent`() {
        val endpoint = PiReportEndpoint("http://127.0.0.1:63342/imux/pi-session", "tok-1")

        assertNull(launchEnvironment(AgentType.CLAUDE, "tab-1", endpoint)["IMUX_TOKEN"])
        assertNull(launchEnvironment(AgentType.CODEX, "tab-1", endpoint)["IMUX_TOKEN"])
    }

    @Test
    fun `内置服务不可用时 pi 照常启动`() {
        val env = launchEnvironment(AgentType.PI, "tab-1", null)

        assertNull(env["IMUX_REPORT_URL"])
        assertNull(env["IMUX_TOKEN"])
        assertEquals("tab-1", env[IMUX_TAB_ENV])
    }
```

文件顶部补充 import：

```kotlin
import com.github.izerui.imux.session.PiReportEndpoint
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests "*AgentCommandTest*"`
Expected: 编译失败，`Unresolved reference 'PiReportEndpoint'`

- [ ] **Step 3: 写凭据实现**

`src/main/kotlin/com/github/izerui/imux/session/PiReportEndpoint.kt`：

```kotlin
package com.github.izerui.imux.session

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import org.jetbrains.ide.BuiltInServerManager
import java.util.UUID

/** 上报接口的 URI 前缀。取得足够独特：扩展点是全局共享链，撞名会截胡别的插件的请求。 */
const val PI_REPORT_PATH: String = "/imux/pi-session"

/**
 * pi 上报所需的地址与令牌。
 *
 * 令牌是这个接口**唯一**的门禁：平台在 `HttpRequestHandler` 这一层不做任何校验，
 * 本机任意进程都能往内置服务上打请求。没有它，别的进程就能伪造上报把标签页
 * 迁到另一个会话上。
 *
 * 令牌只存在于内存，随环境变量下发给 pi 进程——插件不写磁盘（见 README）。
 */
data class PiReportEndpoint(val url: String, val token: String) {

    companion object {
        /** 内置服务未就绪时返回 null，调用方据此退回「不上报」。 */
        fun current(): PiReportEndpoint? = runCatching {
            val port = BuiltInServerManager.getInstance().waitForStart().port
            if (port <= 0) return null
            PiReportEndpoint(
                url = "http://127.0.0.1:$port$PI_REPORT_PATH",
                token = ApplicationManager.getApplication()
                    .getService(PiReportTokenHolder::class.java)
                    .token,
            )
        }.getOrNull()
    }
}

/**
 * 本次 IDE 运行期的上报令牌。
 *
 * 应用级而非项目级：内置 HTTP 服务是整个 IDE 一个，handler 也只注册一次。
 */
@Service(Service.Level.APP)
class PiReportTokenHolder {
    val token: String = UUID.randomUUID().toString()
}
```

- [ ] **Step 4: 改启动命令与环境**

`AgentCommand.kt` 中把 `launchCommand` 改为：

```kotlin
internal fun launchCommand(
    shell: String,
    agentType: AgentType,
    resumeId: String?,
    piExtension: java.nio.file.Path? = null,
): List<String> {
    val cli = agentType.cli
    val script = when {
        resumeId == null -> cli
        // pi 的 --session-id 对已存在的 id 是打开、不存在则以该 id 创建，
        // 所以新建与续聊是同一条命令——新建时 id 由 imux 预先生成传进来。
        agentType == AgentType.PI -> buildString {
            append("$cli --session-id ${singleQuote(resumeId)}")
            // 脚本缺失时**不加** -e：拼出一个加载不了的扩展会让 pi 启动失败，
            // 那是整个会话起不来；而少了上报只是标签页不自动跟随。
            piExtension?.let { append(" -e ${singleQuote(it.toString())}") }
        }
        agentType == AgentType.CODEX -> "$cli resume ${singleQuote(resumeId)}"
        else -> "$cli --resume ${singleQuote(resumeId)}"
    }
    return listOf(shell, "-l", "-i", "-c", script)
}
```

`launchEnvironment` 改为：

```kotlin
internal fun launchEnvironment(
    agentType: AgentType,
    tabId: String,
    piReport: PiReportEndpoint? = null,
): Map<String, String> =
    buildMap {
        put(IMUX_TAB_ENV, tabId)
        when (agentType) {
            AgentType.CLAUDE -> put("CLAUDE_CODE_NATIVE_CURSOR", "1")
            AgentType.PI -> {
                put("PI_HARDWARE_CURSOR", "1")
                // 令牌只发给 pi：它是上报接口唯一的门禁，多发一个进程就多一份泄漏面
                piReport?.let {
                    put("IMUX_REPORT_URL", it.url)
                    put("IMUX_TOKEN", it.token)
                }
            }
            AgentType.CODEX -> Unit
        }
    }
```

文件顶部补充 import：`import com.github.izerui.imux.session.PiReportEndpoint`

- [ ] **Step 5: 接线 TerminalHost**

`TerminalHost.kt` 中把两个命令构造函数改为：

```kotlin
    private fun newCommand(agentType: AgentType, sessionId: String?): List<String> =
        launchCommand(
            resolveShell(System.getenv("SHELL")),
            agentType,
            resumeId = sessionId,
            piExtension = piExtensionFor(agentType),
        )

    private fun resumeCommand(agentType: AgentType, sessionId: String): List<String> =
        launchCommand(
            resolveShell(System.getenv("SHELL")),
            agentType,
            resumeId = sessionId,
            piExtension = piExtensionFor(agentType),
        )

    /** 只有 pi 需要上报扩展，别的 agent 一律不加。 */
    private fun piExtensionFor(agentType: AgentType): java.nio.file.Path? =
        if (agentType == AgentType.PI) piReporterScript() else null
```

同一文件中找到 `.envVariables(launchEnvironment(agentType, tabId))`，改为：

```kotlin
            .envVariables(
                launchEnvironment(
                    agentType,
                    tabId,
                    piReport = if (agentType == AgentType.PI) PiReportEndpoint.current() else null,
                ),
            )
```

补充 import：`import com.github.izerui.imux.session.PiReportEndpoint`

- [ ] **Step 6: 运行全部测试**

Run: `./gradlew test`
Expected: 全部 PASS

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "pi 启动时带上上报扩展与令牌"
```

---

### Task 3: 上报内容解析

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/session/PiSessionReport.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/session/PiSessionReportTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `data class PiSessionReport(val tabId: String, val sessionId: String)`
  - `internal fun parsePiReport(body: String): PiSessionReport?`
  - `internal fun piSessionIdOf(sessionFile: String): String?`

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.github.izerui.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PiSessionReportTest {

    private val file =
        "/Users/demo/.pi/agent/sessions/--Users-demo-proj--/2026-08-13T09-45-00-045Z_019ffa82-bd8d-7edd-a9aa-e5e711524a7a.jsonl"

    @Test
    fun `从会话文件路径解析出会话 id`() {
        assertEquals("019ffa82-bd8d-7edd-a9aa-e5e711524a7a", piSessionIdOf(file))
    }

    /** 文件名是 <时间戳>_<uuid>.jsonl，时间戳里也有横杠，不能按横杠切。 */
    @Test
    fun `时间戳不会被误当成 id`() {
        assertEquals(36, piSessionIdOf(file)?.length)
    }

    @Test
    fun `形状不对的路径解析为 null`() {
        assertNull(piSessionIdOf("/tmp/notasession.jsonl"))
        assertNull(piSessionIdOf("/tmp/2026-08-13T09-45-00-045Z_短id.jsonl"))
        assertNull(piSessionIdOf(""))
    }

    @Test
    fun `解析上报体`() {
        val body = """{"tabId":"imux-1","sessionFile":"$file"}"""

        assertEquals(
            PiSessionReport("imux-1", "019ffa82-bd8d-7edd-a9aa-e5e711524a7a"),
            parsePiReport(body),
        )
    }

    /** 上报来自另一个进程，任何字段都不能假定存在——损坏的报文只该被丢弃。 */
    @Test
    fun `缺字段或损坏的上报体解析为 null`() {
        assertNull(parsePiReport(""))
        assertNull(parsePiReport("这不是 json"))
        assertNull(parsePiReport("""{"tabId":"imux-1"}"""))
        assertNull(parsePiReport("""{"sessionFile":"$file"}"""))
        assertNull(parsePiReport("""{"tabId":"","sessionFile":"$file"}"""))
        assertNull(parsePiReport("""{"tabId":"imux-1","sessionFile":"/tmp/x.jsonl"}"""))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests "*PiSessionReportTest*"`
Expected: 编译失败，`Unresolved reference 'piSessionIdOf'`

- [ ] **Step 3: 写实现**

```kotlin
package com.github.izerui.imux.session

/** pi 扩展报上来的一条：某个标签页此刻在跑哪个会话。 */
data class PiSessionReport(val tabId: String, val sessionId: String)

/**
 * 从 pi 的会话文件路径取出会话 id。
 *
 * 文件名形如 `2026-08-13T09-45-00-045Z_019ffa82-bd8d-7edd-a9aa-e5e711524a7a.jsonl`：
 * 时间戳里也带横杠，所以只能按**最后一个下划线**切，不能按横杠。
 *
 * 解析放在这边而不是 js 扩展里：这里能单测，那边不进测试链路。
 */
internal fun piSessionIdOf(sessionFile: String): String? {
    val name = sessionFile.substringAfterLast('/')
    if (!name.endsWith(JSONL_SUFFIX)) return null
    val id = name.removeSuffix(JSONL_SUFFIX).substringAfterLast('_')
    return id.takeIf(::looksLikePiSessionId)
}

/**
 * 解析上报体。
 *
 * 手写扫描而不是引 JSON 库：报文只有两个字符串字段，且这段代码跑在 netty 的
 * EventLoop 线程上，越简单越好。任何不合形状的输入一律返回 null——
 * 上报来自另一个进程，不能假定它的内容。
 */
internal fun parsePiReport(body: String): PiSessionReport? {
    val tabId = JsonLineScanner.stringValue(body, "tabId")?.takeIf { it.isNotBlank() } ?: return null
    val sessionFile = JsonLineScanner.stringValue(body, "sessionFile") ?: return null
    val sessionId = piSessionIdOf(sessionFile) ?: return null
    return PiSessionReport(tabId, sessionId)
}

private fun looksLikePiSessionId(value: String): Boolean =
    value.length == PI_ID_LENGTH &&
        value.withIndex().all { (index, char) ->
            if (index in PI_ID_DASH_POSITIONS) char == '-' else char.isLetterOrDigit()
        }

private const val JSONL_SUFFIX = ".jsonl"
private const val PI_ID_LENGTH = 36
private val PI_ID_DASH_POSITIONS = setOf(8, 13, 18, 23)
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests "*PiSessionReportTest*"`
Expected: PASS（5 个）

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/session/PiSessionReport.kt src/test/kotlin/com/github/izerui/imux/session/PiSessionReportTest.kt
git commit -m "解析 pi 会话上报内容"
```

---

### Task 4: 接入既有迁移链路

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt`（新增 public 方法）
- Test: `src/test/kotlin/com/github/izerui/imux/session/LiveSessionProbeTest.kt`（追加）

**Interfaces:**
- Consumes: `PiSessionReport`（Task 3）、既有的 `driftOf`、`stillApplicable`、`applyDrifts`
- 说明：本任务先于 HTTP 接收端，因为后者要调用这里产出的方法
- Produces: `fun SessionMonitor.onPiSessionReported(report: PiSessionReport)`

- [ ] **Step 1: 写失败的测试**

pi 的上报要转成既有的 `LiveTab` 才能喂给 `driftOf`。在 `LiveSessionProbeTest.kt` 追加：

```kotlin
    /**
     * pi 走上报而不是进程探测，但迁移判定复用同一套：把上报转成 LiveTab 交给 driftOf，
     * 这样「同一 tabId 报了不同会话就不动」「标签页已关就不迁」这些规则不必写第二遍。
     */
    @Test
    fun `pi 上报能直接喂给既有的漂移判定`() {
        val live = listOf(LiveTab("tab-1", "pi-new"))
        val openTabs = mapOf("tab-1" to "pi-old")

        assertEquals(
            listOf(KeyDrift("tab-1", from = "pi-old", to = "pi-new")),
            driftOf(openTabs, live),
        )
    }

    @Test
    fun `上报的会话与当前一致时不产生迁移`() {
        assertTrue(driftOf(mapOf("tab-1" to "pi-same"), listOf(LiveTab("tab-1", "pi-same"))).isEmpty())
    }

    @Test
    fun `上报来自未知标签页时不产生迁移`() {
        assertTrue(driftOf(mapOf("tab-1" to "pi-old"), listOf(LiveTab("tab-9", "pi-new"))).isEmpty())
    }
```

- [ ] **Step 2: 运行测试确认通过**

Run: `./gradlew test --tests "*LiveSessionProbeTest*"`
Expected: PASS —— 这三条锁的是既有 `driftOf` 对 pi 场景同样成立（characterization test），若失败说明理解有误，停下来重新读 `driftOf`

- [ ] **Step 3: 在 SessionMonitor 中接入**

在 `SessionMonitor` 类中新增（放在 `probeSessionDrift` 附近）：

```kotlin
    /**
     * 收到 pi 扩展的会话上报：它换会话了（`/new`、`/resume`、`/fork`），把标签页迁过去。
     *
     * 与 claude、codex 的区别在于**信息怎么来的**，不在于迁移怎么做：那两个靠进程探测
     * （[probeSessionDrift]），pi 三条观测面全断只能由它自己上报。判定与落地完全复用
     * 同一套 [driftOf] + [applyDrifts]，「同一 tabId 报了不同会话就不动」
     * 「标签页已关就不迁」这些规则不必写第二遍。
     *
     * 由 netty 的 EventLoop 线程调用，因此这里只排期、不干活。
     */
    fun onPiSessionReported(report: PiSessionReport) {
        if (project.isDisposed) return

        coroutineScope.launch(Dispatchers.EDT) {
            if (project.isDisposed) return@launch
            val host = TerminalHost.getInstance(project)
            val drifts = driftOf(host.openTabsByTabId(), listOf(LiveTab(report.tabId, report.sessionId)))
            if (drifts.isEmpty()) return@launch
            applyDrifts(drifts)
        }
    }
```

补充 import：

```kotlin
import com.github.izerui.imux.session.LiveTab
import com.github.izerui.imux.session.PiSessionReport
```

- [ ] **Step 4: 运行全部测试**

Run: `./gradlew test && ./gradlew buildPlugin`
Expected: 全部 PASS，构建成功

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "pi 上报接入既有的标签页迁移链路"
```

---

### Task 5: HTTP 接收端

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/session/PiSessionReportHandler.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`（`<extensions>` 块内追加）
- Test: `src/test/kotlin/com/github/izerui/imux/session/PiSessionReportHandlerTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/PluginXmlRegistrationTest.kt`（追加）

**Interfaces:**
- Consumes: `parsePiReport`、`PI_REPORT_PATH`、`PiReportTokenHolder`（Task 2、3）、`SessionMonitor.onPiSessionReported`（Task 4）
- Produces: `internal fun handlesPiReport(uri: String, isPost: Boolean): Boolean`；类 `PiSessionReportHandler`

- [ ] **Step 1: 写失败的测试**

```kotlin
package com.github.izerui.imux.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiSessionReportHandlerTest {

    /**
     * 扩展点是全局共享链，`findFirstSafe` 先到先得，而且平台会把上次命中的 handler
     * 缓存在 channel 上优先重试。对不属于自己的 URI 必须老实返回 false，
     * 否则会吞掉别的插件的请求。
     */
    @Test
    fun `只认自己的路径`() {
        assertTrue(handlesPiReport("/imux/pi-session", isPost = true))
        assertTrue("带查询串也要认", handlesPiReport("/imux/pi-session?x=1", isPost = true))

        assertFalse(handlesPiReport("/api/about/", isPost = true))
        assertFalse(handlesPiReport("/imux/pi-session-other", isPost = true))
        assertFalse(handlesPiReport("/", isPost = true))
    }

    /** 平台默认只放行 GET/HEAD，这里必须自己认 POST；反过来也不该受理 GET。 */
    @Test
    fun `只受理 POST`() {
        assertFalse(handlesPiReport("/imux/pi-session", isPost = false))
    }
}
```

在 `PluginXmlRegistrationTest.kt` 追加：

```kotlin
    @Test
    fun `注册了 pi 会话上报的 HTTP 接收端`() {
        val xml = File("src/main/resources/META-INF/plugin.xml").readText()

        assertTrue(
            "缺了注册，pi 扩展报上来的会话切换没人接收，标签页不会跟随",
            xml.contains("""<httpRequestHandler implementation="com.github.izerui.imux.session.PiSessionReportHandler"/>"""),
        )
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests "*PiSessionReportHandlerTest*" --tests "*PluginXmlRegistrationTest*"`
Expected: 编译失败，`Unresolved reference 'handlesPiReport'`

- [ ] **Step 3: 写实现**

```kotlin
package com.github.izerui.imux.session

import com.github.izerui.imux.monitor.SessionMonitor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.util.CharsetUtil
import org.jetbrains.ide.HttpRequestHandler
import org.jetbrains.io.send

/** 请求头里携带令牌的字段名。 */
private const val TOKEN_HEADER = "x-imux-token"

/**
 * 判断这条请求是否该由本 handler 处理。
 *
 * 抽成纯函数是为了可测：构造真实的 [FullHttpRequest] 代价不小，而这里的判定
 * 恰恰是最容易出错的地方——平台的 `isSupported` 默认只放行 GET/HEAD，
 * 不覆盖它 POST 永远进不来，且症状是 404 而不是 405。
 */
internal fun handlesPiReport(uri: String, isPost: Boolean): Boolean {
    if (!isPost) return false
    val path = uri.substringBefore('?')
    return path == PI_REPORT_PATH
}

/**
 * 接收 pi 扩展的会话上报。
 *
 * 为什么需要它：pi 不像 claude 有运行态文件、也不像 codex 长期持有会话文件句柄，
 * 又因为设了 `process.title` 而让 `ps` 读不到它的环境变量——三条观测面全断，
 * 只能由 pi 自己说。详见 docs/superpowers/specs/2026-08-13-pi-new-session-tracking-design.md。
 *
 * **令牌是唯一的门禁**：平台在本层不做任何校验，本机任意进程都能打进来。
 * loopback 限制由父类的 `isAccessible` 保证，不必自己再判。
 */
internal class PiSessionReportHandler : HttpRequestHandler() {

    override fun isSupported(request: FullHttpRequest): Boolean =
        handlesPiReport(request.uri(), isPost = request.method() == HttpMethod.POST)

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        val expected = ApplicationManager.getApplication()
            .getService(PiReportTokenHolder::class.java)
            .token
        if (request.headers().get(TOKEN_HEADER) != expected) {
            // 记一笔：本机任意进程都能打到这个接口，被拒的请求是排查时唯一的线索
            LOG.warn("拒绝令牌不匹配的 pi 会话上报")
            HttpResponseStatus.FORBIDDEN.send(context.channel(), request)
            return true
        }

        val report = parsePiReport(request.content().toString(CharsetUtil.UTF_8))
        if (report == null) {
            HttpResponseStatus.BAD_REQUEST.send(context.channel(), request)
            return true
        }

        // 本方法跑在 netty 的 EventLoop 线程上，不能在这里做迁移（要碰 EDT 与文件系统）。
        // 交给各项目的 monitor 自己排期，这里立刻应答。
        ProjectManager.getInstance().openProjects
            .filterNot { it.isDisposed }
            .forEach { runCatching { SessionMonitor.getInstance(it).onPiSessionReported(report) } }

        HttpResponseStatus.OK.send(context.channel(), request)
        return true
    }

    private companion object {
        val LOG = logger<PiSessionReportHandler>()
    }
}
```

- [ ] **Step 4: 注册到 plugin.xml**

在 `<extensions defaultExtensionNs="com.intellij">` 块内追加：

```xml
        <!--
          接收 pi 扩展的会话上报。pi 的会话切换（/new、/resume、/fork）无法从进程外
          观测，只能由它自己上报，见 PiSessionReportHandler 的说明。
        -->
        <httpRequestHandler implementation="com.github.izerui.imux.session.PiSessionReportHandler"/>
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew test --tests "*PiSessionReportHandlerTest*" --tests "*PluginXmlRegistrationTest*"`
Expected: 全部 PASS（`SessionMonitor.onPiSessionReported` 已由 Task 4 产出）

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "接收 pi 会话上报的 HTTP 接口"
```

---

### Task 6: 文档与端到端验证

**Files:**
- Modify: `README.md`（「在会话里 /clear 或 /new 之后」「已知限制」「它读了你机器上的什么」三节）
- Modify: `docs/superpowers/specs/2026-08-13-pi-cli-support-design.md`（同步限制已解除）

- [ ] **Step 1: 端到端验证**

```bash
./gradlew buildPlugin
```

装上新构建后手工验证，逐条确认：

1. 新开一个 pi 会话标签页（必须新开——扩展与环境变量在进程启动时才注入）
2. 按 `Ctrl+O` 展开启动信息，确认 `[Extensions]` 一栏出现 `pi-imux-reporter.js`
3. 在会话里敲 `/new`
4. 确认标签页标题跟着变成新会话
5. 确认左侧列表里新会话标为「已打开」（灰点），旧会话不再标记
6. 在新会话里问一句话，确认跑完后的完成提醒指向**新**会话
7. 敲 `/resume` 切到另一个会话，确认标签页同样跟随

- [ ] **Step 2: 更新 README**

「在会话里 /clear 或 /new 之后」一节，删掉这段：

```markdown
**pi 除外**：它的会话 id 是新建时就定下的，imux 没有对它做这层进程探测。在 pi 里敲 `/new`，新会话会出现在列表里，但原标签页仍记在旧会话上——想接着用新会话，在列表里双击它。
```

替换为：

```markdown
三个 CLI 都支持，但 pi 的做法不同：Claude 与 Codex 是 imux 去翻进程信息认出来的，而 pi 把这些信息全藏住了（没有运行态文件、不持有会话文件句柄、`ps` 读不到它的环境变量），只能由它自己上报——imux 启动 pi 时会加载一个自带的扩展 `pi-imux-reporter.js`，它在会话切换时把「哪个标签页换到了哪个会话」发回 IDE。

这个扩展只做这一件事：读三个环境变量、发一个本机请求。不读你的对话、不碰会话文件、不联网。你在 pi 里按 `Ctrl+O` 能在 `[Extensions]` 一栏看到它。你自己在终端里跑 pi 时它不会被加载。
```

「已知限制」一节删掉这条：

```markdown
- **pi 里敲 `/new` 之后标签页不会自动跟过去**。Claude 的 `/clear` 与 Codex 的 `/new` 都能被认出来（靠翻进程信息），pi 走的是预先绑定 id 那条路，没做这层探测：新会话会出现在列表里，但原标签页仍记在旧会话上。
```

- [ ] **Step 3: 同步旧设计文档**

在 `docs/superpowers/specs/2026-08-13-pi-cli-support-design.md` 末尾追加：

```markdown
## 后续：/new 的标签页跟随（已实现）

本文档写作时列为已知缺口的「pi 里 `/new` 不跟随标签页」已经解决，做法是让 pi 通过自带扩展主动上报，
见 `2026-08-13-pi-new-session-tracking-design.md`。
```

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "文档同步 pi 会话切换跟随"
```
