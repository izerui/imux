package com.github.izerui.imux.turn

import com.github.izerui.imux.session.ClaudeRuntimeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RuntimeStatusTrackerTest {

    private fun session(id: String, status: String?) =
        ClaudeRuntimeSession(id, pid = 1, kind = "interactive", status = status, cwd = "/proj")

    private fun snapshot(vararg pairs: Pair<String, String?>) =
        pairs.associate { (id, status) -> id to session(id, status) }

    /** 首次见到就是 idle，说明它本来就闲着，不是刚跑完——不该提醒。 */
    @Test
    fun `首次观察到的空闲会话不产生完成事件`() {
        val tracker = RuntimeStatusTracker()

        assertTrue(tracker.completedSince(snapshot("s1" to "idle")).isEmpty())
    }

    @Test
    fun `忙碌转空闲产生完成事件`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))

        assertEquals(listOf("s1"), tracker.completedSince(snapshot("s1" to "idle")))
    }

    @Test
    fun `持续空闲不重复产生事件`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))
        tracker.completedSince(snapshot("s1" to "idle"))

        assertTrue(tracker.completedSince(snapshot("s1" to "idle")).isEmpty())
    }

    @Test
    fun `持续忙碌不产生事件`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))

        assertTrue(tracker.completedSince(snapshot("s1" to "busy")).isEmpty())
    }

    @Test
    fun `再次忙碌后再空闲会再次提醒`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))
        tracker.completedSince(snapshot("s1" to "idle"))
        tracker.completedSince(snapshot("s1" to "busy"))

        assertEquals(listOf("s1"), tracker.completedSince(snapshot("s1" to "idle")))
    }

    /** 进程退出（文件消失）不是「跑完了」，不该提醒。 */
    @Test
    fun `会话消失不产生完成事件`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))

        assertTrue(tracker.completedSince(emptyMap()).isEmpty())
    }

    @Test
    fun `多个会话各自独立`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("a" to "busy", "b" to "busy"))

        assertEquals(listOf("a"), tracker.completedSince(snapshot("a" to "idle", "b" to "busy")))
    }

    @Test
    fun `状态为空时按空闲处理`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))

        assertEquals(listOf("s1"), tracker.completedSince(snapshot("s1" to null)))
    }

    /** 从可控时钟推进，验证耗时按 busy 起点计算。 */
    private class FakeClock(var now: Instant = Instant.parse("2026-08-04T10:00:00Z")) : () -> Instant {
        override fun invoke(): Instant = now
        fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
    }

    @Test
    fun `首次见到就在忙的会话不报耗时`() {
        // 插件启动前它已经在跑了，起点无从得知。宁可不显示，也不要报一个错的数。
        val clock = FakeClock()
        val tracker = RuntimeStatusTracker(clock)

        // 第一次观察就是 busy
        tracker.completedSince(snapshot("s1" to "busy"))
        clock.advance(10)
        tracker.completedSince(snapshot("s1" to "idle"))

        assertNull(tracker.lastDuration("s1"))
    }

    @Test
    fun `先见到空闲再转忙碌，起点才算数`() {
        val clock = FakeClock()
        val tracker = RuntimeStatusTracker(clock)

        tracker.completedSince(snapshot("s1" to "idle"))
        clock.advance(5)
        tracker.completedSince(snapshot("s1" to "busy"))
        clock.advance(20)
        tracker.completedSince(snapshot("s1" to "idle"))

        assertEquals(Duration.ofSeconds(20), tracker.lastDuration("s1"))
    }

    @Test
    fun `没跑过的会话没有耗时`() {
        assertNull(RuntimeStatusTracker().lastDuration("never"))
    }
}
