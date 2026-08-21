package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * 「能不能点一下就跑」的**行为**测试。
 *
 * 这一组用例存在的理由与 `LspStatusPresentationTest` 相同：设置页只能做源码文本断言，
 * 而文本断言总能被「保留被钉住的字面量、在别处改语义」绕开。这里直接**调用**，
 * 返回值一变就红。
 *
 * 与那一组不同的是后果的量级：这里守的不是「显示错了一行字」，而是
 * **Windows 用户点一下就在自己机器上执行 `brew install llvm`**。
 */
class LspRemedyRunTest {

    private fun activate(command: String? = "claude plugin install gopls-lsp@claude-plugins-official") =
        Remedy(command, null, RemedyKind.ACTIVATE)

    private fun install(command: String? = "brew install llvm") =
        Remedy(command, "https://clangd.llvm.org/installation", RemedyKind.INSTALL)

    /**
     * `claude plugin install` / `pi install` / `codex mcp add` 都是 CLI 自己的子命令，
     * 跟平台没关系。非 macOS 上把它们一起闸掉，等于让 Windows 与 Linux 用户白白多敲一遍。
     */
    @Test
    fun `激活类命令在所有有 POSIX shell 的平台都能跑`() {
        assertTrue(canRun(activate(), isMac = true, hasPosixShell = true))
        assertTrue(
            "激活是 CLI 的子命令，在 Linux 上闸掉它只是白白让用户多敲一遍",
            canRun(activate(), isMac = false, hasPosixShell = true),
        )
    }

    /**
     * 没有 POSIX shell 时**两类都不给**，包括跨平台的激活类。
     *
     * 命令跨平台，但起命令的那一层不跨：imux 是用 `shell -l -i -c` 把它交出去的，
     * 而 shell 来自 `resolveShell(System.getenv("SHELL"))`，Windows 上退回 `/bin/zsh`。
     *
     * 这一条红了，用户看到的是：Windows 的体检页上多出一个「激活」按钮，
     * 点下去弹 `Cannot run program /bin/zsh`。而这一页在 Windows 上**本来是完整可用的**
     * ——每门语言一句状态，缺口那几行退回上游文档链接，信息一样不少。那是拿一个能用的
     * 东西换了一个不能用的，和别处「一直不能用」不是一回事。
     */
    @Test
    fun `没有 POSIX shell 时两类命令都不给按钮`() {
        assertFalse(
            "命令跨平台，但 shell -l -i -c 这一层不跨；Windows 上会退回 /bin/zsh",
            canRun(activate(), isMac = false, hasPosixShell = false),
        )
        assertFalse(canRun(install(), isMac = false, hasPosixShell = false))
        // isMac 与 hasPosixShell 是两个维度，不许一个盖过另一个
        assertFalse(canRun(activate(), isMac = true, hasPosixShell = false))
        assertFalse(canRun(install(), isMac = true, hasPosixShell = false))
    }

    /**
     * 这一条是整次改动最要紧的断言。
     *
     * 目录表里的安装命令只在 macOS 上核实过——`brew install llvm`、`gem install ruby-lsp`、
     * `opam install ocaml-lsp-server`。从前它们只是显示出来给人复制，平台不对用户自己
     * 一眼就看出来了；现在按钮点下去是直接执行。这一条红了，意味着 Windows 用户的
     * 体检页上多出一个「安装」按钮，点下去在自己机器上跑 brew。
     */
    @Test
    fun `安装类命令只在 macOS 上能跑`() {
        assertTrue(canRun(install(), isMac = true, hasPosixShell = true))
        assertFalse(
            "目录表里的安装命令只在 macOS 上核实过，其它平台按下去就是执行 brew/gem/opam",
            canRun(install(), isMac = false, hasPosixShell = true),
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
     */
    @Test
    fun `目录表里没有一条安装命令能在非 macOS 上跑`() {
        val leaked = LspCatalog.servers.values
            .mapNotNull(LspServer::installCommand)
            .filter { canRun(Remedy(it, "https://x", RemedyKind.INSTALL), isMac = false, hasPosixShell = true) }

        assertEquals(
            "这些安装命令在非 macOS 上漏出了执行按钮，点下去就是在没有对应工具链的机器上执行：$leaked",
            emptyList<String>(),
            leaked,
        )
    }

    /**
     * 没有命令的（`kotlin-language-server`、`sourcekit-lsp` 这些 installCommand 为 null
     * 的），按钮无事可做。给一个点了没反应的按钮比不给更糟。
     */
    @Test
    fun `没有命令时任何平台都不显示按钮`() {
        listOf(true, false).forEach { isMac ->
            assertFalse(
                "isMac=$isMac 时仍给出了按钮，可点击却无事可做",
                canRun(activate(command = null), isMac, hasPosixShell = true),
            )
            assertFalse(
                "isMac=$isMac 时仍给出了按钮，可点击却无事可做",
                canRun(install(command = null), isMac, hasPosixShell = true),
            )
        }
    }

    /**
     * 「激活」与「安装」对用户是两件事：前者一两秒就完，后者可能下载几百兆。
     * 两个键对调的话，用户点一个写着「激活」的按钮，等来的是几分钟的下载。
     */
    @Test
    fun `按钮文案按性质分`() {
        assertEquals("settings.lsp.action.activate", runActionKey(RemedyKind.ACTIVATE))
        assertEquals("settings.lsp.action.install", runActionKey(RemedyKind.INSTALL))
    }

    /** 两类命令共用一条文案，等于没分——用户看不出点下去要不要等。 */
    @Test
    fun `两种性质的文案键互不相同`() {
        val keys = RemedyKind.entries.map(::runActionKey)

        assertEquals("每种性质的文案键必须互不相同：$keys", keys.size, keys.toSet().size)
    }

    /**
     * 键必须在资源包里真实存在，且资源包里不能留孤儿。
     *
     * 前一半挡打错的键（[com.github.izerui.imux.ImuxBundle] 取不到只会把按钮标题显示成
     * `!settings.lsp.action.install!`，跑起来才看得见）；后一半挡「改了映射忘了删旧键」
     * ——`ImuxBundleTest` 只比对十个语言文件的键集合是否一致，十个文件一起留着同一个
     * 没人用的键，它照样全绿。
     */
    @Test
    fun `文案键与资源包双向对齐`() {
        val bundle = Properties().apply {
            File("src/main/resources/messages/ImuxBundle.properties").reader(Charsets.UTF_8).use(::load)
        }
        val used = RemedyKind.entries.map(::runActionKey).toSet()

        used.forEach { key ->
            assertTrue("资源包里没有 $key，按钮上会显示成 !$key!", bundle.containsKey(key))
        }
        assertEquals(
            "资源包里有没人使用的 settings.lsp.action.* 键",
            used,
            bundle.stringPropertyNames().filter { it.startsWith("settings.lsp.action.") }.toSet(),
        )
    }

    /**
     * 「正在激活…」与「正在安装…」也必须分开。
     *
     * 这一列是用户点下按钮之后唯一盯着看的东西：写「正在激活」他会等两秒，
     * 写「正在安装」他知道可以先去干别的。两个键对调，用户会以为激活卡死了。
     */
    @Test
    fun `进行中文案按性质分`() {
        assertEquals("settings.lsp.status.activating", runningStatusKey(RemedyKind.ACTIVATE))
        assertEquals("settings.lsp.status.installing", runningStatusKey(RemedyKind.INSTALL))
    }

    /** 两类共用一条「处理中…」等于没分——用户看不出还要不要等。 */
    @Test
    fun `两种性质的进行中文案键互不相同`() {
        val keys = RemedyKind.entries.map(::runningStatusKey)

        assertEquals("每种性质的进行中文案键必须互不相同：$keys", keys.size, keys.toSet().size)
    }

    /**
     * 进行中的文案不能和任何一条**静态**状态文案共用一个键。
     *
     * 共用的话，「正在激活…」与「未启用插件」会是同一句话：点下按钮之后那一行纹丝不动，
     * 而这一整轮改动的起点正是用户那句「激活后，就状态应该变了啊」。
     */
    @Test
    fun `进行中文案与静态状态文案不共用键`() {
        val running = RemedyKind.entries.map(::runningStatusKey).toSet()
        val static = LspStatus.entries.mapNotNull(::statusMessageKey).toSet()

        assertEquals(
            "进行中文案与静态状态文案撞键了，点下按钮那一行不会有任何变化：${running intersect static}",
            emptySet<String>(),
            running intersect static,
        )
    }

    /**
     * 进行中文案的键也必须在资源包里真实存在。
     *
     * 取不到只会把那一列显示成 `!settings.lsp.status.activating!`——一条比原状态更糟的
     * 假消息，而且只有真跑起来、真点下按钮才看得见。
     */
    @Test
    fun `进行中文案键在资源包里存在`() {
        val bundle = Properties().apply {
            File("src/main/resources/messages/ImuxBundle.properties").reader(Charsets.UTF_8).use(::load)
        }

        RemedyKind.entries.map(::runningStatusKey).forEach { key ->
            assertTrue("资源包里没有 $key，界面上会显示成 !$key!", bundle.containsKey(key))
        }
    }

    /**
     * 行标识必须带上 CLI，**这是这几个纯函数里唯一有真实误报后果的一条**。
     *
     * 同一门语言在 Claude Code / pi / Codex 三个分组里各出现一次。只按语言记「谁在跑」
     * 的话，用户在 Claude 那组点一次「激活」，另外两组的同名语言会一起变成「正在激活…」
     * ——那两行根本没有任何命令在跑。三行同时说谎，而设置页那一侧的文本断言看不见它：
     * 调用点一字不改，只是键少拼了一段。
     */
    @Test
    fun `行标识区分 CLI`() {
        val kotlin = LspCatalog.languages.single { it.id == "kotlin" }

        assertEquals(
            "同一门语言在不同 CLI 下必须是不同的行；否则在一组里点激活，另外两组会一起假装在跑",
            AgentType.entries.size,
            AgentType.entries.map { runRowKey(it, kotlin) }.toSet().size,
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
        val keys = AgentType.entries.flatMap { agent ->
            LspCatalog.languages.map { runRowKey(agent, it) }
        }

        assertEquals("有两行拿到了同一个标识，它们会一起变成「进行中」：$keys", keys.size, keys.toSet().size)
    }

    /**
     * `-l` 与 `-i` 缺一不可，这个坑项目里踩过两次（`AgentCommand` 与 `BinaryProbe` 的
     * KDoc 都记着）：从 Dock 启动的 IDE 只有系统默认 PATH，而 `brew`、`go`、`npm`、
     * `rustup`、`gem` 一个都不在里面。少了 `-l`，用户点「安装」得到的是
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

    /** 认不出来时退回整条命令：标签名难看，远好过一个没有名字的空标签。 */
    @Test
    fun `认不出目标时退回整条命令`() {
        assertEquals("brew install llvm/", runTabTarget("brew install llvm/"))
    }

    /** 标签名是动词加目标：同时开着好几个安装标签时，用户要认得出哪个是哪个。 */
    @Test
    fun `标签名是动词加目标`() {
        assertEquals("安装 kotlin-lsp", runTabName("安装", "brew install --cask kotlin-lsp"))
        assertEquals("Activate npm:pi-lens", runTabName("Activate", "pi install npm:pi-lens"))
    }

    /**
     * 目录表里**每一条**安装命令都要能被认出一个非空的目标。
     *
     * 上面那条逐例断言只覆盖挑出来的几条；这一条把 18 门语言的命令一次摊开，
     * 改坏了提取规则时失败信息直接列出是哪几条变哑了。
     */
    @Test
    fun `目录表里的安装命令都能认出目标`() {
        val mute = LspCatalog.servers.values
            .mapNotNull { it.installCommand }
            .filter { runTabTarget(it).isBlank() }

        assertEquals("这些命令认不出安装目标，标签会没有名字：$mute", emptyList<String>(), mute)
    }
}
