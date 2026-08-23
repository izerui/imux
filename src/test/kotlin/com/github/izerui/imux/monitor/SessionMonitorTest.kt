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

    /**
     * 恢复 pi 标签必须等端点算好，所有平台都一样——这是改动前就有的行为。
     */
    @Test
    fun `恢复 pi 标签在任何平台都要等端点`() {
        val saved = listOf(AgentType.PI.cli)

        assertEquals(true, restoreNeedsReportEndpoint(saved, isWindows = true))
        assertEquals(true, restoreNeedsReportEndpoint(saved, isWindows = false))
    }

    /**
     * Windows 上恢复 codex 标签同样要等。
     *
     * `PiReportEndpoint.current()` 的契约是「绝不等待」，而恢复恰好发生在内置 HTTP
     * 服务还没起来的那几百毫秒里。不等的话恢复出来的 codex 进程拿不到
     * `IMUX_REPORT_URL` / `IMUX_TOKEN`，**这个标签一辈子不上报**；而 `-c` 照旧注入，
     * 用户照样被 codex 那屏「Hooks need review」挡一次，却什么都换不到。
     * IDE 重启后恢复标签正是最常见的场景。
     */
    @Test
    fun `Windows 上恢复 codex 标签也要等端点`() {
        assertEquals(true, restoreNeedsReportEndpoint(listOf(AgentType.CODEX.cli), isWindows = true))
    }

    /**
     * 非 Windows 上 codex 走 `lsof` / `/proc`，不需要端点——不能为了对称白等一次
     * `BuiltInServerManager.waitForStart()`，那会拖慢 macOS 与 Linux 的启动恢复。
     */
    @Test
    fun `非 Windows 上恢复 codex 标签不等端点`() {
        assertFalse(restoreNeedsReportEndpoint(listOf(AgentType.CODEX.cli), isWindows = false))
    }

    @Test
    fun `只有 claude 标签时任何平台都不等端点`() {
        val saved = listOf(AgentType.CLAUDE.cli)

        assertFalse(restoreNeedsReportEndpoint(saved, isWindows = true))
        assertFalse(restoreNeedsReportEndpoint(saved, isWindows = false))
        assertFalse(restoreNeedsReportEndpoint(emptyList(), isWindows = true))
    }

    /** 混着来时只要有一个需要就得等。 */
    @Test
    fun `混合标签里有一个需要端点就要等`() {
        assertEquals(
            true,
            restoreNeedsReportEndpoint(
                listOf(AgentType.CLAUDE.cli, AgentType.CODEX.cli),
                isWindows = true,
            ),
        )
        assertEquals(
            true,
            restoreNeedsReportEndpoint(
                listOf(AgentType.CLAUDE.cli, AgentType.PI.cli),
                isWindows = false,
            ),
        )
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
