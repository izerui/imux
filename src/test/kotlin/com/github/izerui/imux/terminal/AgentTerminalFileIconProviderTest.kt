package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class AgentTerminalFileIconProviderTest {

    @Test
    fun `Claude 与 Codex 使用不同的 16 像素图标`() {
        val claude = AgentTerminalIcons.forAgent(AgentType.CLAUDE)
        val codex = AgentTerminalIcons.forAgent(AgentType.CODEX)

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
}
