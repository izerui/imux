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
    fun `重新开启过（开启代次变了）就丢弃旧读取位置`() {
        val body = code.compact(code.bodyAfter("private fun observeSwitch()", '{'))
        assertTrue(body.contains("if(switchWatch.observe(BridgeLifecycle.switchGeneration)){tails.clear()migratedAt.clear()"))
        val poll = code.compact(code.bodyAfter("private suspend fun pollTab(tabId: String)", '{'))
        assertTrue("pollTab 必须在锁内先观察开关", poll.startsWith("{pollLock.withLock{observeSwitch()if(!BridgeSettings.getInstance().state.enabled)return"))
        val start = code.compact(code.bodyAfter("fun start()", '{'))
        assertTrue("启动时以当时的开启代次为准", start.contains("switchWatch.reset(BridgeLifecycle.switchGeneration)"))
        assertTrue("轮询循环关闭期间也要观察开关", start.contains("pollLock.withLock{observeSwitch()}"))
        val lifecycle = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BridgeLifecycle.kt")
        val apply = lifecycle.compact(lifecycle.bodyAfter("fun applyEnabled(enabled: Boolean)", '{'))
        assertTrue("每次开启都换新代次", apply.contains("synchronized(this){switchGeneration++}"))
        assertTrue("关闭时丢弃未发出的回复", apply.contains("BridgeHub.getInstance().router.discardPending()"))
    }

}
