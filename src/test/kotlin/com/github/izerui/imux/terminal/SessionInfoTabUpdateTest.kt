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
                    JPanel(java.awt.BorderLayout())
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
    fun `刷新保留平台持有的根容器并移除旧内容`() {
        SwingUtilities.invokeAndWait {
            val previous = InfoPage("runtime", listOf(InfoRow("tokens", "100")))
            val next = previous.copy(rows = listOf(InfoRow("tokens", "200")))
            val oldText = JTextArea("100")
            val root = JPanel(java.awt.BorderLayout()).apply { add(oldText) }
            val host = JPanel().apply { add(root) }
            val tab = TabInfo(root)
            val newText = JTextArea("200")
            val search = JTextArea("search")
            val replacement = JPanel(java.awt.BorderLayout()).apply {
                add(search, java.awt.BorderLayout.NORTH)
                add(newText, java.awt.BorderLayout.CENTER)
            }
            assertTrue(updateInfoTabContent(tab, previous, next) { replacement })
            assertSame(root, tab.component)
            assertSame(host, root.parent)
            assertEquals(1, host.componentCount)
            assertNull(oldText.parent)
            assertSame(root, newText.parent)
            assertSame(root, search.parent)
            assertEquals(java.awt.BorderLayout.NORTH, (root.layout as java.awt.BorderLayout).getConstraints(search))
        }
    }
}
