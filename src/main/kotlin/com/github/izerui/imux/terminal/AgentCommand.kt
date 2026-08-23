package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.IMUX_TAB_ENV
import com.github.izerui.imux.session.PiReportEndpoint

/**
 * 启动 CLI 的命令行。
 *
 * **必须经用户的 shell 启动，不能直接 exec `claude`。** 从 Dock/Finder 启动的 IDE
 * 只有系统默认 PATH（`/usr/bin:/bin:/usr/sbin:/sbin`），而 CLI 通常装在
 * `/opt/homebrew/bin`、`~/.local/bin` 这类地方，直接 exec 一律找不到——
 * 表现就是点开会话后标签页一片空白。
 * （从终端 `runIde` 起的沙箱继承了终端的 PATH，所以在沙箱里一切正常，
 * 只有装到正式 IDE 上才暴露。这个差异坑过一次。）
 *
 * POSIX shell 的两个参数缺一不可：
 * - `-l` 读 profile，拿到用户配好的 PATH
 * - `-i` 读 rc，`claude` 这类 **alias** 才存在（实测本机的 claude 就是个 alias，
 *   还带着 `--dangerously-skip-permissions` 参数，丢了它行为就变了）
 *
 * PowerShell 反而要 `-NoProfile`，详见 [shellArgs] 的 KDoc。
 *
 * 不用 `exec` 替换进程：多一层 shell 无妨，而 `-c` 执行完即退出，
 * CLI 一结束 shell 也跟着结束，「进程终止即关标签页」的行为不受影响。
 *
 * [pidFile] 只在 Windows 上有值（由调用点按平台决定），用来让 shell 自报 pid；
 * POSIX 方言下 [pidFileRecordCommand] 恒为 null，macOS 与 Linux 的命令行一个字不加。
 */
internal fun launchCommand(
    shell: String,
    agentType: AgentType,
    resumeId: String?,
    piExtension: java.nio.file.Path? = null,
    initialPrompt: String? = null,
    pidFile: String? = null,
): List<String> {
    val dialect = dialectOf(shell)
    val cli = agentType.cli
    val script =
        when {
            resumeId == null -> {
                cli
            }

            // pi 的 --session-id 对已存在的 id 是打开、不存在则以该 id 创建，
            // 所以新建与续聊是同一条命令——新建时 id 由 imux 预先生成传进来。
            agentType == AgentType.PI -> {
                buildString {
                    append("$cli --session-id ${quote(dialect, resumeId)}")
                    // 脚本缺失时**不加** -e：拼出一个加载不了的扩展会让 pi 启动失败，
                    // 那是整个会话起不来；而少了上报只是标签页不自动跟随。
                    piExtension?.let { append(" -e ${quote(dialect, it.toString())}") }
                }
            }

            agentType == AgentType.CODEX -> {
                "$cli resume ${quote(dialect, resumeId)}"
            }

            else -> {
                "$cli --resume ${quote(dialect, resumeId)}"
            }
        }
    val command =
        initialPrompt
            ?.takeIf { it.isNotBlank() }
            ?.let { "$script ${quote(dialect, it)}" }
            ?: script
    // pid 自报只在 Windows 分支生成：POSIX 方言下 pidFileRecordCommand 恒为 null，
    // macOS 与 Linux 的启动命令因此一个字都不加。
    //
    // **分隔符必须是 `;` 而不是 `&&`。** 写 pid 文件失败（目录没建起来、磁盘满、
    // 杀毒软件挡住）只该让这个标签认不出漂移，绝不能连带让整个会话起不来——
    // `&&` 会在第一条失败时短路掉 CLI，把一个软失败升级成硬失败。
    val prefixed =
        pidFile
            ?.let { pidFileRecordCommand(dialect, it) }
            ?.let { "$it; $command" }
            ?: command
    return listOf(shell) + shellArgs(dialect) + prefixed
}

/**
 * 传给 CLI 进程的终端环境。
 *
 * [IMUX_TAB_ENV] 每种 agent 都要带：它是把一个 CLI 进程认回对应终端的唯一依据。
 * 会话 id 不是终端的固有属性——用户敲 `/clear` 或 `/new`，CLI 换一个会话 id 而进程
 * 不变，插件得靠这个标记发现这件事。用我们自己发的 tabId 而不用 pid，是因为命令是
 * `shell -l -i -c "cli"`，CLI 是 shell 的子进程，而 shell 是否 exec 掉自己
 * 因 shell 与平台而异。详见 [com.github.izerui.imux.session.LiveSessionProbe]。
 *
 * `CLAUDE_CODE_NATIVE_CURSOR` 只给 claude：它默认隐藏真实终端光标并自行绘制一个反色
 * 光标，而 IDEA 262 reworked terminal 的 cursor tracker 在光标隐藏期间不会发布位置
 * 变化，导致 output model 的 cursorOffset 停在 Claude 启动时的 grid home，
 * IME 候选窗也跟着定位到旧输出处。native cursor 模式保留完整 TUI，但会持续维护
 * 可被终端追踪的真实光标。Codex 本来就显式维护真实光标，不需要这个变量。
 *
 * pi 注入 `PI_HARDWARE_CURSOR=1`，让 IDEA 262 能用可见 terminal cursor 定位 macOS
 * 输入法候选窗。pi 同时画出的反色假光标由 imux 自带扩展通过官方 editor factory
 * 去掉（见 `pi-imux-reporter.js`）：保留 CURSOR_MARKER 与硬件光标，只去掉紧随 marker
 * 的反色渲染。这样候选窗能跟随，中文双宽字符间移动时也只有一套可见光标。
 */
internal fun launchEnvironment(
    agentType: AgentType,
    tabId: String,
    piReport: PiReportEndpoint? = null,
): Map<String, String> =
    buildMap {
        put(IMUX_TAB_ENV, tabId)
        when (agentType) {
            AgentType.CLAUDE -> {
                put("CLAUDE_CODE_NATIVE_CURSOR", "1")
            }

            AgentType.PI -> {
                put("PI_HARDWARE_CURSOR", "1")
                // 令牌只发给 pi：它是上报接口唯一的门禁，多发一个进程就多一份泄漏面
                piReport?.let {
                    put("IMUX_REPORT_URL", it.url)
                    put("IMUX_TOKEN", it.token)
                }
            }

            AgentType.CODEX -> {
                // codex 不上报：它在所有平台上都由 imux 自己观测——macOS 与 Linux 读它
                // 持有的会话文件句柄，Windows 读它自己写的运行态 sqlite
                // （见 [com.github.izerui.imux.session.CodexRuntimeIndex]）。
                // 令牌因此不发给它：那是上报接口唯一的门禁，多发一个进程就多一份泄漏面。
                Unit
            }
        }
    }

/**
 * 新建会话时预先定下的会话 id，没有则返回 null。
 *
 * 只有 pi 有：它的 `--session-id` 接受任意 id，不存在就以该 id 创建，于是标签页与会话
 * 从启动那一刻起就是确定的，不必等 CLI 落盘后再靠时间窗去猜是哪一个
 * （见 [com.github.izerui.imux.session.SessionListModel.registerPending]）。
 *
 * claude 与 codex 没有对应能力，id 只能由 CLI 自己生成、插件事后认领。
 */
internal fun preassignedSessionId(
    agentType: AgentType,
    newId: () -> String = {
        java.util.UUID
            .randomUUID()
            .toString()
    },
): String? = if (agentType == AgentType.PI) newId() else null

/**
 * 交给终端或 `ProcessBuilder` 的 shell 可执行文件。
 *
 * **非 Windows 分支与改动前逐字节相同**，且刻意**不看** [configuredShell]：
 * macOS 上取 `SHELL` 是正在工作的行为，换数据源就是改用户机器上正在跑的东西。
 *
 * Windows 上 `SHELL` 通常没有值，从前退回 `/bin/zsh`，四个调用点一律
 * `Cannot run program /bin/zsh`。改取 [configuredShell]——用户在 Terminal 设置里
 * 配的那个 shell，也就是他自己终端里跑的东西。
 *
 * [configuredShell] 可能是一条完整命令行（`"C:\...\bash.exe" --login -i`），
 * 因为 JetBrains Terminal 设置允许用户在同一个字段里填路径加参数。
 * [shellExecutableOf] 负责取出第一个 token（可执行文件路径），
 * 用户填的参数（`--login -i` 之类）被丢弃——我们不是开交互 shell，
 * 是跑一条命令，参数由 [shellArgs] 提供。
 *
 * **白名单之外一律换成 `powershell.exe`。** 只放行两类认得出的 shell：
 * PowerShell（`pwsh`、`powershell`），以及名字在 [POSIX_SHELL_NAMES] 里的
 * POSIX shell（`bash`、`zsh`、`fish` 等）。不在白名单里的——包括 `cmd.exe`、
 * `wsl.exe`、`nu.exe`、`elvish.exe`、Cmder——都会被换成 PowerShell。
 *
 * cmd 被换掉是因为它的引号与转义规则（`^` 转义、`%` 二次展开）写错会把用户的
 * 初始 prompt 拼成一条别的命令。WSL 也在被换之列：它的命令行形状是
 * `wsl.exe -d Ubuntu`，参数语义与 `-l -i -c` 完全不同，草率放行会更糟。
 * PowerShell 在每台受支持的 Windows 上都在。
 *
 * 三个参数都不给默认值：调用点必须显式表态，避免将来新增调用点时静默漏掉平台判断。
 */
internal fun resolveShell(
    shellEnv: String?,
    isWindows: Boolean,
    configuredShell: String?,
): String {
    if (!isWindows) return shellEnv?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
    val executable = shellExecutableOf(configuredShell) ?: return WINDOWS_FALLBACK_SHELL
    // dialectOf 认不出的名字会落到 POSIX；Windows 上那意味着 cmd 或某个我们没见过的
    // shell，两者都不该拿 PowerShell 的引号规则去拼，但也不该拿 POSIX 的 -l -i 去跑。
    // 只放行明确认得出的两类：PowerShell，以及名字在 POSIX_SHELL_NAMES 里的 POSIX shell。
    return if (looksLikePosixShell(executable) || dialectOf(executable) == ShellDialect.POWERSHELL) {
        executable
    } else {
        WINDOWS_FALLBACK_SHELL
    }
}

/**
 * 从 IDE Terminal 设置字段里取出可执行文件路径。
 *
 * JetBrains 允许用户在 Shell Path 字段里填完整命令行（路径 + 参数），
 * 内部用 `ParametersListUtil.parse` 解析。我们不调平台的解析器——它内部做
 * 文件存在性检查（IO），会让纯函数在别人的机器上测不出确定结果。
 *
 * 规则：
 * - null / 全空白 → null
 * - 以双引号开头 → 取到下一个双引号为止的内容（处理带空格的路径）
 * - 否则 → 取第一个空白之前的部分
 */
internal fun shellExecutableOf(configuredShell: String?): String? {
    val trimmed = configuredShell?.trim() ?: return null
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith('"')) {
        val closing = trimmed.indexOf('"', 1)
        // 没有配对的闭合引号 → 去掉开头引号、整串当路径
        return if (closing <= 1) trimmed.removePrefix("\"").ifBlank { null }
        else trimmed.substring(1, closing).ifBlank { null }
    }
    val spaceIdx = trimmed.indexOfFirst { it.isWhitespace() }
    return if (spaceIdx < 0) trimmed else trimmed.substring(0, spaceIdx).ifBlank { null }
}

/** 白名单里的 POSIX shell（Git Bash、MSYS2 等）。 */
private fun looksLikePosixShell(shellPath: String): Boolean {
    val name =
        shellPath
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .lowercase()
            .removeSuffix(".exe")
    return name in POSIX_SHELL_NAMES
}

private val POSIX_SHELL_NAMES = setOf("sh", "bash", "zsh", "fish", "dash", "ksh")
private const val WINDOWS_FALLBACK_SHELL = "powershell.exe"

/**
 * 包成 POSIX 单引号字符串。
 *
 * 两个调用点，性质不同：
 *
 * - `lsp/CodexLspProbe.kt` 用它拼的是**给用户看的命令文本**
 *   （`codex mcp add pi-lens -- '<路径>'`），与终端方言无关。
 * - `lsp/BinaryProbe.kt` 的 [buildProbeScript] 用它拼的是**真正交给
 *   `ProcessBuilder` 执行**的探测脚本。`ShellBinaryProbe` 经 [probeScript] 分派后，
 *   POSIX 方言仍然调用 [buildProbeScript] &#8594; 本函数；Windows 上 Git Bash
 *   也走此路径。
 *
 * 已知取舍：Windows 上 `CodexLspProbe` 那条建议会由 PowerShell 执行，
 * 而两种方言只在值里含单引号时才产生不同结果——含单引号的可执行文件路径极其罕见，
 * 且失败形态是命令报错而非执行了别的东西。
 * 不为此把方言穿透进探针层（那一层刻意不碰任何平台概念）。
 */
internal fun singleQuote(value: String): String = quote(ShellDialect.POSIX, value)
