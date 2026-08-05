package com.github.izerui.imux.terminal

import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.model.AgentType
import com.intellij.ui.AnimatedIcon
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.ui.EmptyIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AgentTerminalFileIconProviderTest {

    @Test
    fun `Claude 与 Codex 使用不同的 16 像素图标`() {
        val claude = AgentIcons.forAgent(AgentType.CLAUDE)
        val codex = AgentIcons.forAgent(AgentType.CODEX)

        assertNotSame(claude, codex)
        assertEquals(16, claude.iconWidth)
        assertEquals(16, claude.iconHeight)
        assertEquals(16, codex.iconWidth)
        assertEquals(16, codex.iconHeight)
    }

    @Test
    fun `忽略非 imux 虚拟文件`() {
        val icon = AgentTerminalFileIconProvider()
            .getIcon(LightVirtualFile("notes.txt"), 0, null)

        assertNull(icon)
    }

    @Test
    fun `标签在品牌图标前保留固定状态槽`() {
        val running = terminalTabIcon(AgentType.CLAUDE, running = true, unread = true)
        val unread = terminalTabIcon(AgentType.CLAUDE, running = false, unread = true)
        val idle = terminalTabIcon(AgentType.CLAUDE, running = false, unread = false)

        assertEquals(2, running.iconCount)
        assertSame(AnimatedIcon.Default.INSTANCE, running.getIcon(0))
        assertSame(AgentIcons.forAgent(AgentType.CLAUDE), running.getIcon(1))

        assertEquals(2, unread.iconCount)
        assertSame(com.intellij.icons.AllIcons.General.Modified, unread.getIcon(0))
        assertSame(AgentIcons.forAgent(AgentType.CLAUDE), unread.getIcon(1))

        assertEquals(2, idle.iconCount)
        assertSame(EmptyIcon.ICON_16, idle.getIcon(0))
        assertSame(AgentIcons.forAgent(AgentType.CLAUDE), idle.getIcon(1))
        assertEquals(running.iconWidth, idle.iconWidth)
        assertEquals(unread.iconWidth, idle.iconWidth)
    }
}
