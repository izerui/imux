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
        assertTrue(writes.runIfLatest("a", a) {})
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
            submit { writes.runIfLatest(key, ticket) { started.countDown(); release.await() } }
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
            val wrote = other.submit<Boolean> { writes.runIfLatest("s2", writes.ticket("s2")) {} }
            other.shutdown()
            assertTrue(wrote.get(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            writer.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
