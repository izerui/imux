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
    fun `标签不见了延迟释放绑定且关闭期间保留绑定供恢复`() {
        val source = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/TerminalBridgeService.kt")
        val code = source.normalized
        assertTrue("释放走延迟复核", code.contains("TabChange.Release -> scheduleRelease(binding)"))
        val schedule = source.bodyAfter("private fun scheduleRelease(binding: Binding)", '{')
        assertTrue(schedule.contains("delay(RELEASE_GRACE_MS)"))
        assertTrue(
            "等待后关闭中或已销毁就不释放绑定",
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

    @Test
    fun `确定关闭项目和退出应用分别调用同一个关闭通知入口`() {
        val projectClose = code.compact(code.bodyAfter("override fun projectClosing(project: Project)", '{'))
        assertTrue(projectClose.contains("if(project===this@TerminalBridgeService.project)notifyClosing()"))
        val appClose = code.compact(code.bodyAfter("override fun appWillBeClosed(isRestart: Boolean)", '{'))
        assertTrue(appClose.contains("notifyClosing()"))
        val beforeSave = code.bodyAfter("override fun projectClosingBeforeSave(project: Project)", '{')
        assertFalse("保存前只标记，不提前发出断开提示", beforeSave.contains("notifyClosing()"))
    }

    @Test
    fun `关闭通知先取消项目任务并预取署名再同步等待发送`() {
        val closing = code.compact(code.bodyAfter("private fun notifyClosing()", '{'))
        assertTrue(closing.startsWith("{projectClosing=truescope.cancel()"))
        assertTrue(closing.contains("closeNotifier.notifyOnce(bindingsInProject().map{ClosingBinding(it,SessionTitles.of(it),SessionTitles.label(it))})"))
        assertTrue(code.normalized.contains("hub.router.onSuspended(notice.binding, notice.title, notice.label)"))
        val reattach = code.compact(code.bodyAfter("private fun reattached(binding: Binding)", '{'))
        assertTrue(reattach.startsWith("{if(projectClosing||project.isDisposed)return"))
        assertTrue(reattach.contains("notifyConnection{hub.router.onReattached("))
        val notify = code.compact(code.bodyAfter("internal fun notifyConnection(block: suspend () -> Unit)", '{'))
        assertTrue(notify.startsWith("{if(projectClosing||project.isDisposed)return"))
        assertTrue(notify.contains("scope.launch{block()}"))
        val selected = code.compact(code.normalized)
        assertTrue(selected.contains("hub.bindings.snapshot().filter{it.target.projectPath==projectPath}"))
    }
}
