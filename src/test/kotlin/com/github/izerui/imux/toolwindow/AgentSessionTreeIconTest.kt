package com.github.izerui.imux.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.util.ui.EmptyIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import javax.swing.JTree

class AgentSessionTreeIconTest {

    @Test
    fun `运行中会话显示平台默认加载动画`() {
        val icon = sessionStatusIcon(running = true, unread = true)

        assertSame(AnimatedIcon.Default.INSTANCE, icon)
    }

    @Test
    fun `未读会话只显示原有未读标记`() {
        val icon = sessionStatusIcon(running = false, unread = true)

        assertSame(AllIcons.General.Modified, icon)
    }

    @Test
    fun `普通会话使用空图标固定标题起始位置`() {
        val icon = sessionStatusIcon(running = false, unread = false)

        assertSame(EmptyIcon.ICON_16, icon)
    }

    @Test
    fun `树渲染器允许加载动画自动刷新`() {
        val tree = JTree()

        enableRendererAnimation(tree)

        assertEquals(
            true,
            tree.getClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED),
        )
    }
}
