package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TerminalHost` 那几处**接线**的守卫。
 *
 * 这一层没法跑起来做行为测试（未引入平台 test-framework，见 build.gradle.kts），
 * 而它拼出来的东西恰恰是三平台支持的全部落点：pid 自报、Windows 的 pid 文件清理、
 * shell 的取法。审查时实测过三条变异，**每一条都让全量套件保持全绿**：
 *
 * - `pidFile = tabPidFileFor(tabId)` → `null`：Windows 上 claude 与 codex 的漂移
 *   探测一起静默死掉——pid 文件回答的是「这个 shell 属于哪个标签」，两种 agent
 *   都靠它（`tabPidFileFor` 只看 `SystemInfo.isWindows`，不看 agent 类型）
 * - 删掉 `closeSession` 里那句 `deleteTabPidFile`：pid 文件泄漏 → pid 被系统复用 →
 *   **把一个毫不相干的新进程认成某标签的 shell**，正是「认错比不迁移更糟」那一类
 * - `configuredShell = TerminalLocalOptions.getInstance().shellPath` → `null`：
 *   Windows 上永远退回 `powershell.exe`，Git Bash 用户配的东西被无声忽略
 *
 * 归一化与整段比对的工具全部复用 [SourceCode]（规则见那一侧的 KDoc），
 * 不在这里另发明一套判据。
 */
class TerminalHostWiringSourceTest {
    private val host = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt")
    private val startup = SourceCode("src/main/kotlin/com/github/izerui/imux/monitor/ImuxStartupActivity.kt")

    /**
     * 新建会话那条命令的**每一个实参**都被钉住。
     *
     * 这个函数是「三平台支持」在新建路径上的唯一落点，而它的每一项失败都是静默的：
     *
     * - `pidFile` 传 null → Windows 上 shell 不写 pid 文件 → `readTabId` 去一个永远
     *   为空的目录里找 → claude 敲 `/clear` 之后标题停更、未读清不掉，
     *   再点该会话会开出第二个终端与还在跑的原进程抢同一个会话；codex 一并受害，
     *   它在 Windows 上同样靠 pid 文件认「属于哪个标签」
     * - `configuredShell` 换掉或传 null → Windows 上永远退回 `powershell.exe`，
     *   用户在 Terminal 设置里配的 Git Bash 被无声忽略
     * - `piExtension` 传 null → pi 的标签不跟随
     *
     * 整段比对而不是逐条 `contains`：这些实参都是具名的，逐条 `contains("pidFile")`
     * 在改成 `pidFile = null` 之后照样命中。
     */
    @Test
    fun `新建会话的命令带齐 pid 自报与用户配置的 shell`() {
        host.assertSameCode(
            "这几个实参各自守着一条 Windows 上的通道，改成 null 的后果全是静默失效——" +
                "功能看起来「没做」而不是「坏了」。若你只是动了排版，照下面的「期望」抄回去即可。",
            """
            =
                launchCommand(
                    resolveShell(
                        System.getenv("SHELL"),
                        isWindows = SystemInfo.isWindows,
                        configuredShell = TerminalLocalOptions.getInstance().shellPath,
                    ),
                    agentType,
                    resumeId = sessionId,
                    piExtension = piExtensionFor(agentType),
                    initialPrompt = initialPrompt,
                    pidFile = tabPidFileFor(tabId),
                )
            """,
            host.bodyAfter(
                "private fun newCommand(agentType: AgentType, sessionId: String?, " +
                    "initialPrompt: String?, tabId: String): List<String>",
                '(',
            ),
        )
    }

    /**
     * 续聊那条路必须与新建**同样齐全**。
     *
     * 单独一条用例而不是与上一条合并：这两个函数长得像，恰恰因此最容易只改一个。
     * 恢复标签（`restoreTabs`）与列表点击走的都是这一条——Windows 上重启 IDE 之后
     * 恢复出来的每一个标签都没有 pid 文件，是比新建更常见的入口。
     *
     * 唯一的差别是没有 `initialPrompt`：续聊不带初始 prompt。
     */
    @Test
    fun `续聊会话的命令同样带齐 pid 自报`() {
        host.assertSameCode(
            "续聊与新建必须一样齐全。恢复标签走的正是这一条，Windows 上重启 IDE 之后" +
                "恢复出来的每个标签都会落在这里。",
            """
            =
                launchCommand(
                    resolveShell(
                        System.getenv("SHELL"),
                        isWindows = SystemInfo.isWindows,
                        configuredShell = TerminalLocalOptions.getInstance().shellPath,
                    ),
                    agentType,
                    resumeId = sessionId,
                    piExtension = piExtensionFor(agentType),
                    pidFile = tabPidFileFor(tabId),
                )
            """,
            host.bodyAfter(
                "private fun resumeCommand(agentType: AgentType, sessionId: String, tabId: String): List<String>",
                '(',
            ),
        )
    }

    /**
     * 关标签页必须删掉这个标签的 pid 文件——**这是「认错」的唯一防线**。
     *
     * pid 会被系统复用。残留的 `imux-<tabId>.pid` 里记着一个早就退出的 shell 的 pid，
     * 而那个数字迟早会被一个毫不相干的新进程拿到；`tabIdByParentChain` 于是把它认成
     * 这个标签的 shell，终端被迁到别人的会话上——比不迁移更糟。
     *
     * 三件事各自钉一条，因为它们各错各的：
     *
     * 1. 这一句必须在（删掉它就是泄漏）
     * 2. 认的必须是 `file.tabId` 而不是 `key`——key 会随会话迁移而变，
     *    用 key 拼出来的文件名根本不存在，删了个寂寞，而真正的残留还在
     * 3. 必须包在 `SystemInfo.isWindows` 里——别的平台压根没有这个目录
     */
    @Test
    fun `关标签页时删掉这个标签的 pid 文件`() {
        val body = host.compactArgs(host.bodyAfter("fun closeSession(file: AgentTerminalVirtualFile)", '{'))

        assertTrue(
            "少了这一句，pid 文件会一直留着；pid 被系统复用之后，一个毫不相干的新进程" +
                "会被认成这个标签的 shell，终端被迁到别人的会话上：$body",
            body.contains("if(SystemInfo.isWindows)deleteTabPidFile(imuxTabPidDir(),file.tabId)"),
        )
        assertEquals(
            "只能删一次，且只能用 tabId 认：key 会随会话迁移而变，用它拼出来的文件名" +
                "根本不存在——删了个寂寞，而真正的残留还在：$body",
            1,
            Regex("""deleteTabPidFile\(""").findAll(body).count(),
        )
    }

    /**
     * pid 文件路径这几行整段钉死。
     *
     * 三种改法都是静默的：
     *
     * - 去掉 `if (!SystemInfo.isWindows) return null` → macOS 与 Linux 上凭空多出一个
     *   目录和一堆 pid 文件（虽然 `pidFileRecordCommand` 对 POSIX 返回 null、命令行
     *   不变，但目录会被真建出来），而那两个平台根本不读它
     * - 去掉 `Files.createDirectories` → PowerShell 的 `Set-Content` 写进一个不存在的
     *   目录，**失败是静默的**，表现只是这个标签认不出漂移
     * - 把 `getOrElse { … null }` 改成抛 → 建不了目录（权限、磁盘满、杀毒软件）
     *   会让整个会话起不来，把一个软失败升级成硬失败
     */
    @Test
    fun `pid 文件只在 Windows 上生成且失败时退回不自报`() {
        host.assertSameCode(
            "少了建目录那一句，PowerShell 的 Set-Content 会静默失败；" +
                "把失败改成抛，建不了目录就变成整个会话起不来。",
            """
            {
                if (!SystemInfo.isWindows) return null
                return runCatching {
                    val dir = Files.createDirectories(imuxTabPidDir())
                    tabPidFilePath(dir, tabId).toString()
                }.getOrElse {
                    LOG.warn("建不了 pid 文件目录，本标签不做 pid 自报", it)
                    null
                }
            }
            """,
            host.bodyAfter("private fun tabPidFileFor(tabId: String): String?", '{'),
        )
    }

    /**
     * 整套 codex hook 机制必须从接线层彻底消失。
     *
     * 它已被 codex 自己写的运行态 sqlite 取代（`CodexRuntimeIndex`）。留着任何一截
     * 都不是「多余但无害」：`-c hooks.SessionStart=…` 一旦重新出现在命令行上，
     * 用户首次开 codex 标签就会被 codex 的 hook 信任复核屏挡一次，而那道门后面
     * 什么都换不到——上报端点、上报路径与那个 `.ps1` 都已经不在了。
     */
    @Test
    fun `接线层不再有任何 codex hook 的残留`() {
        val source = host.normalized

        listOf("codexHookScriptFor", "codexReporterScript", "codexHookScript", "codexEndpointOf")
            .forEach { symbol ->
                assertFalse(
                    "hook 机制已整套删除，接线层不该再提到 $symbol——它带来的是一次" +
                        "什么都换不到的信任复核屏",
                    source.contains(symbol),
                )
            }
    }

    /**
     * 开机清扫必须**整个应用只做一次**，而且只在 Windows 上做。
     *
     * `ProjectActivity` 每开一个项目窗口就跑一遍，而 pid 文件目录是全应用共用的：
     * 少了那个 `compareAndSet`，第二个项目打开时会把第一个窗口里**活着的**标签的
     * pid 文件一并抹掉，那些标签从此认不出会话漂移——而它们看起来一切正常。
     */
    @Test
    fun `开机清扫只在 Windows 上做且整个应用只做一次`() {
        startup.assertSameCode(
            "少了 compareAndSet，第二个项目窗口一打开就会抹掉第一个窗口里活着的标签的" +
                "pid 文件，那些标签从此认不出漂移，而看起来一切正常。",
            """
            {
                if (!SystemInfo.isWindows) return
                if (!swept.compareAndSet(false, true)) return
                sweepTabPidFiles(imuxTabPidDir())
            }
            """,
            startup.bodyAfter("private fun sweepStaleTabPidFilesOnce()", '{'),
        )
        assertTrue(
            "清扫必须真的被调用，否则崩溃留下的残留永远不会被抹掉",
            startup.compactArgs(startup.normalized).contains("sweepStaleTabPidFilesOnce()"),
        )
    }
}
