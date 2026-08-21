package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger

/** 同 ClaudeCodeLspProbe：本文件只有顶层函数，没有类可以喂给 `logger<T>()`。 */
private val LOG = Logger.getInstance("com.github.izerui.imux.lsp.PiLspProbe")

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
    // 抛异常才记日志：`packages` 键不存在时 getAsJsonArray 返回 null 而不抛，
    // 那是「没装扩展」的正常状态，不该刷日志。真抛出来的是 JSON 坏了或形状不对
    // ——UI 上同样只显示成「未安装 pi-lens」，除了日志没有别的线索。
    // 只写文件名，不写内容：settings 里有用户主目录路径。
    val packages = runCatching {
        JsonParser.parseString(settingsJson).asJsonObject.getAsJsonArray("packages")
    }.onFailure { LOG.warn("解析 ~/.pi/agent/settings.json 失败，按未安装 pi-lens 处理", it) }
        .getOrNull() ?: return false

    return packages.any { element ->
        val value = runCatching { element.asString }.getOrNull() ?: return@any false
        // 精确匹配或带版本后缀；npm:pi-lens-extras 这类前缀相同的包必须排除
        value == PI_LENS_PACKAGE || value.startsWith("$PI_LENS_PACKAGE@")
    }
}

/**
 * pi 这一组的报告。
 *
 * **目录表里的全部语言都出现**：非 toolchain-gated 的那些标成 [LspStatus.AUTO_MANAGED]
 * ——pi-lens 会按 npm/pip/github 策略按需装 server（pi-lens `docs/lsp-capability-matrix.md`，
 * issue #241）。此前它们被过滤掉，真实用户因此在 pi 组里找不到 TypeScript，
 * 得出了「pi 不支持 TypeScript LSP」的结论，而事实恰恰相反。
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

    val findings = LspCatalog.languages.map { language ->
        // piLensBinary 非空 ⟺ piLensGated（LspCatalogTest 钉住），判空即「非 gated」。
        val binary = language.piLensBinary
        val status = when {
            binary == null -> LspStatus.AUTO_MANAGED
            !binaries.containsKey(binary) -> LspStatus.UNKNOWN
            binaries[binary] == null -> LspStatus.MISSING_BINARY
            else -> LspStatus.READY
        }
        // 只有 MISSING_BINARY 有可执行的下一步。AUTO_MANAGED 尤其不能给建议：
        // pi-lens 会自己装，让用户再手动装一遍是纯粹的噪音。
        val remedy = if (status == LspStatus.MISSING_BINARY && binary != null) {
            LspCatalog.server(binary)?.let { Remedy(it.installCommand, it.docsUrl) }
        } else {
            null
        }
        LanguageFinding(language, status, remedy)
    }
    return CliReport(AgentType.PI, installed = true, findings = findings)
}
