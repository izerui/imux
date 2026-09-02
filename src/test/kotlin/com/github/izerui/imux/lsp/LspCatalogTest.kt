package com.github.izerui.imux.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * 共用同一个 server 二进制的语言，pi-lens 侧的判定必须一致。
     *
     * C 与 C++ 都由 clangd-lsp 这一个插件供能，pi-lens 的 docs/language-coverage.md 里
     * 也是 `C/C++` 合并的一行——不可能一个自动装、一个要手动装。目录表里曾经把 C 写成
     * 非 gated，这个矛盾被「只显示有问题的语言」藏住了；改成全量列表后，同一个 clangd
     * 会在同一张表里一行显示「自动安装」、一行显示「不在 PATH」，当场自相矛盾。
     */
    @Test
    fun `共用同一个 Claude 插件二进制的语言 pi-lens 判定必须一致`() {
        LspCatalog.languages
            .filter { it.claudeBinary != null }
            .groupBy { it.claudeBinary }
            .forEach { (binary, group) ->
                val ids = group.map(LspLanguage::id)
                assertEquals(
                    "$binary 被 $ids 共用，piLensGated 必须一致",
                    1,
                    group.map(LspLanguage::piLensGated).toSet().size,
                )
                assertEquals(
                    "$binary 被 $ids 共用，piLensBinary 必须一致",
                    1,
                    group.map(LspLanguage::piLensBinary).toSet().size,
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
        // `c` 与 `cpp` 同属 spec 的 C/C++ 一行，两个都要在
        setOf("go", "java", "kotlin", "swift", "lua", "c", "cpp", "haskell", "elixir", "ocaml", "nix", "fsharp")
            .forEach { assertTrue("缺少 pi-lens gated 语言 $it", it in gated) }
    }

    /**
     * **每条安装命令推导出来的工具，都必须在前置工具表里。**
     *
     * 依赖关系只写在命令文本里（第一个 token 就是），推导规则与数据表因此必须两头对齐。
     * 漏一个的后果是：那门语言的「启用」按钮点下去，链的第一步就是
     * `command not found: <工具>`——而我们本来有机会先把它装上，或者退一步说清
     * 「需要先安装 &lt;工具&gt;」。
     *
     * 前置工具的**自身**安装命令一起摊进来查：`brew install --cask dotnet-sdk` 依赖
     * `brew`，那一条也必须在表里，否则「brew 都没有」这一层就没人接住。
     */
    @Test
    fun `每条安装命令依赖的工具都在前置表里`() {
        val commands = LspCatalog.servers.values.mapNotNull(LspServer::installCommand) +
            LspCatalog.tools.values.mapNotNull(LspTool::installCommand)
        val unknown = commands.map(::requiredTool).filterNot(LspCatalog.tools::containsKey).toSet()

        assertEquals("这些工具没有条目，缺了它们时页面什么都说不出来：$unknown", emptySet<String>(), unknown)
    }

    /**
     * 没有安装命令的工具必须有官网链接——那一格靠它才不是死路。
     *
     * `brew` / `go` / `npm` / `node` / `gem` 我们不给安装命令（不能用 brew 装 brew；其余
     * 牵扯到版本管理器与系统包管理器，猜一条命令跑下去坏的是用户的开发环境）。
     * 那时界面上只剩「需要先安装 brew」加一个链接——链接没了就只剩半句话。
     */
    @Test
    fun `每个前置工具都有官网链接`() {
        LspCatalog.tools.values.forEach { tool ->
            assertTrue("${tool.name}: 没有安装命令时至少要有官网链接", tool.docsUrl.isNotBlank())
        }
    }

    /** 表里的键必须就是工具名，否则按第一个 token 查表永远查不到。 */
    @Test
    fun `前置工具表按工具名索引`() {
        LspCatalog.tools.forEach { (name, tool) -> assertEquals(name, tool.name) }
    }

    /**
     * 前置工具必须与语言服务器**同一次**探测问完。
     *
     * 单独再起一次探测就是再付一遍读 profile 的钱（`zsh -l -i` 的开销是稳定存在的），
     * 而这一页每次刷新都要走一遍。漏掉的话更糟：`isToolPresent` 对一个没被探测过的名字
     * 只会答「不在」，于是每条链都会白白多出一层 `brew install …`。
     */
    @Test
    fun `前置工具与语言服务器在同一次探测里问完`() {
        assertTrue(
            "语言服务器必须都在探测集合里",
            LspCatalog.allProbeTargets.containsAll(LspCatalog.allBinaries),
        )
        assertTrue(
            "前置工具必须都在探测集合里，否则每条链都会白白多出一层安装命令",
            LspCatalog.allProbeTargets.containsAll(LspCatalog.tools.keys),
        )
    }

    /**
     * C# 与 Ruby 必须是 toolchain-gated。
     *
     * 依据是 pi-lens 自己的 `docs/lsp-capability-matrix.md` 第 139 行：那几行能填上是
     * 因为 server「要么自动安装（npm/pip/github 策略），要么**由 CI 工作流提供工具链
     * （Ruby/Dart/Zig + .NET→csharp）**」。`csharp-ls` 是 dotnet 全局工具、`ruby-lsp`
     * 是 gem，pi-lens 的三种自动安装策略一种都套不上。
     *
     * 写成非 gated 的后果是用户可见的假消息：机器上没装 .NET，pi 组的 C# 却挂着绿灯说
     * 「由 pi-lens 提供」，而实际用起来是 LSP Inactive——绿灯是这一页最不该撒谎的地方。
     */
    @Test
    fun `C# 与 Ruby 需要用户自己提供工具链`() {
        setOf("csharp", "ruby").forEach { id ->
            val language = LspCatalog.languages.single { it.id == id }
            assertTrue("$id 的 server 装在工具链上，pi-lens 自己装不了", language.piLensGated)
            assertNotNull("$id 必须声明 pi 侧的 server 二进制", language.piLensBinary)
        }
    }

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
    fun `brew 与 opam 系命令仍算 macOS 专属`() {
        listOf(
            "brew install jdtls",
            "brew install --cask kotlin-lsp",
            "brew install lua-language-server",
            "brew install llvm",
            "brew install --cask dotnet-sdk",
            "brew install rustup",
            "brew install opam",
            "opam install ocaml-lsp-server",
        ).forEach { command ->
            assertTrue("『$command』只在 macOS 上核实过", command in LspCatalog.macOnlyCommands)
        }
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
