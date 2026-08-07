package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class NewSessionPopupTest {

    @Test
    fun `菜单为每个 Agent 生成一项并带品牌图标`() {
        val group = agentActionGroup {}

        val children = group.childActionsOrStubs

        assertEquals(AgentType.entries.size, children.size)
        AgentType.entries.forEachIndexed { index, agentType ->
            val presentation = children[index].templatePresentation
            assertEquals(agentType.displayName, presentation.text)
            // 厂商名放描述里走状态栏提示，不再单独占一行
            assertEquals(agentType.vendor, presentation.description)
            assertSame(AgentIcons.forAgent(agentType), presentation.icon)
        }
    }
}
