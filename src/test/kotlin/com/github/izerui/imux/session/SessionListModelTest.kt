package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SessionListModelTest {

    private val base: Instant = Instant.parse("2026-08-03T10:00:00Z")

    /** 可控时钟，避免依赖真实墙钟。 */
    private class FakeClock(var now: Instant)

    private var scanResult: List<AgentSession> = emptyList()

    private fun session(id: String, type: AgentType, at: Instant) =
        AgentSession(id, "标题-$id", type, at, at, java.nio.file.Paths.get("/tmp/$id.jsonl"))

    private fun model(clock: FakeClock) =
        SessionListModel(scan = { scanResult }, clock = { clock.now })

    @Test
    fun `新建的 pending 立即出现在对应分组中`() {
        val model = model(FakeClock(base))

        val pending = model.registerPending(AgentType.CLAUDE)
        model.refresh()

        val entries = model.entries(AgentType.CLAUDE)
        assertEquals(1, entries.size)
        assertTrue(entries[0] is ListEntry.Pending)
        assertNull(model.boundIdFor(pending.key))
    }

    @Test
    fun `预知 id 的新会话落盘后被绑定，并显示为真实会话`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val pending = model.registerPending(AgentType.CLAUDE, sessionId = "新id")

        clock.now = base.plusSeconds(30)
        scanResult = listOf(session("新id", AgentType.CLAUDE, base.plusSeconds(20)))
        model.refresh()

        assertEquals("新id", model.boundIdFor(pending.key))
        val entries = model.entries(AgentType.CLAUDE)
        assertEquals(1, entries.size)
        assertTrue("绑定后应显示为真实会话", entries[0] is ListEntry.Existing)
    }

    // ---- 预知 id 的 pending（pi）----
    //
    // pi 用 `--session-id` 启动，会话 id 在启动那一刻就定了，不必靠「时间窗内最近的
    // pending」去猜。猜是有代价的：同时新建两个会话时，两条 pending 都符合时间窗，
    // 谁绑谁全看落盘顺序，绑错就把终端迁到别人的会话上。

    @Test
    fun `预知 id 的 pending 只认领同 id 的会话`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val first = model.registerPending(AgentType.PI, sessionId = "pi-1")
        val second = model.registerPending(AgentType.PI, sessionId = "pi-2")

        clock.now = base.plusSeconds(30)
        // 落盘顺序与新建顺序相反：pi-2 先写盘。按时间窗猜的话它会被 second 之后
        // 启动的那条 pending 抢走，而 id 是确定的，抢不走。
        scanResult = listOf(
            session("pi-2", AgentType.PI, base.plusSeconds(10)),
            session("pi-1", AgentType.PI, base.plusSeconds(20)),
        )
        model.refresh()

        assertEquals("pi-1", model.boundIdFor(first.key))
        assertEquals("pi-2", model.boundIdFor(second.key))
    }

    @Test
    fun `预知 id 的 pending 不认领其他会话`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val pending = model.registerPending(AgentType.PI, sessionId = "pi-mine")

        clock.now = base.plusSeconds(30)
        // 用户在 IDE 外面自己开的 pi 会话，时间窗完全吻合，但 id 不是我们要的那个
        scanResult = listOf(session("pi-other", AgentType.PI, base.plusSeconds(20)))
        model.refresh()

        assertNull(model.boundIdFor(pending.key))
        assertEquals(listOf("pi-other"), model.drainUnclaimedSessions())
    }

    @Test
    fun `没有 pending 认领的新会话被记为无主`() {
        // 这正是终端里 /clear、/new 的形态：会话凭空出现，没人在等它。
        // 它可能属于某个已打开的终端（CLI 换了 id），也可能是用户在 IDE 外面
        // 自己开的——分辨这件事要靠进程探测，model 只负责报告「有这么个东西」。
        val model = model(FakeClock(base))

        scanResult = listOf(session("凭空出现的id", AgentType.CLAUDE, base))
        model.refresh()

        assertEquals(listOf("凭空出现的id"), model.drainUnclaimedSessions())
    }

    @Test
    fun `无主会话取走即清空`() {
        val model = model(FakeClock(base))
        scanResult = listOf(session("某id", AgentType.CLAUDE, base))
        model.refresh()

        model.drainUnclaimedSessions()

        assertTrue("重复消费会让同一次变化被探测两遍", model.drainUnclaimedSessions().isEmpty())
    }

    @Test
    fun `被 pending 认领的新会话不算无主`() {
        val clock = FakeClock(base)
        val model = model(clock)
        model.registerPending(AgentType.CLAUDE, sessionId = "新id")

        clock.now = base.plusSeconds(30)
        scanResult = listOf(session("新id", AgentType.CLAUDE, base.plusSeconds(20)))
        model.refresh()

        assertTrue(model.drainUnclaimedSessions().isEmpty())
    }

    /**
     * 只有一个 codex 新标签在等时也不猜：它可能是别的标签 `/new` 出来的，也可能是 IDE 外开的，
     * 按时间窗认给这个新标签就认错了。报成无主，由进程探测按终端身份认领。
     */
    @Test
    fun `只有一个没有预知 id 的 pending 在等时也不认领，会话报成无主`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val pending = model.registerPending(AgentType.CODEX)

        clock.now = base.plusSeconds(30)
        scanResult = listOf(session("别的标签 /new 出来的", AgentType.CODEX, base.plusSeconds(20)))
        model.refresh()

        assertNull(model.boundIdFor(pending.key))
        assertEquals(listOf("别的标签 /new 出来的"), model.drainUnclaimedSessions())
        assertEquals(2, model.entries(AgentType.CODEX).size)
    }

    @Test
    fun `不同类型的新会话不被绑定`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val pending = model.registerPending(AgentType.CLAUDE)

        scanResult = listOf(session("codex-id", AgentType.CODEX, base.plusSeconds(20)))
        model.refresh()

        assertNull(model.boundIdFor(pending.key))
    }

    /**
     * 原规则挑启动最晚的那个：同时新建两个 codex 会话、先在早开的那个里说话，会把两边绑反，
     * 且都认领了就没有无主会话触发纠正。现在不猜，报成无主交给进程探测精确迁移。
     */
    @Test
    fun `多个同类型 pending 竞争时不猜，两个都不认领，会话报成无主`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val early = model.registerPending(AgentType.CODEX)
        clock.now = base.plusSeconds(10)
        val late = model.registerPending(AgentType.CODEX)

        clock.now = base.plusSeconds(40)
        scanResult = listOf(session("新id", AgentType.CODEX, base.plusSeconds(30)))
        model.refresh()

        assertNull(model.boundIdFor(early.key))
        assertNull(model.boundIdFor(late.key))
        assertEquals(listOf("新id"), model.drainUnclaimedSessions())
        assertEquals(emptyList<Pair<String, String>>(), model.drainNewBindings())
    }

    @Test
    fun `进程探测迁走 pending 后认领它，列表不再显示占位，也不再产出换 key`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val early = model.registerPending(AgentType.CODEX)
        val late = model.registerPending(AgentType.CODEX)
        clock.now = base.plusSeconds(40)
        scanResult = listOf(session("新id", AgentType.CODEX, base.plusSeconds(30)))
        model.refresh()

        model.claimPending(early.key, "新id")

        assertEquals("新id", model.boundIdFor(early.key))
        assertNull(model.boundIdFor(late.key))
        val pendingKeys = model.entries(AgentType.CODEX).filterIsInstance<ListEntry.Pending>().map { it.pending.key }
        assertEquals(listOf(late.key), pendingKeys)
        assertEquals(emptyList<Pair<String, String>>(), model.drainNewBindings())
    }

    /**
     * pi 一启动就上报会话、终端随即迁到真实 id（走 applyDrifts），这时会话文件只有文件头，列表还显示不出来。
     * 原先在这里就认领并隐藏占位，新建的 pi 会话在发第一条消息前从列表里消失。
     */
    @Test
    fun `pi 上报迁移时会话还显示不出来：占位保留，会话出现后才换成真实会话`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val pending = model.registerPending(AgentType.PI, sessionId = "pi-1")

        model.claimPending(pending.key, "pi-1")
        model.refresh()

        assertEquals(listOf(pending.key), model.entries(AgentType.PI).filterIsInstance<ListEntry.Pending>().map { it.pending.key })

        clock.now = base.plusSeconds(30)
        scanResult = listOf(session("pi-1", AgentType.PI, base.plusSeconds(20)))
        model.refresh()

        assertEquals("pi-1", model.boundIdFor(pending.key))
        assertEquals(listOf("pi-1"), model.entries(AgentType.PI).map { (it as ListEntry.Existing).session.id })
    }

    @Test
    fun `pi 上报迁移后、发第一条消息前关掉标签，占位仍能取消`() {
        val model = model(FakeClock(base))
        val pending = model.registerPending(AgentType.PI, sessionId = "pi-1")
        model.claimPending(pending.key, "pi-1")

        assertTrue(model.cancelPending(pending.key))
        assertEquals(emptyList<ListEntry>(), model.entries(AgentType.PI))
    }

    @Test
    fun `认领早于会话出现在列表时，占位先保留，会话出现后才隐藏`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val pending = model.registerPending(AgentType.CODEX)

        model.claimPending(pending.key, "X")
        assertEquals(listOf(pending.key), model.entries(AgentType.CODEX).filterIsInstance<ListEntry.Pending>().map { it.pending.key })

        clock.now = base.plusSeconds(30)
        scanResult = listOf(session("X", AgentType.CODEX, base.plusSeconds(20)))
        model.refresh()

        assertEquals(listOf("X"), model.entries(AgentType.CODEX).map { (it as ListEntry.Existing).session.id })
    }

    @Test
    fun `认领不存在或已认领的 pending 时什么也不做`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val pending = model.registerPending(AgentType.CODEX)
        scanResult = listOf(session("第一个", AgentType.CODEX, base.plusSeconds(10)))
        model.refresh()
        model.claimPending(pending.key, "第一个")

        model.claimPending(pending.key, "别的id")
        model.claimPending("不存在", "别的id")

        assertEquals("第一个", model.boundIdFor(pending.key))
        assertNull(model.boundIdFor("不存在"))
    }

    @Test
    fun `已绑定的 pending 不再参与后续绑定`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val pending = model.registerPending(AgentType.CODEX)

        scanResult = listOf(session("第一个", AgentType.CODEX, base.plusSeconds(10)))
        model.refresh()
        model.claimPending(pending.key, "第一个")
        model.drainUnclaimedSessions()
        scanResult = scanResult + session("第二个", AgentType.CODEX, base.plusSeconds(20))
        model.refresh()

        assertEquals("第一个", model.boundIdFor(pending.key))
        assertEquals(listOf("第二个"), model.drainUnclaimedSessions())
    }

    @Test
    fun `超时未绑定的 pending 被清除`() {
        val clock = FakeClock(base)
        val model = model(clock)
        model.registerPending(AgentType.CLAUDE)

        clock.now = base.plus(Duration.ofMinutes(31))
        model.refresh()

        assertTrue(model.entries(AgentType.CLAUDE).isEmpty())
    }

    @Test
    fun `取消未绑定 pending 会移除条目并返回 true`() {
        val model = model(FakeClock(base))
        val pending = model.registerPending(AgentType.CLAUDE)

        val removed = model.cancelPending(pending.key)

        assertTrue(removed)
        assertTrue(model.entries(AgentType.CLAUDE).isEmpty())
        assertNull(model.boundIdFor(pending.key))
    }

    @Test
    fun `pi 终端提前迁到预分配 id 后仍可取消未绑定 pending`() {
        val model = model(FakeClock(base))
        val pending = model.registerPending(AgentType.PI, sessionId = "pi-empty")

        val removed = model.cancelPending("pi-empty")

        assertTrue(removed)
        assertTrue(model.entries(AgentType.PI).isEmpty())
        assertNull(model.boundIdFor(pending.key))
    }

    @Test
    fun `预分配 id 已绑定真实会话后不能取消`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val pending = model.registerPending(AgentType.PI, sessionId = "pi-started")
        scanResult = listOf(session("pi-started", AgentType.PI, base.plusSeconds(10)))
        model.refresh()

        val removed = model.cancelPending("pi-started")

        assertFalse(removed)
        assertEquals("pi-started", model.boundIdFor(pending.key))
        assertEquals(1, model.entries(AgentType.PI).size)
    }

    @Test
    fun `取消已绑定 pending 不影响真实会话并返回 false`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val pending = model.registerPending(AgentType.CLAUDE, sessionId = "真实id")

        scanResult = listOf(session("真实id", AgentType.CLAUDE, base.plusSeconds(10)))
        model.refresh()

        val removed = model.cancelPending(pending.key)

        assertFalse(removed)
        assertEquals(
            listOf("真实id"),
            model.entries(AgentType.CLAUDE).map { (it as ListEntry.Existing).session.id },
        )
    }

    @Test
    fun `真实会话 id 调用取消时返回 false 且保留会话`() {
        val clock = FakeClock(base)
        val model = model(clock)

        scanResult = listOf(session("真实id", AgentType.CLAUDE, base.plusSeconds(10)))
        model.refresh()

        val removed = model.cancelPending("真实id")

        assertFalse(removed)
        assertEquals(
            listOf("真实id"),
            model.entries(AgentType.CLAUDE).map { (it as ListEntry.Existing).session.id },
        )
    }

    @Test
    fun `取消不存在的 key 返回 false 且无副作用`() {
        val model = model(FakeClock(base))
        model.registerPending(AgentType.CLAUDE)

        val removed = model.cancelPending("missing")

        assertFalse(removed)
        assertEquals(1, model.entries(AgentType.CLAUDE).size)
    }

    @Test
    fun `仅成功取消 pending 时通知监听者`() {
        val model = model(FakeClock(base))
        val pending = model.registerPending(AgentType.CLAUDE)
        var notified = 0
        model.addListener { notified++ }

        assertTrue(model.cancelPending(pending.key))
        assertEquals(1, notified)

        assertFalse(model.cancelPending(pending.key))
        assertEquals(1, notified)
    }

    /**
     * 绑定发生后，终端仍记在 openNew 时的合成 key 下，与会话真实 id 对不上。
     * 后果：绑定后的会话不显示运行中标识，再次点击还会重开一个终端。
     * 所以模型必须把「哪个 pending 绑到了哪个 id」交出去，让终端宿主换 key。
     */
    @Test
    fun `绑定发生时产出待处理的换 key 信息`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val pending = model.registerPending(AgentType.CLAUDE, sessionId = "真实id")

        scanResult = listOf(session("真实id", AgentType.CLAUDE, base.plusSeconds(10)))
        model.refresh()

        assertEquals(listOf(pending.key to "真实id"), model.drainNewBindings())
    }

    @Test
    fun `换 key 信息取走后不再重复产出`() {
        val clock = FakeClock(base)
        val model = model(clock)
        model.registerPending(AgentType.CLAUDE, sessionId = "真实id")

        scanResult = listOf(session("真实id", AgentType.CLAUDE, base.plusSeconds(10)))
        model.refresh()
        assertEquals(1, model.drainNewBindings().size)
        model.refresh()

        assertTrue(model.drainNewBindings().isEmpty())
    }

    @Test
    fun `上一轮还在、这一轮没了的会话报成已消失，取走即清空`() {
        val model = model(FakeClock(base))
        scanResult = listOf(session("a", AgentType.CODEX, base), session("b", AgentType.CODEX, base))
        model.refresh()
        assertEquals(emptyList<String>(), model.drainRemovedSessions())

        scanResult = listOf(session("b", AgentType.CODEX, base))
        model.refresh()

        assertEquals(listOf("a"), model.drainRemovedSessions())
        assertEquals(emptyList<String>(), model.drainRemovedSessions())
    }

    /** 扫描在后台跑：新会话刚标上未读、扫描结果里还没有它，不能当成消失，否则刚标的未读被误清。 */
    @Test
    fun `从没被扫描到过的会话不算消失，它刚标上的未读保留`() {
        val model = model(FakeClock(base))
        val tracker = com.github.izerui.imux.monitor.UnreadTracker({}, {}, {})
        scanResult = listOf(session("a", AgentType.CODEX, base))
        model.refresh()

        // 后台扫描开始时新会话文件还没写出来；扫描期间它第一轮跑完、标上未读
        tracker.markUnread("new")
        scanResult = listOf(session("a", AgentType.CODEX, base))
        model.refresh()
        tracker.forget(model.drainRemovedSessions())

        assertTrue(tracker.isUnread("new"))
    }

    @Test
    fun `没有绑定时换 key 信息为空`() {
        val model = model(FakeClock(base))
        scanResult = listOf(session("无人认领", AgentType.CLAUDE, base))
        model.refresh()

        assertTrue(model.drainNewBindings().isEmpty())
    }

    /**
     * 扫描结果没变化时不该通知——每次通知都会重建整棵树。
     * 工具窗口状态变化等事件会频繁触发刷新，无谓重建是卡顿来源之一。
     */
    @Test
    fun `扫描结果未变化时不重复通知`() {
        val model = model(FakeClock(base))
        var notified = 0
        model.addListener { notified++ }

        scanResult = listOf(session("a", AgentType.CLAUDE, base))
        model.refresh()
        assertEquals("首次有变化，应通知", 1, notified)

        model.refresh()
        model.refresh()

        assertEquals("结果相同，不应再通知", 1, notified)
    }

    @Test
    fun `扫描结果变化时恢复通知`() {
        val model = model(FakeClock(base))
        var notified = 0
        model.addListener { notified++ }

        scanResult = listOf(session("a", AgentType.CLAUDE, base))
        model.refresh()
        scanResult = listOf(session("a", AgentType.CLAUDE, base), session("b", AgentType.CODEX, base))
        model.refresh()

        assertEquals(2, notified)
    }

    @Test
    fun `有待绑定的 pending 时即使扫描结果不变也要通知`() {
        val clock = FakeClock(base)
        val model = model(clock)
        scanResult = listOf(session("a", AgentType.CLAUDE, base))
        model.refresh()

        var notified = 0
        model.addListener { notified++ }
        model.registerPending(AgentType.CLAUDE)

        assertEquals("新建 pending 必须立刻反映到界面", 1, notified)
    }

    /**
     * 契约是「有变化才通知」而非「每次 refresh 都通知」——
     * 无谓通知会导致整棵树重建，是卡顿来源。空扫描且无 pending 时不该通知。
     */
    @Test
    fun `refresh 带来变化时通知监听者`() {
        val model = model(FakeClock(base))
        var notified = 0
        model.addListener { notified++ }

        model.refresh()
        assertEquals("空结果且无 pending，无变化不通知", 0, notified)

        scanResult = listOf(session("新出现", AgentType.CLAUDE, base))
        model.refresh()

        assertEquals(1, notified)
    }

    @Test
    fun `entries 按类型分流且保持仓库给出的顺序`() {
        val model = model(FakeClock(base))
        scanResult = listOf(
            session("c1", AgentType.CLAUDE, base.plusSeconds(30)),
            session("x1", AgentType.CODEX, base.plusSeconds(20)),
            session("c2", AgentType.CLAUDE, base.plusSeconds(10)),
        )
        model.refresh()

        assertEquals(
            listOf("c1", "c2"),
            model.entries(AgentType.CLAUDE).map { (it as ListEntry.Existing).session.id },
        )
        assertEquals(
            listOf("x1"),
            model.entries(AgentType.CODEX).map { (it as ListEntry.Existing).session.id },
        )
    }

    @Test
    fun `会话只改了标题也通知监听者：依赖标题显示的界面靠它刷新`() {
        val model = model(FakeClock(base))
        val original = session("s1", AgentType.CLAUDE, base)
        scanResult = listOf(original)
        model.refresh()
        var notified = 0
        model.addListener { notified++ }
        model.refresh()
        assertEquals("扫描结果没变不通知", 0, notified)
        scanResult = listOf(original.copy(title = "改过的标题"))
        model.refresh()
        assertEquals(1, notified)
    }
}
