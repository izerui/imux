package com.github.izerui.imux.overview

import com.github.izerui.imux.SourceCode
import com.github.izerui.imux.overview.report.LatestReportWrites
import com.github.izerui.imux.overview.report.ReportWrite
import com.github.izerui.imux.overview.report.SessionReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.time.Instant

/**
 * 报告文件原先只在面板展开时才写：面板收着时生成完、或者从没展开过，`.imux/reports/` 里是旧版甚至没有文件。
 * 现在生成完成就经写入闸门写一次。
 */
class WriteGeneratedReportTest {
    private val node = OverviewNode(NodeSymbol.MAIN, "确认需求")
    private val withReport = OverviewResult(listOf(node), Instant.now(), report = SessionReport(timeline = listOf(node)))
    private val written = ReportWrite(Path.of("/tmp/report.html"), changed = true, digest = "d")

    private val writes = LatestReportWrites()
    private val notified = mutableListOf<Pair<String, ReportWrite?>>()
    private val calls = mutableListOf<Pair<String, OverviewResult>>()

    init {
        writes.addListener { key, _, write -> notified += key to write }
    }

    private fun write(key: String, result: OverviewResult): ReportWrite {
        calls += key to result
        return written
    }

    @Test
    fun `有报告时生成完成就写入，并通知各面板`() {
        writeGeneratedReport("s1", withReport, writes, ::write)

        assertEquals(listOf("s1" to withReport), calls)
        assertEquals(listOf<Pair<String, ReportWrite?>>("s1" to written), notified)
    }

    /** 闸门会把没写成当作写入失败通知出去，正显示旧报告的面板就会误弹「保存失败」。 */
    @Test
    fun `没有报告时不写，也不发通知`() {
        writeGeneratedReport("s1", OverviewResult(listOf(node), Instant.now()), writes, ::write)

        assertEquals(emptyList<Pair<String, OverviewResult>>(), calls)
        assertEquals(emptyList<Pair<String, ReportWrite?>>(), notified)
    }

    /** 面板在生成前为旧报告领的号还没写到时，生成完成的写入让它作废：旧内容不会晚到把新报告盖回去。 */
    @Test
    fun `生成完成的写入让面板此前领的号作废`() {
        val panelTicket = writes.ticket("s1")

        writeGeneratedReport("s1", withReport, writes, ::write)

        assertFalse(writes.runIfLatest("s1", panelTicket) { error("旧号不应再写") })
    }

    /** 上面几条只验了函数本身；服务的生成入口不接它，报告照旧只在展开面板时才写。 */
    @Test
    fun `服务的生成入口在生成完成后写报告`() {
        val service = SourceCode("src/main/kotlin/com/github/izerui/imux/overview/ProgressOverview.kt")

        assertTrue(
            service.compact(service.normalized).contains(
                "generate={key,onProgress->generateOverview(key,onProgress).also{writeGeneratedReport(key,it,reportWrites,::writeReport)}}",
            ),
        )
    }
}
