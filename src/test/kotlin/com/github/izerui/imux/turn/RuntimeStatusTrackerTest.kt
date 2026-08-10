package com.github.izerui.imux.turn

import com.github.izerui.imux.session.ClaudeRuntimeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RuntimeStatusTrackerTest {

    private fun session(id: String, status: String?, waitingFor: String? = null) =
        ClaudeRuntimeSession(
            id,
            pid = 1,
            kind = "interactive",
            status = status,
            cwd = "/proj",
            waitingFor = waitingFor,
        )

    private fun snapshot(vararg pairs: Pair<String, String?>) =
        pairs.associate { (id, status) -> id to session(id, status) }

    private fun waitingSnapshot(id: String, reason: String? = null) =
        mapOf(id to session(id, "waiting", reason))

    /** 首次见到就是 idle，说明它本来就闲着，不是刚跑完——不该提醒。 */
    @Test
    fun `首次观察到的空闲会话不产生完成事件`() {
        val tracker = RuntimeStatusTracker()

        assertTrue(tracker.completedSince(snapshot("s1" to "idle")).completed.isEmpty())
    }

    @Test
    fun `忙碌转空闲产生完成事件`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))

        assertEquals(listOf("s1"), tracker.completedSince(snapshot("s1" to "idle")).completed)
    }

    @Test
    fun `持续空闲不重复产生事件`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))
        tracker.completedSince(snapshot("s1" to "idle"))

        assertTrue(tracker.completedSince(snapshot("s1" to "idle")).completed.isEmpty())
    }

    @Test
    fun `持续忙碌不产生事件`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))

        assertTrue(tracker.completedSince(snapshot("s1" to "busy")).completed.isEmpty())
    }

    @Test
    fun `再次忙碌后再空闲会再次提醒`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))
        tracker.completedSince(snapshot("s1" to "idle"))
        tracker.completedSince(snapshot("s1" to "busy"))

        assertEquals(listOf("s1"), tracker.completedSince(snapshot("s1" to "idle")).completed)
    }

    /** 进程退出（文件消失）不是「跑完了」，不该提醒。 */
    @Test
    fun `会话消失不产生完成事件`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))

        assertTrue(tracker.completedSince(emptyMap()).completed.isEmpty())
    }

    @Test
    fun `多个会话各自独立`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("a" to "busy", "b" to "busy"))

        assertEquals(listOf("a"), tracker.completedSince(snapshot("a" to "idle", "b" to "busy")).completed)
    }

    @Test
    fun `状态为空时按空闲处理`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))

        assertEquals(listOf("s1"), tracker.completedSince(snapshot("s1" to null)).completed)
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

    // ---- 等待用户操作 ----

    /** 弹出选项时对话已经停下，要像跑完一样标记未读，但它不是「完成」。 */
    @Test
    fun `忙碌转等待产生等待事件而非完成事件`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))

        val outcome = tracker.completedSince(waitingSnapshot("s1"))

        assertEquals(listOf("s1"), outcome.waiting.map { it.sessionId })
        assertTrue(outcome.completed.isEmpty())
    }

    @Test
    fun `等待事件带上等待原因`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))

        val outcome = tracker.completedSince(waitingSnapshot("s1", "permission prompt"))

        assertEquals("permission prompt", outcome.waiting.single().reason)
    }

    /** 轮询一秒一拍，用户没去点的话会一直读到 waiting——只在进入的那一拍报。 */
    @Test
    fun `持续等待不重复产生事件`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))
        tracker.completedSince(waitingSnapshot("s1"))

        assertTrue(tracker.completedSince(waitingSnapshot("s1")).waiting.isEmpty())
    }

    @Test
    fun `等待转忙碌不产生事件`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))
        tracker.completedSince(waitingSnapshot("s1"))

        val outcome = tracker.completedSince(snapshot("s1" to "busy"))

        assertTrue(outcome.waiting.isEmpty())
        assertTrue(outcome.completed.isEmpty())
    }

    /** 一轮里连着几次权限确认，每次都是一次真实跃迁，都该报。 */
    @Test
    fun `等待转忙碌后再等待会再次提醒`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))
        tracker.completedSince(waitingSnapshot("s1"))
        tracker.completedSince(snapshot("s1" to "busy"))

        assertEquals(listOf("s1"), tracker.completedSince(waitingSnapshot("s1")).waiting.map { it.sessionId })
    }

    /** 用户在 CLI 里直接 ESC 掉了选择框，这一轮就此结束。 */
    @Test
    fun `等待转空闲产生完成事件`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))
        tracker.completedSince(waitingSnapshot("s1"))

        assertEquals(listOf("s1"), tracker.completedSince(snapshot("s1" to "idle")).completed)
    }

    /** 与「首次见到就是 idle 不提醒」对称：它早就卡在那了，不是刚发生的跃迁。 */
    @Test
    fun `首次观察到就在等待的会话不产生事件`() {
        val tracker = RuntimeStatusTracker()

        val outcome = tracker.completedSince(waitingSnapshot("s1"))

        assertTrue(outcome.waiting.isEmpty())
        assertTrue(outcome.completed.isEmpty())
    }

    /** 但它转忙碌后再次等待，那就是一次真实跃迁了。 */
    @Test
    fun `首见即等待的会话转忙碌后再等待会提醒`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(waitingSnapshot("s1"))
        tracker.completedSince(snapshot("s1" to "busy"))

        assertEquals(listOf("s1"), tracker.completedSince(waitingSnapshot("s1")).waiting.map { it.sessionId })
    }

    /** 进程退出不是「停下等你」，沿用「会话消失不提醒」。 */
    @Test
    fun `等待中的会话消失不产生事件`() {
        val tracker = RuntimeStatusTracker()
        tracker.completedSince(snapshot("s1" to "busy"))
        tracker.completedSince(waitingSnapshot("s1"))

        val outcome = tracker.completedSince(emptyMap())

        assertTrue(outcome.waiting.isEmpty())
        assertTrue(outcome.completed.isEmpty())
    }

    /**
     * 本设计不污染原有行为的关键一条。
     *
     * 判「完成」的依据是忙碌转非忙碌，而 waiting 已不算忙碌。若它顺手清掉计时起点，
     * 这一轮的耗时就会从 15 秒缩水成 10 秒——等待期间必须保留起点。
     */
    @Test
    fun `等待期间保留计时起点，最终报出完整耗时`() {
        val clock = FakeClock()
        val tracker = RuntimeStatusTracker(clock)

        tracker.completedSince(snapshot("s1" to "idle"))
        tracker.completedSince(snapshot("s1" to "busy"))
        clock.advance(5)
        tracker.completedSince(waitingSnapshot("s1"))
        clock.advance(30)
        tracker.completedSince(snapshot("s1" to "busy"))
        clock.advance(10)
        tracker.completedSince(snapshot("s1" to "idle"))

        assertEquals(Duration.ofSeconds(45), tracker.lastDuration("s1"))
    }
}
