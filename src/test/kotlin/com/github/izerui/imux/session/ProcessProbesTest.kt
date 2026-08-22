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

        assertEquals("imux-abc", readTabId(999, isLinux = true, procRoot = procRoot))
    }

    @Test
    fun `isLinux 为假时不碰 proc`() {
        val procRoot = temp.root.toPath()
        val dir = Files.createDirectories(procRoot.resolve("999"))
        Files.write(dir.resolve("environ"), "IMUX_TAB=imux-abc\u0000".toByteArray())

        // 走的是 ps 那条路，本机没有 pid 999 这个进程，因此认不出来。
        // 若这条返回了 imux-abc，说明分派写反了。
        assertNull(readTabId(999, isLinux = false, procRoot = procRoot))
    }

    @Test
    fun `非 Linux 仍走 ps 与 lsof 的解析路径`() {
        // 这两个纯解析函数是 macOS 上正在工作的东西，Linux 分支不得影响它们
        assertEquals(
            "imux-abc",
            tabIdFromPsOutput("  501 22941 ttys003 PATH=/usr/bin IMUX_TAB=imux-abc /bin/zsh"),
        )
        assertNull(tabIdFromPsOutput("  501 22941 ttys003 MY_IMUX_TAB=imux-abc /bin/zsh"))
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

        assertEquals(listOf(rollout.toString()), readHeldRollouts(999, isLinux = true, procRoot = procRoot))
        // 反向：走 lsof 那条路，本机没有 pid 999 这个进程，认不出来
        assertTrue(readHeldRollouts(999, isLinux = false, procRoot = procRoot).isEmpty())
    }

    @Test
    fun `PROC_ROOT 指向正确的系统路径`() {
        assertEquals(Path.of("/proc"), PROC_ROOT)
    }
}
