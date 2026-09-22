package com.github.izerui.imux.monitor

import com.github.izerui.imux.SourceCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeleteSessionTest {
    @Test
    fun `有标签页时不论运行态都允许删除`() {
        assertFalse(isRunningWithoutTab(hasTab = true, inRunningIds = false, runtimeOccupied = false))
        assertFalse(isRunningWithoutTab(hasTab = true, inRunningIds = true, runtimeOccupied = false))
        assertFalse(isRunningWithoutTab(hasTab = true, inRunningIds = false, runtimeOccupied = true))
        assertFalse(isRunningWithoutTab(hasTab = true, inRunningIds = true, runtimeOccupied = true))
    }

    @Test
    fun `无标签页且不运行时允许删除`() {
        assertFalse(isRunningWithoutTab(hasTab = false, inRunningIds = false, runtimeOccupied = false))
    }

    @Test
    fun `无标签页且 inRunningIds 命中时禁止删除`() {
        assertTrue(isRunningWithoutTab(hasTab = false, inRunningIds = true, runtimeOccupied = false))
    }

    @Test
    fun `无标签页且 runtimeOccupied 命中时禁止删除`() {
        assertTrue(isRunningWithoutTab(hasTab = false, inRunningIds = false, runtimeOccupied = true))
    }

    @Test
    fun `无标签页且两个信号都命中时禁止删除`() {
        assertTrue(isRunningWithoutTab(hasTab = false, inRunningIds = true, runtimeOccupied = true))
    }

    @Test
    fun `deleteSession 委托 executeDelete 并传入运行态和关闭回调`() {
        val source = SourceCode("src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt")
        val body = source.bodyAfter("fun deleteSession(session: AgentSession): DeleteResult", '{')
        val compact = source.compact(body)

        assertTrue(
            "deleteSession 必须委托给 executeDelete",
            compact.contains("executeDelete("),
        )
        assertTrue(
            "必须传入 isRunningWithoutTab 参数",
            compact.contains("isRunningWithoutTab=isRunningWithoutTab(session.id)"),
        )
        assertTrue(
            "必须传入 closeTab 回调",
            compact.contains("closeTab=") && compact.contains("closeTabBySessionKey(session.id)"),
        )
    }

    @Test
    fun `executeDelete 按序检查运行态和标签关闭并返回区分结果`() {
        val source = SourceCode("src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt")
        val body = source.bodyAfter("fun executeDelete(", '{')
        val compact = source.compact(body)

        assertTrue(
            "无标签页运行态必须返回 RUNNING_WITHOUT_TAB",
            compact.contains("DeleteResult.RUNNING_WITHOUT_TAB"),
        )
        assertTrue(
            "标签关闭被拒绝时必须返回 CLOSE_REJECTED",
            compact.contains("DeleteResult.CLOSE_REJECTED"),
        )
        assertTrue(
            "受理删除必须返回 ACCEPTED",
            compact.contains("DeleteResult.ACCEPTED"),
        )
    }

    @Test
    fun `deleteSession 文件删除失败时弹通知且协程不可取消`() {
        val source = SourceCode("src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt")
        val body = source.bodyAfter("fun deleteSession(session: AgentSession): DeleteResult", '{')
        val compact = source.compact(body)

        assertTrue(
            "IO 线程中必须检查删除结果的 onFailure 并发通知",
            compact.contains("ioResult.onFailure") && compact.contains("notification.delete.failed"),
        )
        assertTrue(
            "launch 必须使用 NonCancellable，防止 scope 取消导致删除和刷新被跳过",
            compact.contains("launch(NonCancellable"),
        )
    }

    @Test
    fun `NonCancellable launch 在 scope 取消后仍然执行`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val executed = CountDownLatch(1)

        scope.cancel()

        scope.launch(NonCancellable) {
            executed.countDown()
        }

        assertTrue(
            "scope 取消后 NonCancellable launch 的协程体必须执行",
            executed.await(2, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `普通 launch 在 scope 取消后不执行`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var executed = false

        scope.cancel()

        scope.launch {
            executed = true
        }

        assertFalse(
            "scope 取消后普通 launch 的协程体不应执行（对照组）",
            executed,
        )
    }

    @Test
    fun `closeTabBySessionKey 在 closeFile 后验证文件已从 files 移除`() {
        val source = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt")
        val body = source.bodyAfter("fun closeTabBySessionKey(key: String): Boolean", '{')
        val compact = source.compact(body)

        assertTrue(
            "closeFile 之后必须检查 files 是否仍包含 key，不能无条件返回 true",
            compact.contains("closeFile(file)") && compact.contains("!files.containsKey(key)"),
        )
    }

    @Test
    fun `executeDelete 运行中无标签页时不调用关闭也不调用 onAccepted`() {
        var closeCalled = false
        var acceptedCalled = false
        val result = executeDelete(
            isRunningWithoutTab = true,
            closeTab = { closeCalled = true; true },
            onAccepted = { acceptedCalled = true },
        )
        assertEquals(DeleteResult.RUNNING_WITHOUT_TAB, result)
        assertFalse("运行中无标签页时不应调用 closeTab", closeCalled)
        assertFalse("运行中无标签页时不应调用 onAccepted", acceptedCalled)
    }

    @Test
    fun `executeDelete 关闭被拒绝时不调用 onAccepted`() {
        var acceptedCalled = false
        val result = executeDelete(
            isRunningWithoutTab = false,
            closeTab = { false },
            onAccepted = { acceptedCalled = true },
        )
        assertEquals(DeleteResult.CLOSE_REJECTED, result)
        assertFalse("关闭被拒绝时不应调用 onAccepted（不应清除未读）", acceptedCalled)
    }

    @Test
    fun `executeDelete 受理删除时调用 onAccepted 恰好一次`() {
        var acceptedCount = 0
        val result = executeDelete(
            isRunningWithoutTab = false,
            closeTab = { true },
            onAccepted = { acceptedCount++ },
        )
        assertEquals(DeleteResult.ACCEPTED, result)
        assertEquals("受理删除时 onAccepted 必须恰好调用一次", 1, acceptedCount)
    }

    @Test
    fun `deleteSession 通过 executeDelete 委托且 onAccepted 包含 clearUnread`() {
        val source = SourceCode("src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt")

        val deleteBody = source.bodyAfter("fun deleteSession(session: AgentSession): DeleteResult", '{')
        val deleteCompact = source.compact(deleteBody)
        assertTrue(
            "deleteSession 必须委托给 executeDelete",
            deleteCompact.contains("executeDelete("),
        )
        assertTrue(
            "onAccepted 回调中必须包含 clearUnread",
            deleteCompact.contains("clearUnread(session.id)"),
        )

        val closedBody = source.bodyAfter("fun sessionClosed(key: String)", '{')
        val closedCompact = source.compact(closedBody)
        assertFalse(
            "sessionClosed 不应调用 clearUnread——普通关标签页不应撤销未读标记",
            closedCompact.contains("clearUnread"),
        )
    }

    @Test
    fun `isRunningWithoutTab 方法同时检查 runningIds 和 runtime`() {
        val source = SourceCode("src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt")
        val compact = source.compact(source.normalized)

        assertTrue(
            "isRunningWithoutTab 必须将 runningIds 传入 inRunningIds",
            compact.contains("isRunningWithoutTab(") &&
                compact.contains("sessionIdinrunningIds"),
        )
        assertTrue(
            "isRunningWithoutTab 必须将 runtime[].isOccupied 传入 runtimeOccupied",
            compact.contains("runtime[sessionId]?.isOccupied==true"),
        )
    }
}
