package com.github.izerui.imux.bridge

import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class BridgeProjectCloseListenerTest {
    @Test
    fun `保存前取消关闭不停止项目任务且之后仍能正常关闭`() {
        val owner = project()
        val scope = CoroutineScope(Job())
        var closeCount = 0
        val listener = bridgeProjectCloseListener(owner) {
            closeCount++
            scope.coroutineContext[Job]!!.cancel()
        }
        try {
            listener.projectClosingBeforeSave(owner)
            assertEquals("可取消阶段不能发送断开通知", 0, closeCount)
            assertTrue(scope.isActive)
            var executed = false
            scope.launch(start = CoroutineStart.UNDISPATCHED) { executed = true }
            assertTrue("取消关闭后项目任务仍可执行", executed)
            listener.projectClosing(owner)
            assertEquals(1, closeCount)
            assertFalse(scope.isActive)
        } finally {
            scope.coroutineContext[Job]!!.cancel()
        }
    }

    @Test
    fun `关闭另一工程不触发当前工程关闭处理`() {
        val owner = project()
        var closed = false
        val listener = bridgeProjectCloseListener(owner) { closed = true }
        listener.projectClosing(project())
        assertFalse(closed)
        listener.projectClosing(owner)
        assertTrue(closed)
    }

    private fun project(): Project =
        Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
            error("关闭监听只应比较项目身份，不应调用 ${method.name}")
        } as Project
}
