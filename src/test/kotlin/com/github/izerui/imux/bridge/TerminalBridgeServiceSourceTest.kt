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
        assertFalse("保存前仍可能取消关闭，不能留下关闭标记", code.contains("override fun projectClosingBeforeSave(project: Project)"))
        assertTrue(
            "标签释放须等保存弹窗结束再复核，避免正常关闭工程时丢失绑定",
            source.compact(schedule).contains("withContext(Dispatchers.EDT+ModalityState.nonModal().asContextElement())"),
        )
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
        val start = code.compactArgs(code.bodyAfter("fun start()", '{'))
        assertTrue(start.contains("subscribe(ProjectCloseListener.TOPIC,bridgeProjectCloseListener(project,::notifyClosing))"))
        val appClose = code.compact(code.bodyAfter("override fun appWillBeClosed(isRestart: Boolean)", '{'))
        assertTrue(appClose.contains("notifyClosing()"))
        assertFalse("可取消的保存前阶段不修改桥接状态", code.normalized.contains("override fun projectClosingBeforeSave("))
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

    @Test
    fun `工程关闭后即使保留绑定也发出界面刷新信号`() {
        val closing = code.compact(code.bodyAfter("private fun notifyClosing()", '{'))
        val mark = closing.indexOf("projectClosing=true")
        val refresh = closing.indexOf("hub.changed()")
        assertTrue("刷新前必须先使会话不可用", mark >= 0 && refresh > mark)
        assertTrue("断开提示可能等待网络，界面刷新不能排在它后面", refresh < closing.indexOf("closeNotifier.notifyOnce("))
        code.assertSameCode(
            "可用性查询应读取实际停止状态，而不是可被取消的保存前标记",
            "internal fun isClosing(): Boolean = !scope.isActive",
            code.normalized.substringAfter("private var projectClosing = false")
                .substringBefore("private val started").trim(),
        )
    }

    @Test
    fun `标签恢复或关闭时刷新会话可用状态`() {
        val changed = code.compact(code.bodyAfter("private fun onSessionsChanged()", '{'))
        assertTrue("绑定不变时也必须刷新界面", changed.endsWith("hub.changed()}"))
        assertTrue("必须观察真实终端的启动状态", changed.contains("sessionStates.update(open.mapNotNull"))
        assertTrue(changed.contains("host.terminalViewOf(key)?.sessionState"))
        assertTrue(
            "终端状态变化（含 CLI 退出）回到 EDT 重新对账，对账结束会刷新界面",
            code.compact(code.normalized).contains("if(!projectClosing&&!project.isDisposed)onSessionsChanged()"),
        )
        assertTrue("CLI 退出的标签参与解绑判断", changed.contains("tabChangeFor(binding,open,seenTabs,projectClosing||project.isDisposed,terminated)"))
    }
}
