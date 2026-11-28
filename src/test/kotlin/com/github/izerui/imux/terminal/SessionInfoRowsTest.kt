package com.github.izerui.imux.terminal

import org.junit.Assert.*
import org.junit.Test
import java.awt.Container
import javax.swing.*

class SessionInfoRowsTest {
    @Test
    fun `长说明文字在窄视口换行增高且不产生横向滚动`() {
        SwingUtilities.invokeAndWait {
            val text = JTextArea("正在检查项目中的运行状态与文件变化，".repeat(8)).apply {
                lineWrap = true
                wrapStyleWord = true
            }
            val rows = SessionInfoRows().apply {
                add(text)
            }
            val scroll = JScrollPane(rows).apply {
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }
            fun layoutTree(container: Container) {
                container.doLayout()
                container.components.filterIsInstance<Container>().forEach(::layoutTree)
            }
            fun atWidth(width: Int): Int {
                scroll.setSize(width, 180)
                repeat(5) { layoutTree(scroll) }
                assertEquals(scroll.viewport.extentSize.width, rows.width)
                assertFalse(scroll.horizontalScrollBar.isVisible)
                assertTrue(text.width <= scroll.viewport.extentSize.width)
                assertTrue("换行文字的高度不能被截断", text.height >= text.preferredSize.height)
                return text.height
            }
            val wide = atWidth(440)
            val narrow = atWidth(260)
            assertTrue("变窄后应换成更多行", narrow > wide)
        }
    }
}
