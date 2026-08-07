package com.github.izerui.imux.terminal

import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.model.AgentType
import com.intellij.icons.AllIcons
import com.intellij.ui.RowIcon
import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    /** 四种组合，逐个取一遍。 */
    private fun tabIcons(agentType: AgentType = AgentType.CLAUDE) = listOf(
        terminalTabIcon(agentType, running = false, unread = false),
        terminalTabIcon(agentType, running = true, unread = false),
        terminalTabIcon(agentType, running = false, unread = true),
        terminalTabIcon(agentType, running = true, unread = true),
    )

    /**
     * 状态修饰品牌图标，而不是取代它。
     *
     * 取代的话，标签页一忙碌就只剩一个转圈，认不出这是 Claude 还是 Codex 的会话，
     * 也认不出它和旁边的普通编辑器标签有什么区别。
     */
    @Test
    fun `任何状态下都能区分是哪个 agent`() {
        tabIcons(AgentType.CLAUDE).zip(tabIcons(AgentType.CODEX)).forEach { (claude, codex) ->
            assertNotSame(claude, codex)
        }
    }

    /** 四种组合各有各的样子，否则等于没标记。 */
    @Test
    fun `四种状态组合互不相同`() {
        val icons = tabIcons()

        assertEquals(icons.size, icons.distinct().size)
    }

    /** 未读标记与会话列表用同一个常量，两处样式才一致。 */
    @Test
    fun `未读标记就是列表里那一个且跟在品牌图标后面`() {
        val unread = terminalTabIcon(AgentType.CLAUDE, running = false, unread = true) as RowIcon

        assertSame(AgentIcons.forAgent(AgentType.CLAUDE), unread.getIcon(0))
        assertSame(AllIcons.General.Modified, unread.getIcon(1))
    }

    /** 没有未读时不留空位：图标就是品牌图标本身，不多占一格。 */
    @Test
    fun `没有未读时不占位`() {
        val idle = terminalTabIcon(AgentType.CLAUDE, running = false, unread = false)

        assertSame(AgentIcons.forAgent(AgentType.CLAUDE), idle)
        assertEquals(16, idle.iconWidth)
    }

    /** 动画只建一次：每次新建会让呼吸从头开始，看着像卡住。 */
    @Test
    fun `同一状态复用同一个实例`() {
        assertSame(
            terminalTabIcon(AgentType.CLAUDE, running = true, unread = false),
            terminalTabIcon(AgentType.CLAUDE, running = true, unread = false),
        )
    }

    /** 忙碌不改变尺寸：呼吸只动亮度，标题不会一忙起来就横移。 */
    @Test
    fun `忙碌与否宽度相同`() {
        assertEquals(
            terminalTabIcon(AgentType.CLAUDE, running = false, unread = false).iconWidth,
            terminalTabIcon(AgentType.CLAUDE, running = true, unread = false).iconWidth,
        )
        assertEquals(
            terminalTabIcon(AgentType.CLAUDE, running = false, unread = true).iconWidth,
            terminalTabIcon(AgentType.CLAUDE, running = true, unread = true).iconWidth,
        )
    }

    /** 高度始终一致，否则标签页会随状态忽高忽低。 */
    @Test
    fun `四种状态组合的图标等高`() {
        assertEquals(1, tabIcons().map { it.iconHeight }.distinct().size)
    }
}
