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
