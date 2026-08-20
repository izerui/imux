package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Claude Code 的 LSP 配置探测。
 *
 * 两个全局来源：
 * 1. `~/.claude/settings.json` 顶层的 `lspServers`
 * 2. `enabledPlugins` 里启用的插件 × marketplace 清单中该插件的 `lspServers`
 *
 * **项目级的 `.claude/settings.json` 不读**：体检页是应用级设置，拿不到 Project。
 * 代价是只在某项目里配了 LSP 的用户会被误报未配置，UI 上以脚注说明。
 * 见 spec「Claude Code——原生 LSP」一节。
 *
 * 用 Gson 而不是 [com.github.izerui.imux.session.JsonLineScanner]：后者是为
 * 数 MB 的会话文件做的线性扫描，只取顶层字符串；这里要读的是嵌套结构
 * （`lspServers.<名>.command`、`plugins[].lspServers`），且文件只有几 KB。
 */
internal fun parseConfiguredCommands(settingsJson: String?, marketplaceJson: String?): Set<String> {
    val settings = parseObject(settingsJson) ?: return emptySet()
    return buildSet {
        addAll(directCommands(settings))
        addAll(pluginCommands(settings, marketplaceJson))
    }
}

/** `lspServers` 直接定义的 command。 */
private fun directCommands(settings: JsonObject): Set<String> =
    settings.asObject("lspServers")
        ?.entrySet()
        .orEmpty()
        .mapNotNull { (_, value) -> value.asObjectOrNull()?.asString("command") }
        .toSet()

/** 启用的插件在 marketplace 清单里声明的 command。 */
private fun pluginCommands(settings: JsonObject, marketplaceJson: String?): Set<String> {
    val enabled = settings.asObject("enabledPlugins")
        ?.entrySet()
        .orEmpty()
        .filter { (_, value) -> runCatching { value.asBoolean }.getOrDefault(false) }
        // 键形如 `gopls-lsp@claude-plugins-official`，marketplace 里的 name 不带来源后缀
        .map { (key, _) -> key.substringBefore('@') }
        .toSet()
    if (enabled.isEmpty()) return emptySet()

    val plugins = parseObject(marketplaceJson)?.get("plugins")?.asArrayOrNull() ?: return emptySet()
    return plugins.mapNotNull { it.asObjectOrNull() }
        .filter { it.asString("name") in enabled }
        .flatMap { plugin ->
            plugin.asObject("lspServers")
                ?.entrySet()
                .orEmpty()
                .mapNotNull { (_, value) -> value.asObjectOrNull()?.asString("command") }
        }
        .toSet()
}

/**
 * 把配置与二进制探测结果合成 Claude Code 这一组的报告。
 *
 * 只列官方有对应插件的语言——Haskell 之类官方没插件的，在 Claude Code 上
 * 不存在「装个插件就能用」的路径，列进来只能显示一条无法执行的建议。
 */
internal fun claudeReport(
    configuredCommands: Set<String>,
    binaries: Map<String, String?>,
    cliInstalled: Boolean,
): CliReport {
    if (!cliInstalled) {
        return CliReport(AgentType.CLAUDE, installed = false, findings = emptyList())
    }

    val findings = LspCatalog.languages
        .filter { it.claudePlugin != null && it.claudeBinary != null }
        .map { language ->
            val binary = language.claudeBinary!!
            val configured = binary in configuredCommands
            val located = binaries[binary]
            val status = when {
                !configured -> LspStatus.MISSING_CONFIG
                !binaries.containsKey(binary) -> LspStatus.UNKNOWN
                located == null -> LspStatus.MISSING_BINARY
                else -> LspStatus.READY
            }
            LanguageFinding(language, status, remedyFor(language, status))
        }
    return CliReport(AgentType.CLAUDE, installed = true, findings = findings)
}

private fun remedyFor(language: LspLanguage, status: LspStatus): Remedy? = when (status) {
    LspStatus.MISSING_CONFIG ->
        Remedy("claude plugin install ${language.claudePlugin}@claude-plugins-official", null)

    LspStatus.MISSING_BINARY -> LspCatalog.server(language.claudeBinary.orEmpty())
        ?.let { Remedy(it.installCommand, it.docsUrl) }

    LspStatus.READY, LspStatus.UNKNOWN -> null
}

// —— Gson 便利封装：任何形状不符都返回 null，绝不抛给调用方 ——

private fun parseObject(text: String?): JsonObject? {
    if (text.isNullOrBlank()) return null
    return runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
}

private fun JsonElement.asObjectOrNull(): JsonObject? =
    runCatching { asJsonObject }.getOrNull()

private fun JsonElement.asArrayOrNull(): List<JsonElement>? =
    runCatching { asJsonArray.toList() }.getOrNull()

private fun JsonObject.asObject(key: String): JsonObject? =
    get(key)?.asObjectOrNull()

private fun JsonObject.asString(key: String): String? =
    runCatching { get(key)?.asString }.getOrNull()?.takeIf(String::isNotBlank)
