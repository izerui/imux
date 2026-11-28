package com.github.izerui.imux.terminal

import com.intellij.ui.tabs.TabInfo
import org.junit.Assert.*
import org.junit.Test
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class SessionInfoTabUpdateTest {
    @Test
    fun `运行用量变化保留技能路径说明及服务名称的选区`() {
        SwingUtilities.invokeAndWait {
            val runtime = InfoPage("runtime", listOf(InfoRow("tokens", "100")))
            val skills = InfoPage("skills", listOf(InfoRow("review", "Review code", location = "/skills/review")))
            val mcp = InfoPage("MCP", listOf(InfoRow("idea")))
            val pages = listOf(runtime, skills, mcp)
            val path = JTextArea("/skills/review").apply { select(0, 7) }
            val description = JTextArea("Review code").apply { select(0, 6) }
            val service = SelectableInfoText("idea").apply { selectAll() }
            val skillsContent = JPanel().apply { add(path); add(description) }
            val mcpContent = JPanel().apply { add(service) }
            val tabs = listOf(TabInfo(JPanel()), TabInfo(skillsContent), TabInfo(mcpContent))
            val rebuilt = mutableListOf<String>()
            val next = listOf(runtime.copy(rows = listOf(InfoRow("tokens", "200"))), skills, mcp)
            next.forEachIndexed { index, page ->
                updateInfoTabContent(tabs[index], pages[index], page) {
                    rebuilt += it.name
                    JPanel()
                }
            }
            assertEquals(listOf("runtime"), rebuilt)
            assertSame(skillsContent, tabs[1].component)
            assertSame(mcpContent, tabs[2].component)
            assertEquals("/skills", path.selectedText)
            assertEquals("Review", description.selectedText)
            assertEquals("idea", service.selectedText)
        }
    }

    @Test
    fun `更新页签内容时通知平台显示新组件`() {
        SwingUtilities.invokeAndWait {
            val previous = InfoPage("runtime", listOf(InfoRow("tokens", "100")))
            val next = previous.copy(rows = listOf(InfoRow("tokens", "200")))
            val tab = TabInfo(JPanel())
            val replacement = JPanel()
            var notifications = 0
            tab.changeSupport.addPropertyChangeListener("component") { notifications++ }
            assertTrue(updateInfoTabContent(tab, previous, next) { replacement })
            assertSame(replacement, tab.component)
            assertEquals(1, notifications)
        }
    }
}
