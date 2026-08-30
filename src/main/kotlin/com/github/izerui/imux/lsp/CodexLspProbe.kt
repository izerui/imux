package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.singleQuote
import java.nio.file.Files
import java.nio.file.Path

/** pi-lens 的 MCP 服务端可执行文件名（pi-lens `package.json` 的 bin 之一）。 */
internal const val PI_LENS_MCP_BIN = "pi-lens-mcp"
internal const val NPX_BIN = "npx"

/** 隔离安装那条链的第一步；缺了整条链跑不动，因此单独设闸。 */
internal const val NPM_BIN = "npm"

private data class McpLaunch(
    val command: String,
    val args: List<String>,
    val referencesPiLens: Boolean,
)

/**
 * Codex 是否挂了一个真正可启动的 pi-lens MCP。
 *
 * 不能只搜索配置文本：pi 默认把可执行文件装进自己的私有 `.bin`，裸命令通常不在
 * Codex 的 PATH 中。绝对路径直接检查文件；裸命令和 npx 等启动器使用同一轮 shell
 * 探测结果校验。
 *
 * **文件位可执行不足以判定「可启动」。** pi-lens 把 `typebox` 与
 * `@earendil-works/pi-tui` 列为 optional peer，交由 pi 宿主在自己的 runtime 里解析
 * 裸标识符（见 pi-lens `scripts/lib/host-provided-deps.mjs`）。Codex 是把
 * `pi-lens-mcp` 当独立子进程 spawn 的，没有宿主供给，Node 从 pi-lens 自身位置逐级
 * 上溯找不到这两个包，进程以 `ERR_MODULE_NOT_FOUND` 立即退出——而文件位自始至终
 * 是可执行的。因此每一种形态都要过 [handshake] 这一关。
 *
 * **交给 [handshake] 的必须是完整启动命令，不能只有 command。** `command = "npx"` +
 * `args = ["-y", "pi-lens-mcp"]` 里真正的标识落在 args 上，只 spawn 那个 npx 等于拿
 * 半条命令去判活——npx 不带参数根本不会应答 `initialize`，一份完全正常的配置会被判成
 * 损坏。反过来，裸命令若因为「解析不出绝对路径」就跳过握手，PATH 里一份坏掉的安装
 * （把 pi 的私有 `.bin` 加进 PATH 是常见做法）会被判成健康。两头都得堵。
 */
internal fun mountsPiLensMcp(
    configToml: String?,
    userHome: Path,
    binaries: Map<String, String?>,
    handshake: (List<String>) -> Boolean = { true },
): Boolean =
    mcpLaunches(configToml).any { launch ->
        launch.referencesPiLens &&
            resolveExecutable(launch.command, userHome, binaries)
                ?.let { executable -> handshake(listOf(executable) + launch.args) } == true
    }

/**
 * 一次 MCP `initialize` 的 stdout 是否表明 server 真的起来了。
 *
 * 判据是「有一行 JSON 同时带 `result` 与 `protocolVersion`」，而不是「进程退出码为 0」
 * 或「stdout 非空」：
 *
 * - 退出码不可用。MCP server 是长驻的，握手成功后并不退出；而 stdin 收到 EOF 后
 *   正常退出的 server 与压根没启动起来的，退出码可以一模一样。
 * - stdout 非空也不可用。server 启动时往 stdout 打的横幅会让「崩溃前吐了一行日志」
 *   看起来像握手成功。pi-lens 自己把 `console.log` 改道到 stderr 正是为了不污染这条
 *   流，但 imux 不能假定每个被挂上来的 server 都这么自律。
 *
 * 带 `error` 的响应同样算失败——server 进程活着，但它不认 `initialize`，对 Codex
 * 而言与起不来没有区别。
 */
internal fun handshakeSucceeded(stdout: String): Boolean =
    stdout.lineSequence().any { line ->
        val trimmed = line.trim()
        trimmed.startsWith("{") && trimmed.contains("\"result\"") && trimmed.contains("\"protocolVersion\"")
    }

/**
 * 把配置里的 command 归一成一个可 spawn 的绝对路径，归不出来就是 `null`。
 *
 * 绝对路径要验文件位——配置里那串是用户或 imux 写进去的，没经过任何探测。裸命令则
 * **不再验**：它的值来自同一轮 `command -v`，那条命令本身只会吐出可执行的路径，再查
 * 一次 [Files.isExecutable] 只是给探测与判定之间那点时间差添一个假阴性。
 */
private fun resolveExecutable(
    command: String,
    userHome: Path,
    binaries: Map<String, String?>,
): String? {
    val expanded =
        runCatching {
            if (command.startsWith("~/")) userHome.resolve(command.removePrefix("~/")) else Path.of(command)
        }.getOrNull() ?: return null
    return if (expanded.isAbsolute) {
        expanded.takeIf(Files::isExecutable)?.toString()
    } else {
        binaries[command]?.takeIf { binaryAvailability(binaries, command) == BinaryAvailability.PRESENT }
    }
}

/** 只解析 Codex 自己会写出的单行 MCP 形状，不冒充通用 TOML 解析器。 */
private fun mcpLaunches(configToml: String?): List<McpLaunch> {
    if (configToml.isNullOrBlank()) return emptyList()
    if (!tomlSectionContains(configToml, "mcp_servers", PI_LENS_MCP_BIN)) return emptyList()

    val launches = mutableListOf<McpLaunch>()
    var inMcpSection = false
    var command: String? = null
    var args = emptyList<String>()
    var referencesPiLens = false

    fun flush() {
        command?.let { launches += McpLaunch(it, args, referencesPiLens) }
        command = null
        args = emptyList()
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
                launches += McpLaunch(inlineCommand, tomlStringArray(value), value.contains(PI_LENS_MCP_BIN))
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
        TOML_ARGS.matchEntire(line)?.let { args = tomlStringArray(line) }
        if (line.substringAfter('=', "").contains(PI_LENS_MCP_BIN)) referencesPiLens = true
    }
    flush()
    return launches
}

/**
 * 取出 `args = ["-y", "pi-lens-mcp"]` 里那几个字符串。
 *
 * 与 [TOML_COMMAND] 同一条原则：只认 Codex 自己会写出的单行形状，不冒充通用 TOML
 * 解析器。跨行数组、单引号字面量串都落不到这里——落不到就是空 args，握手退化成只跑
 * command，与改动前同一种误判，不会更糟。
 */
private fun tomlStringArray(line: String): List<String> =
    TOML_ARGS
        .find(line)
        ?.groupValues
        ?.get(1)
        ?.let { inner -> TOML_QUOTED.findAll(inner).map { it.groupValues[1] }.toList() }
        .orEmpty()

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
private val TOML_ARGS = Regex("""args\s*=\s*\[([^]]*)].*""")
private val TOML_QUOTED = Regex(""""([^"]*)"""")

/**
 * Codex 专用的隔离 pi-lens 安装目录。
 *
 * **刻意不复用 pi 的 `~/.pi/agent/npm`。** 那份安装依赖 pi 宿主替它解析
 * `typebox` 与 `@earendil-works/pi-tui`，Codex 把 server 当独立子进程 spawn 时无人供给，
 * 必崩。反过来也不能往 pi 的目录里补装这两个包：它们会落在 pi-lens 自己的解析链上，
 * 变成 pi 宿主之外的第二份副本，正是 pi-lens `scripts/lib/host-provided-deps.mjs`
 * 记录的那次退化（模块导入 941ms → 180ms 就是靠移除副本拿回来的）。
 * 两边各自持有一份自包含安装，谁也不拖累谁。
 */
internal fun codexPiLensHome(userHome: Path): Path = userHome.resolve(".codex/mcp/pi-lens")

/**
 * 隔离安装里那个真正能被拉起的 `pi-lens-mcp`。
 *
 * **Windows 上必须是 `.cmd`。** npm 在 `.bin` 下为每个 bin 写三个文件：无后缀的
 * `#!/bin/sh` 脚本（只给 Git Bash）、`.ps1`、以及 `.cmd`；三者里只有 `.cmd` 能被
 * CreateProcess 原生执行。POSIX 上则相反，`.bin` 里只有那个无后缀的 symlink。
 * 平台从参数进来而不是就地读 `SystemInfo`，理由与本模块其余闸门一致：一个纯函数
 * 才测得住两条分支。
 */
internal fun codexPiLensMcp(
    userHome: Path,
    isWindows: Boolean,
): Path = codexPiLensHome(userHome).resolve("node_modules/.bin/$PI_LENS_MCP_BIN${if (isWindows) ".cmd" else ""}")

/**
 * Codex 这一组的报告。
 *
 * 挂载后跑的是与 pi 同一个 pi-lens 扩展（只是各自一份自包含安装，见 [codexPiLensHome]），
 * 语言层面的能力矩阵一模一样，因此与 pi 共用 [piLensFindings]，不重复探测。
 *
 * 共用的是**算法**而不是 pi 那份报告的结果：pi 侧没装 pi-lens 时 [piReport] 的 findings
 * 是空列表，直接复用会让一个挂载成功的 Codex 显示成一门语言都没有。
 */
internal fun codexReport(
    mounted: Boolean,
    binaries: Map<String, String?>,
    cliInstalled: Boolean,
    userHome: Path,
    isWindows: Boolean,
): CliReport {
    if (!cliInstalled) {
        return CliReport(AgentType.CODEX, installed = false, findings = emptyList())
    }
    if (!mounted) {
        // 链的第一步就是 npm。它**确认**不在 PATH 时，整条链跑下去只会是
        // `npm: command not found`——给一个必然失败的按钮，比不给按钮更糟。目录表把 npm
        // 登记成 installCommand = null（安装方式牵扯 nvm/asdf 与系统包管理器，猜一条命令
        // 下去坏的是用户的开发环境），blockingTool 就是为这种情形准备的出口。
        //
        // 只挡 MISSING，不挡 UNKNOWN：键不存在意味着那一轮 shell 探测超时了，而不是
        // 「没有 npm」。与 LspDiagnostics.isInstalled 同一把尺子。
        LspCatalog.tool(NPM_BIN)?.takeIf { binaryAvailability(binaries, NPM_BIN) == BinaryAvailability.MISSING }
            ?.let { npm ->
                return CliReport(
                    AgentType.CODEX,
                    installed = true,
                    findings = emptyList(),
                    groupRemedy = Remedy(emptyList(), npm.docsUrl, npm.name),
                )
            }
        // `npm --prefix` 自行创建目录，不需要先 mkdir；两个 host-provided 包必须与
        // pi-lens 一起装，否则装出来的仍是一个 spawn 即崩的 server。
        val commands =
            listOf(
                "npm --prefix ${singleQuote(codexPiLensHome(userHome).toString())} " +
                    "i pi-lens typebox @earendil-works/pi-tui",
                "codex mcp add pi-lens -- ${singleQuote(codexPiLensMcp(userHome, isWindows).toString())}",
            )
        return CliReport(
            AgentType.CODEX,
            installed = true,
            findings = emptyList(),
            groupRemedy = Remedy(commands, null),
        )
    }
    return CliReport(AgentType.CODEX, installed = true, findings = piLensFindings(binaries))
}
