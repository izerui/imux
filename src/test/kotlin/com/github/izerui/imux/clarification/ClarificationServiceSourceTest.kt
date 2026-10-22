package com.github.izerui.imux.clarification

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClarificationServiceSourceTest {
    private val source = SourceCode("src/main/kotlin/com/github/izerui/imux/clarification/ClarificationService.kt")

    @Test
    fun `单次调用沿用轨迹的调用方式与超时`() {
        assertTrue(source.normalized.contains("oneShotAgentInvocation("))
        assertTrue(source.normalized.contains("runOneShotAgent("))
        assertTrue(source.normalized.contains("AGENT_TIMEOUT_SECONDS = 600L"))
    }

    @Test
    fun `发送复用结对编程的括号粘贴加回车`() {
        assertTrue(source.normalized.contains("trySendPeerFeedback("))
        assertFalse(source.normalized.contains("tryStagePeerFeedback("))
    }

    @Test
    fun `发送时机读取运行集合与 Claude 等待确认状态`() {
        val busy = source.bodyAfter("private fun isMainSessionBusy(", '{')
        assertTrue(busy.contains("runningIds"))
        assertTrue(busy.contains("isWaiting"))
    }

    @Test
    fun `背景快照在后台线程读取`() {
        val start = source.bodyAfter("internal fun start(", '{')
        val launch = start.indexOf("launch(")
        val read = start.indexOf("latestConversationOf(")
        assertTrue("背景快照必须在 launch 的 IO 任务里读取", launch >= 0 && read > launch)
    }

    @Test
    fun `项目关闭时服务关闭打开的窗口`() {
        val dispose = source.bodyAfter("override fun dispose()", '{')
        assertTrue(dispose.contains(".close("))
    }

    @Test
    fun `会话 key 迁移时移动状态而不取消在途一轮`() {
        val migrate = source.normalized.substringAfter("private fun migrate(").substringBefore("override fun dispose")
        assertTrue(migrate.contains("flows.remove(from)"))
        assertFalse(migrate.contains(".cancel("))
    }
}
