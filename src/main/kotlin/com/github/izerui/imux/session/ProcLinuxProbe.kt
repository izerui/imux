package com.github.izerui.imux.session

import com.intellij.openapi.diagnostic.logger
import java.nio.file.Files
import java.nio.file.Path

// Linux 上的进程探测：直接读 /proc，不起子进程。
//
// 比 macOS 那条路（`ps eww` + `lsof`）严格更好：
// - 不起子进程（现在每轮探测起两个）
// - **不依赖 lsof**——很多发行版默认不装，现在的实现在那些机器上静默返回空
// - 没有输出格式解析，少一类最容易出错的东西
//
// procRoot 参数化是为了测试能指向临时目录，与 SessionRepository.forUserHome() 的
// 做法一致。生产入口传 /proc。

private val LOG = logger<ProcLinuxProbeLocation>()

private object ProcLinuxProbeLocation

/**
 * 从 `/proc/&lt;pid&gt;/environ` 的内容里取出 [IMUX_TAB_ENV]。
 *
 * 格式是 NUL 分隔的 `KEY=VALUE`，因此可以精确按条切分——不像 `ps eww` 把 env 和
 * 命令行拼在同一行、必须靠正则锚定变量名。变量名仍要整条相等地比，
 * 否则 `MY_IMUX_TAB` 与 `IMUX_TABS` 都会被误认。
 *
 * 空值当作没有：不是 imux 开的进程，或者 shell 把变量清了。判据用共用的 [isTabId]，
 * 与 `ps` 那条通道、Windows 的 pid 文件通道同一把尺子——三套宽严不一的判据意味着
 * 同一个畸形值在三个平台上有三种命运，而这一层的铁律是「认不出就跳过」。
 */
internal fun tabIdFromProcEnviron(bytes: ByteArray): String? =
    String(bytes, Charsets.UTF_8)
        .split('\u0000')
        .firstNotNullOfOrNull { entry ->
            val separator = entry.indexOf('=')
            if (separator < 0) return@firstNotNullOfOrNull null
            if (entry.substring(0, separator) != IMUX_TAB_ENV) return@firstNotNullOfOrNull null
            entry.substring(separator + 1).takeIf(::isTabId)
        }

/**
 * 读一个进程的 [IMUX_TAB_ENV]。
 *
 * 读不到（进程已退出、无权限、非本用户进程）返回 null，本轮不认领——与 `ps` 失败同构。
 * `LiveSessionProbe` 的铁律是「认不出就跳过，不能猜」。
 */
internal fun readTabIdFromProc(
    pid: Long,
    procRoot: Path,
): String? =
    runCatching {
        tabIdFromProcEnviron(Files.readAllBytes(procRoot.resolve("$pid/environ")))
    }.getOrElse {
        LOG.debug("读取 /proc/$pid/environ 失败，本轮不认领该进程", it)
        null
    }

/**
 * 读一个进程正持有的 rollout 文件。
 *
 * `/proc/&lt;pid&gt;/fd/` 下每个条目都是指向被打开文件的软链。
 *
 * **个别软链读不了不能带倒整轮**：目录在遍历期间会变（进程随时开关文件），
 * 而 codex 同时开着一大堆无关文件——少认一个是软失败，整轮返回空则会让
 * 一个真在跑的会话被当成认不出来。
 */
internal fun readHeldRolloutsFromProc(
    pid: Long,
    procRoot: Path,
): List<String> =
    runCatching {
        Files.list(procRoot.resolve("$pid/fd")).use { entries ->
            entries
                .map { runCatching { Files.readSymbolicLink(it).toString() }.getOrNull() }
                .toList()
                .filterNotNull()
                .filter { threadIdOfRollout(it) != null }
        }
    }.getOrElse {
        LOG.debug("读取 /proc/$pid/fd 失败，本轮不认领该进程", it)
        emptyList()
    }
