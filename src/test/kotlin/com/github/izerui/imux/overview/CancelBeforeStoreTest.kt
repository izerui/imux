package com.github.izerui.imux.overview

import com.github.izerui.imux.overview.report.SessionReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.channels.ClosedByInterruptException
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CancellationException

/**
 * 取消落在「智能体已返回、插件正在统计会话」这几秒里时：统计被中断抛出的异常原先被当作「统计不出来」吞掉，
 * 接着把改动文件、时长都是空的报告存进缓存。界面恢复成旧报告，下次从缓存加载却是这份残缺的，还不提示过期。
 */
class CancelBeforeStoreTest {
    private val old = listOf(OverviewNode(NodeSymbol.MAIN, "旧的时间线"))

    @Test
    fun `统计会话时被取消，不保存新结果，缓存保持生成前的样子`() {
        val file = Files.createTempFile("session", ".jsonl").also {
            it.toFile().deleteOnExit()
            Files.writeString(it, "u1\na1\nu2\n")
        }
        val cache = OverviewCache(null)
        cache.put("s1", OverviewResult(old, Instant.now(), report = SessionReport(timeline = old)), fileSize = 3, lineCount = 2)
        var cancelled = false

        val thrown = runCatching {
            runTrailGeneration(
                cache, "s1", file, { _, _ -> "p" },
                runAgent = { reportOutput("[MAIN] 新的时间线") },
                isCancelled = { cancelled },
                facts = {
                    // 用户点了取消：线程被中断，读会话文件抛出 ClosedByInterruptException
                    cancelled = true
                    throw ClosedByInterruptException()
                },
            )
        }.exceptionOrNull()

        assertTrue("应按取消结束：$thrown", thrown is CancellationException)
        assertEquals("旧的时间线", cache.get("s1")!!.result.nodes.single().text)
    }
}
