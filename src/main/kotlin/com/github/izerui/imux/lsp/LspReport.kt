package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.commandChain
import com.github.izerui.imux.terminal.dialectOf

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
 * 一条修复建议：按顺序执行即可补齐当前缺口的命令链，以及无法自动执行时的退路。
 *
 * 平台闸门检查 [commands] 本身是否包含 [LspCatalog.macOnlyCommands]，因为同一条链可能
 * 同时包含平台专属安装命令和跨平台 CLI 配置命令。
 *
 * [commands] 由 [chainFor] 按 shell 方言串接，保证任一步失败即停止，且输出保留在用户
 * 可见的终端中。[blockingTool] 表示链依赖一个当前不在 PATH、且 imux 没有可靠安装方式的
 * 工具；此时命令必须为空，[docsUrl] 指向该工具的官方安装说明。
 */
internal data class Remedy(
    val commands: List<String>,
    val docsUrl: String?,
    val blockingTool: String? = null,
) {
    /**
     * 交给终端的那一整行，`null` 表示没有可跑的东西。
     *
     * 拼接住在这里而不是设置页里，理由与 `runTabName` 相同：壳里留一个
     * `commands.joinToString(" && ")` 的话，把分隔符改成 `;`（哪一步失败都继续往下跑）
     * 或者改成 `" "`（拼成一条谁也不认识的命令）都只是改一个字面量，而设置页那一侧
     * 只能做源码文本断言。搬到这里之后它能被真正调用着测。
     *
     * **收 [shell] 而不是恒用 `&&`。** `&&` 是 PowerShell **7.0** 才有的操作符，
     * 交给 Windows 自带的 5.1 会直接报解析错误、整条链一个命令都不跑，
     * 详见 [com.github.izerui.imux.terminal.commandChain]。方言与 [runCommandLine]
     * 同源（都由 [dialectOf] 从同一个 shell 路径推出），两处不可能漂移；
     * macOS 上它恒为 POSIX，结果与改动前逐字节相同。
     *
     * 收 shell 路径而不是 `ShellDialect`，是为了让设置页那一侧继续不碰方言概念——
     * 它手上本来就有 `resolveShell(…)` 的结果，要交给 [runCommandLine] 的也正是它。
     */
    fun chainFor(shell: String): String? = commandChain(dialectOf(shell), commands).takeIf(String::isNotEmpty)
}

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
    val gaps: List<LanguageFinding> get() =
        findings.filter {
            it.status == LspStatus.MISSING_CONFIG || it.status == LspStatus.MISSING_BINARY
        }
}

internal data class LspReport(
    val cliReports: List<CliReport>,
)
