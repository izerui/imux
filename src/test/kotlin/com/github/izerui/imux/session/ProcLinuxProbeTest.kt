package com.github.izerui.imux.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class ProcLinuxProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun environ(vararg entries: String): ByteArray =
        entries.joinToString("\u0000").toByteArray() + 0

    @Test
    fun `从 NUL 分隔的 environ 里取出 IMUX_TAB`() {
        val bytes = environ("PATH=/usr/bin", "IMUX_TAB=imux-abc", "LANG=zh_CN.UTF-8")
        assertEquals("imux-abc", tabIdFromProcEnviron(bytes))
    }

    @Test
    fun `值里含空格的其它变量不干扰`() {
        // ps 那一侧靠正则锚定变量名正是因为这个；/proc 是 NUL 分隔，天然没这个问题，
        // 但仍要钉住——将来有人改成按空格切就会红
        val bytes = environ("MSG=hello world here", "IMUX_TAB=imux-abc")
        assertEquals("imux-abc", tabIdFromProcEnviron(bytes))
    }

    @Test
    fun `变量名边界要卡死`() {
        assertNull(tabIdFromProcEnviron(environ("MY_IMUX_TAB=x")))
        assertNull(tabIdFromProcEnviron(environ("IMUX_TABS=x")))
    }

    @Test
    fun `没有这个变量返回 null`() {
        assertNull(tabIdFromProcEnviron(environ("PATH=/usr/bin")))
        assertNull(tabIdFromProcEnviron(ByteArray(0)))
    }

    @Test
    fun `空值当作没有`() {
        // 不是 imux 开的进程，或者 shell 把变量清了
        assertNull(tabIdFromProcEnviron(environ("IMUX_TAB=")))
    }

    @Test
    fun `读不到 proc 目录时返回 null 而不是抛异常`() {
        // 进程已退出、无权限——与 ps 失败同构，本轮不认领
        assertNull(readTabIdFromProc(4242, temp.root.toPath()))
    }

    @Test
    fun `从 proc fd 目录读出 rollout 路径`() {
        val procRoot = temp.root.toPath()
        val fd = Files.createDirectories(procRoot.resolve("777/fd"))
        val id = "c0b2cc08-746f-4dc6-bb78-636d380d9216"
        val rollout = temp.newFile("rollout-2026-08-06T13-59-47-$id.jsonl").toPath()
        val noise = temp.newFile("history.jsonl").toPath()
        Files.createSymbolicLink(fd.resolve("3"), rollout)
        Files.createSymbolicLink(fd.resolve("4"), noise)

        assertEquals(listOf(rollout.toString()), readHeldRolloutsFromProc(777, procRoot))
    }

    @Test
    fun `个别软链读不了不影响其余`() {
        val procRoot = temp.root.toPath()
        val fd = Files.createDirectories(procRoot.resolve("778/fd"))
        val id = "c0b2cc08-746f-4dc6-bb78-636d380d9216"
        val rollout = temp.newFile("rollout-2026-08-06T13-59-48-$id.jsonl").toPath()
        Files.createSymbolicLink(fd.resolve("3"), rollout)
        // 普通文件不是软链，readSymbolicLink 会抛——不能让它带倒整轮
        Files.createFile(fd.resolve("4"))

        assertEquals(listOf(rollout.toString()), readHeldRolloutsFromProc(778, procRoot))
    }

    @Test
    fun `进程目录不存在时返回空列表`() {
        assertEquals(emptyList<String>(), readHeldRolloutsFromProc(4242, temp.root.toPath()))
    }
}
