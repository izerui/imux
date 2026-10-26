package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.Binding
import com.github.izerui.imux.bridge.core.RobotId
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Test

class BindingLifecycleTest {
    private val binding = Binding(RobotId("r"), SessionTarget("/p", "tab-1", AgentType.CLAUDE, "s1"), epoch = 1)

    @Test
    fun `标签仍开着则保持`() {
        assertEquals(TabChange.Keep, tabChangeFor(binding, mapOf("tab-1" to "s1"), setOf("tab-1"), projectClosing = false))
    }

    @Test
    fun `同一会话以新标签打开则重新挂接`() {
        assertEquals(
            TabChange.Reattach("tab-9"),
            tabChangeFor(binding, mapOf("tab-9" to "s1"), emptySet(), projectClosing = false),
        )
    }

    @Test
    fun `见过的标签消失且项目未在关闭则释放`() {
        assertEquals(TabChange.Release, tabChangeFor(binding, emptyMap(), setOf("tab-1"), projectClosing = false))
    }

    @Test
    fun `项目关闭中保持离线`() {
        assertEquals(TabChange.Keep, tabChangeFor(binding, emptyMap(), setOf("tab-1"), projectClosing = true))
    }

    @Test
    fun `本服务从未见过的标签不释放，等待恢复`() {
        assertEquals(TabChange.Keep, tabChangeFor(binding, emptyMap(), emptySet(), projectClosing = false))
    }

    @Test
    fun `新会话未落盘时不按 sessionId 误挂接`() {
        val pending = binding.copy(target = binding.target.copy(sessionId = null))
        assertEquals(TabChange.Release, tabChangeFor(pending, mapOf("tab-2" to "other"), setOf("tab-1"), projectClosing = false))
    }

    @Test
    fun `恢复的绑定在会话标签已打开时重新挂接`() {
        // 恢复的绑定 tabId 是占位符 "restored:s1"，不在 openTabs 中；
        // 但 sessionId 与已打开标签的 sessionKey 匹配，应触发 Reattach。
        val restored = Binding(RobotId("r"), SessionTarget("/p", "restored:s1", AgentType.CLAUDE, "s1"), epoch = 1)
        assertEquals(
            TabChange.Reattach("tab-new"),
            tabChangeFor(restored, mapOf("tab-new" to "s1"), emptySet(), projectClosing = false),
        )
    }
}
