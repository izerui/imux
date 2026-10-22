package com.github.izerui.imux.clarification

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `取消与关闭清理都先作废本轮再取消任务`() {
        val cancel = source.bodyAfter("internal fun cancel(", '{')
        val cancelledIdx = cancel.indexOf(".cancelled()")
        val cancelJobIdx = cancel.indexOf("?.cancel()")
        assertTrue("cancel() 必须先调 cancelled()", cancelledIdx >= 0 && cancelledIdx < cancelJobIdx)

        val release = source.bodyAfter("internal fun releaseOwner(", '{')
        val relCancelledIdx = release.indexOf(".cancelled()")
        assertTrue("releaseOwner() 必须先调 cancelled()", relCancelledIdx >= 0)
        // releaseOwner 通过 discard 间接取消任务，自身不含 ?.cancel()
    }

    @Test
    fun `生成结果不先行取消在途任务`() {
        val finish = source.bodyAfter("internal fun finish(", '{')
        assertTrue("finish() 必须调用 beginResult(", finish.contains("beginResult("))
        val beginIdx = finish.indexOf("beginResult(")
        val cancelIdx = finish.indexOf("?.cancel()")
        assertTrue("finish() 在 beginResult 之前不能有 ?.cancel()", cancelIdx < 0 || cancelIdx > beginIdx)
    }

    @Test
    fun `launch 助手在 IO 调度器上运行`() {
        val launchBody = source.bodyAfter("private fun launch(", '{')
        assertTrue(launchBody.contains("Dispatchers.IO"))
    }

    @Test
    fun `关闭标签页时结果页与发送中的需求先通知再丢弃`() {
        val release = source.bodyAfter("internal fun releaseOwner(", '{')
        val read = release.indexOf("undeliveredRequirement(")
        val discard = release.indexOf("discard(")
        val notify = release.indexOf("notifyUndelivered")
        assertTrue("丢弃之前读出尚未送达的需求", read >= 0 && read < discard)
        assertTrue("丢弃后通知并提供复制", notify > discard)
    }

    @Test
    fun `任务登记在流程当前的 key 下且与迁移同锁`() {
        val register = source.bodyAfter("private fun register(", '{')
        assertTrue("取流程当前所在的 key", register.contains("currentKeyOf(flow) ?: sessionKey"))
        assertTrue("登记与迁移同锁", register.contains("synchronized(jobs)"))
        val migrate = source.bodyAfter("private fun migrate(", '{')
        assertTrue("迁移在同一把锁内移动任务", migrate.contains("synchronized(jobs) {") && migrate.substringAfter("synchronized(jobs) {").contains("jobs.remove(from)"))
        assertTrue("IO 任务经 register 登记", source.bodyAfter("private fun launch(", '{').contains("register(sessionKey, flow)"))
        assertTrue("发送任务经 register 登记", source.bodyAfter("internal fun send(", '{').contains("register(sessionKey, flow)"))
    }

    @Test
    fun `会话记录是否存在在 IO 线程上判断`() {
        val check = source.bodyAfter("internal fun checkBackground(", '{')
        assertTrue(check.contains("Dispatchers.IO"))
        assertTrue(check.indexOf("Files.isRegularFile(") > check.indexOf("Dispatchers.IO"))
    }

    @Test
    fun `restart 先作废流程再取消任务`() {
        val restart = source.bodyAfter("internal fun restart(", '{')
        val cancelledIdx = restart.indexOf(".cancelled()")
        val cancelJobIdx = restart.indexOf("?.cancel()")
        assertTrue("restart() 必须先调 cancelled()", cancelledIdx >= 0 && cancelledIdx < cancelJobIdx)
        assertTrue("restart() 创建新流程", restart.contains("newFlow("))
        assertTrue("restart() 让对话框绑定新流程", restart.contains("rebind("))
    }

    @Test
    fun `restart 的新流程保存到 flows 映射`() {
        val restart = source.bodyAfter("internal fun restart(", '{')
        assertTrue("新流程替换旧流程", restart.contains("flows[sessionKey] = newFlow"))
    }

    @Test
    fun `通知里的复制使用 CopyPasteManager`() {
        val notify = source.bodyAfter("private fun notifyUndelivered(", '{')
        assertTrue(notify.contains("CopyPasteManager.getInstance().setContents("))
        assertFalse(source.normalized.contains("systemClipboard"))
    }

    @Test
    fun `结果页的需求尚未送达`() {
        assertEquals("REQ", undeliveredRequirement(ClarificationState.Result("REQ")))
    }

    @Test
    fun `发送中的需求尚未送达`() {
        assertEquals("REQ", undeliveredRequirement(ClarificationState.Sending("REQ", waitingForTurn = true)))
    }

    @Test
    fun `调查中与提问页没有待送达的需求`() {
        assertNull(undeliveredRequirement(ClarificationState.Investigating(null, 0)))
        assertNull(undeliveredRequirement(ClarificationState.Asking(listOf(Question("d1", "Q", "", "", emptyList())), 0)))
    }
}
