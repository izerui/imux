package com.github.izerui.imux.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 迁移目标还没出现在列表里时，选中该怎么办。
 *
 * pi 在 `/new` 的那一刻就上报，而列表要等下一轮扫描（约 3 秒）才看得见新会话。
 * 此时 `revealSession` 定位不到目标、静默返回，高亮就永远钉死在旧会话上——
 * 这与轮次监控当初漏挂 watcher 是同一个病根：上报早于扫描。
 */
class DeferredSelectionTest {

    @Test
    fun `目标已经在列表里时不需要挂起`() {
        val deferred = DeferredSelection()

        assertNull(deferred.claim(selectedNow = "旧id") { true })
    }

    @Test
    fun `目标还没出现时挂起，出现后交出它`() {
        val deferred = DeferredSelection()
        deferred.defer(selectedAtDefer = "旧id", target = "新id")

        // 扫描还没跑，定位不到
        assertNull(deferred.claim(selectedNow = "旧id") { false })
        // 下一轮扫描之后
        assertEquals("新id", deferred.claim(selectedNow = "旧id") { true })
    }

    @Test
    fun `交出之后不再重复交出`() {
        val deferred = DeferredSelection()
        deferred.defer(selectedAtDefer = "旧id", target = "新id")
        deferred.claim(selectedNow = "旧id") { true }

        assertNull(deferred.claim(selectedNow = "新id") { true })
    }

    /**
     * 等待期间用户自己点了别的会话，就不该再抢他的选中——
     * 抢选中比不跟随更烦人：他正看着的东西会在几秒后毫无征兆地跳走。
     */
    @Test
    fun `用户在等待期间改了选中就作废`() {
        val deferred = DeferredSelection()
        deferred.defer(selectedAtDefer = "旧id", target = "新id")

        assertNull(deferred.claim(selectedNow = "用户点的另一个会话") { true })
        // 作废是一次性的，之后即使选中又回到旧会话也不再跟随
        assertNull(deferred.claim(selectedNow = "旧id") { true })
    }

    /** 迁移发生时列表本来就没选中任何东西（用户没碰过列表），仍然应该跟过去。 */
    @Test
    fun `迁移时没有选中也能跟随`() {
        val deferred = DeferredSelection()
        deferred.defer(selectedAtDefer = null, target = "新id")

        assertEquals("新id", deferred.claim(selectedNow = null) { true })
    }

    /**
     * 连续两次 `/new`：第二次的目标应当取代第一次的。
     * 留着第一次的会把选中送到一个已经不属于这个终端的会话上。
     */
    @Test
    fun `后一次挂起覆盖前一次`() {
        val deferred = DeferredSelection()
        deferred.defer(selectedAtDefer = "旧id", target = "中间id")
        deferred.defer(selectedAtDefer = "旧id", target = "最新id")

        assertEquals("最新id", deferred.claim(selectedNow = "旧id") { true })
    }
}
