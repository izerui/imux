package com.github.izerui.imux.session

import com.intellij.openapi.util.SystemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/** 解析 `ps eww` 与 `lsof` 的输出。命令本身不可测，解析必须可测。 */
class ProcessProbesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `从 ps 输出里取出终端标记`() {
        // 真实形状：env 与命令行混在一行里，空格分隔，顺序不保证
        val output = """
            |  PID TTY      STAT   TIME COMMAND
            |11814 ??       S   1:23.45 SHELL=/bin/zsh IMUX_TAB=imux-abc123 TERM=xterm-256color claude
        """.trimMargin()

        assertEquals("imux-abc123", tabIdFromPsOutput(output))
    }

    @Test
    fun `没有标记的进程解析为空`() {
        val output = "  PID TTY      STAT   TIME COMMAND\n999 ??  S 0:01.00 SHELL=/bin/zsh claude"

        assertNull(tabIdFromPsOutput(output))
    }

    @Test
    fun `不会把名字相似的变量当成标记`() {
        // IMUX_TABS、MY_IMUX_TAB 都不是我们注入的那个
        val output = "999 ?? S 0:01.00 IMUX_TABS=x MY_IMUX_TAB=y claude"

        assertNull(tabIdFromPsOutput(output))
    }

    @Test
    fun `空输出解析为空`() {
        assertNull(tabIdFromPsOutput(""))
    }

    /**
     * **三条读取通道共用同一条 tabId 判据。**
     *
     * 从前 `/proc` 只要非空就照单全收、`ps` 只要求不含空白、Windows 那侧却要求
     * `imux-` 前缀——同一个畸形值在三个平台上有三种命运，而这一层的铁律是
     * 「认不出就跳过：认错会把终端迁到别人的会话上，比不迁移更糟」。
     *
     * 收紧对 macOS 没有可观察的影响：imux 发出的 tabId 一律是 `imux-<uuid>`，
     * 被挡掉的只可能是**别人**的 `IMUX_TAB`。
     *
     * 三支各一条断言：判据本身、`ps` 通道、`/proc` 通道。
     */
    @Test
    fun `三条通道的 tabId 判据是同一条`() {
        assertTrue(isTabId("imux-abc123"))
        assertFalse("空值不是 tabId", isTabId(""))
        assertFalse("只有前缀不是 tabId", isTabId("imux-"))
        assertFalse("别人的变量值不是 tabId", isTabId("something-else"))
        assertFalse("带空白的不是 tabId", isTabId("imux-a b"))

        // ps 通道：别人往 IMUX_TAB 里写了什么，一律不认领
        assertNull(tabIdFromPsOutput("999 ?? S 0:01.00 IMUX_TAB=not-ours claude"))
        assertEquals("imux-ok", tabIdFromPsOutput("999 ?? S 0:01.00 IMUX_TAB=imux-ok claude"))

        // /proc 通道：同一把尺子
        assertNull(tabIdFromProcEnviron("IMUX_TAB=not-ours\u0000".toByteArray()))
        assertEquals("imux-ok", tabIdFromProcEnviron("IMUX_TAB=imux-ok\u0000".toByteArray()))
    }

    @Test
    fun `从 lsof 输出里挑出 rollout 文件`() {
        // 真实形状：codex 同时开着一堆无关文件，只有 rollout 才是会话
        val output = """
            |COMMAND   PID     USER   FD   TYPE DEVICE  SIZE/OFF     NODE NAME
            |codex   31694 liuyuhua  cwd    DIR   1,18       640 12345678 /Users/x/github/imux
            |codex   31694 liuyuhua    7u   REG   1,18      1024 87654321 /Users/x/.codex/history.jsonl
            |codex   31694 liuyuhua   30u   REG   1,18    120847 37265223 /Users/x/.codex/sessions/2026/08/06/rollout-2026-08-06T13-59-47-019fd5a8-0890-73f3-abf8-891be422a5a6.jsonl
        """.trimMargin()

        assertEquals(
            listOf(
                "/Users/x/.codex/sessions/2026/08/06/" +
                    "rollout-2026-08-06T13-59-47-019fd5a8-0890-73f3-abf8-891be422a5a6.jsonl",
            ),
            rolloutPathsFromLsof(output),
        )
    }

    @Test
    fun `路径含空格时不被截断`() {
        // NAME 是 lsof 输出的最后一列，其中的空格属于路径本身
        val output = "codex 1 u 30u REG 1,18 1 1 /Users/x/my dir/rollout-2026-08-06T13-59-47-" +
            "019fd5a8-0890-73f3-abf8-891be422a5a6.jsonl"

        assertEquals(
            listOf(
                "/Users/x/my dir/rollout-2026-08-06T13-59-47-" +
                    "019fd5a8-0890-73f3-abf8-891be422a5a6.jsonl",
            ),
            rolloutPathsFromLsof(output),
        )
    }

    @Test
    fun `行首有空格时仍能对齐列`() {
        // 多一个前导空格就会切出一个空段，把后面所有列顶掉一位
        val output = "  codex 1 u 30u REG 1,18 1 1 /Users/x/rollout-2026-08-06T13-59-47-" +
            "019fd5a8-0890-73f3-abf8-891be422a5a6.jsonl"

        assertEquals(1, rolloutPathsFromLsof(output).size)
    }

    @Test
    fun `列数不足的行被安全跳过`() {
        // socket、pipe 之类的行列数与普通文件不同，错位不能变成误判
        assertTrue(rolloutPathsFromLsof("codex 1 u 30u REG").isEmpty())
    }

    @Test
    fun `没有 rollout 时返回空`() {
        val output = "codex 31694 liuyuhua 7u REG 1,18 1024 876 /Users/x/.codex/history.jsonl"

        assertTrue(rolloutPathsFromLsof(output).isEmpty())
    }

    @Test
    fun `macOS 与 Linux 的可执行文件名匹配不变`() {
        assertTrue(executableMatches("/opt/homebrew/bin/codex", "codex", isWindows = false))
        assertTrue(executableMatches("/usr/local/bin/codex", "codex", isWindows = false))
        assertFalse(executableMatches("/usr/bin/tail", "codex", isWindows = false))
        // 整条命令行含 codex 不算——那会把 `tail -f codex.log` 也算进来
        assertFalse(executableMatches("/usr/bin/codex-helper", "codex", isWindows = false))
    }

    @Test
    fun `POSIX 上大小写与 exe 后缀都不放宽`() {
        // Linux 大小写敏感：/usr/bin/CODEX 是另一个可执行文件，
        // 认成 codex 会把终端迁到别人的会话上——比不迁移更糟
        assertFalse(executableMatches("/usr/bin/CODEX", "codex", isWindows = false))
        assertFalse(executableMatches("/usr/bin/codex.exe", "codex", isWindows = false))
    }

    @Test
    fun `Windows 的反斜杠路径与 exe 后缀都能认出来`() {
        // 从前用 substringAfterLast('/') 比较，Windows 上两头都不匹配，
        // 结果是一个 codex 进程都认不出来，漂移探测整个静默失效
        assertTrue(executableMatches("C:\\Users\\me\\AppData\\npm\\codex.exe", "codex", isWindows = true))
        assertTrue(executableMatches("C:\\bin\\CODEX.EXE", "codex", isWindows = true))
        assertFalse(executableMatches("C:\\bin\\notcodex.exe", "codex", isWindows = true))
    }

    @Test
    fun `isLinux 为真时走 proc 而不是 ps`() {
        val procRoot = temp.root.toPath()
        val dir = Files.createDirectories(procRoot.resolve("999"))
        Files.write(dir.resolve("environ"), "IMUX_TAB=imux-abc\u0000".toByteArray())

        // isWindows 必须显式传：不传就取 SystemInfo.isWindows，这条用例在 Windows 主机上
        // 会落进 pid 文件分支返回 null，而它要测的是 Linux 分派。
        assertEquals("imux-abc", readTabId(999, isLinux = true, isWindows = false, procRoot = procRoot))
    }

    @Test
    fun `isLinux 为假时不碰 proc`() {
        val procRoot = temp.root.toPath()
        val dir = Files.createDirectories(procRoot.resolve("999"))
        Files.write(dir.resolve("environ"), "IMUX_TAB=imux-abc\u0000".toByteArray())

        // 走的是 ps 那条路，本机没有 pid 999 这个进程，因此认不出来。
        // 若这条返回了 imux-abc，说明分派写反了。
        //
        // isWindows 同样必须显式传，理由同上；另外不传的话这条会去摸真实的
        // PathManager 系统目录，单元测试不该碰那里。
        assertNull(readTabId(999, isLinux = false, isWindows = false, procRoot = procRoot))
    }

    /**
     * **macOS 的 `ps` 分派必须真的走到 `ps`，而不只是「解析函数还在」。**
     *
     * 上一版这条用例名字承诺「走 ps 与 lsof 的解析路径」，方法体却只调了
     * `tabIdFromPsOutput`——`readHeldRollouts` 与 `readTabId` 的非 Linux 分派一次都没
     * 被调用。于是把 `else` 那一支改成 `?: return null`（或干脆 `-> null`）全套照绿，
     * 而 macOS 上一个标签都认不出来。
     *
     * 现在把 `runCommand` 注入进来，直接断言**它收到的是哪一条命令**、
     * 以及输出真的被解析了：这是「走了 ps 但没结果」与「压根没走 ps」唯一的分水岭。
     */
    @Test
    fun `macOS 分派真的把 ps 跑起来并解析它的输出`() {
        val asked = mutableListOf<List<String>>()

        val tabId =
            readTabId(
                11814,
                isLinux = false,
                isWindows = false,
                procRoot = temp.root.toPath(),
                pidDir = { temp.root.toPath() },
                runCommand = { command ->
                    asked += command
                    "11814 ?? S 1:23.45 SHELL=/bin/zsh IMUX_TAB=imux-abc123 claude"
                },
            )

        assertEquals("imux-abc123", tabId)
        assertEquals(
            "macOS 上认领终端靠的就是这一条命令；分派改掉之后它一次都不会被跑到",
            listOf(listOf("ps", "eww", "-p", "11814")),
            asked,
        )
    }

    /** `ps` 起不来（进程已退出、命令不存在）时不认领，而不是拿空串去比。 */
    @Test
    fun `ps 跑不起来时不认领`() {
        assertNull(
            readTabId(
                999,
                isLinux = false,
                isWindows = false,
                procRoot = temp.root.toPath(),
                pidDir = { temp.root.toPath() },
                runCommand = { null },
            ),
        )
    }

    @Test
    fun `readHeldRollouts 在 isLinux 为真时走 proc`() {
        Assume.assumeFalse(SystemInfo.isWindows)
        val procRoot = temp.root.toPath()
        val fd = Files.createDirectories(procRoot.resolve("999/fd"))
        val rollout = temp.newFile(
            "rollout-2026-08-06T13-59-47-c0b2cc08-746f-4dc6-bb78-636d380d9216.jsonl",
        ).toPath()
        Files.createSymbolicLink(fd.resolve("3"), rollout)

        assertEquals(
            listOf(rollout.toString()),
            readHeldRollouts(999, isLinux = true, isWindows = false, procRoot = procRoot),
        )
        // 反向：Linux 分支不得去跑 lsof
        var ranCommand = false
        readHeldRollouts(999, isLinux = true, isWindows = false, procRoot = procRoot, runCommand = {
            ranCommand = true
            null
        })
        assertFalse("Linux 上读 /proc 就够了，不该另起一个 lsof 子进程", ranCommand)
    }

    /**
     * **macOS 的 lsof 分派同样要真的走到 lsof。**
     *
     * 这是三平台改造里唯一一处 macOS 侧「没有红灯保护」的分派。把
     * `else -> rolloutPathsFromLsof(runCommand(…))` 改成 `else -> emptyList()`
     *（一行、看起来完全像正常代码）之后，上一版全套 827 照绿，而 macOS 上 codex 的
     * 漂移探测**彻底失效**：敲 `/new` 后标题停更、未读清不掉，
     * 再点新会话会跟原进程抢同一个 rollout。
     *
     * 「走了 lsof 但没结果」与「压根没走 lsof」在真实环境里给出完全相同的返回值
     *（都是空列表），所以只能把 `runCommand` 注入进来，直接看它收到了什么。
     */
    @Test
    fun `macOS 分派真的把 lsof 跑起来并解析它的输出`() {
        val asked = mutableListOf<List<String>>()
        val rollout =
            "/Users/x/.codex/sessions/2026/08/06/" +
                "rollout-2026-08-06T13-59-47-019fd5a8-0890-73f3-abf8-891be422a5a6.jsonl"

        val held =
            readHeldRollouts(
                31694,
                isLinux = false,
                isWindows = false,
                procRoot = temp.root.toPath(),
                runCommand = { command ->
                    asked += command
                    "COMMAND PID USER FD TYPE DEVICE SIZE/OFF NODE NAME\n" +
                        "codex 31694 me 7u REG 1,18 1024 876 /Users/x/.codex/history.jsonl\n" +
                        "codex 31694 me 30u REG 1,18 120847 372 $rollout\n"
                },
            )

        assertEquals(listOf(rollout), held)
        assertEquals(
            "macOS 上 codex 的漂移探测就靠这一条命令；分派改成 emptyList() 之后它一次都跑不到",
            listOf(listOf("lsof", "-p", "31694")),
            asked,
        )
    }

    /** `lsof` 起不来（未安装、进程已退出）时返回空，而不是抛。 */
    @Test
    fun `lsof 跑不起来时返回空`() {
        assertTrue(
            readHeldRollouts(
                999,
                isLinux = false,
                isWindows = false,
                procRoot = temp.root.toPath(),
                runCommand = { null },
            ).isEmpty(),
        )
    }

    /**
     * **Windows 上一句 `lsof` 都不许跑。**
     *
     * Windows 读不到别的进程打开的文件句柄，改读 codex 自己的运行态 sqlite
     * （见 [CodexRuntimeIndex]）。少了 Windows 分支，会落到 `lsof` 那条路：
     * `GeneralCommandLine` 起不来 → **每一轮探测的每一个 codex pid** 都往
     * `idea.log` 写一条带完整堆栈的 `LOG.warn`。功能不坏，但 Windows 用户排障时唯一的
     * 线索来源被刷屏淹掉——包括 `readTabId` 那条专门为此留下的三态 debug 日志。
     */
    @Test
    fun `Windows 上不碰 lsof 也不碰 proc`() {
        Assume.assumeFalse(SystemInfo.isWindows)
        val procRoot = temp.root.toPath()
        var ranCommand = false

        assertTrue(
            readHeldRollouts(
                999,
                isLinux = false,
                isWindows = true,
                procRoot = procRoot,
                runCommand = {
                    ranCommand = true
                    null
                },
                rolloutOfPid = { null },
            ).isEmpty(),
        )
        assertFalse(
            "Windows 上 lsof 根本不存在，跑它只会让 idea.log 被失败堆栈刷屏",
            ranCommand,
        )
        // Windows 且 isLinux 也为真（不可能，但分派顺序必须写对）时同样不读 /proc
        val fd = Files.createDirectories(procRoot.resolve("777/fd"))
        val rollout = temp.newFile(
            "rollout-2026-08-06T13-59-47-c0b2cc08-746f-4dc6-bb78-636d380d9217.jsonl",
        ).toPath()
        Files.createSymbolicLink(fd.resolve("3"), rollout)
        assertTrue(
            "Windows 分支必须排在最前，否则平台判断的顺序一变就会落到别的路上",
            readHeldRollouts(777, isLinux = true, isWindows = true, procRoot = procRoot, rolloutOfPid = { null }).isEmpty(),
        )
    }

    @Test
    fun `Windows 分支从运行态索引取 rollout 而不是碰 lsof 或 proc`() {
        var ranCommand = false
        val asked = mutableListOf<Long>()

        val held =
            readHeldRollouts(
                4197,
                isLinux = false,
                isWindows = true,
                procRoot = temp.root.toPath(),
                runCommand = { ranCommand = true; null },
                rolloutOfPid = { pid -> asked += pid; "/x/rollout-a.jsonl" },
            )

        assertEquals(listOf("/x/rollout-a.jsonl"), held)
        assertEquals(listOf(4197L), asked)
        assertFalse("Windows 上不该起 lsof", ranCommand)
    }

    @Test
    fun `Windows 上运行态索引查不到时返回空而不是猜`() {
        val held =
            readHeldRollouts(
                4197,
                isLinux = false,
                isWindows = true,
                procRoot = temp.root.toPath(),
                runCommand = { null },
                rolloutOfPid = { null },
            )

        assertEquals(emptyList<String>(), held)
    }

    @Test
    fun `非 Windows 一律不碰运行态索引`() {
        // macOS 走 lsof、Linux 走 proc，两支都不该去查 codex 的 sqlite——
        // 那是 Windows 独有的退路，在别处用等于换掉正在工作的观测面
        var touched = false
        val probe: (Long) -> String? = { touched = true; "/x.jsonl" }

        readHeldRollouts(1, isLinux = true, isWindows = false, procRoot = temp.root.toPath(), rolloutOfPid = probe)
        assertFalse("Linux 分支不该查运行态索引", touched)

        readHeldRollouts(
            1,
            isLinux = false,
            isWindows = false,
            procRoot = temp.root.toPath(),
            runCommand = { null },
            rolloutOfPid = probe,
        )
        assertFalse("macOS 分支不该查运行态索引", touched)
    }

    @Test
    fun `PROC_ROOT 指向正确的系统路径`() {
        assertEquals(Path.of("/proc"), PROC_ROOT)
    }
}
