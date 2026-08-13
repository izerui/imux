package com.github.izerui.imux.monitor

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.github.izerui.imux.session.KeyDrift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths
import java.time.Instant

/**
 * 会话漂移落地的两件事必须**同时**发生：标签页迁到新会话，且新会话被纳入轮次监控。
 *
 * 只断言第一条是不够的——pi 没有运行态文件，完成提醒与运行转圈完全依赖轮次监控。
 * 迁过去了却没挂上监控，界面看着一切正常，提醒却永远不来，而且是静默的。
 */
class SessionDriftApplierTest {

    private val session = AgentSession(
        id = "新会话id",
        title = "重构登录流程",
        agentType = AgentType.PI,
        lastActiveAt = Instant.EPOCH,
        createdAt = Instant.EPOCH,
        filePath = Paths.get("/tmp/新会话id.jsonl"),
    )

    @Test
    fun `扫描已经见过新会话时迁移并纳入监控`() {
        val recorder = Recorder(known = mapOf(session.id to session))
        val applier = recorder.applier(openTabs = mapOf("tab-1" to "旧会话id"))

        assertTrue(applier.apply(listOf(KeyDrift("tab-1", from = "旧会话id", to = session.id))))

        assertEquals(listOf("旧会话id" to session.id), recorder.rebound)
        assertEquals(listOf(session.id), recorder.watched)
        assertEquals(listOf("旧会话id"), recorder.unreadCleared)
    }

    /**
     * pi 的核心场景：扩展在 session_start 那一刻就上报，会话文件刚落盘，
     * 下一轮扫描（约 3 秒）还没跑，此刻 `sessionOf` 查不到任何东西。
     *
     * 迁移仍然要做（标签页身份不依赖扫描），监控则要挂起来等扫描到位再补——
     * 这一步若被静默跳过，之后没有任何路径会补上。
     */
    @Test
    fun `扫描还没见过新会话时先迁移并把监控挂起等待`() {
        val recorder = Recorder(known = emptyMap())
        val applier = recorder.applier(openTabs = mapOf("tab-1" to "旧会话id"))

        assertTrue(applier.apply(listOf(KeyDrift("tab-1", from = "旧会话id", to = session.id))))
        assertEquals(listOf("旧会话id" to session.id), recorder.rebound)
        assertTrue("此刻还查不到文件路径，挂不上是正常的", recorder.watched.isEmpty())

        // 下一轮扫描把会话带进来，监控必须在这时补上
        recorder.known = mapOf(session.id to session)
        recorder.openTabs = mapOf("tab-1" to session.id)
        applier.retryPendingWatches()

        assertEquals(listOf(session.id), recorder.watched)
    }

    /** 重复补挂不能重复登记：TurnWatcher 自己会去重，但队列不该无限攒着。 */
    @Test
    fun `补挂成功后不再重复登记`() {
        val recorder = Recorder(known = emptyMap())
        val applier = recorder.applier(openTabs = mapOf("tab-1" to "旧会话id"))
        applier.apply(listOf(KeyDrift("tab-1", from = "旧会话id", to = session.id)))

        recorder.known = mapOf(session.id to session)
        recorder.openTabs = mapOf("tab-1" to session.id)
        applier.retryPendingWatches()
        applier.retryPendingWatches()

        assertEquals(listOf(session.id), recorder.watched)
    }

    /** 标签页被用户关掉的话，等着的监控要丢弃：再挂上去就是盯一个没人在看的会话。 */
    @Test
    fun `标签页关掉后不再补挂监控`() {
        val recorder = Recorder(known = emptyMap())
        val applier = recorder.applier(openTabs = mapOf("tab-1" to "旧会话id"))
        applier.apply(listOf(KeyDrift("tab-1", from = "旧会话id", to = session.id)))

        recorder.known = mapOf(session.id to session)
        recorder.openTabs = emptyMap()
        applier.retryPendingWatches()

        assertTrue(recorder.watched.isEmpty())

        // 丢弃是彻底的：标签页即使又开回来（那是另一个终端）也不该复活
        recorder.openTabs = mapOf("tab-1" to session.id)
        applier.retryPendingWatches()
        assertTrue(recorder.watched.isEmpty())
    }

    /**
     * 迁移失败的那笔要留到下一轮重试。
     *
     * pi 这条链路上一次 `/new` 只产生**一次** HTTP 上报，没有 claude/codex 那样的
     * 探测重试推着走；失败即永久停在旧 id 上。
     */
    @Test
    fun `迁移失败时留到下一轮重试`() {
        val recorder = Recorder(known = mapOf(session.id to session))
        recorder.rebindSucceeds = false
        val applier = recorder.applier(openTabs = mapOf("tab-1" to "旧会话id"))

        assertFalse(applier.apply(listOf(KeyDrift("tab-1", from = "旧会话id", to = session.id))))
        assertTrue(recorder.watched.isEmpty())
        assertTrue(recorder.unreadCleared.isEmpty())

        // 下一轮：目标腾出来了，这笔要自己重来，不需要外部再报一次
        recorder.rebindSucceeds = true
        applier.retryPendingWatches()

        assertEquals(listOf("旧会话id" to session.id), recorder.rebound.takeLast(1))
        assertEquals(listOf(session.id), recorder.watched)
    }

    /** 标签页已经不记着旧 id 了（用户关掉又开了别的），这笔重试要丢掉而不是硬迁。 */
    @Test
    fun `重试时标签页已经变了就丢弃`() {
        val recorder = Recorder(known = mapOf(session.id to session))
        recorder.rebindSucceeds = false
        val applier = recorder.applier(openTabs = mapOf("tab-1" to "旧会话id"))
        applier.apply(listOf(KeyDrift("tab-1", from = "旧会话id", to = session.id)))

        recorder.rebindSucceeds = true
        recorder.openTabs = mapOf("tab-1" to "完全不相干的会话")
        applier.retryPendingWatches()

        assertTrue(recorder.rebound.isEmpty())
        assertTrue(recorder.watched.isEmpty())
    }

    /**
     * 同一个 tabId 连着上报两次同一个会话（pi 的 session_start 在某些流程里会重放）：
     * 第二次没有可迁的东西，不该产生第二次 rebind，也不该把已挂的监控再登记一遍。
     */
    @Test
    fun `同一个标签页重复上报同一会话不重复动作`() {
        val recorder = Recorder(known = mapOf(session.id to session))
        val applier = recorder.applier(openTabs = mapOf("tab-1" to "旧会话id"))
        applier.apply(listOf(KeyDrift("tab-1", from = "旧会话id", to = session.id)))
        recorder.openTabs = mapOf("tab-1" to session.id)

        // 第二次上报：driftOf 已经不会产出 drift（当前 id 就是目标 id），这里模拟
        // 上游万一漏过一条的情况——stillApplicable 必须把它挡掉
        assertFalse(applier.apply(listOf(KeyDrift("tab-1", from = "旧会话id", to = session.id))))

        assertEquals(1, recorder.rebound.size)
        assertEquals(listOf(session.id), recorder.watched)
    }

    private class Recorder(var known: Map<String, AgentSession>) {
        var openTabs: Map<String, String> = emptyMap()
        var rebindSucceeds = true
        val rebound = mutableListOf<Pair<String, String>>()
        val watched = mutableListOf<String>()
        val unreadCleared = mutableListOf<String>()

        fun applier(openTabs: Map<String, String>): SessionDriftApplier {
            this.openTabs = openTabs
            return SessionDriftApplier(
                sessionOf = { known[it] },
                openTabs = { this.openTabs },
                rebindKey = { from, to, _ ->
                    if (rebindSucceeds) rebound += from to to
                    rebindSucceeds
                },
                startWatching = { watched += it.id },
                clearUnread = { unreadCleared += it },
            )
        }
    }
}

/**
 * 纯逻辑再对，没被接上也是白搭。这些是 C1 的接线守卫。
 */
class SessionDriftApplierWiringTest {

    private val monitor = java.io.File(
        "src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt",
    ).readText()

    @Test
    fun `落地器必须长期持有而不是每次现造`() {
        // 现造一个就等于把「等着扫描补挂监控」「等着重试的迁移」两个队列一起扔掉，
        // pi 的会话会因此永远进不了轮次监控——而界面上完全看不出来
        assertTrue(
            "落地器要作为字段活着",
            Regex("""private val driftApplier = SessionDriftApplier\(""").containsMatchIn(monitor),
        )
        assertFalse(
            "不能在 applyDrifts 里现造",
            Regex("""fun applyDrifts[\s\S]{0,300}?SessionDriftApplier\(""").containsMatchIn(monitor),
        )
    }

    @Test
    fun `每轮扫描之后必须补挂等着的监控`() {
        assertTrue(
            "pi 的上报早于扫描，补挂这一步没接上的话它永远不会被纳入轮次监控",
            Regex("""applyNewBindings\(\)\s*(//[^\n]*\n\s*)*driftApplier\.retryPendingWatches\(\)""")
                .containsMatchIn(monitor),
        )
    }

    @Test
    fun `轮询也要推进重试而不只靠扫描`() {
        // 只挂在扫描监听上不够：那条通路要 applyScan 判定「结果有变化」才通知，
        // 而占着目标 key 的重复终端被收拾掉、用户关掉标签页，都不改变扫描结果——
        // 队列会因此停摆。轮询无条件按拍走，是重试的兜底节奏。
        assertTrue(
            "checkCompletedTurns 里必须也续一拍重试",
            Regex("""fun checkCompletedTurns[\s\S]{0,900}?driftApplier\.retryPendingWatches\(\)""")
                .containsMatchIn(monitor),
        )
    }

    @Test
    fun `pending 绑定与上报漂移走同一条落地通路`() {
        // 分成两条路的话，被上报先迁走的那个终端会让 rebindKey("pending-N", …) 失败，
        // 刷一条误报 WARN，并把紧随其后的挂监控一并跳过
        assertTrue(
            "绑定必须转成 KeyDrift 交给 applyDrifts",
            Regex("""fun applyNewBindings[\s\S]{0,600}?applyDrifts\(""").containsMatchIn(monitor),
        )
        assertFalse(
            "不能再走各自的 rebindKey",
            monitor.contains("host.rebindKey(pendingKey"),
        )
    }
}
