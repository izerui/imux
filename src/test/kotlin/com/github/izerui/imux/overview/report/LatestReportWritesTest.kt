package com.github.izerui.imux.overview.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 「只写同一会话最新的那次」按会话判断，不按面板：标签页拖动重建后，旧面板排队的写入不能把新报告盖回旧的。 */
class LatestReportWritesTest {
    @Test
    fun `标签页重建后旧面板排队的写入晚执行时，不会把新报告盖回旧的`() {
        val root = Files.createTempDirectory("project")
        val writes = LatestReportWrites()
        val oldPanel = writes.ticket("s1")
        val newPanel = writes.ticket("s1")

        assertTrue(writes.runIfLatest("s1", newPanel) { writeReportFile(root, "s1", "<html>新</html>") })
        assertFalse("旧面板的号已过期，不写", writes.runIfLatest("s1", oldPanel) { writeReportFile(root, "s1", "<html>旧</html>") })

        assertEquals("<html>新</html>", Files.readString(reportFile(root, "s1")))
    }

    @Test
    fun `不同会话各自发号互不作废`() {
        val writes = LatestReportWrites()
        val a = writes.ticket("a")
        writes.ticket("b")
        assertTrue(writes.runIfLatest("a", a) { null })
    }

    @Test
    fun `作废之后排队中的写入都不再执行`() {
        val writes = LatestReportWrites()
        val pending = writes.ticket("s1")
        writes.ticket("s1")
        assertFalse(writes.runIfLatest("s1", pending) { error("不应执行") })
    }

    @Test
    fun `并发的两次写入一次只进行一个，较早的号排到后面也会跳过`() {
        val root = Files.createTempDirectory("project")
        val writes = LatestReportWrites()
        val oldTicket = writes.ticket("s1")
        val newTicket = writes.ticket("s1")
        val pool = Executors.newFixedThreadPool(2)
        val newWritten = CountDownLatch(1)
        pool.submit { writes.runIfLatest("s1", newTicket) { writeReportFile(root, "s1", "<html>新</html>") }; newWritten.countDown() }
        pool.submit { newWritten.await(); writes.runIfLatest("s1", oldTicket) { writeReportFile(root, "s1", "<html>旧</html>") } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals("<html>新</html>", Files.readString(reportFile(root, "s1")))
    }

    /** 模拟慢盘：写入在 [release] 放行前一直卡着。 */
    private fun daemonPool() = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }

    private fun slowWrite(writes: LatestReportWrites, key: String, started: CountDownLatch, release: CountDownLatch) =
        daemonPool().apply {
            val ticket = writes.ticket(key)
            submit { writes.runIfLatest(key, ticket) { started.countDown(); release.await(); null } }
            shutdown()
        }

    @Test
    fun `后台正在写报告时，界面线程领号不用等磁盘`() {
        val writes = LatestReportWrites()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = slowWrite(writes, "s1", started, release)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        try {
            val ticketing = daemonPool()
            val sameSession = ticketing.submit<Long> { writes.ticket("s1") }
            val otherSession = ticketing.submit<Long> { writes.ticket("s2") }
            ticketing.shutdown()
            assertTrue("同一会话正在写时领号也不能卡住", sameSession.get(1, TimeUnit.SECONDS) > 0)
            assertTrue("别的会话领号同样不能卡住", otherSession.get(1, TimeUnit.SECONDS) > 0)
        } finally {
            release.countDown()
            writer.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `一个会话正在写时不挡住别的会话写入`() {
        val writes = LatestReportWrites()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = slowWrite(writes, "s1", started, release)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        try {
            val other = daemonPool()
            val wrote = other.submit<Boolean> { writes.runIfLatest("s2", writes.ticket("s2")) { null } }
            other.shutdown()
            assertTrue(wrote.get(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            writer.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `同一会话两个分屏同时请求时，号过期的面板也收到写好的最新报告`() {
        val root = Files.createTempDirectory("project")
        val writes = LatestReportWrites()
        val left = mutableListOf<Pair<String, ReportWrite?>>()
        val right = mutableListOf<Pair<String, ReportWrite?>>()
        writes.addListener { key, _, write -> left += key to write }
        writes.addListener { key, _, write -> right += key to write }

        val leftTicket = writes.ticket("s1")
        val rightTicket = writes.ticket("s1")
        assertFalse("左边号已过期，不写", writes.runIfLatest("s1", leftTicket) { writeReportFile(root, "s1", "<html>旧</html>") })
        assertTrue(writes.runIfLatest("s1", rightTicket) { writeReportFile(root, "s1", "<html>新</html>") })

        val expected = listOf("s1" to ReportWrite(reportFile(root, "s1"), changed = true, digest = contentDigest("<html>新</html>")))
        assertEquals("左边面板也要拿到写好的报告", expected, left)
        assertEquals(expected, right)
        assertEquals("<html>新</html>", Files.readString(reportFile(root, "s1")))
    }

    @Test
    fun `写入失败也通知所有面板，注销后不再通知`() {
        val writes = LatestReportWrites()
        val heard = mutableListOf<Pair<String, ReportWrite?>>()
        val remove = writes.addListener { key, _, write -> heard += key to write }

        assertTrue(writes.runIfLatest("s1", writes.ticket("s1")) { null })
        remove()
        writes.runIfLatest("s1", writes.ticket("s1")) { null }

        assertEquals(listOf<Pair<String, ReportWrite?>>("s1" to null), heard)
    }

    @Test
    fun `旧请求写失败后的通知晚于新请求的成功通知到达时被丢掉`() {
        val root = Files.createTempDirectory("project")
        val oldWritten = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        var oldTicket = -1L
        // 测试钩子：旧请求写完、发通知之前在这里卡住
        val writes = LatestReportWrites(beforeNotify = { _, ticket -> if (ticket == oldTicket) { oldWritten.countDown(); releaseOld.await() } })
        val heard = java.util.Collections.synchronizedList(mutableListOf<Pair<String, ReportWrite?>>())
        writes.addListener { key, _, write -> heard += key to write }

        oldTicket = writes.ticket("s1")
        val old = daemonPool().apply {
            submit { writes.runIfLatest("s1", oldTicket) { null } }
            shutdown()
        }
        assertTrue("旧请求已写完（失败），停在通知前", oldWritten.await(5, TimeUnit.SECONDS))

        val newWrite = writeReportFile(root, "s1", "<html>新</html>")
        assertTrue(writes.runIfLatest("s1", writes.ticket("s1")) { newWrite })
        releaseOld.countDown()
        assertTrue(old.awaitTermination(5, TimeUnit.SECONDS))

        assertEquals("只剩新请求的成功通知", listOf<Pair<String, ReportWrite?>>("s1" to newWrite), heard.toList())
    }

    @Test
    fun `旧请求改了文件后停在通知前，新请求写同样内容没改文件也先通知时，面板仍按内容知道要重新加载`() {
        val root = Files.createTempDirectory("project")
        writeReportFile(root, "s1", "<html>旧</html>")
        val shownBefore = ShownReport(reportFile(root, "s1"), contentDigest("<html>旧</html>"))
        val oldWritten = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        var oldTicket = -1L
        val writes = LatestReportWrites(beforeNotify = { _, ticket -> if (ticket == oldTicket) { oldWritten.countDown(); releaseOld.await() } })
        val heard = java.util.Collections.synchronizedList(mutableListOf<ReportWrite?>())
        writes.addListener { _, _, write -> heard += write }

        oldTicket = writes.ticket("s1")
        val old = daemonPool().apply {
            submit { writes.runIfLatest("s1", oldTicket) { writeReportFile(root, "s1", "<html>新</html>") } }
            shutdown()
        }
        assertTrue("旧请求已把文件改成新版，停在通知前", oldWritten.await(5, TimeUnit.SECONDS))
        assertTrue(writes.runIfLatest("s1", writes.ticket("s1")) { writeReportFile(root, "s1", "<html>新</html>") })
        releaseOld.countDown()
        assertTrue(old.awaitTermination(5, TimeUnit.SECONDS))

        val delivered = heard.single()!!
        assertFalse("新请求没再改文件", delivered.changed)
        assertTrue("但面板显示的是旧版，按内容判断仍要重新加载", needsReload(shownBefore, delivered))
    }

    @Test
    fun `面板按路径和内容摘要决定要不要重新加载`() {
        val path = java.nio.file.Path.of("/p/.imux/reports/s1.html")
        val write = ReportWrite(path, changed = false, digest = "b")
        assertTrue("还没显示过", needsReload(null, write))
        assertTrue("显示的内容不同", needsReload(ShownReport(path, "a"), write))
        assertTrue("换了文件", needsReload(ShownReport(java.nio.file.Path.of("/p/.imux/reports/s2.html"), "b"), write))
        assertFalse("同一文件同一内容", needsReload(ShownReport(path, "b"), write.copy(changed = true)))
    }

    @Test
    fun `点击前的请求不结算打开意图，点击之后的请求成功才打开、失败才放弃`() {
        assertEquals("没有打开意图", OpenDecision(openNow = false, pending = null), settleOpen(pending = null, noticeTicket = 5, written = true))
        assertEquals("点击前的失败通知：意图留着", OpenDecision(openNow = false, pending = 5), settleOpen(pending = 5, noticeTicket = 4, written = false))
        assertEquals("点击前的成功通知：也不算，等点击那次", OpenDecision(openNow = false, pending = 5), settleOpen(pending = 5, noticeTicket = 4, written = true))
        assertEquals("点击那次写好：打开", OpenDecision(openNow = true, pending = null), settleOpen(pending = 5, noticeTicket = 5, written = true))
        assertEquals("更新的请求写好：同样打开", OpenDecision(openNow = true, pending = null), settleOpen(pending = 5, noticeTicket = 7, written = true))
        assertEquals("点击那次写失败：放弃", OpenDecision(openNow = false, pending = null), settleOpen(pending = 5, noticeTicket = 5, written = false))
    }

    @Test
    fun `点击前的失败通知在点击后才到、新请求随后成功时，仍会打开浏览器`() {
        val root = Files.createTempDirectory("project")
        val oldFailed = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        var oldTicket = -1L
        val writes = LatestReportWrites(beforeNotify = { _, ticket -> if (ticket == oldTicket) { oldFailed.countDown(); releaseOld.await() } })
        val notices = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, ReportWrite?>>())
        writes.addListener { _, ticket, write -> notices += ticket to write }

        oldTicket = writes.ticket("s1")
        val old = daemonPool().apply {
            submit { writes.runIfLatest("s1", oldTicket) { null } }
            shutdown()
        }
        assertTrue("旧请求写失败，停在通知前", oldFailed.await(5, TimeUnit.SECONDS))
        val clickTicket = writes.ticket("s1")
        releaseOld.countDown()
        assertTrue(old.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(writes.runIfLatest("s1", clickTicket) { writeReportFile(root, "s1", "<html>新</html>") })

        var pending: Long? = clickTicket
        var opened = false
        for ((ticket, write) in notices.toList()) {
            val decision = settleOpen(pending, ticket, written = write != null)
            pending = decision.pending
            if (decision.openNow) opened = true
        }
        assertEquals("旧失败先到、新成功后到", listOf(oldTicket to null, clickTicket to notices.last().second), notices.toList())
        assertTrue("打开意图没被点击前的请求结算掉", opened)
    }
}
