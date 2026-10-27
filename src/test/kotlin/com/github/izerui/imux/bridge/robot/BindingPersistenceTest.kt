package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BindingPersistenceTest {
    @Test
    fun `只保存已有原生会话 id 的绑定`() {
        val snapshot =
            listOf(
                Binding(RobotId("a"), SessionTarget("/p", "tab-1", AgentType.CODEX, "s1"), 1),
                Binding(RobotId("b"), SessionTarget("/p", "tab-2", AgentType.CLAUDE, null), 2),
            )
        assertEquals(listOf(SavedBinding("a", "/p", "codex", "s1")), bindingsToSave(snapshot))
    }

    @Test
    fun `恢复时使用占位 tabId 并按 cli 还原 Agent`() {
        assertEquals(
            SessionTarget("/p", "restored:s1", AgentType.PI, "s1"),
            restoredTarget(SavedBinding("a", "/p", "pi", "s1")),
        )
        assertNull(restoredTarget(SavedBinding("a", "/p", "unknown-cli", "s1")))
    }
}
