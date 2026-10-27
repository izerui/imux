package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.SourceCode
import com.github.izerui.imux.icons.AgentIcons
import com.intellij.icons.AllIcons
import com.intellij.ui.RowIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTreePhoneMarkerTest {
    @Test
    fun `未连手机时图标不变`() {
        assertSame(AllIcons.Ide.GrayDot, withPhoneMarker(AllIcons.Ide.GrayDot, phoneConnected = false))
        assertNull(withPhoneMarker(null, phoneConnected = true))
    }

    @Test
    fun `连着手机时在状态图标后追加手机标记`() {
        val icon = withPhoneMarker(AllIcons.Ide.GrayDot, phoneConnected = true) as RowIcon
        assertEquals(2, icon.iconCount)
        assertSame(AllIcons.Ide.GrayDot, icon.getIcon(0))
        assertSame(AgentIcons.phone, icon.getIcon(1))
    }

    @Test
    fun `会话树在装饰变化时重载，并按标签询问手机连接`() {
        val tree = SourceCode("src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt").normalized
        assertTrue(tree.contains("SessionDecorationsListener.TOPIC"))
        assertTrue(tree.contains("SessionDecorations.phoneConnected(project,"))
        assertTrue(tree.contains("withPhoneMarker("))
    }
}
