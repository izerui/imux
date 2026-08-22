# imux 三平台支持 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 imux 的会话启动、LSP 体检、会话漂移探测在 macOS / Linux / Windows 上都可用，三个 CLI（claude / codex / pi）一个不少，且 macOS 现有行为逐字节不变。

**Architecture:** 新增一层 `ShellDialect`（方言 = 参数形状 + 引号规则 + 探测脚本写法），按 shell 二进制名判定而非按操作系统判定；四个 shell 调用点全部改经这一层。会话漂移探测的三个平台各用一套身份通道，但 `LiveSessionProbe` 的注入接口不变，只换实现。所有平台判断以参数注入，因此三平台分支都能在 macOS 开发机上被 JUnit 真调用断言。

**Tech Stack:** Kotlin, IntelliJ Platform 262, JUnit 4, Gradle（`--offline`）

设计文档：`docs/superpowers/specs/2026-08-22-imux-cross-platform-design.md`

## Global Constraints

- **macOS 现有行为逐字节不变。** 用户原话：「都支持，但是不要产生 bug，导致其他原有支持的平台有问题」。每个被改动的函数都必须有一条 macOS 形态的 `assertEquals` 用例钉死完整返回值。
- **不修改任何 CLI 的配置文件。** 用户原话：「只要别改动全局的 cli 就行，就是别改 cli 的配置文件本身就行」。codex 走 `-c` 命令行覆盖，pi 走 `-e`，claude 不碰。写入只允许发生在 `PathManager.getSystemPath()` 下 imux 自己的目录。
- **平台判断一律以参数注入**，纯函数里不得直读 `SystemInfo`。整个 `src/main` 里 `SystemInfo.` 只允许出现在把它读成实参的调用点。
- 只支持 IntelliJ Platform 262，不写兼容分支、不用反射绕私有 API。
- 测试只能用 JUnit 4，项目**未**引入平台 test-framework，不可用 `BasePlatformTestCase` 等。
- 测试方法用中文反引号命名。
- 不新增 Gradle 依赖。
- **KDoc 内不得出现 `*/`**，需要时写 `&#42;`。
- 工作树里可能有仓库所有者的未提交改动，**禁止 `git add -A` / `git add .` / `git commit -a`**，只 `git add` 本任务明确列出的文件。
- gradle 一律带 `--offline`。
- 图标只用 `AllIcons`。
- 删除任何既有断言时，必须在实现报告里逐条说明「它守的东西是不是真的没了」。`LspRemedyRunTest` 与 `ImuxLspUiSourceTest` 因「重构时顺手删老断言让出地盘」被代码审查打回过两次。
- 基线：`./gradlew clean test --offline` 当前全绿（退出码 0）。

---

## File Structure

**新建**

| 文件 | 责任 |
| --- | --- |
| `src/main/kotlin/com/github/izerui/imux/terminal/ShellDialect.kt` | 方言的四个纯函数：`dialectOf` / `shellArgs` / `quote` / `probeScript` |
| `src/main/kotlin/com/github/izerui/imux/session/ProcLinuxProbe.kt` | Linux `/proc` 的两个解析纯函数 + 读取实现 |
| `src/main/kotlin/com/github/izerui/imux/session/WindowsTabPidFile.kt` | Windows pid 自报文件的写/读/清扫 + 父链上溯纯函数 |
| `src/main/kotlin/com/github/izerui/imux/terminal/CodexHookOverride.kt` | codex `-c hooks.SessionStart=…` 实参的构造（含 TOML 转义） |
| `src/main/scripts/codex-imux-reporter.ps1` | Windows 上 codex 的 SessionStart hook 脚本 |
| `src/test/kotlin/com/github/izerui/imux/terminal/ShellDialectTest.kt` | |
| `src/test/kotlin/com/github/izerui/imux/session/ProcLinuxProbeTest.kt` | |
| `src/test/kotlin/com/github/izerui/imux/session/WindowsTabPidFileTest.kt` | |
| `src/test/kotlin/com/github/izerui/imux/terminal/CodexHookOverrideTest.kt` | |

**修改**

| 文件 | 改什么 |
| --- | --- |
| `terminal/AgentCommand.kt` | `resolveShell` 加平台参数；`launchCommand` 走方言；`singleQuote` 变 `quote(POSIX, …)` 的别名；`launchEnvironment` 给 codex 也发上报端点 |
| `terminal/TerminalHost.kt` | 两个调用点传平台实参 |
| `lsp/BinaryProbe.kt` | `buildProbeScript` → `probeScript(dialect, …)`；`ShellBinaryProbe` 走方言 |
| `lsp/LspRemedyRun.kt` | `canRun` 删 `hasPosixShell` 维度；`runCommandLine` 走方言 |
| `lsp/LspCatalog.kt` | `macOnlyCommands` 收窄 |
| `settings/ImuxLspConfigurable.kt` | 两个调用点跟随 |
| `session/ProcessProbes.kt` | `codexPids` 认 `.exe` 与 `\`；三平台分派 |
| `session/LiveSessionProbe.kt` | `fileNameOf` 切 `/` 与 `\` |
| `session/PiSessionReportHandler.kt` | 新增 codex 上报路径 |
| `session/PiReportEndpoint.kt` | 新增 codex 路径常量 |
| `build.gradle.kts` | 打包 `.ps1` 脚本；`:test` 输入加 `src/main/scripts` |

---

# 阶段一：shell 方言（第 2、3 层）

完成后 Windows / Linux 上会话能起、LSP 页能探能点。这是一个可独立交付的整体。

---

### Task 1: `ShellDialect` 的四个纯函数

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/terminal/ShellDialect.kt`
- Create: `src/test/kotlin/com/github/izerui/imux/terminal/ShellDialectTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `internal enum class ShellDialect { POSIX, POWERSHELL }`
  - `internal fun dialectOf(shellPath: String): ShellDialect`
  - `internal fun shellArgs(dialect: ShellDialect): List<String>`
  - `internal fun quote(dialect: ShellDialect, value: String): String`
  - `internal fun probeScript(dialect: ShellDialect, binaries: List<String>): String`

- [ ] **Step 1: 先写失败的测试**

创建 `src/test/kotlin/com/github/izerui/imux/terminal/ShellDialectTest.kt`：

```kotlin
package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellDialectTest {

    @Test
    fun `方言按 shell 二进制名判定而不按操作系统`() {
        assertEquals(ShellDialect.POSIX, dialectOf("/bin/zsh"))
        assertEquals(ShellDialect.POSIX, dialectOf("/bin/bash"))
        assertEquals(ShellDialect.POSIX, dialectOf("/usr/local/bin/fish"))
        assertEquals(ShellDialect.POWERSHELL, dialectOf("C:\\Program Files\\PowerShell\\7\\pwsh.exe"))
        assertEquals(
            ShellDialect.POWERSHELL,
            dialectOf("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"),
        )
    }

    @Test
    fun `Windows 上的 Git Bash 是 POSIX 方言`() {
        // 按操作系统判的话这里会错发 PowerShell 的引号规则，当场拼错命令行
        assertEquals(ShellDialect.POSIX, dialectOf("C:\\Program Files\\Git\\bin\\bash.exe"))
    }

    @Test
    fun `可执行文件名大小写与 exe 后缀都不影响判定`() {
        assertEquals(ShellDialect.POWERSHELL, dialectOf("C:\\X\\PowerShell.EXE"))
        assertEquals(ShellDialect.POWERSHELL, dialectOf("pwsh"))
    }

    @Test
    fun `cmd 不是受支持的方言按 POSIX 兜底`() {
        // resolveShell 不会把 cmd 交到这里（它会换成 powershell.exe），
        // 这条只钉住「不认识的名字有确定结果」，不表示支持 cmd
        assertEquals(ShellDialect.POSIX, dialectOf("C:\\Windows\\System32\\cmd.exe"))
    }

    @Test
    fun `POSIX 参数与现网完全一致`() {
        assertEquals(listOf("-l", "-i", "-c"), shellArgs(ShellDialect.POSIX))
    }

    @Test
    fun `PowerShell 不读 profile`() {
        // Windows 的 PATH 在环境变量块里，IDE 直接继承，不需要 profile；
        // 而读 profile 会引入启动延迟与用户 profile 报错的风险
        assertEquals(listOf("-NoLogo", "-NoProfile", "-Command"), shellArgs(ShellDialect.POWERSHELL))
    }

    @Test
    fun `POSIX 引号与现网的 singleQuote 逐字节相同`() {
        listOf("plain", "with space", "it's", "a'b'c", "", "--flag=x").forEach { value ->
            assertEquals(singleQuote(value), quote(ShellDialect.POSIX, value))
        }
    }

    @Test
    fun `POSIX 引号把单引号闭合转义重开`() {
        assertEquals("'it'\\''s'", quote(ShellDialect.POSIX, "it's"))
    }

    @Test
    fun `PowerShell 引号把单引号写成两个`() {
        assertEquals("'it''s'", quote(ShellDialect.POWERSHELL, "it's"))
        assertEquals("'plain'", quote(ShellDialect.POWERSHELL, "plain"))
    }

    @Test
    fun `POSIX 探测脚本与现网逐字节相同`() {
        val binaries = listOf("gopls", "jdtls")
        assertEquals(buildProbeScript(binaries), probeScript(ShellDialect.POSIX, binaries))
    }

    @Test
    fun `PowerShell 探测脚本输出同样的名称制表符路径`() {
        val script = probeScript(ShellDialect.POWERSHELL, listOf("gopls"))
        // 与 POSIX 版共用 parseProbeOutput，格式必须一致
        assertEquals(
            "\$ErrorActionPreference='SilentlyContinue'; @('gopls') | ForEach-Object { " +
                "\$p = (Get-Command \$_ -ErrorAction SilentlyContinue | Select-Object -First 1).Source; \"\$_`t\$p\" }",
            script,
        )
    }

    @Test
    fun `探测脚本对二进制名同样施加方言引号`() {
        assertEquals(
            "\$ErrorActionPreference='SilentlyContinue'; @('a''b') | ForEach-Object { " +
                "\$p = (Get-Command \$_ -ErrorAction SilentlyContinue | Select-Object -First 1).Source; \"\$_`t\$p\" }",
            probeScript(ShellDialect.POWERSHELL, listOf("a'b")),
        )
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew test --offline --tests '*ShellDialectTest*'
```

预期：编译失败，`Unresolved reference: ShellDialect`。

- [ ] **Step 3: 写实现**

创建 `src/main/kotlin/com/github/izerui/imux/terminal/ShellDialect.kt`：

```kotlin
package com.github.izerui.imux.terminal

import com.github.izerui.imux.lsp.buildProbeScript

// shell 方言：参数形状、引号规则、探测脚本写法。
//
// 一行平台 API 都不碰，理由与 lsp/LspRemedyRun.kt 相同：本项目未引入平台
// test-framework，UI 与平台相关逻辑只能做源码文本断言，而文本断言总能被
// 「保留被钉住的字面量、在别处改语义」绕开。搬进纯函数之后，三个平台的分支
// 都能被普通 JUnit 4 真调用——把 quote 的 PowerShell 分支改成 POSIX 规则，
// ShellDialectTest 当场变红，而调用点那一侧只剩一个调用点。

/**
 * 受支持的 shell 方言。
 *
 * **没有 CMD。** cmd 的引号与转义规则（`^` 转义、`%` 会被二次展开、引号内规则随
 * 上下文变化）写对的难度远高于收益，而写错的后果是把用户的初始 prompt 拼成一条
 * 别的命令。IntelliJ 在 Windows 上依次探测 `pwsh` &#8594; `powershell` &#8594; `cmd`，
 * PowerShell 是绝大多数机器上的实际默认值；解析到 cmd 时由 [resolveShell] 换成
 * `powershell.exe`，不会走到这里。
 */
internal enum class ShellDialect { POSIX, POWERSHELL }

/**
 * 按 shell 可执行文件名判方言，**不按操作系统判**。
 *
 * Windows 上 Git Bash 很常见，它是 POSIX；按 `SystemInfo.isWindows` 判会给
 * Git Bash 用户发 PowerShell 的引号规则，当场拼错命令行。
 *
 * 认不出来的名字一律当 POSIX：这是 macOS/Linux 上唯一正确的答案，而 Windows 上
 * 走到这里的只可能是用户配了个我们没见过的 shell——那时按 POSIX 处理至少与
 * 改动前一致，不会更坏。
 */
internal fun dialectOf(shellPath: String): ShellDialect {
    val name =
        shellPath
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .lowercase()
            .removeSuffix(".exe")
    return if (name == "pwsh" || name == "powershell") ShellDialect.POWERSHELL else ShellDialect.POSIX
}

/**
 * 交给 shell 执行一条命令所需的参数。
 *
 * **POSIX 的 `-l -i` 缺一不可**，这个坑项目里踩过两次：
 * - `-l` 读 profile。从 Dock/Finder 启动的 IDE 只有系统默认 PATH
 *   （`/usr/bin:/bin:/usr/sbin:/sbin`），而 CLI 与 `brew`、`go`、`npm` 都不在里面
 * - `-i` 读 rc，配成 **alias** 的工具才存在（本机的 `claude` 就是个 alias，
 *   还带着参数，丢了它行为就变）
 *
 * **PowerShell 反而要 `-NoProfile`。** 上面那套存在的唯一理由是 macOS/Linux 的
 * GUI 程序拿不到用户 shell 的 PATH；Windows 的 PATH 在环境变量块里，IDE 直接继承，
 * 不需要读 profile，而读 profile 会引入几百毫秒到数秒的启动延迟与用户 profile
 * 报错的风险。**这不是漏了，是有意的。**
 *
 * 已知取舍：Windows 上把 CLI 配成 PowerShell 函数或别名的用户拿不到它。触发面窄——
 * Windows 上 npm 装的 CLI 是 PATH 里的 `.cmd` shim，不是别名。
 */
internal fun shellArgs(dialect: ShellDialect): List<String> =
    when (dialect) {
        ShellDialect.POSIX -> listOf("-l", "-i", "-c")
        ShellDialect.POWERSHELL -> listOf("-NoLogo", "-NoProfile", "-Command")
    }

/**
 * 包成该方言的字面量字符串。
 *
 * 凡是拼进命令行的东西一律当作不可信：会话 id 来自文件名，初始 prompt 来自用户输入。
 *
 * 两种方言都用单引号（内部一切都是字面量），只是转义单引号自身的写法不同：
 * POSIX 要闭合-转义-重开（`'\''`），PowerShell 写两个单引号（`''`）。
 *
 * **POSIX 分支必须与 [singleQuote] 逐字节相同**——那是 macOS 上正在工作的行为。
 */
internal fun quote(
    dialect: ShellDialect,
    value: String,
): String =
    when (dialect) {
        ShellDialect.POSIX -> "'" + value.replace("'", "'\\''") + "'"
        ShellDialect.POWERSHELL -> "'" + value.replace("'", "''") + "'"
    }

/**
 * 一次问完一批二进制在不在 PATH 里的脚本。
 *
 * 两种方言的**输出格式必须一致**（`名称<TAB>路径`，查不到时制表符后为空），
 * 因为上游共用同一个 `parseProbeOutput`。不能靠「有没有这一行」判断：登录 shell
 * 会往 stdout 混入 profile 的欢迎语。
 *
 * POSIX 分支直接复用 [buildProbeScript]，一个字节不改。
 */
internal fun probeScript(
    dialect: ShellDialect,
    binaries: List<String>,
): String =
    when (dialect) {
        ShellDialect.POSIX -> buildProbeScript(binaries)
        ShellDialect.POWERSHELL -> {
            val list = binaries.joinToString(",") { quote(ShellDialect.POWERSHELL, it) }
            "\$ErrorActionPreference='SilentlyContinue'; @($list) | ForEach-Object { " +
                "\$p = (Get-Command \$_ -ErrorAction SilentlyContinue | Select-Object -First 1).Source; \"\$_`t\$p\" }"
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

```
./gradlew test --offline --tests '*ShellDialectTest*'
```

预期：全部 PASS。若 `> Task :test` 显示 `UP-TO-DATE` 或 `FROM-CACHE`，说明没真跑，加 `--rerun-tasks` 重来。

- [ ] **Step 5: 变异验证**

把 `quote` 的 `POWERSHELL` 分支改成与 POSIX 相同 → 跑测试 → 确认 `PowerShell 引号把单引号写成两个` FAIL → 还原。
把 `dialectOf` 改成 `if (shellPath.contains("\\"))` → 确认 `Windows 上的 Git Bash 是 POSIX 方言` FAIL → 还原。

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/terminal/ShellDialect.kt \
        src/test/kotlin/com/github/izerui/imux/terminal/ShellDialectTest.kt
git commit -m "新增 shell 方言层，按二进制名而非操作系统判定

Windows 上 Git Bash 很常见且是 POSIX，按操作系统判会给它发 PowerShell 的
引号规则，当场拼错命令行。

POSIX 分支的参数与引号规则与现网逐字节相同，并有用例直接与 singleQuote /
buildProbeScript 对比钉住——macOS 上正在工作的行为不能因这一层而改变。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: `resolveShell` 平台化

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt:132`
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt:570,582`
- Modify: `src/main/kotlin/com/github/izerui/imux/lsp/BinaryProbe.kt:88`
- Modify: `src/main/kotlin/com/github/izerui/imux/settings/ImuxLspConfigurable.kt:712`
- Test: `src/test/kotlin/com/github/izerui/imux/terminal/AgentCommandTest.kt`

**Interfaces:**
- Consumes: `dialectOf(shellPath): ShellDialect`、`ShellDialect`
- Produces: `internal fun resolveShell(shellEnv: String?, isWindows: Boolean, configuredShell: String?): String`（**三个参数都无默认值**）

- [ ] **Step 1: 先写失败的测试**

在 `AgentCommandTest.kt` 追加：

```kotlin
    @Test
    fun `非 Windows 上的 shell 解析与现网逐字节相同`() {
        // 这是 macOS 上正在工作的行为，改它等于改用户机器上正在跑的东西
        assertEquals("/bin/zsh", resolveShell(null, isWindows = false, configuredShell = null))
        assertEquals("/bin/zsh", resolveShell("", isWindows = false, configuredShell = null))
        assertEquals("/bin/zsh", resolveShell("   ", isWindows = false, configuredShell = null))
        assertEquals("/bin/bash", resolveShell("/bin/bash", isWindows = false, configuredShell = null))
    }

    @Test
    fun `非 Windows 上不理会 IDE 配置的 shell`() {
        // 换数据源会改变 macOS 行为，与「原有平台不能出问题」冲突
        assertEquals(
            "/bin/bash",
            resolveShell("/bin/bash", isWindows = false, configuredShell = "/usr/local/bin/fish"),
        )
    }

    @Test
    fun `Windows 上采用 IDE 配置的 shell`() {
        assertEquals(
            "C:\\Program Files\\PowerShell\\7\\pwsh.exe",
            resolveShell(
                shellEnv = null,
                isWindows = true,
                configuredShell = "C:\\Program Files\\PowerShell\\7\\pwsh.exe",
            ),
        )
    }

    @Test
    fun `Windows 上保留用户配置的 Git Bash`() {
        assertEquals(
            "C:\\Program Files\\Git\\bin\\bash.exe",
            resolveShell(null, isWindows = true, configuredShell = "C:\\Program Files\\Git\\bin\\bash.exe"),
        )
    }

    @Test
    fun `Windows 上配的是 cmd 时改用 PowerShell`() {
        // 全文唯一一处不听用户配置：cmd 的转义规则写错会把初始 prompt 拼成另一条命令
        assertEquals(
            "powershell.exe",
            resolveShell(null, isWindows = true, configuredShell = "C:\\Windows\\System32\\cmd.exe"),
        )
    }

    @Test
    fun `Windows 上取不到配置时退回 PowerShell 而不是 bin zsh`() {
        assertEquals("powershell.exe", resolveShell(null, isWindows = true, configuredShell = null))
        assertEquals("powershell.exe", resolveShell(null, isWindows = true, configuredShell = "  "))
        // Windows 上 SHELL 通常没有值；就算有，也不该拿 POSIX 路径去 ProcessBuilder
        assertEquals("powershell.exe", resolveShell("/bin/zsh", isWindows = true, configuredShell = null))
    }
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew test --offline --tests '*AgentCommandTest*'
```

预期：编译失败，`resolveShell` 不接受三个参数。

- [ ] **Step 3: 改实现**

`terminal/AgentCommand.kt` 把第 132 行整个替换：

```kotlin
/**
 * 交给终端或 `ProcessBuilder` 的 shell 可执行文件。
 *
 * **非 Windows 分支与改动前逐字节相同**，且刻意**不看** [configuredShell]：
 * macOS 上取 `SHELL` 是正在工作的行为，换数据源就是改用户机器上正在跑的东西。
 *
 * Windows 上 `SHELL` 通常没有值，从前退回 `/bin/zsh`，四个调用点一律
 * `Cannot run program /bin/zsh`。改取 [configuredShell]——用户在 Terminal 设置里
 * 配的那个 shell，也就是他自己终端里跑的东西。
 *
 * **解析到 cmd 时不听用户配置，改用 `powershell.exe`。** 这是全项目唯一一处覆盖
 * 用户设置，理由是 cmd 的引号与转义规则（`^` 转义、`%` 二次展开）写错会把用户的
 * 初始 prompt 拼成一条别的命令；宁可换一个我们能正确转义的 shell。PowerShell 在
 * 每台受支持的 Windows 上都在。
 *
 * 三个参数都不给默认值：调用点必须显式表态，避免将来新增调用点时静默漏掉平台判断。
 */
internal fun resolveShell(
    shellEnv: String?,
    isWindows: Boolean,
    configuredShell: String?,
): String {
    if (!isWindows) return shellEnv?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
    val configured = configuredShell?.takeIf { it.isNotBlank() } ?: return WINDOWS_FALLBACK_SHELL
    // dialectOf 认不出的名字会落到 POSIX；Windows 上那意味着 cmd 或某个我们没见过的
    // shell，两者都不该拿 PowerShell 的引号规则去拼，但也不该拿 POSIX 的 -l -i 去跑。
    // 只放行明确认得出的两类：PowerShell，以及路径里带 sh/bash/zsh 的 POSIX shell。
    return if (looksLikePosixShell(configured) || dialectOf(configured) == ShellDialect.POWERSHELL) {
        configured
    } else {
        WINDOWS_FALLBACK_SHELL
    }
}

/** Windows 上认得出的 POSIX shell（Git Bash、MSYS2、WSL 转发器等）。 */
private fun looksLikePosixShell(shellPath: String): Boolean {
    val name =
        shellPath
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .lowercase()
            .removeSuffix(".exe")
    return name in POSIX_SHELL_NAMES
}

private val POSIX_SHELL_NAMES = setOf("sh", "bash", "zsh", "fish", "dash", "ksh")
private const val WINDOWS_FALLBACK_SHELL = "powershell.exe"
```

- [ ] **Step 4: 改四个调用点**

`terminal/TerminalHost.kt` 第 570、582 行，两处 `resolveShell(System.getenv("SHELL"))` 改为：

```kotlin
            resolveShell(
                System.getenv("SHELL"),
                isWindows = SystemInfo.isWindows,
                configuredShell = TerminalOptionsProvider.getInstance().shellPath,
            ),
```

顶部补 `import com.intellij.openapi.util.SystemInfo` 与
`import org.jetbrains.plugins.terminal.TerminalOptionsProvider`。

`lsp/BinaryProbe.kt` 第 88 行的默认实参改为：

```kotlin
    private val shell: String =
        resolveShell(
            System.getenv("SHELL"),
            isWindows = SystemInfo.isWindows,
            configuredShell = TerminalOptionsProvider.getInstance().shellPath,
        ),
```

`settings/ImuxLspConfigurable.kt` 第 712 行同样改，实参写法一致。

- [ ] **Step 5: 跑全量测试**

```
./gradlew test --offline
```

预期：全部 PASS。既有 `AgentCommandTest` 里调 `resolveShell` 的旧用例会因缺参数编译失败——**逐条补上 `isWindows = false, configuredShell = null`，不要删用例**。

- [ ] **Step 6: 变异验证**

把 `if (!isWindows)` 那行删掉 → 确认 `非 Windows 上的 shell 解析与现网逐字节相同` FAIL → 还原。
把 cmd 分支改成直接 `return configured` → 确认 `Windows 上配的是 cmd 时改用 PowerShell` FAIL → 还原。

- [ ] **Step 7: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt \
        src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt \
        src/main/kotlin/com/github/izerui/imux/lsp/BinaryProbe.kt \
        src/main/kotlin/com/github/izerui/imux/settings/ImuxLspConfigurable.kt \
        src/test/kotlin/com/github/izerui/imux/terminal/AgentCommandTest.kt
git commit -m "resolveShell 分平台解析，Windows 改取 IDE 配置的终端 shell

Windows 上取不到 SHELL 会退回 /bin/zsh，四个调用点一律当场
Cannot run program /bin/zsh——会话起不来、LSP 探测全落无法确定。

非 Windows 分支逐字节不变，且刻意不看 IDE 配置：macOS 上取 SHELL 是正在
工作的行为，换数据源就是改用户机器上正在跑的东西。

解析到 cmd 时不听用户配置改用 PowerShell，是全项目唯一一处覆盖用户设置。
cmd 的转义规则写错会把用户的初始 prompt 拼成一条别的命令。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: `launchCommand` 与 `runCommandLine` 走方言

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt`（`launchCommand`、`singleQuote`）
- Modify: `src/main/kotlin/com/github/izerui/imux/lsp/LspRemedyRun.kt`（`runCommandLine`）
- Test: `src/test/kotlin/com/github/izerui/imux/terminal/AgentCommandTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/lsp/LspRemedyRunTest.kt`

**Interfaces:**
- Consumes: `dialectOf`、`shellArgs`、`quote`
- Produces: `launchCommand` / `runCommandLine` 签名不变（仍收 `shell: String`），内部按 `dialectOf(shell)` 分派

- [ ] **Step 1: 先写失败的测试**

在 `AgentCommandTest.kt` 追加。**这一组是 macOS 不回归的主防线**：

```kotlin
    @Test
    fun `macOS 形态的启动命令逐字节不变`() {
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "claude"),
            launchCommand("/bin/zsh", AgentType.CLAUDE, resumeId = null),
        )
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "claude --resume 'abc-123'"),
            launchCommand("/bin/zsh", AgentType.CLAUDE, resumeId = "abc-123"),
        )
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "codex resume 'abc-123'"),
            launchCommand("/bin/zsh", AgentType.CODEX, resumeId = "abc-123"),
        )
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "pi --session-id 'abc-123'"),
            launchCommand("/bin/zsh", AgentType.PI, resumeId = "abc-123"),
        )
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "pi --session-id 'abc-123' -e '/tmp/r.js'"),
            launchCommand("/bin/zsh", AgentType.PI, resumeId = "abc-123", piExtension = Path.of("/tmp/r.js")),
        )
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "claude --resume 'abc-123' 'say hi'"),
            launchCommand("/bin/zsh", AgentType.CLAUDE, resumeId = "abc-123", initialPrompt = "say hi"),
        )
    }

    @Test
    fun `PowerShell 形态用 PowerShell 的参数与引号`() {
        assertEquals(
            listOf("pwsh.exe", "-NoLogo", "-NoProfile", "-Command", "claude --resume 'abc-123'"),
            launchCommand("pwsh.exe", AgentType.CLAUDE, resumeId = "abc-123"),
        )
    }

    @Test
    fun `初始 prompt 里的单引号按方言转义`() {
        // prompt 是用户自由输入，是整条命令行里最不可信的一段
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "claude 'it'\\''s'"),
            launchCommand("/bin/zsh", AgentType.CLAUDE, resumeId = null, initialPrompt = "it's"),
        )
        assertEquals(
            listOf("pwsh.exe", "-NoLogo", "-NoProfile", "-Command", "claude 'it''s'"),
            launchCommand("pwsh.exe", AgentType.CLAUDE, resumeId = null, initialPrompt = "it's"),
        )
    }
```

在 `LspRemedyRunTest.kt` 追加：

```kotlin
    @Test
    fun `macOS 形态的执行命令行逐字节不变`() {
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "brew install jdtls"),
            runCommandLine("/bin/zsh", "brew install jdtls"),
        )
    }

    @Test
    fun `PowerShell 形态的执行命令行用 PowerShell 参数`() {
        assertEquals(
            listOf("pwsh.exe", "-NoLogo", "-NoProfile", "-Command", "npm install -g pyright"),
            runCommandLine("pwsh.exe", "npm install -g pyright"),
        )
    }
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew test --offline --tests '*AgentCommandTest*' --tests '*LspRemedyRunTest*'
```

预期：`PowerShell 形态…` 两条 FAIL，实际值里是 `-l, -i, -c`。

- [ ] **Step 3: 改 `launchCommand`**

`terminal/AgentCommand.kt`：函数体开头取方言，所有 `singleQuote(...)` 换成 `quote(dialect, ...)`，末行换成拼方言参数。

```kotlin
internal fun launchCommand(
    shell: String,
    agentType: AgentType,
    resumeId: String?,
    piExtension: java.nio.file.Path? = null,
    initialPrompt: String? = null,
): List<String> {
    val dialect = dialectOf(shell)
    val cli = agentType.cli
    val script =
        when {
            resumeId == null -> {
                cli
            }

            // pi 的 --session-id 对已存在的 id 是打开、不存在则以该 id 创建，
            // 所以新建与续聊是同一条命令——新建时 id 由 imux 预先生成传进来。
            agentType == AgentType.PI -> {
                buildString {
                    append("$cli --session-id ${quote(dialect, resumeId)}")
                    // 脚本缺失时**不加** -e：拼出一个加载不了的扩展会让 pi 启动失败，
                    // 那是整个会话起不来；而少了上报只是标签页不自动跟随。
                    piExtension?.let { append(" -e ${quote(dialect, it.toString())}") }
                }
            }

            agentType == AgentType.CODEX -> {
                "$cli resume ${quote(dialect, resumeId)}"
            }

            else -> {
                "$cli --resume ${quote(dialect, resumeId)}"
            }
        }
    val command =
        initialPrompt
            ?.takeIf { it.isNotBlank() }
            ?.let { "$script ${quote(dialect, it)}" }
            ?: script
    return listOf(shell) + shellArgs(dialect) + command
}
```

同时把 `singleQuote` 改成 `quote(POSIX, …)` 的别名，签名与行为不变：

```kotlin
/**
 * 包成 POSIX 单引号字符串。
 *
 * 保留这个名字是因为 `lsp/CodexLspProbe.kt` 用它拼的是**给用户看的命令文本**
 * （`codex mcp add pi-lens -- '<路径>'`），与终端方言无关。
 *
 * 已知取舍：Windows 上那条建议会由 PowerShell 执行，而两种方言只在值里含单引号时
 * 才产生不同结果——含单引号的可执行文件路径极其罕见，且失败形态是命令报错而非
 * 执行了别的东西。不为此把方言穿透进探针层（那一层刻意不碰任何平台概念）。
 */
internal fun singleQuote(value: String): String = quote(ShellDialect.POSIX, value)
```

- [ ] **Step 4: 改 `runCommandLine`**

`lsp/LspRemedyRun.kt` 末尾的 `runCommandLine`：

```kotlin
internal fun runCommandLine(
    shell: String,
    command: String,
): List<String> = listOf(shell) + shellArgs(dialectOf(shell)) + command
```

顶部补 `import com.github.izerui.imux.terminal.dialectOf` 与
`import com.github.izerui.imux.terminal.shellArgs`。

原 KDoc 里「`-l` 与 `-i` 缺一不可」那段整体保留，但要补一句说明 PowerShell 分支
为什么是 `-NoProfile`（指向 `shellArgs` 的 KDoc，不重复论述）。

- [ ] **Step 5: 跑全量测试**

```
./gradlew test --offline
```

预期：全部 PASS。

- [ ] **Step 6: 变异验证**

把 `listOf(shell) + shellArgs(dialect) + command` 改回 `listOf(shell, "-l", "-i", "-c", command)`
→ 确认 `PowerShell 形态用 PowerShell 的参数与引号` FAIL、而 `macOS 形态的启动命令逐字节不变` 仍 PASS
→ 还原。这一步同时证明了 macOS 用例确实钉的是 macOS 那条路。

- [ ] **Step 7: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt \
        src/main/kotlin/com/github/izerui/imux/lsp/LspRemedyRun.kt \
        src/test/kotlin/com/github/izerui/imux/terminal/AgentCommandTest.kt \
        src/test/kotlin/com/github/izerui/imux/lsp/LspRemedyRunTest.kt
git commit -m "启动命令与执行命令行改经方言拼装

launchCommand 里的会话 id 与初始 prompt 都是不可信输入，从前一律按 POSIX
单引号转义。PowerShell 的转义规则不同，照 POSIX 拼会把用户的 prompt 拼成
一条别的命令。

macOS 形态的返回值用 assertEquals 逐字节钉死，覆盖三个 agent、有无 resumeId、
有无初始 prompt、有无 pi 扩展的每一种组合。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: `BinaryProbe` 走方言

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/lsp/BinaryProbe.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/lsp/BinaryProbeTest.kt`

**Interfaces:**
- Consumes: `probeScript(dialect, binaries)`、`dialectOf`、`shellArgs`
- Produces: `ShellBinaryProbe` 行为不变，内部按 shell 方言选脚本与参数

- [ ] **Step 1: 先写失败的测试**

在 `BinaryProbeTest.kt` 追加：

```kotlin
    @Test
    fun `PowerShell 与 POSIX 的探测输出走同一个解析器`() {
        // 两种方言的脚本形状不同，但输出格式必须一致，否则 parseProbeOutput 会把
        // Windows 的结果整片丢弃，18 门语言全落 UNKNOWN 而没有任何报错
        val parsed = parseProbeOutput("gopls\t/usr/local/bin/gopls\njdtls\t\n")
        assertEquals(mapOf("gopls" to "/usr/local/bin/gopls", "jdtls" to null), parsed)
    }

    @Test
    fun `探测脚本仍然一次问完所有二进制`() {
        // 每个二进制起一个登录 shell 不可接受：本表近二十个二进制，
        // 而 zsh -l -i 要读 profile 与 rc
        val posix = probeScript(ShellDialect.POSIX, listOf("a", "b", "c"))
        assertEquals(2, posix.count { it == ';' })
        val pwsh = probeScript(ShellDialect.POWERSHELL, listOf("a", "b", "c"))
        assertTrue("PowerShell 版应把三个名字放进同一个数组", pwsh.contains("@('a','b','c')"))
    }
```

在 `ImuxLspUiSourceTest`（或 `BinaryProbeTest` 里既有的源码断言处）补一条挡板：

```kotlin
    @Test
    fun `探测进程仍不得把 stderr 接成管道`() {
        val body = File("src/main/kotlin/com/github/izerui/imux/lsp/BinaryProbe.kt").readText()
        // stderr 无人读取时子进程写满 64KB 就阻塞，stdout 永不关闭，
        // readText() 永久挂起，waitFor 的超时那行根本执行不到
        assertFalse(body.contains("redirectErrorStream"))
        assertFalse(body.contains("Redirect.PIPE"))
        assertTrue(body.contains("Redirect.DISCARD"))
    }
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew test --offline --tests '*BinaryProbeTest*'
```

预期：`探测脚本仍然一次问完所有二进制` FAIL（`probeScript` 尚未被 import 进该测试）或编译失败。

- [ ] **Step 3: 改实现**

`lsp/BinaryProbe.kt` 的 `ShellBinaryProbe.locate` 里那行 `ProcessBuilder`：

```kotlin
            val dialect = dialectOf(shell)
            val process =
                ProcessBuilder(
                    listOf(shell) + shellArgs(dialect) + probeScript(dialect, binaries.toList()),
                ).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
```

`buildProbeScript` **保留不动**（`probeScript` 的 POSIX 分支调它，且它自己的用例还在）。
顶部补 `import com.github.izerui.imux.terminal.dialectOf` / `probeScript` / `shellArgs`。

- [ ] **Step 4: 跑全量测试**

```
./gradlew test --offline
```

- [ ] **Step 5: 变异验证**

把 `probeScript(dialect, …)` 改回 `buildProbeScript(…)` → 确认有用例 FAIL → 还原。
若没有任何用例红，说明这一层缺守卫，补一条断言 `ShellBinaryProbe` 源码里含 `probeScript(`。

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/lsp/BinaryProbe.kt \
        src/test/kotlin/com/github/izerui/imux/lsp/BinaryProbeTest.kt
git commit -m "二进制探测按 shell 方言选脚本

Windows 上 command -v 不存在，探测整体失败 → 18 门语言全落无法确定。
PowerShell 版用 Get-Command，输出仍是名称制表符路径，与 POSIX 版共用
parseProbeOutput。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: `canRun` 删闸门维度 + `macOnlyCommands` 收窄

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/lsp/LspRemedyRun.kt`（`canRun`）
- Modify: `src/main/kotlin/com/github/izerui/imux/lsp/LspCatalog.kt`（`macOnlyCommands`）
- Modify: `src/main/kotlin/com/github/izerui/imux/settings/ImuxLspConfigurable.kt:574`
- Test: `src/test/kotlin/com/github/izerui/imux/lsp/LspRemedyRunTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/lsp/LspCatalogTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/settings/ImuxLspUiSourceTest.kt`

**Interfaces:**
- Consumes: `requiredTool(command): String`（已存在）
- Produces: `internal fun canRun(remedy: Remedy, isMac: Boolean): Boolean`（**少一个参数**）

- [ ] **Step 1: 先写失败的测试**

在 `LspCatalogTest.kt` 追加：

```kotlin
    @Test
    fun `只有 brew 与 opam 的命令算作 macOS 专属`() {
        LspCatalog.macOnlyCommands.forEach { command ->
            val tool = requiredTool(command)
            assertTrue("『$command』不该被当作 macOS 专属", tool == "brew" || tool == "opam")
        }
    }

    @Test
    fun `语言自带包管理器的安装命令不算 macOS 专属`() {
        // 这 7 条在 Linux/Windows 上形态完全相同，把它们挡在 macOS 之外
        // 等于让非 macOS 用户白白少 7 门语言的一键启用
        listOf(
            "go install golang.org/x/tools/gopls@latest",
            "npm install -g typescript-language-server typescript",
            "npm install -g pyright",
            "npm install -g intelephense",
            "gem install ruby-lsp",
            "dotnet tool install --global csharp-ls",
            "dotnet tool install --global fsautocomplete",
        ).forEach { command ->
            assertFalse("『$command』是跨平台的", command in LspCatalog.macOnlyCommands)
        }
    }

    @Test
    fun `brew 系命令仍算 macOS 专属`() {
        listOf(
            "brew install jdtls",
            "brew install --cask kotlin-lsp",
            "brew install lua-language-server",
            "brew install llvm",
            "brew install --cask dotnet-sdk",
            "brew install rustup",
            "brew install opam",
        ).forEach { command ->
            assertTrue("『$command』只在 macOS 上核实过", command in LspCatalog.macOnlyCommands)
        }
    }
```

在 `LspRemedyRunTest.kt` 追加：

```kotlin
    @Test
    fun `非 macOS 上跨平台命令给按钮`() {
        val remedy = Remedy(listOf("npm install -g pyright"), null)
        assertTrue(canRun(remedy, isMac = false))
    }

    @Test
    fun `非 macOS 上 brew 命令不给按钮`() {
        val remedy = Remedy(listOf("brew install jdtls"), null)
        assertFalse(canRun(remedy, isMac = false))
    }

    @Test
    fun `链里含任何一条 brew 命令就整条不给按钮`() {
        // 链用 && 串起来，第一条跑不通后面一条都跑不到；
        // 跑到一半红着停下的终端比一开始就没有按钮更让人以为插件坏了
        val remedy =
            Remedy(
                listOf("brew install --cask dotnet-sdk", "dotnet tool install --global csharp-ls"),
                null,
            )
        assertFalse(canRun(remedy, isMac = false))
        assertTrue(canRun(remedy, isMac = true))
    }

    @Test
    fun `空链一律不给按钮`() {
        assertFalse(canRun(Remedy(emptyList(), "https://example.com"), isMac = true))
        assertFalse(canRun(Remedy(emptyList(), null), isMac = false))
    }
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew test --offline --tests '*LspCatalogTest*' --tests '*LspRemedyRunTest*'
```

预期：`只有 brew 与 opam…` FAIL（现在所有安装命令都在集合里）；`canRun` 相关条目编译失败（还要三个参数）。

- [ ] **Step 3: 收窄 `macOnlyCommands`**

`lsp/LspCatalog.kt` 第 127-129 行整体替换：

```kotlin
    /**
     * 只在 macOS 上核实过、不可跨平台执行的安装命令。
     *
     * 判据是命令的第一个 token 是 `brew` 还是 `opam`——13 个 server 里有 7 个用的是
     * 语言自带的包管理器（npm / go / gem / dotnet），那些命令在三个平台上形态完全
     * 相同，把它们挡在 macOS 之外等于让非 macOS 用户白白少 7 门语言的一键启用。
     *
     * **为什么不补 Linux / Windows 版的 brew 系命令。** Linux 要分 apt / dnf /
     * pacman / zypper 四套，Windows 要 winget 包 ID，而本仓库**没有任何办法验证
     * 它们**（见 docs/superpowers/specs/2026-08-22-imux-cross-platform-design.md
     * 的「验证边界」）。编一条跑不通的命令挂在「启用」按钮上，比不给按钮更糟：
     * 剩下那 4 门在非 macOS 上保持退路——短目标名 + 完整命令 tooltip + 上游文档
     * 链接，那是一份完整产出。
     *
     * 将来有人在真机核实过，补数据即可，调用点不用动。
     */
    val macOnlyCommands: Set<String> =
        (servers.values.mapNotNull(LspServer::installCommand) + tools.values.mapNotNull(LspTool::installCommand))
            .filter { requiredTool(it) in NON_PORTABLE_TOOLS }
            .toSet()
```

在文件末尾（`private` 区）加：

```kotlin
/** 安装方式因发行版而异、无法跨平台照抄的包管理器。 */
private val NON_PORTABLE_TOOLS = setOf("brew", "opam")
```

`LspCatalog.kt` 顶部补 `import` 不需要——`requiredTool` 在同一个 `lsp` 包内。

同时把 `LspCatalog.kt` 第 58-59 行那句「Linux/Windows 用户看到的是同一条命令……
这是已知取舍」改写为：现在只有 brew/opam 那几条是取舍，其余已经是对的。

- [ ] **Step 4: 删 `canRun` 的 `hasPosixShell` 维度**

`lsp/LspRemedyRun.kt`：

```kotlin
/**
 * 这条修复建议该不该配一个执行按钮。
 *
 * 两个条件缺一不可：
 *
 * 1. **有命令可跑**。链为空的两种情况都在这里被挡下：没有已知安装命令的
 *    （`kotlin-language-server`、`sourcekit-lsp` 等目录表里 installCommand 为 null 的
 *    那几门），以及卡在一个我们装不上的前置工具上的（[Remedy.blockingTool] 非空时
 *    [Remedy.commands] 必为空）。给一个点了必然失败的按钮是空头承诺。
 * 2. **整条链在本平台上验证过**。判据是 [LspCatalog.macOnlyCommands]——只有 brew 与
 *    opam 系的命令是 macOS 专属；`npm` / `go` / `gem` / `dotnet` 那 7 条三平台形态
 *    相同，而 `claude plugin install` / `pi install` / `codex mcp add` 是 CLI 自己的
 *    子命令，不依赖任何外部工具链。
 *
 * **为什么是「链里含任何一条 macOS-only 就整条按 macOS-only 处理」。** 链是用 `&&`
 * 串起来交给终端的：第一条跑不通，后面一条都跑不到。「前半段能跑」这种中间态不存在，
 * 而一个跑到一半就红着停下的终端标签，比一开始就没有按钮更让人以为是插件坏了。
 *
 * **原先的第三个维度 `hasPosixShell` 已删除。** 它挡的是「Windows 上 `resolveShell`
 * 退回 `/bin/zsh`，按钮点下去报 `Cannot run program /bin/zsh`」——[resolveShell] 支持
 * Windows 之后这个前提消失了，本函数的旧 KDoc 亲口写着「`resolveShell` 支持 Windows
 * 之后，第 1 条该去掉」。
 *
 * [isMac] 作为**参数注入**而不是在这里读 `SystemInfo`：SystemInfo 是平台类，一旦在纯
 * 函数里读，这个函数就只能在开发者自己的机器上被测到一半——而这里最要命的分支恰恰
 * 是「不在 macOS 上会怎样」。
 */
internal fun canRun(
    remedy: Remedy,
    isMac: Boolean,
): Boolean =
    remedy.commands.isNotEmpty() &&
        (isMac || remedy.commands.none { it in LspCatalog.macOnlyCommands })
```

`settings/ImuxLspConfigurable.kt` 第 574 行：

```kotlin
        if (!canRun(remedy, SystemInfo.isMac)) {
```

- [ ] **Step 5: 处理被删的断言**

`LspRemedyRunTest` 里所有传三个参数的既有用例要改成两个参数。
**凡是专测 `hasPosixShell` 维度的用例（例如「Windows 上不给按钮」），删除时必须在
实现报告里逐条写明**：它守的是「`/bin/zsh` 在 Windows 上跑不了」，而 Task 2 已经让
Windows 拿到真实 shell，所以它守的东西确实没了。

`ImuxLspUiSourceTest` 里若有断言钉住 `canRun(remedy, SystemInfo.isMac, !SystemInfo.isWindows)`
这个调用形状，改成新形状；若有「壳里不得有第二处平台判断」的否定断言，**保留并确认
仍然成立**（`SystemInfo.isWindows` 现在只出现在 Task 2 的四个调用点，不在 UI 壳里）。

- [ ] **Step 6: 跑全量测试**

```
./gradlew clean test --offline
```

- [ ] **Step 7: 变异验证**

把 `NON_PORTABLE_TOOLS` 改成 `setOf("brew", "opam", "npm")` → 确认
`语言自带包管理器的安装命令不算 macOS 专属` FAIL → 还原。
把 `canRun` 改成恒 `true` → 确认 `非 macOS 上 brew 命令不给按钮` 与 `空链一律不给按钮` FAIL → 还原。

- [ ] **Step 8: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/lsp/LspRemedyRun.kt \
        src/main/kotlin/com/github/izerui/imux/lsp/LspCatalog.kt \
        src/main/kotlin/com/github/izerui/imux/settings/ImuxLspConfigurable.kt \
        src/test/kotlin/com/github/izerui/imux/lsp/LspRemedyRunTest.kt \
        src/test/kotlin/com/github/izerui/imux/lsp/LspCatalogTest.kt \
        src/test/kotlin/com/github/izerui/imux/settings/ImuxLspUiSourceTest.kt
git commit -m "放开非 macOS 的一键启用，只把 brew 与 opam 系命令留作 macOS 专属

macOnlyCommands 原本是目录表里所有安装命令的并集，于是 Linux/Windows 上
只有 CLI 自己的子命令能拿到按钮。而 13 个 server 里有 7 个用的是语言自带
的包管理器，那些命令三平台形态完全相同——白白少了 7 门语言。

同时删掉 canRun 的 hasPosixShell 维度：它挡的是 Windows 上退回 /bin/zsh
的按钮，resolveShell 支持 Windows 之后这个前提已经不存在。

brew 系那 4 门语言在非 macOS 上不补命令，保持文档链接退路——本仓库没有
办法验证 apt/dnf/pacman/winget 的写法，编一条跑不通的命令挂在按钮上比不给
按钮更糟。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: 阶段一收尾 —— 打包验证与文档回填

**Files:**
- Modify: `docs/superpowers/specs/2026-08-21-cli-lsp-diagnostics-followups.md`（第 10、10.1 条）

- [ ] **Step 1: 打包验证**

```
./gradlew clean test buildPlugin --offline
```

预期：测试全绿，`build/distributions/` 下产出 zip。

- [ ] **Step 2: 回填遗留文档**

在 `docs/superpowers/specs/2026-08-21-cli-lsp-diagnostics-followups.md` 的第 10 条与
第 10.1 条各追加一段结论，注明由
`docs/superpowers/specs/2026-08-22-imux-cross-platform-design.md` 解决，并写清楚
**没有全解决的部分**：brew 系 4 门语言的非 macOS 命令仍未补，原因是无法验证。

- [ ] **Step 3: 提交**

```bash
git add docs/superpowers/specs/2026-08-21-cli-lsp-diagnostics-followups.md
git commit -m "回填 LSP 遗留第 10 与 10.1 条的处置结论

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

# 阶段二：会话漂移探测（第 4 层）

**这里是一个自然的停止点。** 阶段一完成后 Windows / Linux 上会话能起、LSP 页可用，
是可独立交付的一版。阶段二补的是「用户在终端里敲 `/clear`、`/new` 之后标签能跟上」。

---

### Task 7: 跨平台路径与进程名处理

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/session/ProcessProbes.kt:62-72`（`codexPids`）
- Modify: `src/main/kotlin/com/github/izerui/imux/session/LiveSessionProbe.kt:143`（`fileNameOf`）
- Test: `src/test/kotlin/com/github/izerui/imux/session/ProcessProbesTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/session/LiveSessionProbeTest.kt`

**Interfaces:**
- Produces: `internal fun executableMatches(command: String, cli: String): Boolean`、
  `internal fun fileNameOf(path: String): String`（后者由 private 提升为 internal 以便测试）

- [ ] **Step 1: 先写失败的测试**

在 `ProcessProbesTest.kt` 追加：

```kotlin
    @Test
    fun `macOS 与 Linux 的可执行文件名匹配不变`() {
        assertTrue(executableMatches("/opt/homebrew/bin/codex", "codex"))
        assertTrue(executableMatches("/usr/local/bin/codex", "codex"))
        assertFalse(executableMatches("/usr/bin/tail", "codex"))
        // 整条命令行含 codex 不算——那会把 `tail -f codex.log` 也算进来
        assertFalse(executableMatches("/usr/bin/codex-helper", "codex"))
    }

    @Test
    fun `Windows 的反斜杠路径与 exe 后缀都能认出来`() {
        // 从前用 substringAfterLast('/') 比较，Windows 上两头都不匹配，
        // 结果是一个 codex 进程都认不出来，漂移探测整个静默失效
        assertTrue(executableMatches("C:\\Users\\me\\AppData\\npm\\codex.exe", "codex"))
        assertTrue(executableMatches("C:\\bin\\CODEX.EXE", "codex"))
        assertFalse(executableMatches("C:\\bin\\notcodex.exe", "codex"))
    }
```

在 `LiveSessionProbeTest.kt` 追加：

```kotlin
    @Test
    fun `rollout 路径在两种分隔符下都能取到文件名`() {
        val id = "c0b2cc08-746f-4dc6-bb78-636d380d9216"
        assertEquals(
            id,
            threadIdOfRollout("/Users/me/.codex/sessions/rollout-2026-08-06T13-59-47-$id.jsonl"),
        )
        assertEquals(
            id,
            threadIdOfRollout("C:\\Users\\me\\.codex\\sessions\\rollout-2026-08-06T13-59-47-$id.jsonl"),
        )
    }

    @Test
    fun `不是 rollout 形状的路径一律不认`() {
        assertNull(threadIdOfRollout("C:\\Users\\me\\.codex\\history.jsonl"))
        assertNull(threadIdOfRollout("/Users/me/.codex/history.jsonl"))
    }
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew test --offline --tests '*ProcessProbesTest*' --tests '*LiveSessionProbeTest*'
```

预期：`Windows 的反斜杠路径…` 与 `rollout 路径在两种分隔符下…` 的 Windows 半边 FAIL。

- [ ] **Step 3: 改实现**

`session/ProcessProbes.kt`，把 `codexPids` 里的内联比较抽成纯函数：

```kotlin
/**
 * 这条可执行文件路径是不是指定的 CLI。
 *
 * 按**可执行文件名**匹配而不是整条命令行包含——后者会把 `tail -f codex.log`、
 * 乃至本插件自己的 `zsh -c "codex resume ..."` 外壳都算进来。
 *
 * 两种分隔符都要切、`.exe` 后缀要去、比较不分大小写：Windows 上路径是
 * `C:\Users\me\AppData\npm\codex.exe`，只切 `/` 且区分大小写的话**一个进程都
 * 认不出来**，而失败是静默的——漂移探测什么也不报，看起来跟「没有漂移」一样。
 */
internal fun executableMatches(
    command: String,
    cli: String,
): Boolean =
    command
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .lowercase()
        .removeSuffix(".exe") == cli.lowercase()

internal fun codexPids(): List<Long> = runCatching {
    ProcessHandle.allProcesses()
        .filter { handle -> handle.info().command().map { executableMatches(it, "codex") }.orElse(false) }
        .map { it.pid() }
        .toList()
}.getOrElse {
    LOG.warn("扫描 codex 进程失败", it)
    emptyList()
}
```

`session/LiveSessionProbe.kt` 第 143 行：

```kotlin
/** 两种分隔符都切：Windows 上 rollout 路径来自 codex，用的是反斜杠。 */
internal fun fileNameOf(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
```

（由 `private` 改 `internal`，因为 `ProcessProbes` 与测试都要用。）

- [ ] **Step 4: 跑全量测试**

```
./gradlew test --offline
```

- [ ] **Step 5: 变异验证**

把 `.substringAfterLast('\\')` 删掉 → 确认两条 Windows 用例 FAIL、macOS 用例仍 PASS → 还原。
这一步同时证明改动在 macOS 输入上是恒等的。

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/session/ProcessProbes.kt \
        src/main/kotlin/com/github/izerui/imux/session/LiveSessionProbe.kt \
        src/test/kotlin/com/github/izerui/imux/session/ProcessProbesTest.kt \
        src/test/kotlin/com/github/izerui/imux/session/LiveSessionProbeTest.kt
git commit -m "进程名与路径匹配同时认反斜杠与 exe 后缀

codexPids 用 substringAfterLast('/') == \"codex\" 认进程，Windows 上分隔符是
反斜杠、文件名是 codex.exe，两头都不匹配——一个进程都认不出来，且失败是
静默的：漂移探测什么也不报，看起来跟「没有漂移」一样。

在 macOS 输入上这两处改动是恒等的，有用例钉住。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: Linux 的 `/proc` 探针

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/session/ProcLinuxProbe.kt`
- Create: `src/test/kotlin/com/github/izerui/imux/session/ProcLinuxProbeTest.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/session/ProcessProbes.kt`（`readTabId` / `readHeldRollouts` 分派）

**Interfaces:**
- Consumes: `IMUX_TAB_ENV`（已存在的常量）、`threadIdOfRollout`
- Produces:
  - `internal fun tabIdFromProcEnviron(bytes: ByteArray): String?`
  - `internal fun readTabIdFromProc(pid: Long, procRoot: Path): String?`
  - `internal fun readHeldRolloutsFromProc(pid: Long, procRoot: Path): List<String>`

- [ ] **Step 1: 先写失败的测试**

创建 `src/test/kotlin/com/github/izerui/imux/session/ProcLinuxProbeTest.kt`：

```kotlin
package com.github.izerui.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class ProcLinuxProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun environ(vararg entries: String): ByteArray =
        entries.joinToString("\u0000").toByteArray() + 0

    @Test
    fun `从 NUL 分隔的 environ 里取出 IMUX_TAB`() {
        val bytes = environ("PATH=/usr/bin", "IMUX_TAB=imux-abc", "LANG=zh_CN.UTF-8")
        assertEquals("imux-abc", tabIdFromProcEnviron(bytes))
    }

    @Test
    fun `值里含空格的其它变量不干扰`() {
        // ps 那一侧靠正则锚定变量名正是因为这个；/proc 是 NUL 分隔，天然没这个问题，
        // 但仍要钉住——将来有人改成按空格切就会红
        val bytes = environ("MSG=hello world here", "IMUX_TAB=imux-abc")
        assertEquals("imux-abc", tabIdFromProcEnviron(bytes))
    }

    @Test
    fun `变量名边界要卡死`() {
        assertNull(tabIdFromProcEnviron(environ("MY_IMUX_TAB=x")))
        assertNull(tabIdFromProcEnviron(environ("IMUX_TABS=x")))
    }

    @Test
    fun `没有这个变量返回 null`() {
        assertNull(tabIdFromProcEnviron(environ("PATH=/usr/bin")))
        assertNull(tabIdFromProcEnviron(ByteArray(0)))
    }

    @Test
    fun `空值当作没有`() {
        // 不是 imux 开的进程，或者 shell 把变量清了
        assertNull(tabIdFromProcEnviron(environ("IMUX_TAB=")))
    }

    @Test
    fun `读不到 proc 目录时返回 null 而不是抛异常`() {
        // 进程已退出、无权限——与 ps 失败同构，本轮不认领
        assertNull(readTabIdFromProc(4242, temp.root.toPath()))
    }

    @Test
    fun `从 proc fd 目录读出 rollout 路径`() {
        val procRoot = temp.root.toPath()
        val fd = Files.createDirectories(procRoot.resolve("777/fd"))
        val id = "c0b2cc08-746f-4dc6-bb78-636d380d9216"
        val rollout = temp.newFile("rollout-2026-08-06T13-59-47-$id.jsonl").toPath()
        val noise = temp.newFile("history.jsonl").toPath()
        Files.createSymbolicLink(fd.resolve("3"), rollout)
        Files.createSymbolicLink(fd.resolve("4"), noise)

        assertEquals(listOf(rollout.toString()), readHeldRolloutsFromProc(777, procRoot))
    }

    @Test
    fun `个别软链读不了不影响其余`() {
        val procRoot = temp.root.toPath()
        val fd = Files.createDirectories(procRoot.resolve("778/fd"))
        val id = "c0b2cc08-746f-4dc6-bb78-636d380d9216"
        val rollout = temp.newFile("rollout-2026-08-06T13-59-48-$id.jsonl").toPath()
        Files.createSymbolicLink(fd.resolve("3"), rollout)
        // 普通文件不是软链，readSymbolicLink 会抛——不能让它带倒整轮
        Files.createFile(fd.resolve("4"))

        assertEquals(listOf(rollout.toString()), readHeldRolloutsFromProc(778, procRoot))
    }

    @Test
    fun `进程目录不存在时返回空列表`() {
        assertEquals(emptyList<String>(), readHeldRolloutsFromProc(4242, temp.root.toPath()))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew test --offline --tests '*ProcLinuxProbeTest*'
```

预期：编译失败，`Unresolved reference: tabIdFromProcEnviron`。

- [ ] **Step 3: 写实现**

创建 `src/main/kotlin/com/github/izerui/imux/session/ProcLinuxProbe.kt`：

```kotlin
package com.github.izerui.imux.session

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path

// Linux 上的进程探测：直接读 /proc，不起子进程。
//
// 比 macOS 那条路（`ps eww` + `lsof`）严格更好：
// - 不起子进程（现在每轮探测起两个）
// - **不依赖 lsof**——很多发行版默认不装，现在的实现在那些机器上静默返回空
// - 没有输出格式解析，少一类最容易出错的东西
//
// procRoot 参数化是为了测试能指向临时目录，与 SessionRepository.forUserHome() 的
// 做法一致。生产入口传 /proc。

private val LOG = logger<ProcLinuxProbeLocation>()

private object ProcLinuxProbeLocation

/**
 * 从 `/proc/&lt;pid&gt;/environ` 的内容里取出 [IMUX_TAB_ENV]。
 *
 * 格式是 NUL 分隔的 `KEY=VALUE`，因此可以精确按条切分——不像 `ps eww` 把 env 和
 * 命令行拼在同一行、必须靠正则锚定变量名。变量名仍要整条相等地比，
 * 否则 `MY_IMUX_TAB` 与 `IMUX_TABS` 都会被误认。
 *
 * 空值当作没有：不是 imux 开的进程，或者 shell 把变量清了。
 */
internal fun tabIdFromProcEnviron(bytes: ByteArray): String? =
    String(bytes, Charsets.UTF_8)
        .split('\u0000')
        .firstNotNullOfOrNull { entry ->
            val separator = entry.indexOf('=')
            if (separator < 0) return@firstNotNullOfOrNull null
            if (entry.substring(0, separator) != IMUX_TAB_ENV) return@firstNotNullOfOrNull null
            entry.substring(separator + 1).takeIf { it.isNotBlank() }
        }

/**
 * 读一个进程的 [IMUX_TAB_ENV]。
 *
 * 读不到（进程已退出、无权限、非本用户进程）返回 null，本轮不认领——与 `ps` 失败同构。
 * `LiveSessionProbe` 的铁律是「认不出就跳过，不能猜」。
 */
internal fun readTabIdFromProc(
    pid: Long,
    procRoot: Path,
): String? =
    runCatching {
        tabIdFromProcEnviron(Files.readAllBytes(procRoot.resolve("$pid/environ")))
    }.getOrNull()

/**
 * 读一个进程正持有的 rollout 文件。
 *
 * `/proc/&lt;pid&gt;/fd/` 下每个条目都是指向被打开文件的软链。
 *
 * **个别软链读不了不能带倒整轮**：目录在遍历期间会变（进程随时开关文件），
 * 而 codex 同时开着一大堆无关文件——少认一个是软失败，整轮返回空则会让
 * 一个真在跑的会话被当成认不出来。
 */
internal fun readHeldRolloutsFromProc(
    pid: Long,
    procRoot: Path,
): List<String> =
    runCatching {
        Files.list(procRoot.resolve("$pid/fd")).use { entries ->
            entries
                .map { runCatching { Files.readSymbolicLink(it).toString() }.getOrNull() }
                .toList()
                .filterNotNull()
                .filter { threadIdOfRollout(it) != null }
        }
    }.getOrElse {
        LOG.debug("读取 /proc/$pid/fd 失败，本轮不认领该进程")
        emptyList()
    }
```

- [ ] **Step 4: 在 `ProcessProbes.kt` 里分派**

把现有的 `readTabId` 与 `readHeldRollouts` 改成按平台分派，**macOS 分支一个字节不改**：

```kotlin
/**
 * 读一个进程的 [IMUX_TAB_ENV]；读不到（进程已退出、权限不足）返回 null。
 *
 * Linux 走 `/proc`（不起子进程、无需 `ps`）；其余走 `ps eww`。
 * Windows 上**读不到别的进程的环境变量**（环境块在目标进程的 PEB 里，要
 * `ReadProcessMemory` + 调试权限），因此不走这条路——见 [WindowsTabPidFile]。
 */
internal fun readTabId(
    pid: Long,
    isLinux: Boolean = SystemInfo.isLinux,
    procRoot: Path = PROC_ROOT,
): String? =
    if (isLinux) {
        readTabIdFromProc(pid, procRoot)
    } else {
        tabIdFromPsOutput(runCommand(listOf("ps", "eww", "-p", pid.toString())) ?: return null)
    }

/** 读一个进程正持有的 rollout 文件。 */
internal fun readHeldRollouts(
    pid: Long,
    isLinux: Boolean = SystemInfo.isLinux,
    procRoot: Path = PROC_ROOT,
): List<String> =
    if (isLinux) {
        readHeldRolloutsFromProc(pid, procRoot)
    } else {
        rolloutPathsFromLsof(runCommand(listOf("lsof", "-p", pid.toString())) ?: return emptyList())
    }

/** 生产入口。参数化只为让分派本身可测——分派选错分支是这一层最难发现的错。 */
internal val PROC_ROOT: Path = Path.of("/proc")
```

`procRoot` 必须**从一开始就可注入**，否则「分派选错分支」这个错在 macOS 上根本
测不到——而它恰恰是本任务最容易写反的一行。

顶部补 `import com.intellij.openapi.util.SystemInfo` 与 `import java.nio.file.Path`。

**`tabIdFromPsOutput` 与 `rolloutPathsFromLsof` 两个纯解析函数一个字节不改**，
它们的既有用例全部保留。

- [ ] **Step 5: 补分派用例与 macOS 不回归用例**

在 `ProcessProbesTest.kt` 追加。**第一条钉的是分派本身**——选错分支是这一层最容易
写反、又最难发现的错（症状是漂移探测静默失效，与「没有漂移」长得一模一样）：

```kotlin
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `isLinux 为真时走 proc 而不是 ps`() {
        val procRoot = temp.root.toPath()
        val dir = Files.createDirectories(procRoot.resolve("999"))
        Files.write(dir.resolve("environ"), "IMUX_TAB=imux-abc\u0000".toByteArray())

        assertEquals("imux-abc", readTabId(999, isLinux = true, procRoot = procRoot))
    }

    @Test
    fun `isLinux 为假时不碰 proc`() {
        val procRoot = temp.root.toPath()
        val dir = Files.createDirectories(procRoot.resolve("999"))
        Files.write(dir.resolve("environ"), "IMUX_TAB=imux-abc\u0000".toByteArray())

        // 走的是 ps 那条路，本机没有 pid 999 这个进程，因此认不出来。
        // 若这条返回了 imux-abc，说明分派写反了。
        assertNull(readTabId(999, isLinux = false, procRoot = procRoot))
    }

    @Test
    fun `非 Linux 仍走 ps 与 lsof 的解析路径`() {
        // 这两个纯解析函数是 macOS 上正在工作的东西，Linux 分支不得影响它们
        assertEquals(
            "imux-abc",
            tabIdFromPsOutput("  501 22941 ttys003 PATH=/usr/bin IMUX_TAB=imux-abc /bin/zsh"),
        )
        assertNull(tabIdFromPsOutput("  501 22941 ttys003 MY_IMUX_TAB=imux-abc /bin/zsh"))
    }
```

- [ ] **Step 6: 跑全量测试**

```
./gradlew test --offline
```

- [ ] **Step 7: 变异验证**

把 `readTabId` 的 `isLinux` 分支反过来（`if (!isLinux)`）→ 确认
`isLinux 为真时走 proc 而不是 ps` 与 `isLinux 为假时不碰 proc` **两条同时 FAIL** → 还原。
把 `tabIdFromProcEnviron` 的变量名比较从 `!=` 改成 `!startsWith` → 确认
`变量名边界要卡死` FAIL → 还原。

- [ ] **Step 8: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/session/ProcLinuxProbe.kt \
        src/main/kotlin/com/github/izerui/imux/session/ProcessProbes.kt \
        src/test/kotlin/com/github/izerui/imux/session/ProcLinuxProbeTest.kt \
        src/test/kotlin/com/github/izerui/imux/session/ProcessProbesTest.kt
git commit -m "Linux 上改读 /proc，不再依赖 ps 与 lsof

lsof 在很多发行版上默认不装，现在的实现在那些机器上静默返回空——codex 的
会话漂移探测无声失效。/proc/<pid>/fd 是系统自带的，且不用起子进程、不用
解析命令输出。

macOS 分支的两个纯解析函数一个字节不改，既有用例全部保留。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: Windows 上 claude 的 pid 自报

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/session/WindowsTabPidFile.kt`
- Create: `src/test/kotlin/com/github/izerui/imux/session/WindowsTabPidFileTest.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt`（`launchCommand` 加 Windows 前缀）
- Modify: `src/main/kotlin/com/github/izerui/imux/session/ProcessProbes.kt`（`readTabId` 加 Windows 分支）
- Test: `src/test/kotlin/com/github/izerui/imux/terminal/AgentCommandTest.kt`

**Interfaces:**
- Consumes: `quote(dialect, value)`、`dialectOf`
- Produces:
  - `internal fun pidFileRecordCommand(dialect: ShellDialect, pidFile: String): String?`
  - `internal fun tabIdByParentChain(pid: Long, parentOf: (Long) -> Long?, tabIdOfShellPid: (Long) -> String?, maxDepth: Int = 8): String?`
  - `internal fun tabPidFilesIn(dir: Path): Map<Long, String>`

- [ ] **Step 1: 先写失败的测试**

创建 `src/test/kotlin/com/github/izerui/imux/session/WindowsTabPidFileTest.kt`：

```kotlin
package com.github.izerui.imux.session

import com.github.izerui.imux.terminal.ShellDialect
import com.github.izerui.imux.terminal.pidFileRecordCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WindowsTabPidFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `POSIX 方言不生成 pid 自报前缀`() {
        // macOS 与 Linux 靠环境变量认领，启动命令一个字都不该加
        assertNull(pidFileRecordCommand(ShellDialect.POSIX, "/tmp/imux/x.pid"))
    }

    @Test
    fun `PowerShell 方言把自己的 pid 写进指定文件`() {
        assertEquals(
            "\$PID | Set-Content -LiteralPath 'C:\\t\\x.pid' -Encoding ascii",
            pidFileRecordCommand(ShellDialect.POWERSHELL, "C:\\t\\x.pid"),
        )
    }

    @Test
    fun `pid 文件路径里的单引号按方言转义`() {
        assertEquals(
            "\$PID | Set-Content -LiteralPath 'C:\\o''brien\\x.pid' -Encoding ascii",
            pidFileRecordCommand(ShellDialect.POWERSHELL, "C:\\o'brien\\x.pid"),
        )
    }

    @Test
    fun `父链上溯认领标签`() {
        // powershell(100) → cmd(101) → node(102) → claude(103)
        val parents = mapOf(103L to 102L, 102L to 101L, 101L to 100L)
        val shells = mapOf(100L to "imux-abc")
        assertEquals(
            "imux-abc",
            tabIdByParentChain(103L, parentOf = parents::get, tabIdOfShellPid = shells::get),
        )
    }

    @Test
    fun `链上没有已知 shell 时不认领`() {
        // 用户自己在终端里敲的 claude，不属于任何 imux 标签
        val parents = mapOf(103L to 102L, 102L to 1L)
        assertNull(
            tabIdByParentChain(103L, parentOf = parents::get, tabIdOfShellPid = { null }),
        )
    }

    @Test
    fun `深度上限防止无谓遍历与环`() {
        // Windows 上 npm 装的 CLI 是 .cmd shim，链路可能是 powershell → cmd → node → claude，
        // 比 POSIX 深；但也不能无限走下去
        val parents = (2L..100L).associateWith { it - 1 }
        assertNull(
            tabIdByParentChain(
                100L,
                parentOf = parents::get,
                tabIdOfShellPid = { pid -> "imux-abc".takeIf { pid == 1L } },
                maxDepth = 8,
            ),
        )
    }

    @Test
    fun `自身就是已知 shell 时直接认领`() {
        // POSIX 的 -c 可能让 shell exec 掉自己，CLI 与 shell 同 pid
        assertNull(tabIdByParentChain(50L, parentOf = { null }, tabIdOfShellPid = { null }))
        assertEquals(
            "imux-abc",
            tabIdByParentChain(50L, parentOf = { null }, tabIdOfShellPid = { "imux-abc" }),
        )
    }

    @Test
    fun `读出目录下所有 pid 文件`() {
        val dir = temp.root.toPath()
        temp.newFile("imux-abc.pid").writeText("100\n")
        temp.newFile("imux-def.pid").writeText("200")
        temp.newFile("garbage.txt").writeText("300")
        temp.newFile("imux-bad.pid").writeText("not-a-number")

        assertEquals(mapOf(100L to "imux-abc", 200L to "imux-def"), tabPidFilesIn(dir))
    }

    @Test
    fun `目录不存在时返回空映射`() {
        assertEquals(emptyMap<Long, String>(), tabPidFilesIn(temp.root.toPath().resolve("missing")))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew test --offline --tests '*WindowsTabPidFileTest*'
```

预期：编译失败。

- [ ] **Step 3: 写 `pidFileRecordCommand`**

加进 `src/main/kotlin/com/github/izerui/imux/terminal/ShellDialect.kt` 末尾：

```kotlin
/**
 * 让 shell 把自己的 pid 写进 [pidFile] 的那一小段命令；POSIX 方言返回 null。
 *
 * **只有 Windows 需要它。** macOS 与 Linux 靠读目标进程的环境变量认领
 * （`ps eww` / `/proc/&lt;pid&gt;/environ`），启动命令一个字都不加。Windows 上
 * **读不到别的进程的环境变量**——环境块在目标进程的 PEB 里，要 `ReadProcessMemory`
 * 加调试权限，`Get-Process` 不给，JDK 也不给（`ProcessHandle.Info.arguments()`
 * 在 Windows 上恒为空）。于是换一条通道：启动命令本来就由 imux 全权拼接，
 * 让 shell 把自己的 pid 留下来，之后从 CLI 进程沿父链上溯即可对号入座。
 *
 * 写进的是 `PathManager.getSystemPath()` 下 imux 自己的目录，**不碰用户的任何配置**。
 *
 * `-Encoding ascii` 是必须的：PowerShell 5 的 `Set-Content` 默认写 UTF-16LE 带 BOM，
 * 读回来 `"100".toLong()` 会失败，而失败是静默的（该标签认不出来）。
 */
internal fun pidFileRecordCommand(
    dialect: ShellDialect,
    pidFile: String,
): String? =
    when (dialect) {
        ShellDialect.POSIX -> null
        ShellDialect.POWERSHELL ->
            "\$PID | Set-Content -LiteralPath ${quote(dialect, pidFile)} -Encoding ascii"
    }
```

- [ ] **Step 4: 写 `WindowsTabPidFile.kt`**

```kotlin
package com.github.izerui.imux.session

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path

// Windows 上「CLI 进程属于哪个终端标签」的身份通道。
//
// 全部做成纯函数并注入 IO，理由与 lsp/LspRemedyRun.kt 相同：这一层的正确性
// 全在「链走到哪停、什么时候不认领」上，而这些分支在 macOS 开发机上一条也走不到，
// 除非把进程关系注入进来。

private val LOG = logger<WindowsTabPidFileLocation>()

private object WindowsTabPidFileLocation

/** pid 文件的扩展名与前缀；文件名即 tabId。 */
private const val PID_FILE_SUFFIX = ".pid"
private const val TAB_ID_PREFIX = "imux-"

/**
 * 从 CLI 进程沿父链上溯，找出它属于哪个终端标签。
 *
 * 向上走而不是从每个 shell 向下找，是因为前者是 O(链长)、后者要遍历整张进程表。
 *
 * **先看自己再看父辈**：POSIX 的 `-c` 可能让 shell exec 掉自己，届时 CLI 与 shell
 * 同 pid（这条路 Windows 上走不到，但把判断写全，免得将来复用时踩）。
 *
 * [maxDepth] 默认 8：Windows 上 npm 装的 CLI 是 PATH 里的 `.cmd` shim，链路可能是
 * `powershell` &#8594; `cmd` &#8594; `node` &#8594; `claude`，比 POSIX 深；但也不能无限走下去——
 * 走到 pid 1 之外还有环的可能（pid 复用），有上限才有确定的收场。
 *
 * 认不出返回 null，本轮不认领。`LiveSessionProbe` 的铁律：认错会把终端迁到别人的
 * 会话上，比不迁移更糟。
 */
internal fun tabIdByParentChain(
    pid: Long,
    parentOf: (Long) -> Long?,
    tabIdOfShellPid: (Long) -> String?,
    maxDepth: Int = 8,
): String? {
    var current: Long? = pid
    repeat(maxDepth) {
        val at = current ?: return null
        tabIdOfShellPid(at)?.let { return it }
        current = parentOf(at)
    }
    return null
}

/**
 * 目录下所有 pid 文件，映射为 `shell pid → tabId`。
 *
 * 文件名即 tabId（`imux-&lt;uuid&gt;.pid`），内容是一个十进制 pid。
 * 内容不是数字的条目直接跳过而不是抛——目录里可能有写了一半的文件
 * （shell 刚起、还没写完就被探测撞上）。
 */
internal fun tabPidFilesIn(dir: Path): Map<Long, String> =
    runCatching {
        Files.list(dir).use { entries ->
            entries
                .toList()
                .mapNotNull { file ->
                    val name = file.fileName.toString()
                    if (!name.startsWith(TAB_ID_PREFIX) || !name.endsWith(PID_FILE_SUFFIX)) return@mapNotNull null
                    val pid =
                        runCatching { Files.readString(file).trim().toLong() }.getOrNull()
                            ?: return@mapNotNull null
                    pid to name.removeSuffix(PID_FILE_SUFFIX)
                }.toMap()
        }
    }.getOrElse {
        LOG.debug("读取 pid 文件目录失败：$dir")
        emptyMap()
    }

/** 删掉一个标签的 pid 文件；不存在也不报错。 */
internal fun deleteTabPidFile(dir: Path, tabId: String) {
    runCatching { Files.deleteIfExists(dir.resolve("$tabId$PID_FILE_SUFFIX")) }
        .onFailure { LOG.debug("删除 pid 文件失败：$tabId") }
}

/** 清扫整个目录，用于 IDE 启动时抹掉崩溃退出留下的残留。 */
internal fun sweepTabPidFiles(dir: Path) {
    runCatching {
        Files.list(dir).use { entries ->
            entries.toList().forEach { file ->
                if (file.fileName.toString().endsWith(PID_FILE_SUFFIX)) Files.deleteIfExists(file)
            }
        }
    }.onFailure { LOG.debug("清扫 pid 文件目录失败：$dir") }
}
```

- [ ] **Step 5: 把前缀接进 `launchCommand`**

`terminal/AgentCommand.kt` 的 `launchCommand` 增加一个参数并在末尾拼接：

```kotlin
internal fun launchCommand(
    shell: String,
    agentType: AgentType,
    resumeId: String?,
    piExtension: java.nio.file.Path? = null,
    initialPrompt: String? = null,
    pidFile: String? = null,
): List<String> {
    val dialect = dialectOf(shell)
    // …（中段不变）…
    val command =
        initialPrompt
            ?.takeIf { it.isNotBlank() }
            ?.let { "$script ${quote(dialect, it)}" }
            ?: script
    // pid 自报只在 Windows 分支生成：POSIX 方言下 pidFileRecordCommand 恒为 null，
    // macOS 与 Linux 的启动命令因此一个字都不加。
    //
    // **分隔符必须是 `;` 而不是 `&&`。** 写 pid 文件失败（目录没建起来、磁盘满、
    // 杀毒软件挡住）只该让这个标签认不出漂移，绝不能连带让整个会话起不来——
    // `&&` 会在第一条失败时短路掉 CLI，把一个软失败升级成硬失败。
    val prefixed =
        pidFile
            ?.let { pidFileRecordCommand(dialect, it) }
            ?.let { "$it; $command" }
            ?: command
    return listOf(shell) + shellArgs(dialect) + prefixed
}
```

在 `AgentCommandTest.kt` 追加：

```kotlin
    @Test
    fun `POSIX 上传了 pid 文件也不改变启动命令`() {
        // macOS 与 Linux 靠环境变量认领，命令行必须与改动前逐字节相同
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "claude"),
            launchCommand("/bin/zsh", AgentType.CLAUDE, resumeId = null, pidFile = "/tmp/x.pid"),
        )
    }

    @Test
    fun `PowerShell 上 pid 自报排在 CLI 之前`() {
        assertEquals(
            listOf(
                "pwsh.exe",
                "-NoLogo",
                "-NoProfile",
                "-Command",
                "\$PID | Set-Content -LiteralPath 'C:\\t\\x.pid' -Encoding ascii; claude",
            ),
            launchCommand("pwsh.exe", AgentType.CLAUDE, resumeId = null, pidFile = "C:\\t\\x.pid"),
        )
    }
```

- [ ] **Step 6: 在 `ProcessProbes.readTabId` 加 Windows 分支**

```kotlin
internal fun readTabId(
    pid: Long,
    isLinux: Boolean = SystemInfo.isLinux,
    isWindows: Boolean = SystemInfo.isWindows,
): String? =
    when {
        isWindows -> {
            val shells = tabPidFilesIn(imuxTabPidDir())
            tabIdByParentChain(
                pid,
                parentOf = { ProcessHandle.of(it).flatMap { h -> h.parent() }.map(ProcessHandle::pid).orElse(null) },
                tabIdOfShellPid = shells::get,
            )
        }

        isLinux -> readTabIdFromProc(pid, PROC_ROOT)

        else -> tabIdFromPsOutput(runCommand(listOf("ps", "eww", "-p", pid.toString())) ?: return null)
    }

/** imux 自己的临时目录，只放 pid 文件；**不碰用户的任何配置**。 */
internal fun imuxTabPidDir(): Path =
    PathManager.getSystemPath().let { Path.of(it, "imux", "tabs") }
```

`TerminalHost` 在建标签时把 `imuxTabPidDir().resolve("$tabId.pid")` 传给
`newCommand` / `resumeCommand`（仅当 `SystemInfo.isWindows`，否则传 null），
并在关闭标签时调 `deleteTabPidFile`；`ImuxStartupActivity` 里调一次 `sweepTabPidFiles`。
**目录要先 `Files.createDirectories`**，否则 `Set-Content` 会失败——而失败是静默的。

- [ ] **Step 7: 跑全量测试并变异验证**

```
./gradlew clean test --offline
```

把 `pidFileRecordCommand` 的 POSIX 分支改成返回非 null → 确认
`POSIX 上传了 pid 文件也不改变启动命令` FAIL → 还原。
把 `maxDepth` 默认值改成 100 → 确认 `深度上限防止无谓遍历与环` FAIL → 还原。

- [ ] **Step 8: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/session/WindowsTabPidFile.kt \
        src/main/kotlin/com/github/izerui/imux/session/ProcessProbes.kt \
        src/main/kotlin/com/github/izerui/imux/terminal/ShellDialect.kt \
        src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt \
        src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt \
        src/main/kotlin/com/github/izerui/imux/monitor/ImuxStartupActivity.kt \
        src/test/kotlin/com/github/izerui/imux/session/WindowsTabPidFileTest.kt \
        src/test/kotlin/com/github/izerui/imux/terminal/AgentCommandTest.kt
git commit -m "Windows 上改用 shell 自报 pid 认领终端标签

Windows 读不到别的进程的环境变量——环境块在目标进程的 PEB 里，要
ReadProcessMemory 加调试权限，Get-Process 与 JDK 都不给。于是换一条通道：
启动命令本来就由 imux 拼接，让 shell 把自己的 pid 留下，之后从 CLI 进程
沿父链上溯对号入座。

pid 文件写进 PathManager 的系统目录，不碰用户的任何 CLI 配置。
POSIX 方言下这段前缀恒为 null，macOS 与 Linux 的启动命令一个字不加。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: Windows 上 codex 的 hook 注入与上报

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/terminal/CodexHookOverride.kt`
- Create: `src/test/kotlin/com/github/izerui/imux/terminal/CodexHookOverrideTest.kt`
- Create: `src/main/scripts/codex-imux-reporter.ps1`
- Modify: `src/main/kotlin/com/github/izerui/imux/session/PiReportEndpoint.kt`（加路径常量）
- Modify: `src/main/kotlin/com/github/izerui/imux/session/PiSessionReportHandler.kt`（加 codex 分支）
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt`（`launchEnvironment` 给 codex 发端点）
- Modify: `build.gradle.kts`（打包 `.ps1`、`:test` 输入加 `src/main/scripts`）

**Interfaces:**
- Consumes: `quote(dialect, value)`、`dialectOf(shellPath)`、`PiReportEndpoint`、
  `piReportTokenMatches(actual, expected)`、`locatePiReporterScript(pluginPath, classPathEntry)`
- Produces:
  - `internal fun tomlBasicString(value: String): String`
  - `internal fun codexHookOverrideArg(dialect: ShellDialect, scriptPath: String): String`
  - `internal fun codexReporterScript(): Path?`（脚本相对路径 `scripts/codex-imux-reporter.ps1`）
  - `const val CODEX_REPORT_PATH: String = "/imux/codex-session"`
  - `internal fun handlesCodexReport(uri: String, isPost: Boolean): Boolean`
  - `launchEnvironment` 签名变为
    `internal fun launchEnvironment(agentType: AgentType, tabId: String, piReport: PiReportEndpoint? = null, isWindows: Boolean = false, codexReport: PiReportEndpoint? = null): Map<String, String>`
  - `launchCommand` 增加 `codexHookScript: String? = null` 形参（Task 9 已加过 `pidFile`）

- [ ] **Step 1: 先写失败的测试**

创建 `src/test/kotlin/com/github/izerui/imux/terminal/CodexHookOverrideTest.kt`：

```kotlin
package com.github.izerui.imux.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class CodexHookOverrideTest {

    @Test
    fun `TOML 基本字符串转义反斜杠与双引号`() {
        // Windows 路径全是反斜杠；TOML 基本字符串里 \ 是转义引导符，不转义会解析失败
        assertEquals("\"C:\\\\Users\\\\me\\\\r.ps1\"", tomlBasicString("C:\\Users\\me\\r.ps1"))
        assertEquals("\"say \\\"hi\\\"\"", tomlBasicString("say \"hi\""))
        assertEquals("\"plain\"", tomlBasicString("plain"))
    }

    @Test
    fun `hook 覆盖实参是 codex 认识的 schema`() {
        // schema 由 codex debug models -c 逐层用错类型试出来：
        // hooks.SessionStart 是 MatcherGroup 序列，MatcherGroup.hooks 是
        // HookHandlerConfig 序列，type="command" 时 command 必填且为字符串
        val arg = codexHookOverrideArg(ShellDialect.POWERSHELL, "C:\\p\\r.ps1")
        assertEquals(
            "'hooks.SessionStart=[{hooks=[{type=\"command\"," +
                "command=\"powershell -NoLogo -NoProfile -File \\\"C:\\\\p\\\\r.ps1\\\"\"}]}]'",
            arg,
        )
    }

    @Test
    fun `实参外层套的是方言引号`() {
        // 这是全项目嵌套引号最深的一处：TOML 字符串里嵌着脚本路径，外面再套一层 shell 引号
        val arg = codexHookOverrideArg(ShellDialect.POWERSHELL, "C:\\o'brien\\r.ps1")
        // PowerShell 单引号内的单引号写成两个
        assertEquals(true, arg.startsWith("'"))
        assertEquals(true, arg.endsWith("'"))
        assertEquals(true, arg.contains("o''brien"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew test --offline --tests '*CodexHookOverrideTest*'
```

预期：编译失败。

- [ ] **Step 3: 写实现**

创建 `src/main/kotlin/com/github/izerui/imux/terminal/CodexHookOverride.kt`：

```kotlin
package com.github.izerui.imux.terminal

/**
 * 包成 TOML 基本字符串。
 *
 * Windows 路径全是反斜杠，而 TOML 基本字符串里 `\` 是转义引导符——不转义的话
 * `C:\Users\me` 里的 `\U` 会被当成 Unicode 转义，codex 解析配置直接报错，
 * 表现是会话起不来。
 */
internal fun tomlBasicString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * codex 的 `-c hooks.SessionStart=…` 实参，已按 [dialect] 加好外层引号。
 *
 * **为什么用 `-c` 而不是写 `~/.codex/hooks.json`。** 用户的要求是「别改 cli 的配置
 * 文件本身」，而 `plugin.xml` 的描述里也写着「imux does not host or modify your
 * sessions」。`-c` 是命令行覆盖，与 pi 的 `-e &lt;扩展路径&gt;` 是同一个形状。
 *
 * **schema 是实证得出的**，不是推断——用 `codex debug models -c` 逐层塞错类型试出来：
 * `hooks.SessionStart` 要 `MatcherGroup` 序列；`MatcherGroup.hooks` 要
 * `HookHandlerConfig` 序列（内部标记枚举，标记字段是 `type`）；`type="command"`
 * 时 `command` 必填且必须是字符串。与 Claude Code 的 hook 结构同族。
 *
 * 只在 Windows 上注入：macOS 与 Linux 走 `lsof` / `/proc` 读文件句柄，那条路
 * 正在工作，不该为统一而动它。
 */
internal fun codexHookOverrideArg(
    dialect: ShellDialect,
    scriptPath: String,
): String {
    // 内层这对引号是**给 shell 看的**，不是 TOML 引号——codex 把 command 的值整条
    // 交给 shell 执行，路径里有空格时全靠它。因此这里用**原始路径**，不预先转义。
    val command = "powershell -NoLogo -NoProfile -File \"$scriptPath\""
    // TOML 转义只施加一次，作用于整条 command。反斜杠在这一步翻倍、内层引号在这一步
    // 变成 \"——这正是期望值的形状。
    val toml = "hooks.SessionStart=[{hooks=[{type=\"command\",command=${tomlBasicString(command)}}]}]"
    return quote(dialect, toml)
}
```

**这里极易写错，务必对着测试期望值核**：把内层写成 `tomlBasicString(scriptPath)`
会让路径**被转义两次**（`C:\p` 变成 `C:\\\\p` 而不是 `C:\\p`），TOML 解析出来的
路径带着多余的反斜杠。转义只能施加一次，且必须在最外层——内层那对引号属于 shell。

**而且写错了不会报错。** 实测把两种写法都喂给 `codex debug models -c`，
**codex 全部接受**——双重转义在 TOML 语法上完全合法，只是解析出来的路径不存在。
症状是 hook 静默地永不触发，与「Windows 上 codex 漂移探测没做」长得一模一样。
`CodexHookOverrideTest` 里那条期望值是这一层唯一的守卫。

`tomlBasicString` 内部两个 `replace` 的**顺序也不能换**：先转义反斜杠再转义引号，
否则第二步给引号添的那个反斜杠会被第一步漏掉（换了顺序则会被重复转义）。

- [ ] **Step 4: 写上报脚本**

创建 `src/main/scripts/codex-imux-reporter.ps1`：

```powershell
# codex 的 SessionStart hook：把「这个终端标签现在在跑哪个会话」报回 imux。
#
# 为什么需要它：Windows 上读不到别的进程打开的文件句柄（要 Sysinternals handle.exe，
# 不自带、要管理员权限），而 codex 没有运行态文件（state_5.sqlite 的 threads 表
# 无 pid 字段）。两条观测面全断，只能由 codex 自己说。
#
# 本脚本由 codex 执行，因此继承 codex 进程的环境变量——IMUX_TAB 就是这么拿到的。
$ErrorActionPreference = 'Stop'
try {
    $tab = $env:IMUX_TAB
    $url = $env:IMUX_REPORT_URL
    $token = $env:IMUX_TOKEN
    if (-not $tab -or -not $url -or -not $token) { exit 0 }

    $payload = [Console]::In.ReadToEnd() | ConvertFrom-Json
    $sessionId = $payload.session_id
    if (-not $sessionId) { exit 0 }

    $body = @{ tabId = $tab; sessionId = $sessionId } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$url" -Body $body `
        -ContentType 'application/json' -Headers @{ 'x-imux-token' = $token } | Out-Null
} catch {
    # 上报失败只是标签不自动跟随，绝不能让 codex 的会话启动受影响
    exit 0
}
```

`build.gradle.kts` 第 102 行附近加一行打包：

```kotlin
    from("src/main/scripts/codex-imux-reporter.ps1") { into("${project.name}/scripts") }
```

第 119 行的 `:test` 输入列表加上 `"src/main/scripts"`：

```kotlin
    listOf("src/main/kotlin", "src/main/resources", "src/main/js", "src/main/scripts").forEach { dir ->
```

- [ ] **Step 5: 加上报路径与 handler 分支**

`session/PiReportEndpoint.kt` 加常量：

```kotlin
/** codex hook 的上报路径。与 pi 分开而不是在 body 里加判别字段：
 *  `handlesPiReport` 是被用例钉住的纯函数，加判别字段要改它与 `parsePiReport`
 *  的全部用例；并列一条新路径则 pi 那一侧逐字节不变。 */
const val CODEX_REPORT_PATH: String = "/imux/codex-session"
```

`session/PiSessionReportHandler.kt`：`isSupported` 与 `process` 各加一条并列分支，
**令牌校验走同一个 `piReportTokenMatches`**，不得另写比较逻辑（该函数的 KDoc 列了
四种写宽就会漏的写法）。新增纯函数 `handlesCodexReport(uri, isPost)`，形状与
`handlesPiReport` 一致并配同样的用例。

`terminal/AgentCommand.kt` 的 `launchEnvironment`：`AgentType.CODEX` 分支从
`Unit` 改为发端点，**仅当 Windows**：

```kotlin
            AgentType.CODEX -> {
                // 令牌只发给需要上报的进程：它是上报接口唯一的门禁，
                // 多发一个进程就多一份泄漏面。非 Windows 上 codex 走 lsof / /proc，
                // 不需要上报，因此也不发。
                if (isWindows) {
                    codexReport?.let {
                        put("IMUX_REPORT_URL", it.url)
                        put("IMUX_TOKEN", it.token)
                    }
                }
            }
```

`launchEnvironment` 因此要新增 `isWindows: Boolean` 与 `codexReport: PiReportEndpoint?`
两个参数，并补一条用例钉住「非 Windows 上 codex 的环境变量与改动前逐字节相同」。

`launchCommand` 在 `agentType == AgentType.CODEX && dialect == POWERSHELL` 时，
把 `-c ${codexHookOverrideArg(dialect, scriptPath)}` 拼进命令；脚本定位复用
`locatePiReporterScript` 的同款做法（新增 `codexReporterScript()`，脚本相对路径
`scripts/codex-imux-reporter.ps1`）。**脚本找不到时不加 `-c`**——理由与 pi 的 `-e`
完全相同：拼一个加载不了的路径会让 CLI 启动失败，那是整个会话起不来，而少了上报
只是标签不自动跟随。

- [ ] **Step 6: 跑全量测试并变异验证**

```
./gradlew clean test --offline
```

把 `tomlBasicString` 里的 `.replace("\\", "\\\\")` 删掉 → 确认
`TOML 基本字符串转义反斜杠与双引号` FAIL → 还原。
把 `launchEnvironment` 的 `if (isWindows)` 去掉 → 确认「非 Windows 上 codex 的
环境变量逐字节不变」FAIL → 还原。

- [ ] **Step 7: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/terminal/CodexHookOverride.kt \
        src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt \
        src/main/kotlin/com/github/izerui/imux/session/PiReportEndpoint.kt \
        src/main/kotlin/com/github/izerui/imux/session/PiSessionReportHandler.kt \
        src/main/scripts/codex-imux-reporter.ps1 \
        build.gradle.kts \
        src/test/kotlin/com/github/izerui/imux/terminal/CodexHookOverrideTest.kt \
        src/test/kotlin/com/github/izerui/imux/session/PiSessionReportHandlerTest.kt
git commit -m "Windows 上给 codex 注入 SessionStart hook 上报会话

Windows 读不到别的进程打开的文件句柄（要 Sysinternals handle.exe，不自带、
要管理员权限），而 codex 没有运行态文件——两条观测面全断，只能由 codex
自己说。

用 -c 命令行覆盖而不是写 ~/.codex/hooks.json：用户明确要求别改 CLI 的配置
文件本身，而这与 pi 的 -e 扩展是同一个形状。hook 的 schema 是用
codex debug models -c 逐层塞错类型实证出来的，不是推断。

只在 Windows 上注入。macOS 与 Linux 走 lsof / /proc，那条路正在工作，
不该为统一而动它。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 11: 交付收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-08-22-imux-cross-platform-design.md`（追加交付说明）

- [ ] **Step 1: 全量验证**

```
./gradlew clean test buildPlugin --offline
```

预期：测试全绿，`build/distributions/` 下产出 zip。

- [ ] **Step 2: 写交付说明**

在设计文档末尾追加一节「交付状态」，**逐条区分已验证与未验证**：

- 已验证：macOS 上全量测试通过、打包通过、所有 macOS 形态的返回值逐字节钉死
- **未验证：Linux 与 Windows 上的任何一行运行时行为**。本仓库没有那两个平台的环境
- 未验证的具体清单照抄设计文档「验证边界」一节的第二栏

**不得把推断写成验证。**

- [ ] **Step 3: 提交**

```bash
git add docs/superpowers/specs/2026-08-22-imux-cross-platform-design.md
git commit -m "补记三平台支持的交付状态与未验证清单

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 真机验证清单（只有仓库所有者能做）

代码交付后仍需在真机上确认这些，**它们无法在本仓库的环境里验证**：

1. **Windows**：会话标签能起来（三个 CLI 各一次）
2. **Windows**：LSP 页 18 门语言不再全是「无法确定」
3. **Windows**：点「启用」后终端里真的跑起了命令
4. **Windows**：在 codex 里敲 `/new`，标签是否跟上（这一条依赖 codex 的 hook 在
   `/new` 时触发，以及 `-c` 注入的 hook 不弹信任提示——两条都未验证）
5. **Windows**：在 claude 里敲 `/clear`，标签是否跟上（依赖 pid 自报与父链上溯）
6. **Linux**：会话能起、LSP 页能探
7. **Linux**：`/proc` 探针在无 `lsof` 的发行版上工作
8. **macOS**：整体回归——会话启动、LSP 页、漂移探测三者行为与改动前一致
