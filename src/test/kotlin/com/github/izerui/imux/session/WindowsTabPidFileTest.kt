package com.github.izerui.imux.session

import com.github.izerui.imux.terminal.ShellDialect
import com.github.izerui.imux.terminal.pidFileRecordCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * Windows 上「CLI 进程属于哪个终端标签」的身份通道。
 *
 * 这一层的正确性全在「链走到哪停、什么时候不认领」上，而这些分支在 macOS 开发机上
 * 一条也走不到，除非把进程关系注入进来。
 */
class WindowsTabPidFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `POSIX 方言不生成 pid 自报前缀`() {
        // macOS 与 Linux 靠环境变量认领，启动命令一个字都不该加
        assertNull(pidFileRecordCommand(ShellDialect.POSIX, "/tmp/imux/x.pid"))
    }

    @Test
    fun `PowerShell 方言把自己的 pid 写进指定文件`() {
        assertEquals(
            "\$PID | Set-Content -LiteralPath 'C:\\t\\x.pid' -Encoding ascii",
            pidFileRecordCommand(ShellDialect.POWERSHELL, "C:\\t\\x.pid"),
        )
    }

    /**
     * PowerShell 5 的 `Set-Content` 默认写 UTF-16LE 带 BOM，读回来 `"100".toLong()` 会抛，
     * 而失败是静默的（只表现为该标签认不出漂移）。
     */
    @Test
    fun `pid 文件必须以 ascii 写出`() {
        assertTrue(
            "少了 -Encoding ascii，PowerShell 5 会写出 UTF-16LE 带 BOM 的文件，读回来解析不出 pid",
            pidFileRecordCommand(ShellDialect.POWERSHELL, "C:\\t\\x.pid")!!.endsWith(" -Encoding ascii"),
        )
    }

    @Test
    fun `pid 文件路径里的单引号按方言转义`() {
        assertEquals(
            "\$PID | Set-Content -LiteralPath 'C:\\o''brien\\x.pid' -Encoding ascii",
            pidFileRecordCommand(ShellDialect.POWERSHELL, "C:\\o'brien\\x.pid"),
        )
    }

    @Test
    fun `父链上溯认领标签`() {
        // powershell(100) → cmd(101) → node(102) → claude(103)
        val parents = mapOf(103L to 102L, 102L to 101L, 101L to 100L)
        val shells = mapOf(100L to "imux-abc")
        assertEquals(
            "imux-abc",
            tabIdByParentChain(103L, parentOf = parents::get, tabIdOfShellPid = shells::get),
        )
    }

    @Test
    fun `链上没有已知 shell 时不认领`() {
        // 用户自己在终端里敲的 claude，不属于任何 imux 标签
        val parents = mapOf(103L to 102L, 102L to 1L)
        assertNull(
            tabIdByParentChain(103L, parentOf = parents::get, tabIdOfShellPid = { null }),
        )
    }

    @Test
    fun `深度上限防止无谓遍历与环`() {
        // Windows 上 npm 装的 CLI 是 .cmd shim，链路可能是 powershell → cmd → node → claude，
        // 比 POSIX 深；但也不能无限走下去
        val parents = (2L..100L).associateWith { it - 1 }
        assertNull(
            tabIdByParentChain(
                100L,
                parentOf = parents::get,
                tabIdOfShellPid = { pid -> "imux-abc".takeIf { pid == 1L } },
                maxDepth = 8,
            ),
        )
    }

    /**
     * 上一条用例显式传了 `maxDepth = 8`，因此钉不住**默认值**——把默认改成 100 它照样绿。
     * 而默认值才是生产路径上真正生效的那个：`ProcessProbes.readTabId` 不传这个参数。
     */
    @Test
    fun `默认深度上限是 8`() {
        val parents = (2L..100L).associateWith { it - 1 }
        val shellAtOne = { pid: Long -> "imux-abc".takeIf { pid == 1L } }

        // 自身算第一层，pid 8 到 pid 1 刚好八层，够得到
        assertEquals("imux-abc", tabIdByParentChain(8L, parents::get, shellAtOne))
        // 再深一层就够不到
        assertNull(tabIdByParentChain(9L, parents::get, shellAtOne))
    }

    @Test
    fun `自身就是已知 shell 时直接认领`() {
        // POSIX 的 -c 可能让 shell exec 掉自己，CLI 与 shell 同 pid
        assertNull(tabIdByParentChain(50L, parentOf = { null }, tabIdOfShellPid = { null }))
        assertEquals(
            "imux-abc",
            tabIdByParentChain(50L, parentOf = { null }, tabIdOfShellPid = { "imux-abc" }),
        )
    }

    @Test
    fun `读出目录下所有 pid 文件`() {
        val dir = temp.root.toPath()
        temp.newFile("imux-abc.pid").writeText("100\n")
        temp.newFile("imux-def.pid").writeText("200")
        temp.newFile("garbage.txt").writeText("300")
        temp.newFile("imux-bad.pid").writeText("not-a-number")

        assertEquals(mapOf(100L to "imux-abc", 200L to "imux-def"), tabPidFilesIn(dir))
    }

    @Test
    fun `目录不存在时返回空映射`() {
        assertEquals(emptyMap<Long, String>(), tabPidFilesIn(temp.root.toPath().resolve("missing")))
    }

    @Test
    fun `pid 文件名就是 tabId`() {
        // 写入端（TerminalHost 拼启动命令）与读出端（tabPidFilesIn）必须用同一条命名规则，
        // 否则写出去的文件永远读不回来——而这个失败没有任何报错。
        assertEquals(
            temp.root.toPath().resolve("imux-abc.pid"),
            tabPidFilePath(temp.root.toPath(), "imux-abc"),
        )
    }

    @Test
    fun `删除单个标签的 pid 文件`() {
        val dir = temp.root.toPath()
        temp.newFile("imux-abc.pid").writeText("100")
        temp.newFile("imux-def.pid").writeText("200")

        deleteTabPidFile(dir, "imux-abc")

        assertEquals(mapOf(200L to "imux-def"), tabPidFilesIn(dir))
        // 不存在也不该抛：关标签页的路径上不能因为文件早没了就炸
        deleteTabPidFile(dir, "imux-abc")
        deleteTabPidFile(dir.resolve("missing"), "imux-def")
    }

    @Test
    fun `清扫只抹掉 imux 自己的 pid 文件`() {
        val dir = temp.root.toPath()
        temp.newFile("imux-abc.pid").writeText("100")
        temp.newFile("imux-def.pid").writeText("200")
        val keepText = temp.newFile("notes.txt").toPath()
        // 清扫端与读出端必须用同一条判据：只看 .pid 后缀的话，别人的 pid 文件会被误删，
        // 而 tabPidFilesIn 根本看不见它——两边不对称的后果全落在别人身上。
        val keepForeign = temp.newFile("someone-else.pid").toPath()

        sweepTabPidFiles(dir)

        assertEquals(emptyMap<Long, String>(), tabPidFilesIn(dir))
        assertTrue("清扫不能碰目录里别的东西", Files.exists(keepText))
        assertTrue("不带 imux- 前缀的 pid 文件不归我们管，不能删", Files.exists(keepForeign))
        // 目录不存在也不该抛：IDE 首次启动时这个目录还没建起来
        sweepTabPidFiles(dir.resolve("missing"))
    }

    /**
     * 链断了、链太深、压根没有 pid 文件——在 Windows 用户那里症状完全一样
     * （「漂移探测不工作」）。回调报出实际层数，是 Task 11 真机排障唯一的抓手。
     */
    @Test
    fun `没认领时报出实际走过的层数`() {
        val walked = mutableListOf<Int>()

        // 链在第三层断掉（102 没有父进程）：走了 3 层，不到 maxDepth
        tabIdByParentChain(
            103L,
            parentOf = mapOf(103L to 102L)::get,
            tabIdOfShellPid = { null },
            onUnclaimed = walked::add,
        )
        // 链够长但一路没命中：正好走满 maxDepth
        tabIdByParentChain(
            100L,
            parentOf = (2L..100L).associateWith { it - 1 }::get,
            tabIdOfShellPid = { null },
            maxDepth = 4,
            onUnclaimed = walked::add,
        )

        assertEquals(listOf(2, 4), walked)
    }

    @Test
    fun `认领成功时不回调`() {
        var called = false

        assertEquals(
            "imux-abc",
            tabIdByParentChain(
                50L,
                parentOf = { null },
                tabIdOfShellPid = { "imux-abc" },
                onUnclaimed = { called = true },
            ),
        )
        assertFalse("认出来了就没什么可诊断的，不该有日志噪音", called)
    }

    @Test
    fun `Windows 分支从 pid 文件认领而不读 proc`() {
        // 分派选错分支是这一层最难发现的错：Windows 上读不到别的进程的环境变量，
        // 走到 /proc 或 ps 那条路只会一个标签都认不出来。
        val procRoot = temp.root.toPath().resolve("proc")
        Files.createDirectories(procRoot.resolve("999"))
        Files.write(procRoot.resolve("999/environ"), "IMUX_TAB=imux-from-proc\u0000".toByteArray())

        val pidDir = Files.createDirectories(temp.root.toPath().resolve("tabs"))
        val self = ProcessHandle.current().pid()
        Files.writeString(pidDir.resolve("imux-from-pidfile.pid"), self.toString())

        assertEquals(
            "imux-from-pidfile",
            readTabId(self, isLinux = true, isWindows = true, procRoot = procRoot, pidDir = { pidDir }),
        )
    }

    @Test
    fun `Windows 上没有对应 pid 文件时不认领`() {
        val pidDir = Files.createDirectories(temp.root.toPath().resolve("tabs"))

        assertNull(
            readTabId(
                ProcessHandle.current().pid(),
                isLinux = false,
                isWindows = true,
                procRoot = temp.root.toPath(),
                pidDir = { pidDir },
            ),
        )
    }

    @Test
    fun `Linux 分派不碰 pid 文件目录`() {
        // macOS 与 Linux 的分派必须与改动前逐字节相同：pidDir 连读都不该读一下
        val procRoot = temp.root.toPath().resolve("proc")
        Files.createDirectories(procRoot.resolve("999"))
        Files.write(procRoot.resolve("999/environ"), "IMUX_TAB=imux-abc\u0000".toByteArray())
        var touched = false

        assertEquals(
            "imux-abc",
            readTabId(
                999,
                isLinux = true,
                isWindows = false,
                procRoot = procRoot,
                pidDir = {
                    touched = true
                    temp.root.toPath()
                },
            ),
        )
        assertFalse("Linux 分支不该去看 pid 文件目录", touched)
    }

    /**
     * **macOS 分派（两个标志都为假）必须单独钉一条。**
     *
     * 只钉 `isLinux = true` 的那条盖不住它：把第一个分支条件写成 `isWindows || !isLinux ->`
     * ——函数体一字不改、看起来完全像正常代码——`isLinux = true` 的用例照样绿，
     * 而 macOS 上每一次探测都会落进 Windows 分支，去一个永远为空的目录里找 pid 文件，
     * 漂移探测整个静默失效：敲 `/clear` 后标题停更、未读清不掉，
     * 再点该会话会开出第二个 `--resume` 终端与还在跑的原进程抢同一个会话。
     */
    @Test
    fun `macOS 分派既不碰 proc 也不碰 pid 文件目录`() {
        val procRoot = temp.root.toPath().resolve("proc")
        Files.createDirectories(procRoot.resolve("999"))
        Files.write(procRoot.resolve("999/environ"), "IMUX_TAB=imux-from-proc\u0000".toByteArray())
        val pidDir = Files.createDirectories(temp.root.toPath().resolve("tabs"))
        Files.writeString(pidDir.resolve("imux-from-pidfile.pid"), ProcessHandle.current().pid().toString())
        var touched = false

        // 走的是 ps 那条路，本机没有 pid 999 这个进程，因此认不出来。
        // 这个 null 在三个平台上都成立：Windows 上连 ps 都没有，runCommand 直接失败。
        assertNull(
            readTabId(
                999,
                isLinux = false,
                isWindows = false,
                procRoot = procRoot,
                pidDir = {
                    touched = true
                    pidDir
                },
            ),
        )
        assertFalse("macOS 分支不该去看 pid 文件目录", touched)
    }

    @Test
    fun `pid 文件目录落在 IDE 系统目录下的 imux 自己的位置`() {
        // 铁律：不修改任何 CLI 的配置文件。pid 文件只允许写进 PathManager 的系统目录。
        val dir = imuxTabPidDir()

        assertEquals("tabs", dir.fileName.toString())
        assertEquals("imux", dir.parent.fileName.toString())
        assertEquals(
            com.intellij.openapi.application.PathManager
                .getSystemPath(),
            dir.parent.parent
                .toString(),
        )
    }
}
