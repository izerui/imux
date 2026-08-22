package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * 「一行一个按钮，点了就能用」的**行为**测试。
 *
 * 这一组用例存在的理由与 `LspStatusPresentationTest` 相同：设置页只能做源码文本断言，
 * 而文本断言总能被「保留被钉住的字面量、在别处改语义」绕开。这里直接**调用**，
 * 返回值一变就红。
 *
 * 与那一组不同的是后果的量级：这里守的不是「显示错了一行字」，而是
 * **Windows 用户点一下就在自己机器上执行 `brew install llvm`**，
 * 以及「按钮上写着启用、点下去只做了三件事里的一件」。
 */
class LspRemedyRunTest {
    private fun language(id: String): LspLanguage = LspCatalog.languages.single { it.id == id }

    /** 只有列出来的这几个名字在 PATH 里，其余一律确认不在。 */
    private fun present(vararg names: String): (String) -> BinaryAvailability =
        { name ->
            if (name in names) BinaryAvailability.PRESENT else BinaryAvailability.MISSING
        }

    private fun unknown(): (String) -> BinaryAvailability = { BinaryAvailability.UNKNOWN }

    private val claudePluginCommand = "claude plugin install kotlin-lsp@claude-plugins-official"

    // —— canRun：谁看得到这个按钮 ——

    /**
     * `claude plugin install` / `pi install` / `codex mcp add` 都是 CLI 自己的子命令，
     * 跟平台没关系。非 macOS 上把它们一起闸掉，等于让 Windows 与 Linux 用户白白多敲一遍。
     */
    @Test
    fun `纯 CLI 子命令的链在所有有 POSIX shell 的平台都能跑`() {
        val remedy = Remedy(listOf(claudePluginCommand), null)

        assertTrue(canRun(remedy, isMac = true, hasPosixShell = true))
        assertTrue(
            "这是 CLI 的子命令，在 Linux 上闸掉它只是白白让用户多敲一遍",
            canRun(remedy, isMac = false, hasPosixShell = true),
        )
    }

    /**
     * 没有 POSIX shell 时**任何链都不给**，包括纯 CLI 子命令那种。
     *
     * 命令跨平台，但起命令的那一层不跨：imux 是用 `shell -l -i -c` 把它交出去的，
     * 而 shell 来自 `resolveShell(System.getenv("SHELL"))`，Windows 上退回 `/bin/zsh`。
     *
     * 这一条红了，用户看到的是：Windows 的体检页上多出一个「启用」按钮，
     * 点下去弹 `Cannot run program /bin/zsh`。而这一页在 Windows 上**本来是完整可用的**
     * ——每门语言一句状态，缺口那几行由 `ImuxLspConfigurable.fallbackCell` 给出命令链的
     * 短目标名（完整链挂 tooltip）与上游文档链接，信息一样不少。那是拿一个能用的
     * 东西换了一个不能用的，和别处「一直不能用」不是一回事。
     */
    @Test
    fun `没有 POSIX shell 时任何链都不给按钮`() {
        val cli = Remedy(listOf(claudePluginCommand), null)
        val install = Remedy(listOf("brew install llvm"), "https://clangd.llvm.org/installation")

        assertFalse(
            "命令跨平台，但 shell -l -i -c 这一层不跨；Windows 上会退回 /bin/zsh",
            canRun(cli, isMac = false, hasPosixShell = false),
        )
        assertFalse(canRun(install, isMac = false, hasPosixShell = false))
        // isMac 与 hasPosixShell 是两个维度，不许一个盖过另一个
        assertFalse(canRun(cli, isMac = true, hasPosixShell = false))
        assertFalse(canRun(install, isMac = true, hasPosixShell = false))
    }

    /**
     * 这一条是整次改动最要紧的断言。
     *
     * 目录表里的安装命令只在 macOS 上核实过——`brew install llvm`、`gem install ruby-lsp`、
     * `opam install ocaml-lsp-server`。从前它们只是显示出来给人复制，平台不对用户自己
     * 一眼就看出来了；现在按钮点下去是直接执行。这一条红了，意味着 Windows 用户的
     * 体检页上多出一个「启用」按钮，点下去在自己机器上跑 brew。
     */
    @Test
    fun `目录表里的安装命令只在 macOS 上能跑`() {
        val remedy = Remedy(listOf("brew install llvm"), "https://clangd.llvm.org/installation")

        assertTrue(canRun(remedy, isMac = true, hasPosixShell = true))
        assertFalse(
            "目录表里的安装命令只在 macOS 上核实过，其它平台按下去就是执行 brew/gem/opam",
            canRun(remedy, isMac = false, hasPosixShell = true),
        )
    }

    /**
     * 目录表里**每一条**安装命令，在非 macOS 上都必须被闸住——一条都不许漏。
     *
     * 上一条只喂了 `brew install llvm` 一个样例，于是「看起来讲得通的一次放宽」能整个
     * 绕过它。实测这段代码 645 条全绿：
     *
     * ```
     * val portable = listOf("npm ", "dotnet ", "rustup ", "go ", "gem ", "opam ")
     * if (portable.any { remedy.command?.startsWith(it) == true }) return true
     * ```
     *
     * 它放走的包括 `gem install ruby-lsp` 与 `opam install ocaml-lsp-server`
     * ——**正是 `canRun` 自己的 KDoc 点名说「只有 macOS 形状」的那两条**。
     * 这不是恶意变异，是一个改代码的人合理地想「npm/go 明明跨平台」时会写出来的东西。
     *
     * 所以判据必须是**目录表的全集**而不是抽样，先例是下面的
     * [目录表里的安装命令都能认出目标]。失败时直接列出漏了哪几条。
     *
     * 本轮把**前置工具的安装命令**一起摊进来：`brew install --cask dotnet-sdk` 之流是
     * 这一轮才新长出来的、会被放进链里执行的命令，它们与目录表那批是同一种东西
     * （只在 macOS 上核实过的 brew 形状），漏掉的话链的第一步就能在 Linux 上跑起来。
     */
    @Test
    fun `没有一条 macOS 形状的命令能在非 macOS 上跑`() {
        val commands =
            LspCatalog.servers.values.mapNotNull(LspServer::installCommand) +
                LspCatalog.tools.values.mapNotNull(LspTool::installCommand)
        val leaked = commands.filter { canRun(Remedy(listOf(it), "https://x"), isMac = false, hasPosixShell = true) }

        assertEquals(
            "这些命令在非 macOS 上漏出了执行按钮，点下去就是在没有对应工具链的机器上执行：$leaked",
            emptyList<String>(),
            leaked,
        )
    }

    /**
     * 一条链里**只要含任何一条 macOS-only 的命令，整条就按 macOS-only 处理**。
     *
     * 这是链式命令带来的新形态，也是这一轮平台闸门唯一真正重想过的地方：C# 在一台
     * 干净的机器上要跑的是 `brew install --cask dotnet-sdk && dotnet tool install
     * --global csharp-ls && claude plugin install csharp-lsp@…`——最后一条跨平台，
     * 前两条不跨。按「最后一条能跑就给按钮」放行的话，Linux 用户点下去第一步就红着停下。
     *
     * 链是 `&&` 串的：第一条跑不通后面一条都跑不到，「前半段能跑」这种中间态不存在。
     */
    @Test
    fun `链里混进一条 macOS 形状的命令就整条闸住`() {
        val mixed =
            Remedy(
                listOf(
                    "brew install --cask dotnet-sdk",
                    "dotnet tool install --global csharp-ls",
                    "claude plugin install csharp-lsp@claude-plugins-official",
                ),
                null,
            )

        assertTrue(canRun(mixed, isMac = true, hasPosixShell = true))
        assertFalse(
            "链是 && 串的，第一条跑不通后面一条都跑不到；只看最后一条就放行，" +
                "Linux 用户点下去会得到一个红着停在第一步的终端标签",
            canRun(mixed, isMac = false, hasPosixShell = true),
        )
    }

    /**
     * 空链一律不给按钮——**这一条同时挡住两种「点了必然失败」**。
     *
     * 一种是没有已知安装命令（`kotlin-language-server`、`sourcekit-lsp` 那几门），
     * 一种是卡在一个我们装不上的前置工具上（[Remedy.blockingTool] 非空时链必为空）。
     * 给一个点了没反应或必然报错的按钮，比不给更糟。
     */
    @Test
    fun `空链在任何平台都不显示按钮`() {
        listOf(true, false).forEach { isMac ->
            assertFalse(
                "isMac=$isMac 时仍给出了按钮，可点击却无事可做",
                canRun(Remedy(emptyList(), "https://x"), isMac, hasPosixShell = true),
            )
            assertFalse(
                "isMac=$isMac 时卡在装不上的前置工具上却仍给出了按钮",
                canRun(Remedy(emptyList(), "https://brew.sh", blockingTool = "brew"), isMac, hasPosixShell = true),
            )
        }
    }

    // —— enableCommands：点一下到底跑什么 ——

    /**
     * 只缺配置那一层时，只跑装插件这一步。
     *
     * 本机真实场景：`kotlin-lsp` 二进制早就装好了（brew cask），Claude Code 却没启用插件。
     * 这一条红了，意味着用户点一下「启用」会被重新装一遍已经有的 1.3GB server。
     */
    @Test
    fun `只缺配置时只跑装插件那一步`() {
        assertEquals(
            listOf(claudePluginCommand),
            enableCommands(
                language("kotlin"),
                AgentType.CLAUDE,
                LspStatus.MISSING_CONFIG,
                present("kotlin-lsp", "brew"),
            ),
        )
    }

    /**
     * 配置与二进制**都**缺时，先装 server 再装插件——顺序不能反。
     *
     * `MISSING_CONFIG` 这个状态压根没说过二进制在不在（探针里配置层排在二进制层前面，
     * 一命中就返回了）。只装插件不装 server，用户点完按钮得到的是一个配好了、却指向
     * 一个不存在的程序的 LSP——「点了没用」换了个形状。
     */
    @Test
    fun `配置与二进制都缺时先装 server 再装插件`() {
        assertEquals(
            listOf("brew install --cask kotlin-lsp", claudePluginCommand),
            enableCommands(language("kotlin"), AgentType.CLAUDE, LspStatus.MISSING_CONFIG, present("brew")),
        )
    }

    @Test
    fun `未知的 server 与工具链不被推断为缺失`() {
        assertEquals(
            listOf(claudePluginCommand),
            enableCommands(language("kotlin"), AgentType.CLAUDE, LspStatus.MISSING_CONFIG, unknown()),
        )
    }

    /**
     * 三层全缺时，**前置工具排在最前面**——这就是简报里 C# 那个例子。
     *
     * `dotnet tool install --global csharp-ls` 在没装 .NET SDK 的机器上得到的是
     * `command not found: dotnet`，而用户看到的是一个写着「启用」的按钮点下去报错、
     * 真正缺的东西连提都没提。
     */
    @Test
    fun `三层全缺时前置工具排在最前面`() {
        assertEquals(
            listOf(
                "brew install --cask dotnet-sdk",
                "dotnet tool install --global csharp-ls",
                "claude plugin install csharp-lsp@claude-plugins-official",
            ),
            enableCommands(language("csharp"), AgentType.CLAUDE, LspStatus.MISSING_CONFIG, present("brew")),
        )
    }

    /** 前置工具已经在 PATH 里就不再装它一遍——多跑一条 `brew install --cask` 是几百兆。 */
    @Test
    fun `前置工具已在时不重复安装它`() {
        assertEquals(
            listOf(
                "dotnet tool install --global csharp-ls",
                "claude plugin install csharp-lsp@claude-plugins-official",
            ),
            enableCommands(
                language("csharp"),
                AgentType.CLAUDE,
                LspStatus.MISSING_CONFIG,
                present("brew", "dotnet"),
            ),
        )
    }

    /**
     * 只缺二进制时**不碰配置层**：那一层已经好了。
     *
     * pi 侧尤其如此——pi-lens 根本没有「装插件」这一步，链里冒出一条
     * `claude plugin install` 会在 pi 的分组里执行一条 Claude Code 的命令。
     */
    @Test
    fun `只缺二进制时不碰配置层`() {
        assertEquals(
            listOf("go install golang.org/x/tools/gopls@latest"),
            enableCommands(language("go"), AgentType.PI, LspStatus.MISSING_BINARY, present("go")),
        )
    }

    /**
     * 目录表里没有已知安装命令的（`kotlin-language-server`、`sourcekit-lsp` 等），
     * 一条命令都不给。
     *
     * 链跑完也不可用，那就不该有按钮——那一格退回文档链接（见 [remedyFor] 那组用例）。
     * 编一条跑不通的命令比不给命令更糟：用户会以为是自己环境的问题。
     */
    @Test
    fun `没有已知安装命令时一条命令都不给`() {
        assertEquals(
            emptyList<String>(),
            enableCommands(language("kotlin"), AgentType.PI, LspStatus.MISSING_BINARY, present("brew", "go", "npm")),
        )
    }

    /**
     * 不是「用户真能采取行动的缺口」的那四种状态，一条命令都不给。
     *
     * [LspStatus.AUTO_MANAGED] 是这里最要紧的一条：pi-lens 会自己装，给按钮就是让用户
     * 再手动装一遍——而用户看到「按需安装」时问的正是「为什么还要按需安装？」。
     * [LspStatus.NOT_AVAILABLE] 做什么都改变不了，[LspStatus.UNKNOWN] 是没查出来。
     */
    @Test
    fun `不是缺口的状态一条命令都不给`() {
        val nothing = present()
        listOf(LspStatus.READY, LspStatus.AUTO_MANAGED, LspStatus.NOT_AVAILABLE, LspStatus.UNKNOWN)
            .forEach { status ->
                LspCatalog.languages.forEach { language ->
                    AgentType.entries.forEach { agent ->
                        assertEquals(
                            "$status 的 ${language.id}/$agent 不该有任何可跑的命令",
                            emptyList<String>(),
                            enableCommands(language, agent, status, nothing),
                        )
                    }
                }
            }
    }

    /**
     * 装不上的前置工具挡住整条链，并把**是哪一个**说出来。
     *
     * `go` / `npm` / `gem` 的安装牵扯到版本管理器（nvm / rbenv / asdf）与系统包管理器，
     * 猜一条命令跑下去坏的是用户的开发环境。所以不给按钮，改说「需要先安装 go」。
     * 这一条红了，用户要么得到一个点下去 `command not found` 的按钮，
     * 要么得到一格什么都不说的空白——两种都让人以为插件坏了。
     */
    @Test
    fun `装不上的前置工具挡住整条链并说出是哪一个`() {
        val go = language("go")

        assertEquals(
            emptyList<String>(),
            enableCommands(go, AgentType.PI, LspStatus.MISSING_BINARY, present()),
        )
        assertEquals("go", enableBlocker(go, AgentType.PI, LspStatus.MISSING_BINARY, present())?.name)
    }

    /**
     * `brew` 自己不在时**不编一条用 brew 装 brew 的命令**。
     *
     * 这是「前置工具的前置工具」那一层：`dotnet` 我们会装，而装它的命令
     * （`brew install --cask dotnet-sdk`）自己要 brew。brew 缺了就到头了——
     * 报的必须是 `brew` 而不是 `dotnet`，否则用户照着「需要先安装 dotnet」去装 dotnet，
     * 装完回来发现按钮还是没有。
     */
    @Test
    fun `brew 缺失时报的是 brew 而不是它要装的那个工具`() {
        val csharp = language("csharp")

        assertEquals(
            emptyList<String>(),
            enableCommands(csharp, AgentType.CLAUDE, LspStatus.MISSING_CONFIG, present()),
        )
        assertEquals(
            "brew",
            enableBlocker(csharp, AgentType.CLAUDE, LspStatus.MISSING_CONFIG, present())?.name,
        )
    }

    /** 链能组出来时就没有阻塞工具——两个出口必须对同一份计划给出一致的回答。 */
    @Test
    fun `能组出链时没有阻塞工具`() {
        val kotlin = language("kotlin")

        assertNull(enableBlocker(kotlin, AgentType.CLAUDE, LspStatus.MISSING_CONFIG, present("brew")))
        assertTrue(enableCommands(kotlin, AgentType.CLAUDE, LspStatus.MISSING_CONFIG, present("brew")).isNotEmpty())
    }

    /**
     * 目录表里**每一门语言、每一个 CLI、两种缺口**组合出来的链，都不许含重复步骤，
     * 也不许在同一条链里既装 server 又跳过前置。
     *
     * 抽样测不到的是「某一门语言的命令形状特殊，把推导规则带偏」。这里把
     * 18 门 × 3 个 CLI × 2 种缺口摊开跑一遍，只断言两条结构性质：
     * 步骤不重复（重复意味着某一层被算了两次），且最后一步一定是目录表里认得的命令。
     */
    @Test
    fun `目录表全摊开时链里没有重复步骤`() {
        val known =
            LspCatalog.servers.values
                .mapNotNull(LspServer::installCommand)
                .toSet() +
                LspCatalog.tools.values
                    .mapNotNull(LspTool::installCommand)
                    .toSet()
        val bad = mutableListOf<String>()
        listOf(LspStatus.MISSING_CONFIG, LspStatus.MISSING_BINARY).forEach { status ->
            AgentType.entries.forEach { agent ->
                LspCatalog.languages.forEach { language ->
                    val chain = enableCommands(language, agent, status, present())
                    if (chain.size != chain.toSet().size) {
                        bad += "${language.id}/$agent/$status 链里有重复步骤：$chain"
                    }
                    chain.dropLast(1).forEach { step ->
                        if (step !in known) {
                            bad += "${language.id}/$agent/$status 里 `$step` 不是目录表认得的命令"
                        }
                    }
                }
            }
        }

        assertEquals(bad.joinToString("\n"), emptyList<String>(), bad)
    }

    /** 依赖推导就是命令的第一个 token——不另维护一张会漂移的映射表。 */
    @Test
    fun `依赖的工具就是命令的第一个 token`() {
        assertEquals("brew", requiredTool("brew install --cask dotnet-sdk"))
        assertEquals("dotnet", requiredTool("dotnet tool install --global csharp-ls"))
        assertEquals("go", requiredTool("go install golang.org/x/tools/gopls@latest"))
        assertEquals("claude", requiredTool(" claude plugin install kotlin-lsp@claude-plugins-official "))
    }

    // —— remedyFor：这一行在界面上还剩什么 ——

    /**
     * 链组不出来时，给的链接必须是**那个工具**的官网，不是语言服务器的文档。
     *
     * 用户下一步要做的事是装 brew / node / ruby，不是读 pyright 的 README。
     * 给错链接等于让他绕一大圈才发现自己缺的根本不是那个东西。
     */
    @Test
    fun `被前置工具挡住时给的是那个工具的官网`() {
        val remedy = remedyFor(language("go"), AgentType.PI, LspStatus.MISSING_BINARY, present())

        assertEquals("go", remedy?.blockingTool)
        assertEquals(LspCatalog.tool("go")?.docsUrl, remedy?.docsUrl)
        assertEquals(
            "被工具挡住时链必为空，否则 canRun 会放出一个点下去必然 command not found 的按钮",
            emptyList<String>(),
            remedy?.commands,
        )
    }

    /**
     * 没有已知安装命令时**也要有文档链接**——否则那一格是彻底的死路。
     *
     * 本机真实场景：pi 侧的 Kotlin 用社区的 `kotlin-language-server`，目录表里没有
     * 已核实的安装命令。没有链接的话，这一行只剩「服务器不在 PATH 中」六个字。
     */
    @Test
    fun `没有安装命令时退回上游文档链接`() {
        val remedy = remedyFor(language("kotlin"), AgentType.PI, LspStatus.MISSING_BINARY, present())

        assertEquals(emptyList<String>(), remedy?.commands)
        assertEquals("https://github.com/fwcd/kotlin-language-server", remedy?.docsUrl)
    }

    /**
     * 配置缺口**也**带上 server 的上游文档链接。
     *
     * 从前这一类修复的 docsUrl 恒为 null，于是闸门挡下按钮时（Windows、或从欢迎页打开
     * 设置）Claude Code 组 13 门语言那一格只剩一个短标签。多给一个链接是纯增益。
     */
    @Test
    fun `配置缺口也带上上游文档链接`() {
        val remedy = remedyFor(language("kotlin"), AgentType.CLAUDE, LspStatus.MISSING_CONFIG, present("brew"))

        assertEquals(listOf("brew install --cask kotlin-lsp", claudePluginCommand), remedy?.commands)
        assertEquals("https://github.com/Kotlin/kotlin-lsp", remedy?.docsUrl)
    }

    /**
     * 没有下一步可做的行**不许有 remedy**——那一格必须是空的。
     *
     * 尤其是 [LspStatus.AUTO_MANAGED]：摆个链接或标签只会让人以为有事要办，
     * 而用户对这一行什么都不用做。这正是「为什么还要按需安装？」那句话的正面回答。
     */
    @Test
    fun `没有下一步的行不给任何建议`() {
        val nothing = present()
        val leaked =
            LspCatalog.languages.flatMap { language ->
                listOf(LspStatus.READY, LspStatus.AUTO_MANAGED, LspStatus.NOT_AVAILABLE, LspStatus.UNKNOWN)
                    .flatMap { status ->
                        AgentType.entries.mapNotNull { agent ->
                            "${language.id}/$agent/$status".takeIf { remedyFor(language, agent, status, nothing) != null }
                        }
                    }
            }

        assertEquals("这些行没有任何可执行的下一步，却给了建议：$leaked", emptyList<String>(), leaked)
    }

    // —— 命令链拼接 ——

    /**
     * 链用 `&&` 串起来：**哪一步失败就停在哪，用户在终端里看得见**。
     *
     * 换成 `;` 的话，`brew install --cask dotnet-sdk` 失败之后 `dotnet tool install`
     * 照样会跑一遍、再失败一次、最后 `claude plugin install` 把一个指向不存在程序的
     * 配置写进 `~/.claude/settings.json`——用户得到的是一屏红字加一个坏掉的配置。
     */
    @Test
    fun `链用 and-and 串起来`() {
        assertEquals(
            "brew install --cask dotnet-sdk && dotnet tool install --global csharp-ls",
            Remedy(listOf("brew install --cask dotnet-sdk", "dotnet tool install --global csharp-ls"), null).chain,
        )
        assertEquals("pi install npm:pi-lens", Remedy(listOf("pi install npm:pi-lens"), null).chain)
    }

    /** 空链没有命令行可给：`""` 会让终端标签起一个什么都不做的 shell，而按钮看着能点。 */
    @Test
    fun `空链没有命令行`() {
        assertNull(Remedy(emptyList(), "https://x").chain)
    }

    // —— 文案键 ——

    /**
     * 按钮上只剩一个词、进行中也只剩一句，两个键都必须在资源包里真实存在，
     * 且资源包里不能留孤儿。
     *
     * 前一半挡打错的键（[com.github.izerui.imux.ImuxBundle] 取不到只会把按钮标题显示成
     * `!settings.lsp.action.enable!`，跑起来才看得见）；后一半挡「改了映射忘了删旧键」
     * ——`ImuxBundleTest` 只比对十个语言文件的键集合是否一致，十个文件一起留着同一个
     * 没人用的键，它照样全绿。上一轮的 `settings.lsp.action.activate` /
     * `settings.lsp.action.install` 正是这么被这条断言逼着从十个文件里删干净的。
     */
    @Test
    fun `按钮文案键与资源包双向对齐`() {
        val bundle =
            Properties().apply {
                File("src/main/resources/messages/ImuxBundle.properties").reader(Charsets.UTF_8).use(::load)
            }

        assertTrue(
            "资源包里没有 $ENABLE_ACTION_KEY，按钮上会显示成 !$ENABLE_ACTION_KEY!",
            bundle.containsKey(ENABLE_ACTION_KEY),
        )
        assertEquals(
            "资源包里有没人使用的 settings.lsp.action.* 键——按钮上只剩一个词「启用」了",
            setOf(ENABLE_ACTION_KEY),
            bundle.stringPropertyNames().filter { it.startsWith("settings.lsp.action.") }.toSet(),
        )
    }

    /**
     * 进行中的文案不能和任何一条**静态**状态文案共用一个键。
     *
     * 共用的话，「正在启用…」与「未启用插件」会是同一句话：点下按钮之后那一行纹丝不动，
     * 而这一整轮改动的起点正是用户那句「激活后，就状态应该变了啊」。
     */
    @Test
    fun `进行中文案与静态状态文案不共用键`() {
        val static = LspStatus.entries.mapNotNull(::statusMessageKey).toSet()

        assertFalse(
            "进行中文案与静态状态文案撞键了，点下按钮那一行不会有任何变化：$ENABLING_STATUS_KEY",
            ENABLING_STATUS_KEY in static,
        )
    }

    /**
     * 进行中文案的键也必须在资源包里真实存在。
     *
     * 取不到只会把那一列显示成 `!settings.lsp.status.enabling!`——一条比原状态更糟的
     * 假消息，而且只有真跑起来、真点下按钮才看得见。
     */
    @Test
    fun `进行中文案键在资源包里存在`() {
        val bundle =
            Properties().apply {
                File("src/main/resources/messages/ImuxBundle.properties").reader(Charsets.UTF_8).use(::load)
            }

        assertTrue(
            "资源包里没有 $ENABLING_STATUS_KEY，界面上会显示成 !$ENABLING_STATUS_KEY!",
            bundle.containsKey(ENABLING_STATUS_KEY),
        )
    }

    /**
     * 「需要先安装 &lt;工具&gt;」那句必须带占位符。
     *
     * 不带的话，用户看到的是一句「需要先安装」——需要先安装什么？而这一格存在的**全部**
     * 意义就是把那个名字说出来。`ImuxBundleTest` 只比对十个语言文件之间占位符一致，
     * 十个文件一起漏掉 `{0}` 它照样全绿。
     */
    @Test
    fun `缺工具的提示必须说出是哪个工具`() {
        val bundle =
            Properties().apply {
                File("src/main/resources/messages/ImuxBundle.properties").reader(Charsets.UTF_8).use(::load)
            }

        assertTrue(
            "settings.lsp.tool.missing 必须带 {0}，否则用户看到的是「需要先安装」——安装什么？",
            bundle.getProperty("settings.lsp.tool.missing").orEmpty().contains("{0}"),
        )
    }

    // —— 行标识 / 终端命令行 / 标签名 ——

    /**
     * 行标识必须带上 CLI，**这是这几个纯函数里唯一有真实误报后果的一条**。
     *
     * 同一门语言在 Claude Code / pi / Codex 三个分组里各出现一次。只按语言记「谁在跑」
     * 的话，用户在 Claude 那组点一次「启用」，另外两组的同名语言会一起变成「正在启用…」
     * ——那两行根本没有任何命令在跑。三行同时说谎，而设置页那一侧的文本断言看不见它：
     * 调用点一字不改，只是键少拼了一段。
     */
    @Test
    fun `行标识区分 CLI`() {
        val kotlin = language("kotlin")

        assertEquals(
            "同一门语言在不同 CLI 下必须是不同的行；否则在一组里点启用，另外两组会一起假装在跑",
            AgentType.entries.size,
            AgentType.entries
                .map { runRowKey(it, kotlin) }
                .toSet()
                .size,
        )
    }

    /**
     * 18 门语言 × 3 个 CLI = 54 行，行标识必须两两不同。
     *
     * 上一条只喂了 kotlin 一门；这一条把整张表摊开，失败信息直接给出撞了多少个。
     * 语言 id 与 CLI 名之间少一个分隔符之类的写法只在特定组合上撞车，抽样抓不住。
     */
    @Test
    fun `目录表里每一行的标识都互不相同`() {
        val keys =
            AgentType.entries.flatMap { agent ->
                LspCatalog.languages.map { runRowKey(agent, it) }
            }

        assertEquals("有两行拿到了同一个标识，它们会一起变成「进行中」：$keys", keys.size, keys.toSet().size)
    }

    /**
     * `-l` 与 `-i` 缺一不可，这个坑项目里踩过两次（`AgentCommand` 与 `BinaryProbe` 的
     * KDoc 都记着）：从 Dock 启动的 IDE 只有系统默认 PATH，而 `brew`、`go`、`npm`、
     * `rustup`、`gem` 一个都不在里面。少了 `-l`，用户点「启用」得到的是
     * `command not found`；少了 `-i`，配成 alias 的工具同样找不到。
     *
     * 从终端 `runIde` 起的沙箱继承了终端的 PATH，所以这个缺陷**在沙箱里永远复现不出来**
     * ——只有装到正式 IDE 上才暴露。这条断言是它唯一的看门人。
     */
    @Test
    fun `命令行必须经登录且交互的 shell`() {
        assertEquals(
            listOf("/bin/zsh", "-l", "-i", "-c", "brew install llvm"),
            runCommandLine("/bin/zsh", "brew install llvm"),
        )
    }

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

    /** 命令原样交给 `-c`，不做任何拼接或转义——它整条就是 shell 的最后一个实参。 */
    @Test
    fun `命令原样作为 shell 的最后一个实参`() {
        val quoted = """npm install -g "a b" && echo 'done'"""

        assertEquals(quoted, runCommandLine("/bin/bash", quoted).last())
    }

    /**
     * 标签栏上只有十来个字符的位置。写完整命令的话，
     * `brew install llvm` 与 `brew install lua-language-server` 前缀完全一样，
     * 用户同时开着几个安装标签时认不出哪个是哪个。
     */
    @Test
    fun `标签名认出的是安装目标`() {
        assertEquals("llvm", runTabTarget("brew install llvm"))
        assertEquals("kotlin-lsp", runTabTarget("brew install --cask kotlin-lsp"))
        assertEquals("gopls", runTabTarget("go install golang.org/x/tools/gopls@latest"))
        assertEquals("gopls-lsp", runTabTarget("claude plugin install gopls-lsp@claude-plugins-official"))
        assertEquals("npm:pi-lens", runTabTarget("pi install npm:pi-lens"))
        assertEquals("pi-lens-mcp", runTabTarget("codex mcp add pi-lens -- pi-lens-mcp"))
        assertEquals("rust-analyzer", runTabTarget("rustup component add rust-analyzer"))
    }

    /**
     * 喂进去的是**整条链**时，认出的是**最后一步**的目标。
     *
     * 一条链的意义由它的终点决定：C# 那条三步链认出来必须是 `csharp-lsp`，
     * 而不是让用户在标签栏上看到 `dotnet-sdk` 去猜这跟 C# 有什么关系。
     */
    @Test
    fun `整条链认出的是最后一步的目标`() {
        assertEquals(
            "csharp-lsp",
            runTabTarget(
                "brew install --cask dotnet-sdk && dotnet tool install --global csharp-ls && " +
                    "claude plugin install csharp-lsp@claude-plugins-official",
            ),
        )
    }

    /** 认不出来时退回整条命令：标签名难看，远好过一个没有名字的空标签。 */
    @Test
    fun `认不出目标时退回整条命令`() {
        assertEquals("brew install llvm/", runTabTarget("brew install llvm/"))
    }

    /** 标签名是动词加目标：同时开着好几个标签时，用户要认得出哪个是哪个。 */
    @Test
    fun `标签名是动词加目标`() {
        assertEquals("启用 kotlin-lsp", runTabName("启用", "brew install --cask kotlin-lsp"))
        assertEquals("Enable npm:pi-lens", runTabName("Enable", "pi install npm:pi-lens"))
    }

    /**
     * 目录表里**每一条**安装命令都要能被认出一个非空的目标。
     *
     * 上面那条逐例断言只覆盖挑出来的几条；这一条把 18 门语言的命令一次摊开，
     * 改坏了提取规则时失败信息直接列出是哪几条变哑了。
     */
    @Test
    fun `目录表里的安装命令都能认出目标`() {
        val mute =
            LspCatalog.servers.values
                .mapNotNull { it.installCommand }
                .filter { runTabTarget(it).isBlank() }

        assertEquals("这些命令认不出安装目标，标签会没有名字：$mute", emptyList<String>(), mute)
    }
}
