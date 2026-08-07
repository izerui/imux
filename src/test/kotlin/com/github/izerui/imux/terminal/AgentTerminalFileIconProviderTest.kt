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

    @Test
    fun `空闲时就是品牌图标本身`() {
        assertSame(
            AgentIcons.forAgent(AgentType.CLAUDE),
            terminalTabIcon(AgentType.CLAUDE, running = false, unread = false),
        )
    }

    /**
     * 状态修饰品牌图标，而不是取代它。
     *
     * 取代的话，标签页一忙碌就只剩一个转圈，认不出这是 Claude 还是 Codex 的会话，
     * 也认不出它和旁边的普通编辑器标签有什么区别。
     */
    @Test
    fun `忙碌与未读时仍能区分是哪个 agent`() {
        assertNotSame(
            terminalTabIcon(AgentType.CLAUDE, running = true, unread = false),
            terminalTabIcon(AgentType.CODEX, running = true, unread = false),
        )
        assertNotSame(
            terminalTabIcon(AgentType.CLAUDE, running = false, unread = true),
            terminalTabIcon(AgentType.CODEX, running = false, unread = true),
        )
    }

    /** 三种状态各有各的样子，否则等于没标记。 */
    @Test
    fun `同一 agent 的三种状态互不相同`() {
        val running = terminalTabIcon(AgentType.CLAUDE, running = true, unread = false)
        val unread = terminalTabIcon(AgentType.CLAUDE, running = false, unread = true)
        val idle = terminalTabIcon(AgentType.CLAUDE, running = false, unread = false)

        assertNotSame(running, unread)
        assertNotSame(running, idle)
        assertNotSame(unread, idle)
    }

    /** 忙碌优先于未读：还在跑就还没有「读完」这回事。 */
    @Test
    fun `忙碌优先于未读`() {
        assertSame(
            terminalTabIcon(AgentType.CLAUDE, running = true, unread = false),
            terminalTabIcon(AgentType.CLAUDE, running = true, unread = true),
        )
    }

    /** 三态同宽，标签页标题才不会随状态左右跳。 */
    @Test
    fun `三种状态的图标宽高一致`() {
        val icons = listOf(
            terminalTabIcon(AgentType.CLAUDE, running = true, unread = false),
            terminalTabIcon(AgentType.CLAUDE, running = false, unread = true),
            terminalTabIcon(AgentType.CLAUDE, running = false, unread = false),
        )

        assertEquals(listOf(16, 16, 16), icons.map { it.iconWidth })
        assertEquals(listOf(16, 16, 16), icons.map { it.iconHeight })
    }
}
