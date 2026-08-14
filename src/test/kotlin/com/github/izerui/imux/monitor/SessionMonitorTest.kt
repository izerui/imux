package com.github.izerui.imux.monitor

import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.PiReportType
import com.github.izerui.imux.session.PiSessionReport
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

class SessionMonitorTest {

    @Test
    fun `model 变化会透传给 monitor 监听器`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val monitor = SessionMonitor(testProject(), scope)
        val parentDisposable = Disposer.newDisposable()
        var notified = 0
        monitor.addListener(parentDisposable) { notified++ }

        monitor.model.registerPending(AgentType.CLAUDE)

        assertEquals(1, notified)
        Disposer.dispose(parentDisposable)
        scope.cancel()
    }

    @Test
    fun `初始化失败后启动守卫允许重试`() {
        val started = AtomicBoolean(false)
        var attempts = 0

        assertThrows(IllegalStateException::class.java) {
            started.runOnceResetOnFailure {
                attempts++
                error("初始化失败")
            }
        }

        assertEquals(1, attempts)
        assertFalse(started.get())

        started.runOnceResetOnFailure { attempts++ }

        assertEquals(2, attempts)
        assertFalse(started.runOnceResetOnFailure { attempts++ })
        assertEquals(2, attempts)
    }

    @Test
    fun `pi 上报只属于 cwd 完全相同的项目`() {
        val report = PiSessionReport(
            PiReportType.SESSION_START,
            "imux-1",
            "session-1",
            "/Users/demo/project-a",
        )

        assertEquals(true, piReportBelongsToProject(report, "/Users/demo/project-a"))
        assertFalse(piReportBelongsToProject(report, "/Users/demo/project-b"))
    }

    private fun testProject(): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getBasePath" -> "/tmp/imux-test-project"
                "isDisposed" -> false
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "TestProject"
                else -> defaultValue(method.returnType)
            }
        } as Project

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
