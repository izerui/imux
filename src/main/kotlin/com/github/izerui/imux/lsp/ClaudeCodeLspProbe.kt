package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger

/**
 * 本文件只有顶层函数，没有类可以喂给 `logger<T>()`，因此显式给类别名。
 * 类别沿用文件的全限定名，`idea.log` 里的呈现与 `logger<T>()` 一致。
 */
private val LOG = Logger.getInstance("com.github.izerui.imux.lsp.ClaudeCodeLspProbe")

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
internal fun parseConfiguredCommands(
    settingsJson: String?,
    marketplaceJson: String?,
): Set<String> {
    val settings = parseObject(settingsJson, "~/.claude/settings.json") ?: return emptySet()
    return buildSet {
        addAll(directCommands(settings))
        addAll(pluginCommands(settings, marketplaceJson))
    }
}

/** `lspServers` 直接定义的 command。 */
private fun directCommands(settings: JsonObject): Set<String> =
    settings
        .asObject("lspServers")
        ?.entrySet()
        .orEmpty()
        .mapNotNull { (_, value) -> value.asObjectOrNull()?.asString("command") }
        .toSet()

/** 启用的插件在 marketplace 清单里声明的 command。 */
private fun pluginCommands(
    settings: JsonObject,
    marketplaceJson: String?,
): Set<String> {
    val enabled =
        settings
            .asObject("enabledPlugins")
            ?.entrySet()
            .orEmpty()
            .filter { (_, value) -> runCatching { value.asBoolean }.getOrDefault(false) }
            // 键形如 `gopls-lsp@claude-plugins-official`，marketplace 里的 name 不带来源后缀
            .map { (key, _) -> key.substringBefore('@') }
            .toSet()
    if (enabled.isEmpty()) return emptySet()

    val plugins =
        parseObject(marketplaceJson, "claude-plugins-official/marketplace.json")
            ?.get("plugins")
            ?.asArrayOrNull()
            ?: return emptySet()
    return plugins
        .mapNotNull { it.asObjectOrNull() }
        .filter { it.asString("name") in enabled }
        .flatMap { plugin ->
            plugin
                .asObject("lspServers")
                ?.entrySet()
                .orEmpty()
                .mapNotNull { (_, value) -> value.asObjectOrNull()?.asString("command") }
        }.toSet()
}

/**
 * 把配置与二进制探测结果合成 Claude Code 这一组的报告。
 *
 * **目录表里的全部语言都出现**，官方没有对应插件的（Haskell 等）标成
 * [LspStatus.NOT_AVAILABLE]。此前它们被过滤掉，用户看到的是一份没有说明的短名单，
 * 于是把「没列出来」读成了「不支持」——体检工具最不该做的就是让人自己去猜省略了什么。
 */
internal fun claudeReport(
    configuredCommands: Set<String>,
    binaries: Map<String, String?>,
    cliInstalled: Boolean,
): CliReport {
    if (!cliInstalled) {
        return CliReport(AgentType.CLAUDE, installed = false, findings = emptyList())
    }

    val toolAvailability: (String) -> BinaryAvailability = { binaryAvailability(binaries, it) }

    val findings =
        LspCatalog.languages.map { language ->
            // claudePlugin 与 claudeBinary 必定同时存在或同时缺失（LspCatalogTest 钉住），
            // 所以判 binary 为空就等价于「官方没有这门语言的插件」。
            val binary = language.claudeBinary
            val status =
                when {
                    binary == null -> LspStatus.NOT_AVAILABLE
                    binary !in configuredCommands -> LspStatus.MISSING_CONFIG
                    !binaries.containsKey(binary) -> LspStatus.UNKNOWN
                    binaries[binary] == null -> LspStatus.MISSING_BINARY
                    else -> LspStatus.READY
                }
            // 建议由 remedyFor 一处算出：它要串的是「缺哪层加哪层」的整条链，而不是
            // 「这个状态对应哪一条命令」——MISSING_CONFIG 这个状态压根没说过二进制在不在
            // （上面的 when 里配置层排在二进制层前面，一命中就返回了）。
            LanguageFinding(language, status, remedyFor(language, AgentType.CLAUDE, status, toolAvailability))
        }
    return CliReport(AgentType.CLAUDE, installed = true, findings = findings)
}

// —— Gson 便利封装：任何形状不符都返回 null，绝不抛给调用方 ——

/**
 * 只有这一层记日志，下面几个形状封装刻意不记。
 *
 * 这里失败意味着「文件在、但不是合法 JSON」——用户改坏了配置，而 UI 只会显示成
 * 「未配置」，除了日志没有别的线索。下面的 [asObject] / [asString] 之流失败则多半
 * 只是键不存在或类型不同，属于正常形状差异，逐条记会把 idea.log 刷满。
 *
 * [source] 只写文件名，**不写文件内容**：这些 settings 里有用户主目录路径乃至令牌。
 */
private fun parseObject(
    text: String?,
    source: String,
): JsonObject? {
    if (text.isNullOrBlank()) return null
    return runCatching { JsonParser.parseString(text).asJsonObject }
        .onFailure { LOG.warn("解析 $source 失败，按未配置处理", it) }
        .getOrNull()
}

private fun JsonElement.asObjectOrNull(): JsonObject? = runCatching { asJsonObject }.getOrNull()

private fun JsonElement.asArrayOrNull(): List<JsonElement>? = runCatching { asJsonArray.toList() }.getOrNull()

private fun JsonObject.asObject(key: String): JsonObject? = get(key)?.asObjectOrNull()

private fun JsonObject.asString(key: String): String? = runCatching { get(key)?.asString }.getOrNull()?.takeIf(String::isNotBlank)
