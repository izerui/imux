package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.model.AgentType
import com.intellij.icons.AllIcons
import com.intellij.util.ui.EmptyIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionTreeIconTest {

    @Test
    fun `运行中会话同时保留品牌与状态图标`() {
        val icon = sessionRowIcon(AgentType.CLAUDE, AllIcons.Nodes.RunnableMark)

        assertEquals(2, icon.iconCount)
        assertSame(AgentIcons.forAgent(AgentType.CLAUDE), icon.getIcon(0))
        assertSame(AllIcons.Nodes.RunnableMark, icon.getIcon(1))
    }

    @Test
    fun `普通会话为空状态预留固定图标槽位`() {
        val icon = sessionRowIcon(AgentType.CODEX, null)

        assertEquals(2, icon.iconCount)
        assertSame(AgentIcons.forAgent(AgentType.CODEX), icon.getIcon(0))
        assertTrue(icon.getIcon(1) is EmptyIcon)
        assertEquals(32, icon.iconWidth)
        assertEquals(16, icon.iconHeight)
    }
}
