package com.github.izerui.imux.bridge

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBridgeServiceSourceTest {
    private val code = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/TerminalBridgeService.kt")

    @Test
    fun `start 用原子比较交换防止重复订阅`() {
        assertTrue(code.normalized.contains("private val started = AtomicBoolean(false)"))
        val body = code.compact(code.bodyAfter("fun start()", '{'))
        assertTrue(
            "start() 必须以 compareAndSet 开头",
            body.startsWith("{if(!started.compareAndSet(false,true))return")
        )
        assertFalse(code.normalized.contains("started = true"))
    }

    @Test
    fun `读取、结算与等待提示都交给标签轮询器（同一标签同一把锁），上一轮还没发完的标签这轮跳过`() {
        val start = code.compact(code.bodyAfter("fun start()", '{'))
        assertTrue(start.contains("valmark=tails.mark(tabId)scope.launch(Dispatchers.IO){poller.completed(tabId,mark)}"))
        assertTrue(start.contains("scope.launch(Dispatchers.IO){poller.waiting(tabId,reason)}"))
        assertTrue(start.contains("if(poller.isBusy(tabId))continue"))
        assertTrue(start.contains("poller.poll(tabId)"))
    }

    @Test
    fun `项目存活期间持续轮询绑定会话`() {
        val start = code.compact(code.bodyAfter("fun start()", '{'))
        assertTrue(start.contains("while(isActive){if(!project.isDisposed){for(bindinginbindingsInProject()){"))
        assertFalse(start.contains("BridgeSettings"))
    }

    @Test
    fun `标签不见了先等一会儿再断开，退出与关项目期间不断开，自动接回时告诉手机`() {
        val source = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/TerminalBridgeService.kt")
        val code = source.normalized
        assertTrue("释放走延迟复核", code.contains("TabChange.Release -> scheduleRelease(binding)"))
        val schedule = source.bodyAfter("private fun scheduleRelease(binding: Binding)", '{')
        assertTrue(schedule.contains("delay(RELEASE_GRACE_MS)"))
        assertTrue(
            "等待后关闭中或已销毁就不断开",
            schedule.contains("if (projectClosing || project.isDisposed) return@withContext")
        )
        assertTrue("保存前就记下项目在关闭", code.contains("override fun projectClosingBeforeSave(project: Project)"))
        assertTrue(
            "确定退出时记下，不用可被取消的 appClosing",
            code.contains("override fun appWillBeClosed(isRestart: Boolean)") && !code.contains("override fun appClosing()")
        )
        assertTrue(
            "自动接回后通知手机",
            code.contains("hub.bindings.reattach(binding.robotId, change.tabId)?.let(::reattached)")
        )
        assertTrue(
            source.bodyAfter("private fun reattached(binding: Binding)", '{').contains("hub.router.onReattached(")
        )
    }
}
