package com.github.izerui.imux.session

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Path

/**
 * 向操作系统问两件事：某个进程的环境变量、它打开着哪些文件。
 *
 * 都得靠外部命令：JDK 的 [ProcessHandle] 只给命令行与启动时刻，不给别的进程的
 * 环境变量，更不给打开的文件句柄；macOS 也没有 `/proc` 可读。
 *
 * 解析与执行分开写，是为了让解析可测——命令输出的形状是这里最容易出错的地方。
 */
private val LOG = logger<ProcessProbesLocation>()

/**
 * 日志归属锚点。
 *
 * 从前这里写的是 `logger<LiveSessionProbe>()`——本文件的日志会记到**另一个类**名下，
 * 排障时按类名过滤 `idea.log` 直接漏掉。隔壁 `ProcLinuxProbe.kt` 与
 * `WindowsTabPidFile.kt` 各有一个 `*Location` object 专门解决这件事，这里跟它们统一。
 */
private object ProcessProbesLocation

/**
 * 读进程的环境变量，取出 [IMUX_TAB_ENV]。
 *
 * `ps eww` 把 env 和命令行拼在同一行、以空格分隔，因此**不能**按等号切分整行：
 * 别的变量的值里带空格是常事。tabId 是我们自己发的 `imux-<uuid>`，不含空格，
 * 所以直接锚定这一个变量名去取，既准又不受其它变量干扰。
 *
 * 变量名两侧的边界必须卡死，否则 `IMUX_TABS=` 或 `MY_IMUX_TAB=` 都会被误认。
 *
 * 取到的值再过一遍 [isTabId]——三条读取通道共用同一条判据，见那个函数的 KDoc。
 */
internal fun tabIdFromPsOutput(output: String): String? =
    TAB_ENV_PATTERN.find(output)?.groupValues?.get(1)?.takeIf(::isTabId)

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

/**
 * 读一个进程的 [IMUX_TAB_ENV]；读不到（进程已退出、权限不足）返回 null。
 *
 * 三条路，按平台分派：
 * - Windows：**读不到别的进程的环境变量**（环境块在目标进程的 PEB 里，要
 *   `ReadProcessMemory` + 调试权限），改从 shell 自报的 pid 文件沿父链上溯认领，
 *   见 [tabIdByParentChain]
 * - Linux：读 `/proc`（不起子进程、无需 `ps`）
 * - 其余（macOS）：`ps eww`
 *
 * **Windows 分支必须排在最前**：Windows 上 [SystemInfo.isLinux] 为假会落到 `ps`，
 * 而那条路在 Windows 上一个标签都认不出来。
 *
 * [pidDir] 传的是取目录的函数而不是目录本身：默认值在调用点求值，写成 `Path` 会让
 * 每一次非 Windows 的调用都白跑一趟 [imuxTabPidDir]。
 *
 * [runCommand] 与 [procRoot] / [pidDir] 同一个理由被注入：分派选错分支是这一层最难
 * 发现的错，而「走了 `ps` 但本机没有这个 pid」与「压根没走 `ps`」在真实环境里给出
 * **完全相同**的结果（都是 null），不注入就没有任何断言分得开这两者。
 */
internal fun readTabId(
    pid: Long,
    isLinux: Boolean = SystemInfo.isLinux,
    isWindows: Boolean = SystemInfo.isWindows,
    procRoot: Path = PROC_ROOT,
    pidDir: () -> Path = ::imuxTabPidDir,
    runCommand: (List<String>) -> String? = ::runCommandForOutput,
): String? =
    when {
        isWindows -> {
            val shells = tabPidFilesIn(pidDir())
            tabIdByParentChain(
                pid,
                parentOf = ::parentPidOf,
                tabIdOfShellPid = shells::get,
                // 整条 Windows 通道的失败都是静默的：链断了、链太深、pid 文件压根没写出来，
                // 在用户那里的症状都是「漂移探测不工作」。留一行 debug 把三者分开。
                // 只记数量、层数与 pid 这类标识，**不记文件内容**，与本项目既有日志约定一致。
                onUnclaimed = { depth ->
                    LOG.debug("pid $pid 未认领：本轮有 ${shells.size} 个 pid 文件，父链走了 $depth 层")
                },
            )
        }

        isLinux -> readTabIdFromProc(pid, procRoot)

        else -> tabIdFromPsOutput(runCommand(listOf("ps", "eww", "-p", pid.toString())) ?: return null)
    }

/**
 * 读一个进程正持有的 rollout 文件。
 *
 * 三条路，按平台分派，与 [readTabId] 同一个形状：
 * - Windows：读不到别的进程打开的文件句柄（要 Sysinternals 的 `handle.exe`，不自带
 *   且要管理员权限），但 codex 把 pid → thread 写进了自己的运行态 sqlite，因此
 *   改读它，见 [CodexRuntimeIndex]。
 * - Linux：读 `/proc/&lt;pid&gt;/fd/`（不起子进程、也不依赖 `lsof`）
 * - 其余（macOS）：`lsof -p`
 *
 * **Windows 分支必须排在最前，而且不能省。** 少了它，Windows 会落到 `lsof` 那一支，
 * [GeneralCommandLine] 起不来，每一轮探测的每一个 codex pid 都会往 `idea.log` 写一条
 * 带完整堆栈的 `LOG.warn`——功能不坏，但 Windows 用户排障时唯一的线索来源被刷屏淹掉，
 * 包括 [readTabId] 那条专门留下的三态 debug 日志。
 *
 * **查询代价**：`readHeldRollouts` 由 `SessionMonitor.probeSessionDrift` 调用，
 * 跑在 `Dispatchers.IO`（池线程）上——不会阻塞 EDT。Windows 分支的
 * [CodexRuntimeIndex.rolloutPathOf] 在大库上约 0.3 秒墙钟，只要不在 EDT 上就没问题。
 *
 * [runCommand] 与 [rolloutOfPid] 注入的理由见 [readTabId]：分派选错分支是这一层最难
 * 发现的错，症状与「没有漂移」不可区分。
 */
internal fun readHeldRollouts(
    pid: Long,
    isLinux: Boolean = SystemInfo.isLinux,
    isWindows: Boolean = SystemInfo.isWindows,
    procRoot: Path = PROC_ROOT,
    runCommand: (List<String>) -> String? = ::runCommandForOutput,
    rolloutOfPid: (Long) -> String? = ::codexRolloutOfPid,
): List<String> =
    when {
        isWindows -> listOfNotNull(rolloutOfPid(pid))

        isLinux -> readHeldRolloutsFromProc(pid, procRoot)

        else -> rolloutPathsFromLsof(runCommand(listOf("lsof", "-p", pid.toString())) ?: return emptyList())
    }

/**
 * Windows 上的生产入口：读 codex 自己的运行态 sqlite。
 *
 * 参数化（而不是在 [readHeldRollouts] 里直接 new）只为让分派本身可测——
 * 「分派选错分支」是这一层最难发现的错，症状与「没有漂移」不可区分。
 */
private fun codexRolloutOfPid(pid: Long): String? =
    CodexRuntimeIndex(Path.of(System.getProperty("user.home"), ".codex")).rolloutPathOf(pid)

/** 生产入口。参数化只为让分派本身可测——分派选错分支是这一层最难发现的错。 */
internal val PROC_ROOT: Path = Path.of("/proc")

/**
 * 这条可执行文件路径是不是指定的 CLI。
 *
 * 按**可执行文件名**匹配而不是整条命令行包含——后者会把 `tail -f codex.log`、
 * 乃至本插件自己的 `zsh -c "codex resume ..."` 外壳都算进来。
 *
 * 反斜杠切分在两个平台都做——POSIX 路径里不会有 `\`，多切一次是恒等的。
 * 大小写与 `.exe` 后缀**只在 Windows 上放宽**：Linux 大小写敏感，`/usr/bin/CODEX`
 * 是**另一个**可执行文件，认成 codex 会把终端迁到别人的会话上——比不迁移更糟。
 *
 * [isWindows] 由调用点注入（`SystemInfo.isWindows`），纯函数里不读平台类。
 */
internal fun executableMatches(
    command: String,
    cli: String,
    isWindows: Boolean,
): Boolean {
    val name = command.substringAfterLast('/').substringAfterLast('\\')
    return if (isWindows) {
        name.lowercase().removeSuffix(".exe") == cli.lowercase()
    } else {
        name == cli
    }
}

/**
 * 活着的 codex 进程。
 *
 * codex 没有运行态文件可查（claude 那边有 `~/.claude/sessions/`），只能扫进程表。
 * 按可执行文件名匹配而不是整条命令行包含 "codex"——后者会把
 * `tail -f codex.log`、乃至本插件自己的 `zsh -c "codex resume ..."` 外壳都算进来。
 */
internal fun codexPids(): List<Long> = runCatching {
    val isWindows = SystemInfo.isWindows
    ProcessHandle.allProcesses()
        .filter { handle -> handle.info().command().map { executableMatches(it, "codex", isWindows) }.orElse(false) }
        .map { it.pid() }
        .toList()
}.getOrElse {
    LOG.warn("扫描 codex 进程失败", it)
    emptyList()
}

/**
 * 跑一条命令并取标准输出。生产默认值，测试从参数注入一个假的。
 *
 * 超时必须设：`lsof` 在挂载点无响应（网络盘、睡眠中的外置盘）时会长时间卡住，
 * 而调用方在轮询链路上。宁可这一轮探测不出来。
 */
private fun runCommandForOutput(command: List<String>): String? = runCatching {
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
