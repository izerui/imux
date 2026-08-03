package com.github.liuyuhua.imux.session

import com.github.liuyuhua.imux.model.AgentSession
import com.github.liuyuhua.imux.model.AgentType
import org.junit.Assert.assertEquals
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
        AgentSession(id, "标题-$id", type, at)

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
    fun `晚于启动时刻出现的同类型新会话被绑定`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val pending = model.registerPending(AgentType.CLAUDE)

        clock.now = base.plusSeconds(30)
        scanResult = listOf(session("新id", AgentType.CLAUDE, base.plusSeconds(20)))
        model.refresh()

        assertEquals("新id", model.boundIdFor(pending.key))
        val entries = model.entries(AgentType.CLAUDE)
        assertEquals(1, entries.size)
        assertTrue("绑定后应显示为真实会话", entries[0] is ListEntry.Existing)
    }

    @Test
    fun `早于启动时刻的会话不被绑定`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val pending = model.registerPending(AgentType.CLAUDE)

        scanResult = listOf(session("老id", AgentType.CLAUDE, base.minusSeconds(60)))
        model.refresh()

        assertNull(model.boundIdFor(pending.key))
        assertEquals(2, model.entries(AgentType.CLAUDE).size)
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

    @Test
    fun `多个同类型 pending 竞争时绑定到启动时刻最晚的那个`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val early = model.registerPending(AgentType.CLAUDE)
        clock.now = base.plusSeconds(10)
        val late = model.registerPending(AgentType.CLAUDE)

        clock.now = base.plusSeconds(40)
        scanResult = listOf(session("新id", AgentType.CLAUDE, base.plusSeconds(30)))
        model.refresh()

        assertEquals("新id", model.boundIdFor(late.key))
        assertNull(model.boundIdFor(early.key))
    }

    @Test
    fun `已绑定的 pending 不再参与后续绑定`() {
        val clock = FakeClock(base)
        val model = model(clock)
        val pending = model.registerPending(AgentType.CLAUDE)

        scanResult = listOf(session("第一个", AgentType.CLAUDE, base.plusSeconds(10)))
        model.refresh()
        scanResult = scanResult + session("第二个", AgentType.CLAUDE, base.plusSeconds(20))
        model.refresh()

        assertEquals("第一个", model.boundIdFor(pending.key))
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
    fun `refresh 后通知监听者`() {
        val model = model(FakeClock(base))
        var notified = 0
        model.addListener { notified++ }

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
}
