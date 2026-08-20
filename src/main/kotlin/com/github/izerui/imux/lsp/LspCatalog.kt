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
