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
        val read = code.compact(code.bodyAfter("private suspend fun readTab(tabId: String, mark: ReadMark?,)", '{'))
        assertTrue("读取必须在锁内先观察开关", read.contains("{pollLock.withLock{observeSwitch()if(!BridgeSettings.getInstance().state.enabled)returnnull"))
        val start = code.compact(code.bodyAfter("fun start()", '{'))
        val poll = code.compact(code.bodyAfter("private suspend fun pollTab(tabId: String, mark: ReadMark? = null, then: suspend () -> Unit = {},)", '{'))
        assertTrue("同一标签的读取、发送与收尾在同一把标签锁内", poll.contains("{tabLock(tabId).withLock{valpoll=readTab(tabId,mark)"))
        assertTrue("发送不在共享读取锁内", !poll.contains("pollLock"))
        assertTrue("启动时以当时的开启代次为准", start.contains("switchWatch.reset(BridgeLifecycle.switchGeneration)"))
        assertTrue("轮询循环关闭期间也要观察开关", start.contains("pollLock.withLock{observeSwitch()}"))
        val lifecycle = SourceCode("src/main/kotlin/com/github/izerui/imux/bridge/BridgeLifecycle.kt")
        val apply = lifecycle.compact(lifecycle.bodyAfter("fun applyEnabled(enabled: Boolean)", '{'))
        assertTrue("每次开启都换新代次", apply.contains("synchronized(this){switchGeneration++}"))
        assertTrue("关闭时丢弃未发出的回复", apply.contains("BridgeHub.getInstance().router.discardPending()"))
    }

}
