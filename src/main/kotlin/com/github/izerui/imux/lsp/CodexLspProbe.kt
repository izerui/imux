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
            // codex 自己的子命令，跨平台——所有平台都可以点一下就跑
            groupRemedy = Remedy("codex mcp add pi-lens -- $PI_LENS_MCP_BIN", null, RemedyKind.ACTIVATE),
        )
    }
    return CliReport(AgentType.CODEX, installed = true, findings = piFindings)
}
