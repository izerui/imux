package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.TerminalBridgeService
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.terminal.TerminalHost
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class TerminalSessionAvailabilityTest {
    private val target = SessionTarget("/closed-project", "tab", AgentType.CLAUDE, "session")

    @Test
    fun `后台恢复标签未启动时不可用且运行后变为可用并在退出后离线`() {
        val state = MutableStateFlow<TerminalViewSessionState>(TerminalViewSessionState.NotStarted)
        val view = Proxy.newProxyInstance(TerminalView::class.java.classLoader, arrayOf(TerminalView::class.java)) { _, method, _ ->
            when (method.name) {
                "getSessionState" -> state
                else -> error("可用性检查不得启动终端或发送内容：${method.name}")
            }
        } as TerminalView
        val owner = project(open = true, disposed = false, mutableListOf())
        val lookedUp = mutableListOf<SessionTarget>()
        val port = ImuxTerminalSessionPort(
            viewAt = { requestedProject, requestedTarget ->
                assertTrue(requestedProject === owner)
                lookedUp += requestedTarget
                view
            },
            projectAt = { owner },
        )
        assertFalse("存在后台标签并不表示 CLI 已运行", port.isAvailable(target))
        state.value = TerminalViewSessionState.Running
        assertTrue("实际运行后才允许连接", port.isAvailable(target))
        state.value = TerminalViewSessionState.Terminated
        assertFalse("退出后不再允许连接", port.isAvailable(target))
        assertEquals(listOf(target, target, target), lookedUp)
    }

    @Test
    fun `已关闭工程没有匹配项目时会话不可用`() {
        val lookups = mutableListOf<String>()
        val port = ImuxTerminalSessionPort { path -> lookups += path; null }
        assertFalse(port.isAvailable(target))
        assertEquals(listOf("/closed-project"), lookups)
    }

    @Test
    fun `项目仍在列表但已经关闭时不查询终端服务`() {
        val lookups = mutableListOf<Class<*>>()
        val port = ImuxTerminalSessionPort { project(open = false, disposed = false, lookups) }
        assertFalse(port.isAvailable(target))
        assertEquals(emptyList<Class<*>>(), lookups)
    }

    @Test
    fun `项目已销毁时不查询终端服务`() {
        val lookups = mutableListOf<Class<*>>()
        val port = ImuxTerminalSessionPort { project(open = true, disposed = true, lookups) }
        assertFalse(port.isAvailable(target))
        assertEquals(emptyList<Class<*>>(), lookups)
    }

    @Test
    fun `项目重新打开但终端尚未创建时仍显示不可用且不创建服务`() {
        val lookups = mutableListOf<Class<*>>()
        val port = ImuxTerminalSessionPort { project(open = true, disposed = false, lookups) }
        assertFalse(port.isAvailable(target))
        assertEquals(listOf(TerminalBridgeService::class.java, TerminalHost::class.java), lookups)
    }

    private fun project(open: Boolean, disposed: Boolean, lookups: MutableList<Class<*>>): Project =
        Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { proxy, method, args ->
            when (method.name) {
                "isOpen" -> open
                "isDisposed" -> disposed
                "getServiceIfCreated" -> {
                    lookups += args!![0] as Class<*>
                    null
                }
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "availability-test-project"
                else -> error("状态查询不应调用 ${method.name}")
            }
        } as Project
}
