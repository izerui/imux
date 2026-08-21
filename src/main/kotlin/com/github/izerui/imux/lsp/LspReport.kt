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

    /**
     * pi-lens 会按需自动安装该语言的 server，用户无需干预。
     *
     * 刻意**不**退化成查 PATH：pi-lens 是懒安装的，用到才装，装到哪也不归 imux 管
     * （本机 `~/.pi/agent/npm/node_modules/.bin/` 里只有 pi-lens 自己的工具）。
     * 对非 gated 语言，PATH 里有就直接用、没有就按需装，两种情况最终都可用——
     * 查 PATH 的结果与真相无关，标成「未安装」比原先的「不显示」更糟。
     */
    AUTO_MANAGED,

    /** 该 CLI 生态里没有这门语言的接入方式（Claude Code 官方无对应 LSP 插件）。 */
    NOT_AVAILABLE,
}

/**
 * 一条修复建议的**性质**。
 *
 * 它决定按钮上写什么字，更重要的是决定这条命令**能不能让用户点一下就跑**
 *（见 `canRun`）：两类命令的跨平台程度差着一个量级，而按钮一旦存在就是真执行，
 * 不再是「复制出来自己看一眼」。
 */
internal enum class RemedyKind {
    /**
     * 装 CLI 插件 / 扩展 / 挂 MCP。是 CLI 自己的子命令，不依赖任何外部工具链，快且确定。
     *
     * **准确说清它跨的是什么、不跨什么**——这句话是下一个人放宽闸门时的依据，写错了
     * 比代码写错更贵，因为它会被复用：`claude plugin install` / `pi install` /
     * `codex mcp add` 这三条**命令本身**在三大平台上都成立，所以它们不受
     * 「目录表的安装命令只在 macOS 核实过」那道闸门限制。但**起这条命令的那一层不跨**
     * ——imux 是用 `shell -l -i -c` 把它交出去的，而 shell 来自
     * `resolveShell(System.getenv("SHELL"))`，Windows 上退回 `/bin/zsh`。
     * 所以 ACTIVATE 仍然受「有没有 POSIX shell」那道闸门限制（见 `canRun`）。
     *
     * 这类命令会写用户的 `~/.claude/settings.json` 之类的文件——但**写的是 CLI，不是
     * imux**。imux 只是替用户敲了那行字，敲完之后文件由谁改、改成什么样，全在 CLI 手里；
     * 命令跑在一个用户看得见、能答话、能 Ctrl-C 的终端标签里，而不是后台静默执行。
     * README 那句「一个字节都不往用户文件里写」因此继续成立，这条线不能含糊。
     */
    ACTIVATE,

    /**
     * 装语言服务器二进制。走 brew / go / npm / rustup / gem / opam，慢且依赖外部工具链。
     *
     * 目录表里这批命令只在 macOS 上核实过（`brew` / `gem` / `opam` 那几条更是只有
     * macOS 形状），所以它们的执行按钮**只在 macOS 出现**。
     */
    INSTALL,
}

/**
 * 一条修复建议：点一下就能跑的命令，或（没有已知命令时）一个上游文档链接。
 *
 * [kind] 没有默认值是刻意的：默认值意味着新的产出点可以什么都不想就拿到一个 kind，
 * 而拿错的后果是 Windows 用户在界面上看到一个「安装」按钮、点下去执行 `brew install llvm`。
 * 让编译器在每个产出点上问一次，是这里唯一可靠的提醒。
 */
internal data class Remedy(val command: String?, val docsUrl: String?, val kind: RemedyKind)

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

    /**
     * 「缺口」= 用户**真能采取行动**的那两种状态，而不是「一切非 READY」。
     *
     * [LspStatus.AUTO_MANAGED] 与 [LspStatus.NOT_AVAILABLE] 都不是缺口：前者是好消息，
     * 后者用户做什么都改变不了。[LspStatus.UNKNOWN] 也不算——我们并不知道它缺不缺，
     * 没有可执行的建议给用户，把它计进「待补充 N」只会制造焦虑。
     *
     * 这两个派生属性现在只用于计数与图标选择，逐行渲染走完整的 [findings]。
     */
    val gaps: List<LanguageFinding> get() = findings.filter {
        it.status == LspStatus.MISSING_CONFIG || it.status == LspStatus.MISSING_BINARY
    }
}

internal data class LspReport(val cliReports: List<CliReport>)
