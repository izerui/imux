package com.github.izerui.imux.bridge.terminal

import com.intellij.terminal.frontend.view.TerminalViewSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStateObserverTest {
    @Test
    fun `启动与退出触发刷新且重复对账不叠加订阅`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = MutableStateFlow<TerminalViewSessionState>(TerminalViewSessionState.NotStarted)
        var updates = 0
        val observer = SessionStateObserver(scope) { updates++ }
        try {
            observer.update(mapOf("tab" to state))
            assertEquals(1, updates)
            observer.update(mapOf("tab" to state))
            assertEquals(1, state.subscriptionCount.value)
            assertEquals(1, updates)
            state.value = TerminalViewSessionState.Running
            assertEquals(2, updates)
            state.value = TerminalViewSessionState.Terminated
            assertEquals(3, updates)
            observer.update(emptyMap())
            assertEquals(0, state.subscriptionCount.value)
            state.value = TerminalViewSessionState.Running
            assertEquals("已关闭标签不再刷新界面", 3, updates)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `替换状态源时摘除旧订阅且项目取消后新订阅也结束`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val old = MutableStateFlow<TerminalViewSessionState>(TerminalViewSessionState.NotStarted)
        val replacement = MutableStateFlow<TerminalViewSessionState>(TerminalViewSessionState.NotStarted)
        var updates = 0
        val observer = SessionStateObserver(scope) { updates++ }
        try {
            observer.update(mapOf("tab" to old))
            observer.update(mapOf("tab" to replacement))
            assertEquals(0, old.subscriptionCount.value)
            assertEquals(1, replacement.subscriptionCount.value)
            old.value = TerminalViewSessionState.Running
            assertEquals(2, updates)
            scope.cancel()
            assertEquals(0, replacement.subscriptionCount.value)
            replacement.value = TerminalViewSessionState.Running
            assertEquals(2, updates)
        } finally {
            scope.cancel()
        }
    }
}
