package com.github.izerui.imux.terminal

import com.intellij.ui.tabs.TabInfo
import org.junit.Assert.*
import org.junit.Test
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class SessionInfoTabUpdateTest {
    @Test
    fun `请求用量更新保留运行页工作目录的选区`() {
        SwingUtilities.invokeAndWait {
            val previous = InfoPage("runtime", listOf(InfoRow("cwd", "/project/path"), InfoRow("tokens", "100")), searchable = false)
            val root = SessionRuntimeInfoPage(previous.rows)
            val tab = TabInfo(root)
            val scroll = root.components.filterIsInstance<javax.swing.JScrollPane>().single()
            val rows = scroll.viewport.view as JPanel
            fun fields() = rows.components.filterIsInstance<JPanel>()
                .flatMap { it.components.filterIsInstance<javax.swing.JTextPane>() }
            val path = fields()[0].apply { selectAll() }
            val next = previous.copy(rows = listOf(InfoRow("cwd", "/project/path"), InfoRow("tokens", "200")))

            assertTrue(updateInfoTabContent(tab, previous, next) {
                error("运行页更新不应创建新的页容器")
            })

            assertSame(scroll, root.components.single())
            assertSame(path, fields()[0])
            assertEquals("/project/path", fields()[0].selectedText)
            assertEquals("200", fields()[1].text)
        }
    }

    @Test
    fun `运行字段增删后显示新字段`() {
        SwingUtilities.invokeAndWait {
            val previous = InfoPage("runtime", listOf(InfoRow("cwd", "/project/path"), InfoRow("branch", "main")), searchable = false)
            val root = SessionRuntimeInfoPage(previous.rows)
            val tab = TabInfo(root)
            val next = previous.copy(rows = listOf(InfoRow("cwd", "/project/path"), InfoRow("model", "example-model")))

            updateInfoTabContent(tab, previous, next) { error("不应替换运行页") }

            val rows = (root.components.filterIsInstance<javax.swing.JScrollPane>().single().viewport.view as JPanel)
            assertEquals(2, rows.componentCount)
            val fields = rows.components.filterIsInstance<JPanel>()
                .flatMap { it.components.filterIsInstance<javax.swing.JTextPane>() }
            assertEquals(listOf("/project/path", "example-model"), fields.map { it.text })
        }
    }

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
