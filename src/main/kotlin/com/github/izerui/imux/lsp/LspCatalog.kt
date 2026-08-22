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

/**
 * 一条安装命令**自己**依赖的工具链。
 *
 * `dotnet tool install --global csharp-ls` 这条命令，在没装 .NET SDK 的机器上得到的是
 * `command not found: dotnet`。用户看到的是一个写着「启用」的按钮点下去报错——而真正
 * 缺的东西（dotnet）连提都没提。所以命令链的第一层就是「把这条命令要用的工具装上」。
 *
 * [installCommand] 为 null 表示**我们没有可靠的安装方式**，此时不给按钮，改在那一格里
 * 说「需要先安装 &lt;工具&gt;」并给 [docsUrl]。`brew` 是这一类的典型：不能用 brew 装 brew。
 * `go` / `npm` / `gem` 同理——它们的安装方式牵扯到版本管理器（nvm / rbenv / asdf）
 * 与系统包管理器，猜一条命令跑下去，坏的是用户的开发环境。
 */
internal data class LspTool(
    val name: String,
    val installCommand: String?,
    val docsUrl: String,
)

internal object LspCatalog {

    /**
     * 安装命令按 macOS 给出（imux 的主要用户在 macOS，且本机可核实）。
     * npm / go / dotnet / rustup / gem 这些跨平台工具的命令三平台形态完全相同，
     * 已经不再被 [macOnlyCommands] 拦截；只有 brew 与 opam 系的命令仍是 macOS 专属，
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
     * 上面那些安装命令**自己**要用到的工具，按命令的第一个 token 索引。
     *
     * 这张表刻意与 [servers] 分开，而不是给每条 server 命令挂一个「依赖」字段：
     * 依赖关系已经写在命令文本里了（第一个 token 就是），再抄一遍就是两处会漂移的真相。
     * `LspCatalogTest` 反过来钉住「每条 server 命令推导出来的工具都在这张表里」。
     *
     * 三条安装命令都在本机核实过，`dotnet` 那条尤其容易写错：
     * .NET SDK 在 Homebrew 里是 **cask**（`brew install --cask dotnet-sdk`），
     * 写成 formula 的 `brew install dotnet-sdk` 直接报 `No available formula`。
     *
     * `brew` / `go` / `npm` / `gem` 四条留空 [LspTool.installCommand]：
     * 见 [LspTool] 的说明——猜一条命令去装它们，坏的是用户的开发环境。
     */
    val tools: Map<String, LspTool> = listOf(
        LspTool("brew", null, "https://brew.sh"),
        LspTool("go", null, "https://go.dev/dl/"),
        LspTool("npm", null, "https://nodejs.org/en/download"),
        LspTool("gem", null, "https://www.ruby-lang.org/en/documentation/installation/"),
        LspTool("dotnet", "brew install --cask dotnet-sdk", "https://dotnet.microsoft.com/download"),
        LspTool("rustup", "brew install rustup", "https://rustup.rs"),
        LspTool("opam", "brew install opam", "https://opam.ocaml.org/doc/Install.html"),
    ).associateBy(LspTool::name)

    fun tool(name: String): LspTool? = tools[name]

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

    /**
     * 语言集合 = 官方 12 个 Claude Code LSP 插件覆盖的 13 门语言（clangd-lsp 一个插件
     * 管 C 与 C++）∪ pi-lens 的 toolchain-gated 语言，共 18 门。
     *
     * pi-lens 覆盖 36+ 门，其余那些既不 gated 又没有官方 Claude 插件的语言不进这张表：
     * 它们在三个 CLI 上都没有可展示的差异，列出来只会把表撑长。表里这 18 门则**每门
     * 都在每个分组里出现**——「没什么可查」是好消息，得说出来，不能与「不显示」混为一谈。
     */
    val languages: List<LspLanguage> = listOf(
        // C 与 C++ 共用同一个 clangd，pi-lens 的 docs/language-coverage.md 里也是
        // `C/C++` 合并的一行，不可能一个自动装一个要手动装。曾经把 C 写成非 gated，
        // 这个矛盾被「只显示有问题的语言」藏住了；改成全量列表后会当场露馅。
        LspLanguage("c", "C", "clangd-lsp", "clangd", piLensGated = true, piLensBinary = "clangd"),
        LspLanguage("cpp", "C++", "clangd-lsp", "clangd", piLensGated = true, piLensBinary = "clangd"),
        // C# 与 Ruby 曾经写成非 gated（= pi-lens 自动装），那是**读错了 pi-lens 的文档**。
        // pi-lens 的 docs/lsp-capability-matrix.md 第 139 行原文说，那几行能填上是因为
        // server「要么自动安装（npm/pip/github 策略），要么由 CI 工作流提供工具链
        //（Ruby/Dart/Zig + .NET→csharp）」——后半句正是这两门。`csharp-ls` 是 dotnet
        // 全局工具、`ruby-lsp` 是 gem，pi-lens 的三种自动安装策略一种都套不上，
        // 它需要 .NET SDK / Ruby 先在那儿。也就是说它们与 kotlin/swift 那批是同一回事，
        // 只是没被列进 pi-lens 那份 gated 名单（因为 pi-lens 自己的 CI 会装工具链，
        // 而用户的机器不会）。写成非 gated 的后果是：用户机器上没装 .NET，pi 组的 C#
        // 却挂着绿灯说「由 pi-lens 提供」，而实际用起来是 LSP Inactive。
        LspLanguage("csharp", "C#", "csharp-lsp", "csharp-ls", piLensGated = true, piLensBinary = "csharp-ls"),
        LspLanguage("go", "Go", "gopls-lsp", "gopls", piLensGated = true, piLensBinary = "gopls"),
        LspLanguage("java", "Java", "jdtls-lsp", "jdtls", piLensGated = true, piLensBinary = "jdtls"),
        LspLanguage("kotlin", "Kotlin", "kotlin-lsp", "kotlin-lsp", piLensGated = true, piLensBinary = "kotlin-language-server"),
        LspLanguage("lua", "Lua", "lua-lsp", "lua-language-server", piLensGated = true, piLensBinary = "lua-language-server"),
        LspLanguage("php", "PHP", "php-lsp", "intelephense", piLensGated = false, piLensBinary = null),
        LspLanguage("python", "Python", "pyright-lsp", "pyright-langserver", piLensGated = false, piLensBinary = null),
        // 与 csharp 同一条依据，见上面那段注释。
        LspLanguage("ruby", "Ruby", "ruby-lsp", "ruby-lsp", piLensGated = true, piLensBinary = "ruby-lsp"),
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

    /** 探测一遍需要查的全部语言服务器二进制，去重后交给 BinaryProbe 一次问完。 */
    val allBinaries: Set<String> =
        languages.flatMap { listOfNotNull(it.claudeBinary, it.piLensBinary) }.toSet()

    /**
     * 一次登录 shell 里要查完的**全部**名字：语言服务器 + 它们的安装命令依赖的工具链。
     *
     * 前置工具必须与语言服务器**同一次**问完。单独再起一次探测就是再付一遍读 profile
     * 的钱（`zsh -l -i` 的开销是稳定存在的，不是偶发），而这一页每次刷新都要走一遍。
     */
    val allProbeTargets: Set<String> = allBinaries + tools.keys
}

/** 安装方式因发行版而异、无法跨平台照抄的包管理器。 */
private val NON_PORTABLE_TOOLS = setOf("brew", "opam")
