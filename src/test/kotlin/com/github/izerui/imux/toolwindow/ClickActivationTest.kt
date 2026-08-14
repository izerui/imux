package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickActivationTest {
    @Test
    fun `单击模式下单击激活`() {
        assertTrue(shouldActivate(singleClickMode = true, clickCount = 1))
    }

    @Test
    fun `单击模式下双击的第二下不再激活`() {
        assertFalse(
            "双击会先后派发 clickCount 1 和 2，单击模式已在第一下激活过，第二下必须忽略，否则同一会话被打开两次",
            shouldActivate(singleClickMode = true, clickCount = 2),
        )
    }

    @Test
    fun `双击模式下单击不激活`() {
        assertFalse(shouldActivate(singleClickMode = false, clickCount = 1))
    }

    @Test
    fun `双击模式下双击激活`() {
        assertTrue(shouldActivate(singleClickMode = false, clickCount = 2))
    }

    @Test
    fun `复制文本包含清晰标注的会话类型和完整 id`() {
        assertEquals(
            "Session type: Claude Code\nSession ID: session-abc-123",
            sessionClipboardText(AgentType.CLAUDE, "session-abc-123"),
        )
    }
}
