package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Test

class LspReportTest {

    private fun finding(status: LspStatus) =
        LanguageFinding(LspCatalog.languages.first(), status, remedy = null)

    private fun reportOfAllStatuses() = CliReport(
        agentType = AgentType.PI,
        installed = true,
        findings = LspStatus.entries.map(::finding),
    )

    /**
     * 「缺口」= 用户**真能采取行动**的那两种状态。
     *
     * 这条断言穷举全部状态而不是抽查几个：`gaps` 曾经是 `status != READY`，
     * 加进 AUTO_MANAGED / NOT_AVAILABLE 之后，那个写法会把「pi-lens 会自动装」
     * 和「官方没这个插件」一起算成缺口，页面顶上的「待补充 N」当场翻倍——
     * 而这两条用户一个都处理不了。UNKNOWN 同样不算：我们并不知道它缺不缺。
     */
    @Test
    fun `缺口只包含配置缺口与二进制缺口`() {
        val gaps = reportOfAllStatuses().gaps.map(LanguageFinding::status)

        assertEquals(listOf(LspStatus.MISSING_CONFIG, LspStatus.MISSING_BINARY), gaps)
    }

    @Test
    fun `已就绪只包含 READY`() {
        val ready = reportOfAllStatuses().ready.map(LanguageFinding::status)

        assertEquals(listOf(LspStatus.READY), ready)
    }

    /**
     * ready + gaps 刻意不等于 findings：中间那三种状态既不是成绩也不是待办。
     * 写进断言是为了防止有人「顺手补齐」，把它们塞回任何一边。
     */
    @Test
    fun `既不就绪也不算缺口的状态存在且不被计入任何一边`() {
        val report = reportOfAllStatuses()

        assertEquals(
            LspStatus.entries.size - report.ready.size - report.gaps.size,
            listOf(LspStatus.UNKNOWN, LspStatus.AUTO_MANAGED, LspStatus.NOT_AVAILABLE).size,
        )
    }
}
