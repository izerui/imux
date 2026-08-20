package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonParser

/** `pi install` 写进 settings 的包标识；可带 `@版本` 后缀。 */
private const val PI_LENS_PACKAGE = "npm:pi-lens"

/**
 * pi 是否装了 pi-lens。
 *
 * pi core 自己没有 LSP：全量 dist 中 `lspServers`、`languageServer`、
 * `textDocument/` 零命中，内建工具只有 bash/edit/write/read/find/grep/ls。
 * LSP 来自第三方扩展 pi-lens，登记在 `~/.pi/agent/settings.json` 的 `packages` 里。
 *
 * 项目级 `.pi/settings.json` 不读，理由与 Claude Code 一致（见 spec）。
 */
internal fun hasPiLens(settingsJson: String?): Boolean {
    if (settingsJson.isNullOrBlank()) return false
    val packages = runCatching {
        JsonParser.parseString(settingsJson).asJsonObject.getAsJsonArray("packages")
    }.getOrNull() ?: return false

    return packages.any { element ->
        val value = runCatching { element.asString }.getOrNull() ?: return@any false
        // 精确匹配或带版本后缀；npm:pi-lens-extras 这类前缀相同的包必须排除
        value == PI_LENS_PACKAGE || value.startsWith("$PI_LENS_PACKAGE@")
    }
}

/**
 * pi 这一组的报告。
 *
 * 只列 toolchain-gated 语言：其余语言 pi-lens 会按 npm/pip/github 策略自动装 server，
 * 没有可体检的缺口（pi-lens `docs/lsp-capability-matrix.md`，issue #241）。
 */
internal fun piReport(
    piLensInstalled: Boolean,
    binaries: Map<String, String?>,
    cliInstalled: Boolean,
): CliReport {
    if (!cliInstalled) {
        return CliReport(AgentType.PI, installed = false, findings = emptyList())
    }
    if (!piLensInstalled) {
        return CliReport(
            AgentType.PI,
            installed = true,
            findings = emptyList(),
            groupRemedy = Remedy("pi install $PI_LENS_PACKAGE", "https://github.com/apmantza/pi-lens"),
        )
    }

    val findings = LspCatalog.languages
        .filter(LspLanguage::piLensGated)
        .map { language ->
            val binary = language.piLensBinary!!
            val status = when {
                !binaries.containsKey(binary) -> LspStatus.UNKNOWN
                binaries[binary] == null -> LspStatus.MISSING_BINARY
                else -> LspStatus.READY
            }
            val remedy = if (status == LspStatus.MISSING_BINARY) {
                LspCatalog.server(binary)?.let { Remedy(it.installCommand, it.docsUrl) }
            } else {
                null
            }
            LanguageFinding(language, status, remedy)
        }
    return CliReport(AgentType.PI, installed = true, findings = findings)
}
