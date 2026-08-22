package com.github.izerui.imux.session

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path

// Windows 上「CLI 进程属于哪个终端标签」的身份通道。
//
// macOS 与 Linux 靠注入的 IMUX_TAB 环境变量认领（`ps eww` / `/proc/<pid>/environ`）。
// Windows 上**读不到别的进程的环境变量**——环境块在目标进程的 PEB 里，要
// ReadProcessMemory 加调试权限，Get-Process 不给，JDK 也不给
// （ProcessHandle.Info.arguments() 在 Windows 上恒为空）。
//
// 替代通道：启动命令本来就由 imux 全权拼接，让 shell 把自己的 pid 写进一个文件
// （见 terminal/ShellDialect.kt 的 pidFileRecordCommand），之后从 CLI 进程沿父链
// 上溯撞到它即可对号入座。
//
// 全部做成纯函数并注入 IO，理由与 lsp/LspRemedyRun.kt 相同：这一层的正确性
// 全在「链走到哪停、什么时候不认领」上，而这些分支在 macOS 开发机上一条也走不到，
// 除非把进程关系注入进来。

private val LOG = logger<WindowsTabPidFileLocation>()

private object WindowsTabPidFileLocation

/** pid 文件的扩展名与前缀；去掉扩展名后的文件名即 tabId。 */
private const val PID_FILE_SUFFIX = ".pid"
private const val TAB_ID_PREFIX = "imux-"

/**
 * 从 CLI 进程沿父链上溯，找出它属于哪个终端标签。
 *
 * 向上走而不是从每个 shell 向下找，是因为前者是 O(链长)、后者要遍历整张进程表。
 *
 * **先看自己再看父辈**：POSIX 的 `-c` 可能让 shell exec 掉自己，届时 CLI 与 shell
 * 同 pid（这条路 Windows 上走不到，但把判断写全，免得将来复用时踩）。
 *
 * [maxDepth] 默认 8：Windows 上 npm 装的 CLI 是 PATH 里的 `.cmd` shim，链路可能是
 * `powershell` &#8594; `cmd` &#8594; `node` &#8594; `claude`，比 POSIX 深；但也不能无限走下去——
 * 走到 pid 1 之外还有环的可能（pid 复用），有上限才有确定的收场。
 *
 * 认不出返回 null，本轮不认领。[LiveSessionProbe] 的铁律：认错会把终端迁到别人的
 * 会话上，比不迁移更糟。
 *
 * [onUnclaimed] 收的是「实际走过的层数」，只在没认领时回调，默认什么都不做。
 * 有它才分得清三种失败：链断了（层数 < [maxDepth]，多半是某一环的父进程已退出，
 * JDK 的 `ProcessHandle.parent()` 用启动时刻校验防 pid 复用，父进程一没就返回空
 * Optional）、链太深（层数 == [maxDepth]）、以及压根没有 pid 文件可比对。
 * 这三种在 Windows 用户那里的症状完全一样——「漂移探测不工作」。
 */
internal fun tabIdByParentChain(
    pid: Long,
    parentOf: (Long) -> Long?,
    tabIdOfShellPid: (Long) -> String?,
    maxDepth: Int = 8,
    onUnclaimed: (depthWalked: Int) -> Unit = {},
): String? {
    var current: Long? = pid
    var walked = 0
    repeat(maxDepth) {
        val at =
            current ?: run {
                onUnclaimed(walked)
                return null
            }
        walked++
        tabIdOfShellPid(at)?.let { return it }
        current = parentOf(at)
    }
    onUnclaimed(walked)
    return null
}

/**
 * 目录下所有 pid 文件，映射为 `shell pid → tabId`。
 *
 * 文件名即 tabId（`imux-&#42;.pid`），内容是一个十进制 pid。
 * 内容不是数字的条目直接跳过而不是抛——目录里可能有写了一半的文件
 * （shell 刚起、还没写完就被探测撞上）。
 */
internal fun tabPidFilesIn(dir: Path): Map<Long, String> =
    runCatching {
        Files.list(dir).use { entries ->
            entries
                .toList()
                .mapNotNull { file ->
                    val name = file.fileName.toString()
                    if (!isTabPidFileName(name)) return@mapNotNull null
                    val pid =
                        runCatching { Files.readString(file).trim().toLong() }.getOrNull()
                            ?: return@mapNotNull null
                    pid to name.removeSuffix(PID_FILE_SUFFIX)
                }.toMap()
        }
    }.getOrElse {
        LOG.debug("读取 pid 文件目录失败：$dir")
        emptyMap()
    }

/**
 * 一个标签的 pid 文件路径。
 *
 * 写入端（[com.github.izerui.imux.terminal.TerminalHost] 拼启动命令）与读出端
 * （[tabPidFilesIn]）必须共用这一条命名规则：两边各写各的时，写出去的文件永远读不回来，
 * 而这个失败没有任何报错。
 */
internal fun tabPidFilePath(
    dir: Path,
    tabId: String,
): Path = dir.resolve("$tabId$PID_FILE_SUFFIX")

/**
 * 删掉一个标签的 pid 文件；不存在也不报错。
 *
 * 关标签页时必须删：pid 会被系统复用，残留的文件会把一个毫不相干的新进程
 * 认成某个标签的 shell。
 */
internal fun deleteTabPidFile(
    dir: Path,
    tabId: String,
) {
    runCatching { Files.deleteIfExists(tabPidFilePath(dir, tabId)) }
        .onFailure { LOG.debug("删除 pid 文件失败：$tabId") }
}

/**
 * 清扫整个目录，用于 IDE 启动时抹掉崩溃退出留下的残留。
 *
 * 判据与 [tabPidFilesIn] 共用 [isTabPidFileName]，一个字都不能松：两处若不对称，
 * 将来往这个目录里放别的 `.pid` 会被这里误删，而读出端根本看不见它。
 */
internal fun sweepTabPidFiles(dir: Path) {
    runCatching {
        Files.list(dir).use { entries ->
            entries.toList().forEach { file ->
                if (isTabPidFileName(file.fileName.toString())) Files.deleteIfExists(file)
            }
        }
    }.onFailure { LOG.debug("清扫 pid 文件目录失败：$dir") }
}

/** 「这是 imux 自己的 pid 文件」的唯一判据。读出端与清扫端必须共用。 */
private fun isTabPidFileName(name: String): Boolean = name.startsWith(TAB_ID_PREFIX) && name.endsWith(PID_FILE_SUFFIX)

/**
 * imux 自己的临时目录，只放 pid 文件。
 *
 * 落在 `PathManager.getSystemPath()` 下——那是 IDE 给插件放缓存与运行态的地方，
 * 卸载插件即随 IDE 系统目录一并消失。**不碰用户的任何 CLI 配置文件**。
 */
internal fun imuxTabPidDir(): Path = Path.of(PathManager.getSystemPath(), "imux", "tabs")

/**
 * 一个进程的父进程 pid；查不到返回 null。
 *
 * JDK 的 [ProcessHandle] 在 Windows 上给不了环境变量与命令行参数，但**给得了父子关系**
 * ——这正是这条通道成立的前提。
 */
internal fun parentPidOf(pid: Long): Long? =
    runCatching {
        ProcessHandle
            .of(pid)
            .flatMap { it.parent() }
            .map(ProcessHandle::pid)
            .orElse(null)
    }.getOrNull()
