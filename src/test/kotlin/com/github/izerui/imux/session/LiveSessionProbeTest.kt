package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        piPids: List<Long> = emptyList(),
        env: Map<Long, String> = emptyMap(),
        claudeSession: Map<Long, String> = emptyMap(),
        rollouts: Map<Long, List<String>> = emptyMap(),
    ) = LiveSessionProbe(
        pidsOf = { type ->
            when (type) {
                AgentType.CLAUDE -> claudePids
                AgentType.CODEX -> codexPids
                AgentType.PI -> piPids
            }
        },
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

    /**
     * pi 不在探测范围内：它的会话 id 由 imux 用 `--session-id` 预先定下，
     * 终端与会话从启动那一刻就绑好了，没有可探测的漂移。
     *
     * 连进程表都不该去翻——pi 由 node 启动，可执行名不是 `pi`，
     * 按名字匹配既费事又容易误伤别的 node 进程。
     */
    @Test
    fun `pi 不参与进程探测`() {
        var askedForPiPids = false
        val probe = LiveSessionProbe(
            pidsOf = { type ->
                if (type == AgentType.PI) askedForPiPids = true
                emptyList()
            },
            tabIdOf = { null },
            claudeSessionOf = { null },
            rolloutsHeldBy = { emptyList() },
        )

        assertTrue(probe.probe().isEmpty())
        assertFalse("不该为 pi 去翻进程表", askedForPiPids)
    }

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

    @Test
    fun `同一终端探测出多个不同会话时一个都不认领`() {
        // claude 的后台 agent 会继承父进程的环境变量，于是带着同一个 IMUX_TAB
        // 却报出自己那个独立的会话 id。真要迁就可能迁到后台 agent 的会话上，
        // 而且顺序不确定。分不清就别动——这是最后一道防线，
        // 上游还会用 interactivePids 把 bg 进程挡掉。
        val drift = driftOf(
            openTabs = mapOf("tab-1" to "旧id"),
            live = listOf(LiveTab("tab-1", "交互会话"), LiveTab("tab-1", "后台agent会话")),
        )

        assertTrue(drift.isEmpty())
    }

    @Test
    fun `同一终端重复报出同一个会话仍然认领`() {
        // 重复不等于矛盾
        val drift = driftOf(
            openTabs = mapOf("tab-1" to "旧id"),
            live = listOf(LiveTab("tab-1", "新id"), LiveTab("tab-1", "新id")),
        )

        assertEquals(listOf(KeyDrift("tab-1", from = "旧id", to = "新id")), drift)
    }

    // ---- 只认交互式进程 ----

    private fun runtime(pid: Long, kind: String) =
        ClaudeRuntimeSession("会话$pid", pid, kind, "idle", "/tmp")

    @Test
    fun `后台 agent 的进程不参与探测`() {
        val pids = interactivePids(
            listOf(runtime(1L, "interactive"), runtime(2L, "bg")),
        )

        assertEquals(listOf(1L), pids)
    }

    @Test
    fun `kind 缺失的进程按交互式对待`() {
        // 老版本 CLI 可能不写这个字段，漏掉真正的终端比多认一个更糟
        assertEquals(listOf(1L), interactivePids(listOf(runtime(1L, kind = "null").copy(kind = null))))
    }

    // ---- 应用前复核 ----

    @Test
    fun `标签页还在且仍记着旧 id 时结果可用`() {
        val drifts = listOf(KeyDrift("tab-1", from = "旧id", to = "新id"))

        assertEquals(drifts, stillApplicable(drifts, currentTabs = mapOf("tab-1" to "旧id")))
    }

    @Test
    fun `探测期间标签页被关掉则结果作废`() {
        val drifts = listOf(KeyDrift("tab-1", from = "旧id", to = "新id"))

        assertTrue(stillApplicable(drifts, currentTabs = emptyMap()).isEmpty())
    }

    @Test
    fun `探测期间同一会话被重新打开成新标签页则结果作废`() {
        // 关掉原标签页又双击了旧会话：新终端的 sessionKey 一样，但 tabId 是新的，
        // 拿旧结果去迁它就是张冠李戴
        val drifts = listOf(KeyDrift("tab-1", from = "旧id", to = "新id"))

        assertTrue(stillApplicable(drifts, currentTabs = mapOf("tab-2" to "旧id")).isEmpty())
    }

    @Test
    fun `探测期间该终端已经自行迁走则结果作废`() {
        val drifts = listOf(KeyDrift("tab-1", from = "旧id", to = "新id"))

        assertTrue(stillApplicable(drifts, currentTabs = mapOf("tab-1" to "别的id")).isEmpty())
    }

    // ---- pi 上报复用既有漂移判定 ----

    /**
     * pi 走上报而不是进程探测，但迁移判定复用同一套：把上报转成 LiveTab 交给 driftOf，
     * 这样「同一 tabId 报了不同会话就不动」「标签页已关就不迁」这些规则不必写第二遍。
     */
    @Test
    fun `pi 上报能直接喂给既有的漂移判定`() {
        val live = listOf(LiveTab("tab-1", "pi-new"))
        val openTabs = mapOf("tab-1" to "pi-old")

        assertEquals(
            listOf(KeyDrift("tab-1", from = "pi-old", to = "pi-new")),
            driftOf(openTabs, live),
        )
    }

    @Test
    fun `上报的会话与当前一致时不产生迁移`() {
        assertTrue(driftOf(mapOf("tab-1" to "pi-same"), listOf(LiveTab("tab-1", "pi-same"))).isEmpty())
    }

    @Test
    fun `上报来自未知标签页时不产生迁移`() {
        assertTrue(driftOf(mapOf("tab-1" to "pi-old"), listOf(LiveTab("tab-9", "pi-new"))).isEmpty())
    }

    @Test
    fun `rollout 路径在两种分隔符下都能取到文件名`() {
        val id = "c0b2cc08-746f-4dc6-bb78-636d380d9216"
        assertEquals(
            id,
            threadIdOfRollout("/Users/me/.codex/sessions/rollout-2026-08-06T13-59-47-$id.jsonl"),
        )
        assertEquals(
            id,
            threadIdOfRollout("C:\\Users\\me\\.codex\\sessions\\rollout-2026-08-06T13-59-47-$id.jsonl"),
        )
    }

    @Test
    fun `不是 rollout 形状的路径一律不认`() {
        assertNull(threadIdOfRollout("C:\\Users\\me\\.codex\\history.jsonl"))
        assertNull(threadIdOfRollout("/Users/me/.codex/history.jsonl"))
    }

    @Test
    fun `fileNameOf 在两种分隔符下都能取到文件名`() {
        assertEquals("b.jsonl", fileNameOf("C:\\a\\b.jsonl"))
        assertEquals("b.jsonl", fileNameOf("/a/b.jsonl"))
        assertEquals("plain.txt", fileNameOf("plain.txt"))
    }

    @Test
    fun `Windows 风格路径下新旧 rollout 仍按文件名时间戳而非全路径排序`() {
        // 目录名 Z > A，但时间戳 14-13-47 > 13-59-47。
        // 如果 fileNameOf 退化为全路径比较（不切反斜杠），maxByOrNull 会按目录名
        // 挑到 Z 目录下的旧会话——终端标题、未读、完成通知全停在上一个会话上
        val probe = probe(
            codexPids = listOf(31694L),
            env = mapOf(31694L to "tab-2"),
            rollouts = mapOf(
                31694L to listOf(
                    "C:\\Users\\me\\.codex\\sessions\\Z\\rollout-2026-08-06T13-59-47-$oldThread.jsonl",
                    "C:\\Users\\me\\.codex\\sessions\\A\\rollout-2026-08-06T14-13-47-$newThread.jsonl",
                ),
            ),
        )

        assertEquals(listOf(LiveTab("tab-2", newThread)), probe.probe())
    }
}
