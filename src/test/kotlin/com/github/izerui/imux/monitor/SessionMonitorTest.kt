package com.github.izerui.imux.monitor

import com.github.izerui.imux.SourceCode
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
import org.junit.Assert.assertTrue
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
        val report =
            PiSessionReport(
                PiReportType.SESSION_START,
                "imux-1",
                "session-1",
                "/Users/demo/project-a",
            )

        assertEquals(true, piReportBelongsToProject(report, "/Users/demo/project-a"))
        assertFalse(piReportBelongsToProject(report, "/Users/demo/project-b"))
    }

    @Test
    fun `pi 启动上报触发扫描且扫描后立即刷新运行态`() {
        val source = SourceCode("src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt")
        val reportBody = source.bodyAfter("fun onPiSessionReported(report: PiSessionReport)", '{')
        val refreshWorkerBody = source.bodyAfter("private fun startRefreshWorker()", '{')

        assertTrue(
            "session_start 必须主动扫描，否则新会话仍要等最多 3 秒才进入模型",
            source.compact(reportBody).contains(
                source.compact(
                    """
                    PiReportType.SESSION_START -> {
                        val drifts = driftOf(host.openTabsByTabId(), listOf(LiveTab(report.tabId, report.sessionId)))
                        if (drifts.isNotEmpty()) applyDrifts(drifts)
                        refresh()
                    }
                    """.trimIndent(),
                ),
            ),
        )
        assertTrue(
            "扫描挂上 TurnWatcher 后必须当场重算运行态，否则标记还要再等下一拍",
            source.compact(refreshWorkerBody).contains(
                source.compact(
                    "withContext(Dispatchers.EDT) { model.applyScan(scanned) } checkCompletedTurns()",
                ),
            ),
        )
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
     * codex 在**任何**平台上都不等端点。
     *
     * 它一条平台都不靠上报：macOS 走 `lsof`、Linux 走 `/proc` 读它持有的会话文件
     * 句柄，Windows 读它自己写的运行态 sqlite（`CodexRuntimeIndex`）。
     * 白等一次 `BuiltInServerManager.waitForStart()` 只会拖慢启动恢复，
     * 而那正是 IDE 重启后最常见的场景。
     *
     * Windows 那一半曾经返回 true（codex 一度靠 `-c hooks.SessionStart` 注入的
     * hook 上报），随整套 hook 机制一起删除。
     */
    @Test
    fun `任何平台上恢复 codex 标签都不等端点`() {
        assertFalse(restoreNeedsReportEndpoint(listOf(AgentType.CODEX.cli), isWindows = true))
        assertFalse(restoreNeedsReportEndpoint(listOf(AgentType.CODEX.cli), isWindows = false))
    }

    @Test
    fun `只有 claude 标签时任何平台都不等端点`() {
        val saved = listOf(AgentType.CLAUDE.cli)

        assertFalse(restoreNeedsReportEndpoint(saved, isWindows = true))
        assertFalse(restoreNeedsReportEndpoint(saved, isWindows = false))
        assertFalse(restoreNeedsReportEndpoint(emptyList(), isWindows = true))
    }

    /** 混着来时只要有一个需要就得等——而只有 pi 需要。 */
    @Test
    fun `混合标签里有一个需要端点就要等`() {
        assertEquals(
            true,
            restoreNeedsReportEndpoint(
                listOf(AgentType.CLAUDE.cli, AgentType.PI.cli),
                isWindows = false,
            ),
        )
        assertEquals(
            true,
            restoreNeedsReportEndpoint(
                listOf(AgentType.CLAUDE.cli, AgentType.PI.cli),
                isWindows = true,
            ),
        )
        assertFalse(
            "claude 与 codex 都不靠上报，混在一起也不该等",
            restoreNeedsReportEndpoint(
                listOf(AgentType.CLAUDE.cli, AgentType.CODEX.cli),
                isWindows = true,
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

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
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
