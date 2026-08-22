package com.github.izerui.imux.session

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.logger

/**
 * 向操作系统问两件事：某个进程的环境变量、它打开着哪些文件。
 *
 * 都得靠外部命令：JDK 的 [ProcessHandle] 只给命令行与启动时刻，不给别的进程的
 * 环境变量，更不给打开的文件句柄；macOS 也没有 `/proc` 可读。
 *
 * 解析与执行分开写，是为了让解析可测——命令输出的形状是这里最容易出错的地方。
 */
private val LOG = logger<LiveSessionProbe>()

/**
 * 读进程的环境变量，取出 [IMUX_TAB_ENV]。
 *
 * `ps eww` 把 env 和命令行拼在同一行、以空格分隔，因此**不能**按等号切分整行：
 * 别的变量的值里带空格是常事。tabId 是我们自己发的 `imux-<uuid>`，不含空格，
 * 所以直接锚定这一个变量名去取，既准又不受其它变量干扰。
 *
 * 变量名两侧的边界必须卡死，否则 `IMUX_TABS=` 或 `MY_IMUX_TAB=` 都会被误认。
 */
internal fun tabIdFromPsOutput(output: String): String? =
    TAB_ENV_PATTERN.find(output)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

/**
 * 从 `lsof -p` 的输出里挑出 codex 正在写的 rollout 文件。
 *
 * NAME 是最后一列，路径里可能有空格，所以按前 8 列切完取**剩下全部**，
 * 不能简单地拿最后一个空格分段。
 *
 * codex 同时开着一大堆无关文件（含 `history.jsonl`），只认 rollout 那个形状；
 * 具体的形状校验交给 [threadIdOfRollout]。
 */
internal fun rolloutPathsFromLsof(output: String): List<String> =
    output.lineSequence()
        // 必须先 trim：行首多一个空格就会切出一个空段，后面所有列整体右移一位
        .mapNotNull { line ->
            line.trim().split(WHITESPACE, limit = LSOF_NAME_COLUMN).getOrNull(LSOF_NAME_COLUMN - 1)
        }
        .filter { threadIdOfRollout(it) != null }
        .toList()

/** 读一个进程的 [IMUX_TAB_ENV]；读不到（进程已退出、权限不足）返回 null。 */
internal fun readTabId(pid: Long): String? =
    tabIdFromPsOutput(runCommand(listOf("ps", "eww", "-p", pid.toString())) ?: return null)

/** 读一个进程正持有的 rollout 文件。 */
internal fun readHeldRollouts(pid: Long): List<String> =
    rolloutPathsFromLsof(runCommand(listOf("lsof", "-p", pid.toString())) ?: return emptyList())

/**
 * 这条可执行文件路径是不是指定的 CLI。
 *
 * 按**可执行文件名**匹配而不是整条命令行包含——后者会把 `tail -f codex.log`、
 * 乃至本插件自己的 `zsh -c "codex resume ..."` 外壳都算进来。
 *
 * 两种分隔符都要切、`.exe` 后缀要去、比较不分大小写：Windows 上路径是
 * `C:\Users\me\AppData\npm\codex.exe`，只切 `/` 且区分大小写的话**一个进程都
 * 认不出来**，而失败是静默的——漂移探测什么也不报，看起来跟「没有漂移」一样。
 */
internal fun executableMatches(
    command: String,
    cli: String,
): Boolean =
    command
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .lowercase()
        .removeSuffix(".exe") == cli.lowercase()

/**
 * 活着的 codex 进程。
 *
 * codex 没有运行态文件可查（claude 那边有 `~/.claude/sessions/`），只能扫进程表。
 * 按可执行文件名匹配而不是整条命令行包含 "codex"——后者会把
 * `tail -f codex.log`、乃至本插件自己的 `zsh -c "codex resume ..."` 外壳都算进来。
 */
internal fun codexPids(): List<Long> = runCatching {
    ProcessHandle.allProcesses()
        .filter { handle -> handle.info().command().map { executableMatches(it, "codex") }.orElse(false) }
        .map { it.pid() }
        .toList()
}.getOrElse {
    LOG.warn("扫描 codex 进程失败", it)
    emptyList()
}

/**
 * 跑一条命令并取标准输出。
 *
 * 超时必须设：`lsof` 在挂载点无响应（网络盘、睡眠中的外置盘）时会长时间卡住，
 * 而调用方在轮询链路上。宁可这一轮探测不出来。
 */
private fun runCommand(command: List<String>): String? = runCatching {
    val output = ExecUtil.execAndGetOutput(GeneralCommandLine(command), COMMAND_TIMEOUT_MS)
    if (output.isTimeout) {
        LOG.warn("探测命令超时：${command.joinToString(" ")}")
        null
    } else {
        output.stdout
    }
}.getOrElse {
    LOG.warn("探测命令失败：${command.joinToString(" ")}", it)
    null
}

private val TAB_ENV_PATTERN = Regex("""(?<![\w])$IMUX_TAB_ENV=(\S+)""")
private val WHITESPACE = Regex("""\s+""")

/** lsof 的 NAME 是第 9 列，前 8 列是 COMMAND PID USER FD TYPE DEVICE SIZE/OFF NODE。 */
private const val LSOF_NAME_COLUMN = 9
private const val COMMAND_TIMEOUT_MS = 3_000
