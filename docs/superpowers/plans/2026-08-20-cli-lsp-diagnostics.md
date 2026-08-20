# CLI LSP 体检 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 imux 加一个应用级设置子页，检测 Claude Code、pi、Codex 三个 CLI 的 LSP 覆盖情况，列出缺失语言并给出可复制的安装命令。

**Architecture:** 一个静态语言/服务器目录表 + 三个互相独立的探针（各读各自 CLI 的全局配置文件）+ 一个共享的二进制存在性探测器（经用户登录 shell 一次批量查询）。三者由一个编排器汇总成报告，设置页只负责渲染。全部只读，不写任何用户文件、不执行安装。

**Tech Stack:** Kotlin 2.3.21 / IntelliJ Platform 262 / Gson（平台捆绑）/ JUnit 4 / IntelliJ UI DSL v2

设计依据：`docs/superpowers/specs/2026-08-20-cli-lsp-diagnostics-design.md`

## Global Constraints

- **只支持 IntelliJ IDEA 2026.2（build 262）**。不写兼容分支、不用反射绕 `internal`/私有 API。
- **UI 一律优先平台原生**：图标取 `com.intellij.icons.AllIcons` 语义匹配项，不自绘 SVG；按钮走 Action System；尺寸间距用 `JBUI`/`UIUtil`。
- **测试只能用 JUnit 4**（`org.junit.Test` / `org.junit.Assert`）。`com.jetbrains.intellij.platform:test-framework` 未引入，**不可使用 `BasePlatformTestCase` 及任何平台测试基类**。UI 与扩展点注册用源码级断言测试（见既有 `ImuxSettingsUiSourceTest`、`PluginXmlRegistrationTest`）。
- **Gson 可用**：`com.google.gson` 由平台捆绑（`lib/intellij.libraries.gson.jar`），已实测编译通过。**`com.fasterxml.jackson.dataformat.toml` 不在编译 classpath 上**，实测 `Unresolved reference 'toml'`——Codex 的 `config.toml` 必须手写扫描。
- **i18n 有 10 个语言文件**（`src/main/resources/messages/ImuxBundle*.properties`）。`ImuxBundleTest` 强制：所有语言键集合完全一致、占位符集合一致、无参消息不得含 `''`、带参消息经 `MessageFormat` 往返后不得丢参数。新增任何一个键都必须同时补齐 10 个文件。
- **不阻塞 EDT**：shell 探测与文件读取一律走后台协程。
- **测试用中文反引号命名**，与既有测试一致，例如 ``fun `解析会话切换上报体`()``。
- **提交信息用中文**，正文说明「为什么」而不只是「做了什么」，与既有 git 历史一致。

## File Structure

新增包 `com.github.izerui.imux.lsp`：

| 文件 | 职责 |
| --- | --- |
| `lsp/LspCatalog.kt` | 静态数据：语言表、服务器表、安装命令。纯数据，无逻辑。 |
| `lsp/LspReport.kt` | 结果模型：`LspStatus` 枚举、`LanguageFinding`、`CliReport`、`LspReport`。 |
| `lsp/BinaryProbe.kt` | 二进制存在性探测接口 + 经登录 shell 的实现。 |
| `lsp/ClaudeCodeLspProbe.kt` | 解析 Claude Code 全局配置与 marketplace 清单。 |
| `lsp/PiLspProbe.kt` | 解析 pi 全局 settings 的 `packages`。 |
| `lsp/CodexLspProbe.kt` | 扫描 `~/.codex/config.toml` 的 `mcp_servers`。 |
| `lsp/TomlSectionScanner.kt` | 手写 TOML 段落扫描，只服务 Codex 探针。 |
| `lsp/LspDiagnostics.kt` | 编排三个探针，产出 `LspReport`。 |
| `settings/ImuxLspConfigurable.kt` | 设置子页 UI。 |

修改：
- `src/main/resources/META-INF/plugin.xml`：注册新 `applicationConfigurable`
- `src/main/resources/messages/ImuxBundle*.properties`（10 个）：新增 12 个键
- `src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt`：`singleQuote` 由 `private` 改 `internal` 以便复用

---

### Task 1: 静态目录表与结果模型

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/lsp/LspCatalog.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/lsp/LspReport.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/lsp/LspCatalogTest.kt`

**Interfaces:**
- Produces: `LspCatalog.languages: List<LspLanguage>`、`LspCatalog.server(binary: String): LspServer?`、`LspLanguage(id, displayName, claudePlugin, claudeBinary, piLensGated, piLensBinary)`、`LspServer(binary, installCommand, docsUrl)`、`LspStatus` 枚举、`LanguageFinding(language, status, remedy)`、`CliReport(agentType, installed, findings, groupRemedy)`、`LspReport(claude, pi, codex)`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/kotlin/com/github/izerui/imux/lsp/LspCatalogTest.kt`：

```kotlin
package com.github.izerui.imux.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LspCatalogTest {

    @Test
    fun `语言 id 不重复`() {
        val ids = LspCatalog.languages.map(LspLanguage::id)
        assertEquals("语言 id 必须唯一", ids.size, ids.toSet().size)
    }

    /**
     * Claude Code 与 pi-lens 对同一语言可能用不同的 server（Kotlin 上是
     * kotlin-lsp 与 kotlin-language-server），两个二进制都必须在服务器表里
     * 有安装信息，否则 UI 拿不到修复命令，缺口只能显示成一句「未安装」。
     */
    @Test
    fun `每个引用到的二进制都能在服务器表里查到`() {
        LspCatalog.languages.forEach { language ->
            listOfNotNull(language.claudeBinary, language.piLensBinary).forEach { binary ->
                assertNotNull("服务器表缺少 $binary（${language.id} 引用）", LspCatalog.server(binary))
            }
        }
    }

    @Test
    fun `声明了 Claude 插件就必须同时声明其二进制`() {
        LspCatalog.languages.forEach { language ->
            assertEquals(
                "${language.id}: claudePlugin 与 claudeBinary 必须同时存在或同时缺失",
                language.claudePlugin == null,
                language.claudeBinary == null,
            )
        }
    }

    /** gated 的语言才需要用户自己装 server，非 gated 由 pi-lens 自动安装、不该有二进制名。 */
    @Test
    fun `pi-lens 二进制只在 gated 语言上声明`() {
        LspCatalog.languages.forEach { language ->
            assertEquals(
                "${language.id}: piLensGated 与 piLensBinary 必须一致",
                language.piLensGated,
                language.piLensBinary != null,
            )
        }
    }

    /** 没有已知安装命令时必须给文档链接，否则用户在 UI 上拿不到任何下一步。 */
    @Test
    fun `没有安装命令的服务器必须有文档链接`() {
        LspCatalog.servers.values.forEach { server ->
            assertTrue(
                "${server.binary}: installCommand 与 docsUrl 不能同时为空",
                server.installCommand != null || server.docsUrl.isNotBlank(),
            )
        }
    }

    @Test
    fun `覆盖 spec 点名的 toolchain-gated 语言`() {
        val gated = LspCatalog.languages.filter(LspLanguage::piLensGated).map(LspLanguage::id).toSet()
        setOf("go", "java", "kotlin", "swift", "lua", "cpp", "haskell", "elixir", "ocaml", "nix", "fsharp")
            .forEach { assertTrue("缺少 pi-lens gated 语言 $it", it in gated) }
    }

    @Test
    fun `覆盖官方 12 个 Claude Code LSP 插件`() {
        val plugins = LspCatalog.languages.mapNotNull(LspLanguage::claudePlugin).toSet()
        setOf(
            "clangd-lsp", "csharp-lsp", "gopls-lsp", "jdtls-lsp", "kotlin-lsp", "lua-lsp",
            "php-lsp", "pyright-lsp", "ruby-lsp", "rust-analyzer-lsp", "swift-lsp", "typescript-lsp",
        ).forEach { assertTrue("缺少官方插件 $it", it in plugins) }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*LspCatalogTest*' --offline`
Expected: 编译失败，`Unresolved reference 'LspCatalog'`

- [ ] **Step 3: 写结果模型**

创建 `src/main/kotlin/com/github/izerui/imux/lsp/LspReport.kt`：

```kotlin
package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType

/**
 * 一门语言在某个 CLI 上的 LSP 可用状态。
 *
 * 「配置缺口」与「二进制缺口」必须分开：前者一行命令就能补（本机实测的真实例子是
 * kotlin-lsp 二进制已装、Claude Code 却没启用对应插件），后者要下载安装。
 * 混成一个「不可用」会让用户对着一个 1.3GB 的下载命令发呆，而其实只差一行配置。
 */
internal enum class LspStatus {
    /** 配置到位且 server 二进制在 PATH 里。 */
    READY,

    /** 二进制可能在，但该 CLI 没配上。 */
    MISSING_CONFIG,

    /** 配置到位，但 server 不在 PATH。 */
    MISSING_BINARY,

    /** 探测超时或失败，信息不足以判断——不猜。 */
    UNKNOWN,
}

/** 一条修复建议：可复制的命令，或（没有已知命令时）一个上游文档链接。 */
internal data class Remedy(val command: String?, val docsUrl: String?)

internal data class LanguageFinding(
    val language: LspLanguage,
    val status: LspStatus,
    val remedy: Remedy?,
)

/**
 * 单个 CLI 的体检结果。
 *
 * [installed] 为 false 时 [findings] 必为空：用户只装了 pi 却收到一堆 Claude Code
 * 的缺口提示是纯噪音，README 已明确「装一个也能用」。
 *
 * [groupRemedy] 是整组级别的前置修复（pi 未装 pi-lens、Codex 未挂 MCP），
 * 它存在时逐语言的缺口没有意义——先把前置补上。
 */
internal data class CliReport(
    val agentType: AgentType,
    val installed: Boolean,
    val findings: List<LanguageFinding>,
    val groupRemedy: Remedy? = null,
) {
    val ready: List<LanguageFinding> get() = findings.filter { it.status == LspStatus.READY }
    val gaps: List<LanguageFinding> get() = findings.filter { it.status != LspStatus.READY }
}

internal data class LspReport(val cliReports: List<CliReport>)
```

- [ ] **Step 4: 写静态目录表**

创建 `src/main/kotlin/com/github/izerui/imux/lsp/LspCatalog.kt`：

```kotlin
package com.github.izerui.imux.lsp

/**
 * 一门语言在 Claude Code 与 pi-lens 两条路径上的接入方式。
 *
 * 两边的 server 二进制**可能不同**：Kotlin 上 Claude Code 官方插件用 JetBrains 的
 * `kotlin-lsp`，pi-lens 用社区的 `kotlin-language-server`。所以分成两个字段、
 * 分别探测，不能合并。
 */
internal data class LspLanguage(
    val id: String,
    val displayName: String,
    /** Claude Code 官方 marketplace 插件名；null 表示官方没有对应插件。 */
    val claudePlugin: String?,
    /** 上述插件在 `lspServers.command` 里要求的二进制。 */
    val claudeBinary: String?,
    /** pi-lens 是否需要用户手动安装该语言的 server（toolchain-gated 家族）。 */
    val piLensGated: Boolean,
    /** pi-lens 使用的二进制；非 gated 语言为 null——pi-lens 会自己装。 */
    val piLensBinary: String?,
)

/**
 * 一个语言服务器的安装方式。
 *
 * [installCommand] 只填**已核实**的命令，拿不准一律留 null 并给 [docsUrl]——
 * 编一条跑不通的命令比不给命令更糟：用户会以为是自己环境的问题。
 * 补充命令是纯数据改动，后续随时可加。
 */
internal data class LspServer(
    val binary: String,
    val installCommand: String?,
    val docsUrl: String,
)

internal object LspCatalog {

    /**
     * 安装命令按 macOS 给出（imux 的主要用户在 macOS，且本机可核实）。
     * Linux/Windows 用户看到的是同一条命令——它对 npm/go/dotnet/rustup 这些
     * 跨平台工具本来就通用；只有 brew 那几条不通用，这是已知取舍，
     * 后续要分平台时把 String 换成按平台取值即可，调用点不变。
     */
    val servers: Map<String, LspServer> = listOf(
        // 以下五条在本机实测确认了安装来源
        LspServer("jdtls", "brew install jdtls", "https://github.com/eclipse-jdtls/eclipse.jdt.ls"),
        LspServer("kotlin-lsp", "brew install --cask kotlin-lsp", "https://github.com/Kotlin/kotlin-lsp"),
        LspServer("lua-language-server", "brew install lua-language-server", "https://luals.github.io"),
        LspServer("gopls", "go install golang.org/x/tools/gopls@latest", "https://pkg.go.dev/golang.org/x/tools/gopls"),
        LspServer(
            "typescript-language-server",
            "npm install -g typescript-language-server typescript",
            "https://github.com/typescript-language-server/typescript-language-server",
        ),
        LspServer("pyright-langserver", "npm install -g pyright", "https://github.com/microsoft/pyright"),

        // 以下为上游文档给出的标准安装方式
        LspServer("clangd", "brew install llvm", "https://clangd.llvm.org/installation"),
        LspServer("csharp-ls", "dotnet tool install --global csharp-ls", "https://github.com/razzmatazz/csharp-language-server"),
        LspServer("intelephense", "npm install -g intelephense", "https://intelephense.com"),
        LspServer("ruby-lsp", "gem install ruby-lsp", "https://shopify.github.io/ruby-lsp"),
        LspServer("rust-analyzer", "rustup component add rust-analyzer", "https://rust-analyzer.github.io"),
        LspServer("kotlin-language-server", null, "https://github.com/fwcd/kotlin-language-server"),
        LspServer("sourcekit-lsp", null, "https://github.com/swiftlang/sourcekit-lsp"),
        LspServer("haskell-language-server-wrapper", null, "https://haskell-language-server.readthedocs.io"),
        LspServer("elixir-ls", null, "https://github.com/elixir-lsp/elixir-ls"),
        LspServer("ocamllsp", "opam install ocaml-lsp-server", "https://github.com/ocaml/ocaml-lsp"),
        LspServer("nixd", null, "https://github.com/nix-community/nixd"),
        LspServer("fsautocomplete", "dotnet tool install --global fsautocomplete", "https://github.com/ionide/FsAutoComplete"),
    ).associateBy(LspServer::binary)

    fun server(binary: String): LspServer? = servers[binary]

    /**
     * 语言集合 = 官方 12 个 Claude Code LSP 插件覆盖的语言 ∪ pi-lens 的 11 个
     * toolchain-gated 语言。非 gated 的 pi-lens 语言不列——它们由 pi-lens 自动安装，
     * 没有可体检的缺口，列出来只会把表撑长。
     */
    val languages: List<LspLanguage> = listOf(
        LspLanguage("c", "C", "clangd-lsp", "clangd", piLensGated = false, piLensBinary = null),
        LspLanguage("cpp", "C++", "clangd-lsp", "clangd", piLensGated = true, piLensBinary = "clangd"),
        LspLanguage("csharp", "C#", "csharp-lsp", "csharp-ls", piLensGated = false, piLensBinary = null),
        LspLanguage("go", "Go", "gopls-lsp", "gopls", piLensGated = true, piLensBinary = "gopls"),
        LspLanguage("java", "Java", "jdtls-lsp", "jdtls", piLensGated = true, piLensBinary = "jdtls"),
        LspLanguage("kotlin", "Kotlin", "kotlin-lsp", "kotlin-lsp", piLensGated = true, piLensBinary = "kotlin-language-server"),
        LspLanguage("lua", "Lua", "lua-lsp", "lua-language-server", piLensGated = true, piLensBinary = "lua-language-server"),
        LspLanguage("php", "PHP", "php-lsp", "intelephense", piLensGated = false, piLensBinary = null),
        LspLanguage("python", "Python", "pyright-lsp", "pyright-langserver", piLensGated = false, piLensBinary = null),
        LspLanguage("ruby", "Ruby", "ruby-lsp", "ruby-lsp", piLensGated = false, piLensBinary = null),
        LspLanguage("rust", "Rust", "rust-analyzer-lsp", "rust-analyzer", piLensGated = false, piLensBinary = null),
        LspLanguage("swift", "Swift", "swift-lsp", "sourcekit-lsp", piLensGated = true, piLensBinary = "sourcekit-lsp"),
        LspLanguage(
            "typescript", "TypeScript/JavaScript", "typescript-lsp", "typescript-language-server",
            piLensGated = false, piLensBinary = null,
        ),
        LspLanguage("haskell", "Haskell", null, null, piLensGated = true, piLensBinary = "haskell-language-server-wrapper"),
        LspLanguage("elixir", "Elixir", null, null, piLensGated = true, piLensBinary = "elixir-ls"),
        LspLanguage("ocaml", "OCaml", null, null, piLensGated = true, piLensBinary = "ocamllsp"),
        LspLanguage("nix", "Nix", null, null, piLensGated = true, piLensBinary = "nixd"),
        LspLanguage("fsharp", "F#", null, null, piLensGated = true, piLensBinary = "fsautocomplete"),
    )

    /** 探测一遍需要查的全部二进制，去重后交给 BinaryProbe 一次问完。 */
    val allBinaries: Set<String> =
        languages.flatMap { listOfNotNull(it.claudeBinary, it.piLensBinary) }.toSet()
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew test --tests '*LspCatalogTest*' --offline`
Expected: PASS，7 个测试全绿

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/lsp/ src/test/kotlin/com/github/izerui/imux/lsp/
git commit -m "$(cat <<'EOF'
增加 LSP 体检的语言目录与结果模型

Claude Code 与 pi-lens 对同一语言可能用不同的 server 二进制（Kotlin 上是
kotlin-lsp 与 kotlin-language-server），因此两条路径的二进制分字段记录、
分别探测。安装命令只填已核实的，拿不准的留 null 配文档链接——编一条跑不通
的命令会让用户误以为是自己环境的问题。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: 二进制存在性探测

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/lsp/BinaryProbe.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt`（`singleQuote` 改 `internal`）
- Test: `src/test/kotlin/com/github/izerui/imux/lsp/BinaryProbeTest.kt`

**Interfaces:**
- Consumes: `LspCatalog.allBinaries`
- Produces: `interface BinaryProbe { fun locate(binaries: Set<String>): Map<String, String?> }`、`buildProbeScript(binaries: List<String>): String`、`parseProbeOutput(output: String): Map<String, String?>`、`class ShellBinaryProbe(shell: String) : BinaryProbe`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/kotlin/com/github/izerui/imux/lsp/BinaryProbeTest.kt`：

```kotlin
package com.github.izerui.imux.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryProbeTest {

    @Test
    fun `脚本对每个二进制输出一行 名称 制表符 路径`() {
        val script = buildProbeScript(listOf("gopls", "jdtls"))

        assertTrue("必须逐个查询", script.contains("'gopls'") && script.contains("'jdtls'"))
        assertTrue("必须用 command -v 而不是 which（后者在部分 shell 里不是内建）", script.contains("command -v"))
        assertTrue("失败时不能让整条脚本中断", script.contains("2>/dev/null"))
    }

    /** 二进制名来自本仓库的静态表，但拼进 shell 的东西一律当作不可信。 */
    @Test
    fun `二进制名被单引号包裹`() {
        assertTrue(buildProbeScript(listOf("a'b")).contains("""'a'\''b'"""))
    }

    @Test
    fun `解析输出得到路径映射`() {
        val output = "gopls\t/Users/demo/go/bin/gopls\njdtls\t/opt/homebrew/bin/jdtls\n"

        assertEquals(
            mapOf("gopls" to "/Users/demo/go/bin/gopls", "jdtls" to "/opt/homebrew/bin/jdtls"),
            parseProbeOutput(output),
        )
    }

    /** 未安装时 command -v 无输出，制表符后为空——这一条必须解析成 null 而不是空串。 */
    @Test
    fun `路径为空表示未安装`() {
        val parsed = parseProbeOutput("gopls\t\njdtls\t/opt/homebrew/bin/jdtls\n")

        assertTrue("键必须在", "gopls" in parsed)
        assertNull(parsed["gopls"])
        assertEquals("/opt/homebrew/bin/jdtls", parsed["jdtls"])
    }

    /** 登录 shell 会打印 profile 里的欢迎语、版本提示等噪音，不能让它污染结果。 */
    @Test
    fun `忽略不含制表符的噪音行`() {
        val output = "Welcome to zsh!\n\ngopls\t/usr/local/bin/gopls\nnvm: v22\n"

        assertEquals(mapOf("gopls" to "/usr/local/bin/gopls"), parseProbeOutput(output))
    }

    @Test
    fun `空输入得到空映射`() {
        assertEquals(emptyMap<String, String?>(), parseProbeOutput(""))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*BinaryProbeTest*' --offline`
Expected: 编译失败，`Unresolved reference 'buildProbeScript'`

- [ ] **Step 3: 放开 singleQuote 的可见性**

在 `src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt` 末尾，把

```kotlin
private fun singleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
```

改为

```kotlin
internal fun singleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
```

原有的 KDoc 注释保持不动——它解释的「凡是拼进 shell 命令行的东西一律当作不可信」正是 LSP 探测复用它的理由。

- [ ] **Step 4: 写实现**

创建 `src/main/kotlin/com/github/izerui/imux/lsp/BinaryProbe.kt`：

```kotlin
package com.github.izerui.imux.lsp

import com.github.izerui.imux.terminal.resolveShell
import com.github.izerui.imux.terminal.singleQuote
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.TimeUnit

/** 查一批二进制在不在 PATH 里；值为绝对路径，不在则为 null。 */
internal interface BinaryProbe {
    fun locate(binaries: Set<String>): Map<String, String?>
}

/**
 * 拼出一次问完所有二进制的脚本。
 *
 * 每个二进制起一个登录 shell 是不可接受的：`zsh -l -i` 要读 profile 与 rc，
 * 单次开销可观，本表有近二十个二进制。一次调用、按行返回。
 *
 * 输出格式 `名称<TAB>路径`：`command -v` 找不到时输出空串，于是制表符后为空，
 * 与「找到了」在结构上仍然可区分——不能靠「有没有这一行」判断，因为登录 shell
 * 会往 stdout 混入 profile 的欢迎语。
 */
internal fun buildProbeScript(binaries: List<String>): String =
    binaries.joinToString("; ") { binary ->
        val quoted = singleQuote(binary)
        "printf '%s\\t%s\\n' $quoted \"\$(command -v $quoted 2>/dev/null)\""
    }

/** 解析 [buildProbeScript] 的输出。不含制表符的行是 shell 噪音，丢弃。 */
internal fun parseProbeOutput(output: String): Map<String, String?> =
    output.lineSequence()
        .mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab < 0) return@mapNotNull null
            val name = line.substring(0, tab).trim()
            if (name.isEmpty()) return@mapNotNull null
            name to line.substring(tab + 1).trim().takeIf(String::isNotEmpty)
        }
        .toMap()

/**
 * 经用户登录 shell 探测。
 *
 * **不能用 IDE 进程自己的 PATH。** 从 Dock/Finder 启动的 IDE 只有系统默认
 * PATH（`/usr/bin:/bin:/usr/sbin:/sbin`），而语言服务器普遍装在
 * `/opt/homebrew/bin`、`~/go/bin`、`~/.nvm/versions/node/*/bin`。
 * 本机实测五个已装 server 分散在三个前缀下，没有一个落在系统默认 PATH 里。
 *
 * 从终端 `runIde` 起的沙箱继承了终端 PATH，所以这个 bug 只在正式 IDE 上暴露——
 * 与 [com.github.izerui.imux.terminal.launchCommand] 记录的是同一个坑，
 * 那次表现为「点开会话后标签页一片空白」。
 *
 * `-l` 读 profile 拿 PATH，`-i` 读 rc 拿 alias 与 nvm/rbenv 之类的 shim。
 */
internal class ShellBinaryProbe(
    private val shell: String = resolveShell(System.getenv("SHELL")),
    private val timeoutSeconds: Long = TIMEOUT_SECONDS,
) : BinaryProbe {

    override fun locate(binaries: Set<String>): Map<String, String?> {
        if (binaries.isEmpty()) return emptyMap()
        return runCatching {
            val process = ProcessBuilder(shell, "-l", "-i", "-c", buildProbeScript(binaries.toList()))
                .redirectErrorStream(false)
                .start()
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                // 超时不能退化成「全部未安装」——那会让 UI 谎报一堆缺口。
                // 返回空映射，上层据此标 UNKNOWN。
                return emptyMap()
            }
            parseProbeOutput(output)
        }.onFailure { LOG.warn("LSP 二进制探测失败，全部标记为无法确定", it) }
            .getOrDefault(emptyMap())
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
        val LOG = logger<ShellBinaryProbe>()
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew test --tests '*BinaryProbeTest*' --offline`
Expected: PASS，6 个测试全绿

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/lsp/BinaryProbe.kt \
        src/main/kotlin/com/github/izerui/imux/terminal/AgentCommand.kt \
        src/test/kotlin/com/github/izerui/imux/lsp/BinaryProbeTest.kt
git commit -m "$(cat <<'EOF'
增加经登录 shell 的语言服务器二进制探测

必须走 $SHELL -l -i 而不是 IDE 自己的 PATH：本机实测五个已装 server 分散在
/opt/homebrew/bin、~/go/bin、~/.nvm 三个前缀下，没有一个在从 Dock 启动的
IDE 的默认 PATH 里。这与 launchCommand 记录的是同一个坑，沙箱里发现不了。

一次 shell 调用查完全部二进制：登录 shell 要读 profile 与 rc，近二十个二进制
逐个起 shell 的开销不可接受。超时返回空映射而非「全部未安装」，避免谎报缺口。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Claude Code 探针

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/lsp/ClaudeCodeLspProbe.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/lsp/ClaudeCodeLspProbeTest.kt`

**Interfaces:**
- Consumes: `LspCatalog.languages`、`LspLanguage`、`LspStatus`、`LanguageFinding`、`Remedy`、`CliReport`、`BinaryProbe`
- Produces: `parseConfiguredCommands(settingsJson: String?, marketplaceJson: String?): Set<String>`、`claudeReport(configuredCommands: Set<String>, binaries: Map<String, String?>, cliInstalled: Boolean): CliReport`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/kotlin/com/github/izerui/imux/lsp/ClaudeCodeLspProbeTest.kt`：

```kotlin
package com.github.izerui.imux.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeCodeLspProbeTest {

    private val marketplace = """
        {"plugins":[
          {"name":"gopls-lsp","lspServers":{"gopls":{"command":"gopls","extensionToLanguage":{".go":"go"}}}},
          {"name":"kotlin-lsp","lspServers":{"kotlin":{"command":"kotlin-lsp","extensionToLanguage":{".kt":"kotlin"}}}},
          {"name":"not-an-lsp-plugin","category":"development"}
        ]}
    """.trimIndent()

    @Test
    fun `从 settings 里直接定义的 lspServers 取 command`() {
        val settings = """{"lspServers":{"gopls":{"command":"gopls","args":["--background-index"]}}}"""

        assertEquals(setOf("gopls"), parseConfiguredCommands(settings, null))
    }

    /** 官方 LSP 插件把 command 藏在 marketplace 清单里，settings 里只有一个开关。 */
    @Test
    fun `启用的插件带来其 marketplace 中声明的 command`() {
        val settings = """{"enabledPlugins":{"gopls-lsp@claude-plugins-official":true}}"""

        assertEquals(setOf("gopls"), parseConfiguredCommands(settings, marketplace))
    }

    @Test
    fun `未启用的插件不计入`() {
        val settings = """{"enabledPlugins":{"kotlin-lsp@claude-plugins-official":false}}"""

        assertTrue(parseConfiguredCommands(settings, marketplace).isEmpty())
    }

    @Test
    fun `两个来源合并`() {
        val settings = """
            {"lspServers":{"custom":{"command":"my-server"}},
             "enabledPlugins":{"kotlin-lsp@claude-plugins-official":true}}
        """.trimIndent()

        assertEquals(setOf("my-server", "kotlin-lsp"), parseConfiguredCommands(settings, marketplace))
    }

    /** 配置文件随时可能不存在或被用户改坏，任何一种都只该降级为「未配置」。 */
    @Test
    fun `缺失或损坏的配置解析为空集合`() {
        assertTrue(parseConfiguredCommands(null, null).isEmpty())
        assertTrue(parseConfiguredCommands("", marketplace).isEmpty())
        assertTrue(parseConfiguredCommands("这不是 json", marketplace).isEmpty())
        assertTrue(parseConfiguredCommands("[1,2,3]", marketplace).isEmpty())
        assertTrue(parseConfiguredCommands("""{"lspServers":"应该是对象"}""", marketplace).isEmpty())
        assertTrue(parseConfiguredCommands("""{"enabledPlugins":{"gopls-lsp@x":true}}""", "坏掉的清单").isEmpty())
    }

    @Test
    fun `配置到位且二进制在则为就绪`() {
        val report = claudeReport(
            configuredCommands = setOf("gopls"),
            binaries = mapOf("gopls" to "/Users/demo/go/bin/gopls"),
            cliInstalled = true,
        )

        val go = report.findings.single { it.language.id == "go" }
        assertEquals(LspStatus.READY, go.status)
    }

    /** 本机真实场景：kotlin-lsp 二进制已装，Claude Code 却没启用插件。 */
    @Test
    fun `二进制在但插件没启用则是配置缺口并给出装插件的命令`() {
        val report = claudeReport(
            configuredCommands = emptySet(),
            binaries = mapOf("kotlin-lsp" to "/opt/homebrew/bin/kotlin-lsp"),
            cliInstalled = true,
        )

        val kotlin = report.findings.single { it.language.id == "kotlin" }
        assertEquals(LspStatus.MISSING_CONFIG, kotlin.status)
        assertEquals(
            "claude plugin install kotlin-lsp@claude-plugins-official",
            kotlin.remedy?.command,
        )
    }

    @Test
    fun `配置了但二进制不在则是二进制缺口并给出安装命令`() {
        val report = claudeReport(
            configuredCommands = setOf("jdtls"),
            binaries = mapOf("jdtls" to null),
            cliInstalled = true,
        )

        val java = report.findings.single { it.language.id == "java" }
        assertEquals(LspStatus.MISSING_BINARY, java.status)
        assertEquals("brew install jdtls", java.remedy?.command)
    }

    /** 探测超时时映射为空，不能把「没查到」说成「没安装」。 */
    @Test
    fun `二进制映射里没有该键时状态为无法确定`() {
        val report = claudeReport(setOf("jdtls"), emptyMap(), cliInstalled = true)

        assertEquals(LspStatus.UNKNOWN, report.findings.single { it.language.id == "java" }.status)
    }

    /** 官方没有对应插件的语言（Haskell 等）不该出现在 Claude Code 这一组。 */
    @Test
    fun `没有官方插件的语言不列入`() {
        val report = claudeReport(emptySet(), emptyMap(), cliInstalled = true)

        assertTrue(report.findings.none { it.language.id == "haskell" })
        assertTrue(report.findings.any { it.language.id == "kotlin" })
    }

    @Test
    fun `CLI 未安装时不产出任何缺口`() {
        val report = claudeReport(emptySet(), emptyMap(), cliInstalled = false)

        assertFalse(report.installed)
        assertTrue(report.findings.isEmpty())
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*ClaudeCodeLspProbeTest*' --offline`
Expected: 编译失败，`Unresolved reference 'parseConfiguredCommands'`

- [ ] **Step 3: 写实现**

创建 `src/main/kotlin/com/github/izerui/imux/lsp/ClaudeCodeLspProbe.kt`：

```kotlin
package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Claude Code 的 LSP 配置探测。
 *
 * 两个全局来源：
 * 1. `~/.claude/settings.json` 顶层的 `lspServers`
 * 2. `enabledPlugins` 里启用的插件 × marketplace 清单中该插件的 `lspServers`
 *
 * **项目级的 `.claude/settings.json` 不读**：体检页是应用级设置，拿不到 Project。
 * 代价是只在某项目里配了 LSP 的用户会被误报未配置，UI 上以脚注说明。
 * 见 spec「Claude Code——原生 LSP」一节。
 *
 * 用 Gson 而不是 [com.github.izerui.imux.session.JsonLineScanner]：后者是为
 * 数 MB 的会话文件做的线性扫描，只取顶层字符串；这里要读的是嵌套结构
 * （`lspServers.<名>.command`、`plugins[].lspServers`），且文件只有几 KB。
 */
internal fun parseConfiguredCommands(settingsJson: String?, marketplaceJson: String?): Set<String> {
    val settings = parseObject(settingsJson) ?: return emptySet()
    return buildSet {
        addAll(directCommands(settings))
        addAll(pluginCommands(settings, marketplaceJson))
    }
}

/** `lspServers` 直接定义的 command。 */
private fun directCommands(settings: JsonObject): Set<String> =
    settings.asObject("lspServers")
        ?.entrySet()
        .orEmpty()
        .mapNotNull { (_, value) -> value.asObjectOrNull()?.asString("command") }
        .toSet()

/** 启用的插件在 marketplace 清单里声明的 command。 */
private fun pluginCommands(settings: JsonObject, marketplaceJson: String?): Set<String> {
    val enabled = settings.asObject("enabledPlugins")
        ?.entrySet()
        .orEmpty()
        .filter { (_, value) -> runCatching { value.asBoolean }.getOrDefault(false) }
        // 键形如 `gopls-lsp@claude-plugins-official`，marketplace 里的 name 不带来源后缀
        .map { (key, _) -> key.substringBefore('@') }
        .toSet()
    if (enabled.isEmpty()) return emptySet()

    val plugins = parseObject(marketplaceJson)?.get("plugins")?.asArrayOrNull() ?: return emptySet()
    return plugins.mapNotNull { it.asObjectOrNull() }
        .filter { it.asString("name") in enabled }
        .flatMap { plugin ->
            plugin.asObject("lspServers")
                ?.entrySet()
                .orEmpty()
                .mapNotNull { (_, value) -> value.asObjectOrNull()?.asString("command") }
        }
        .toSet()
}

/**
 * 把配置与二进制探测结果合成 Claude Code 这一组的报告。
 *
 * 只列官方有对应插件的语言——Haskell 之类官方没插件的，在 Claude Code 上
 * 不存在「装个插件就能用」的路径，列进来只能显示一条无法执行的建议。
 */
internal fun claudeReport(
    configuredCommands: Set<String>,
    binaries: Map<String, String?>,
    cliInstalled: Boolean,
): CliReport {
    if (!cliInstalled) {
        return CliReport(AgentType.CLAUDE, installed = false, findings = emptyList())
    }

    val findings = LspCatalog.languages
        .filter { it.claudePlugin != null && it.claudeBinary != null }
        .map { language ->
            val binary = language.claudeBinary!!
            val configured = binary in configuredCommands
            val located = binaries[binary]
            val status = when {
                !configured -> LspStatus.MISSING_CONFIG
                !binaries.containsKey(binary) -> LspStatus.UNKNOWN
                located == null -> LspStatus.MISSING_BINARY
                else -> LspStatus.READY
            }
            LanguageFinding(language, status, remedyFor(language, status))
        }
    return CliReport(AgentType.CLAUDE, installed = true, findings = findings)
}

private fun remedyFor(language: LspLanguage, status: LspStatus): Remedy? = when (status) {
    LspStatus.MISSING_CONFIG ->
        Remedy("claude plugin install ${language.claudePlugin}@claude-plugins-official", null)

    LspStatus.MISSING_BINARY -> LspCatalog.server(language.claudeBinary.orEmpty())
        ?.let { Remedy(it.installCommand, it.docsUrl) }

    LspStatus.READY, LspStatus.UNKNOWN -> null
}

// —— Gson 便利封装：任何形状不符都返回 null，绝不抛给调用方 ——

private fun parseObject(text: String?): JsonObject? {
    if (text.isNullOrBlank()) return null
    return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
}

private fun JsonElement.asObjectOrNull(): JsonObject? =
    runCatching { asJsonObject }.getOrNull()

private fun JsonElement.asArrayOrNull(): List<JsonElement>? =
    runCatching { asJsonArray.toList() }.getOrNull()

private fun JsonObject.asObject(key: String): JsonObject? =
    get(key)?.asObjectOrNull()

private fun JsonObject.asString(key: String): String? =
    runCatching { get(key)?.asString }.getOrNull()?.takeIf(String::isNotBlank)
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests '*ClaudeCodeLspProbeTest*' --offline`
Expected: PASS，11 个测试全绿

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/lsp/ClaudeCodeLspProbe.kt \
        src/test/kotlin/com/github/izerui/imux/lsp/ClaudeCodeLspProbeTest.kt
git commit -m "$(cat <<'EOF'
增加 Claude Code 的 LSP 配置探测

合并两个全局来源：settings.json 顶层的 lspServers，以及 enabledPlugins 与
marketplace 清单交叉出的插件 lspServers。官方 LSP 插件走的是后者。

区分配置缺口与二进制缺口：本机的真实情况是 kotlin-lsp 二进制已装、插件却
没启用，这种一行命令就能补，不该和「要下载 1.3GB」显示成同一种缺失。

用 Gson 而非 JsonLineScanner：后者是为数 MB 会话文件做的顶层线性扫描，
这里要读嵌套结构且文件只有几 KB。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: pi 探针

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/lsp/PiLspProbe.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/lsp/PiLspProbeTest.kt`

**Interfaces:**
- Consumes: `LspCatalog.languages`、`LspStatus`、`LanguageFinding`、`Remedy`、`CliReport`
- Produces: `hasPiLens(settingsJson: String?): Boolean`、`piReport(piLensInstalled: Boolean, binaries: Map<String, String?>, cliInstalled: Boolean): CliReport`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/kotlin/com/github/izerui/imux/lsp/PiLspProbeTest.kt`：

```kotlin
package com.github.izerui.imux.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiLspProbeTest {

    @Test
    fun `packages 里有 pi-lens`() {
        assertTrue(hasPiLens("""{"packages":["npm:pi-lens"],"theme":"dark"}"""))
    }

    /** pi 允许写死版本，`pi install npm:pi-lens@4.0.1` 会原样落进 packages。 */
    @Test
    fun `带版本号的写法也算装了`() {
        assertTrue(hasPiLens("""{"packages":["npm:pi-lens@4.0.1"]}"""))
    }

    /** 名字前缀相同的另一个包不能被误认。 */
    @Test
    fun `前缀相同的其他包不算`() {
        assertFalse(hasPiLens("""{"packages":["npm:pi-lens-extras"]}"""))
        assertFalse(hasPiLens("""{"packages":["npm:pi-lensify"]}"""))
    }

    @Test
    fun `没装或配置损坏都算没装`() {
        assertFalse(hasPiLens(null))
        assertFalse(hasPiLens(""))
        assertFalse(hasPiLens("这不是 json"))
        assertFalse(hasPiLens("""{"packages":[]}"""))
        assertFalse(hasPiLens("""{"packages":"应该是数组"}"""))
        assertFalse(hasPiLens("""{"theme":"dark"}"""))
    }

    /** 没装 pi-lens 时逐语言的缺口没有意义，先把前置补上。 */
    @Test
    fun `未装 pi-lens 时给出整组修复建议且不列逐语言缺口`() {
        val report = piReport(piLensInstalled = false, binaries = emptyMap(), cliInstalled = true)

        assertEquals("pi install npm:pi-lens", report.groupRemedy?.command)
        assertTrue(report.findings.isEmpty())
    }

    /** 非 gated 语言由 pi-lens 自动安装 server，不需要体检，也不该占版面。 */
    @Test
    fun `装了 pi-lens 后只列 toolchain-gated 语言`() {
        val report = piReport(piLensInstalled = true, binaries = emptyMap(), cliInstalled = true)

        assertTrue(report.findings.all { it.language.piLensGated })
        assertTrue(report.findings.any { it.language.id == "kotlin" })
        assertTrue("Python 由 pi-lens 自动安装，不该出现", report.findings.none { it.language.id == "python" })
    }

    @Test
    fun `gated 语言的二进制在则就绪`() {
        val report = piReport(
            piLensInstalled = true,
            binaries = mapOf("gopls" to "/Users/demo/go/bin/gopls"),
            cliInstalled = true,
        )

        assertEquals(LspStatus.READY, report.findings.single { it.language.id == "go" }.status)
    }

    /** 本机真实场景：pi-lens 装了，Kotlin 的 server 没装，状态栏显示 LSP Inactive。 */
    @Test
    fun `gated 语言的二进制不在则是二进制缺口`() {
        val report = piReport(
            piLensInstalled = true,
            binaries = mapOf("kotlin-language-server" to null),
            cliInstalled = true,
        )

        val kotlin = report.findings.single { it.language.id == "kotlin" }
        assertEquals(LspStatus.MISSING_BINARY, kotlin.status)
        assertNotNull("没有已知安装命令时也要给文档链接", kotlin.remedy?.docsUrl)
    }

    @Test
    fun `二进制映射里没有该键时状态为无法确定`() {
        val report = piReport(piLensInstalled = true, binaries = emptyMap(), cliInstalled = true)

        assertEquals(LspStatus.UNKNOWN, report.findings.single { it.language.id == "go" }.status)
    }

    @Test
    fun `CLI 未安装时不产出任何缺口或建议`() {
        val report = piReport(piLensInstalled = false, binaries = emptyMap(), cliInstalled = false)

        assertFalse(report.installed)
        assertTrue(report.findings.isEmpty())
        assertEquals(null, report.groupRemedy)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*PiLspProbeTest*' --offline`
Expected: 编译失败，`Unresolved reference 'hasPiLens'`

- [ ] **Step 3: 写实现**

创建 `src/main/kotlin/com/github/izerui/imux/lsp/PiLspProbe.kt`：

```kotlin
package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonParser

/** `pi install` 写进 settings 的包标识；可带 `@版本` 后缀。 */
private const val PI_LENS_PACKAGE = "npm:pi-lens"

/**
 * pi 是否装了 pi-lens。
 *
 * pi core 自己没有 LSP：全量 dist 中 `lspServers`、`languageServer`、
 * `textDocument/` 零命中，内建工具只有 bash/edit/write/read/find/grep/ls。
 * LSP 来自第三方扩展 pi-lens，登记在 `~/.pi/agent/settings.json` 的 `packages` 里。
 *
 * 项目级 `.pi/settings.json` 不读，理由与 Claude Code 一致（见 spec）。
 */
internal fun hasPiLens(settingsJson: String?): Boolean {
    if (settingsJson.isNullOrBlank()) return false
    val packages = runCatching {
        JsonParser.parseString(settingsJson).asJsonObject.getAsJsonArray("packages")
    }.getOrNull() ?: return false

    return packages.any { element ->
        val value = runCatching { element.asString }.getOrNull() ?: return@any false
        // 精确匹配或带版本后缀；npm:pi-lens-extras 这类前缀相同的包必须排除
        value == PI_LENS_PACKAGE || value.startsWith("$PI_LENS_PACKAGE@")
    }
}

/**
 * pi 这一组的报告。
 *
 * 只列 toolchain-gated 语言：其余语言 pi-lens 会按 npm/pip/github 策略自动装 server，
 * 没有可体检的缺口（pi-lens `docs/lsp-capability-matrix.md`，issue #241）。
 */
internal fun piReport(
    piLensInstalled: Boolean,
    binaries: Map<String, String?>,
    cliInstalled: Boolean,
): CliReport {
    if (!cliInstalled) {
        return CliReport(AgentType.PI, installed = false, findings = emptyList())
    }
    if (!piLensInstalled) {
        return CliReport(
            AgentType.PI,
            installed = true,
            findings = emptyList(),
            groupRemedy = Remedy("pi install $PI_LENS_PACKAGE", "https://github.com/apmantza/pi-lens"),
        )
    }

    val findings = LspCatalog.languages
        .filter(LspLanguage::piLensGated)
        .map { language ->
            val binary = language.piLensBinary!!
            val status = when {
                !binaries.containsKey(binary) -> LspStatus.UNKNOWN
                binaries[binary] == null -> LspStatus.MISSING_BINARY
                else -> LspStatus.READY
            }
            val remedy = if (status == LspStatus.MISSING_BINARY) {
                LspCatalog.server(binary)?.let { Remedy(it.installCommand, it.docsUrl) }
            } else {
                null
            }
            LanguageFinding(language, status, remedy)
        }
    return CliReport(AgentType.PI, installed = true, findings = findings)
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests '*PiLspProbeTest*' --offline`
Expected: PASS，10 个测试全绿

- [ ] **Step 5: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/lsp/PiLspProbe.kt \
        src/test/kotlin/com/github/izerui/imux/lsp/PiLspProbeTest.kt
git commit -m "$(cat <<'EOF'
增加 pi 的 LSP 探测

pi core 没有 LSP（全量 dist 中 lspServers、textDocument/ 零命中），LSP 来自
第三方扩展 pi-lens，登记在 ~/.pi/agent/settings.json 的 packages 里。

只列 toolchain-gated 的 11 个语言：其余语言 pi-lens 会自动安装 server，没有
可体检的缺口。包名匹配要排除 npm:pi-lens-extras 这类前缀相同的其他包。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Codex 探针与 TOML 扫描

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/lsp/TomlSectionScanner.kt`
- Create: `src/main/kotlin/com/github/izerui/imux/lsp/CodexLspProbe.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/lsp/CodexLspProbeTest.kt`

**Interfaces:**
- Consumes: `piReport` 产出的 `LanguageFinding` 列表、`CliReport`、`Remedy`
- Produces: `mountsPiLensMcp(configToml: String?): Boolean`、`codexReport(mounted: Boolean, piFindings: List<LanguageFinding>, cliInstalled: Boolean): CliReport`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/kotlin/com/github/izerui/imux/lsp/CodexLspProbeTest.kt`：

```kotlin
package com.github.izerui.imux.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexLspProbeTest {

    @Test
    fun `标准段落形式的 mcp_servers`() {
        val toml = """
            model = "gpt-5"

            [mcp_servers.pi-lens]
            command = "pi-lens-mcp"
        """.trimIndent()

        assertTrue(mountsPiLensMcp(toml))
    }

    /** command 走 npx 之类的启动器时，真正的标识在 args 里。 */
    @Test
    fun `command 在 args 里也算挂载`() {
        val toml = """
            [mcp_servers.lens]
            command = "npx"
            args = ["-y", "pi-lens-mcp"]
        """.trimIndent()

        assertTrue(mountsPiLensMcp(toml))
    }

    @Test
    fun `内联表形式的 mcp_servers`() {
        assertTrue(mountsPiLensMcp("""mcp_servers.lens = { command = "pi-lens-mcp" }"""))
    }

    /** 别的段落里出现这个字符串不能算数——比如注释掉的旧配置。 */
    @Test
    fun `mcp_servers 之外出现的同名字符串不算`() {
        val toml = """
            [history]
            note = "pi-lens-mcp"

            [mcp_servers.other]
            command = "some-other-server"
        """.trimIndent()

        assertFalse(mountsPiLensMcp(toml))
    }

    @Test
    fun `注释行不算`() {
        val toml = """
            [mcp_servers.lens]
            # command = "pi-lens-mcp"
            command = "something-else"
        """.trimIndent()

        assertFalse(mountsPiLensMcp(toml))
    }

    @Test
    fun `缺失或损坏的配置算未挂载`() {
        assertFalse(mountsPiLensMcp(null))
        assertFalse(mountsPiLensMcp(""))
        assertFalse(mountsPiLensMcp("[[[乱码"))
        assertFalse(mountsPiLensMcp("""[mcp_servers.x]${"\n"}command = "gopls""""))
    }

    @Test
    fun `未挂载时给出挂载命令且不列逐语言缺口`() {
        val report = codexReport(mounted = false, piFindings = emptyList(), cliInstalled = true)

        assertEquals("codex mcp add pi-lens -- pi-lens-mcp", report.groupRemedy?.command)
        assertTrue(report.findings.isEmpty())
    }

    /** 挂载后 Codex 用的是同一套 pi-lens server，语言状态与 pi 完全一致。 */
    @Test
    fun `挂载后复用 pi 的语言结果`() {
        val kotlin = LspCatalog.languages.single { it.id == "kotlin" }
        val piFindings = listOf(LanguageFinding(kotlin, LspStatus.MISSING_BINARY, Remedy(null, "https://x")))

        val report = codexReport(mounted = true, piFindings = piFindings, cliInstalled = true)

        assertEquals(piFindings, report.findings)
        assertEquals(null, report.groupRemedy)
    }

    @Test
    fun `CLI 未安装时不产出任何缺口或建议`() {
        val report = codexReport(mounted = false, piFindings = emptyList(), cliInstalled = false)

        assertFalse(report.installed)
        assertTrue(report.findings.isEmpty())
        assertEquals(null, report.groupRemedy)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*CodexLspProbeTest*' --offline`
Expected: 编译失败，`Unresolved reference 'mountsPiLensMcp'`

- [ ] **Step 3: 写 TOML 扫描**

创建 `src/main/kotlin/com/github/izerui/imux/lsp/TomlSectionScanner.kt`：

```kotlin
package com.github.izerui.imux.lsp

/**
 * 只够用的 TOML 段落扫描。
 *
 * **不是通用 TOML 解析器。** 平台捆绑了 `jackson-dataformat-toml`，但它不在插件的
 * 编译 classpath 上（实测 `Unresolved reference 'toml'`），而为了判断一个布尔值
 * 引一个新依赖不划算——本项目的仓库可达性本来就受限（见 build.gradle.kts 注释）。
 *
 * 只回答一个问题：某个顶层段落（如 `mcp_servers`）下面，有没有哪一行的值里
 * 出现了给定标识。做法是按行扫描、跟踪当前段落名、跳过注释。
 *
 * 已知不支持：多行数组、多行字符串。Codex 写出来的 `config.toml` 是每个 server
 * 一个 `[mcp_servers.<名>]` 段落、键值都在单行上，落不到这些形式里；
 * 真遇到多行数组只会漏判成「未挂载」，UI 上表现为多给一条已经满足的建议，
 * 不会误报成已挂载。
 */
internal fun tomlSectionContains(toml: String?, topLevelSection: String, needle: String): Boolean {
    if (toml.isNullOrBlank()) return false

    var inSection = false
    toml.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach

        if (line.startsWith("[")) {
            // `[mcp_servers.pi-lens]` / `[[mcp_servers.x]]` → 取第一段名字
            val header = line.trim('[', ']').trim()
            inSection = header == topLevelSection || header.startsWith("$topLevelSection.")
            return@forEach
        }

        // 内联表：`mcp_servers.lens = { command = "pi-lens-mcp" }`
        if (line.startsWith("$topLevelSection.") && line.contains(needle)) return true

        if (inSection && line.substringAfter('=', "").contains(needle)) return true
    }
    return false
}
```

- [ ] **Step 4: 写 Codex 探针**

创建 `src/main/kotlin/com/github/izerui/imux/lsp/CodexLspProbe.kt`：

```kotlin
package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType

/** pi-lens 的 MCP 服务端可执行文件名（pi-lens `package.json` 的 bin 之一）。 */
private const val PI_LENS_MCP_BIN = "pi-lens-mcp"

/**
 * Codex 是否挂了 pi-lens 的 MCP。
 *
 * Codex 完全没有 LSP：二进制中 `textDocument/`、`lspServers`、`language_servers`、
 * `lsp_servers`、`languageServer` 全部零命中。它有的是 `mcp_servers`
 * （`~/.codex/config.toml`），而 pi-lens 自带 `pi-lens-mcp` 可执行文件，
 * 其 `docs/mcp.md` 的既定目标就是把自己暴露给 MCP 宿主——这是 Codex 唯一的等价路径。
 */
internal fun mountsPiLensMcp(configToml: String?): Boolean =
    tomlSectionContains(configToml, "mcp_servers", PI_LENS_MCP_BIN)

/**
 * Codex 这一组的报告。
 *
 * 挂载后走的是与 pi 完全相同的 pi-lens server，语言层面的缺口一模一样，
 * 因此直接复用 [piReport] 的结果，不重复探测。
 */
internal fun codexReport(
    mounted: Boolean,
    piFindings: List<LanguageFinding>,
    cliInstalled: Boolean,
): CliReport {
    if (!cliInstalled) {
        return CliReport(AgentType.CODEX, installed = false, findings = emptyList())
    }
    if (!mounted) {
        return CliReport(
            AgentType.CODEX,
            installed = true,
            findings = emptyList(),
            groupRemedy = Remedy("codex mcp add pi-lens -- $PI_LENS_MCP_BIN", null),
        )
    }
    return CliReport(AgentType.CODEX, installed = true, findings = piFindings)
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew test --tests '*CodexLspProbeTest*' --offline`
Expected: PASS，9 个测试全绿

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/lsp/TomlSectionScanner.kt \
        src/main/kotlin/com/github/izerui/imux/lsp/CodexLspProbe.kt \
        src/test/kotlin/com/github/izerui/imux/lsp/CodexLspProbeTest.kt
git commit -m "$(cat <<'EOF'
增加 Codex 的 LSP 探测

Codex 完全没有 LSP：二进制中 textDocument/、lspServers、language_servers
全部零命中。唯一等价路径是挂 pi-lens 自带的 pi-lens-mcp，挂上后与 pi 走
同一套 server，因此语言层面的结果直接复用 pi 的，不重复探测。

手写 TOML 段落扫描而不引依赖：平台虽捆绑 jackson-dataformat-toml，但它不在
插件编译 classpath 上（实测 Unresolved reference），为一个布尔值引新依赖不
划算。已知不支持多行数组，最坏结果是漏判成未挂载、多给一条建议。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 编排器

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/lsp/LspDiagnostics.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/lsp/LspDiagnosticsTest.kt`

**Interfaces:**
- Consumes: `parseConfiguredCommands`、`claudeReport`、`hasPiLens`、`piReport`、`mountsPiLensMcp`、`codexReport`、`BinaryProbe`、`LspCatalog.allBinaries`
- Produces: `class LspDiagnostics(userHome: Path, binaryProbe: BinaryProbe)`，方法 `fun run(): LspReport`

**CLI 是否安装也走同一次批量探测。** `claude` / `codex` / `pi` 只是另外三个要查的二进制，没有理由为它们各起一个登录 shell——那与 Task 2 「一次问完」的理由完全相同（读 profile 的开销是每次都要付的）。全流程只起 1 次登录 shell。

- [ ] **Step 1: 写失败的测试**

创建 `src/test/kotlin/com/github/izerui/imux/lsp/LspDiagnosticsTest.kt`：

```kotlin
package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class LspDiagnosticsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(relative: String, content: String) {
        val file = temp.root.toPath().resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    /** [installed] 里的 CLI 会在探测结果中带上一个路径，其余带 null（= 确认未安装）。 */
    private fun diagnostics(
        binaries: Map<String, String?> = emptyMap(),
        installed: Set<AgentType> = AgentType.entries.toSet(),
    ) = LspDiagnostics(
        userHome = temp.root.toPath(),
        binaryProbe = object : BinaryProbe {
            override fun locate(wanted: Set<String>): Map<String, String?> =
                binaries + AgentType.entries.associate { type ->
                    type.cli to if (type in installed) "/usr/local/bin/${type.cli}" else null
                }
        },
    )

    private fun LspReport.of(type: AgentType) = cliReports.single { it.agentType == type }

    @Test
    fun `三个 CLI 各产出一份报告`() {
        val report = diagnostics().run()

        assertEquals(3, report.cliReports.size)
        AgentType.entries.forEach { report.of(it) }
    }

    @Test
    fun `读取三个 CLI 的全局配置`() {
        write(".claude/settings.json", """{"lspServers":{"gopls":{"command":"gopls"}}}""")
        write(".pi/agent/settings.json", """{"packages":["npm:pi-lens"]}""")
        write(".codex/config.toml", "[mcp_servers.lens]\ncommand = \"pi-lens-mcp\"")

        val report = diagnostics(binaries = mapOf("gopls" to "/usr/bin/gopls")).run()

        assertEquals(
            LspStatus.READY,
            report.of(AgentType.CLAUDE).findings.single { it.language.id == "go" }.status,
        )
        assertEquals("装了 pi-lens 就不该再给整组建议", null, report.of(AgentType.PI).groupRemedy)
        assertEquals("挂了 MCP 就不该再给整组建议", null, report.of(AgentType.CODEX).groupRemedy)
    }

    /** 配置文件一个都不存在是全新机器的正常状态，不能抛异常。 */
    @Test
    fun `配置文件全部缺失时仍产出完整报告`() {
        val report = diagnostics().run()

        assertEquals("pi install npm:pi-lens", report.of(AgentType.PI).groupRemedy?.command)
        assertEquals("codex mcp add pi-lens -- pi-lens-mcp", report.of(AgentType.CODEX).groupRemedy?.command)
        assertTrue(report.of(AgentType.CLAUDE).findings.all { it.status == LspStatus.MISSING_CONFIG })
    }

    @Test
    fun `未安装的 CLI 整组跳过`() {
        write(".pi/agent/settings.json", """{"packages":["npm:pi-lens"]}""")

        val report = diagnostics(installed = setOf(AgentType.PI)).run()

        assertFalse(report.of(AgentType.CLAUDE).installed)
        assertTrue(report.of(AgentType.CLAUDE).findings.isEmpty())
        assertTrue(report.of(AgentType.PI).installed)
    }

    /** 语言服务器与三个 CLI 必须在同一次调用里问完——登录 shell 只该起一次。 */
    @Test
    fun `一次探测同时覆盖语言服务器与三个 CLI`() {
        val calls = mutableListOf<Set<String>>()
        LspDiagnostics(
            userHome = temp.root.toPath(),
            binaryProbe = object : BinaryProbe {
                override fun locate(wanted: Set<String>): Map<String, String?> {
                    calls += wanted
                    return emptyMap()
                }
            },
        ).run()

        assertEquals("只允许探测一次", 1, calls.size)
        assertEquals(LspCatalog.allBinaries + AgentType.entries.map { it.cli }, calls.single())
    }

    /**
     * 探测超时会返回空映射。此时不能把「没查到」说成「CLI 没装」——
     * 那会让整个页面变成三行「未安装」，而真实情况只是探测失败。
     */
    @Test
    fun `探测结果里没有 CLI 键时视为已安装并逐项标记无法确定`() {
        val report = LspDiagnostics(
            userHome = temp.root.toPath(),
            binaryProbe = object : BinaryProbe {
                override fun locate(wanted: Set<String>): Map<String, String?> = emptyMap()
            },
        ).run()

        assertTrue("不得因探测失败而谎报未安装", report.of(AgentType.CLAUDE).installed)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*LspDiagnosticsTest*' --offline`
Expected: 编译失败，`Unresolved reference 'LspDiagnostics'`

- [ ] **Step 3: 写实现**

创建 `src/main/kotlin/com/github/izerui/imux/lsp/LspDiagnostics.kt`：

```kotlin
package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import java.nio.file.Files
import java.nio.file.Path

/**
 * 编排三个探针，产出一次完整体检。
 *
 * [userHome] 参数化是为了测试能指向临时目录，与 `SessionRepository.forUserHome()`
 * 的做法一致。[binaryProbe] 同理注入，单测不碰真实 shell。
 *
 * 全程只读：只 `Files.readString`，不创建、不写入、不执行安装。
 */
internal class LspDiagnostics(
    private val userHome: Path,
    private val binaryProbe: BinaryProbe,
) {

    fun run(): LspReport {
        // 一次问完：语言服务器 + 三个 CLI 自身。登录 shell 要读 profile，
        // 那份开销每次都要付，没有理由为查三个 CLI 名字再起三次。
        val located = binaryProbe.locate(LspCatalog.allBinaries + AgentType.entries.map { it.cli })

        val claude = claudeReport(
            configuredCommands = parseConfiguredCommands(
                read(".claude/settings.json"),
                read(".claude/plugins/marketplaces/claude-plugins-official/.claude-plugin/marketplace.json"),
            ),
            binaries = located,
            cliInstalled = isInstalled(located, AgentType.CLAUDE),
        )

        val pi = piReport(
            piLensInstalled = hasPiLens(read(".pi/agent/settings.json")),
            binaries = located,
            cliInstalled = isInstalled(located, AgentType.PI),
        )

        val codex = codexReport(
            mounted = mountsPiLensMcp(read(".codex/config.toml")),
            // 挂载后与 pi 是同一套 server，语言结果原样复用
            piFindings = pi.findings,
            cliInstalled = isInstalled(located, AgentType.CODEX),
        )

        return LspReport(listOf(claude, pi, codex))
    }

    /**
     * 只有**确认查过且没查到**才算未安装。
     *
     * 探测超时返回空映射，那时键根本不存在——此时报「未安装」会把整页变成三行
     * 假消息。当作已安装，逐语言自然落到 UNKNOWN，用户看到的是「无法确定」，
     * 这才是真话。
     */
    private fun isInstalled(located: Map<String, String?>, agentType: AgentType): Boolean =
        !located.containsKey(agentType.cli) || located[agentType.cli] != null

    /** 读不到就是读不到——不存在、无权限、编码坏了，一律降级为「未配置」。 */
    private fun read(relative: String): String? =
        runCatching {
            val file = userHome.resolve(relative)
            if (Files.isRegularFile(file)) Files.readString(file) else null
        }.getOrNull()
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests '*LspDiagnosticsTest*' --offline`
Expected: PASS，5 个测试全绿

- [ ] **Step 5: 跑全量测试确认没打破既有行为**

Run: `./gradlew test --offline`
Expected: 全部 PASS

- [ ] **Step 6: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/lsp/LspDiagnostics.kt \
        src/test/kotlin/com/github/izerui/imux/lsp/LspDiagnosticsTest.kt
git commit -m "$(cat <<'EOF'
增加 LSP 体检的编排器

语言服务器与三个 CLI 自身在同一次调用里问完，全流程只起一次登录 shell——
读 profile 的开销每次都要付，没有理由为查三个 CLI 名字再起三次。

探测超时返回空映射时，CLI 视为已安装、逐语言落到 UNKNOWN：把「没查到」说成
「没装」会让整页变成三行假消息。

Codex 挂载 MCP 后与 pi 走同一套 server，语言结果直接复用，不重复探测。
userHome 与 binaryProbe 均注入，与 SessionRepository.forUserHome 的做法一致。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: 设置子页、文案与注册

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/settings/ImuxLspConfigurable.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/main/resources/messages/ImuxBundle.properties` 及其余 9 个语言文件
- Test: `src/test/kotlin/com/github/izerui/imux/settings/ImuxLspUiSourceTest.kt`
- Test: `src/test/kotlin/com/github/izerui/imux/PluginXmlRegistrationTest.kt`（新增一个用例）

**Interfaces:**
- Consumes: `LspDiagnostics`、`LspReport`、`CliReport`、`LanguageFinding`、`LspStatus`、`Remedy`、`ShellBinaryProbe`
- Produces: `class ImuxLspConfigurable : BoundConfigurable`

- [ ] **Step 1: 写失败的测试**

创建 `src/test/kotlin/com/github/izerui/imux/settings/ImuxLspUiSourceTest.kt`：

```kotlin
package com.github.izerui.imux.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI 无法在本项目里跑起来做行为测试（未引入平台 test-framework，见 build.gradle.kts），
 * 因此对源码做结构断言，守住几条一旦破坏就只能在真机上才发现的约定。
 */
class ImuxLspUiSourceTest {
    private val source: String by lazy {
        File("src/main/kotlin/com/github/izerui/imux/settings/ImuxLspConfigurable.kt").readText()
    }

    /**
     * shell 探测要起登录 shell 读 profile，绝不能落在 EDT 上——
     * 与 PiReportEndpointCache 记录的是同一类教训。
     */
    @Test
    fun `体检在后台执行且回到 EDT 刷新`() {
        assertTrue("探测必须放到后台线程", source.contains("executeOnPooledThread"))
        assertTrue("刷新 UI 必须回到 EDT", source.contains("invokeLater"))
    }

    /**
     * CLI 是否安装必须并进那一次批量探测。页面里凡是另起 ProcessBuilder 的，
     * 都是又一个登录 shell——读一遍 profile 的钱要重付一次。
     */
    @Test
    fun `设置页自己不起 shell 进程`() {
        assertFalse("CLI 探测应并入 BinaryProbe，不在页面里另起进程", source.contains("ProcessBuilder"))
    }

    @Test
    fun `页面是只读的`() {
        assertTrue("体检页没有可保存状态", source.contains("override fun isModified(): Boolean = false"))
    }

    @Test
    fun `按 CLI 分组并给出重新检测入口`() {
        assertTrue(source.contains("settings.lsp.refresh"))
        assertTrue(source.contains("settings.lsp.checking"))
        assertTrue("必须说明只检查全局配置", source.contains("settings.lsp.scope.note"))
    }

    @Test
    fun `复制按钮走平台剪贴板`() {
        assertTrue(source.contains("CopyPasteManager"))
        assertTrue(source.contains("settings.lsp.copy"))
    }

    /** 图标必须用官方语义图标，不自绘。 */
    @Test
    fun `状态图标取自 AllIcons`() {
        assertTrue(source.contains("AllIcons."))
        assertFalse("不得引用自定义 svg", source.contains(".svg"))
    }
}
```

在 `src/test/kotlin/com/github/izerui/imux/PluginXmlRegistrationTest.kt` 中新增一个用例（放在 `注册了应用级 Imux 设置页` 之后）：

```kotlin
    @Test
    fun `注册了 LSP 体检子页`() {
        assertTrue(
            "LSP 体检必须挂在 Imux 设置页下，否则用户找不到入口",
            pluginXml.contains("com.github.izerui.imux.settings.ImuxLspConfigurable") &&
                pluginXml.contains("parentId=\"com.github.izerui.imux.settings\"") &&
                pluginXml.contains("displayName=\"LSP\""),
        )
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests '*ImuxLspUiSourceTest*' --tests '*PluginXmlRegistrationTest*' --offline`
Expected: FAIL，找不到 `ImuxLspConfigurable.kt`，且 plugin.xml 未注册

- [ ] **Step 3: 新增 12 个英文文案键**

在 `src/main/resources/messages/ImuxBundle.properties` 末尾追加：

```properties
settings.lsp.scope.note=Only global CLI configuration is checked. Per-project settings files are ignored.
settings.lsp.refresh=Re-check
settings.lsp.checking=Checking language servers...
settings.lsp.cli.missing={0} is not installed
settings.lsp.ready=Ready ({0})
settings.lsp.gaps=Missing ({0})
settings.lsp.status.config=plugin not enabled
settings.lsp.status.binary=server not on PATH
settings.lsp.status.unknown=could not determine
settings.lsp.copy=Copy
settings.lsp.pi.auto=pi-lens is installed and covers 36+ languages automatically. These need the server installed manually:
settings.lsp.codex.mount=Codex has no LSP of its own. Mounting the pi-lens MCP server gives it the same coverage as pi.
```

- [ ] **Step 4: 补齐其余 9 个语言文件**

追加到 `ImuxBundle_zh_CN.properties`：

```properties
settings.lsp.scope.note=仅检查 CLI 的全局配置，不读取各项目自己的配置文件。
settings.lsp.refresh=重新检测
settings.lsp.checking=正在检测语言服务器…
settings.lsp.cli.missing=未安装 {0}
settings.lsp.ready=已就绪（{0}）
settings.lsp.gaps=待补充（{0}）
settings.lsp.status.config=未启用插件
settings.lsp.status.binary=服务器不在 PATH 中
settings.lsp.status.unknown=无法确定
settings.lsp.copy=复制
settings.lsp.pi.auto=pi-lens 已安装，可自动覆盖 36 种以上语言。下列语言需要自行安装服务器：
settings.lsp.codex.mount=Codex 自身没有 LSP。挂载 pi-lens 的 MCP 服务端后，即可获得与 pi 相同的覆盖。
```

追加到 `ImuxBundle_zh_TW.properties`：

```properties
settings.lsp.scope.note=僅檢查 CLI 的全域設定，不讀取各專案自己的設定檔。
settings.lsp.refresh=重新檢測
settings.lsp.checking=正在檢測語言伺服器…
settings.lsp.cli.missing=未安裝 {0}
settings.lsp.ready=已就緒（{0}）
settings.lsp.gaps=待補充（{0}）
settings.lsp.status.config=未啟用外掛
settings.lsp.status.binary=伺服器不在 PATH 中
settings.lsp.status.unknown=無法確定
settings.lsp.copy=複製
settings.lsp.pi.auto=pi-lens 已安裝，可自動涵蓋 36 種以上語言。下列語言需要自行安裝伺服器：
settings.lsp.codex.mount=Codex 本身沒有 LSP。掛載 pi-lens 的 MCP 伺服端後，即可獲得與 pi 相同的涵蓋範圍。
```

追加到 `ImuxBundle_ja.properties`：

```properties
settings.lsp.scope.note=CLI のグローバル設定のみを確認します。プロジェクトごとの設定ファイルは読み取りません。
settings.lsp.refresh=再チェック
settings.lsp.checking=言語サーバーを確認しています...
settings.lsp.cli.missing={0} がインストールされていません
settings.lsp.ready=利用可能（{0}）
settings.lsp.gaps=未整備（{0}）
settings.lsp.status.config=プラグインが有効ではありません
settings.lsp.status.binary=サーバーが PATH にありません
settings.lsp.status.unknown=判定できません
settings.lsp.copy=コピー
settings.lsp.pi.auto=pi-lens がインストールされ、36 以上の言語を自動的にカバーします。次の言語はサーバーを手動で導入してください：
settings.lsp.codex.mount=Codex 自体に LSP はありません。pi-lens の MCP サーバーを登録すると pi と同じ範囲をカバーできます。
```

追加到 `ImuxBundle_ko.properties`：

```properties
settings.lsp.scope.note=CLI의 전역 설정만 확인합니다. 프로젝트별 설정 파일은 읽지 않습니다.
settings.lsp.refresh=다시 검사
settings.lsp.checking=언어 서버를 확인하는 중...
settings.lsp.cli.missing={0}이(가) 설치되지 않았습니다
settings.lsp.ready=사용 가능 ({0})
settings.lsp.gaps=미설정 ({0})
settings.lsp.status.config=플러그인이 활성화되지 않음
settings.lsp.status.binary=서버가 PATH에 없음
settings.lsp.status.unknown=확인할 수 없음
settings.lsp.copy=복사
settings.lsp.pi.auto=pi-lens가 설치되어 36개 이상의 언어를 자동으로 지원합니다. 다음 언어는 서버를 직접 설치해야 합니다:
settings.lsp.codex.mount=Codex에는 자체 LSP가 없습니다. pi-lens MCP 서버를 등록하면 pi와 동일한 범위를 지원합니다.
```

追加到 `ImuxBundle_de.properties`：

```properties
settings.lsp.scope.note=Es wird nur die globale CLI-Konfiguration geprüft. Projektbezogene Konfigurationsdateien werden ignoriert.
settings.lsp.refresh=Erneut prüfen
settings.lsp.checking=Sprachserver werden geprüft ...
settings.lsp.cli.missing={0} ist nicht installiert
settings.lsp.ready=Verfügbar ({0})
settings.lsp.gaps=Fehlend ({0})
settings.lsp.status.config=Plugin nicht aktiviert
settings.lsp.status.binary=Server nicht im PATH
settings.lsp.status.unknown=Nicht feststellbar
settings.lsp.copy=Kopieren
settings.lsp.pi.auto=pi-lens ist installiert und deckt über 36 Sprachen automatisch ab. Für folgende Sprachen muss der Server manuell installiert werden:
settings.lsp.codex.mount=Codex hat kein eigenes LSP. Mit dem pi-lens-MCP-Server erhält es dieselbe Abdeckung wie pi.
```

追加到 `ImuxBundle_fr.properties`（注意带占位符的消息里单引号必须写成 `''`，否则 `MessageFormat` 会吞掉参数，`ImuxBundleTest` 会直接失败）：

```properties
settings.lsp.scope.note=Seule la configuration globale des CLI est vérifiée. Les fichiers de configuration par projet sont ignorés.
settings.lsp.refresh=Revérifier
settings.lsp.checking=Vérification des serveurs de langage...
settings.lsp.cli.missing={0} n''est pas installé
settings.lsp.ready=Disponible ({0})
settings.lsp.gaps=Manquant ({0})
settings.lsp.status.config=extension non activée
settings.lsp.status.binary=serveur absent du PATH
settings.lsp.status.unknown=indéterminable
settings.lsp.copy=Copier
settings.lsp.pi.auto=pi-lens est installé et couvre automatiquement plus de 36 langages. Les langages suivants nécessitent une installation manuelle du serveur :
settings.lsp.codex.mount=Codex ne dispose pas de LSP. Monter le serveur MCP pi-lens lui donne la même couverture que pi.
```

追加到 `ImuxBundle_es.properties`：

```properties
settings.lsp.scope.note=Solo se comprueba la configuración global de las CLI. Los archivos de configuración por proyecto se ignoran.
settings.lsp.refresh=Volver a comprobar
settings.lsp.checking=Comprobando servidores de lenguaje...
settings.lsp.cli.missing={0} no está instalado
settings.lsp.ready=Disponible ({0})
settings.lsp.gaps=Faltante ({0})
settings.lsp.status.config=complemento no habilitado
settings.lsp.status.binary=servidor fuera del PATH
settings.lsp.status.unknown=no se puede determinar
settings.lsp.copy=Copiar
settings.lsp.pi.auto=pi-lens está instalado y cubre más de 36 lenguajes automáticamente. Estos lenguajes requieren instalar el servidor manualmente:
settings.lsp.codex.mount=Codex no tiene LSP propio. Montar el servidor MCP de pi-lens le da la misma cobertura que pi.
```

追加到 `ImuxBundle_pt_BR.properties`：

```properties
settings.lsp.scope.note=Apenas a configuração global das CLIs é verificada. Arquivos de configuração por projeto são ignorados.
settings.lsp.refresh=Verificar novamente
settings.lsp.checking=Verificando servidores de linguagem...
settings.lsp.cli.missing={0} não está instalado
settings.lsp.ready=Disponível ({0})
settings.lsp.gaps=Ausente ({0})
settings.lsp.status.config=plugin não habilitado
settings.lsp.status.binary=servidor fora do PATH
settings.lsp.status.unknown=não foi possível determinar
settings.lsp.copy=Copiar
settings.lsp.pi.auto=O pi-lens está instalado e cobre mais de 36 linguagens automaticamente. Estas linguagens exigem instalar o servidor manualmente:
settings.lsp.codex.mount=O Codex não tem LSP próprio. Montar o servidor MCP do pi-lens dá a ele a mesma cobertura do pi.
```

追加到 `ImuxBundle_ru.properties`：

```properties
settings.lsp.scope.note=Проверяется только глобальная конфигурация CLI. Файлы настроек отдельных проектов не читаются.
settings.lsp.refresh=Проверить заново
settings.lsp.checking=Проверка языковых серверов...
settings.lsp.cli.missing={0} не установлен
settings.lsp.ready=Доступно ({0})
settings.lsp.gaps=Отсутствует ({0})
settings.lsp.status.config=плагин не включён
settings.lsp.status.binary=сервер отсутствует в PATH
settings.lsp.status.unknown=не удалось определить
settings.lsp.copy=Копировать
settings.lsp.pi.auto=pi-lens установлен и автоматически покрывает более 36 языков. Для следующих языков сервер нужно установить вручную:
settings.lsp.codex.mount=У Codex нет собственного LSP. Подключение MCP-сервера pi-lens даёт то же покрытие, что и у pi.
```

- [ ] **Step 5: 运行文案测试确认 10 个语言键一致**

Run: `./gradlew test --tests '*ImuxBundleTest*' --offline`
Expected: PASS。若 `parameterized translations survive message formatting` 失败，检查法语 `n''est` 的双撇号是否漏写。

- [ ] **Step 6: 写设置子页**

创建 `src/main/kotlin/com/github/izerui/imux/settings/ImuxLspConfigurable.kt`：

```kotlin
package com.github.izerui.imux.settings

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.lsp.CliReport
import com.github.izerui.imux.lsp.LanguageFinding
import com.github.izerui.imux.lsp.LspDiagnostics
import com.github.izerui.imux.lsp.LspReport
import com.github.izerui.imux.lsp.LspStatus
import com.github.izerui.imux.lsp.Remedy
import com.github.izerui.imux.lsp.ShellBinaryProbe
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.JPanel

/**
 * Tools | Imux | LSP —— 三个 CLI 的 LSP 覆盖体检。
 *
 * 纯只读页：没有任何可保存的状态，[isModified] 恒为 false。它只回答一个问题——
 * 「我的 CLI 现在能不能用 LSP，不能的话该敲哪条命令」。
 *
 * 不做启动扫描、不弹通知：imux 已经有轮次完成提醒，再加一类噪音不划算。
 */
internal class ImuxLspConfigurable : BoundConfigurable("LSP") {

    private val content = JPanel(BorderLayout())

    override fun createPanel(): DialogPanel = panel {
        row {
            comment(ImuxBundle.message("settings.lsp.scope.note"))
        }
        row {
            button(ImuxBundle.message("settings.lsp.refresh")) { refresh() }
        }
        row {
            cell(content).align(com.intellij.ui.dsl.builder.AlignX.FILL)
        }
    }.also { refresh() }

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    /**
     * 体检要起登录 shell 读 profile，绝不能落在 EDT 上。
     *
     * 与 [com.github.izerui.imux.session.PiReportEndpointCache] 记录的是同一类教训：
     * 那次是 `BuiltInServerManager.waitForStart()` 在 EDT 上现算，IDE 刚启动时
     * 当场卡死界面。这里更糟——登录 shell 的启动开销是稳定存在的，不是偶发。
     */
    private fun refresh() {
        showChecking()
        ApplicationManager.getApplication().executeOnPooledThread {
            val report = runCatching { diagnostics().run() }.getOrNull()
            ApplicationManager.getApplication().invokeLater {
                if (report == null) showChecking() else showReport(report)
            }
        }
    }

    private fun diagnostics() = LspDiagnostics(
        userHome = Path.of(System.getProperty("user.home")),
        binaryProbe = ShellBinaryProbe(),
    )

    private fun showChecking() {
        content.removeAll()
        content.add(JBLabel(ImuxBundle.message("settings.lsp.checking")), BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
    }

    private fun showReport(report: LspReport) {
        content.removeAll()
        content.add(
            panel {
                report.cliReports.forEach { cliReport ->
                    group(cliReport.agentType.displayName) { renderCli(cliReport) }
                }
            },
            BorderLayout.CENTER,
        )
        content.revalidate()
        content.repaint()
    }

    private fun Panel.renderCli(cliReport: CliReport) {
        if (!cliReport.installed) {
            row {
                icon(AllIcons.General.Information)
                label(ImuxBundle.message("settings.lsp.cli.missing", cliReport.agentType.displayName))
            }
            return
        }

        cliReport.groupRemedy?.let { remedy ->
            row {
                icon(AllIcons.General.Warning)
                label(groupMessage(cliReport))
            }
            renderRemedy(remedy)
            return
        }

        val ready = cliReport.ready
        if (ready.isNotEmpty()) {
            row {
                icon(AllIcons.General.InspectionsOK)
                // 已就绪的折叠成一行：体检表一啰嗦就没人看
                label(
                    ImuxBundle.message("settings.lsp.ready", ready.size) + "  " +
                        ready.joinToString(" · ") { it.language.displayName },
                )
            }
        }

        val gaps = cliReport.gaps
        if (gaps.isEmpty()) return
        row {
            icon(AllIcons.General.Warning)
            label(ImuxBundle.message("settings.lsp.gaps", gaps.size))
        }
        gaps.forEach { finding ->
            row {
                label("${finding.language.displayName}  —  ${statusText(finding)}")
                    .customize(JBUI.Borders.emptyLeft(UNIT))
            }
            finding.remedy?.let { renderRemedy(it) }
        }
    }

    private fun Panel.renderRemedy(remedy: Remedy) {
        remedy.command?.let { command ->
            row {
                label(command).customize(JBUI.Borders.emptyLeft(UNIT * 2))
                button(ImuxBundle.message("settings.lsp.copy")) {
                    com.intellij.openapi.ide.CopyPasteManager.copyTextToClipboard(command)
                }
            }
        }
        // 没有已知安装命令时至少给出上游文档，不让用户卡在「不可用」三个字上
        if (remedy.command == null) {
            remedy.docsUrl?.let { url ->
                row { browserLink(url, url).customize(JBUI.Borders.emptyLeft(UNIT * 2)) }
            }
        }
    }

    private fun groupMessage(cliReport: CliReport): String =
        when (cliReport.agentType) {
            com.github.izerui.imux.model.AgentType.CODEX -> ImuxBundle.message("settings.lsp.codex.mount")
            else -> ImuxBundle.message("settings.lsp.pi.auto")
        }

    private fun statusText(finding: LanguageFinding): String = when (finding.status) {
        LspStatus.MISSING_CONFIG -> ImuxBundle.message("settings.lsp.status.config")
        LspStatus.MISSING_BINARY -> ImuxBundle.message("settings.lsp.status.binary")
        LspStatus.UNKNOWN -> ImuxBundle.message("settings.lsp.status.unknown")
        LspStatus.READY -> ""
    }

    private companion object {
        const val UNIT = 8
    }
}
```

> 实现提示：UI DSL v2 的 `customize` / `browserLink` / `AlignX` 在 262 上的确切签名以本机 SDK 为准。若 `customize(Borders)` 不可用，改用 `.gap(RightGap.SMALL)` 或在 `row` 上用 `indent { }` 缩进，效果等价。不要为了对齐而自绘组件。

- [ ] **Step 7: 注册设置子页**

在 `src/main/resources/META-INF/plugin.xml` 的 `<extensions defaultExtensionNs="com.intellij">` 内、现有 `applicationConfigurable` 之后追加：

```xml
        <applicationConfigurable
                parentId="com.github.izerui.imux.settings"
                instance="com.github.izerui.imux.settings.ImuxLspConfigurable"
                id="com.github.izerui.imux.settings.lsp"
                displayName="LSP"/>
```

- [ ] **Step 8: 运行全量测试**

Run: `./gradlew test --offline`
Expected: 全部 PASS，包括新增的 `ImuxLspUiSourceTest`、`PluginXmlRegistrationTest.注册了 LSP 体检子页`、以及 `ImuxBundleTest` 的四个 i18n 校验

- [ ] **Step 9: 编译整个插件确认无 API 误用**

Run: `./gradlew buildPlugin --offline`
Expected: BUILD SUCCESSFUL。若 UI DSL 的 `customize` / `browserLink` / `AlignX` 签名不符，按 Step 6 的实现提示改用等价 API 后重跑。

- [ ] **Step 10: 在真实 IDE 里验证 PATH 陷阱**

Run: `./gradlew runIde`

打开 **Settings → Tools → Imux → LSP**，确认：
1. 页面先显示"正在检测…"，随后出报告——**界面全程不卡顿**（卡了说明探测跑到了 EDT 上）
2. 已装的 server 显示为就绪。**注意**：`runIde` 的沙箱继承了终端 PATH，所以这里全绿不能证明正式 IDE 上也对。
3. 打包安装到正式 IDE（从 Dock 启动）再看一次——这才是 PATH 陷阱真正会暴露的地方。若正式 IDE 上一片红而沙箱里全绿，就是登录 shell 参数没生效。

- [ ] **Step 11: 提交**

```bash
git add src/main/kotlin/com/github/izerui/imux/settings/ImuxLspConfigurable.kt \
        src/main/resources/META-INF/plugin.xml \
        src/main/resources/messages/ \
        src/test/kotlin/com/github/izerui/imux/settings/ImuxLspUiSourceTest.kt \
        src/test/kotlin/com/github/izerui/imux/PluginXmlRegistrationTest.kt
git commit -m "$(cat <<'EOF'
增加 Tools | Imux | LSP 体检设置页

只读页面：列出三个 CLI 各自的 LSP 覆盖情况，缺什么给什么命令，一个字节都不
往用户配置里写、也不代为安装。

体检走后台线程后回 EDT 刷新：登录 shell 要读 profile，开销稳定存在，放在 EDT
上必然卡界面——与 PiReportEndpointCache 是同一类教训。

页面上明确标注只检查全局配置，让只在某个项目里配了 LSP 的用户知道误报从何而来。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## 计划自查

**Spec 覆盖**

| Spec 章节 | 对应任务 |
| --- | --- |
| 核心模型（配置缺口 / 二进制缺口） | Task 1（`LspStatus`） |
| Claude Code 探针（两个全局来源合并） | Task 3 |
| pi 探针（packages 找 pi-lens，gated 语言查二进制） | Task 4 |
| Codex 探针（config.toml 找 pi-lens-mcp） | Task 5 |
| PATH 陷阱（登录 shell、一次批量查） | Task 2 + Task 7 Step 10 |
| 设置子页（应用级、只读、不探测项目语言） | Task 7 |
| 静态映射表（两边二进制可能不同） | Task 1 |
| 错误处理（文件缺失/解析失败/超时/CLI 未装） | Task 3/4/5/6 各自的降级测试 |
| 测试（纯函数化、注入假探测） | 每个任务的测试 |
| 明确不做（不代装、不改配置、不探测项目语言） | 全程只读，无写路径 |

**类型一致性**：`LspStatus`、`Remedy`、`LanguageFinding`、`CliReport`、`LspReport` 在 Task 1 定义，Task 3/4/5/6/7 均按此签名使用；`BinaryProbe.locate` 的签名在 Task 2 定义，Task 6 的假实现与 Task 7 的真实现一致。

**已知风险**

1. **Gson 是平台捆绑库而非官方 API**。已实测编译通过，且单元测试会同时验证运行期可用性（测试跑在同一 classpath 上）。若 JetBrains 未来移除，编译期即报错，不会静默失效。
2. **UI DSL 细节签名**以本机 262 SDK 为准，Task 7 Step 9 的编译会暴露不符，Step 6 已给出等价替代方案。
3. **安装命令仅覆盖 macOS 常见路径**。非 macOS 用户看到的 brew 命令不适用——这是 spec 认可的取舍，改为分平台是纯数据改动，调用点不变。
