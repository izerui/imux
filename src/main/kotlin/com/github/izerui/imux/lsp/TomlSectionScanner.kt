package com.github.izerui.imux.lsp

/**
 * 只够用的 TOML 段落扫描。
 *
 * **不是通用 TOML 解析器。** 平台捆绑了 `jackson-dataformat-toml`，但它不在插件的
 * 编译 classpath 上（实测 `Unresolved reference 'toml'`），而为了判断一个布尔值
 * 引一个新依赖不划算——本项目的仓库可达性本来就受限（见 build.gradle.kts 注释）。
 *
 * 只回答一个问题：某个顶层段落（如 `mcp_servers`）下面，有没有哪一行的值里
 * 出现了给定标识。做法是按行扫描、跟踪当前段落名、跳过注释。
 *
 * 已知不支持：多行数组、多行字符串。Codex 写出来的 `config.toml` 是每个 server
 * 一个 `[mcp_servers.<名>]` 段落、键值都在单行上，落不到这些形式里；
 * 真遇到多行数组只会漏判成「未挂载」，UI 上表现为多给一条已经满足的建议，
 * 不会误报成已挂载。
 */
internal fun tomlSectionContains(toml: String?, topLevelSection: String, needle: String): Boolean {
    if (toml.isNullOrBlank()) return false

    var inSection = false
    toml.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach

        if (line.startsWith("[")) {
            // `[mcp_servers.pi-lens]` / `[[mcp_servers.x]]` → 取第一段名字
            val header = line.trim('[', ']').trim()
            inSection = header == topLevelSection || header.startsWith("$topLevelSection.")
            return@forEach
        }

        // 内联表：`mcp_servers.lens = { command = "pi-lens-mcp" }`
        if (line.startsWith("$topLevelSection.") && line.contains(needle)) return true

        if (inSection && line.substringAfter('=', "").contains(needle)) return true
    }
    return false
}
