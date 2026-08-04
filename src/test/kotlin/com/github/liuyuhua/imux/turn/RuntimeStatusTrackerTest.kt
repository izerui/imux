package com.github.liuyuhua.imux.turn

import com.github.liuyuhua.imux.session.ClaudeRuntimeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
