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
        assertTrue("start() 必须以 compareAndSet 开头", body.startsWith("{if(!started.compareAndSet(false,true))return"))
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
    fun `轮询循环在关闭期间也观察开关代次，关了又开就作废旧读取位置`() {
        val start = code.compact(code.bodyAfter("fun start()", '{'))
        assertTrue(start.contains("valenabled=BridgeSettings.getInstance().state.enabledtails.observeSwitch()if(enabled){"))
    }

    @Test
    fun `每次开启换新代次，关闭时丢弃未发出的回复`() {
        val lifecycle = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BridgeLifecycle.kt")
        assertTrue(lifecycle.compact(lifecycle.bodyAfter("private fun start()", '{')).contains("synchronized(this){switchGeneration++}"))
        assertTrue(lifecycle.compact(lifecycle.bodyAfter("private fun stop()", '{')).contains("BridgeHub.getInstance().router.discardPending()"))
    }
}
