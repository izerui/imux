package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.singleQuote
import java.nio.file.Files
import java.nio.file.Path

/** pi-lens 的 MCP 服务端可执行文件名（pi-lens `package.json` 的 bin 之一）。 */
internal const val PI_LENS_MCP_BIN = "pi-lens-mcp"
internal const val NPX_BIN = "npx"

internal fun standardPiLensMcp(userHome: Path): Path = userHome.resolve(".pi/agent/npm/node_modules/.bin/$PI_LENS_MCP_BIN")

private data class McpLaunch(
    val command: String,
    val referencesPiLens: Boolean,
)

/**
 * Codex 是否挂了一个真正可启动的 pi-lens MCP。
 *
 * 不能只搜索配置文本：pi 默认把可执行文件装进自己的私有 `.bin`，裸命令通常不在
 * Codex 的 PATH 中。绝对路径直接检查文件；裸命令和 npx 等启动器使用同一轮 shell
 * 探测结果校验。
 */
internal fun mountsPiLensMcp(
    configToml: String?,
    userHome: Path,
    binaries: Map<String, String?>,
): Boolean =
    mcpLaunches(configToml).any { launch ->
        launch.referencesPiLens && commandAvailable(launch.command, userHome, binaries)
    }

private fun commandAvailable(
    command: String,
    userHome: Path,
    binaries: Map<String, String?>,
): Boolean {
    val expanded =
        runCatching {
            if (command.startsWith("~/")) userHome.resolve(command.removePrefix("~/")) else Path.of(command)
        }.getOrNull() ?: return false
    return if (expanded.isAbsolute) {
        Files.isExecutable(expanded)
    } else {
        binaryAvailability(binaries, command) == BinaryAvailability.PRESENT
    }
}

/** 只解析 Codex 自己会写出的单行 MCP 形状，不冒充通用 TOML 解析器。 */
private fun mcpLaunches(configToml: String?): List<McpLaunch> {
    if (configToml.isNullOrBlank()) return emptyList()
    if (!tomlSectionContains(configToml, "mcp_servers", PI_LENS_MCP_BIN)) return emptyList()

    val launches = mutableListOf<McpLaunch>()
    var inMcpSection = false
    var command: String? = null
    var referencesPiLens = false

    fun flush() {
        command?.let { launches += McpLaunch(it, referencesPiLens) }
        command = null
        referencesPiLens = false
    }

    configToml.lineSequence().forEach { rawLine ->
        val line = stripTomlComment(rawLine).trim()
        if (line.isEmpty()) return@forEach

        if (line.startsWith("[")) {
            flush()
            val header = line.trim('[', ']').trim()
            inMcpSection = header == "mcp_servers" || header.startsWith("mcp_servers.")
            return@forEach
        }

        if (line.startsWith("mcp_servers.") && line.contains('=')) {
            flush()
            val value = line.substringAfter('=')
            val inlineCommand = TOML_COMMAND.find(value)?.groupValues?.get(1)
            if (inlineCommand != null) {
                launches += McpLaunch(inlineCommand, value.contains(PI_LENS_MCP_BIN))
            }
            inMcpSection = false
            return@forEach
        }

        if (!inMcpSection) return@forEach
        TOML_COMMAND
            .matchEntire(line)
            ?.groupValues
            ?.get(1)
            ?.let { command = it }
        if (line.substringAfter('=', "").contains(PI_LENS_MCP_BIN)) referencesPiLens = true
    }
    flush()
    return launches
}

private fun stripTomlComment(line: String): String {
    var quoted = false
    var escaped = false
    line.forEachIndexed { index, char ->
        when {
            escaped -> escaped = false
            char == '\\' && quoted -> escaped = true
            char == '"' -> quoted = !quoted
            char == '#' && !quoted -> return line.substring(0, index)
        }
    }
    return line
}

private val TOML_COMMAND = Regex("""command\s*=\s*"([^"]+)"""")

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
    piLensMcpExecutable: Path,
    piLensMcpAvailable: Boolean = Files.isExecutable(piLensMcpExecutable),
): CliReport {
    if (!cliInstalled) {
        return CliReport(AgentType.CODEX, installed = false, findings = emptyList())
    }
    if (!mounted) {
        val commands =
            buildList {
                if (!piLensMcpAvailable) add("pi install npm:pi-lens")
                add("codex mcp add pi-lens -- ${singleQuote(piLensMcpExecutable.toString())}")
            }
        return CliReport(
            AgentType.CODEX,
            installed = true,
            findings = emptyList(),
            groupRemedy = Remedy(commands, null),
        )
    }
    return CliReport(AgentType.CODEX, installed = true, findings = piFindings)
}
