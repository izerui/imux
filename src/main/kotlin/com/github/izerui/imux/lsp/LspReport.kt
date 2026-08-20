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
