package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探测「每个 imux 终端此刻真正在跑哪个会话」。
 *
 * 这里锁住的是 `/clear`（claude）与 `/new`（codex）：CLI 换了会话 id 而进程不变，
 * 插件必须能自己发现这件事——否则终端一直记在旧 id 下，标题、未读、完成通知
 * 全部错位，再点新会话还会重开一个终端去抢同一个会话。
 */
class LiveSessionProbeTest {

    private fun probe(
        claudePids: List<Long> = emptyList(),
        codexPids: List<Long> = emptyList(),
        env: Map<Long, String> = emptyMap(),
        claudeSession: Map<Long, String> = emptyMap(),
        rollouts: Map<Long, List<String>> = emptyMap(),
    ) = LiveSessionProbe(
        pidsOf = { type -> if (type == AgentType.CLAUDE) claudePids else codexPids },
        tabIdOf = { pid -> env[pid] },
        claudeSessionOf = { pid -> claudeSession[pid] },
        rolloutsHeldBy = { pid -> rollouts[pid].orEmpty() },
    )

    private fun rollout(threadId: String, stamp: String = "2026-08-06T13-59-47") =
        "/Users/x/.codex/sessions/2026/08/06/rollout-$stamp-$threadId.jsonl"

    /** thread id 是标准 uuid，探测器靠这个形状把无关文件挡在外面。 */
    private val oldThread = "019fd5a8-0890-73f3-abf8-891be422a5a6"
    private val newThread = "019fd5b4-dca1-7593-bf7c-048a1a9370b5"
    private val codexThread = "019fd7f4-4e51-73a2-be05-4018cd1bd186"

    @Test
    fun `claude 进程换了会话 id 后探测到新 id`() {
        val probe = probe(
            claudePids = listOf(11814L),
            env = mapOf(11814L to "tab-1"),
            // 运行态文件是 CLI 自己维护的，/clear 后原地更新为新 id
            claudeSession = mapOf(11814L to "新会话id"),
        )

        assertEquals(listOf(LiveTab("tab-1", "新会话id")), probe.probe())
    }

    @Test
    fun `codex 进程按持有的 rollout 文件定位当前会话`() {
        val probe = probe(
            codexPids = listOf(31694L),
            env = mapOf(31694L to "tab-2"),
            rollouts = mapOf(31694L to listOf(rollout("019fd5a8-0890-73f3-abf8-891be422a5a6"))),
        )

        assertEquals(
            listOf(LiveTab("tab-2", "019fd5a8-0890-73f3-abf8-891be422a5a6")),
            probe.probe(),
        )
    }

    @Test
    fun `codex 同时持有新旧两个 rollout 时取时间戳最新的`() {
        // /new 之后旧文件的句柄未必立刻关闭，不能假定只有一个
        val probe = probe(
            codexPids = listOf(31694L),
            env = mapOf(31694L to "tab-2"),
            rollouts = mapOf(
                31694L to listOf(
                    rollout(oldThread, stamp = "2026-08-06T13-59-47"),
                    rollout(newThread, stamp = "2026-08-06T14-13-47"),
                ),
            ),
        )

        assertEquals(listOf(LiveTab("tab-2", newThread)), probe.probe())
    }

    @Test
    fun `没有 imux 标记的进程一律忽略`() {
        // 运行态目录是全机器共享的，用户自己在别处开的 CLI 不归本插件管
        val probe = probe(
            claudePids = listOf(999L),
            env = emptyMap(),
            claudeSession = mapOf(999L to "别处的会话"),
        )

        assertTrue(probe.probe().isEmpty())
    }

    @Test
    fun `进程有标记但查不到当前会话时不产出条目`() {
        // 宁可不认领也不错认：查不到就保持现状，交给既有的 pending 机制
        val probe = probe(
            claudePids = listOf(11814L),
            env = mapOf(11814L to "tab-1"),
        )

        assertTrue(probe.probe().isEmpty())
    }

    @Test
    fun `两个 agent 的终端同时开着时各自归位`() {
        val probe = probe(
            claudePids = listOf(11814L),
            codexPids = listOf(31694L),
            env = mapOf(11814L to "tab-c", 31694L to "tab-x"),
            claudeSession = mapOf(11814L to "claude会话"),
            rollouts = mapOf(31694L to listOf(rollout(codexThread))),
        )

        assertEquals(
            setOf(LiveTab("tab-c", "claude会话"), LiveTab("tab-x", codexThread)),
            probe.probe().toSet(),
        )
    }

    @Test
    fun `rollout 文件名解析出末尾的 thread id`() {
        assertEquals(
            "019fd5a8-0890-73f3-abf8-891be422a5a6",
            threadIdOfRollout(rollout("019fd5a8-0890-73f3-abf8-891be422a5a6")),
        )
    }

    @Test
    fun `不是 rollout 的文件名解析为空`() {
        assertNull(threadIdOfRollout("/Users/x/.codex/history.jsonl"))
        assertNull(threadIdOfRollout("/dev/null"))
    }

    // ---- 漂移检测：探测结果与终端记账的差异 ----

    @Test
    fun `探测到的会话与终端当前记账不一致时报告漂移`() {
        val drift = driftOf(
            openTabs = mapOf("tab-1" to "旧id"),
            live = listOf(LiveTab("tab-1", "新id")),
        )

        assertEquals(listOf(KeyDrift("tab-1", from = "旧id", to = "新id")), drift)
    }

    @Test
    fun `一致时不报告漂移`() {
        val drift = driftOf(
            openTabs = mapOf("tab-1" to "同一个id"),
            live = listOf(LiveTab("tab-1", "同一个id")),
        )

        assertTrue(drift.isEmpty())
    }

    @Test
    fun `探测不到的终端不报告漂移`() {
        // CLI 还没起来、或运行态文件尚未落盘，都属于这种情况，保持现状即可
        val drift = driftOf(openTabs = mapOf("tab-1" to "旧id"), live = emptyList())

        assertTrue(drift.isEmpty())
    }

    @Test
    fun `不属于任何已开标签页的探测结果被丢弃`() {
        // 上一轮刚关掉的标签页，其进程可能还没退干净
        val drift = driftOf(openTabs = emptyMap(), live = listOf(LiveTab("tab-9", "某会话")))

        assertTrue(drift.isEmpty())
    }
}
