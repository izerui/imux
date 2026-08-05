package com.github.izerui.imux.toolwindow

import com.intellij.icons.AllIcons
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AgentSessionTreeIconTest {

    @Test
    fun `运行中会话只显示原有运行标记`() {
        val icon = sessionStatusIcon(running = true, unread = true)

        assertSame(AllIcons.Nodes.RunnableMark, icon)
    }

    @Test
    fun `未读会话只显示原有未读标记`() {
        val icon = sessionStatusIcon(running = false, unread = true)

        assertSame(AllIcons.General.Modified, icon)
    }

    @Test
    fun `普通会话不显示图标`() {
        val icon = sessionStatusIcon(running = false, unread = false)

        assertNull(icon)
    }
}
