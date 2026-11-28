package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionInfoPanelSourceTest {
    private val source = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/SessionInfoPanel.kt")
    private val code = source.compact(source.normalized)

    @Test
    fun `运行页入口使用支持局部更新的组件`() {
        val page = source.compact(source.bodyAfter("private fun pageComponent(page: InfoPage)", '{'))
        assertTrue(page.contains(
            source.compact("""if (page.name == msg("session.info.runtime")) return SessionRuntimeInfoPage(page.rows)""")
        ))
    }

    @Test
    fun `会话信息里的横向分隔线不与弹窗边线相交`() {
        assertTrue(
            "顶部统计区应先留左右边距，再画上下分隔线",
            code.contains(
                source.compact(
                    """
                    JBUI.Borders.compound(
                        JBUI.Borders.empty(0, CONTENT_INSET),
                        JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 0),
                        JBUI.Borders.empty(9, 0),
                    )
                    """.trimIndent()
                )
            )
        )
        assertTrue(
            "页签及表格应整体留出左右边距",
            code.contains(
                source.compact(
                    """
                    JPanel(BorderLayout()).apply {
                        border = JBUI.Borders.empty(0, CONTENT_INSET)
                        add(tabs.component)
                    }
                    """.trimIndent()
                )
            )
        )
        assertTrue(
            "底部更新时间区应先留左右边距，再画顶部分隔线",
            code.contains(
                source.compact(
                    """
                    JBUI.Borders.compound(
                        JBUI.Borders.empty(0, CONTENT_INSET),
                        JBUI.Borders.customLineTop(JBColor.border()),
                        JBUI.Borders.empty(7, 0),
                    )
                    """.trimIndent()
                )
            )
        )
    }

    @Test
    fun `技能路径在标题下方直接显示小字且不再展开`() {
        val heading = source.bodyAfter("val heading = JPanel(BorderLayout(0, JBUI.scale(4))).apply", '{')
        assertTrue(heading.contains("add(nameLine, BorderLayout.NORTH)"))
        assertTrue(heading.contains("JBTextArea(path)"))
        assertTrue(heading.contains("size2D * 0.9f"))
        assertTrue(heading.contains("foreground = UIUtil.getContextHelpForeground()"))
        assertTrue(code.contains("content.add(heading,BorderLayout.NORTH)"))
        org.junit.Assert.assertFalse(code.contains("JToggleButton"))
        org.junit.Assert.assertFalse(code.contains("HoverListener"))
        org.junit.Assert.assertFalse(code.contains("expanded"))
    }

    @Test
    fun `会话标题变化后弹窗标题可以原地更新`() {
        assertTrue(code.contains("privatevaltitleValue=bold(title)"))
        assertTrue(code.contains("funupdateTitle(value:String)"))
        assertTrue(code.contains("titleValue.text=value"))
    }

    @Test
    fun `手动刷新保留搜索和滚动位置的恢复路径`() {
        assertTrue(code.contains("privatevardisplayRevision=0L"))
        assertTrue(code.contains("valfocusedSearch=focusedSearchState()"))
        assertTrue(code.contains("if(revision!=displayRevision)return@invokeLater"))
        assertTrue(code.contains("restoreSearchState(focusedSearch)"))
        assertTrue(code.contains("if(scroll.verticalScrollBar.value==0)scroll.verticalScrollBar.value=offset"))
    }

    @Test
    fun `刷新按页比较并保留未变化页签`() {
        val display = source.compact(source.bodyAfter(
            "fun display(tokens: String, context: String, sections: List<InfoPage>)", '{'
        ))
        assertTrue(display.contains("if(existing!=null&&previousPages[page.name]==page)return@forEachIndexed"))
        assertTrue(display.contains("updateInfoTabContent(existing,previousPages[page.name],page,::pageComponent)"))
        org.junit.Assert.assertFalse(display.contains("removeAllTabs"))
        org.junit.Assert.assertFalse(display.contains("searchFields.clear()"))
    }
}
