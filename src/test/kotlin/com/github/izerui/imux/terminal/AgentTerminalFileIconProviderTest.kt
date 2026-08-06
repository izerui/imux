package com.github.izerui.imux.terminal

import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.model.AgentType
import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.testFramework.LightVirtualFile
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

    /**
     * 状态图标**取代**品牌图标，而不是并排挂在它左边。
     *
     * 并排的话，标签页会在忙碌时变宽、闲下来又缩回去，标题跟着左右跳。
     * 而状态本就是短暂的，品牌图标一直都在，谁该让位很清楚。
     */
    @Test
    fun `忙碌与未读时状态图标取代品牌图标`() {
        val brand = AgentIcons.forAgent(AgentType.CLAUDE)
        val running = terminalTabIcon(AgentType.CLAUDE, running = true, unread = true)
        val unread = terminalTabIcon(AgentType.CLAUDE, running = false, unread = true)
        val idle = terminalTabIcon(AgentType.CLAUDE, running = false, unread = false)

        // 忙碌优先于未读：还在跑就还没有「读完」这回事
        assertSame(AnimatedIcon.Default.INSTANCE, running)
        assertSame(AllIcons.General.Modified, unread)
        assertSame(brand, idle)
    }

    /** 三态同宽，标签页标题才不会随状态左右跳。 */
    @Test
    fun `三种状态的图标宽度一致`() {
        val widths = listOf(
            terminalTabIcon(AgentType.CLAUDE, running = true, unread = false),
            terminalTabIcon(AgentType.CLAUDE, running = false, unread = true),
            terminalTabIcon(AgentType.CLAUDE, running = false, unread = false),
        ).map { it.iconWidth }

        assertEquals(listOf(16, 16, 16), widths)
    }
}
